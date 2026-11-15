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
 * 补推整表尤其不能省：它覆盖「上次改设置时 root 还没授权」和「从旧版本升上来、
 * Settings.Global 里还没有快照」两种情况，漏了新装/升级用户会以为开关全不生效。
 *
 * v1.9.4-lull.10：Android 15（API 35）起 targetSdk≥35 强制 edge-to-edge，裸
 * WebView 不处理 WindowInsets 会把网页画到状态栏后面 —— 状态栏里的 HUD 数值
 * 与网页顶部控件挤成一团。这里用 FrameLayout 根布局显式吃掉 systemBars insets。
 */
@SuppressLint("SetJavaScriptEnabled", "NewApi")
class WebUiActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: NFPrefsBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bridge = NFPrefsBridge(applicationContext)
        // 旧版全量写回把 name_mod_* 写成了空串，MonitorView 会因此不画图标。
        // 升级后先清掉这些脏键，图标才会恢复项目默认。
        bridge.migrate()

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
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(0, bars.top, 0, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    val top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    val bottom = insets.systemWindowInsetBottom
                    v.setPadding(0, top, 0, bottom)
                }
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
        webView.destroy()
        super.onDestroy()
    }
}
