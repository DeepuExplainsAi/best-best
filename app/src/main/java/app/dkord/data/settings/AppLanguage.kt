package app.dkord.data.settings

import java.util.Locale

/** Only languages with real app translations belong in the picker. Never infer from phone language. */
enum class AppLanguage(val tag: String) {
    ENGLISH("en"), HINDI("hi");

    companion object {
        fun fromTag(tag: String?): AppLanguage? = entries.firstOrNull {
            it.tag == tag?.substringBefore('-')?.substringBefore('_')?.lowercase(Locale.ROOT)
        }
    }
}

internal fun resolveAppLanguage(
    savedTag: String?,
    platformTag: String? = null,
    platformManaged: Boolean = false,
    platformInitialized: Boolean = false,
): AppLanguage = when {
    !platformManaged -> AppLanguage.fromTag(savedTag) ?: AppLanguage.ENGLISH
    !platformTag.isNullOrBlank() -> AppLanguage.fromTag(platformTag) ?: AppLanguage.ENGLISH
    !platformInitialized -> AppLanguage.fromTag(savedTag) ?: AppLanguage.ENGLISH
    else -> AppLanguage.ENGLISH // Clearing Android's app-language override means our English default.
}

/** Keep the device's regional formatting/content country when changing only the interface language. */
internal fun appLanguageTag(language: AppLanguage, country: String): String =
    if (country.matches(Regex("[A-Za-z]{2}|[0-9]{3}"))) "${language.tag}-${country.uppercase(Locale.ROOT)}" else language.tag
