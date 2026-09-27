package com.palixander.scalesync

import com.palixander.scalesync.measurements.formatWeight

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.palixander.scalesync.ui.components.HuaweiIconButton
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.domain.PET_NAME_LENGTH
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.domain.normalizePetName
import com.palixander.scalesync.measurements.formatMeasurementDateTime
import java.util.Locale
import com.palixander.scalesync.ui.text.resolve
import com.palixander.scalesync.ui.currentAppLocale

internal object PetMeasurementTestTags {
    const val Dialog = "pet-measurement-dialog"
    const val Title = "pet-measurement-title"
    const val CreateAction = "pet-create-action"
    const val NameField = "pet-name-field"
    const val CreateConfirm = "pet-create-confirm"
    const val SpeciesCat = "pet-species-cat"
    const val SpeciesDog = "pet-species-dog"
    const val Cancel = "pet-measurement-cancel"
    const val FirstWeight = "pet-first-weight"
    const val Result = "pet-measurement-result"
    const val Done = "pet-measurement-done"
    const val BackToSelection = "pet-back-to-selection"
    const val Retry = "pet-measurement-retry"
    fun pet(id: PetId) = "pet-select-${id.value}"
}

internal data class PetMeasurementCallbacks(
    val onOpen: () -> Unit,
    val onShowCreate: () -> Unit,
    val onCreateAndStart: (String, PetSpecies) -> Unit,
    val onStart: (PetId) -> Unit,
    val onCancel: () -> Unit,
    val onDone: () -> Unit,
    val onRetry: () -> Unit,
) {
    companion object {
        val None = PetMeasurementCallbacks({}, {}, { _, _ -> }, {}, {}, {}, {})
    }
}

