package com.fasterdownloads.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Day / night selection for the app chrome. */
enum class ThemeMode {
    /** Follow the Android system setting (default until the user picks something). */
    SYSTEM,
    /** Neon canvas, black ink. */
    DAY,
    /** Black canvas, neon ink. */
    NIGHT,
    ;

    val storageValue: String
        get() = when (this) {
            SYSTEM -> "system"
            DAY -> "day"
            NIGHT -> "night"
        }

    companion object {
        fun fromStorage(value: String?): ThemeMode = when (value) {
            "day" -> DAY
            "night" -> NIGHT
            else -> SYSTEM
        }
    }
}

/**
 * Remembers the day/night choice across launches.
 *
 * The value lives in a [StateFlow] so the activity can rebuild its system-bar style and the
 * theme can flip as soon as the switch is tapped, without an activity restart.
 */
object ThemePreference {

    private const val PREFS = "faster_downloads"
    private const val KEY_THEME_MODE = "theme_mode"

    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)

    /** Current selection; starts as [ThemeMode.SYSTEM] until [load] runs. */
    val mode: StateFlow<ThemeMode> = _mode

    fun load(context: Context) {
        val stored = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, null)
        _mode.value = ThemeMode.fromStorage(stored)
    }

    fun set(context: Context, mode: ThemeMode) {
        _mode.value = mode
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.storageValue)
            .apply()
    }

    /** Resolves the mode against the system setting right now. */
    fun isNight(context: Context, mode: ThemeMode = _mode.value): Boolean = when (mode) {
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
        ThemeMode.SYSTEM ->
            (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
