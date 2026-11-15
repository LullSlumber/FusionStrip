package com.jj.nexusfloat.webui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.jj.nexusfloat.App
import com.jj.nexusfloat.bridge.SettingsChannel
import com.jj.nexusfloat.constant.Constants
import com.jj.nexusfloat.stats.StatsService
import com.jj.nexusfloat.utils.LogUtils
import com.jj.nexusfloat.utils.RootShell
import org.json.JSONObject

/**
 * WebUI ↔ 模块设置表的桥接层，注入名 "NF"。
 *
 * 写入严格沿用仓库原有机制：本地 SharedPreferences("perf") + RemotePreferences
 * 双写，再 SettingsChannel.publish() 推整表到 Settings.Global。SystemUI 侧
 * 「Remote → PrefsSnapshot → SettingsChannel」逐键回退，三条通道因此天然一致。
 *
 * v1.9.4-lull.10 修正两处会直接改坏 HUD 的问题：
 *  1. 前端改成只提交 diff，不再把整套 DEFAULTS 全量灌进 perf 表。全量写回会
 *     覆盖用户原有设置，且早期 DEFAULTS 把 name_mod_* 定成空串，而
 *     MonitorView.applyName() 遇到空名会把矢量图标和名称一起 GONE —— 状态栏
 *     就只剩一串裸数值。commit() 现在额外把「空名称键」当成「删掉该键」，
 *     让 NexusBridge 回退到 Constants.Modules.DEFAULT_NAMES，图标自然回来。
 *  2. 本地写盘由 apply() 换成 commit()：SystemUI 重启时可能把 App 进程一起拉起，
 *     apply() 异步落盘会导致新进程读到旧 xml。设置改动频率很低，同步写更稳。
 */
class NFPrefsBridge(private val context: Context) {