@Composable
internal fun PetMeasurementDialog(
    state: PetMeasurementUiState,
    pets: List<PetWithLatestWeight>,
    callbacks: PetMeasurementCallbacks,
) {
    val locale = currentAppLocale()
    if (state == PetMeasurementUiState.Idle || state == PetMeasurementUiState.Cancelled) return

    if (state is PetMeasurementUiState.AwaitingFirstWeight ||
        state is PetMeasurementUiState.AwaitingSecondWeight ||
        state is PetMeasurementUiState.Result ||
        state is PetMeasurementUiState.Saving ||
        state is PetMeasurementUiState.ConnectionError
    ) {
        PetMeasurementFullScreen(state, callbacks)
        return
    }

    AlertDialog(
        onDismissRequest = {
            if (state !is PetMeasurementUiState.Saving) callbacks.onCancel()
        },
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.testTag(PetMeasurementTestTags.Dialog),
        title = { Text(dialogTitle(state)) },
        text = {
            when (state) {
                PetMeasurementUiState.SelectingPet -> PetSelection(pets, callbacks)
                PetMeasurementUiState.CreatingPet -> PetCreation(pets, callbacks)
                is PetMeasurementUiState.AwaitingFirstWeight -> Column {
                    Text(stringResource(R.string.pet_measurement_first_instructions_named, state.pet.displayName))
                }
                is PetMeasurementUiState.AwaitingSecondWeight -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.pet_measurement_first_accepted, formatPetWeight(state.firstWeightKg, locale)),
                        modifier = Modifier.testTag(PetMeasurementTestTags.FirstWeight),
                    )
                    Text(
                        stringResource(R.string.pet_measurement_second_instructions_named, state.pet.displayName),
                    )
                }
                is PetMeasurementUiState.Saving -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.pet_measurement_saving))
                }
                is PetMeasurementUiState.Result -> Text(
                    stringResource(R.string.pet_measurement_result_weight, formatPetWeight(state.petWeightKg, locale)),
                    modifier = Modifier.testTag(PetMeasurementTestTags.Result),
                )
                is PetMeasurementUiState.Error -> Text(
                    state.message.resolve(LocalContext.current.resources),
                    color = MaterialTheme.colorScheme.error,
                )
                is PetMeasurementUiState.ConnectionError -> Unit
                PetMeasurementUiState.Idle,
                PetMeasurementUiState.Cancelled,
                -> Unit
            }
        },
        confirmButton = {
            when (state) {
                PetMeasurementUiState.SelectingPet -> TextButton(
                    onClick = callbacks.onShowCreate,
                    modifier = Modifier.testTag(PetMeasurementTestTags.CreateAction),
                ) { Text(stringResource(R.string.pet_measurement_new_pet)) }
                is PetMeasurementUiState.Error -> TextButton(
                    onClick = callbacks.onOpen,
                    modifier = Modifier.testTag(PetMeasurementTestTags.BackToSelection),
                ) {
                    Text(stringResource(R.string.pet_measurement_back_to_selection))
                }
                is PetMeasurementUiState.Result -> TextButton(
                    onClick = callbacks.onDone,
                    modifier = Modifier.testTag(PetMeasurementTestTags.Done),
                ) {
                    Text(stringResource(R.string.action_done))
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (state !is PetMeasurementUiState.Saving) {
                TextButton(
                    onClick = callbacks.onCancel,
                    modifier = Modifier.testTag(PetMeasurementTestTags.Cancel),
                ) { Text(stringResource(if (state is PetMeasurementUiState.Error) R.string.action_close else R.string.action_cancel)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PetMeasurementFullScreen(
    state: PetMeasurementUiState,
    callbacks: PetMeasurementCallbacks,
) {
    val locale = currentAppLocale()
    val saving = state is PetMeasurementUiState.Saving
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        titleFocus.requestFocus()
    }
    Dialog(
        onDismissRequest = { if (!saving) callbacks.onCancel() },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BackHandler(enabled = !saving, onBack = callbacks.onCancel)
        Surface(Modifier.fillMaxSize().testTag(PetMeasurementTestTags.Dialog)) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.pet_measurement_title),
                                modifier = Modifier
                                    .focusRequester(titleFocus)
                                    .focusable()
                                    .testTag(PetMeasurementTestTags.Title)
                                    .semantics { heading() },
                            )
                        },
                        navigationIcon = {
                            HuaweiIconButton(
                                icon = HuaweiIcons.Back,
                                contentDescription = stringResource(R.string.pet_measurement_close_a11y),
                                onClick = callbacks.onCancel,
                                enabled = !saving,
                            )
                        },
                    )
                },
                bottomBar = {
                    Surface(shadowElevation = 8.dp) {
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (state is PetMeasurementUiState.Result) {
                                OutlinedButton(
                                    onClick = callbacks.onCancel,
                                    modifier = Modifier.weight(1f).testTag(PetMeasurementTestTags.Cancel),
                                ) { Text(stringResource(R.string.action_cancel)) }
                                Button(
                                    onClick = callbacks.onDone,
                                    modifier = Modifier.weight(1f).testTag(PetMeasurementTestTags.Done),
                                ) { Text(stringResource(R.string.action_done)) }
                            } else if (!saving) {
                                if (state is PetMeasurementUiState.ConnectionError) {
                                    Button(
                                        onClick = callbacks.onRetry,
                                        modifier = Modifier.weight(1f).testTag(PetMeasurementTestTags.Retry),
                                    ) { Text(stringResource(R.string.action_retry)) }
                                }
                                OutlinedButton(
                                    onClick = callbacks.onCancel,
                                    modifier = Modifier.weight(1f).testTag(PetMeasurementTestTags.Cancel),
                                ) { Text(stringResource(R.string.action_cancel)) }
                            }
                        }
                    }
                },
            ) { padding ->
                val pet = when (state) {
                    is PetMeasurementUiState.AwaitingFirstWeight -> state.pet
                    is PetMeasurementUiState.AwaitingSecondWeight -> state.pet
                    is PetMeasurementUiState.Result -> state.pet
                    is PetMeasurementUiState.Saving -> state.pet
                    is PetMeasurementUiState.ConnectionError -> state.pet
                    else -> return@Scaffold
                }
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(pet.displayName, style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(16.dp))
                    when (state) {
                        is PetMeasurementUiState.AwaitingFirstWeight -> {
                            Text(stringResource(R.string.pet_measurement_first_title), style = MaterialTheme.typography.titleLarge)
                            Text(
                                stringResource(R.string.pet_measurement_first_instructions),
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                            CircularProgressIndicator()
                            state.currentWeightKg?.let {
                                Text(stringResource(R.string.weight_kg_format, formatPetWeight(it, locale)), style = MaterialTheme.typography.headlineMedium)
                            }
                            Text(stringResource(R.string.pet_measurement_waiting_stable))
                        }
                        is PetMeasurementUiState.AwaitingSecondWeight -> {
                            Text(stringResource(R.string.pet_measurement_second_title), style = MaterialTheme.typography.titleLarge)
                            Text(
                                stringResource(R.string.pet_measurement_first_reading, formatPetWeight(state.firstWeightKg, locale)),
                                modifier = Modifier.padding(vertical = 16.dp)
                                    .testTag(PetMeasurementTestTags.FirstWeight),
                            )
                            Text(
                                stringResource(R.string.pet_measurement_second_instructions),
                            )
                            CircularProgressIndicator(Modifier.padding(16.dp))
                            state.currentWeightKg?.let {
                                Text(stringResource(R.string.weight_kg_format, formatPetWeight(it, locale)), style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                        is PetMeasurementUiState.Result -> {
                            Text("✓", style = MaterialTheme.typography.headlineLarge)
                            Text(stringResource(R.string.action_done), style = MaterialTheme.typography.titleLarge)
                            Text(
                                stringResource(R.string.pet_measurement_result_weight, formatPetWeight(state.petWeightKg, locale)),
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.padding(top = 16.dp).testTag(PetMeasurementTestTags.Result),
                            )
                            state.previousPetWeightKg?.let { previous ->
                                val delta = state.petWeightKg - previous
                                val prefix = if (delta > 0) "+" else ""
                                Text(stringResource(R.string.pet_measurement_delta, prefix, formatPetWeight(delta, locale)))
                            }
                        }
                        is PetMeasurementUiState.Saving -> {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.pet_measurement_saving), modifier = Modifier.padding(top = 16.dp))
                        }
                        is PetMeasurementUiState.ConnectionError -> {
                            Text(stringResource(R.string.pet_measurement_connection_lost), style = MaterialTheme.typography.titleLarge)
                            state.firstWeightKg?.let {
                                Text(stringResource(R.string.pet_measurement_first_reading_saved, formatPetWeight(it, locale)))
                            }
                            Text(state.message.resolve(LocalContext.current.resources), color = MaterialTheme.colorScheme.error)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun PetSelection(
    pets: List<PetWithLatestWeight>,
    callbacks: PetMeasurementCallbacks,
) {
    val locale = currentAppLocale()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pets.isEmpty()) Text(stringResource(R.string.pet_measurement_no_pets))
        pets.forEach { item ->
            val detail = item.latestMeasurement?.let {
                stringResource(
                    R.string.pet_measurement_latest_weight,
                    formatPetWeight(it.petWeightKg, locale),
                    formatMeasurementDateTime(it.measuredAt, locale = locale),
                )
            } ?: stringResource(R.string.pet_measurement_no_measurements)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { callbacks.onStart(item.pet.id) }
                    .semantics {
                        contentDescription = "${item.pet.displayName}. $detail"
                    }
                    .padding(vertical = 10.dp)
                    .testTag(PetMeasurementTestTags.pet(item.pet.id)),
            ) {
                Text(item.pet.displayName, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PetCreation(
    pets: List<PetWithLatestWeight>,
    callbacks: PetMeasurementCallbacks,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var species by rememberSaveable { mutableStateOf<PetSpecies?>(null) }
    val trimmed = name.trim()
    val error = when {
        !submitted -> null
        trimmed.isEmpty() -> stringResource(R.string.pet_editor_name_required)
        trimmed.length !in PET_NAME_LENGTH -> stringResource(R.string.pet_editor_name_too_long)
        pets.any { normalizePetName(it.pet.displayName) == normalizePetName(trimmed) } ->
            stringResource(R.string.pet_editor_name_duplicate)
        species == null -> stringResource(R.string.pet_editor_species_required)
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; submitted = false },
            label = { Text(stringResource(R.string.pet_editor_name)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth().testTag(PetMeasurementTestTags.NameField),
        )
        PetSpeciesSelector(species) { species = it; submitted = false }
        error?.takeIf { species == null && submitted }?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                submitted = true
                if (trimmed.isNotEmpty() && trimmed.length in PET_NAME_LENGTH &&
                    pets.none { normalizePetName(it.pet.displayName) == normalizePetName(trimmed) } &&
                    species != null
                ) callbacks.onCreateAndStart(trimmed, requireNotNull(species))
            },
            modifier = Modifier.fillMaxWidth().testTag(PetMeasurementTestTags.CreateConfirm),
        ) { Text(stringResource(R.string.pet_measurement_create_and_weigh)) }
    }
}

@Composable
internal fun PetSpeciesSelector(selected: PetSpecies?, onSelected: (PetSpecies) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            PetSpecies.CAT to stringResource(R.string.pet_measurement_cat),
            PetSpecies.DOG to stringResource(R.string.pet_measurement_dog),
        ).forEach { (value, label) ->
            OutlinedButton(
                onClick = { onSelected(value) },
                modifier = Modifier.testTag(
                    if (value == PetSpecies.CAT) PetMeasurementTestTags.SpeciesCat
                    else PetMeasurementTestTags.SpeciesDog,
                ),
            ) { Text(if (selected == value) "✓ $label" else label) }
        }
    }
}

@Composable
private fun dialogTitle(state: PetMeasurementUiState): String = when (state) {
    PetMeasurementUiState.SelectingPet -> stringResource(R.string.pet_measurement_choose_pet)
    PetMeasurementUiState.CreatingPet -> stringResource(R.string.pet_measurement_new_pet)
    is PetMeasurementUiState.AwaitingFirstWeight -> stringResource(R.string.pet_measurement_first_title)
    is PetMeasurementUiState.AwaitingSecondWeight -> stringResource(R.string.pet_measurement_second_title)
    is PetMeasurementUiState.Saving -> stringResource(R.string.pet_measurement_saving_title)
    is PetMeasurementUiState.Result -> stringResource(R.string.action_done)
    is PetMeasurementUiState.ConnectionError -> stringResource(R.string.pet_measurement_connection_lost)
    is PetMeasurementUiState.Error -> stringResource(R.string.pet_measurement_failed_title)
    PetMeasurementUiState.Idle, PetMeasurementUiState.Cancelled -> ""
}

private fun formatPetWeight(value: Double, locale: Locale = Locale.getDefault()): String =
    formatWeight(value, locale)
