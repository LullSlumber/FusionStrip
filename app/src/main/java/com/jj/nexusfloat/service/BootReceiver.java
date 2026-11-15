package com.jj.nexusfloat.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 开机自启：直接把悬浮条宿主服务拉起来。
 *
 * 原版开机只恢复 GPU 采集（渲染在 SystemUI 侧，随系统就绪自然出现）；
 * 独立 App 化之后渲染也在自己进程里，所以开机要拉起的是 OverlayService。
 * 悬浮权限没授时服务内部会按间隔重试，不用这里管。
 *
 * 同时监听 LOCKED_BOOT_COMPLETED（Direct Boot 阶段触发）：未解锁阶段
 * FGS 可能被系统拦一下，但 receiver 先注册，解锁后 START_STICKY 兜底。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            return;
        }
        LogUtils.i("BootReceiver: " + action);
        // HUD 总开关关着就不拉服务：用户关掉的悬浮条不该开机自己冒出来
        boolean hudEnabled = context.getSharedPreferences(
                Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(Constants.Modules.KEY_HUD_ENABLED, true);
        if (!hudEnabled) {
            LogUtils.i("BootReceiver: hud disabled, skip");
            return;
        }
        try {
            Intent service = new Intent(context, OverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Throwable t) {
            // Direct Boot / 精简 ROM 下可能暂时起不来，服务自己会重试
            LogUtils.w("BootReceiver: start OverlayService failed", t);
        }
    }
}
