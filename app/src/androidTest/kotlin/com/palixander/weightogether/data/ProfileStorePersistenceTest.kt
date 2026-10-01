package com.palixander.weightogether.data

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
    fun pauseBooleanSurvivesStoreRecreationAndResumePersists() {
        ProfileStore(context).setExternalSyncPaused(true)

        assertEquals(true, ProfileStore(context).settings.value.externalSyncPaused)

        ProfileStore(context).setExternalSyncPaused(false)
        assertEquals(false, ProfileStore(context).settings.value.externalSyncPaused)
    }

    @Test
    fun futureLegacyDeadlineMigratesToPersistentPauseAcrossRestart() {
        preferences().edit()
            .putLong("external_sync_paused_until_epoch_millis", 123_456L)
            .commit()

        assertEquals(true, ProfileStore(context, nowEpochMillis = { 100_000L }).externalSyncPaused)
        assertEquals(true, ProfileStore(context, nowEpochMillis = { 999_999L }).externalSyncPaused)
        assertEquals(false, preferences().contains("external_sync_paused_until_epoch_millis"))
    }

    @Test
    fun expiredLegacyDeadlineMigratesToResumedAcrossRestart() {
        preferences().edit()
            .putLong("external_sync_paused_until_epoch_millis", 123_456L)
            .commit()

        assertEquals(false, ProfileStore(context, nowEpochMillis = { 123_456L }).externalSyncPaused)
        assertEquals(false, ProfileStore(context, nowEpochMillis = { 1L }).externalSyncPaused)
        assertEquals(false, preferences().contains("external_sync_paused_until_epoch_millis"))
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

    @Test
    fun applyingPortableSettingsRefreshesFlowAndPreservesDeviceLocalPause() {
        val store = ProfileStore(context)
        store.setExternalSyncPaused(true)

        store.applyPortableSettings(
            PortableProfileSettings(
                scaleAddress = "aa:bb",
                scaleName = "Imported",
                reliabilityMode = true,
                selectedChartMetricKeys = emptySet(),
                homeKgChartSeriesKeys = setOf("weight_kg"),
            ),
        )

        assertEquals("AA:BB", store.settings.value.scaleAddress)
        assertEquals("Imported", store.settings.value.scaleName)
        assertEquals(true, store.settings.value.reliabilityMode)
        assertEquals(emptySet<String>(), store.settings.value.selectedChartMetricKeys)
        assertEquals(setOf("weight_kg"), store.settings.value.homeKgChartSeriesKeys)
        assertEquals(true, store.settings.value.externalSyncPaused)
        assertEquals(true, ProfileStore(context).externalSyncPaused)
    }

    private fun preferences() =
        context.getSharedPreferences("mi_sync_settings", Context.MODE_PRIVATE)
}
