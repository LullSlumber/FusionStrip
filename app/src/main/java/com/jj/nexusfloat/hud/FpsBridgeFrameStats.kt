/*
 * FPS 桥（C 方案第三步）：把 NexusFloat 采集线程每拍算出的帧率喂给
 * DroidDeckHudView 的 FrameStats 接口。
 *
 * FrameStats 原本由 GameFrameFeed 逐帧 add() 驱动（gamescope 帧环），NexusFloat
 * 没有逐帧时间戳，只有每拍一个 fps 值，所以这里覆写各读数方法返回桥接值，
 * add() 置空。1% low：NexusFloat 不算 1% low，桥内用最近 N 拍的滚动窗口做
 * 1 百分位换算（传入显式 low1 > 0 时以传入值为准）。
 */
package com.jj.nexusfloat.hud

import com.droiddeck.launcher.hud.FrameStats
import kotlin.math.min
import kotlin.math.roundToInt

internal class FpsBridgeFrameStats : FrameStats() {

    private val lock = Any()
    private var bridgedFps = 0f
    private var bridgedLow1 = 0f
    private var bridgedAvgFps = 0f
    private var lastPushNs = 0L

    /** 1% low 滚动窗口：每拍约 1s，600 拍 ≈ 10 分钟。 */
    private val fpsHistory = FloatArray(HISTORY)
    private var historyHead = 0
    private var historySize = 0

    fun push(fps: Float, low1: Float, nowNs: Long) {
        synchronized(lock) {
            bridgedFps = maxOf(fps, 0f)
            if (low1 > 0f) {
                bridgedLow1 = low1
            } else {
                pushHistory(bridgedFps)
                bridgedLow1 = percentileLow1()
            }
            if (lastPushNs != 0L && nowNs > lastPushNs && bridgedFps > 0f) {
                val dt = (nowNs - lastPushNs) / 1e9f
                val w = min(dt, 10f)
                if (bridgedAvgFps <= 0f) bridgedAvgFps = bridgedFps
                else bridgedAvgFps = bridgedAvgFps * (1f - w / (w + SMOOTH)) + bridgedFps * (w / (w + SMOOTH))
            }
            lastPushNs = nowNs
        }
    }

    override var fps: Float
        get() = synchronized(lock) { bridgedFps }
        set(value) { synchronized(lock) { bridgedFps = value } }

    override fun lows(nowNs: Long): FrameStats.Lows {
        val low1 = synchronized(lock) { bridgedLow1 }
        // 只有 1% low 有桥接来源；0 在视图里按「无该档」处理（不渲染）
        return FrameStats.Lows(low1, 0f, 0f)
    }

    override fun averageFps(): Float = synchronized(lock) { bridgedAvgFps }

    /** NexusFloat 侧没有帧时间直方图，固定合理值：图幅按 5–40ms 展示。 */
    override fun graphMinMs(): Float = 5f

    override fun graphMaxMs(): Float = 40f

    /** 无逐帧数据可累计，置空。 */
    override fun add(frametimeNs: Long, timeNs: Long) {
        // 由 push() 取代
    }

    private fun pushHistory(fps: Float) {
        fpsHistory[historyHead] = fps
        historyHead = (historyHead + 1) % HISTORY
        if (historySize < HISTORY) historySize++
    }

    /** 最近窗口的 1 百分位（1% low）；样本不足时返回 0。 */
    private fun percentileLow1(): Float {
        if (historySize < MIN_SAMPLES) return 0f
        val sorted = FloatArray(historySize)
        for (i in 0 until historySize) sorted[i] = fpsHistory[i]
        sorted.sort()
        val idx = (0.01 * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private companion object {
        const val HISTORY = 600
        const val MIN_SAMPLES = 15
        /** 平均帧率的平滑常数（秒），等效指数窗口。 */
        const val SMOOTH = 30f
    }
}
