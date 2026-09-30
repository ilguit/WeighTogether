package com.palixander.scalesync.charts

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.palixander.scalesync.domain.AccountId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AnalyticalChartSettingsStoreTest {
    @Test fun persistedAccountSettingsRestoreAfterStoreRecreationIncludingEmptySelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("analytical_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val a = AccountId("a")
        val b = AccountId("b")
        val store = PreferenceAnalyticalChartSettingsStore(prefs)
        val expected = listOf(AnalyticalChartSettings(AnalyticalChartType.MORNING, emptySet(), MorningWindow(400, 550), MorningFilterMode.AUTOMATIC))
        store.write(a, expected)
        store.write(b, listOf(AnalyticalChartSettings(AnalyticalChartType.HOURLY)))
        assertEquals(expected, PreferenceAnalyticalChartSettingsStore(prefs).read(a))
        store.write(a, emptyList())
        assertTrue(PreferenceAnalyticalChartSettingsStore(prefs).read(a).isEmpty())
        assertEquals(AnalyticalChartType.HOURLY, PreferenceAnalyticalChartSettingsStore(prefs).read(b).single().type)
    }
}
