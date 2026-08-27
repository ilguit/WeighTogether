package com.example.huaweimisync.data

import android.content.Context
import androidx.core.content.edit
import com.example.huaweimisync.worker.ExternalSyncOperationSerializer
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
    // Missing keys from installations created before #43 intentionally mean enabled.
    val healthConnectSyncEnabled: Boolean = true,
    val huaweiSyncEnabled: Boolean = true,
)

/** Settings that are meaningful when moved to another installation or device. */
data class PortableProfileSettings(
    val scaleAddress: String?,
    val scaleName: String?,
    val reliabilityMode: Boolean,
    val selectedChartMetricKeys: Set<String>?,
    val homeKgChartSeriesKeys: Set<String>?,
)

data class VersionedPortableProfileSettings(
    val settings: PortableProfileSettings,
    val revision: Long,
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

interface ExternalSyncDestinationSettingsStore {
    fun isExternalSyncEnabled(destination: ExternalSyncDestination): Boolean

    /** Persists the device-local opt-out before publishing it to readers. */
    fun setExternalSyncEnabled(destination: ExternalSyncDestination, enabled: Boolean)
}

class ProfileStore(
    context: Context,
    private val portableOperations: ExternalSyncOperationSerializer = ExternalSyncOperationSerializer(),
) : ExternalSyncPauseSettingsStore, ExternalSyncDestinationSettingsStore {
    private val preferences = context.getSharedPreferences("mi_sync_settings", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    @Volatile
    private var versionedPortable = VersionedPortableProfileSettings(
        mutableSettings.value.toPortableSnapshot(),
        0L,
    )
    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    fun portableSnapshot(): PortableProfileSettings = settings.value.toPortableSnapshot()

    fun versionedPortableSnapshot(): VersionedPortableProfileSettings = versionedPortable

    fun applyPortableSettings(value: PortableProfileSettings) {
        portableOperations.runExclusiveBlocking { applyPortableSettingsWithinExclusiveOperation(value) }
    }

    fun applyPortableSettingsWithinExclusiveOperation(value: PortableProfileSettings) {
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
        refreshPortable()
    }

    fun saveScale(address: String, name: String?) {
        portableOperations.runExclusiveBlocking {
            preferences.edit {
                putString(KEY_SCALE_ADDRESS, address.uppercase())
                putString(KEY_SCALE_NAME, name ?: "MIBFS")
            }
            refreshPortable()
        }
    }

    fun clearScale() {
        portableOperations.runExclusiveBlocking {
            preferences.edit {
                remove(KEY_SCALE_ADDRESS)
                remove(KEY_SCALE_NAME)
            }
            refreshPortable()
        }
    }

    fun setReliabilityMode(enabled: Boolean) {
        portableOperations.runExclusiveBlocking {
            preferences.edit { putBoolean(KEY_RELIABILITY, enabled) }
            refreshPortable()
        }
    }

    fun saveSelectedChartMetricKeys(keys: Set<String>) {
        portableOperations.runExclusiveBlocking {
            preferences.edit { putStringSet(KEY_SELECTED_CHART_METRICS, keys.toSet()) }
            refreshPortable()
        }
    }

    fun saveHomeKgChartSeriesKeys(keys: Set<String>) {
        portableOperations.runExclusiveBlocking {
            preferences.edit { putStringSet(KEY_HOME_KG_CHART_SERIES, keys.toSet()) }
            refreshPortable()
        }
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

    override fun isExternalSyncEnabled(destination: ExternalSyncDestination): Boolean =
        when (destination) {
            ExternalSyncDestination.HEALTH_CONNECT -> settings.value.healthConnectSyncEnabled
            ExternalSyncDestination.HUAWEI -> settings.value.huaweiSyncEnabled
        }

    override fun setExternalSyncEnabled(
        destination: ExternalSyncDestination,
        enabled: Boolean,
    ) {
        portableOperations.runExclusiveBlocking {
            val key = when (destination) {
                ExternalSyncDestination.HEALTH_CONNECT -> KEY_HEALTH_CONNECT_SYNC_ENABLED
                ExternalSyncDestination.HUAWEI -> KEY_HUAWEI_SYNC_ENABLED
            }
            check(preferences.edit().putBoolean(key, enabled).commit()) {
                "Could not durably commit ${destination.name} sync setting"
            }
            refresh()
        }
    }

    private fun refresh() {
        mutableSettings.value = read()
    }

    private fun refreshPortable() {
        refresh()
        versionedPortable = VersionedPortableProfileSettings(
            mutableSettings.value.toPortableSnapshot(),
            versionedPortable.revision + 1,
        )
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
            healthConnectSyncEnabled = preferences.getBoolean(
                KEY_HEALTH_CONNECT_SYNC_ENABLED,
                true,
            ),
            huaweiSyncEnabled = preferences.getBoolean(KEY_HUAWEI_SYNC_ENABLED, true),
        )
    }

    private companion object {
        const val KEY_SCALE_ADDRESS = "scale_address"
        const val KEY_SCALE_NAME = "scale_name"
        const val KEY_RELIABILITY = "reliability"
        const val KEY_SELECTED_CHART_METRICS = "selected_chart_metrics"
        const val KEY_HOME_KG_CHART_SERIES = "home_kg_chart_series"
        const val KEY_EXTERNAL_SYNC_PAUSED_UNTIL = "external_sync_paused_until_epoch_millis"
        const val KEY_HEALTH_CONNECT_SYNC_ENABLED = "health_connect_sync_enabled"
        const val KEY_HUAWEI_SYNC_ENABLED = "huawei_sync_enabled"
    }
}
