package com.palixander.scalesync.ui.reminder

import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.R
import com.palixander.scalesync.ScaleSyncApplication
import com.palixander.scalesync.data.*
import com.palixander.scalesync.domain.*
import com.palixander.scalesync.reminder.WeighingReminderCapability
import com.palixander.scalesync.reminder.WeighingReminderCapabilityGateway
import com.palixander.scalesync.reminder.WeighingReminderCapabilityIssue
import com.palixander.scalesync.reminder.WeighingReminderCoordinator
import com.palixander.scalesync.reminder.nextEnabledReminderOccurrence
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

object ReminderSettingsTestTags {
    const val Entry = "reminder-settings-entry"
    const val Screen = "reminder-settings-screen"
    const val Loading = "reminder-settings-loading"
    const val Empty = "reminder-settings-empty"
    const val Error = "reminder-settings-error"
    const val Unavailable = "reminder-settings-unavailable"
    const val Add = "reminder-settings-add"
    const val Editor = "reminder-settings-editor"
    const val Time = "reminder-settings-time"
    const val Enabled = "reminder-settings-enabled"
    const val Save = "reminder-settings-save"
    const val Delete = "reminder-settings-delete"
    const val Duplicate = "reminder-settings-duplicate"
    const val DeleteConfirmation = "reminder-settings-delete-confirmation"
    fun row(id: WeighingReminderId) = "reminder-settings-row-${id.value}"
    fun weekday(day: DayOfWeek) = "reminder-settings-weekday-${day.name.lowercase()}"
}

sealed interface ReminderListState {
    data object Loading : ReminderListState
    data class Ready(val schedules: List<WeighingReminderSchedule>) : ReminderListState
    data class Error(val cause: Throwable) : ReminderListState
}

class ReminderSettingsStateOwner(
    private val owner: WeighingReminderOwner,
    private val repository: RoomWeighingReminderRepository,
    private val coordinator: WeighingReminderCoordinator,
    private val capabilities: WeighingReminderCapabilityGateway,
) {
    val schedules = repository.observe(owner)
    fun capability(): WeighingReminderCapability = capabilities.read()
    fun settingsIntents(issue: WeighingReminderCapabilityIssue) = capabilities.settingsIntents(issue)
    suspend fun save(id: WeighingReminderId?, draft: WeighingReminderDraft): SaveWeighingReminderResult {
        val result = if (id == null) repository.create(draft) else repository.update(id, draft)
        if (result is SaveWeighingReminderResult.Saved) coordinator.reconcile()
        return result
    }
    suspend fun enabled(id: WeighingReminderId, enabled: Boolean): SaveWeighingReminderResult {
        val result = repository.setEnabled(id, enabled)
        coordinator.reconcile()
        return result
    }
    suspend fun delete(id: WeighingReminderId) {
        repository.delete(id)
        coordinator.reconcile()
    }
}

@Composable
fun rememberReminderSettingsStateOwner(owner: WeighingReminderOwner): ReminderSettingsStateOwner {
    val app = LocalContext.current.applicationContext as ScaleSyncApplication
    return remember(owner) {
        ReminderSettingsStateOwner(
            owner, app.container.weighingReminderRepository,
            app.container.weighingReminders, app.container.weighingReminderCapabilities,
        )
    }
}

@Composable
fun ReminderSettingsEntry(
    owner: WeighingReminderOwner,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateOwner = rememberReminderSettingsStateOwner(owner)
    var state: ReminderListState by remember(owner) { mutableStateOf(ReminderListState.Loading) }
    LaunchedEffect(stateOwner) {
        stateOwner.schedules.catch { state = ReminderListState.Error(it) }.collect {
            state = ReminderListState.Ready(it)
        }
    }
    val summary = when (val current = state) {
        ReminderListState.Loading -> stringResource(R.string.reminder_loading)
        is ReminderListState.Error -> stringResource(R.string.reminder_requires_attention)
        is ReminderListState.Ready -> reminderSummary(current.schedules)
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.reminder_title)) },
        supportingContent = { Text(summary) },
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).testTag(ReminderSettingsTestTags.Entry)
            .semantics { contentDescription = "${summary}" },
    )
}

