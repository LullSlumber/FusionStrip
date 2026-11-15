/*
 * FusionStrip 移植适配（新文件，非原移植包成员）。
 *
 * HudMetrics.Snapshot / HudMetrics.Battery 的构造器是 Java 包私有，而
 * com.jj.nexusfloat.hud.FusionStripView 需要在 com.jj.nexusfloat.hud 包里每拍构造
 * Snapshot。本工厂与 HudMetrics 同包，负责替调用方 new 出这两个对象；它只收原始
 * 参数，不引用任何 com.jj.nexusfloat.*（保持 droiddeck → nexusfloat 单向引用）。
 */
package com.droiddeck.launcher.hud

object SnapshotFactory {

    fun build(
        cpuPercent: Int?,
        gpuPercent: Int?,
        cpuClockMhz: Int?,
        gpuClockMhz: Int?,
        cpuTempC: Int?,
        gpuTempC: Int?,
        ramUsedBytes: Long,
        ramTotalBytes: Long,
        ramPercent: Float,
        vramUsedBytes: Long?,
        battery: HudMetrics.Battery?,
        perCorePercent: IntArray?,
        perCoreClockMhz: IntArray?,
        swapUsedBytes: Long?,
        swapTotalBytes: Long?,
        netDownBps: Long?,
        netUpBps: Long?,
    ): HudMetrics.Snapshot = HudMetrics.Snapshot(
        cpuPercent, gpuPercent, cpuClockMhz, gpuClockMhz,
        cpuTempC, gpuTempC, ramUsedBytes, ramTotalBytes,
        ramPercent, vramUsedBytes, battery,
        perCorePercent, perCoreClockMhz,
        swapUsedBytes, swapTotalBytes, netDownBps, netUpBps,
    )

    fun buildBattery(
        watts: Float,
        charging: Boolean,
        percent: Int?,
        tempC: Int?,
        runtimeText: String?,
    ): HudMetrics.Battery = HudMetrics.Battery(watts, charging, percent, tempC, runtimeText)
}
