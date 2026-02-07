package com.vettid.app.core.storage

import android.content.Context
import android.content.SharedPreferences
import com.vettid.app.features.settings.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-sensitive app preferences (theme, UI settings).
 * Uses regular SharedPreferences since theme choice is not sensitive data.
 */
class AppPreferencesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeFlow = MutableStateFlow(getTheme())
    val themeFlow: StateFlow<AppTheme> = _themeFlow.asStateFlow()

    fun getTheme(): AppTheme {
        val name = prefs.getString(KEY_THEME, AppTheme.AUTO.name) ?: AppTheme.AUTO.name
        return try {
            AppTheme.valueOf(name)
        } catch (_: IllegalArgumentException) {
            AppTheme.AUTO
        }
    }

    fun setTheme(theme: AppTheme) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        _themeFlow.value = theme
    }

    companion object {
        private const val PREFS_NAME = "app_preferences"
        private const val KEY_THEME = "app_theme"
    }
}
