package com.jj.nexusfloat.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.Nullable;

import com.jj.nexusfloat.R;
import com.jj.nexusfloat.collector.GpuCollectorWorker;
import com.jj.nexusfloat.ui.MonitorOverlay;
import com.jj.nexusfloat.ui.view.MonitorView;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 悬浮条宿主前台服务。
 *
 * 原版 NexusFloat 把 MonitorView 注入 SystemUI 进程渲染（走 LSPosed）；
 * 独立 App 化之后没有注入渠道，改由本服务在自己进程里建
 * TYPE_APPLICATION_OVERLAY 悬浮窗托管 MonitorView。渲染逻辑（MonitorView）
 * 与显示判定（MonitorOverlay）原封未动。
 *
 * 需要前台服务的原因：悬浮窗要跨息屏、跨应用一直挂，后台服务随时会被杀；
 * specialUse 类型没有时长上限。监视条本身只占一条常驻通知。
 *
 * 权限没授的话不报错，按 5 秒一次重试：用户从设置页点完授权回来，
 * 不用重启 App 悬浮条自己就出来了。
 */
public class OverlayService extends Service {

    private static final String CHANNEL_ID = "perfhud_overlay";
    private static final int NOTIFICATION_ID = 1;
    private static final long RETRY_INTERVAL_MS = 5000L;

    private MonitorOverlay overlay;
    private MonitorView monitorView;
    private Handler handler;
    private boolean started;

    private final Runnable retryRunnable = this::tryStart;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        startForegroundCompat();
        tryStart();
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "监视条",
                    NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("悬浮监视条运行期间占用，隐藏采集时不耗电");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("PerfHUD 运行中")
                .setContentText("悬浮条显示时才采集数据")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /** 挂悬浮条。权限没授或者 addView 失败都按间隔重试 */
    private void tryStart() {
        if (started) {
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            LogUtils.i("OverlayService: overlay permission missing, retrying");
            scheduleRetry();
            return;
        }
        try {
            monitorView = new MonitorView(this);
            overlay = new MonitorOverlay(this);
            started = overlay.start(monitorView);
        } catch (Throwable t) {
            LogUtils.e("OverlayService: start failed", t);
            started = false;
        }
        if (started) {
            LogUtils.i("OverlayService: monitor bar started");
        } else {
            overlay = null;
            monitorView = null;
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        handler.removeCallbacks(retryRunnable);
        handler.postDelayed(retryRunnable, RETRY_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // BootReceiver / 设置页都会拉起本服务；已经跑着就当刷新一次判定
        tryStart();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(retryRunnable);
        if (overlay != null) {
            overlay.stop();
        }
        overlay = null;
        monitorView = null;
        started = false;
        // 悬浮条没了就没有 overlay 需求；setOverlayDemand(false) 顺带停掉 root 采集
        GpuCollectorWorker.setOverlayDemand(false);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
