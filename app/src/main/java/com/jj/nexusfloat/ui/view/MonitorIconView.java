package com.jj.nexusfloat.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * 监视条名称位的内置矢量图标（v1.9.4-lull.2）。
 *
 * 9 枚单色线性小图标，路径数据取自 Lucide 图标库（ISC 协议，24×24 视口、
 * stroke 2、圆头圆角），静态解析成 Path 常量后按需绘制——不碰 SystemUI
 * 的任何资源，也不引入外部素材文件。
 *
 * 尺寸由 MonitorView 按当前字号驱动（字号 × {@link #SIZE_SCALE}，
 * 最小 {@link #MIN_SIZE_DP} dp 保底），颜色直接取当前文字色，
 * 反色 / 换色自动跟随；线条粗细随「字体加粗」开关联动。
 *
 * 名称位的取舍规则在 MonitorView#applyName：名称保持默认 → 显示图标；
 * 用户自定义过名称 → 显示文字；名称被清空 → 两者都藏。
 */
public final class MonitorIconView extends View {

    public static final int ICON_CPU = 0;
    public static final int ICON_GPU = 1;
    public static final int ICON_RAM = 2;
    public static final int ICON_TEMP = 3;
    public static final int ICON_POWER = 4;
    public static final int ICON_CURRENT = 5;
    public static final int ICON_FPS = 6;
    public static final int ICON_CPU_TEMP = 7;
    public static final int ICON_NET = 8;
    public static final int ICON_COUNT = 9;

    /** 图标边长 = 当前字号（sp）× 这个系数，跟文字等比缩放 */
    public static final float SIZE_SCALE = 1.1f;
    /** 图标最小边长（dp）：字号拉到下限时图标不至于糊成一团 */
    public static final float MIN_SIZE_DP = 6f;

    /** 常规线宽（24 视口单位，跟 Lucide 原生一致） */
    private static final float STROKE_NORMAL = 2f;
    /** 加粗线宽：跟「字体加粗」开关联动 */
    private static final float STROKE_BOLD = 2.25f;

    /**
     * 每枚图标的一组图元。条目以 "R:" 开头的是圆角矩形（x,y,w,h,r），
     * 其余是 SVG 路径数据，由 {@link #parseSvgPath} 解析。
     *
     * Lucide 是 ISC 协议，路径原样收录（电流那枚的闪电子路径保持绝对坐标，
     * 不做合并改写——合并时 M 改成小写 m 会变成相对位移，图标就画歪了）。
     */
    private static final String[][] ICON_DEFS = {
            // ICON_CPU：cpu（芯片）
            {"R:4,4,16,16,2", "R:8,8,8,8,1",
                    "M12 20v2M12 2v2M17 20v2M17 2v2M2 12h2M2 17h2M2 7h2M20 12h2M20 17h2M20 7h2M7 20v2M7 2v2"},
            // ICON_GPU：monitor（显示器）
            {"R:2,3,20,14,2", "M8 21h8M12 17v4"},
            // ICON_RAM：memory-stick（内存条）
            {"R:2,6,20,10,2",
                    "M12 12v-2M12 18v-2M16 12v-2M16 18v-2M2 11h1.5M20 18v-2M20.5 11H22M4 18v-2M8 12v-2M8 18v-2"},
            // ICON_TEMP：thermometer（温度计）
            {"M14 4v10.54a4 4 0 1 1-4 0V4a2 2 0 0 1 4 0Z"},
            // ICON_POWER：zap（闪电）
            {"M15.914 4a1.5 1.5 0 0 0-2.474-1.561l-9 9A1.5 1.5 0 0 0 5.5 14h4.002a.5.5 0 0 1 .471.666L8.086 20a1.5 1.5 0 0 0 2.475 1.56l9-9A1.5 1.5 0 0 0 18.5 10h-3.997a.5.5 0 0 1-.472-.667z"},
            // ICON_CURRENT：battery-charging（充电电池）
            {"M15 7h1a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-2",
                    "M6 7H5a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2",
                    "M11 7l-3 5h4l-3 5",
                    "M22 11v2"},
            // ICON_FPS：gauge（仪表盘）
            {"m12 14 4-4M3.34 19a10 10 0 1 1 17.32 0"},
            // ICON_CPU_TEMP：thermometer-sun（温度计 + 日光）
            {"M12 2v2M12 8a4 4 0 0 0-1.645 7.647M2 12h2M20 14.54a4 4 0 1 1-4 0V4a2 2 0 0 1 4 0zM4.93 4.93l1.41 1.41M6.34 17.66l-1.41 1.41"},
            // ICON_NET：arrow-up-down（上下双箭头）
            {"m21 16-4 4-4-4M17 20V4M3 8l4-4 4 4M7 4v16"},
    };

    /** 静态解析好的 Path 常量，所有实例共用（onDraw 只读，UI 线程独占，安全） */
    private static final Path[][] ICON_PATHS = buildPaths();

    private final int iconType;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int sizePx;

    public MonitorIconView(Context context, int iconType) {
        super(context);
        if (iconType < 0 || iconType >= ICON_COUNT) {
            throw new IllegalArgumentException("unknown icon type: " + iconType);
        }
        this.iconType = iconType;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        // 默认加粗（MonitorView 的 fontBold 默认值是 true），之后由 applyIconBold 校准
        paint.setStrokeWidth(STROKE_BOLD);
    }

    /** 设定图标边长（px）：同时就是 View 的布局宽高 */
    public void setIconSizePx(int px) {
        if (px <= 0 || px == sizePx) {
            return;
        }
        sizePx = px;
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) {
            lp = new LinearLayout.LayoutParams(px, px);
            setLayoutParams(lp);
        } else {
            lp.width = px;
            lp.height = px;
            setLayoutParams(lp);
        }
        invalidate();
    }

    /** 颜色直取当前文字色，反色 / 换色自动跟随 */
    public void setIconColor(int color) {
        if (paint.getColor() != color) {
            paint.setColor(color);
            invalidate();
        }
    }

    /** 线条粗细跟「字体加粗」开关联动 */
    public void setIconBold(boolean bold) {
        float target = bold ? STROKE_BOLD : STROKE_NORMAL;
        if (paint.getStrokeWidth() != target) {
            paint.setStrokeWidth(target);
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (sizePx <= 0) {
            return;
        }
        // 24 视口等比缩到当前边长；stroke 宽度随画布变换一起缩放，
        // 相对线宽在任何尺寸下都与 mockup 一致
        canvas.save();
        float s = sizePx / 24f;
        canvas.scale(s, s);
        Path[] paths = ICON_PATHS[iconType];
        for (Path p : paths) {
            canvas.drawPath(p, paint);
        }
        canvas.restore();
    }

    // ------------------------------------------------------------------
    // SVG 路径解析
    //
    // 这里的路径数据只需要 M/m L/l H/h V/v A/a Z/z 十个命令（Lucide 图标
    // 不含贝塞尔），自己实现一个迷你解析器就够，不依赖 androidx 的 PathParser
    // ——模块跑在 SystemUI 进程里，少一个外部依赖就少一分注入风险。
    //
    // 圆弧按 W3C 规范的端点参数化 → 圆心参数化转换后交给 Path.arcTo；
    // 坐标系是 y 向下的屏幕坐标，跟 Android 一致，角度无需翻转。
    // ------------------------------------------------------------------

    private static Path[][] buildPaths() {
        Path[][] out = new Path[ICON_DEFS.length][];
        for (int i = 0; i < ICON_DEFS.length; i++) {
            String[] defs = ICON_DEFS[i];
            Path[] paths = new Path[defs.length];
            for (int j = 0; j < defs.length; j++) {
                String def = defs[j];
                if (def.startsWith("R:")) {
                    paths[j] = roundRect(def.substring(2));
                } else {
                    paths[j] = parseSvgPath(def);
                }
            }
            out[i] = paths;
        }
        return out;
    }

    /** "x,y,w,h,r" 形式的圆角矩形 */
    private static Path roundRect(String spec) {
        String[] parts = spec.split(",");
        RectF r = new RectF(
                Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                Float.parseFloat(parts[0]) + Float.parseFloat(parts[2]),
                Float.parseFloat(parts[1]) + Float.parseFloat(parts[3]));
        float radius = Float.parseFloat(parts[4]);
        Path p = new Path();
        p.addRoundRect(r, radius, radius, Path.Direction.CW);
        return p;
    }

    private static Path parseSvgPath(String d) {
        Path path = new Path();
        Parser ps = new Parser(d);
        char cmd = ' ';
        boolean first = true;
        float x = 0, y = 0, sx = 0, sy = 0;
        while (!ps.atEnd()) {
            char c = ps.peek();
            if (isCommandChar(c)) {
                cmd = c;
                first = true;
                ps.skip();
                continue;
            }
            if (c == ' ' || c == ',') {
                ps.skip();
                continue;
            }
            boolean rel = cmd >= 'a' && cmd <= 'z'; // 小写 = 相对
            switch (cmd) {
                case 'M': case 'm': {
                    float nx = ps.number() + (rel ? x : 0);
                    float ny = ps.number() + (rel ? y : 0);
                    if (first) {
                        path.moveTo(nx, ny);
                        sx = nx; sy = ny;
                    } else {
                        // SVG 规范：moveto 后面跟的坐标对按 lineto 处理
                        path.lineTo(nx, ny);
                    }
                    x = nx; y = ny;
                    first = false;
                    // 后续坐标对按 lineto 语义处理
                    cmd = rel ? 'l' : 'L';
                    break;
                }
                case 'L': case 'l': {
                    x = ps.number() + (rel ? x : 0);
                    y = ps.number() + (rel ? y : 0);
                    path.lineTo(x, y);
                    first = false;
                    break;
                }
                case 'H': case 'h': {
                    x = ps.number() + (rel ? x : 0);
                    path.lineTo(x, y);
                    first = false;
                    break;
                }
                case 'V': case 'v': {
                    y = ps.number() + (rel ? y : 0);
                    path.lineTo(x, y);
                    first = false;
                    break;
                }
                case 'A': case 'a': {
                    float rx = ps.number();
                    float ry = ps.number();
                    // 旋转角：本套图标全是 0，照读不照用
                    ps.number();
                    boolean largeArc = ps.number() != 0;
                    boolean sweep = ps.number() != 0;
                    float nx = ps.number() + (rel ? x : 0);
                    float ny = ps.number() + (rel ? y : 0);
                    appendArc(path, x, y, Math.abs(rx), Math.abs(ry), largeArc, sweep, nx, ny);
                    x = nx; y = ny;
                    first = false;
                    break;
                }
                case 'Z': case 'z': {
                    path.close();
                    x = sx; y = sy;
                    first = true;
                    break;
                }
                default:
                    // 套内数据不含其它命令，出现即是路径写错了，直接抛好过画错
                    throw new IllegalArgumentException("unsupported cmd: " + cmd);
            }
        }
        return path;
    }

    private static boolean isCommandChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /**
     * 端点参数化 → 圆心参数化（W3C SVG F.6.5），再交给 Path.arcTo。
     *
     * 弧的起点就是当前点，arcTo 自带的「连到弧起点」直线段长度为零，不影响形状。
     */
    private static void appendArc(Path path, float x1, float y1,
                                  float rx, float ry, boolean largeArc, boolean sweep,
                                  float x2, float y2) {
        if (x1 == x2 && y1 == y2) {
            return; // 零长度弧按规范跳过
        }
        float dx2 = (x1 - x2) / 2f;
        float dy2 = (y1 - y2) / 2f;
        float x1p = dx2;
        float y1p = dy2; // 旋转角恒为 0，坐标系变换直接省掉
        float rxsq = rx * rx;
        float rysq = ry * ry;
        float x1psq = x1p * x1p;
        float y1psq = y1p * y1p;
        float lambda = x1psq / rxsq + y1psq / rysq;
        float co;
        if (lambda > 1f) {
            // 半径过小，按规范放大到能容纳弦
            float s = (float) Math.sqrt(lambda);
            rx *= s;
            ry *= s;
            co = 0f;
        } else {
            float num = rxsq * rysq - rxsq * y1psq - rysq * x1psq;
            float den = rxsq * y1psq + rysq * x1psq;
            co = (float) Math.sqrt(Math.max(0f, num / den));
            if (largeArc == sweep) {
                co = -co;
            }
        }
        float cxp = co * rx * y1p / ry;
        float cyp = -co * ry * x1p / rx;
        float cx = cxp + (x1 + x2) / 2f;
        float cy = cyp + (y1 + y2) / 2f;
        float ux = (x1p - cxp) / rx;
        float uy = (y1p - cyp) / ry;
        float vx = (-x1p - cxp) / rx;
        float vy = (-y1p - cyp) / ry;
        float start = (float) Math.toDegrees(Math.atan2(uy, ux));
        float delta = vectorAngle(ux, uy, vx, vy);
        if (!sweep && delta > 0) {
            delta -= 2f * (float) Math.PI;
        } else if (sweep && delta < 0) {
            delta += 2f * (float) Math.PI;
        }
        RectF oval = new RectF(cx - rx, cy - ry, cx + rx, cy + ry);
        path.arcTo(oval, start, (float) Math.toDegrees(delta));
    }

    /** u 到 v 的有符号夹角：叉积定方向，绝对值用 acos */
    private static float vectorAngle(float ux, float uy, float vx, float vy) {
        float dot = ux * vx + uy * vy;
        float ulen = (float) Math.sqrt(ux * ux + uy * uy);
        float vlen = (float) Math.sqrt(vx * vx + vy * vy);
        float a = (float) Math.acos(Math.max(-1f, Math.min(1f, dot / (ulen * vlen))));
        if (ux * vy - uy * vx < 0) {
            a = -a;
        }
        return a;
    }

    /** 迷你数字扫描器：支持 ".5.5"（两个数）、"-1.645"、"17.32" 这类紧挨着的写法 */
    private static final class Parser {
        private final String d;
        private int i;

        Parser(String d) {
            this.d = d;
        }

        boolean atEnd() {
            return i >= d.length();
        }

        char peek() {
            return d.charAt(i);
        }

        void skip() {
            i++;
        }

        float number() {
            while (i < d.length() && (d.charAt(i) == ' ' || d.charAt(i) == ',')) {
                i++;
            }
            int start = i;
            if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+')) {
                i++;
            }
            boolean dot = false;
            while (i < d.length()) {
                char c = d.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    i++;
                } else {
                    break;
                }
            }
            if (i == start) {
                throw new IllegalArgumentException("bad number at " + i + " in: " + d);
            }
            return Float.parseFloat(d.substring(start, i));
        }
    }
}
