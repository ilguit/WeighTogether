package com.palixander.scalesync.charts

import android.content.SharedPreferences
import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.measurements.restoreHomeKgChartSeriesKeys
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface AnalyticalChartSettingsStore {
    fun read(account: AccountId): List<AnalyticalChartSettings>
    fun write(account: AccountId, settings: List<AnalyticalChartSettings>)
}

/** Separate preferences: these presentation choices never enter measurement backups. */
class PreferenceAnalyticalChartSettingsStore(private val preferences: SharedPreferences) : AnalyticalChartSettingsStore {
    override fun read(account: AccountId): List<AnalyticalChartSettings> = AnalyticalChartType.entries.mapNotNull { type ->
        val prefix = "${account.value}/${type.name}/"
        if (!preferences.getBoolean(prefix + "enabled", false)) return@mapNotNull null
        val start = preferences.getInt(prefix + "start", 300)
        val end = preferences.getInt(prefix + "end", 720)
        AnalyticalChartSettings(
            type = type,
            activeSeriesKeys = restoreHomeKgChartSeriesKeys(preferences.getStringSet(prefix + "series", null)),
            morningWindow = runCatching { MorningWindow(start, end) }.getOrDefault(MorningWindow()),
            morningMode = if (preferences.getBoolean(prefix + "automatic", false)) MorningFilterMode.AUTOMATIC else MorningFilterMode.MANUAL,
        )
    }

    override fun write(account: AccountId, settings: List<AnalyticalChartSettings>) {
        preferences.edit().apply {
            AnalyticalChartType.entries.forEach { type ->
                val prefix = "${account.value}/${type.name}/"
                val setting = settings.firstOrNull { it.type == type }
                putBoolean(prefix + "enabled", setting != null)
                if (setting != null) {
                    putStringSet(prefix + "series", setting.activeSeriesKeys.toSet())
                    putInt(prefix + "start", setting.morningWindow.startMinute)
                    putInt(prefix + "end", setting.morningWindow.endMinute)
                    putBoolean(prefix + "automatic", setting.morningMode == MorningFilterMode.AUTOMATIC)
                }
            }
        }.apply()
    }
}

enum class MorningCalculationStatus { IDLE, RUNNING, SUCCESS, INSUFFICIENT_DATA, ERROR }

data class AnalyticalChartDraft(
    val settings: AnalyticalChartSettings,
    val startMinute: Int = settings.morningWindow.startMinute,
    val endMinute: Int = settings.morningWindow.endMinute,
    val status: MorningCalculationStatus = MorningCalculationStatus.IDLE,
    val preview: MorningPreview? = null,
) {
    val valid: Boolean get() = startMinute in 0..1439 && endMinute in 1..1440 && startMinute < endMinute
}

data class AnalyticalChartState(
    val accountId: AccountId? = null,
    val settings: List<AnalyticalChartSettings> = emptyList(),
    val draft: AnalyticalChartDraft? = null,
    val showAddMenu: Boolean = false,
    val removed: AnalyticalChartSettings? = null,
)

