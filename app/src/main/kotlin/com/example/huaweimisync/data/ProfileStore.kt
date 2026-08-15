package com.example.huaweimisync.data

import android.content.Context
import androidx.core.content.edit
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.core.UserProfile
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val profile: UserProfile? = null,
    val scaleAddress: String? = null,
    val scaleName: String? = null,
    val reliabilityMode: Boolean = false,
    val selectedChartMetricKeys: Set<String>? = null,
)

class ProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("mi_sync_settings", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    fun saveProfile(profile: UserProfile) {
        preferences.edit {
            putString(KEY_HEIGHT, profile.heightCm.toString())
            putString(KEY_BIRTH_DATE, profile.birthDate.toString())
            putString(KEY_SEX, profile.sex.name)
        }
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

    private fun refresh() {
        mutableSettings.value = read()
    }

    private fun read(): AppSettings {
        val height = preferences.getString(KEY_HEIGHT, null)?.toDoubleOrNull()
        val birthDate = preferences.getString(KEY_BIRTH_DATE, null)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        val sex = preferences.getString(KEY_SEX, null)?.let {
            runCatching { Sex.valueOf(it) }.getOrNull()
        }
        val profile = if (height != null && birthDate != null && sex != null) {
            runCatching { UserProfile(height, birthDate, sex) }.getOrNull()
        } else {
            null
        }

        return AppSettings(
            profile = profile,
            scaleAddress = preferences.getString(KEY_SCALE_ADDRESS, null),
            scaleName = preferences.getString(KEY_SCALE_NAME, null),
            reliabilityMode = preferences.getBoolean(KEY_RELIABILITY, false),
            selectedChartMetricKeys = preferences
                .getStringSet(KEY_SELECTED_CHART_METRICS, null)
                ?.toSet(),
        )
    }

    private companion object {
        const val KEY_HEIGHT = "height"
        const val KEY_BIRTH_DATE = "birth_date"
        const val KEY_SEX = "sex"
        const val KEY_SCALE_ADDRESS = "scale_address"
        const val KEY_SCALE_NAME = "scale_name"
        const val KEY_RELIABILITY = "reliability"
        const val KEY_SELECTED_CHART_METRICS = "selected_chart_metrics"
    }
}
