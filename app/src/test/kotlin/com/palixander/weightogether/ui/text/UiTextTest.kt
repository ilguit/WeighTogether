package com.palixander.weightogether.ui.text

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.palixander.weightogether.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UiTextTest {
    @Test
    fun `resource text resolves arguments only at presentation boundary`() {
        val resources = localizedResources(Locale.ENGLISH)

        assertEquals("Step 2 of 3", uiText(R.string.unsaved_preview_step, 2).resolve(resources))
    }

    @Test
    fun `plural text follows active resource locale`() {
        assertEquals("kilograms", pluralUiText(R.plurals.reference_spoken_kg, 2).resolve(localizedResources(Locale.ENGLISH)))
        assertEquals("килограмма", pluralUiText(R.plurals.reference_spoken_kg, 2).resolve(localizedResources(Locale.forLanguageTag("ru"))))
        assertEquals("килограммов", pluralUiText(R.plurals.reference_spoken_kg, 5).resolve(localizedResources(Locale.forLanguageTag("ru"))))
    }

    @Test
    fun `percent resource without arguments remains literal in each locale`() {
        for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("ru"))) {
            assertEquals("%", uiText(R.string.unit_percent).resolve(localizedResources(locale)))
        }
    }

    @Test
    fun `raw percent and format tokens remain literal`() {
        assertEquals("50% %1\$s", UiText.Raw("50% %1\$s").resolve(localizedResources(Locale.ENGLISH)))
    }

    @Test
    fun `nested resource argument preserves literal percent`() {
        assertEquals(
            "18.7 %",
            uiText(R.string.measurement_value_with_unit, "18.7", uiText(R.string.unit_percent))
                .resolve(localizedResources(Locale.ENGLISH)),
        )
    }

    @Test
    fun `joined text resolves raw resource and formatted children`() {
        assertEquals(
            "50% · % · Step 2 of 3",
            UiText.Joined(
                listOf(UiText.Raw("50%"), uiText(R.string.unit_percent), uiText(R.string.unsaved_preview_step, 2)),
                separator = " · ",
            ).resolve(localizedResources(Locale.ENGLISH)),
        )
    }

    @Test
    fun `plural text still formats supplied arguments`() {
        val resources = localizedResources(Locale.ENGLISH)
        assertEquals("1 person", pluralUiText(R.plurals.settings_people_count, 1, 1).resolve(resources))
        assertEquals("2 people", pluralUiText(R.plurals.settings_people_count, 2, 2).resolve(resources))
    }

    private fun localizedResources(locale: Locale) =
        ApplicationProvider.getApplicationContext<Context>()
            .createConfigurationContext(
                Configuration(ApplicationProvider.getApplicationContext<Context>().resources.configuration).apply {
                    setLocale(locale)
                },
            ).resources
}