    companion object {
        private const val TAG = "NFPrefsBridge"
        private const val PUBLISH_DEBOUNCE_MS = 150L
        private const val NAME_PREFIX = "name_"

        /**
         * WebUI 认识的 58 个键（对齐 app.js 的 DEFAULTS）。
         * perf 表里还有 gpu_freq_mhz / gpu_usage_pct / updated_at 等采集数据，
         * 它们不属于设置项：dump 不回读，commit 也不接受。
         */
        private val KNOWN_KEYS = setOf(
            // 显示时机
            "show_portrait", "show_landscape", "show_screen_off", "show_charging_only",
            "app_filter", "app_whitelist",
            // 模块开关
            "mod_clock", "mod_clock_24h", "mod_cpu", "mod_cpu_freq",
            "mod_gpu", "mod_gpu_freq", "mod_ram", "mod_zram",
            "mod_temp", "mod_power", "mod_current", "mod_fps",
            "mod_cpu_temp", "mod_net",
            // 自定义名称
            "name_mod_cpu", "name_mod_gpu", "name_mod_ram", "name_mod_zram",
            "name_mod_temp", "name_mod_power", "name_mod_current", "name_mod_fps",
            "name_mod_cpu_temp", "name_mod_net",
            // 顺序与空格
            "module_order", "space_before",
            // 外观
            "cpu_bar", "gpu_bar", "mod_background", "auto_contrast",
            "text_color", "font_size_sp", "font_bold", "spacing_dp",
            "offset_x", "offset_y",
            // 采样 / 电源
            "update_interval_ms", "wake_interval_sec", "dual_cell",
            // FPS 来源
            "fps_src_panel", "fps_src_sf_latency", "fps_src_sf_timestats",
            "fps_src_fpsgo", "fps_src_ged", "fps_src_vsync",
            "sf_prefer_root", "fps_debug",
            // 统计
            "stats_enabled", "stats_interval_sec", "stats_notification", "stats_screen_on_only",
            // 仅界面
            "app_theme",
        )

        /** App 侧用 readFloat() 读的键，其余数字一律 readInt()（对齐 MainActivity） */
        private val FLOAT_KEYS = setOf("font_size_sp", "spacing_dp")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingPublish: Runnable? = null

    @Volatile private var lastPublishOk = false
    @Volatile private var lastPublishAt = 0L

    private fun prefs() = context.getSharedPreferences(
        Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE
    )

    /**
     * 回读整表：只返回设置项，值保持 JSON 原生类型，前端直接覆盖 DEFAULTS。
     * 值为空的 name_* 键跳过 —— 那是旧版 WebUI 写坏的脏数据，交给前端用项目默认名。
     */
    @JavascriptInterface
    fun dump(): String {
        val state = JSONObject()
        try {
            for ((k, v) in prefs().getAll()) {
                if (!KNOWN_KEYS.contains(k)) continue
                if (k.startsWith(NAME_PREFIX) && v is String && v.isEmpty()) continue
                when (v) {
                    is Boolean -> state.put(k, v)
                    is Int -> state.put(k, v)
                    is Float -> state.put(k, v.toDouble())
                    is Long -> state.put(k, v.toInt())
                    is String -> state.put(k, v)
                }
            }
        } catch (t: Throwable) {
            LogUtils.w("$TAG dump failed", t)
        }
        return JSONObject().put("state", state).toString()
    }

    /**
     * 增量写设置。前端只传发生变化的键；值为 null 表示「恢复项目默认」，
     * 这里直接删键，让 NexusBridge 走 Constants 里的内置默认值。
     */
    @JavascriptInterface
    fun commit(json: String): Boolean {
        return try {
            val obj = JSONObject(json)
            val local = prefs()
            val edit = local.edit()

            val svc = App.getXposedService()
            val remoteEdit = try {
                svc?.getRemotePreferences(Constants.Remote.PREFS_NAME)?.edit()
            } catch (e: Exception) {
                null
            }

            // stats_enabled 不只是存个值：原 MainActivity.applyStatsEnabled() 会
            // 立刻启停采样服务。只在值真的变了时才做。
            val statsKey = Constants.Stats.KEY_ENABLED
            val statsBefore = try {
                local.getBoolean(statsKey, true)
            } catch (e: Exception) {
                true
            }
            val statsAfter = when {
                !obj.has(statsKey) -> statsBefore
                obj.isNull(statsKey) -> true          // 项目默认值
                else -> obj.optBoolean(statsKey, statsBefore)
            }

            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!KNOWN_KEYS.contains(k)) continue
                val raw = obj.get(k)

                // null → 删键（恢复项目默认）；空名称 → 同样删键（否则图标消失）
                val remove = raw === JSONObject.NULL ||
                        (k.startsWith(NAME_PREFIX) && raw is String && raw.isEmpty())
                if (remove) {
                    edit.remove(k)
                    remoteEdit?.remove(k)
                    continue
                }

                when (raw) {
                    is Boolean -> {
                        edit.putBoolean(k, raw)
                        remoteEdit?.putBoolean(k, raw)
                    }
                    is String -> {
                        // app_whitelist 保持前端传来的换行分隔，不做空格替换
                        edit.putString(k, raw)
                        remoteEdit?.putString(k, raw)
                    }
                    is Number -> {
                        if (FLOAT_KEYS.contains(k)) {
                            val f = raw.toFloat()
                            edit.putFloat(k, f)
                            remoteEdit?.putFloat(k, f)
                        } else {
                            val i = raw.toInt()
                            edit.putInt(k, i)
                            remoteEdit?.putInt(k, i)
                        }
                    }
                }
            }
            // 同步落盘：SystemUI 重启可能连带重启本进程，apply() 异步写盘会读到旧值
            edit.commit()
            try {
                remoteEdit?.commit()
            } catch (e: Exception) {
                LogUtils.w("$TAG remote prefs write failed", e)
            }

            if (statsAfter != statsBefore) {
                if (statsAfter) StatsService.startIfEnabled(context) else StatsService.stop(context)
            }
            schedulePublish()
            true
        } catch (t: Throwable) {
            LogUtils.w("$TAG commit failed", t)
            false
        }
    }

    /**
     * 一次性清理旧版 WebUI 写进 perf 表的空名称键。
     * 不清理的话，即使前端改成增量提交，已经落盘的空串仍会让 HUD 一直没图标。
     */
    fun migrate() {
        try {
            val local = prefs()
            val emptyNames = local.getAll()
                .filter { (k, v) -> k.startsWith(NAME_PREFIX) && v is String && v.isEmpty() }
                .map { it.key }
            if (emptyNames.isEmpty()) return

            val edit = local.edit()
            emptyNames.forEach { edit.remove(it) }
            edit.commit()

            try {
                App.getXposedService()
                    ?.getRemotePreferences(Constants.Remote.PREFS_NAME)?.edit()?.let { re ->
                        emptyNames.forEach { re.remove(it) }
                        re.commit()
                    }
            } catch (e: Exception) {
                LogUtils.w("$TAG remote cleanup failed", e)
            }
            // 不在这里单独 publish：Activity 的 onStart 紧接着会补推整表，
            // 两个线程同时 fork settings 命令没必要，也省一次 root shell 争用。
            LogUtils.i("$TAG migrated ${emptyNames.size} empty name keys")
        } catch (t: Throwable) {
            LogUtils.w("$TAG migrate failed", t)
        }
    }

    /** 前端点「应用设置」时立即发布；取消挂着的防抖任务，避免重复 fork */
    @JavascriptInterface
    fun publish(): Boolean {
        cancelPending()
        Thread { publishNow() }.start()
        return true
    }

    /** Activity 退到后台时补一次发布，别把防抖窗口里的改动丢掉 */
    fun flush() {
        if (pendingPublish == null) return
        cancelPending()
        Thread { publishNow() }.start()
    }

    /** 三条通道的实际状态，前端状态卡用来判断「改了为什么不生效」 */
    @JavascriptInterface
    fun diag(): String {
        val o = JSONObject()
        try { o.put("remote", App.getXposedService() != null) }
        catch (t: Throwable) { o.put("remote", false) }
        try { o.put("root", !RootShell.get().isDead()) }
        catch (t: Throwable) { o.put("root", false) }
        try {
            o.put("localKeys", prefs().getAll().size)
            val raw = Settings.Global.getString(
                context.contentResolver, Constants.Component.SETTINGS_KEY
            )
            o.put("globalChars", raw?.length ?: 0)
        } catch (t: Throwable) {
            o.put("localKeys", -1)
            o.put("globalChars", -1)
        }
        o.put("lastPublishOk", lastPublishOk)
        o.put("lastPublishAt", lastPublishAt)
        return o.toString()
    }

    @JavascriptInterface
    fun installedApps(includeAll: Boolean): String =
        InstalledApps.load(context, includeAll)

    @JavascriptInterface
    fun gpuInfo(): String = GpuStatus.read(context)

    @JavascriptInterface
    fun toast(message: String) {
        mainHandler.post {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                // Toast 在后台进程被限时不该让桥接崩
            }
        }
    }

    private fun schedulePublish() {
        cancelPending()
        val task = Runnable {
            pendingPublish = null
            publishNow()
        }
        pendingPublish = task
        mainHandler.postDelayed(task, PUBLISH_DEBOUNCE_MS)
    }

    private fun cancelPending() {
        pendingPublish?.let { mainHandler.removeCallbacks(it) }
        pendingPublish = null
    }

    private fun publishNow(): Boolean {
        // settings put global 要 fork 一次 app_process，必须离主线程远一点
        val ok = try {
            SettingsChannel.publish(context.applicationContext)
        } catch (t: Throwable) {
            LogUtils.w("$TAG publish failed", t)
            false
        }
        lastPublishOk = ok
        lastPublishAt = System.currentTimeMillis()
        if (!ok) {
            LogUtils.w("$TAG publish NOT applied (no root shell?)")
        }
        return ok
    }
}
