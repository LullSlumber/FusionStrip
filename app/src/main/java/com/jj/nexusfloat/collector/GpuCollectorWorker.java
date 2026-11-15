package com.jj.nexusfloat.collector;

import android.os.Handler;
import android.os.HandlerThread;
import com.jj.nexusfloat.bridge.NexusRemoteWriter;
import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 本 App 进程里的后台 GPU 采集，没有前台 Service。
 *
 * 采不采由两个需求方共同决定，任一方要采就采，都不要就停：
 *
 * overlay 是悬浮条。默认 false，进程重启后也还是 false，完全由
 * OverlayService 的 evaluate() 心跳驱动：监视条一隐藏就不发心跳，
 * 采集自然停住，不会因为进程被谁顺手拉起来就又自己跑起来。
 *
 * ui 是设置界面。它的「运行状态」卡片要显示实时 GPU 读数，所以界面
 * 在前台时必须采集，哪怕监视条正藏着。
 *
 * 两边分开记是为了别互相覆盖，否则打开一次 App 就把悬浮条那边的暂停状态冲掉了，
 * 退出 App 之后再没人把它停回去。
 */
public final class GpuCollectorWorker {

    private static HandlerThread workerThread;
    private static Handler workerHandler;
    private static volatile boolean running;
    private static int waitServiceTicks;

    /**
     * 悬浮条要不要数据。
     *
     * 默认 false，靠 OverlayService 的心跳打开。要是反过来默认 true，进程
     * 每次被系统回收再拉起来都会自作主张开始采集，而悬浮条那边只在状态变化时
     * 才下发指令，没人来纠正它，「关了显示还在耗电」就是这么来的。
     */
    private static boolean overlayDemand;
    /** 模块 App 的设置界面在不在前台 */
    private static boolean uiDemand;

    /** 上次成功写进 Remote 的值 */
    private static int lastPublishedMhz = Constants.Config.UNPUBLISHED;
    private static int lastPublishedUsage = Constants.Config.UNPUBLISHED;

    private static final Runnable collectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            tick();
            workerHandler.postDelayed(this, Constants.Config.UPDATE_INTERVAL_MS);
        }
    };

    private GpuCollectorWorker() {}

    /**
     * 按当前需求启停。进程拉起、Xposed 服务绑定这些时机调，
     * 它自己不产生需求，没有需求方就什么都不做。
     */
    public static synchronized void start() {
        apply();
    }

    /** 硬停：服务断开这种场景，不管谁还想要数据都停掉 */
    public static synchronized void stop() {
        overlayDemand = false;
        uiDemand = false;
        apply();
    }

    /** SystemUI 监视条的需求，EarlyInitProvider 收到指令后调这里 */
    public static synchronized void setOverlayDemand(boolean want) {
        overlayDemand = want;
        apply();
    }

    /** 模块 App 设置界面的需求，跟着 Activity 的 onStart/onStop 切 */
    public static synchronized void setUiDemand(boolean want) {
        uiDemand = want;
        apply();
    }

    private static void apply() {
        boolean wanted = overlayDemand || uiDemand;
        if (wanted == running) {
            return;
        }
        if (wanted) {
            running = true;
            ensureThread();
            workerHandler.removeCallbacks(collectRunnable);
            workerHandler.post(collectRunnable);
            LogUtils.i("GpuCollectorWorker started");
        } else {
            running = false;
            // 把已发布值复位：下次恢复时第一轮必定重新写一次 Remote，
            // 不然暂停期间 SystemUI 读到的还是停之前的旧值
            lastPublishedMhz = Constants.Config.UNPUBLISHED;
            lastPublishedUsage = Constants.Config.UNPUBLISHED;
            if (workerHandler != null) {
                workerHandler.removeCallbacks(collectRunnable);
            }
            LogUtils.i("GpuCollectorWorker stopped");
        }
    }

    private static void ensureThread() {
        if (workerThread != null && workerThread.isAlive()) {
            return;
        }
        workerThread = new HandlerThread(Constants.ThreadName.GPU_COLLECTOR);
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    private static void tick() {
        int gpuMhz = GpuRootReader.readFreqMhz();
        int gpuUsage = GpuRootReader.readUsagePercent();

        // 频率和占用都没变就不写，省点传输开销
        if (gpuMhz == lastPublishedMhz && gpuUsage == lastPublishedUsage) {
            return;
        }

        // 先写本地 SharedPreferences。独立 App 化之后读写同进程，
        // 这就是唯一的数据通道
        boolean written = NexusRemoteWriter.publishLocal(gpuMhz, gpuUsage);

        if (written) {
            lastPublishedMhz = gpuMhz;
            lastPublishedUsage = gpuUsage;
        } else if (gpuMhz > 0) {
            LogUtils.w("local publish failed, gpuMhz=" + gpuMhz);
        }
    }
}
