/*
 * C 方案包装 View：用 DroidDeckHudView 完全替换 MonitorView 的渲染。
 *
 * 对外穷举实现 com.jj.nexusfloat.ui.view.MonitorView 的全部公开方法（类型签名一致），
 * 内部持有 DroidDeckHudView（STRIP 尺寸、背景不透明度 88、各显示开关全开）。
 * 数据面：PerformanceCollector 每拍回调 updateData()，在这里组装
 * HudMetrics.Snapshot 经 DroidDeckHudView.snapshotProvider 注入；帧率经
 * FpsBridgeFrameStats（setFrameStats）桥接。DroidDeck 的主题、配色、字号
 * 缩放由其自身配置接管，MonitorView 的外观类开关大多不适用（见各方法注释）。
 */
package com.jj.nexusfloat.hud

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.droiddeck.launcher.hud.DroidDeckHudConfig
import com.droiddeck.launcher.hud.DroidDeckHudView
import com.droiddeck.launcher.hud.SnapshotFactory
import com.jj.nexusfloat.bridge.FpsSyncBridge
import com.jj.nexusfloat.collector.PerformanceCollector
import com.jj.nexusfloat.constant.Constants

class FusionStripView(context: Context) : FrameLayout(context) {

    private val hudView = DroidDeckHudView(context)
    private val fpsBridge = FpsBridgeFrameStats()

    @Volatile
    private var latestSnapshot: com.droiddeck.launcher.hud.HudMetrics.Snapshot? = null

    private var collector: PerformanceCollector? = PerformanceCollector(context, this::updateData)

    /** 用户字号（sp），DroidDeck 无对应配置键，仅存档（见 setFontSizeSp 注释）。 */
    @Volatile
    private var fontSizeSp = 0f

    init {
        addView(hudView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        hudView.setFrameStats(fpsBridge)
        hudView.snapshotProvider = { latestSnapshot }
        applyDefaultConfig()
    }

    /** 默认配置：STRIP 尺寸、背景不透明度 88、显示开关全开；无数据源的行默认关。 */
    private fun applyDefaultConfig() {
        val values = DroidDeckHudConfig.load(context)
        values["hudSize"] = SIZE_STRIP
        values["hudOpacity"] = DEFAULT_OPACITY.toString()
        for (key in ALWAYS_ON) values[key] = "1"
        // NexusFloat 数据面没有这些来源，对应 Snapshot 字段恒为 null，开了只会渲染 "—"
        values["showNet"] = "0"
        values["showSwap"] = "0"
        values["showLatency"] = "0"
        values["showDisplay"] = "0"
        values["showHdr"] = "0"
        values["showProton"] = "0"
        values["showSession"] = "0"
        values["showResolution"] = "0"
        hudView.applyConfig(DroidDeckHudConfig.encode(values))
    }

    private fun currentConfig(): MutableMap<String, String> = DroidDeckHudConfig.load(context)

    private fun apply(values: Map<String, String>) {
        hudView.applyConfig(DroidDeckHudConfig.encode(values))
    }

    // ---- 生命周期：与 MonitorView 相同，采集随 attach/detach 起停 ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        collector?.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        collector?.stop()
    }

    // ---- MonitorView 公开 API 穷举实现 ----

    /**
     * 模块开关 → DroidDeck 配置键映射：
     * IDX_FPS→showFPS、IDX_GPU→showGPULoad、IDX_CPU→showCPUUsage、IDX_RAM→showRAM、
     * IDX_TEMP→showBatteryTemp、IDX_POWER→showPower、IDX_CPU_TEMP→showTemp；
     * IDX_ZRAM 无直接对应（NexusFloat 的 ZRAM 不是 VRAM），按任务约定「ZRAM 开则开 showVram」；
     * IDX_CLOCK/IDX_CLOCK_24H/IDX_CPU_FREQ/IDX_GPU_FREQ/IDX_CURRENT/IDX_NET 无对应，忽略。
     */
    fun setModuleEnabled(enabled: BooleanArray?): Boolean {
        if (enabled == null || enabled.size <= Constants.Modules.IDX_NET) return false
        val values = currentConfig()
        var changed = false
        fun put(key: String, on: Boolean) {
            val v = if (on) "1" else "0"
            if (values[key] != v) { values[key] = v; changed = true }
        }
        put("showFPS", enabled[Constants.Modules.IDX_FPS])
        put("showGPULoad", enabled[Constants.Modules.IDX_GPU])
        put("showCPUUsage", enabled[Constants.Modules.IDX_CPU])
        put("showRAM", enabled[Constants.Modules.IDX_RAM])
        put("showBatteryTemp", enabled[Constants.Modules.IDX_TEMP])
        put("showPower", enabled[Constants.Modules.IDX_POWER])
        put("showTemp", enabled[Constants.Modules.IDX_CPU_TEMP])
        put("showVram", enabled[Constants.Modules.IDX_ZRAM])
        // IDX_CLOCK 等无对应键：DroidDeck 有自己的时钟/网络行，这里不翻译，保持默认
        if (changed) apply(values)
        return changed
    }

