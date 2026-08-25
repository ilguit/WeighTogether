package com.example.huaweimisync.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val scaleAddress: String? = null,
    val scaleName: String? = null,
    val reliabilityMode: Boolean = false,
    val selectedChartMetricKeys: Set<String>? = null,
    val homeKgChartSeriesKeys: Set<String>? = null,
    val externalSyncPausedUntilEpochMillis: Long = 0L,
)

/** Settings that are meaningful when moved to another installation or device. */
data class PortableProfileSettings(
    val scaleAddress: String?,
    val scaleName: String?,
    val reliabilityMode: Boolean,
    val selectedChartMetricKeys: Set<String>?,
    val homeKgChartSeriesKeys: Set<String>?,
)

fun AppSettings.toPortableSnapshot(): PortableProfileSettings = PortableProfileSettings(
    scaleAddress = scaleAddress,
    scaleName = scaleName,
    reliabilityMode = reliabilityMode,
    selectedChartMetricKeys = selectedChartMetricKeys?.toSet(),
    homeKgChartSeriesKeys = homeKgChartSeriesKeys?.toSet(),
)

interface ExternalSyncPauseSettingsStore {
    val externalSyncPausedUntilEpochMillis: Long

    fun setExternalSyncPausedUntilEpochMillis(value: Long)
}

class ProfileStore(context: Context) : ExternalSyncPauseSettingsStore {
    private val preferences = context.getSharedPreferences("mi_sync_settings", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    fun portableSnapshot(): PortableProfileSettings = settings.value.toPortableSnapshot()

    fun applyPortableSettings(value: PortableProfileSettings) {
        val committed = preferences.edit().run {
            if (value.scaleAddress == null) remove(KEY_SCALE_ADDRESS)
            else putString(KEY_SCALE_ADDRESS, value.scaleAddress.uppercase())
            if (value.scaleName == null) remove(KEY_SCALE_NAME)
            else putString(KEY_SCALE_NAME, value.scaleName)
            putBoolean(KEY_RELIABILITY, value.reliabilityMode)
            if (value.selectedChartMetricKeys == null) remove(KEY_SELECTED_CHART_METRICS)
            else putStringSet(KEY_SELECTED_CHART_METRICS, value.selectedChartMetricKeys.toSet())
            if (value.homeKgChartSeriesKeys == null) remove(KEY_HOME_KG_CHART_SERIES)
            else putStringSet(KEY_HOME_KG_CHART_SERIES, value.homeKgChartSeriesKeys.toSet())
            commit()
        }
        check(committed) { "Could not durably commit imported settings" }
        refresh()
    }

    fun saveScale(address: String, name: String?) {
        preferences.edit {
            putString(KEY_SCALE_ADDRESS, address.uppercase())
            putString(KEY_SCALE_NAME, name ?: "MIBFS")
        }
        refresh()
    }

    fun clearScale() {
        preferences.edit {
            remove(KEY_SCALE_ADDRESS)
            remove(KEY_SCALE_NAME)
        }
        refresh()
    }

    fun setReliabilityMode(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_RELIABILITY, enabled) }
        refresh()
    }

    fun saveSelectedChartMetricKeys(keys: Set<String>) {
        preferences.edit { putStringSet(KEY_SELECTED_CHART_METRICS, keys.toSet()) }
        refresh()
    }

    fun saveHomeKgChartSeriesKeys(keys: Set<String>) {
        preferences.edit { putStringSet(KEY_HOME_KG_CHART_SERIES, keys.toSet()) }
        refresh()
    }

    override val externalSyncPausedUntilEpochMillis: Long
        get() = settings.value.externalSyncPausedUntilEpochMillis

    override fun setExternalSyncPausedUntilEpochMillis(value: Long) {
        preferences.edit {
            if (value > 0L) {
                putLong(KEY_EXTERNAL_SYNC_PAUSED_UNTIL, value)
            } else {
                remove(KEY_EXTERNAL_SYNC_PAUSED_UNTIL)
            }
        }
        refresh()
    }

    private fun refresh() {
        mutableSettings.value = read()
    }

    private fun read(): AppSettings {
        return AppSettings(
            scaleAddress = preferences.getString(KEY_SCALE_ADDRESS, null),
            scaleName = preferences.getString(KEY_SCALE_NAME, null),
            reliabilityMode = preferences.getBoolean(KEY_RELIABILITY, false),
            selectedChartMetricKeys = preferences
                .getStringSet(KEY_SELECTED_CHART_METRICS, null)
                ?.toSet(),
            homeKgChartSeriesKeys = preferences
                .getStringSet(KEY_HOME_KG_CHART_SERIES, null)
                ?.toSet(),
            externalSyncPausedUntilEpochMillis = preferences.getLong(
                KEY_EXTERNAL_SYNC_PAUSED_UNTIL,
                0L,
            ),
        )
    }

    private companion object {
        const val KEY_SCALE_ADDRESS = "scale_address"
        const val KEY_SCALE_NAME = "scale_name"
        const val KEY_RELIABILITY = "reliability"
        const val KEY_SELECTED_CHART_METRICS = "selected_chart_metrics"
        const val KEY_HOME_KG_CHART_SERIES = "home_kg_chart_series"
        const val KEY_EXTERNAL_SYNC_PAUSED_UNTIL = "external_sync_paused_until_epoch_millis"
    }
}
