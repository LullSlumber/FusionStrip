package com.jj.nexusfloat.ui.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jj.nexusfloat.collector.PerformanceCollector;
import com.jj.nexusfloat.constant.Constants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 状态栏顶部的性能监视条：CPU/GPU 柱条加文字指标。
 * 跑在 SystemUI 进程里，由 com.jj.nexusfloat.xposed.ModuleMain 注入。
 *
 * 各指标模块（CPU/CPU频率/GPU/GPU频率/RAM/ZRAM/温度/功率/电流/FPS）可以单独开关：
 * 每个模块的子 View 登记成一组，关掉就整组 GONE。本 View 宽度是 WRAP_CONTENT、
 * 承载窗口水平居中，所以藏掉任何一个模块之后监视条都会自动收窄并保持居中。
 *
 * CPU频率 / GPU频率 是 CPU / GPU 的子项（见 Constants.Modules#PARENT）：父项一关，
 * 子项跟着藏。分隔条只归顶层模块，所以单独关掉频率不会改变模块之间的间距。
 *
 * 文字和柱条统一用白色，不再跟着状态栏反色——配上可选的深灰背景，浅色壁纸上
 * 也看得清。
 *
 * 字号（setFontSizeSp(float)）和项目间隔（setSpacingDp(float)）都能让用户调。
 * 字号一变，柱条尺寸、固定列宽、间隔、背景内边距按同一比例缩放；间隔另有自己的
 * 设定值，缩放只是在它基础上再乘字号比例。
 */
public class MonitorView extends LinearLayout {

    private TextView clockIcon;
    private TextView clockText;

    private TextView cpuName;
    /** CPU 名称位的矢量图标：名称保持默认时显示它，自定义过名称就换回文字 */
    private MonitorIconView cpuIconV;
    private MultiCoreBarView cpuBar;
    private TextView cpuFreqMinText;
    private TextView cpuFreqMaxText;
    private TextView cpuUsageText;
    private TextView cpuLeftParentheses;
    private TextView cpuTextView;
    private TextView cpuRightParentheses;
    private TextView gpuLeftParentheses;
    private TextView gpuRightParentheses;

    private TextView gpuName;
    private MonitorIconView gpuIconV;
    private MultiCoreBarView gpuBar;
    private TextView gpuFreqText;
    private TextView gpuUsageText;

    private TextView fpsName;
    private MonitorIconView fpsIconV;
    private TextView fpsText;

    private TextView ramName;
    private MonitorIconView ramIconV;
    private TextView ramText;
    private TextView zramIcon;
    private TextView zramText;

    private TextView batTempName;
    private MonitorIconView batTempIconV;
    private TextView batTempText;
    private TextView batPowerName;
    private MonitorIconView batPowerIconV;
    private TextView batPowerText;
    private TextView batCurrentName;
    private MonitorIconView batCurrentIconV;
    private TextView batCurrentText;

    /** CPU 温度（v1.8.0）：走 thermal_zone，跟电池温度是两个独立模块 */
    private TextView cpuTempName;
    private MonitorIconView cpuTempIconV;
    private TextView cpuTempText;

    /** 网速（v1.8.14）：MB/s */
    private TextView netName;
    private MonitorIconView netIconV;
    private TextView netText;

    private PerformanceCollector collector;

    /**
     * 按 Constants.Modules 下标排列的子 View 分组。
     * CPU频率 / GPU频率 是 CPU / GPU 的子项，父项关了子项一起藏。
     */
    private Group[] groups = new Group[0];

    /** 各模块当前的开关状态，顺序跟 Constants.Modules#KEYS 一致 */
    private final boolean[] moduleEnabled = newAllEnabled();
    /** 算上父项之后的实际可见性，预先分配好，省得每次新建 */
    private final boolean[] effective = newAllEnabled();
    /**
     * 上下文。除了建 View，还用来把当前帧率同步给模块进程
     * （见 updateData 里的 FpsSyncBridge.publish）。
     */
    private final Context context;
    /**
     * 各模块名称位的 TextView，下标跟 Constants.Modules#KEYS 一致。
     * CPU频率 / GPU频率 的数值在父项括号里，没有名称位，所以是 null。
     */
    private final TextView[] nameViews = new TextView[Constants.Modules.COUNT];
    /**
     * 各模块名称位的矢量图标（v1.9.4-lull.2），下标同上。
     * 只有九个指标模块有图标（时钟/ZRAM/频率子项没有），其余是 null。
     * 名称保持默认时显示图标，自定义过名称就换回 nameViews 里的文字。
     */
    private final MonitorIconView[] iconViews = new MonitorIconView[Constants.Modules.COUNT];
    /** 各模块当前显示的名称，用来判断是不是真变了，免得白跑一次重新布局 */
    private final String[] moduleNames = Constants.Modules.DEFAULT_NAMES.clone();
    /** 「背景」开关现在是什么状态 */
    private boolean backgroundEnabled;
    /** 「自动反色」开关现在是什么状态 */
    private boolean autoContrastEnabled;
    /** 用户选的字体颜色（ARGB）。「自动反色」生效时会临时盖掉它 */
    private int textColor = Constants.Ui.COLOR_TEXT;
    /**
     * 系统下发的状态栏图标 tint；Constants.Config#UNPUBLISHED 表示还没收到。
     *
     * 开了「自动反色」之后靠它判断背景明暗：SystemUI 已经算好状态栏该用深色还是
     * 浅色图标了，直接跟着走比自己采像素可靠得多，也不用截屏权限。
     */
    private int statusBarTint = Constants.Config.UNPUBLISHED;
    /** CPU / GPU 柱状图开关，默认开，跟旧版本一致 */
    private boolean cpuBarEnabled = true;
    private boolean gpuBarEnabled = true;
    /** 当前字号（sp） */
    private float fontSizeSp = Constants.Ui.TEXT_SIZE_SP;
    /** 字体加不加粗（v1.8.9），默认加粗，跟旧版一致 */
    private boolean fontBold = true;
    /** 当前项目间隔（dp），实际像素宽度还得乘 fontScale() */
    private float spacingDp = Constants.Ui.DIVIDER_WIDTH_DP;
    /** FPS 诊断开关，开了之后在 FPS 数值后面显示来源标记（b/r/f/g/p/v/x） */
    private boolean fpsDebugEnabled = false;
    /** FPS 数值列，诊断开关一切换就得换探针重算列宽 */
    private ValueColumn fpsColumn;
    /**
     * 空格自定义（v1.8.7）：下标是顶层模块 IDX，值是该模块前面的空格数（0–9）。
     * 默认全是 0（监视条全程紧贴）；用户可以在 App 里用「项目名+数量」指定。
     */
    private int[] spaceBefore = new int[Constants.Modules.COUNT];
    /**
     * 时间是不是用 24 小时制。
     *
     * 默认跟着系统的 12/24 小时设置走，用户在 App 里改过之后就听用户的。
     * 首次读取之前先按系统设置初始化，免得第一拍显示的格式跟系统不一致。
     */
    private boolean clock24h = true;
    /** 缓存的时间文本，只在分钟变了才 setText，省掉每秒一次没意义的重绘 */
    private String lastClockText = "";

