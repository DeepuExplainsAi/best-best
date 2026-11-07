package app.dkord.data.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** English-first application locales, including pre-Android-13 resources used by workers/services. */
object AppLanguages {
    private const val PREFERENCES = "app-language"
    private const val LANGUAGE = "language"
    private const val PLATFORM_INITIALIZED = "platform-initialized"
    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private fun country() = Resources.getSystem().configuration.locales[0]?.country.orEmpty()

    fun initialize(context: Context) {
        val preferences = preferences(context)
        val saved = preferences.getString(LANGUAGE, null)
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val platform = manager.applicationLocales[0]
            val selected = resolveAppLanguage(saved, platform?.toLanguageTag(), platformManaged = true,
                platformInitialized = preferences.getBoolean(PLATFORM_INITIALIZED, false))
            preferences.edit().putString(LANGUAGE, selected.tag).putBoolean(PLATFORM_INITIALIZED, true).apply()
            if (AppLanguage.fromTag(platform?.toLanguageTag()) != selected || (platform?.country.isNullOrEmpty() && country().isNotEmpty())) {
                manager.applicationLocales = LocaleList.forLanguageTags(appLanguageTag(selected, country()))
            }
        } else {
            val selected = resolveAppLanguage(saved)
            val tag = appLanguageTag(selected, country())
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            applyLegacyResources(context, tag)
        }
    }

    fun selected(context: Context): AppLanguage = if (Build.VERSION.SDK_INT >= 33) {
        AppLanguage.fromTag(context.getSystemService(LocaleManager::class.java).applicationLocales[0]?.toLanguageTag()) ?: AppLanguage.ENGLISH
    } else resolveAppLanguage(preferences(context).getString(LANGUAGE, null))

    fun select(context: Context, language: AppLanguage) {
        preferences(context).edit().putString(LANGUAGE, language.tag).apply()
        val tag = appLanguageTag(language, country())
        // AppCompat recreates the Activity; saved Compose navigation and the singleton player survive.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        if (Build.VERSION.SDK_INT < 33) applyLegacyResources(context, tag)
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyResources(context: Context, tag: String) {
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val resources = context.applicationContext.resources
        val configuration = Configuration(resources.configuration)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
}