/** UI-thread intents; expensive selection runs on the supplied worker dispatcher. */
class AnalyticalChartController(
    private val store: AnalyticalChartSettingsStore,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val select: (List<MeasurementEntity>, ZoneId) -> MorningSelectionResult = ::selectMorningWindow,
) {
    private val mutableState = MutableStateFlow(AnalyticalChartState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private fun invalidate() { generation++; job?.cancel(); job = null }

    fun selectAccount(account: AccountId?) {
        if (state.value.accountId == account) return
        invalidate()
        mutableState.value = AnalyticalChartState(account, account?.let(store::read).orEmpty())
    }
    fun showAddMenu(show: Boolean) { mutableState.value = state.value.copy(showAddMenu = show) }
    fun edit(type: AnalyticalChartType) {
        if (state.value.accountId == null) return
        invalidate()
        mutableState.value = state.value.copy(
            draft = AnalyticalChartDraft(state.value.settings.firstOrNull { it.type == type } ?: AnalyticalChartSettings(type)),
            showAddMenu = false,
        )
    }
    fun cancel() { invalidate(); mutableState.value = state.value.copy(draft = null) }
    fun setWindow(start: Int, end: Int) {
        val draft = state.value.draft ?: return
        invalidate()
        mutableState.value = state.value.copy(draft = draft.copy(
            startMinute = start, endMinute = end,
            settings = draft.settings.copy(morningMode = MorningFilterMode.MANUAL),
            status = MorningCalculationStatus.IDLE, preview = null,
        ))
    }
    fun setSeries(keys: Set<String>) {
        val draft = state.value.draft ?: return
        mutableState.value = state.value.copy(draft = draft.copy(settings = draft.settings.copy(activeSeriesKeys = restoreHomeKgChartSeriesKeys(keys))))
    }
    fun save() {
        val draft = state.value.draft ?: return
        if (!draft.valid || draft.status == MorningCalculationStatus.RUNNING) return
        val setting = draft.settings.copy(morningWindow = MorningWindow(draft.startMinute, draft.endMinute))
        persist(state.value.settings.filterNot { it.type == setting.type } + setting)
        cancel()
    }
    private fun persist(settings: List<AnalyticalChartSettings>) {
        val account = state.value.accountId ?: return
        val ordered = settings.sortedBy { it.type.ordinal }
        store.write(account, ordered)
        mutableState.value = state.value.copy(settings = ordered)
    }
    fun toggleSavedSeries(type: AnalyticalChartType, key: String) {
        val setting = state.value.settings.firstOrNull { it.type == type } ?: return
        val keys = com.palixander.scalesync.measurements.toggleHomeKgChartSeriesKey(setting.activeSeriesKeys, key) ?: return
        persist(state.value.settings.map { if (it.type == type) it.copy(activeSeriesKeys = keys) else it })
    }
    fun remove(type: AnalyticalChartType) {
        val removed = state.value.settings.firstOrNull { it.type == type } ?: return
        cancel()
        persist(state.value.settings.filterNot { it.type == type })
        mutableState.value = state.value.copy(removed = removed)
    }
    fun undoRemove() {
        val removed = state.value.removed ?: return
        persist(state.value.settings.filterNot { it.type == removed.type } + removed)
        dismissUndo()
    }
    fun dismissUndo() { mutableState.value = state.value.copy(removed = null) }
    fun invalidateCalculation() {
        invalidate()
        val draft = state.value.draft ?: return
        mutableState.value = state.value.copy(draft = draft.copy(status = MorningCalculationStatus.IDLE, preview = null))
    }
    fun autoSelect(account: AccountId, rows: List<MeasurementEntity>, zone: ZoneId) {
        val draft = state.value.draft ?: return
        if (state.value.accountId != account || draft.settings.type != AnalyticalChartType.MORNING || draft.status == MorningCalculationStatus.RUNNING) return
        invalidate()
        val token = generation
        mutableState.value = state.value.copy(draft = draft.copy(status = MorningCalculationStatus.RUNNING, preview = null))
        job = scope.launch {
            try {
                val result = withContext(dispatcher) { select(rows, zone) }
                if (token != generation || state.value.accountId != account) return@launch
                val current = state.value.draft ?: return@launch
                mutableState.value = state.value.copy(draft = when (result) {
                    MorningSelectionResult.InsufficientData -> current.copy(status = MorningCalculationStatus.INSUFFICIENT_DATA)
                    is MorningSelectionResult.Success -> current.copy(
                        settings = current.settings.copy(morningMode = MorningFilterMode.AUTOMATIC),
                        startMinute = result.preview.window.startMinute,
                        endMinute = result.preview.window.endMinute,
                        status = MorningCalculationStatus.SUCCESS, preview = result.preview,
                    )
                })
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (token == generation) mutableState.value = state.value.copy(draft = state.value.draft?.copy(status = MorningCalculationStatus.ERROR))
            }
        }
    }
}
