package com.palixander.weightogether

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

internal enum class AppLanguage(
    val languageTag: String,
    @param:StringRes val labelRes: Int,
) {
    SYSTEM_DEFAULT("", R.string.settings_language_system_default),
    ENGLISH("en", R.string.settings_language_english),
    RUSSIAN("ru", R.string.settings_language_russian),
    GERMAN("de", R.string.settings_language_german),
    FRENCH("fr", R.string.settings_language_french),
    ITALIAN("it", R.string.settings_language_italian),
    JAPANESE("ja", R.string.settings_language_japanese),
    CHINESE("zh", R.string.settings_language_chinese),
    BELARUSIAN("be", R.string.settings_language_belarusian),
    UKRAINIAN("uk", R.string.settings_language_ukrainian),
    ;

    companion object {
        val supportedLanguageTags: List<String> = entries.drop(1).map(AppLanguage::languageTag)

        fun fromLanguageTags(languageTags: String): AppLanguage {
            val firstTag = languageTags.substringBefore(',').trim()
            if (firstTag.isEmpty()) return SYSTEM_DEFAULT
            return entries.firstOrNull { it.languageTag.equals(firstTag, ignoreCase = true) }
                ?: SYSTEM_DEFAULT
        }
    }
}

internal object AppLanguageManager {
    fun current(): AppLanguage = AppLanguage.fromLanguageTags(
        AppCompatDelegate.getApplicationLocales().toLanguageTags(),
    )

    fun select(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(language.languageTag),
        )
    }
}
