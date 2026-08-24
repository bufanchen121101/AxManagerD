package org.lsposed.lspatch.ui.appearance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsposed.lspatch.lspApp
import org.matrix.vector.ui.ambience.AmbienceSettings
import org.matrix.vector.ui.appearance.AppearanceSettings
import org.matrix.vector.ui.locale.LocaleController

/** LSPatch's brand seed (warm amber), the default accent when dynamic colour is off. */
const val LSPATCH_SEED: Int = 0xFFE08A3C.toInt()

/**
 * LSPatch's appearance + language preferences, persisted in the app's `settings` store.
 *
 * The shared appearance controls and the status header are written against plain values and
 * callbacks, so this is the whole of LSPatch's binding to them: each preference is a [StateFlow] the
 * UI collects, and a setter that writes the pref and pushes the new value. Mirrors what Vector keeps
 * in its SettingsRepository, minus everything LSPatch has no use for.
 */
object LSPSettings : AppearanceSettings, LocaleController {
    private val prefs
        get() = lspApp.prefs

    private val _themeMode = MutableStateFlow(prefs.getString(KEY_THEME_MODE, "system") ?: "system")
    override val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    override fun setThemeMode(value: String) {
        prefs.edit().putString(KEY_THEME_MODE, value).apply()
        _themeMode.value = value
    }

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, false))
    override val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    override fun setDynamicColor(value: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
        _dynamicColor.value = value
    }

    private val _seedColor = MutableStateFlow(prefs.getInt(KEY_SEED_COLOR, LSPATCH_SEED))
    override val seedColor: StateFlow<Int> = _seedColor.asStateFlow()

    override fun setSeedColor(value: Int) {
        prefs.edit().putInt(KEY_SEED_COLOR, value).apply()
        _seedColor.value = value
    }

    private val _amoledBlack = MutableStateFlow(prefs.getBoolean(KEY_AMOLED, false))
    override val amoledBlack: StateFlow<Boolean> = _amoledBlack.asStateFlow()

    override fun setAmoledBlack(value: Boolean) {
        prefs.edit().putBoolean(KEY_AMOLED, value).apply()
        _amoledBlack.value = value
    }

    private val _headerAmbience =
        MutableStateFlow(prefs.getString(KEY_AMBIENCE, "maze") ?: "maze")
    override val headerAmbience: StateFlow<String> = _headerAmbience.asStateFlow()

    override fun setHeaderAmbience(key: String) {
        prefs.edit().putString(KEY_AMBIENCE, key).apply()
        _headerAmbience.value = key
    }


    private val _appLocale = MutableStateFlow(prefs.getString(KEY_LOCALE, "") ?: "")
    override val appLocale: StateFlow<String> = _appLocale.asStateFlow()

    override fun setAppLocale(tag: String) {
        prefs.edit().putString(KEY_LOCALE, tag).apply()
        _appLocale.value = tag
    }

    // Exactly the languages Vector ships, plus English (the base resource set): the picker must not
    // offer a language there is no translation for. Keep in step with the values-* folders below.
    override val availableTags =
        listOf(
            "en", "ar", "de", "es", "fa", "fr", "in", "it", "iw", "ja", "ko", "pl", "pt-BR", "ru",
            "tr", "uk", "vi", "zh-CN", "zh-TW",
        )

    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_SEED_COLOR = "seed_color"
    private const val KEY_AMOLED = "amoled_black"
    private const val KEY_AMBIENCE = "header_ambience"
    private const val KEY_LOCALE = "app_locale"
}

/**
 * Where the header ambience lives, persisted so a rotation or relaunch puts it back — the LSPatch
 * equivalent of Vector's VectorAmbienceSettings.
 */
object LSPAmbienceSettings : AmbienceSettings {
    private val prefs
        get() = lspApp.prefs

    override fun scale(key: String): Float = prefs.getFloat("ambience_scale_$key", 1f)

    override fun speed(key: String): Float = prefs.getFloat("ambience_speed_$key", 1f)

    override fun variant(key: String): Int = prefs.getInt("ambience_variant_$key", 0)

    override fun setScale(key: String, value: Float) {
        prefs.edit().putFloat("ambience_scale_$key", value).apply()
    }

    override fun setSpeed(key: String, value: Float) {
        prefs.edit().putFloat("ambience_speed_$key", value).apply()
    }

    override fun setVariant(key: String, value: Int) {
        prefs.edit().putInt("ambience_variant_$key", value).apply()
    }
}
