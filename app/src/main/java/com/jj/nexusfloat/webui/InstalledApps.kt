package com.jj.nexusfloat.webui

import android.content.Context
import android.content.Intent
import com.jj.nexusfloat.utils.LogUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * WebUI 应用选择器的数据源。
 *
 * 逻辑搬自 ui/AppPickerScreen.kt 的 loadInstalledApps()，但这里不依赖 Compose：
 * 那个文件带一堆 androidx.compose import，WebUI 只想拿一份已装应用列表，
 * 直接复用整个文件会把 Compose 依赖钉死在桥接链路上。
 *
 * QUERY_ALL_PACKAGES 已在 AndroidManifest 声明（安装时授予的 normal 权限），
 * Android 11 起没有它 getInstalledApplications/queryIntentActivities 基本返回空。
 * 只列本地应用，不联网。
 */
object InstalledApps {

    /**
     * @param includeAll true = 全部已安装应用；false = 只列有启动图标的
     * @return JSON 数组字符串：[{"pkg":"...","label":"..."}, ...]，按 label 排序
     */
    fun load(context: Context, includeAll: Boolean): String {
        val pm = context.packageManager
        val self = context.packageName
        val entries: List<Pair<String, String>> = try {
            val packages: List<String> = if (includeAll) {
                pm.getInstalledApplications(0).map { it.packageName }
            } else {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
            }
            packages.asSequence()
                .distinct()
                .filter { it != self }
                .map { pkg ->
                    val label = try {
                        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                    } catch (e: Exception) {
                        pkg
                    }
                    pkg to label
                }
                .sortedBy { it.second.lowercase() }
                .toList()
        } catch (e: Exception) {
            LogUtils.w("InstalledApps.load failed", e)
            emptyList()
        }

        val arr = JSONArray()
        for ((pkg, label) in entries) {
            arr.put(JSONObject().put("pkg", pkg).put("label", label))
        }
        return arr.toString()
    }
}
