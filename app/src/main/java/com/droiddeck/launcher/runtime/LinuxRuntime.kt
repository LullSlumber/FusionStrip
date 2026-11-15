/*
 * Adaptation stub for the standalone HUD APK build.
 * Members consumed by hud-port sources:
 *   LinuxRuntime.cpuTempZones()          -> Iterable<File> of zone dirs (MangoReadings reads zone/temp)
 *   LinuxRuntime.gpuLoadSource(): String? (MangoReadings wraps it in File(...))
 *   LinuxRuntime.gpuTempSource(): String?
 *   LinuxRuntime.rootDir(context): File  (DroidDeck shared root; used for the VRAM feed path)
 *
 * cpuTempZones() scans /sys/class/thermal/thermal_zone* keeping zones whose type
 * mentions cpu / x86_pkg, all wrapped in try-catch. If the plain app process cannot
 * read them, the root feed mirrored by HudDataService (session mirror dir) is used
 * instead. GPU sources return the kgsl nodes when readable, else their mirrored
 * copies written by root shell, else null. All reads are try-catch guarded.
 */
package com.droiddeck.launcher.runtime

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File

object LinuxRuntime {
    private const val THERMAL = "/sys/class/thermal"
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    const val GPU_BUSY = "$KGSL/gpu_busy_percentage"
    const val GPU_TEMP = "$KGSL/gpu_temp"

    /** Directory where HudDataService mirrors root-only nodes; set once at service start. */
    @Volatile var mirrorDir: File? = null

    private fun mirror(sessionDirName: String, child: String): File? {
        val root = mirrorDir ?: return null
        return File(File(root, sessionDirName), child).takeIf { it.isFile }
    }

    private fun zoneType(zone: File): String? = runCatching {
        File(zone, "type").readText().trim().lowercase()
    }.getOrNull()

    private fun isCpuZone(type: String): Boolean =
        type.contains("cpu") || type.contains("x86_pkg")

    /** Zones whose type mentions cpu/x86_pkg; prefers the root-fed mirror when sysfs is blocked. */
    fun cpuTempZones(): List<File> {
        val zones = ArrayList<File>()
        val mirrorRoot = mirrorDir?.let { File(File(it, "thermal"), "zones") }
        runCatching {
            if (mirrorRoot?.isDirectory == true) {
                mirrorRoot.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
                    ?.sortedBy { it.name }?.let { zones += it }
            }
        }
        if (zones.isEmpty()) runCatching {
            val dir = File(THERMAL)
            dir.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
                ?.sortedBy { it.name }
                ?.filter { zone -> zoneType(zone)?.let(::isCpuZone) == true }
                ?.let { zones += it }
        }
        return zones
    }

    fun gpuLoadSource(): String? {
        runCatching {
            if (File(GPU_BUSY).canRead()) return GPU_BUSY
        }
        return runCatching { mirror("sys/kgsl-3d0", "perf_now")?.path }.getOrNull()
    }

    fun gpuTempSource(): String? {
        runCatching {
            if (File(GPU_TEMP).canRead()) return GPU_TEMP
        }
        return runCatching { mirror("sys/kgsl-3d0", "gpu_temp")?.path }.getOrNull()
    }

    /** DroidDeck shared root used for feed files (run/droiddeck-hud/...). */
    fun rootDir(context: Context): File = File(context.filesDir, "root").apply { mkdirs() }

    /** True when a root shell is available via libsu (KernelSU / Magisk). */
    fun hasRoot(): Boolean = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
}
