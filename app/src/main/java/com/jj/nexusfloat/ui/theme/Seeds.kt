package com.jj.nexusfloat.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * MD3 tonal 动态色：整套配色由一个种子色相（hue）生成，对应重设计稿的
 * `--h` CSS 变量方案。六组种子，每组只换 hue，饱和度/明度模板固定。
 *
 * 深浅两套模板数值来自 app-ui-redesign.html 的 CSS token：
 *   深色：primary hsl(h 82% 78%) / surface hsl(h 14% 6%) / card hsl(h 12% 12.5%) ...
 *   浅色：primary hsl(h 68% 38%) / surface hsl(h 28% 98.5%) / card hsl(h 26% 95.5%) ...
 */
object UiSeeds {
    val ORDER = listOf("blue", "violet", "teal", "green", "amber", "rose")

    val NAMES = mapOf(
        "blue" to "钴蓝", "violet" to "堇紫", "teal" to "青碧",
        "green" to "苔绿", "amber" to "琥珀", "rose" to "玫红"
    )

    val HUES = mapOf(
        "blue" to 218, "violet" to 264, "teal" to 184,
        "green" to 148, "amber" to 32, "rose" to 342
    )

    fun hueOf(key: String): Int = HUES[key] ?: -1
}

/** HSL 转 ARGB Color（h: 0-360, s/l: 0-100） */
fun hslColor(h: Int, s: Int, l: Int): Color {
    val hf = ((h % 360) + 360) % 360 / 360.0
    val sf = s.coerceIn(0, 100) / 100.0
    val lf = l.coerceIn(0, 100) / 100.0
    val c = (1 - Math.abs(2 * lf - 1)) * sf
    val hp = hf * 6.0
    val x = c * (1 - Math.abs(hp % 2 - 1))
    val (r1, g1, b1) = when {
        hp < 1 -> Triple(c, x, 0.0)
        hp < 2 -> Triple(x, c, 0.0)
        hp < 3 -> Triple(0.0, c, x)
        hp < 4 -> Triple(0.0, x, c)
        hp < 5 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    val m = lf - c / 2
    return Color(
        red = ((r1 + m) * 255).toFloat() / 255f,
        green = ((g1 + m) * 255).toFloat() / 255f,
        blue = ((b1 + m) * 255).toFloat() / 255f,
        alpha = 1f
    )
}

/**
 * 按种子色相生成整套 NexusColors。字段映射对照重设计稿 token，
 * 与 Theme.kt 里手写的钴蓝（h=218）深浅两套在视觉上等价。
 */
fun nexusColorsForSeed(hue: Int, isDark: Boolean): NexusColors {
    val h = ((hue % 360) + 360) % 360
    return if (isDark) NexusColors(
        primary = hslColor(h, 82, 78),
        onPrimary = hslColor(h, 55, 15),
        primaryContainer = hslColor(h, 42, 28),
        onPrimaryContainer = hslColor(h, 78, 92),
        surface = hslColor(h, 14, 6),
        cardSurface = hslColor(h, 12, 12),
        onSurface = hslColor(h, 16, 94),
        onSurfaceVariant = hslColor(h, 11, 68),
        cardStroke = hslColor(h, 9, 24),
        statusConnected = Color(0xFF5EEAD4),
        statusConnectedBg = hslColor(170, 44, 19),
        statusDisconnected = Color(0xFFFFB4AB),
        statusDisconnectedBg = hslColor(4, 52, 23),
        toggleOnContainer = hslColor(h, 17, 21),
        toggleOnBorder = hslColor(h, 45, 50),
        toggleOffContainer = hslColor(h, 12, 9),
        subItemGuide = hslColor(h, 9, 24),
        isDark = true
    ) else NexusColors(
        primary = hslColor(h, 68, 38),
        onPrimary = hslColor(h, 70, 99),
        primaryContainer = hslColor(h, 78, 89),
        onPrimaryContainer = hslColor(h, 65, 17),
        surface = hslColor(h, 28, 98),
        cardSurface = hslColor(h, 26, 95),
        onSurface = hslColor(h, 20, 12),
        onSurfaceVariant = hslColor(h, 13, 40),
        cardStroke = hslColor(h, 18, 84),
        statusConnected = Color(0xFF007A63),
        statusConnectedBg = hslColor(170, 55, 88),
        statusDisconnected = Color(0xFFB3261E),
        statusDisconnectedBg = hslColor(4, 80, 94),
        toggleOnContainer = hslColor(h, 30, 88),
        toggleOnBorder = hslColor(h, 45, 55),
        toggleOffContainer = hslColor(h, 24, 92),
        subItemGuide = hslColor(h, 18, 84),
        isDark = false
    )
}
