package com.jj.nexusfloat.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import com.jj.nexusfloat.constant.Constants;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 读设置：独立 App 化之后只剩本地 SharedPreferences 一条通道。
 *
 * 原版是 SystemUI 进程读模块进程写的数据，要分 Remote → provider 快照 →
 * Settings.Global 三层兜底；现在读写同进程，一个 SharedPreferences 全解决。
 * 对外 API（方法名、语义、默认值）与原版完全一致，
 * MonitorOverlay / MonitorView / PerformanceCollector 的调用点一行都不用动。
 */
public final class NexusBridge {

    private static volatile Context appContext;

    private NexusBridge() {}

    /** Application.onCreate 时绑上 Context，读 prefs 要用 */
    public static void bind(Context context) {
        appContext = context != null ? context.getApplicationContext() : null;
    }

    private static SharedPreferences prefs() {
        Context ctx = appContext;
        if (ctx == null) {
            return null;
        }
        return ctx.getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean readBool(String key, boolean def) {
        SharedPreferences p = prefs();
        return p != null ? p.getBoolean(key, def) : def;
    }

    private static int readInt(String key, int def) {
        SharedPreferences p = prefs();
        return p != null ? p.getInt(key, def) : def;
    }

    private static float readFloat(String key, float def) {
        SharedPreferences p = prefs();
        return p != null ? p.getFloat(key, def) : def;
    }

    private static long readLong(String key, long def) {
        SharedPreferences p = prefs();
        return p != null ? p.getLong(key, def) : def;
    }

    private static String readString(String key, String def) {
        SharedPreferences p = prefs();
        if (p == null) {
            return def;
        }
        String v = p.getString(key, def);
        return v != null ? v : def;
    }

    // ---- GPU 数据（GpuCollectorWorker 每拍写进来）----

    public static int getGpuFreqMhz() {
        int mhz = readInt(Constants.Remote.KEY_GPU_FREQ_MHZ, 0);
        return mhz > 0 ? mhz : 0;
    }

    public static int getGpuUsage() {
        int pct = readInt(Constants.Remote.KEY_GPU_USAGE_PERCENT, -1);
        return pct >= 0 ? pct : -1;
    }

    public static long getUpdatedAt() {
        return readLong(Constants.Remote.KEY_UPDATED_AT, 0L);
    }

    // ---- 显示时机 ----

    /** 竖屏显不显示监视器；读不到时默认显示 */
    public static boolean isShowInPortrait() {
        return readBool(Constants.Remote.KEY_SHOW_PORTRAIT, true);
    }

    /** 横屏显不显示监视器；读不到时默认显示 */
    public static boolean isShowInLandscape() {
        return readBool(Constants.Remote.KEY_SHOW_LANDSCAPE, true);
    }

    /**
     * 息屏（锁屏/灭屏）时还显不显示监视条。
     * 读不到时默认 false：息屏后就隐藏并停采集，省电。
     */
    public static boolean isShowOnScreenOff() {
        return readBool(Constants.Remote.KEY_SHOW_SCREEN_OFF, false);
    }

    /**
     * 是不是「仅充电时显示」。
     * 读不到默认 false：没设过就按常显走，别让监视条意外消失。
     */
    public static boolean isShowChargingOnly() {
        return readBool(Constants.Remote.KEY_SHOW_CHARGING_ONLY, false);
    }

    /**
     * 读各指标模块的开关，下标跟 Constants.Modules.KEYS 对应。
     * 默认值走 DEFAULT_ENABLED 表（CPU、GPU、功率、FPS 四项）。
     */
    public static boolean[] readModuleFlags() {
        boolean[] flags = new boolean[Constants.Modules.COUNT];
        for (int i = 0; i < flags.length; i++) {
            flags[i] = readBool(Constants.Modules.KEYS[i],
                    Constants.Modules.DEFAULT_ENABLED[i]);
        }
        return flags;
    }

    /**
     * 读各模块在监视条上显示的名称，下标跟 Constants.Modules.KEYS 对应。
     * 用户特意清空的项返回空串，监视条看到空串就只留数值、不显示名称。
     */
    public static String[] readModuleNames() {
        String[] names = new String[Constants.Modules.COUNT];
        for (int i = 0; i < names.length; i++) {
            String def = Constants.Modules.DEFAULT_NAMES[i];
            names[i] = readString(Constants.Modules.nameKey(i), def);
        }
        return names;
    }

    /**
     * 读顶层模块的显示顺序。存的是逗号分隔的 IDX 列表，
     * 解析失败、重复或者混进了子项，一律回退 DEFAULT_ORDER。
     */
    public static int[] readModuleOrder() {
        String raw = readString(Constants.Modules.KEY_MODULE_ORDER, null);
        if (raw == null || raw.isEmpty()) {
            return Constants.Modules.DEFAULT_ORDER.clone();
        }
        String[] parts = raw.split(",");
        int[] order = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                order[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return Constants.Modules.DEFAULT_ORDER.clone();
        }
        int[] normalized = Constants.Modules.normalizeOrder(order);
        return normalized != null ? normalized : Constants.Modules.DEFAULT_ORDER.clone();
    }

    /**
     * 读空格自定义：下标是顶层模块 IDX，值是这模块前面加几个空格。
     * 解析失败或缺键就全 0（监视条全紧贴）。
     */
    public static int[] readSpaceBefore() {
        return Constants.Modules.parseSpaceBefore(
                readString(Constants.Modules.KEY_SPACE_BEFORE, ""));
    }

    /**
     * 读各 FPS 来源的开关，下标跟 Constants.Modules.FpsSource.KEYS 对应。
     * 键存在就用键值，不存在才用旧版整数推导的默认。
     */
    public static boolean[] readFpsSourceFlags() {
        int count = Constants.Modules.FpsSource.COUNT;
        boolean[] fallback = legacyPreset();
        boolean[] flags = new boolean[count];
        for (int i = 0; i < count; i++) {
            flags[i] = readBool(Constants.Modules.FpsSource.KEYS[i], fallback[i]);
        }
        return flags;
    }

    /** 按旧版的 fps_source 整数取对应预设，当成逐键读取的默认值。 */
    private static boolean[] legacyPreset() {
        int legacy = readInt(Constants.Modules.KEY_FPS_SOURCE,
                Constants.Modules.FPS_SOURCE_AUTO);
        int preset = (legacy >= 0 && legacy < Constants.Modules.FpsSource.PRESETS.length)
                ? legacy : 0;
        return Constants.Modules.FpsSource.PRESETS[preset].clone();
    }

    /** 「SurfaceFlinger 走 root」开关；默认关。 */
    public static boolean isSfPreferRoot() {
        return readBool(Constants.Modules.KEY_SF_PREFER_ROOT, false);
    }

    /** 「FPS 诊断」开关；默认关 */
    public static boolean isFpsDebugEnabled() {
        return readBool(Constants.Modules.KEY_FPS_DEBUG, false);
    }

    /** 「背景」开关；默认关，跟旧版本观感保持一致 */
    public static boolean isBackgroundEnabled() {
        return readBool(Constants.Modules.KEY_BACKGROUND, false);
    }

    /** 「自动反色」开关；默认关，保持旧版固定字色的行为 */
    public static boolean isAutoContrastEnabled() {
        return readBool(Constants.Modules.KEY_AUTO_CONTRAST, false);
    }

    /** 时间用不用 24 小时制；读不到就返回 true。 */
    public static boolean isClock24h() {
        return readBool(Constants.Modules.KEYS[Constants.Modules.IDX_CLOCK_24H], true);
    }

    /**
     * 「字体颜色」在 Constants.Ui.TEXT_COLORS 里的下标；默认 0（白）。
     * 越界就按 0 处理：色板长度可能随版本变，旧存的下标不能把监视条搞崩。
     */
    public static int getTextColorIndex() {
        int idx = readInt(Constants.Modules.KEY_TEXT_COLOR, 0);
        return (idx >= 0 && idx < Constants.Ui.TEXT_COLORS.length) ? idx : 0;
    }

    /** CPU 柱状图开关；默认开。 */
    public static boolean isCpuBarEnabled() {
        return readBool(Constants.Modules.KEY_CPU_BAR, true);
    }

    /** GPU 柱状图开关；默认开，理由同上 */
    public static boolean isGpuBarEnabled() {
        return readBool(Constants.Modules.KEY_GPU_BAR, true);
    }

    /** 「双电芯」开关；默认关，用户确认自己机型是双电芯才开 */
    public static boolean isDualCellEnabled() {
        return readBool(Constants.Modules.KEY_DUAL_CELL, false);
    }

    /** 用户设的字号（sp）；读不到就用默认字号 */
    public static float getFontSizeSp() {
        return readFloat(Constants.Modules.KEY_FONT_SIZE, Constants.Ui.TEXT_SIZE_SP);
    }

    /** 用户设的字体加不加粗；默认加粗，跟旧版一致 */
    public static boolean isFontBold() {
        return readBool(Constants.Modules.KEY_FONT_BOLD, true);
    }

    /** 用户设的项目间隔（dp）；读不到就用默认间隔 */
    public static float getSpacingDp() {
        return readFloat(Constants.Modules.KEY_SPACING, Constants.Ui.DIVIDER_WIDTH_DP);
    }

    /**
     * 用户设的采集周期（毫秒）；读不到或者越界就用默认值。
     * 越界是截断而不是回退默认：截到边上比一下跳回 1 秒更接近他的本意。
     */
    public static int getUpdateIntervalMs() {
        int ms = readInt(Constants.Modules.KEY_UPDATE_INTERVAL,
                Constants.Config.UPDATE_INTERVAL_MS);
        return Math.max(Constants.Config.UPDATE_INTERVAL_MIN_MS,
                Math.min(Constants.Config.UPDATE_INTERVAL_MAX_MS, ms));
    }

    /**
     * 后台唤醒间隔（秒）；0 表示不额外唤醒。
     * 独立 App 化之后没有「模块进程」可唤醒，这个设置保留只为兼容旧存储，
     * 监视条侧不再消费它。
     */
    public static int getWakeIntervalSec() {
        int sec = readInt(Constants.Modules.KEY_WAKE_INTERVAL,
                Constants.Modules.WAKE_INTERVAL_DEFAULT_SEC);
        return Math.max(Constants.Modules.WAKE_INTERVAL_MIN_SEC,
                Math.min(Constants.Modules.WAKE_INTERVAL_MAX_SEC, sec));
    }

    /** 监视条水平偏移（px，正值往右）；读不到是 0 */
    public static int getOffsetX() {
        return clampOffset(readInt(Constants.Modules.KEY_OFFSET_X, 0));
    }

    /** 监视条垂直偏移（px，正值往下）；读不到是 0 */
    public static int getOffsetY() {
        return clampOffset(readInt(Constants.Modules.KEY_OFFSET_Y, 0));
    }

    private static int clampOffset(int px) {
        return Math.max(Constants.Modules.OFFSET_MIN_PX,
                Math.min(Constants.Modules.OFFSET_MAX_PX, px));
    }

    /** 「仅在选定应用显示」开关；默认关，也就是所有界面都显示 */
    public static boolean isAppFilterEnabled() {
        return readBool(Constants.Modules.KEY_APP_FILTER, false);
    }

    /**
     * 白名单包名。返回空集合表示用户开了过滤却一个应用都没选——
     * 这种情况监视条应该一直隐藏，跟「选了但不在前台」一样处理。
     */
    public static Set<String> getWhitelist() {
        String raw = readString(Constants.Modules.KEY_APP_WHITELIST, "");
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String pkg : raw.split("[\\n ]+")) {
            String trimmed = pkg.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