    /**
     * DroidDeckHudConfig 没有逐字号键（整体缩放走 hudScale，语义不同），
     * 按任务约定忽略渲染、仅存档 fontSizeSp。
     */
    fun setFontSizeSp(sp: Float): Boolean {
        val changed = fontSizeSp != sp
        fontSizeSp = sp
        return changed
    }

    /** 背景开关 → hudOpacity：开 88，关 0。 */
    fun setBackgroundEnabled(enabled: Boolean): Boolean {
        val values = currentConfig()
        val v = (if (enabled) DEFAULT_OPACITY else 0).toString()
        if (values["hudOpacity"] == v) return false
        values["hudOpacity"] = v
        apply(values)
        return true
    }

    /** 文字颜色由 DroidDeck 主题接管（Palette/Themes），NexusFloat 色号不适用。 */
    fun setTextColorIndex(index: Int) {
        // 由 DroidDeck 主题接管，忽略
    }

    /** 自动反色未接 DroidDeck 主题，见 ModuleMain 中 tintBridge.attach 的处理。 */
    fun setAutoContrastEnabled(enabled: Boolean) {
        // 由 DroidDeck 主题接管，忽略
    }

    /** 字重由 DroidDeck 主题接管。 */
    fun setFontBold(bold: Boolean): Boolean {
        // 由 DroidDeck 主题接管，忽略
        return false
    }

    /** 柱条是 MonitorView 自绘的元件，DroidDeck STRIP 没有对应元件，忽略。 */
    fun setBarsEnabled(cpuBar: Boolean, gpuBar: Boolean): Boolean {
        // DroidDeck 主题无柱条元件，忽略
        return false
    }

    /** DroidDeck 时钟行的制式由系统 DateFormat 决定，忽略。 */
    fun setClock24h(use24h: Boolean) {
        // DroidDeck 主题接管（跟随系统），忽略
    }

    /** FPS 诊断标记是 NexusFloat 渲染层的概念，DroidDeck 无对应开关，忽略。 */
    fun setFpsDebugEnabled(enabled: Boolean): Boolean {
        // 诊断由 FpsSyncBridge/记录页承担，忽略
        return false
    }

    /** 行间距由 DroidDeck STRIP 样式固定，忽略。 */
    fun setSpacingDp(dp: Float): Boolean {
        // DroidDeck STRIP 样式固定，忽略
        return false
    }