@Composable
private fun reminderSummary(schedules: List<WeighingReminderSchedule>): String {
    if (schedules.isEmpty()) return stringResource(R.string.reminder_none)
    val enabled = schedules.filter { it.enabled }
    if (enabled.isEmpty()) return stringResource(R.string.reminder_all_disabled)
    val clock = Clock.systemDefaultZone()
    val occurrence = checkNotNull(nextEnabledReminderOccurrence(enabled, clock))
    val dateTime = occurrence.instant.atZone(clock.zone)
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
    return if (enabled.size == 1) dateTime else stringResource(R.string.reminder_summary_more, dateTime, enabled.size - 1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettingsScreen(owner: WeighingReminderOwner, onBack: () -> Unit) {
    val stateOwner = rememberReminderSettingsStateOwner(owner)
    var state: ReminderListState by remember(owner) { mutableStateOf(ReminderListState.Loading) }
    var editing by remember { mutableStateOf<WeighingReminderSchedule?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(stateOwner) {
        stateOwner.schedules.catch { state = ReminderListState.Error(it) }.collect {
            state = ReminderListState.Ready(it)
        }
    }
    if (creating || editing != null) {
        ReminderEditor(owner, editing, stateOwner, { creating = false; editing = null })
        return
    }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag(ReminderSettingsTestTags.Screen),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.reminder_title), Modifier.semantics { heading() }) }, navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } }) },
        floatingActionButton = { FloatingActionButton(onClick = { creating = true }, Modifier.testTag(ReminderSettingsTestTags.Add)) { Text("+") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val current = state) {
                ReminderListState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).testTag(ReminderSettingsTestTags.Loading))
                is ReminderListState.Error -> Text(stringResource(R.string.reminder_load_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(ReminderSettingsTestTags.Error).semantics { liveRegion = LiveRegionMode.Polite })
                is ReminderListState.Ready -> {
                    val issues = stateOwner.capability().issuesFor(
                        current.schedules.filter { it.enabled }.mapTo(mutableSetOf()) { it.importance },
                    )
                    if (issues.isNotEmpty()) CapabilityWarning(stateOwner, issues.first())
                    if (current.schedules.isEmpty()) {
                        Text(stringResource(R.string.reminder_empty), Modifier.testTag(ReminderSettingsTestTags.Empty))
                    } else current.schedules.forEach { schedule ->
                        ReminderRow(schedule, { editing = schedule }) { enabled ->
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { stateOwner.enabled(schedule.id, enabled) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityWarning(stateOwner: ReminderSettingsStateOwner, issue: WeighingReminderCapabilityIssue) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(ReminderSettingsTestTags.Unavailable)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.reminder_unavailable))
            TextButton(onClick = { openFirstSettings(context, stateOwner, issue) }) {
                Text(stringResource(R.string.reminder_open_settings))
            }
        }
    }
}

private fun openFirstSettings(context: Context, owner: ReminderSettingsStateOwner, issue: WeighingReminderCapabilityIssue) {
    owner.settingsIntents(issue).firstOrNull { runCatching { context.startActivity(it); true }.getOrDefault(false) }
}

