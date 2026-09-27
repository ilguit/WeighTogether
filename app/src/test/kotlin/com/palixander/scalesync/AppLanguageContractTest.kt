package com.palixander.scalesync

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

class AppLanguageContractTest {
    private val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun `locale config declares exactly every supported language`() {
        val config = xml("res/xml/locales_config.xml")
        val locales = config.getElementsByTagName("locale")
        val tags = (0 until locales.length).map { index ->
            (locales.item(index) as Element).getAttributeNS(android, "name")
        }

        assertEquals(listOf("en", "ru", "de", "fr", "it", "ja", "zh", "be", "uk"), tags)
        assertEquals(AppLanguage.supportedLanguageTags, tags)
    }

    @Test
    fun `manifest publishes locale config and enables AndroidX persistence fallback`() {
        val manifest = xml("AndroidManifest.xml")
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/locales_config", application.getAttributeNS(android, "localeConfig"))

        val services = manifest.getElementsByTagName("service")
        val localeService = (0 until services.length)
            .map { services.item(it) as Element }
            .firstOrNull {
                it.getAttributeNS(android, "name") ==
                    "androidx.appcompat.app.AppLocalesMetadataHolderService"
            }
        assertNotNull(localeService)
        assertEquals("false", localeService!!.getAttributeNS(android, "enabled"))
        assertEquals("false", localeService.getAttributeNS(android, "exported"))
        val metadata = localeService.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("autoStoreLocales", metadata.getAttributeNS(android, "name"))
        assertEquals("true", metadata.getAttributeNS(android, "value"))
    }

    @Test
    fun `selection maps supported tags and unknown values to system default`() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, AppLanguage.fromLanguageTags(language.languageTag))
        }
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromLanguageTags("ru,en"))
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromLanguageTags("es"))
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromLanguageTags(""))
    }

    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(main, path)).documentElement
}
