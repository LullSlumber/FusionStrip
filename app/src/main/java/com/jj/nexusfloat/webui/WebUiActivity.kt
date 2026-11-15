package com.jj.nexusfloat.webui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
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
            }
        }

        val root = FrameLayout(this).apply {
            // WebView 全屏铺满（内容画到透明状态栏 / 导航栏下面），
            // 安全区让页面自己处理 —— insets 直接转发给 window.NFInsets
            setOnApplyWindowInsetsListener { _, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    top = bars.top
                    bottom = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottom = insets.systemWindowInsetBottom
                }
                webView.evaluateJavascript(
                    "window.NFInsets&&NFInsets($top,$bottom)", null
                )
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
     */
    fun setSystemBarsLight(light: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val mask = WindowInsetsControllerCompat.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsControllerCompat.APPEARANCE_LIGHT_NAVIGATION_BARS
        controller.setSystemBarsAppearance(
            if (light) mask else 0, mask
        )
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
