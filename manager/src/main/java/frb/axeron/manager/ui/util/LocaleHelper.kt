package frb.axeron.manager.ui.util

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import frb.axeron.api.core.AxeronSettings
import java.util.Locale

object LocaleHelper {
    
    /**
     * Check if should use system language settings (Android 13+)
     */
    val useSystemLanguageSettings: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    
    /**
     * Launch system app locale settings (Android 13+)
     */
    fun launchSystemLanguageSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                // Fallback to app language settings if system settings not available
            }
        }
    }
    
    /**
     * Apply saved language setting to context (for Android < 13)
     */
    fun applyLanguage(context: Context): Context {
        // On Android 13+, language is handled by system
        if (useSystemLanguageSettings) {
            return context
        }

        val prefs = try {
            AxeronSettings.getPreferences()
        } catch (_: Exception) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        }
        val localeTag = prefs.getString(AxeronSettings.LANGUAGE, "system") ?: "system"

        return if (localeTag == "system" || localeTag == "SYSTEM") {
            context
        } else {
            val locale = parseLocaleTag(localeTag)
            setLocale(context, locale)
        }
    }
    
    /**
     * Set locale for context (Android < 13)
     */
    private fun setLocale(context: Context, locale: Locale): Context {
        return updateResources(context, locale)
    }
    
    private fun updateResources(context: Context, locale: Locale): Context {
        val configuration = Configuration()
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
    
    @Suppress("DEPRECATION")
    @SuppressWarnings("deprecation")
    private fun updateResourcesLegacy(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val resources = context.resources
        val configuration = resources.configuration
        configuration.locale = locale
        configuration.setLayoutDirection(locale)
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return context
    }
    
    /**
     * Parse locale tag to Locale object
     */
    private fun parseLocaleTag(tag: String): Locale {
        return try {
            if (tag.contains("_")) {
                val parts = tag.split("_")
                Locale.Builder()
                    .setLanguage(parts[0])
                    .setRegion(parts.getOrNull(1) ?: "")
                    .build()
            } else {
                Locale.Builder()
                    .setLanguage(tag)
                    .build()
            }
        } catch (_: Exception) {
            Locale.getDefault()
        }
    }
    
    /**
     * 根据当前应用语言返回对应的「AI 输出语言」指令文本。
     * 用于注入到 AI 的 system prompt，让 AI 按用户所选语言作答。
     */
    fun languageInstruction(context: Context): String {
        val locale = getCurrentAppLocale(context)
        val lang = locale?.language ?: Locale.getDefault().language
        return when {
            lang.startsWith("zh") -> "请始终使用简体中文回答。"
            lang == "en" -> "Please always respond in English."
            else -> "请始终使用简体中文回答。"
        }
    }

    /**
     * Get current app locale
     */
    fun getCurrentAppLocale(context: Context): Locale? {
        return if (useSystemLanguageSettings) {
            // Android 13+ - get from system app locale settings
            try {
                val localeManager = context.getSystemService(Context.LOCALE_SERVICE) as? LocaleManager
                val locales = localeManager?.applicationLocales
                if (locales != null && !locales.isEmpty) {
                    locales.get(0)
                } else {
                    null // System default
                }
            } catch (_: Exception) {
                null // System default
            }
        } else {
            // Android < 13 - get from SharedPreferences
            val prefs = try {
                AxeronSettings.getPreferences()
            } catch (_: Exception) {
                context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            }
            val localeTag = prefs.getString(AxeronSettings.LANGUAGE, "system") ?: "system"
            if (localeTag == "system" || localeTag == "SYSTEM") {
                null // System default
            } else {
                parseLocaleTag(localeTag)
            }
        }
    }
}