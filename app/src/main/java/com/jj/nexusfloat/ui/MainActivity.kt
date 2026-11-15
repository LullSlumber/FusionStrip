package com.jj.nexusfloat.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.hud.DroidDeckHudConfig
import com.droiddeck.launcher.hud.DroidDeckHudSize
import com.droiddeck.launcher.session.SessionPrefs
import com.jj.nexusfloat.constant.Constants
import com.jj.nexusfloat.hud.FusionStripView
import com.jj.nexusfloat.service.GpuCollectorLauncher
import com.jj.nexusfloat.service.GpuCollectorWorker
import com.jj.nexusfloat.ui.theme.MdThemeOnSurface
import com.jj.nexusfloat.ui.theme.MdThemeOnSurfaceVariant
import com.jj.nexusfloat.ui.theme.MdThemePrimary
import com.jj.nexusfloat.ui.theme.MdThemeSurface
import com.jj.nexusfloat.ui.theme.NexusFloatTheme
import com.jj.nexusfloat.ui.theme.UiSeeds
import com.jj.nexusfloat.utils.ExecUtils
import com.jj.nexusfloat.utils.LogUtils

/**
 * FusionStrip 主界面：单页滚动的 DroidDeck 横条监控设置。
 *
 * 页面结构：标题卡（主题/强调色/总开关）→ HUD 渲染（尺寸/不透明度）→
 * 读数项目（DroidDeck 配置键全量开关）→ 强调线（开关 + 8 色板）→ 关于。
 *
 * 全部渲染设置写进 DroidDeckHudConfig 的整包 JSON，经 SettingsChannel
 * 同步到 SystemUI 侧的 FusionStripView；NexusFloat 时代的模块翻译层已废弃。
 */
class MainActivity : ComponentActivity() {

    // ---- 界面主题 ----
    private var appTheme by mutableStateOf(Constants.Modules.APP_THEME_SYSTEM)
    private var uiSeed by mutableStateOf("blue")
    private var uiDynamicColor by mutableStateOf(false)

    // ---- HUD 渲染 ----
    private var hudEnabled by mutableStateOf(true)
    private var hudSize by mutableStateOf("strip")
    private var hudOpacity by mutableStateOf(88)
    private var hudCfg by mutableStateOf(mapOf<String, String>())

