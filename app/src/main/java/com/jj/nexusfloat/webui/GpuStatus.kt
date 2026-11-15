package com.jj.nexusfloat.webui

import android.content.Context
import com.jj.nexusfloat.App
import com.jj.nexusfloat.collector.GpuRootReader
import com.jj.nexusfloat.collector.SysfsReader
import com.jj.nexusfloat.constant.Constants
import org.json.JSONObject

/**
 * WebUI 状态卡的实时 GPU 读数。
 *
 * 取数逻辑搬自 ui/MainActivity.kt 的 refreshStatus()（:1127-1184），抽成不依赖
 * Compose / Activity 的纯函数，供 NF.gpuInfo() 每秒调一次。
 *
 * 四级回退跟原界面保持一致：root 读 sysfs → 直读 sysfs → RemotePreferences →
 * 本地 perf 表。原界面的「已连接」判据也照搬：XposedService 可用，或者本地
 * 快照里确实有 updated_at（有些机型只注入 SystemUI，服务永远绑不上）。
 */
object GpuStatus {

    fun read(context: Context): String {
        val prefs = context.getSharedPreferences(
            Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE
        )

        // 先试 root 通道（高通 KGSL 必需），读不到再试直读
        var localFreq = GpuRootReader.readFreqMhz()
        if (localFreq <= 0) localFreq = SysfsReader.direct().readGpuFreqMhz()
        var localUsage = GpuRootReader.readUsagePercent()
        if (localUsage < 0) localUsage = SysfsReader.direct().readGpuUsagePercent()

        var remoteFreq = 0
        var remoteUsage = -1
        var updated = 0L

        val svc = App.getXposedService()
        val providerAlive = try {
            prefs.getLong(Constants.Remote.KEY_UPDATED_AT, 0L) > 0L
        } catch (e: Exception) {
            false
        }

        if (svc != null) {
            try {
                val remote = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                remoteFreq = remote.getInt(Constants.Remote.KEY_GPU_FREQ_MHZ, 0)
                remoteUsage = remote.getInt(Constants.Remote.KEY_GPU_USAGE_PERCENT, -1)
                updated = remote.getLong(Constants.Remote.KEY_UPDATED_AT, 0L)
            } catch (e: Exception) {
                // Remote 拿不到就往下走本地，跟原界面一样静默降级
            }
        }
        if (remoteFreq <= 0) {
            remoteFreq = try {
                prefs.getInt(Constants.Remote.KEY_GPU_FREQ_MHZ, 0)
            } catch (e: Exception) {
                0
            }
        }
        if (remoteUsage < 0) {
            remoteUsage = try {
                prefs.getInt(Constants.Remote.KEY_GPU_USAGE_PERCENT, -1)
            } catch (e: Exception) {
                -1
            }
        }
        if (updated <= 0L) {
            updated = try {
                prefs.getLong(Constants.Remote.KEY_UPDATED_AT, 0L)
            } catch (e: Exception) {
                0L
            }
        }

        val finalFreq = if (remoteFreq > 0) remoteFreq else localFreq
        val finalUsage = if (remoteUsage >= 0) remoteUsage else localUsage

        val source = when {
            updated > 0L -> {
                val diffSec = maxOf(0L, (System.currentTimeMillis() - updated) / 1000)
                "上次同步：$diffSec 秒前 (Remote)"
            }
            localFreq > 0 || localUsage >= 0 -> "数据源：本机直读 sysfs"
            else -> "上次同步：从未 (请检查 root 授权)"
        }

        return JSONObject()
            .put("freqMhz", finalFreq)
            .put("usagePercent", finalUsage)
            .put("updatedAt", updated)
            .put("connected", svc != null || providerAlive)
            .put("source", source)
            .toString()
    }
}
