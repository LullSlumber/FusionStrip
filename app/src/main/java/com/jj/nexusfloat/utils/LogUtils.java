package com.jj.nexusfloat.utils;

import android.util.Log;

import com.jj.nexusfloat.constant.Constants;

/**
 * 日志统一从这里走，开不开由 Constants.Config#LOG_ENABLED 决定。
 *
 * 独立 App 化之后没有 LSPosed 模块日志可写，只落 logcat；
 * 原来的 dual / bindModule / xposed 通道已随注入层一起剥离。
 */
public final class LogUtils {

    private LogUtils() {}

    public static boolean isEnabled() {
        return Constants.Config.LOG_ENABLED;
    }

    public static void d(String msg) {
        writeLogcat(Log.DEBUG, msg, null);
    }

    public static void i(String msg) {
        writeLogcat(Log.INFO, msg, null);
    }

    public static void w(String msg) {
        writeLogcat(Log.WARN, msg, null);
    }

    public static void w(String msg, Throwable t) {
        writeLogcat(Log.WARN, msg, t);
    }

    public static void e(String msg) {
        writeLogcat(Log.ERROR, msg, null);
    }

    public static void e(String msg, Throwable t) {
        writeLogcat(Log.ERROR, msg, t);
    }

    private static void writeLogcat(int level, String msg, Throwable t) {
        if (!isEnabled()) {
            return;
        }
        switch (level) {
            case Log.DEBUG:
                if (t != null) Log.d(Constants.TAG, msg, t);
                else Log.d(Constants.TAG, msg);
                break;
            case Log.INFO:
                if (t != null) Log.i(Constants.TAG, msg, t);
                else Log.i(Constants.TAG, msg);
                break;
            case Log.WARN:
                if (t != null) Log.w(Constants.TAG, msg, t);
                else Log.w(Constants.TAG, msg);
                break;
            case Log.ERROR:
            default:
                if (t != null) Log.e(Constants.TAG, msg, t);
                else Log.e(Constants.TAG, msg);
                break;
        }
    }
}
