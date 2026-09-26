package com.palixander.scalesync.ui.text

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.palixander.scalesync.R
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

    private fun localizedResources(locale: Locale) =
        ApplicationProvider.getApplicationContext<Context>()
            .createConfigurationContext(
                ApplicationProvider.getApplicationContext<Context>().resources.configuration.apply {
                    setLocale(locale)
                },
            ).resources
}
