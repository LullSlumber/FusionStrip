package com.jj.nexusfloat.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import com.jj.nexusfloat.constant.Constants;
import com.jj.nexusfloat.utils.LogUtils;

/**
 * 写 GPU 数据：独立 App 化之后只剩本地 SharedPreferences 一条通道。
 *
 * 原版要「本地 prefs 给 provider 快照 + RemotePreferences 给注入进程」双写，
 * 现在读的都是同一个进程，写本地就够了。
 */
public final class NexusRemoteWriter {

    /** 本地 SharedPreferences 用的 Context，由 bind 注入 */
    private static volatile Context appContext;

    private NexusRemoteWriter() {}

    /** 绑上 App 的 Context，本地写入要用 */
    public static void bind(Context ctx) {
        appContext = ctx != null ? ctx.getApplicationContext() : null;
    }

    /**
     * 写本地 SharedPreferences。
     *
     * 返回写成功没有。
     */
    public static boolean publishLocal(int gpuMhz, int gpuUsage) {
        Context ctx = appContext;
        if (ctx == null) {
            return false;
        }
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(
                    Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(Constants.Remote.KEY_GPU_FREQ_MHZ, gpuMhz);
            if (gpuUsage >= 0) {
                editor.putInt(Constants.Remote.KEY_GPU_USAGE_PERCENT, gpuUsage);
            }
            editor.putLong(Constants.Remote.KEY_UPDATED_AT, System.currentTimeMillis());
            // 用 commit 不用 apply：下一拍就可能被读走，
            // apply 是异步落盘，读到的值会慢一拍
            return editor.commit();
        } catch (Throwable t) {
            LogUtils.w("write local prefs failed", t);
            return false;
        }
    }
}
