package app.dkord

import app.dkord.data.settings.AppLanguage
import app.dkord.data.settings.appLanguageTag
import app.dkord.data.settings.resolveAppLanguage
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class AppLanguageTest {
    @Test fun default_is_english_even_on_hindi_or_other_language_phones() {
        val previous = Locale.getDefault()
        try {
            for (phone in listOf("hi-IN", "ta-IN", "ar-SA", "fr-FR", "en-IN")) {
                Locale.setDefault(Locale.forLanguageTag(phone))
                assertEquals(AppLanguage.ENGLISH, resolveAppLanguage(null))
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun only_an_explicit_saved_supported_language_overrides_english() {
        assertEquals(AppLanguage.HINDI, resolveAppLanguage("hi"))
        assertEquals(AppLanguage.ENGLISH, resolveAppLanguage("en"))
        for (unsupported in listOf("", "ta", "fr", "system", "malformed")) {
            assertEquals(AppLanguage.ENGLISH, resolveAppLanguage(unsupported))
        }
        assertEquals(setOf("en", "hi"), AppLanguage.entries.map { it.tag }.toSet())
    }

    @Test fun android_app_language_selection_is_explicit_and_authoritative() {
        assertEquals(AppLanguage.HINDI, resolveAppLanguage("en", "hi-IN", platformManaged = true, platformInitialized = true))
        assertEquals(AppLanguage.ENGLISH, resolveAppLanguage("hi", "en-US", platformManaged = true, platformInitialized = true))
        assertEquals(AppLanguage.ENGLISH, resolveAppLanguage("hi", "fr-FR", platformManaged = true, platformInitialized = true))
    }

    @Test fun upgrading_to_android_13_retains_an_existing_explicit_choice() {
        assertEquals(AppLanguage.HINDI, resolveAppLanguage("hi", platformManaged = true))
        assertEquals(AppLanguage.ENGLISH, resolveAppLanguage(null, platformManaged = true))
    }

    @Test fun clearing_android_app_language_returns_to_english_not_phone_or_stale_choice() {
        assertEquals(AppLanguage.ENGLISH, resolveAppLanguage("hi", platformManaged = true, platformInitialized = true))
        assertEquals(AppLanguage.ENGLISH, resolveAppLanguage("hi", "", platformManaged = true, platformInitialized = true))
    }

    @Test fun choosing_interface_language_keeps_country_and_accepts_supported_regional_tags() {
        assertEquals("en-IN", appLanguageTag(AppLanguage.ENGLISH, "IN"))
        assertEquals("hi-GB", appLanguageTag(AppLanguage.HINDI, "gb"))
        assertEquals("en", appLanguageTag(AppLanguage.ENGLISH, ""))
        assertEquals("en", appLanguageTag(AppLanguage.ENGLISH, "invalid"))
        assertEquals(AppLanguage.HINDI, AppLanguage.fromTag("HI_in"))
        assertNull(AppLanguage.fromTag("ta-IN"))
    }
}