    /** 显示时机切换：横条隐藏时 DroidDeckHudView 的自采集循环随之停掉。 */
    fun setCollecting(collecting: Boolean) {
        hudView.visibility = if (collecting) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    /** 采集周期在 PerformanceCollector 上生效（语义不变，只是不再改布局）。 */
    fun setIntervalMs(ms: Int) {
        collector?.setIntervalMs(ms)
    }

    /** 模块自定义名称是 NexusFloat 渲染层的概念，DroidDeck 行名固定，忽略。 */
    fun setModuleNames(names: Array<String?>?): Boolean {
        // DroidDeck STRIP 行名固定，忽略
        return false
    }

    /** DroidDeck STRIP 行顺序固定，忽略。 */
    fun setModuleOrder(order: IntArray?): Boolean {
        // DroidDeck STRIP 顺序固定，忽略
        return false
    }

    /** 模块间距是 NexusFloat 渲染层的概念，忽略。 */
    fun setSpacesBefore(before: IntArray?): Boolean {
        // DroidDeck STRIP 间距固定，忽略
        return false
    }

    /** 「HUD 尺寸」：写入 DroidDeck 配置键 hudSize 并即时重排（STRIP/面板等）。 */
    fun setHudSize(token: String): Boolean {
        val values = currentConfig()
        if (values["hudSize"] == token) return false
        values["hudSize"] = token
        apply(values)
        return true
    }

    /** 「HUD 不透明度」：映射 DroidDeck 背景 alpha，只重绘不重排。 */
    fun setHudOpacity(pct: Int) {
        val values = currentConfig()
        val v = pct.coerceIn(0, 100).toString()
        if (values["hudOpacity"] == v) return
        values["hudOpacity"] = v
        apply(values)
    }

    /**
     * DroidDeck 渲染配置整包同步：App 端把整个 DroidDeckHudConfig 编码后经
     * SettingsChannel 推过来，这里直接 applyConfig（尺寸/项目/透明度全在里面）。
     * 旧的三层同步键（moduleFlags 翻译层）已废弃。
     */
    private var lastCfg: String? = null
    fun setDroidDeckCfg(json: String?) {
        if (json == lastCfg) return
        lastCfg = json
        if (json.isNullOrEmpty()) {
            applyDefaultConfig()
        } else {
            hudView.applyConfig(json)
        }
    }

    /** 强调线：DroidDeck 渲染本体无线元件，由包装层 onDraw 画在底边（贴游戏一侧）。 */
    private var stripLineEnabled = true
    private var stripLineColor = 0xFFFFB74D.toInt()
    private val stripLinePaint = android.graphics.Paint()

    fun setStripLineEnabled(enabled: Boolean) {
        if (stripLineEnabled == enabled) return
        stripLineEnabled = enabled
        invalidate()
    }

    fun setStripLineColorIndex(index: Int) {
        val palette = com.jj.nexusfloat.constant.Constants.Ui.STRIP_LINE_COLORS
        val color = if (index in palette.indices) palette[index] else palette[0]
        if (stripLineColor == color) return
        stripLineColor = color
        stripLinePaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (!stripLineEnabled || width <= 0) return
        val thickness = Math.max(2, Math.round(resources.displayMetrics.density * 1.5f))
        val y = height - thickness.toFloat()
        canvas.drawRect(0f, y, width.toFloat(), height.toFloat(), stripLinePaint)
    }

    /** 高度给退化模式（status_bar 子 View）与悬浮窗高度探测用。 */
    fun getBarHeightPx(): Int {
        val measured = hudView.measuredHeight
        if (measured > 0) return measured
        // 未测量时的估算：STRIP 行高约 11.5sp（DroidDeckHudStyle）加面板内边距，
        // 这里取一个足够容纳单行内容的保守值
        val density = context.resources.displayMetrics.density
        return (24f * density).toInt()
    }

    /**
     * PerformanceCollector 每拍回调。组装 HudMetrics.Snapshot 注入 DroidDeckHudView，
     * 并把 fps 桥进 FpsBridgeFrameStats。映射（NexusFloat → Snapshot）：
     *   cpuTotalUsage→cpuPercent, gpuTotalUsage→gpuPercent,
     *   cpuFreqMaxMhz→cpuClockMhz, gpuFreq→gpuClockMhz,
     *   cpuTemp(非 NaN)→cpuTempC, gpuTempC 无来源→null,
     *   ramUsagePercent→ramPercent（无字节数，used/total 置 0）,
     *   vram/net/swap/perCoreClock 无来源→null,
     *   batteryPowerW(取绝对值，DroidDeck 约定放电为正幅值)→Battery.watts,
     *   isCharging→Battery.charging, batteryTemp→Battery.tempC,
     *   电池百分比/续航文本无来源→null,
     *   cpuCoreUsages→perCorePercent。
     */
    fun updateData(data: PerformanceCollector.PerformanceData) {
        val cpuTempC = if (java.lang.Float.isNaN(data.cpuTemp)) null
        else Math.round(data.cpuTemp)
        val batteryTempC = Math.round(data.batteryTemp)
        val perCore = IntArray(data.cpuCoreUsages.size) {
            Math.round(data.cpuCoreUsages[it])
        }
        latestSnapshot = SnapshotFactory.build(
            cpuPercent = Math.round(data.cpuTotalUsage),
            gpuPercent = data.gpuTotalUsage,
            cpuClockMhz = data.cpuFreqMaxMhz,
            gpuClockMhz = data.gpuFreq,
            cpuTempC = cpuTempC,
            gpuTempC = null,
            ramUsedBytes = 0L,
            ramTotalBytes = 0L,
            ramPercent = data.ramUsagePercent,
            vramUsedBytes = null,
            battery = SnapshotFactory.buildBattery(
                watts = Math.abs(data.batteryPowerW),
                charging = data.isCharging,
                percent = null,
                tempC = batteryTempC,
                runtimeText = null,
            ),
            perCorePercent = perCore,
            perCoreClockMhz = null,
            swapUsedBytes = null,
            swapTotalBytes = null,
            netDownBps = null,
            netUpBps = null,
        )
        // 1% low：NexusFloat 不算，桥内用滚动窗口换算（low1 传 0 触发窗口计算）
        fpsBridge.push(data.fps, 0f, System.nanoTime())
        // 帧率同步给模块进程（记录页），与 MonitorView 行为一致
        FpsSyncBridge.publish(context, data.fps, data.fpsTag)
    }

    private companion object {
        const val SIZE_STRIP = "strip"
        const val DEFAULT_OPACITY = 88
        val ALWAYS_ON = arrayOf(
            "showFPS", "showLow1", "showGPULoad", "showGpuTemp", "showCPUUsage",
            "showTemp", "showVram", "showRAM", "showBattery", "showBatteryTemp",
            "showBatteryTime", "showPower", "showGpuModel",
        )
    }
}
