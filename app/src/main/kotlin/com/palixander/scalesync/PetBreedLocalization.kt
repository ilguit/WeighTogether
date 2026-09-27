package com.palixander.scalesync

import com.palixander.scalesync.domain.PetSpecies
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Presentation-only breed localization. Catalog IDs and source strings remain untouched. */
internal object PetBreedLocalization {
    private val supportedLanguages = setOf("be", "de", "en", "fr", "it", "ja", "ru", "uk", "zh")

    fun displayName(
        englishName: String,
        russianName: String,
        species: PetSpecies,
        locale: Locale,
    ): String = when (locale.language.takeIf(supportedLanguages::contains) ?: "en") {
        "en" -> englishName
        "ru" -> russianName
        "uk" -> russianName.translateWords(ukrainianWords)
            .replace('ы', 'и').replace('э', 'е').replace('ё', 'ь')
        "be" -> russianName.translateWords(belarusianWords)
            .replace("и", "і").replace("щ", "шч")
        "de" -> englishName.translateWords(germanWords)
        "fr" -> englishName.translateWords(frenchWords)
        "it" -> englishName.translateWords(italianWords)
        "ja" -> "$englishName（${if (species == PetSpecies.CAT) "猫種" else "犬種"}）"
        "zh" -> "$englishName（${if (species == PetSpecies.CAT) "猫种" else "犬种"}）"
        else -> englishName
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun comparator(locale: Locale): Comparator<PetBreedOption> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        return Comparator { left, right ->
            collator.compare(left.displayName, right.displayName)
                .takeUnless { it == 0 }
                ?: left.canonicalName.compareTo(right.canonicalName, ignoreCase = true)
                    .takeUnless { it == 0 }
                ?: left.id.value.compareTo(right.id.value)
        }
    }

    private fun String.translateWords(words: Map<String, String>): String =
        words.entries.fold(this) { value, (source, target) ->
            value.replace(source, target, ignoreCase = true)
        }

    private val ukrainianWords = mapOf(
        "русский" to "російський", "русская" to "російська", "чёрный" to "чорний",
        "немецкий" to "німецький", "немецкая" to "німецька", "итальянский" to "італійський",
        "английский" to "англійський", "шотландский" to "шотландський", "шотландская" to "шотландська",
        "китайская" to "китайська", "тайский" to "тайський", "тайская" to "тайська",
        "американский" to "американський", "американская" to "американська",
        "австралийская" to "австралійська", "среднеазиатская" to "середньоазійська",
        "восточноевропейская" to "східноєвропейська", "овчарка" to "вівчарка",
        "короткошёрстный" to "короткошерстий", "короткошёрстная" to "короткошерста",
        "длинношёрстный" to "довгошерстий", "длинношёрстная" to "довгошерста",
        "гладкошёрстная" to "гладкошерста", "миниатюрный" to "мініатюрний",
        "миниатюрная" to "мініатюрна", "золотистый" to "золотистий", "голубая" to "блакитна",
    )
    private val belarusianWords = mapOf(
        "русский" to "рускі", "русская" to "руская", "чёрный" to "чорны", "немецкий" to "нямецкі",
        "немецкая" to "нямецкая", "английский" to "англійскі", "итальянский" to "італьянскі",
        "шотландский" to "шатландскі", "шотландская" to "шатландская", "китайская" to "кітайская",
        "американский" to "амерыканскі", "американская" to "амерыканская",
        "австралийская" to "аўстралійская", "среднеазиатская" to "сярэднеазіяцкая",
        "восточноевропейская" to "усходнееўрапейская", "овчарка" to "аўчарка",
        "короткошёрстный" to "кароткашэрсны", "короткошёрстная" to "кароткашэрсная",
        "длинношёрстный" to "даўгашэрсны", "длинношёрстная" to "даўгашэрсная",
        "гладкошёрстная" to "гладкашэрсная", "миниатюрный" to "мініяцюрны",
        "миниатюрная" to "мініяцюрная", "золотистый" to "залацісты", "голубая" to "блакітная",
    )
    private val germanWords = mapOf(
        "American" to "Amerikanischer", "Australian" to "Australischer", "British" to "Britisch Kurzhaar",
        "English" to "Englische", "German" to "Deutscher", "Italian" to "Italienisches",
        "Japanese" to "Japanischer", "Russian" to "Russischer", "Scottish" to "Schottische",
        "Chinese" to "Chinesischer", "Thai" to "Thailändischer", "Norwegian" to "Norwegische",
        "Shepherd Dog" to "Schäferhund", "Shepherd" to "Schäferhund", "Forest Cat" to "Waldkatze",
        "Shorthair" to "Kurzhaar", "Short-Haired" to "Kurzhaar", "Smooth-Haired" to "Kurzhaar",
        "Longhair" to "Langhaar", "Long-Haired" to "Langhaar", "Miniature" to "Zwerg",
        "Black And Silver" to "Schwarz und Silber", "Pure Black With Black Undercoat" to "Schwarz",
        "Pepper And Salt" to "Pfeffer und Salz", "Blue" to "Blau", "Golden" to "Golden",
    )
    private val frenchWords = mapOf(
        "American" to "américain", "Australian" to "australien", "British" to "britannique",
        "English" to "anglais", "German" to "allemand", "Italian" to "italien", "Japanese" to "japonais",
        "Russian" to "russe", "Scottish" to "écossais", "Chinese" to "chinois", "Thai" to "thaïlandais",
        "Norwegian" to "norvégien", "Shepherd Dog" to "berger", "Shepherd" to "berger",
        "Forest Cat" to "chat des forêts", "Shorthair" to "à poil court", "Short-Haired" to "à poil court",
        "Smooth-Haired" to "à poil ras", "Longhair" to "à poil long", "Long-Haired" to "à poil long",
        "Miniature" to "nain", "Black And Silver" to "noir et argent", "Pepper And Salt" to "poivre et sel",
        "Pure Black With Black Undercoat" to "noir", "Blue" to "bleu", "Golden" to "doré",
    )
    private val italianWords = mapOf(
        "American" to "americano", "Australian" to "australiano", "British" to "britannico",
        "English" to "inglese", "German" to "tedesco", "Italian" to "italiano", "Japanese" to "giapponese",
        "Russian" to "russo", "Scottish" to "scozzese", "Chinese" to "cinese", "Thai" to "tailandese",
        "Norwegian" to "norvegese", "Shepherd Dog" to "pastore", "Shepherd" to "pastore",
        "Forest Cat" to "gatto delle foreste", "Shorthair" to "a pelo corto", "Short-Haired" to "a pelo corto",
        "Smooth-Haired" to "a pelo raso", "Longhair" to "a pelo lungo", "Long-Haired" to "a pelo lungo",
        "Miniature" to "nano", "Black And Silver" to "nero e argento", "Pepper And Salt" to "pepe e sale",
        "Pure Black With Black Undercoat" to "nero", "Blue" to "blu", "Golden" to "dorato",
    )
}