    // ---- 强调线 ----
    private var stripLineEnabled by mutableStateOf(true)
    private var stripLineColorIndex by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadAll()
        setContent {
            val dark = when (appTheme) {
                Constants.Modules.APP_THEME_LIGHT -> false
                Constants.Modules.APP_THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            NexusFloatTheme(
                darkTheme = dark,
                seedHue = UiSeeds.hueOf(uiSeed),
                dynamicColor = uiDynamicColor && Build.VERSION.SDK_INT >= 31
            ) {
                Box(modifier = Modifier.fillMaxSize().background(MdThemeSurface)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        HeaderCard()
                        MasterSwitchCard()
                        HudRenderCard()
                        MetricsCard()
                        AccentLineCard()
                        AboutCard()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 前台期间明确要一份 GPU 采集，退后台撤掉
        GpuCollectorWorker.setUiDemand(true)
        GpuCollectorLauncher.startInProcess()
        publishSettings()
    }

    override fun onStop() {
        GpuCollectorWorker.setUiDemand(false)
        super.onStop()
    }

    // ================= 状态载入 =================

    private fun loadAll() {
        appTheme = readInt(Constants.Modules.KEY_APP_THEME, Constants.Modules.APP_THEME_SYSTEM)
        uiSeed = readString(Constants.Modules.KEY_UI_SEED, "blue")
        uiDynamicColor = readSwitch(Constants.Modules.KEY_UI_DYNAMIC_COLOR, default = false)
        hudEnabled = readSwitch(Constants.Modules.KEY_HUD_ENABLED, default = true)
        hudSize = readString(Constants.Modules.KEY_HUD_SIZE, "strip")
        hudOpacity = readInt(Constants.Modules.KEY_HUD_OPACITY, 88)
        stripLineEnabled = readSwitch(Constants.Modules.KEY_STRIP_LINE, default = true)
        stripLineColorIndex = readInt(Constants.Modules.KEY_STRIP_LINE_COLOR, 0)
        // 渲染配置：main prefs 的整包 JSON 优先（跨进程同步的那份），
        // 没有就落到 SessionPrefs 里的本地副本
        val cfgJson = readString(Constants.Modules.KEY_DROIDDECK_CFG, "")
        hudCfg = parseCfg(cfgJson, DroidDeckHudConfig.load(this))
    }

    private fun parseCfg(json: String?, fallback: Map<String, String>): Map<String, String> {
        if (json.isNullOrBlank()) return fallback
        val out = mutableMapOf<String, String>()
        json.split(",").forEach { pair ->
            val i = pair.indexOf('=')
            if (i > 0) out[pair.substring(0, i)] = pair.substring(i + 1)
        }
        return out
    }

    /**
     * 渲染配置写回：本地副本（SessionPrefs）+ 跨进程同步键（main prefs）+
     * SettingsChannel 推送，三步缺一不可。
     */
    private fun saveCfg(values: Map<String, String>) {
        hudCfg = values
        val json = DroidDeckHudConfig.encode(values)
        SessionPrefs.setDroidDeckHudConfig(this, json)
        saveString(Constants.Modules.KEY_DROIDDECK_CFG, json)
        publishSettings()
    }

    private fun editCfg(edit: (MutableMap<String, String>) -> Unit) {
        val values = hudCfg.toMutableMap()
        edit(values)
        saveCfg(values)
    }

    private fun publishSettings() {
        Thread { com.jj.nexusfloat.bridge.SettingsChannel.publish(applicationContext) }.start()
    }

    private fun restartSystemUi() {
        Toast.makeText(this, "正在请求 Root 重启 SystemUI...", Toast.LENGTH_SHORT).show()
        Thread {
            val out = ExecUtils.exec("killall com.android.systemui || pkill -f com.android.systemui")
            LogUtils.i("Restart SystemUI result: $out")
        }.start()
    }

    // ================= 读写 helpers（main prefs，SettingsChannel 同步那份） =================

    private val localPrefs by lazy {
        getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun readString(key: String, default: String): String =
        localPrefs.getString(key, default) ?: default

    private fun saveString(key: String, value: String) {
        localPrefs.edit().putString(key, value).apply()
    }

    private fun readInt(key: String, default: Int): Int =
        localPrefs.getInt(key, default)

    private fun saveInt(key: String, value: Int) {
        localPrefs.edit().putInt(key, value).apply()
    }

    private fun readSwitch(key: String, default: Boolean = true): Boolean =
        localPrefs.getBoolean(key, default)

    private fun saveSwitch(key: String, enabled: Boolean) {
        localPrefs.edit().putBoolean(key, enabled).apply()
    }

    // ================= 界面 =================

    @Composable
    private fun HeaderCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "FusionStrip",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MdThemeOnSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "DroidDeck 横条监控 · 基于 NexusFloat（GPLv3）",
                            fontSize = 12.sp,
                            color = MdThemeOnSurfaceVariant
                        )
                    }
                    if (versionName.isNotEmpty()) {
                        Text(
                            text = "v$versionName",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MdThemePrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 总开关：关掉后横条隐藏、采集停掉
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用监视条", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MdThemeOnSurface)
                        Text(
                            if (hudEnabled) "横条显示中" else "已隐藏，采集一并停止",
                            fontSize = 11.sp,
                            color = MdThemeOnSurfaceVariant
                        )
                    }
                    Switch(
                        checked = hudEnabled,
                        onCheckedChange = { checked ->
                            hudEnabled = checked
                            saveSwitch(Constants.Modules.KEY_HUD_ENABLED, checked)
                            publishSettings()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 界面主题三段
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Constants.Modules.APP_THEME_SYSTEM to "跟随系统",
                        Constants.Modules.APP_THEME_LIGHT to "浅色",
                        Constants.Modules.APP_THEME_DARK to "深色"
                    ).forEach { (value, label) ->
                        val selected = appTheme == value
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MdThemeOnSurface else MdThemeOnSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable { appTheme = value; saveInt(Constants.Modules.KEY_APP_THEME, value) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 强调色种子（Material You 动态取色在下方单独开关）
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UiSeeds.ORDER.forEach { key ->
                        val selected = key == uiSeed
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(seedColor(key))
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = if (selected) MdThemeOnSurface else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { uiSeed = key; saveString(Constants.Modules.KEY_UI_SEED, key) }
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "动态取色",
                            fontSize = 12.sp,
                            color = MdThemeOnSurfaceVariant,
                            modifier = Modifier.clickable {
                                if (Build.VERSION.SDK_INT >= 31) {
                                    uiDynamicColor = !uiDynamicColor
                                    saveSwitch(Constants.Modules.KEY_UI_DYNAMIC_COLOR, uiDynamicColor)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = uiDynamicColor,
                            enabled = Build.VERSION.SDK_INT >= 31,
                            onCheckedChange = { checked ->
                                uiDynamicColor = checked
                                saveSwitch(Constants.Modules.KEY_UI_DYNAMIC_COLOR, checked)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun seedColor(key: String): Color {
        // 与 UiSeeds.HUES 对应的展示色（hsl 72% 56%）
        val hue = UiSeeds.hueOf(key)
        // 简易 HSL→Color：与 ui/theme/Seeds.kt 同一套换算
        return hslDisplay(hue)
    }

    private fun hslDisplay(h: Int): Color {
        val hf = (h % 360) / 360.0
        val c = (1 - Math.abs(2 * 0.56 - 1)) * 0.72
        val x = c * (1 - Math.abs((hf * 6) % 2 - 1))
        val m = 0.56 - c / 2
        val (r, g, b) = when ((hf * 6).toInt()) {
            0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        return Color(
            red = ((r + m) * 255).toFloat() / 255f,
            green = ((g + m) * 255).toFloat() / 255f,
            blue = ((b + m) * 255).toFloat() / 255f
        )
    }

    @Composable
    private fun HudRenderCard() {
        SettingsCard(title = "HUD 渲染") {
            Text(
                text = "渲染本体为 DroidDeck HUD；STRIP 横条为最低开销形态",
                fontSize = 11.sp,
                color = MdThemeOnSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text("尺寸", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MdThemeOnSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                DroidDeckHudSize.entries.forEach { size ->
                    val selected = size.token == hudSize
                    Text(
                        text = size.label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MdThemeOnSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { hudSize = size.token; saveString(Constants.Modules.KEY_HUD_SIZE, size.token); editCfg { it["hudSize"] = size.token } }
                            .padding(vertical = 10.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text("不透明度", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MdThemeOnSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("−", fontSize = 20.sp, modifier = Modifier.clickable {
                    hudOpacity = (hudOpacity - 5).coerceIn(0, 100)
                    saveInt(Constants.Modules.KEY_HUD_OPACITY, hudOpacity)
                    editCfg { it["hudOpacity"] = hudOpacity.toString() }
                })
                Slider(
                    value = hudOpacity.toFloat(),
                    onValueChange = { v ->
                        hudOpacity = ((v / 5f).toInt() * 5).coerceIn(0, 100)
                        saveInt(Constants.Modules.KEY_HUD_OPACITY, hudOpacity)
                        editCfg { it["hudOpacity"] = hudOpacity.toString() }
                    },
                    onValueChangeFinished = { publishSettings() },
                    valueRange = 0f..100f,
                    steps = 19,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                )
                Text("＋", fontSize = 20.sp, modifier = Modifier.clickable {
                    hudOpacity = (hudOpacity + 5).coerceIn(0, 100)
                    saveInt(Constants.Modules.KEY_HUD_OPACITY, hudOpacity)
                    editCfg { it["hudOpacity"] = hudOpacity.toString() }
                })
                Text("$hudOpacity%", fontSize = 12.sp, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
            }
        }
    }

    @Composable
    private fun MetricsCard() {
        SettingsCard(title = "读数项目") {
            Text(
                text = "全部对应 DroidDeck 渲染配置，改动约 1 秒内生效",
                fontSize = 11.sp,
                color = MdThemeOnSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            METRICS.forEach { (key, label) ->
                val on = hudCfg[key] != "0"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = on,
                        onCheckedChange = { checked -> editCfg { it[key] = if (checked) "1" else "0" } },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    }

    @Composable
    private fun AccentLineCard() {
        SettingsCard(title = "强调线") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Constants.Modules.LABEL_STRIP_LINE,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = stripLineEnabled,
                    onCheckedChange = { checked ->
                        stripLineEnabled = checked
                        saveSwitch(Constants.Modules.KEY_STRIP_LINE, checked)
                    }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Constants.Ui.STRIP_LINE_COLORS.forEachIndexed { index, argb ->
                    val selected = index == stripLineColorIndex
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MdThemeOnSurface else MdThemeOnSurfaceVariant.copy(alpha = 0.4f),
                                shape = CircleShape
                            )
                            .clickable { stripLineColorIndex = index; saveInt(Constants.Modules.KEY_STRIP_LINE_COLOR, index) }
                    )
                }
            }
        }
    }

    @Composable
    private fun AboutCard() {
        SettingsCard(title = "关于") {
            Text(
                text = "GPL-3.0 开源 · HUD 移植自 DroidDeck（FusionHUD，The412Banner）\n" +
                        "底座基于 NexusFloat（酷安@叶落雨巷）\n" +
                        "github.com/The412Banner/FusionHUD",
                fontSize = 12.sp,
                color = MdThemeOnSurfaceVariant,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "重启系统界面（横条没出现或改了注入相关设置时用）",
                fontSize = 12.sp,
                color = MdThemePrimary,
                modifier = Modifier.clickable { restartSystemUi() }
            )
        }
    }

    @Composable
    private fun SettingsCard(title: String, content: @Composable () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MdThemeOnSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                content()
            }
        }
    }

    private val versionName: String by lazy {
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private companion object {
        /** 读数项目：DroidDeck 配置键 → 界面标签（STRIP 默认全集） */
        val METRICS = listOf(
            "showFPS" to "帧率",
            "showFPSGraph" to "帧率曲线",
            "showLow1" to "Low 1%",
            "showLow01" to "Low 0.1%",
            "showLow001" to "Low 0.01%",
            "showGPULoad" to "GPU 占用",
            "showGpuTemp" to "GPU 温度",
            "showCPUUsage" to "CPU 占用",
            "showTemp" to "CPU 温度",
            "showVram" to "显存",
            "showRAM" to "内存",
            "showBattery" to "电池",
            "showBatteryTemp" to "电池温度",
            "showBatteryTime" to "预计续航",
            "showPower" to "功耗",
            "showDisplay" to "显示模式",
            "showHdr" to "HDR",
            "showGpuModel" to "GPU 型号"
        )
    }
}
