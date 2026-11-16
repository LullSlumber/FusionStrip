package com.jj.nexusfloat.webui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import com.jj.nexusfloat.collector.GpuCollectorWorker
import com.jj.nexusfloat.service.GpuCollectorLauncher
import com.jj.nexusfloat.stats.StatsService

/**
 * WebUI 主界面（替代原 Compose MainActivity 成为 LAUNCHER）。
 *
 * 除了显示网页，还承接原 MainActivity 里跟界面无关、但 HUD 依赖的职责：
 *  - onCreate：清理旧版写坏的配置 + 统计功能开着就确保采样服务在跑（原 :223-225）
 *  - onStart：声明 UI 需要 GPU 采集、拉起采集、补推一次整表快照（原 :502-507）
 *  - onStop：撤掉 UI 采集需求 + 把防抖窗口里没发完的 publish 补发出去
 *
 * v1.9.4-lull.11：状态栏 / 导航栏改真透明（edge-to-edge）。
 *  - 窗口两张栏都设 TRANSPARENT，API 29+ 再关掉系统自带的导航栏对比度遮罩，
 *    否则手势条区域会蒙一层灰底，做不到纯透明。
 *  - WebView 不再留 inset padding，直接全屏铺到两张栏下面；系统栏 insets 通过
 *    window.NFInsets(top,bottom) 转发给页面，页面用 CSS 变量给顶栏 / 底栏让位，
 *    顶栏和底部导航自身做 MD3 半透明 + 模糊，内容从它们底下滚过去。
 */
@SuppressLint("SetJavaScriptEnabled", "NewApi")
class WebUiActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: NFPrefsBridge

    /** 最近一次收到的系统栏 insets，页面加载完成后要补发（首派发时页面 JS 还没跑起来） */
    private var pendingTop = 0
    private var pendingBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bridge = NFPrefsBridge(applicationContext)
        // 旧版全量写回把 name_mod_* 写成了空串，MonitorView 会因此不画图标。
        // 升级后先清掉这些脏键（本地 + Remote），图标才会恢复项目默认。
        bridge.migrate()
        bridge.attachActivity(this)

        applyTransparentBars()

        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true       // WebUI 的 ui 偏好仍存 localStorage
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            // 页面自包含（CSS/JS 全内联），不需要跨文件 file:// 访问
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            addJavascriptInterface(bridge, "NF")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean {
                    // 只放行 assets 内的 file:// 导航。NF 桥接可以写设置，
                    // 绝不能让页面跳到任意外链后仍持有这个对象。
                    return request.url?.scheme != "file"
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    // 首次 insets 派发发生在首次 layout，那时页面脚本还没执行，
                    // window.NFInsets 调用会落空 —— 页面就绪后必须补发一次。
                    sendInsets(pendingTop, pendingBottom)
                }
            }
        }

        val root = FrameLayout(this).apply {
            // WebView 全屏铺满（内容画到透明状态栏 / 导航栏下面），
            // 安全区让页面自己处理 —— insets 直接转发给 window.NFInsets
            setOnApplyWindowInsetsListener { _, insets ->
                val top: Int
                val bottom: Int
                // insets 是物理像素，页面 CSS 用的是逻辑像素（dp）：
                // 直接透传会在高密度屏上把 --nf-sat/--nf-sab 放大 density 倍，
                // 顶栏/底栏让位区域变成天大的空白（lull.15 及之前的真机截图实锤）。
                val density = resources.displayMetrics.density
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    top = (bars.top / density).toInt()
                    bottom = (bars.bottom / density).toInt()
                } else {
                    @Suppress("DEPRECATION")
                    top = (insets.systemWindowInsetTop / density).toInt()
                    @Suppress("DEPRECATION")
                    bottom = (insets.systemWindowInsetBottom / density).toInt()
                }
                pendingTop = top
                pendingBottom = bottom
                sendInsets(top, bottom)
                insets
            }
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(root)
        webView.loadUrl("file:///android_asset/webui/index.html")

        StatsService.startIfEnabled(this)
    }

    /** 把系统栏 insets 转发给页面（页面用它设置 CSS 变量给顶栏 / 底栏让位） */
    private fun sendInsets(top: Int, bottom: Int) {
        webView.evaluateJavascript(
            "window.NFInsets&&NFInsets($top,$bottom)", null
        )
    }

    /** 供桥接层把异步结果回推给页面；evaluateJavascript 必须在主线程调用 */
    fun runJs(script: String) {
        runOnUiThread {
            try {
                webView.evaluateJavascript(script, null)
            } catch (t: Throwable) {
                // Activity 已销毁 / WebView 已 destroy 时丢弃结果即可
            }
        }
    }

    /** 两张系统栏都改透明；API 29+ 顺手关掉系统的对比度遮罩 */
    private fun applyTransparentBars() {
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        // targetSdk 35 强制 edge-to-edge，这里对低版本也对齐同一行为
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    /**
     * 页面切浅色主题时把系统栏图标换成深色，不然白图标配浅背景看不见。
     * 深色主题（默认）传 false，保持白色图标。
     *
     * 用平台 insetsController（API 30+）：外观常量定义在
     * android.view.WindowInsetsController 上（WindowInsets 只有嵌套的 Type 类），
     * 平台 API 语义清晰，一加 13T（Android 15+）必然走这个分支。
     * 更老的设备跳过 —— 默认深色主题本来就配白色图标。
     */
    fun setSystemBarsLight(light: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val controller = window.insetsController ?: return
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        controller.setSystemBarsAppearance(if (light) mask else 0, mask)
    }

    override fun onStart() {
        super.onStart()
        GpuCollectorWorker.setUiDemand(true)
        GpuCollectorLauncher.startInProcess()
        // 走桥接层统一发布：后台 fork settings 命令，并记录成功与否给状态卡看
        bridge.publish()
    }

    override fun onStop() {
        GpuCollectorWorker.setUiDemand(false)
        bridge.flush()
        super.onStop()
    }

    override fun onDestroy() {
        bridge.attachActivity(null)
        webView.destroy()
        super.onDestroy()
    }
}