    /** 所有文字 View，用来统一设字号和颜色 */
    private final List<TextView> allTexts = new ArrayList<>();
    /** 固定列宽的数值 View 和它的宽度探针，字号变了要重新量列宽 */
    private final List<ValueColumn> valueColumns = new ArrayList<>();
    /** 所有分隔条，宽度跟着字号缩放 */
    private final List<View> dividers = new ArrayList<>();

    private static boolean[] newAllEnabled() {
        boolean[] flags = new boolean[Constants.Modules.COUNT];
        Arrays.fill(flags, true);
        return flags;
    }

    /**
     * 一个指标模块占着的那几个连着放的子 View。
     *
     * divider 单独留个引用：开背景的时候要把最后一个可见模块尾巴上的分隔条藏掉，
     * 不然背景右边会多出一段空白，看着不居中。
     */
    private static final class Group {
        final List<View> views;
        final View divider;

        Group(List<View> views, View divider) {
            this.views = views;
            this.divider = divider;
        }
    }

    /** 固定列宽的数值 View：列宽按探针字符串在当前字号下的实际宽度定 */
    private static final class ValueColumn {
        final TextView view;
        /** 探针可以换：开了 FPS 诊断之后 FPS 列要多留一位给来源字母 */
        String probe;

        ValueColumn(TextView view, String probe) {
            this.view = view;
            this.probe = probe;
        }
    }

    public MonitorView(Context context) {
        super(context);
        this.context = context;
        init(context);
    }

    /**
     * 把子 View 和 PerformanceCollector 建起来
     */
    private void init(Context context) {
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER);

        // 时间排最前：它是唯一跟性能无关的项，搁最左边不会打断各指标的顺序。
        // 初值跟着系统的 12/24 小时设置，之后由用户的开关覆盖
        clock24h = android.text.format.DateFormat.is24HourFormat(context);
        clockIcon = createIconView(context, Constants.Modules.IDX_CLOCK);
        // v1.8.6：所有值列统一左对齐——把项目内部多余的空格全删了，
        // 数值紧贴名称的冒号。用户想加空格就走「空格自定义」设置
        clockText = createValueTextView(context, Constants.Modules.CLOCK_PROBE, Gravity.START);
        // 立刻把时间填上：不然第一拍（最多 1 秒）之内是空白的
        updateClock();

        cpuIconV = createModuleIcon(context, Constants.Modules.IDX_CPU, MonitorIconView.ICON_CPU);
        cpuName = createIconView(context, Constants.Modules.IDX_CPU);
        cpuBar = new MultiCoreBarView(context);
        setupBarView(cpuBar, dp2px(Constants.Ui.CPU_BAR_WIDTH_DP), dp2px(Constants.Ui.BAR_HEIGHT_DP));
        // 占用率列（v1.8.9）：固定 2 位数宽度 + 左对齐——两位留得下
        // （8%→10% 时列宽不变，监视条不左右移动），又能紧贴左边的柱条。
        // 括号前的留白由固定列宽统一控制，不再跟着值跳
        cpuUsageText = createValueTextView(context, Constants.Ui.PROBE_USAGE, Gravity.START);
        cpuLeftParentheses = createParenthesesTextView(context,"(");
        cpuFreqMinText = createValueTextView(context, Constants.Ui.PROBE_CPU_FREQ, Gravity.START);
        cpuTextView = createParenthesesTextView(context,"-");
        cpuFreqMaxText = createValueTextView(context, Constants.Ui.PROBE_CPU_FREQ, Gravity.START);
        cpuRightParentheses = createParenthesesTextView(context,")");

        gpuIconV = createModuleIcon(context, Constants.Modules.IDX_GPU, MonitorIconView.ICON_GPU);
        gpuName = createIconView(context, Constants.Modules.IDX_GPU);
        gpuBar = new MultiCoreBarView(context);
        setupBarView(gpuBar, dp2px(Constants.Ui.GPU_BAR_WIDTH_DP), dp2px(Constants.Ui.BAR_HEIGHT_DP));
        // 占用率列（v1.8.9）：固定 2 位数宽度 + 左对齐，理由同 cpuUsageText
        gpuUsageText = createValueTextView(context, Constants.Ui.PROBE_USAGE, Gravity.START);
        gpuLeftParentheses = createParenthesesTextView(context,"(");
        gpuFreqText = createValueTextView(context, Constants.Ui.PROBE_GPU, Gravity.START);
        gpuRightParentheses = createParenthesesTextView(context,")");

        ramIconV = createModuleIcon(context, Constants.Modules.IDX_RAM, MonitorIconView.ICON_RAM);
        ramName = createIconView(context, Constants.Modules.IDX_RAM);
        ramText = createValueTextView(context, Constants.Ui.PROBE_PCT, Gravity.START);
        zramIcon = createIconView(context, Constants.Modules.IDX_ZRAM);
        zramText = createValueTextView(context, Constants.Ui.PROBE_PCT, Gravity.START);

        batTempIconV = createModuleIcon(context, Constants.Modules.IDX_TEMP, MonitorIconView.ICON_TEMP);
        batTempName = createIconView(context, Constants.Modules.IDX_TEMP);
        batTempText = createValueTextView(context, Constants.Ui.PROBE_TEMP, Gravity.START);
        batPowerIconV = createModuleIcon(context, Constants.Modules.IDX_POWER, MonitorIconView.ICON_POWER);
        batPowerName = createIconView(context, Constants.Modules.IDX_POWER);
        // 功率和电流左对齐：这两项的值带 +/- 号，而 Gravity.CENTER 会把
        // 「值比探针短」的那点余量平分到左右两边，于是符号前面凭空多出半格空白。
        // 左对齐之后符号紧贴名称，余量统一落在右侧，项目间隔仍归分隔条管
        batPowerText = createValueTextView(context, Constants.Ui.PROBE_POWER, Gravity.START);
        batCurrentIconV = createModuleIcon(context, Constants.Modules.IDX_CURRENT, MonitorIconView.ICON_CURRENT);
        batCurrentName = createIconView(context, Constants.Modules.IDX_CURRENT);
        batCurrentText = createValueTextView(context, Constants.Ui.PROBE_CURRENT, Gravity.START);

        fpsIconV = createModuleIcon(context, Constants.Modules.IDX_FPS, MonitorIconView.ICON_FPS);
        fpsName = createIconView(context, Constants.Modules.IDX_FPS);
        // FPS 列左对齐（v1.8.5）：数值从列左边开始，紧贴名称位的冒号；
        // 个位数时也从最左排起，自动「前进一格」贴住冒号，不再因为居中留空
        fpsText = createValueTextView(context, Constants.Ui.PROBE_FPS, Gravity.START);
        // 把 FPS 列记下来，诊断开关切换时要换探针
        fpsColumn = valueColumns.get(valueColumns.size() - 1);

        // CPU 温度（v1.8.0）：独立于电池温度，默认排在最后
        cpuTempIconV = createModuleIcon(context, Constants.Modules.IDX_CPU_TEMP, MonitorIconView.ICON_CPU_TEMP);
        cpuTempName = createIconView(context, Constants.Modules.IDX_CPU_TEMP);
        cpuTempText = createValueTextView(context, Constants.Ui.PROBE_CPU_TEMP, Gravity.START);

        // 网速（v1.8.14）：左对齐，数值带单位 MB/s
        netIconV = createModuleIcon(context, Constants.Modules.IDX_NET, MonitorIconView.ICON_NET);
        netName = createIconView(context, Constants.Modules.IDX_NET);
        netText = createValueTextView(context, Constants.Ui.PROBE_NET, Gravity.START);

