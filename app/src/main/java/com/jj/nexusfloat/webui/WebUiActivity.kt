package com.jj.nexusfloat.webui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jj.nexusfloat.bridge.SettingsChannel
import com.jj.nexusfloat.collector.GpuCollectorWorker
import com.jj.nexusfloat.service.GpuCollectorLauncher
import com.jj.nexusfloat.stats.StatsService

/**
 * WebUI 主界面（替代原 Compose MainActivity 成为 LAUNCHER）。
 *
 * 除了显示网页，还承接原 MainActivity 里跟界面无关、但 HUD 依赖的职责：
 *  - onCreate：统计功能开着就确保采样服务在跑（原 :223-225）
 *  - onStart：声明 UI 需要 GPU 采集、拉起采集、补推一次整表快照（原 :502-507）
 *  - onStop：撤掉 UI 采集需求（原 :511-512）
 *
 * 补推整表尤其不能省：它覆盖「上次改设置时 root 还没授权」和「从旧版本升上来、
 * Settings.Global 里还没有快照」两种情况，漏了新装/升级用户会以为开关全不生效。
 *
 * 不调用 enableEdgeToEdge()：WebUI 的 TopAppBar 没有针对状态栏 insets 做 padding，
 * 让系统按普通窗口留出状态栏更稳，也不会出现内容被挖孔屏盖住。
 */
class WebUiActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: NFPrefsBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bridge = NFPrefsBridge(applicationContext)

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
        setContentView(webView)
        webView.loadUrl("file:///android_asset/webui/index.html")

        StatsService.startIfEnabled(this)
    }

    override fun onStart() {
        super.onStart()
        GpuCollectorWorker.setUiDemand(true)
        GpuCollectorLauncher.startInProcess()
        // 后台线程：publish 要 fork settings 命令，几十毫秒到几秒不等
        Thread { SettingsChannel.publish(applicationContext) }.start()
    }

    override fun onStop() {
        GpuCollectorWorker.setUiDemand(false)
        super.onStop()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