@Composable
private fun ReminderRow(schedule: WeighingReminderSchedule, onClick: () -> Unit, onEnabled: (Boolean) -> Unit) {
    val type = stringResource(if (schedule.importance == WeighingReminderImportance.ALARM) R.string.reminder_alarm else R.string.reminder_regular)
    ListItem(
        headlineContent = { Text(schedule.time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) },
        supportingContent = { Text("$type · ${schedule.weekdays.joinToString { it.localizedName() }}") },
        trailingContent = { Switch(schedule.enabled, onEnabled, Modifier.testTag(ReminderSettingsTestTags.Enabled).semantics { stateDescription = if (schedule.enabled) "enabled" else "disabled" }) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(ReminderSettingsTestTags.row(schedule.id)),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderEditor(owner: WeighingReminderOwner, existing: WeighingReminderSchedule?, stateOwner: ReminderSettingsStateOwner, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var time by remember(existing) { mutableStateOf(existing?.time ?: LocalTime.of(9, 0)) }
    var weekdays by remember(existing) { mutableStateOf(existing?.weekdays ?: setOf(LocalDate.now().dayOfWeek)) }
    var importance by remember(existing) { mutableStateOf(existing?.importance ?: WeighingReminderImportance.REGULAR) }
    var enabled by remember(existing) { mutableStateOf(existing?.enabled ?: true) }
    var error by remember { mutableStateOf<Int?>(null) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(modifier = Modifier.fillMaxSize().testTag(ReminderSettingsTestTags.Editor), topBar = { TopAppBar(title = { Text(stringResource(if (existing == null) R.string.reminder_add else R.string.reminder_edit), Modifier.semantics { heading() }) }, navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> time = LocalTime.of(h, m) }, time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(context)).show() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ReminderSettingsTestTags.Time)) { Text(time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) }
            Text(stringResource(R.string.reminder_weekdays), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { day -> FilterChip(selected = day in weekdays, onClick = { weekdays = if (day in weekdays && weekdays.size > 1) weekdays - day else weekdays + day }, label = { Text(day.localizedName()) }, modifier = Modifier.heightIn(min = 48.dp).testTag(ReminderSettingsTestTags.weekday(day)).semantics { role = Role.Checkbox }) }
            }
            Text(stringResource(R.string.reminder_type), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(importance == WeighingReminderImportance.REGULAR, { importance = WeighingReminderImportance.REGULAR }, { Text(stringResource(R.string.reminder_regular)) })
                FilterChip(importance == WeighingReminderImportance.ALARM, { importance = WeighingReminderImportance.ALARM }, { Text(stringResource(R.string.reminder_alarm)) })
            }
            stateOwner.capability().issuesFor(setOf(importance)).firstOrNull()?.let { issue ->
                CapabilityWarning(stateOwner, issue)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.reminder_enabled), Modifier.weight(1f)); Switch(enabled, { enabled = it }) }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(if (it == R.string.reminder_duplicate) ReminderSettingsTestTags.Duplicate else ReminderSettingsTestTags.Error).semantics { liveRegion = LiveRegionMode.Assertive }) }
            Button(onClick = { scope.launch { try { when (stateOwner.save(existing?.id, WeighingReminderDraft(owner, time, weekdays, importance, enabled))) { is SaveWeighingReminderResult.Saved -> onBack(); SaveWeighingReminderResult.Duplicate -> error = R.string.reminder_duplicate; else -> error = R.string.reminder_save_error } } catch (t: Throwable) { if (t is CancellationException) throw t; error = R.string.reminder_save_error } } }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ReminderSettingsTestTags.Save)) { Text(stringResource(R.string.action_save)) }
            if (existing != null) OutlinedButton(onClick = { deleteConfirmation = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ReminderSettingsTestTags.Delete)) { Text(stringResource(R.string.action_delete)) }
        }
    }
    if (deleteConfirmation && existing != null) AlertDialog(onDismissRequest = { deleteConfirmation = false }, modifier = Modifier.testTag(ReminderSettingsTestTags.DeleteConfirmation), title = { Text(stringResource(R.string.reminder_delete_title)) }, text = { Text(stringResource(R.string.reminder_delete_text)) }, confirmButton = { Button(onClick = { scope.launch { stateOwner.delete(existing.id); onBack() } }) { Text(stringResource(R.string.action_delete)) } }, dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text(stringResource(R.string.action_cancel)) } })
}

private fun DayOfWeek.localizedName(): String = getDisplayName(TextStyle.SHORT, Locale.getDefault())
