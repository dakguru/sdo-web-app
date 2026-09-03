package com.karursdo.ui.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** User-selected appearance, persisted across launches (not sensitive — plain prefs). */
@Singleton
class ThemeController @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY, ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)
    )
    val mode: StateFlow<ThemeMode> = _mode

    fun setMode(mode: ThemeMode) {
        _mode.value = mode
        prefs.edit().putString(KEY, mode.name).apply()
    }

    private val _palette = MutableStateFlow(
        runCatching { AppPalette.valueOf(prefs.getString(KEY_PAL, AppPalette.ROYAL_NAVY.name)!!) }
            .getOrDefault(AppPalette.ROYAL_NAVY)
    )
    /** The selected premium colour palette, applied on top of the light/dark [mode]. */
    val palette: StateFlow<AppPalette> = _palette

    fun setPalette(p: AppPalette) {
        _palette.value = p
        prefs.edit().putString(KEY_PAL, p.name).apply()
    }

    private companion object { const val KEY = "theme_mode"; const val KEY_PAL = "theme_palette" }
}
