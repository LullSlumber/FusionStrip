/*
 * Adaptation stub for the standalone HUD APK build.
 * Members consumed by DroidDeckHudView:
 *   Themes.current(context) / Themes.INNER_GAMER / Themes.rgbHue /
 *   Themes.rgbSpeed / Themes.rgbCycleMillis(speed) / Themes.RGB_HUE_STEP /
 *   Themes.moveRgbHue(hue) / Themes.innerGamer(hue)
 */
package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.droiddeck.launcher.session.SessionPrefs

object Themes {
    const val INNER_GAMER = "inner_gamer"

    /** Hue step the view quantises the flowing RGB animation to. */
    const val RGB_HUE_STEP = 10f

    /** One full 360° cycle lasts cycleMillis at rgbSpeed == 1. */
    private const val CYCLE_MILLIS = 6_000L

    @Volatile private var hue = 0f

    val rgbHue: Float get() = hue

    var rgbSpeed: Float = 1f

    fun rgbCycleMillis(speed: Float): Long = (CYCLE_MILLIS / speed.coerceAtLeast(0.01f)).toLong()

    fun moveRgbHue(value: Float) {
        hue = value % 360f
    }

    fun current(context: Context): Palette = when (SessionPrefs.theme(context)) {
        INNER_GAMER -> innerGamer(hue)
        else -> staticPalette(context)
    }

    fun innerGamer(hueValue: Float): Palette = Palette(
        primary = Color.hsv(hueValue % 360f, 0.85f, 1f),
        surface = Color(0xFF121417),
    )

    private fun staticPalette(context: Context): Palette {
        val hex = context.getSharedPreferences("hud_session", Context.MODE_PRIVATE)
            .getString("hudAccentHex", null)
        val color = hex?.let {
            runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull()
        } ?: Color(0xFF1A9FFF)
        return Palette(primary = color, surface = Color(0xFF121417))
    }
}
