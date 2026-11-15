/*
 * Adaptation stub for the standalone HUD APK build.
 * Replaces the DroidDeck launcher's SessionPrefs with a thin SharedPreferences
 * wrapper exposing exactly the members the hud-port sources consume:
 *   droidDeckHudConfig / hudShowFps / hudShowGraph / hudOpacity / theme /
 *   hudEnabled / droidDeckHudPosition / setDroidDeckHudConfig /
 *   setDroidDeckHudPosition / setHudEnabled
 */
package com.droiddeck.launcher.session

import android.content.Context
import android.content.SharedPreferences

object SessionPrefs {
    private const val FILE = "hud_session"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun droidDeckHudConfig(context: Context): String? =
        prefs(context).getString("droidDeckHudConfig", null)

    fun setDroidDeckHudConfig(context: Context, value: String) {
        prefs(context).edit().putString("droidDeckHudConfig", value).apply()
    }

    fun hudShowFps(context: Context): Boolean = prefs(context).getBoolean("hudShowFps", true)

    fun hudShowGraph(context: Context): Boolean = prefs(context).getBoolean("hudShowGraph", true)

    fun hudOpacity(context: Context): Int = prefs(context).getInt("hudOpacity", 80)

    fun theme(context: Context): String = prefs(context).getString("hudTheme", "default") ?: "default"

    fun setTheme(context: Context, value: String) {
        prefs(context).edit().putString("hudTheme", value).apply()
    }

    fun hudEnabled(context: Context): Boolean = prefs(context).getBoolean("hudEnabled", true)

    fun setHudEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("hudEnabled", value).apply()
    }

    fun droidDeckHudPosition(context: Context): Pair<Float, Float>? {
        val p = prefs(context)
        if (!p.contains("hudX") || !p.contains("hudY")) return null
        return Pair(p.getFloat("hudX", 0f), p.getFloat("hudY", 0f))
    }

    fun setDroidDeckHudPosition(context: Context, x: Float, y: Float) {
        prefs(context).edit().putFloat("hudX", x).putFloat("hudY", y).apply()
    }
}
