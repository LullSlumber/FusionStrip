/*
 * Adaptation stub for the standalone HUD APK build.
 * Palette holds the two androidx.compose.ui.graphics.Color members
 * DroidDeckHudView.applyPalette() consumes (palette.primary / palette.surface).
 */
package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Color

data class Palette(
    val primary: Color,
    val surface: Color,
)
