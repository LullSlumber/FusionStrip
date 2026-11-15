package com.jj.nexusfloat.webui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.jj.nexusfloat.App
import com.jj.nexusfloat.bridge.SettingsChannel
import com.jj.nexusfloat.constant.Constants
import com.jj.nexusfloat.stats.StatsService
import com.jj.nexusfloat.utils.LogUtils
import org.json.JSONObject

/**
 * WebUI ↔ 模块设置表的桥接层，注入名 "NF"。
 *
 * 写入严格沿用仓库原有机制：本地 SharedPreferences("perf") + RemotePreferences
 * 双写，再 SettingsChannel.publish() 推整表到 Settings.Global。SystemUI 侧
 * 「Remote → PrefsSnapshot → SettingsChannel」逐键回退，三条通道因此天然一致，
 * 不需要 root 命令，也不需要 KSU 模块壳。
 *
 * 与早期设计稿不同：dump()/commit() 走 JSON 对象而不是 key=type:value 文本。
 * 理由有两个——(1) 文本格式会在编码时把值里的换行替换成空格，白名单如果直接
 * 这样写回本地 perf 表就跟 App 侧 readWhitelist() 的 "\n" 分隔约定不一致；
 * (2) 值里含 '=' / ':' 时文本要额外转义。JSON 用原生类型，没这些问题。
 * key=type:value 仍保留给前端诊断面板的「复制 settings 命令」展示，不参与写入。
 */
class NFPrefsBridge(private val context: Context) {

    companion object {
        private const val TAG = "NFPrefsBridge"
        private const val PUBLISH_DEBOUNCE_MS = 350L

        /**
         * WebUI 认识的 58 个键（对齐 app.js 的 DEFAULTS）。
         * perf 表里还有 gpu_freq_mhz / gpu_usage_pct / updated_at 等采集数据，
         * 它们不属于设置项：dump 不回读（否则会污染前端 state、被快照多写出去），
         * commit 也不接受（避免网页伪造采集数据）。
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

    private fun prefs() = context.getSharedPreferences(
        Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE
    )

    /** 回读整表：只返回设置项，值保持 JSON 原生类型，前端直接覆盖 DEFAULTS */
    @JavascriptInterface
    fun dump(): String {
        val state = JSONObject()
        try {
            for ((k, v) in prefs().getAll()) {
                if (!KNOWN_KEYS.contains(k)) continue
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
     * 批量写设置。前端每次改动都传全量 state，这里逐键按 App 侧真实 getter 类型
     * 落盘；写完安排一次防抖 publish（滑块连续拖动不会 fork 一串 settings 进程）。
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
            // 立刻启停采样服务。只在值真的变了时才做，避免拖别的滑块也反复 stop。
            val statsBefore = try {
                local.getBoolean(Constants.Stats.KEY_ENABLED, true)
            } catch (e: Exception) {
                true
            }
            val statsAfter = obj.optBoolean(Constants.Stats.KEY_ENABLED, statsBefore)

            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!KNOWN_KEYS.contains(k)) continue
                when (val v = obj.get(k)) {
                    is Boolean -> {
                        edit.putBoolean(k, v)
                        remoteEdit?.putBoolean(k, v)
                    }
                    is String -> {
                        // app_whitelist 保持前端传来的换行分隔，不做空格替换
                        edit.putString(k, v)
                        remoteEdit?.putString(k, v)
                    }
                    is Number -> {
                        if (FLOAT_KEYS.contains(k)) {
                            val f = v.toFloat()
                            edit.putFloat(k, f)
                            remoteEdit?.putFloat(k, f)
                        } else {
                            val i = v.toInt()
                            edit.putInt(k, i)
                            remoteEdit?.putInt(k, i)
                        }
                    }
                }
            }
            edit.apply()
            try {
                remoteEdit?.apply()
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

    /** 前端点「应用」时立即发布；取消挂着的防抖任务，避免重复 fork */
    @JavascriptInterface
    fun publish(): Boolean {
        cancelPending()
        publishNow()
        return true
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

    private fun publishNow() {
        // settings put global 要 fork 一次 app_process，必须离主线程远一点
        Thread { SettingsChannel.publish(context.applicationContext) }.start()
    }
}
