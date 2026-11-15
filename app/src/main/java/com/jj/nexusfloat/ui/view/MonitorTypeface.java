package com.jj.nexusfloat.ui.view;

import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 监视条内置字体加载器（v1.9.4-lull.7）。
 *
 * 原版监视条所有文字固定 Typeface.MONOSPACE，在设备上实际显示的是系统等宽字体，
 * 跟用户在别的界面看到的字体不一致。这里把字体文件打进模块 APK 的 assets
 * （segoeui.ttf / segoeuib.ttf，用户自用字体），在 SystemUI 进程里第一次
 * 建监视条时从模块 APK 里解出来、落到 SystemUI 的 cache 目录再 createFromFile。
 *
 * 为什么绕这么一圈：SystemUI 进程里的 Context 是 SystemUI 的，
 * createFromAsset 只能读自己 APK 的 assets，读不了模块的；而模块 APK
 * 文件本身对所有被注入的进程可读（LSPosed 注入的机制决定），所以
 * ZipFile 直接开模块 APK 解 assets 是最稳的一条路。
 *
 * 任何一步失败都保持 null：MonitorView 拿到 null 会回退到原来的
 * MONOSPACE 行为，监视条不至于白屏。
 *
 * 字重语义（lull.7）：get(true) 只在有真粗体文件时返回非 null——
 * 调用方拿到 null 就走 MONOSPACE+BOLD 的老路，绝不拿常规字重的
 * Typeface 去凑加粗位。而且调用方拿到非 null 后直接 setTypeface(tf)
 * 不带样式：createFromFile 出来的样式标记是常规，再传 BOLD 会触发
 * Skia 伪加粗（描边涂抹），笔画发糊发胀，真机已验证。
 */
public final class MonitorTypeface {

    private static Typeface sRegular;
    private static Typeface sBold;
    private static boolean sAttempted;

    private MonitorTypeface() {
    }

    /**
     * 从模块 APK 里解出并加载字体。重复调用无害（只生效一次）。
     *
     * @param moduleApkPath 模块自身 APK 的绝对路径（ApplicationInfo.sourceDir）
     * @param cacheDir      写释放文件的目录（SystemUI 的 cache 目录）
     */
    public static void load(String moduleApkPath, File cacheDir) {
        if (sAttempted || moduleApkPath == null || cacheDir == null) {
            return;
        }
        sAttempted = true;
        File regular = extractFromApk(moduleApkPath,
                "assets/segoeui.ttf", new File(cacheDir, "nf_segoeui.ttf"));
        File bold = extractFromApk(moduleApkPath,
                "assets/segoeuib.ttf", new File(cacheDir, "nf_segoeuib.ttf"));
        try {
            if (regular != null) {
                sRegular = Typeface.createFromFile(regular);
            }
            if (bold != null) {
                sBold = Typeface.createFromFile(bold);
            }
        } catch (Throwable ignored) {
            // 字体文件损坏之类的情况：保持 null，回退等宽
        }
    }

    /**
     * 取当前字重要用的字体。
     *
     * bold=true 时只有真粗体文件在场才返回，否则返回 null 让调用方
     * 回退 MONOSPACE+BOLD；bold=false 时返回常规体（可能为 null）。
     */
    public static Typeface get(boolean bold) {
        return bold ? sBold : sRegular;
    }

    /** 从 APK 里解出一个 entry 到目标文件，失败返回 null */
    private static File extractFromApk(String apkPath, String entry, File outFile) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(apkPath);
            ZipEntry e = zip.getEntry(entry);
            if (e == null) {
                return null;
            }
            InputStream in = zip.getInputStream(e);
            OutputStream out = new FileOutputStream(outFile);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            return outFile;
        } catch (Throwable t) {
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
