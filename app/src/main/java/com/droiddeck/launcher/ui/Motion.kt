/*
 * Adaptation stub for the standalone HUD APK build.
 * Motion scales animation durations; DroidDeckHudView treats scale == 0f as
 * "animations off" and applies positions directly.
 */
package com.droiddeck.launcher.ui

import android.provider.Settings

object Motion {
    @Volatile
    var scale: Float = 1f
        private set

    /** Reads Settings.Global.ANIMATOR_DURATION_SCALE; falls back to 1f. */
    fun refresh(androidContentResolver: android.content.ContentResolver) {
        scale = runCatching {
            Settings.Global.getFloat(
                androidContentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            )
        }.getOrDefault(1f)
    }

    /** Duration in ms for the given nominal duration, scaled by the animator scale. */
    fun ms(nominalMs: Int): Int = (nominalMs * scale).toInt().coerceAtLeast(0)
}
