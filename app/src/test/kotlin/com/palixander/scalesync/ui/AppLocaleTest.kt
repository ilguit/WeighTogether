package com.palixander.scalesync.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppLocaleTest {
    private lateinit var processLocale: Locale

    @Before
    fun rememberProcessLocale() {
        processLocale = Locale.getDefault()
    }

    @After
    fun restoreProcessLocale() {
        Locale.setDefault(processLocale)
    }

    @Test
    fun resourcesLocaleWinsWhenAppLocaleDiffersFromProcessDefault() {
        Locale.setDefault(Locale.US)
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.GERMANY)
        }

        val appResources = base.createConfigurationContext(configuration).resources

        assertEquals(Locale.GERMANY, appResources.appLocale)
    }
}
