package com.jj.nexusfloat;

import android.app.Application;

import com.jj.nexusfloat.bridge.NexusBridge;
import com.jj.nexusfloat.bridge.NexusRemoteWriter;

/**
 * PerfHUD 的 Application：绑好进程内读写通道之后待命。
 *
 * 原版（NexusFloat）是 LSPosed 模块：App 进程采集 GPU、SystemUI 进程渲染监视条，
 * 两边靠 RemotePreferences / ContentProvider / Settings.Global 三条通道传数据。
 * 独立 App 化之后只剩一个进程，读写都在本地 SharedPreferences 上进行，
 * 不再有任何跨进程通道。
 *
 * 采集不在 onCreate 里硬开：GpuCollectorWorker 按需求启停，
 * OverlayService（悬浮条显示时）和设置界面（onStart）会各自声明需求。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 写入通道要 Context，而且必须赶在任何采集线程起来之前
        NexusRemoteWriter.bind(this);
        NexusBridge.bind(this);
    }
}
