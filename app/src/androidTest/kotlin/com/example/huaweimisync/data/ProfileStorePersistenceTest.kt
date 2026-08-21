package com.example.huaweimisync.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ProfileStorePersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences("mi_sync_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun pauseDeadlineSurvivesStoreRecreationAndResumeRemovesIt() {
        ProfileStore(context).setExternalSyncPausedUntilEpochMillis(123_456L)

        assertEquals(123_456L, ProfileStore(context).settings.value.externalSyncPausedUntilEpochMillis)

        ProfileStore(context).setExternalSyncPausedUntilEpochMillis(0L)
        assertEquals(0L, ProfileStore(context).settings.value.externalSyncPausedUntilEpochMillis)
    }

    @Test
    fun homeChartSelectionDistinguishesMissingAndEmptyAndSurvivesRecreation() {
        assertNull(ProfileStore(context).settings.value.homeKgChartSeriesKeys)

        ProfileStore(context).saveHomeKgChartSeriesKeys(emptySet())
        assertEquals(emptySet<String>(), ProfileStore(context).settings.value.homeKgChartSeriesKeys)

        val selected = linkedSetOf("weight_kg", "bone_mass_kg")
        ProfileStore(context).saveHomeKgChartSeriesKeys(selected)
        assertEquals(selected, ProfileStore(context).settings.value.homeKgChartSeriesKeys)
    }
}
