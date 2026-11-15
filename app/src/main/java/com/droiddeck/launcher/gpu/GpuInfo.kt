/*
 * Minimal stub for com.droiddeck.launcher.gpu.GpuInfo, referenced verbatim by
 * HudMetrics.buildDiagnosticsReport():
 *   GpuInfo.Companion.detect().displayName(context)
 * The real DroidDeck implementation probes the Wayland compositor and Vulkan
 * driver; here we fall back to Build information only.
 */
package com.droiddeck.launcher.gpu

import android.os.Build
import android.content.Context

class GpuInfo private constructor(private val name: String) {
    fun displayName(context: Context?): String = name

    companion object {
        @JvmStatic
        fun detect(): GpuInfo = GpuInfo(
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Build.SOC_MODEL.isNullOrBlank())
                    Build.SOC_MODEL
                else
                    Build.HARDWARE
            }.getOrNull() ?: "GPU",
        )
    }
}