        // 各模块先按默认顺序加进布局；用户改过顺序的话由 setModuleOrder 重排
        View clockDivider = addTextOnlyRow(null, clockIcon, clockText);
        View cpuDivider = addCpuRow();
        View gpuDivider = addGpuRow();
        View ramDivider = addTextOnlyRow(ramIconV, ramName, ramText);
        View zramDivider = addTextOnlyRow(null, zramIcon, zramText);
        View tempDivider = addTextOnlyRow(batTempIconV, batTempName, batTempText);
        View powerDivider = addTextOnlyRow(batPowerIconV, batPowerName, batPowerText);
        View currentDivider = addTextOnlyRow(batCurrentIconV, batCurrentName, batCurrentText);
        View fpsDivider = addTextOnlyRow(fpsIconV, fpsName, fpsText);
        View cpuTempDivider = addTextOnlyRow(cpuTempIconV, cpuTempName, cpuTempText);
        View netDivider = addTextOnlyRow(netIconV, netName, netText);

        groups = new Group[Constants.Modules.COUNT];
        // 分隔条归到父模块里：关掉「CPU频率」只去掉括号里的频率，模块间距保持不变
        groups[Constants.Modules.IDX_CLOCK] =
                topGroup(clockDivider, clockIcon, clockText);
        // 12/24 小时制只改文字格式，不增删任何 View，所以它这一「组」是空的。
        // 但还得占个位：applyModuleVisibility 按下标遍历 groups，null 会被跳过
        groups[Constants.Modules.IDX_CLOCK_24H] = subGroup();
        groups[Constants.Modules.IDX_CPU] =
                topGroup(cpuDivider, cpuIconV, cpuName, cpuBar, cpuUsageText);
        groups[Constants.Modules.IDX_CPU_FREQ] =
                subGroup(cpuLeftParentheses, cpuFreqMinText, cpuTextView, cpuFreqMaxText, cpuRightParentheses);
        groups[Constants.Modules.IDX_GPU] =
                topGroup(gpuDivider, gpuIconV, gpuName, gpuBar, gpuUsageText);
        groups[Constants.Modules.IDX_GPU_FREQ] =
                subGroup(gpuLeftParentheses, gpuFreqText, gpuRightParentheses);
        groups[Constants.Modules.IDX_RAM] = topGroup(ramDivider, ramIconV, ramName, ramText);
        groups[Constants.Modules.IDX_ZRAM] = topGroup(zramDivider, zramIcon, zramText);
        groups[Constants.Modules.IDX_TEMP] = topGroup(tempDivider, batTempIconV, batTempName, batTempText);
        groups[Constants.Modules.IDX_POWER] =
                topGroup(powerDivider, batPowerIconV, batPowerName, batPowerText);
        groups[Constants.Modules.IDX_CURRENT] =
                topGroup(currentDivider, batCurrentIconV, batCurrentName, batCurrentText);
        groups[Constants.Modules.IDX_FPS] = topGroup(fpsDivider, fpsIconV, fpsName, fpsText);
        groups[Constants.Modules.IDX_CPU_TEMP] =
                topGroup(cpuTempDivider, cpuTempIconV, cpuTempName, cpuTempText);
        groups[Constants.Modules.IDX_NET] =
                topGroup(netDivider, netIconV, netName, netText);

        applyIconSizes();
        applyModuleVisibility();
        applyBackground();
        applyTintColor();

