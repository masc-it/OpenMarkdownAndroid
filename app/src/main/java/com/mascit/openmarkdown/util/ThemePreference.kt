package com.mascit.openmarkdown.util

import android.content.Context
import android.content.SharedPreferences

enum class ThemeMode { LIGHT, DARK }

class ThemePreference(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, null)
        if (name != null) {
            return try { ThemeMode.valueOf(name) }
            catch (_: IllegalArgumentException) { resolveSystemDefault() }
        }
        // First launch — seed from system, persist
        return resolveSystemDefault().also { setThemeMode(it) }
    }

    private fun resolveSystemDefault(): ThemeMode {
        val nightMask = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            ThemeMode.DARK else ThemeMode.LIGHT
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "openmarkdown_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