        collector = new PerformanceCollector(context, this::updateData);
    }

    /** 顶层模块：分隔条并进本组，整组藏起来时分隔条一起没，模块间距不留空 */
    private static Group topGroup(View divider, View... views) {
        List<View> all = new ArrayList<>(Arrays.asList(views));
        all.add(divider);
        return new Group(all, divider);
    }

    /** 子模块：不带分隔条，开关它只影响父模块内部的内容宽度 */
    private static Group subGroup(View... views) {
        return new Group(Arrays.asList(views), null);
    }

    /**
     * CPU：使用率和最大频率拆成两个 TextView，免得单个宽列右边留空
     *
     * 返回本行末尾那个分隔条。
     */
    private View addCpuRow() {
        addView(cpuIconV);
        addView(cpuName);
        addView(cpuBar);
        addView(cpuUsageText);
        addView(cpuLeftParentheses);
        addView(cpuFreqMinText);
        addView(cpuTextView);
        addView(cpuFreqMaxText);
        addView(cpuRightParentheses);
        return addDivider();
    }

    private View addGpuRow() {
        addView(gpuIconV);
        addView(gpuName);
        addView(gpuBar);
        addView(gpuUsageText);
        addView(gpuLeftParentheses);
        addView(gpuFreqText);
        addView(gpuRightParentheses);
        return addDivider();
    }

    /**
     * 纯文字行：图标（可为 null，时钟/ZRAM 没有矢量图标）→ 名称位 → 数值。
     */
    private View addTextOnlyRow(MonitorIconView icon, TextView name, TextView text) {
        if (icon != null) {
            addView(icon);
        }
        addView(name);
        addView(text);
        return addDivider();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startCollector();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopCollector();
    }

    /**
     * 应用顶层模块的显示顺序（v1.8.0 加的）。
     *
     * 把每个顶层模块连同它的子项（CPU频率 / GPU频率 / 24小时制的 View 在物理上
     * 是夹在父项组里的）从父容器摘下来，再按 order 的顺序一个个 append 回去——
     * LinearLayout 的 addView 就是追加语义，重排就这么完成了。
     *
     * 子项必须跟着父项走：addCpuRow 把「( freq )」放在占用率和分隔条中间，
     * 要是只挪父项组，子项就孤零零挂在容器中间了。拆和装都按
     * 「父项组 → 它的子项组」的顺序处理。
     *
     * 分隔条归在各自的顶层组里（见 topGroup），跟着组一起动，模块间距不会乱。
     *
     * order 是顶层模块下标数组；不合法（混进了子项、有重复）就忽略这次调用，
     * 保持现有顺序。长度不足时会自动补齐（加新模块后老顺序少一项的情况）。
     * 返回顺序有没有真的变。
     */
    public boolean setModuleOrder(int[] order) {
        int[] normalized = Constants.Modules.normalizeOrder(order);
        if (normalized == null) {
            return false;
        }
        order = normalized;
        int[] current = currentTopOrder();
        boolean same = true;
        for (int i = 0; i < order.length; i++) {
            if (current[i] != order[i]) {
                same = false;
                break;
            }
        }
        if (same) {
            return false;
        }
        for (int idx : order) {
            detachGroupWithChildren(idx);
        }
        for (int idx : order) {
            attachGroupWithChildren(idx);
        }
        requestLayout();
        return true;
    }

    /** 把顶层模块组和它子项组的全部 View 从父容器里摘掉 */
    private void detachGroupWithChildren(int topIdx) {
        Group g = topIdx < groups.length ? groups[topIdx] : null;
        if (g != null) {
            for (View v : g.views) {
                removeView(v);
            }
        }
        for (int i = 0; i < groups.length; i++) {
            if (Constants.Modules.PARENT[i] == topIdx && groups[i] != null) {
                for (View v : groups[i].views) {
                    removeView(v);
                }
            }
        }
    }

    /** 把顶层模块组和它子项组的全部 View 按原内部顺序 append 回父容器 */
    private void attachGroupWithChildren(int topIdx) {
        Group g = topIdx < groups.length ? groups[topIdx] : null;
        if (g != null) {
            // 父项自己的内容（图标、柱条、数值…）先加；
            // 分隔条得等子项插完再加——原始布局里 divider 就在子项后面
            for (View v : g.views) {
                if (v != g.divider) {
                    addView(v);
                }
            }
        }
        for (int i = 0; i < groups.length; i++) {
            if (Constants.Modules.PARENT[i] == topIdx && groups[i] != null) {
                for (View v : groups[i].views) {
                    addView(v);
                }
            }
        }
        // 内容和子项都就位了，分隔条收尾
        if (g != null && g.divider != null) {
            addView(g.divider);
        }
    }

    /**
     * 当前实际的顶层排列顺序。
     *
     * 不重新解析存储，直接从父容器里数一遍：从第 0 个子 View 开始，逐个查它属于
     * 哪个顶层组，遇到新的顶层组就记一笔。子项 View 归父项组，跳过；分隔条和图标
     * 都在组里。
     */
    private int[] currentTopOrder() {
        int[] out = new int[Constants.Modules.DEFAULT_ORDER.length];
        int filled = 0;
        outer:
        for (int i = 0; i < getChildCount() && filled < out.length; i++) {
            View child = getChildAt(i);
            for (int idx : Constants.Modules.DEFAULT_ORDER) {
                Group g = idx < groups.length ? groups[idx] : null;
                if (g == null) {
                    continue;
                }
                // 组的第一个 View 一出现就算这组开始了；同一组后面的 View
                // （数值、分隔条）不再触发
                if (g.views.size() > 0 && g.views.get(0) == child) {
                    for (int existing : out) {
                        if (existing == idx) {
                            continue outer;
                        }
                    }
                    out[filled++] = idx;
                }
            }
        }
        // 兜底：万一没数满（布局异常），用默认顺序填齐
        while (filled < out.length) {
            out[filled] = Constants.Modules.DEFAULT_ORDER[filled];
            filled++;
        }
        return out;
    }

    /**
     * 启停采集线程。
     *
     * 悬浮窗模式下监视条藏起来就是 removeView，采集随 detach 自动停。但退化模式
     * （监视条是 status_bar 的子 View）只切 visibility，View 一直 attached 着，
     * 采集线程不会自己停——那就成了关了显示却照旧每秒读一遍 sysfs。所以由
     * MonitorOverlay 在切 visibility 时显式调这个方法。
     */
    public void setCollecting(boolean collecting) {
        if (collecting) {
            startCollector();
        } else {
            stopCollector();
        }
    }

    private void startCollector() {
        if (collector != null) {
            collector.start();
        }
    }

    private void stopCollector() {
        if (collector != null) {
            collector.stop();
        }
    }

    private TextView createTextView(Context context) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
        // v1.8.9：粗细看 fontBold，默认加粗跟旧版一致
        // v1.9.4-lull.7：内置字体就不再叠加 BOLD 样式——打包的两个 ttf 本身
        // 就带正确字重，createFromFile 出来的样式标记是常规，再传 BOLD 会
        // 触发 Skia 伪加粗（描边涂抹），数字发胀、笔画发糊
        Typeface typeface = MonitorTypeface.get(fontBold);
        if (typeface != null) {
            tv.setTypeface(typeface);
        } else {
            tv.setTypeface(Typeface.MONOSPACE,
                    fontBold ? Typeface.BOLD : Typeface.NORMAL);
        }
        // v1.8.6：把 font padding 恢复回来。去掉之后中文名称（fallback 到 CJK 字体）
        // 的 ascent 偏大、字形视觉上往下掉，跟旁边的数字不在一条水平线上。
        // 留着 padding，CJK 和拉丁在行框里视觉居中才一致
        tv.setIncludeFontPadding(true);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        allTexts.add(tv);
        return tv;
    }

    /**
     * 应用字体粗细（v1.8.9）。
     *
     * bold 传 true 是加粗（默认），false 是常规。返回状态有没有变。
     */
    public boolean setFontBold(boolean bold) {
        if (fontBold == bold) {
            return false;
        }
        fontBold = bold;
        Typeface typeface = MonitorTypeface.get(bold);
        for (int i = 0; i < allTexts.size(); i++) {
            if (typeface != null) {
                allTexts.get(i).setTypeface(typeface);
            } else {
                allTexts.get(i).setTypeface(Typeface.MONOSPACE,
                        fontBold ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
        // 图标线条粗细跟字体加粗联动：加粗 2.25 / 常规 2（24 视口单位）
        for (int i = 0; i < iconViews.length; i++) {
            if (iconViews[i] != null) {
                iconViews[i].setIconBold(fontBold);
            }
        }
        requestLayout();
        return true;
    }

    /**
     * 拿样例字符串量出像素宽度，把列宽固定下来，免得数值一变布局就跳。
     * 列宽是跟字号绑的，所以登记探针，字号变了重新量。
     */
    private TextView createValueTextView(Context context, String widthProbe, int gravity) {
        TextView tv = createTextView(context);
        tv.setGravity(gravity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                probeWidthPx(tv, widthProbe), ViewGroup.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        valueColumns.add(new ValueColumn(tv, widthProbe));
        return tv;
    }

    /**
     * 内容宽度的数值 View（v1.8.5 加的）。
     *
     * 列宽按实际内容走，不用探针固定——给占用率列用的：它后面紧跟括号，
     * 固定列宽会在「值比探针短」的时候在右边留白，正好夹在 % 和 ( 中间
     * （用户要求删掉的就是这个空格）。改成 WRAP 之后 45%( 就紧贴了。
     * 代价是两位数↔三位数切换时列宽有轻微跳动，可以接受。
     *
     * 不进 valueColumns：宽度不固定，字号变了也不用重测。
     */
    private TextView createValueTextViewContent(Context context, int gravity) {
        TextView tv = createTextView(context);
        tv.setGravity(gravity);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    /**
     * 探针文本要占多少像素宽。
     *
     * 不能只用 measureText：它返回的是字形前进宽度（advance），可有些字形的实际
     * 墨迹会超出 advance（斜体、部分中文字体的标点），于是最后一个字符右边会被
     * 裁掉一小条。加一个像素级的余量最省事，也比按比例放大稳妥——比例余量在小字号
     * 下不够、大字号下又过头。
     */
    private int probeWidthPx(TextView tv, String probe) {
        float advance = tv.getPaint().measureText(probe);
        return (int) Math.ceil(advance) + dp2px(Constants.Ui.TEXT_WIDTH_SLACK_DP);
    }

    private TextView createParenthesesTextView(Context context, String text) {
        TextView tv = createTextView(context);
        tv.setText(text);
        return tv;
    }

    /**
     * 超长就截断到探针长度，但右边不补空格。
     *
     * v1.6.6 及以前会补空格补到跟探针一样长，那等于在固定列宽之外又加了一层空白：
     * 列宽本来就按探针量好了，补出来的空格全落在列内右侧，于是「实际值比探针短几位」
     * 的项目后面凭空多出几个字符宽的空隙——FPS 探针 4 位而值常常是 2 位（60），
     * 空隙就有两个字符宽，比项目间隔本身还大。这就是「项目之间间隔不统一」的来头。
     *
     * 现在只留截断：列宽固定防抖动，居中对齐让余量均分到两边，项目之间的间隔就
     * 只由分隔条决定了。
     */
    private static String clip(String widthProbe, String value) {
        int width = widthProbe.length();
        return value.length() > width ? value.substring(0, width) : value;
    }

    private static String formatFps(float fps, String tag, boolean debug) {
        if (fps == Constants.Fps.READ_FAILED || fps < 0) {
            // 诊断模式下即使没数值也要把 tag 带出来，不然用户看到的
            // 就一个「?」，问不出是哪一步断的
            if (debug && tag != null && !tag.isEmpty()) {
                return "?" + tag;
            }
            return debug ? "?" : "0";
        }
        String num;
        if (fps >= Constants.Config.FPS_INTEGER_THRESHOLD) {
            num = String.format(Locale.US, "%.0f", fps);
        } else {
            num = String.format(Locale.US, "%.1f", fps);
        }
        if (!debug || tag == null || tag.isEmpty()) {
            return num;
        }
        // 诊断模式：数值后面缀上来源 tag，如 "30b"、"120r"、"0x3"
        return num + tag;
    }

    /**
     * 建一个模块的名称位。名称文字用户可以自定义（见 setModuleNames(String[])），
     * 所以登记到 nameViews 里方便后面更新。
     */
    private TextView createIconView(Context context, int moduleIndex) {
        TextView tv = createTextView(context);
        nameViews[moduleIndex] = tv;
        applyName(moduleIndex, Constants.Modules.DEFAULT_NAMES[moduleIndex]);
        return tv;
    }

    /**
     * 建一个模块的矢量图标（v1.9.4-lull.2），登记到 iconViews 里，
     * 尺寸由 applyIconSizes 按当前字号统一设定。
     */
    private MonitorIconView createModuleIcon(Context context, int moduleIndex, int iconType) {
        MonitorIconView v = new MonitorIconView(context, iconType);
        iconViews[moduleIndex] = v;
        return v;
    }

    /**
     * 把名称写进对应的 TextView，并按名称状态切换「图标 / 文字」显示。
     *
     * 名称保持默认 → 藏文字、显示图标（图标颜色尺寸跟文字同源，观感一致）；
     * 名称被用户自定义 → 藏图标、显示「名称:」文字，自定义能力不丢；
     * 名称为空串（用户特意清空的）→ 两者都藏，只留数值。
     *
     * 注意：模块关掉时的整组隐藏由 applyModuleVisibility 统一压 GONE，
     * 这里只负责在「模块可见」的前提下放开该放开的那一个——被组放开之后
     * 多余的那个会在 applyModuleVisibility 的收尾阶段再压回去。
     */
    @SuppressLint("SetTextI18n")
    private void applyName(int moduleIndex, String name) {
        TextView tv = nameViews[moduleIndex];
        if (tv == null) {
            return;
        }
        moduleNames[moduleIndex] = name;
        MonitorIconView icon = iconViews[moduleIndex];
        boolean useIcon = icon != null && name.equals(Constants.Modules.DEFAULT_NAMES[moduleIndex]);
        if (name.isEmpty()) {
            tv.setText("");
            // 用 GONE 不用空文本：空 TextView 照样占着左右内边距和一点宽度
            tv.setVisibility(View.GONE);
            if (icon != null) {
                icon.setVisibility(View.GONE);
            }
        } else if (useIcon) {
            tv.setText("");
            tv.setVisibility(View.GONE);
            // 最终可见性由 applyModuleVisibility 按开关状态定，这里只负责放开
            icon.setVisibility(View.VISIBLE);
        } else {
            tv.setText(name + ":");
            tv.setVisibility(View.VISIBLE);
            if (icon != null) {
                icon.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 应用用户自定义的模块名称，下标跟 Constants.Modules#KEYS 一致。
     *
     * 返回状态有没有变。
     */
    public boolean setModuleNames(String[] names) {
        if (names == null || names.length != moduleNames.length) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < names.length; i++) {
            String name = names[i] != null ? names[i] : "";
            if (nameViews[i] == null || moduleNames[i].equals(name)) {
                continue;
            }
            applyName(i, name);
            changed = true;
        }
        if (changed) {
            // 名称变了各项宽度就变了，而且清空/填回名称会动 visibility，
            // 得重新走一遍开关判定，否则被关掉的模块的名称位会被放出来
            applyModuleVisibility();
        }
        return changed;
    }

    /**
     * 应用空格自定义（v1.8.7）：给指定的顶层模块组首 View 加 leftMargin。
     *
     * 监视条默认全程紧贴（v1.8.6 把所有空格删光了）。用户可以在 App 里用
     * 「项目名+数量」指定在哪些模块前面加几个空格——实现成组首 View 的左侧 margin，
     * 宽度 = 空格数 × 当前字号下单空格宽度。margin 是跟着 View 本身的，模块重排
     * （setModuleOrder）时自然就带走了。
     *
     * before 下标是顶层模块 IDX，值是该模块前的空格数。返回有没有变化。
     */
    public boolean setSpacesBefore(int[] before) {
        if (before == null || before.length != Constants.Modules.COUNT) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < before.length; i++) {
            if (spaceBefore[i] != before[i]) {
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        spaceBefore = before.clone();
        applySpaceBefore();
        return true;
    }

    /** 把 spaceBefore 数组落实到各组的 leftMargin */
    private void applySpaceBefore() {
        int spacePx = measureSpaceWidthPx();
        for (int idx : Constants.Modules.DEFAULT_ORDER) {
            int count = idx < spaceBefore.length ? spaceBefore[idx] : 0;
            Group g = idx < groups.length ? groups[idx] : null;
            if (g == null || g.views.isEmpty()) {
                continue;
            }
            // v1.8.9：FPS 项目默认再少一个空格——它前面是分隔条，
            // 默认把 FPS 组往左拉一个空格宽，贴得更近一点
            if (idx == Constants.Modules.IDX_FPS) {
                count -= 1;
            }
            View first = g.views.get(0);
            ViewGroup.LayoutParams lp = first.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) lp).leftMargin = count * spacePx;
                first.setLayoutParams(lp);
            }
        }
        requestLayout();
    }

    /** 当前字号下单个空格（当前生效的字体，默认等宽）有多宽，单位像素 */
    private int measureSpaceWidthPx() {
        TextView probe = new TextView(getContext());
        probe.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
        // 测量探针必须跟实际显示的字体一致，列间距才不会跟着字体变
        Typeface typeface = MonitorTypeface.get(true);
        if (typeface != null) {
            probe.setTypeface(typeface);
        } else {
            probe.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        }
        probe.setText(" ");
        return (int) Math.ceil(probe.getPaint().measureText(" "));
    }

    private void setupBarView(MultiCoreBarView bar, int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        int margin = dp2px(Constants.Ui.BAR_HORIZONTAL_MARGIN_DP * fontScale());
        params.leftMargin = margin;
        // v1.9.4-lull.5：柱条右侧和数值之间留一小段空隙（跟图标→柱条的间距一致），
        // 原版是 0 紧贴，跟效果图对比显得数字糊在柱条上
        params.rightMargin = dp2px(2f * fontScale());
        bar.setLayoutParams(params);
    }

    /** sp → px（跟随系统字体缩放，跟 TextView.setTextSize(COMPLEX_UNIT_SP) 同基准） */
    private float sp2px(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                getContext().getResources().getDisplayMetrics());
    }

    /**
     * 按当前字号统一设定各模块图标的尺寸、与右侧内容的间距、GPU 图标的下沉量。
     *
     * 尺寸 = 字号 × {@link MonitorIconView#SIZE_SCALE}，最小
     * {@link MonitorIconView#MIN_SIZE_DP} dp 保底——字号拉到下限时图标不至于糊掉。
     * 图标跟数值 TextView 同在一条水平 LinearLayout 里垂直居中，字号变了这边重算
     * 尺寸、行高跟着变，中心线对齐关系不会错位。init 时和字号变化时都会调。
     */
    private void applyIconSizes() {
        float sp = fontSizeSp * MonitorIconView.SIZE_SCALE;
        int px = Math.max((int) (sp2px(sp) + 0.5f), dp2px(MonitorIconView.MIN_SIZE_DP));
        int gap = dp2px(2f * fontScale());
        for (int i = 0; i < iconViews.length; i++) {
            MonitorIconView v = iconViews[i];
            if (v == null) {
                continue;
            }
            v.setIconSizePx(px);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.rightMargin = gap;
            v.setLayoutParams(lp);
        }
    }

    private View addDivider() {
        View divider = new View(getContext());
        divider.setLayoutParams(new LinearLayout.LayoutParams(dividerWidthPx(), 1));
        addView(divider);
        dividers.add(divider);
        return divider;
    }

    /** 分隔条像素宽度：用户设定的间隔再按字号缩放，大字号下间隔不会显窄 */
    private int dividerWidthPx() {
        return dp2px(spacingDp * fontScale());
    }

    /** 当前字号相对默认字号的比例，用来等比缩放柱条、间距和背景 */
    private float fontScale() {
        return fontSizeSp / Constants.Ui.TEXT_SIZE_SP;
    }

    /**
     * 监视条内容的实测高度（px）。
     *
     * 只给退化模式用（监视条是 status_bar 的子 View）——悬浮窗模式下窗口高度直接用
     * WRAP_CONTENT，不需要这个值。
     *
     * v1.6.7 改成实测，不再按字号比例推算。原先用 MONITOR_BAR_HEIGHT_DP * fontScale()，
     * 等于假定「行高跟字号成正比，且 7sp 时刚好等于 8dp」。这个假定有两种情况不成立：
     * 一是大字号——20sp 算出 22.9dp，可 20sp 等宽字体的实际行高超过这个数，文字下半截
     * 被裁掉；二是中文字形——中文的 ascent/descent 比拉丁字母大，同样字号下更高，
     * 所以自定义名称填中文时会显示不全。
     */
    public int getBarHeightPx() {
        measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int measured = getMeasuredHeight();
        int floor = dp2px(Constants.Ui.MONITOR_BAR_HEIGHT_DP * fontScale());
        return Math.max(measured, floor);
    }

    private int dp2px(float dp) {
        return (int) (dp * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 应用各模块的开关状态。数组顺序得跟 Constants.Modules#KEYS 一致。
     *
     * 返回状态有没有变。
     */
    public boolean setModuleEnabled(boolean[] enabled) {
        if (enabled == null || enabled.length != moduleEnabled.length) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < moduleEnabled.length; i++) {
            if (moduleEnabled[i] != enabled[i]) {
                moduleEnabled[i] = enabled[i];
                changed = true;
            }
        }
        if (changed) {
            applyModuleVisibility();
        }
        return changed;
    }

    /**
     * 应用「背景」开关：开了就给监视条整体加个圆角矩形灰底。
     *
     * 返回状态有没有变。
     */
    public boolean setBackgroundEnabled(boolean enabled) {
        if (backgroundEnabled == enabled) {
            return false;
        }
        backgroundEnabled = enabled;
        applyBackground();
        // 背景改了内边距和尾部分隔条的可见性，宽度跟着变
        applyModuleVisibility();
        // 背景本身就是字色判定的依据之一：开了背景就有确定的深色底，
        // 这时候该固定用白字，而不是继续跟着壁纸走
        applyTintColor();
        return true;
    }

    /**
     * 应用「自动反色」开关：让字色跟着背景明暗走。
     *
     * 返回状态有没有变。
     */
    public boolean setAutoContrastEnabled(boolean enabled) {
        if (autoContrastEnabled == enabled) {
            return false;
        }
        autoContrastEnabled = enabled;
        applyTintColor();
        // 只换颜色，不影响任何尺寸，所以不必 requestLayout
        return false;
    }

    /**
     * 应用 CPU / GPU 柱状图开关。
     *
     * 返回状态有没有变（柱条是占宽度的，变了之后窗口得重新测量）。
     */
    public boolean setBarsEnabled(boolean cpuBar, boolean gpuBar) {
        if (cpuBarEnabled == cpuBar && gpuBarEnabled == gpuBar) {
            return false;
        }
        cpuBarEnabled = cpuBar;
        gpuBarEnabled = gpuBar;
        applyModuleVisibility();
        return true;
    }

    /**
     * 应用「FPS 诊断」开关：开了之后 FPS 数值后面跟一个字母，标明这一拍的帧率到底
     * 来自哪一级数据源（见 Constants.Fps.TAG_*）。
     *
     * 发布版把日志关了（LOG_ENABLED = false），屏幕上这个字母就是用户唯一能反馈
     * 「到底走了哪条路」的渠道。
     *
     * 返回状态有没有变。
     */
    public boolean setFpsDebugEnabled(boolean enabled) {
        if (fpsDebugEnabled == enabled) {
            return false;
        }
        fpsDebugEnabled = enabled;
        // 多出一个字母，列宽得跟着变，不然 clip() 会把字母截掉
        if (fpsColumn != null) {
            fpsColumn.probe = enabled
                    ? Constants.Ui.PROBE_FPS_DEBUG : Constants.Ui.PROBE_FPS;
            ViewGroup.LayoutParams lp = fpsColumn.view.getLayoutParams();
            if (lp != null) {
                lp.width = probeWidthPx(fpsColumn.view, fpsColumn.probe);
                fpsColumn.view.setLayoutParams(lp);
            }
        }
        requestLayout();
        return true;
    }

    /**
     * 应用字号（sp）。柱条尺寸、固定列宽、分隔条宽度和背景内边距都按当前字号
     * 与默认字号之比等比缩放，所以背景会跟着字体一起变大变小。
     *
     * 返回状态有没有变。
     */
    public boolean setFontSizeSp(float sp) {
        float clamped = Math.max(Constants.Ui.TEXT_SIZE_MIN_SP,
                Math.min(Constants.Ui.TEXT_SIZE_MAX_SP, sp));
        if (Math.abs(fontSizeSp - clamped) < 0.01f) {
            return false;
        }
        fontSizeSp = clamped;

        for (int i = 0; i < allTexts.size(); i++) {
            allTexts.get(i).setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
        }
        // 字号变了，等宽字体的列宽也得按新字号重新量
        for (int i = 0; i < valueColumns.size(); i++) {
            ValueColumn col = valueColumns.get(i);
            ViewGroup.LayoutParams lp = col.view.getLayoutParams();
            if (lp != null) {
                lp.width = probeWidthPx(col.view, col.probe);
                col.view.setLayoutParams(lp);
            }
        }
        float scale = fontScale();
        applyDividerWidth();
        setupBarView(cpuBar, dp2px(Constants.Ui.CPU_BAR_WIDTH_DP * scale),
                dp2px(Constants.Ui.BAR_HEIGHT_DP * scale));
        setupBarView(gpuBar, dp2px(Constants.Ui.GPU_BAR_WIDTH_DP * scale),
                dp2px(Constants.Ui.BAR_HEIGHT_DP * scale));
        // 图标跟字号等比缩放（字号 × 1.1），GPU 图标的下沉量也按比例重算
        applyIconSizes();

        // 空格自定义的宽度跟着字号缩放（v1.8.6）
        applySpaceBefore();
        applyBackground();
        requestLayout();
        return true;
    }

    /**
     * 应用项目间隔（dp）。只改分隔条宽度，不动字号和柱条。
     *
     * 返回状态有没有变。
     */
    public boolean setSpacingDp(float dp) {
        float clamped = Math.max(Constants.Ui.DIVIDER_MIN_DP,
                Math.min(Constants.Ui.DIVIDER_MAX_DP, dp));
        if (Math.abs(spacingDp - clamped) < 0.01f) {
            return false;
        }
        spacingDp = clamped;
        applyDividerWidth();
        requestLayout();
        return true;
    }

    /**
     * 应用用户设定的采集周期（毫秒）。
     *
     * 直接转交给 PerformanceCollector：它每轮结束时重读这个值，所以改完下一拍就生效，
     * 不用重启采集线程。
     */
    public void setIntervalMs(int ms) {
        if (collector != null) {
            collector.setIntervalMs(ms);
        }
    }

    private void applyDividerWidth() {
        int width = dividerWidthPx();
        for (int i = 0; i < dividers.size(); i++) {
            View d = dividers.get(i);            ViewGroup.LayoutParams lp = d.getLayoutParams();
            if (lp != null) {
                lp.width = width;
                d.setLayoutParams(lp);
            }
        }
    }

    /**
     * 背景是 GradientDrawable 画的，尺寸自动跟着 View 边界走，
     * 而 View 宽度是 WRAP_CONTENT，所以指标增减时背景宽度自动跟上。
     * 圆角和内边距按字号等比缩放，字体变大时背景一起变大。
     * 开的时候补一点左右内边距，免得首末文字贴到圆角边上。
     */
    private void applyBackground() {
        if (backgroundEnabled) {
            float scale = fontScale();
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(Constants.Ui.COLOR_BACKGROUND);
            bg.setCornerRadius(dp2px(Constants.Ui.BACKGROUND_CORNER_RADIUS_DP * scale));
            setBackground(bg);
            int padH = dp2px(Constants.Ui.BACKGROUND_PADDING_H_DP * scale);
            setPadding(padH, 0, padH, 0);
        } else {
            setBackground(null);
            setPadding(0, 0, 0, 0);
        }
    }

    /**
     * 按开关状态整组显示/隐藏。用 GONE 不用 INVISIBLE，让 LinearLayout 不再给它分宽度，
     * 监视条随之收窄；承载窗口是 WRAP_CONTENT + 水平居中，收窄之后自动还是居中。
     *
     * 开背景的时候额外把最后一个可见模块尾巴上的分隔条藏掉，不然背景右边会多出
     * 一段空白，看着内容没居中。
     */
    private void applyModuleVisibility() {
        View tail = null;
        for (int i = 0; i < groups.length; i++) {
            boolean visible = isEffectivelyEnabled(i);
            effective[i] = visible;
            applyGroupVisibility(groups[i], visible);
            // 名称位按名称状态再压一次（上面按组放开时把图标和文字都放出来了）：
            // 默认名称 → 显示图标、藏文字；自定义 → 显示文字、藏图标；
            // 清空 → 两个都藏。模块本身不可见时整组已经是 GONE，不用再动
            if (visible && nameViews[i] != null) {
                MonitorIconView icon = iconViews[i];
                boolean useIcon = icon != null
                        && moduleNames[i].equals(Constants.Modules.DEFAULT_NAMES[i]);
                if (moduleNames[i].isEmpty()) {
                    nameViews[i].setVisibility(View.GONE);
                    if (icon != null) {
                        icon.setVisibility(View.GONE);
                    }
                } else if (useIcon) {
                    nameViews[i].setVisibility(View.GONE);
                    if (icon != null) {
                        icon.setVisibility(View.VISIBLE);
                    }
                } else {
                    nameViews[i].setVisibility(View.VISIBLE);
                    if (icon != null) {
                        icon.setVisibility(View.GONE);
                    }
                }
            }
            // 只有顶层模块带分隔条，记下最后一个可见的
            if (visible && groups[i] != null && groups[i].divider != null) {
                tail = groups[i].divider;
            }
        }
        // 柱条是 CPU / GPU 组的成员，上面按组统一放开了，这里按各自的柱状图
        // 开关再压回去。放在循环之后：柱条只受父模块和它自己的开关影响
        if (cpuBar != null) {
            cpuBar.setVisibility(
                    effective[Constants.Modules.IDX_CPU] && cpuBarEnabled
                            ? View.VISIBLE : View.GONE);
        }
        if (gpuBar != null) {
            gpuBar.setVisibility(
                    effective[Constants.Modules.IDX_GPU] && gpuBarEnabled
                            ? View.VISIBLE : View.GONE);
        }
        if (backgroundEnabled && tail != null) {
            tail.setVisibility(View.GONE);
        }
        requestLayout();
    }

    /** 模块自己开着、父模块（如果有）也开着，才真的可见 */
    private boolean isEffectivelyEnabled(int index) {
        if (!moduleEnabled[index]) {
            return false;
        }
        int parent = Constants.Modules.PARENT[index];
        return parent < 0 || moduleEnabled[parent];
    }

    private static void applyGroupVisibility(Group group, boolean visible) {
        if (group == null) {
            return;
        }
        int target = visible ? View.VISIBLE : View.GONE;
        for (int i = 0; i < group.views.size(); i++) {
            View v = group.views.get(i);
            if (v != null && v.getVisibility() != target) {
                v.setVisibility(target);
            }
        }
    }

    /**
     * PerformanceCollector 周期性回调过来，刷新各指标文字和柱条。
     * 已经藏起来的模块跳过刷新，省掉不可见 View 的 setText 和重绘。
     * 判定用 effective，父模块关掉时它的子项也不用刷。
     */
    public void updateData(PerformanceCollector.PerformanceData data) {
        if (effective[Constants.Modules.IDX_CLOCK]) {
            updateClock();
        }
        if (effective[Constants.Modules.IDX_CPU]) {
            if (cpuBarEnabled) {
                cpuBar.updateUsages(data.cpuCoreUsages);
            }
            cpuUsageText.setText(clip(Constants.Ui.PROBE_USAGE,
                    String.format(Locale.US, "%d%%", (int) data.cpuTotalUsage)));
        }
        if (effective[Constants.Modules.IDX_CPU_FREQ]) {
            cpuFreqMinText.setText(String.format(Locale.US, "%d", data.cpuFreqMinMhz));
            cpuFreqMaxText.setText(String.format(Locale.US, "%d", data.cpuFreqMaxMhz));
        }

        if (effective[Constants.Modules.IDX_GPU]) {
            if (gpuBarEnabled) {
                gpuBar.updateUsages(data.gpuCoreUsages);
            }
            gpuUsageText.setText(clip(Constants.Ui.PROBE_USAGE,
                    String.format(Locale.US, "%d%%", data.gpuTotalUsage)));
        }
        if (effective[Constants.Modules.IDX_GPU_FREQ]) {
            gpuFreqText.setText(String.format(Locale.US, "%d", data.gpuFreq));
        }

        if (effective[Constants.Modules.IDX_RAM]) {
            ramText.setText(clip(Constants.Ui.PROBE_PCT,
                    String.format(Locale.US, "%d%%", (int) data.ramUsagePercent)));
        }
        if (effective[Constants.Modules.IDX_ZRAM]) {
            zramText.setText(clip(Constants.Ui.PROBE_PCT,
                    String.format(Locale.US, "%d%%", (int) data.zramUsagePercent)));
        }
        if (effective[Constants.Modules.IDX_TEMP]) {
            batTempText.setText(clip(Constants.Ui.PROBE_TEMP,
                    String.format(Locale.US, "%.1f°C", data.batteryTemp)));
        }
        if (effective[Constants.Modules.IDX_POWER]) {
            batPowerText.setText(clip(Constants.Ui.PROBE_POWER,
                    String.format(Locale.US, "%+.2fW", data.batteryPowerW)));
        }
        if (effective[Constants.Modules.IDX_CURRENT]) {
            // %+d 保证放电是负、充电是正，跟功率的符号约定一致
            batCurrentText.setText(clip(Constants.Ui.PROBE_CURRENT,
                    String.format(Locale.US, "%+dmA", (int) data.batteryCurrentMa)));
        }
        if (effective[Constants.Modules.IDX_FPS]) {
            String probe = fpsDebugEnabled
                    ? Constants.Ui.PROBE_FPS_DEBUG : Constants.Ui.PROBE_FPS;
            fpsText.setText(clip(probe,
                    formatFps(data.fps, data.fpsTag, fpsDebugEnabled)));
        }
        if (effective[Constants.Modules.IDX_CPU_TEMP]) {
            // NaN 表示 thermal_zone 里找不到 CPU 项，显示 --，跟电池温度读不到时
            // 的表现区分开：一个是模块还在但取不到数，一个是模块本身关着
            String text = Float.isNaN(data.cpuTemp)
                    ? "--°C"
                    : String.format(Locale.US, "%d°C", (int) data.cpuTemp);
            cpuTempText.setText(clip(Constants.Ui.PROBE_CPU_TEMP, text));
        }
        if (effective[Constants.Modules.IDX_NET]) {
            // 保留两位小数：MB/s 这个量级下 0.01 的差别也有意义
            netText.setText(clip(Constants.Ui.PROBE_NET,
                    String.format(Locale.US, "%.2fMB/s", data.netSpeedMBps)));
        }
    }

    /**
     * 刷新时间文本。
     *
     * 只在文本真的变了（也就是跨过一分钟）时才 setText：采集是每秒一拍，而时间一分钟
     * 才变一次，无条件 setText 会让 TextView 每秒重新测量、重绘，白白占主线程——
     * 监视条上一共十几个 View，这种浪费得避。
     */
    private void updateClock() {
        String text = DateFormat.format(
                clock24h ? Constants.Modules.CLOCK_PATTERN_24H
                        : Constants.Modules.CLOCK_PATTERN_12H,
                System.currentTimeMillis()).toString();
        if (!text.equals(lastClockText)) {
            lastClockText = text;
            clockText.setText(clip(Constants.Modules.CLOCK_PROBE, text));
        }
    }

    /**
     * 应用 12/24 小时制开关。
     *
     * 返回状态有没有变。
     */
    public boolean setClock24h(boolean use24h) {
        if (clock24h == use24h) {
            return false;
        }
        clock24h = use24h;
        // 强制下一次 updateClock 重新写入：格式变了但分钟数没变，
        // 不清缓存的话得等到下一分钟才生效
        lastClockText = "";
        updateClock();
        return true;
    }

    /**
     * 由 com.jj.nexusfloat.bridge.StatusBarTintBridge 下发系统状态栏图标色。
     *
     * 只在开了「自动反色」之后才影响显示：SystemUI 把状态栏该用深色还是浅色图标
     * 算好了，这个颜色就是最可靠的「背景是亮还是暗」的信号，比自己采像素省事得多，
     * 也不用截屏权限。
     */
    public void onColorsChanged(int tint) {
        if (statusBarTint == tint) {
            return;
        }
        statusBarTint = tint;
        if (autoContrastEnabled) {
            applyTintColor();
        }
    }

    /**
     * 算出并应用文字和柱条的颜色。
     *
     * 优先级从高到低：
     * 一是开了「自动反色」又没开背景——跟着系统状态栏图标色走。系统用深色图标说明
     * 背景是亮的，那文字也该用深色。这一路会盖掉用户选的颜色，因为它本来就是为了
     * 保证可读性。
     * 二是其余情况——用「字体颜色」选定的预设色。开了背景的也走这里：背景是确定的
     * 半透明深灰，没必要再跟着壁纸变。
     */
    private void applyTintColor() {
        boolean autoDark = autoContrastEnabled && !backgroundEnabled && isLightBackground();
        int t = autoDark ? Constants.Ui.COLOR_TEXT_DARK : textColor;

        cpuBar.setTintColor(t);
        gpuBar.setTintColor(t);

        for (int i = 0; i < allTexts.size(); i++) {
            allTexts.get(i).setTextColor(t);
        }
        // 图标颜色直取文字色：反色 / 换色时图标跟着一起变
        for (int i = 0; i < iconViews.length; i++) {
            if (iconViews[i] != null) {
                iconViews[i].setIconColor(t);
            }
        }
    }

    /**
     * 应用「字体颜色」。
     *
     * index 是 Constants.Ui#TEXT_COLORS 的下标，越界就按白色处理。返回状态有没有变。
     */
    public boolean setTextColorIndex(int index) {
        int[] palette = Constants.Ui.TEXT_COLORS;
        int color = (index >= 0 && index < palette.length) ? palette[index] : palette[0];
        if (textColor == color) {
            return false;
        }
        textColor = color;
        applyTintColor();
        // 只换颜色不改尺寸，无需 requestLayout
        return false;
    }

    /**
     * 看系统下发的状态栏图标色，判断背景是不是浅色。
     *
     * 系统在浅色背景上会把图标改成深色，所以图标越暗、背景越亮。用 luma 加权
     * （人眼对绿色最敏感）而不是简单取平均，免得纯蓝色被误判成亮色。
     *
     * 还没收到 tint 时返回 false，也就是维持白字——判定手段缺失时宁可沿用旧行为，
     * 也别贸然换成深色，让深色壁纸上的字看不见。
     */
    private boolean isLightBackground() {
        if (statusBarTint == Constants.Config.UNPUBLISHED) {
            return false;
        }
        int r = (statusBarTint >> 16) & 0xFF;
        int g = (statusBarTint >> 8) & 0xFF;
        int b = statusBarTint & 0xFF;
        int luma = (r * 299 + g * 587 + b * 114) / 1000;
        return luma < Constants.Ui.TINT_DARK_LUMA_MAX;
    }
}
