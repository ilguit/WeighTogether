package com.palixander.scalesync

import com.palixander.scalesync.measurements.formatWeight
import com.palixander.scalesync.ui.components.ManualOriginIndicator

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import com.palixander.scalesync.ui.components.HuaweiIconButton
import com.palixander.scalesync.ui.icons.HuaweiIcons
import com.palixander.scalesync.domain.PET_NAME_LENGTH
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetWithLatestWeight
import com.palixander.scalesync.domain.normalizePetName
import com.palixander.scalesync.measurements.formatMeasurementDateTime
import java.util.Locale

internal object PetMeasurementTestTags {
    const val Dialog = "pet-measurement-dialog"
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
                    Text(
                        "Выполните одно взвешивание с ${state.pet.displayName} на руках, " +
                            "а другое — без питомца. Порядок не важен. Начните с любого " +
                            "варианта и дождитесь стабильного значения.",
                    )
                }
                is PetMeasurementUiState.AwaitingSecondWeight -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Первое значение принято: ${formatPetWeight(state.firstWeightKg)} кг",
                        modifier = Modifier.testTag(PetMeasurementTestTags.FirstWeight),
                    )
                    Text(
                        "Теперь выполните оставшееся взвешивание: с ${state.pet.displayName} " +
                            "на руках или без питомца. Дождитесь стабильного значения.",
                    )
                }
                is PetMeasurementUiState.Saving -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Сохраняем результат…")
                }
                is PetMeasurementUiState.Result -> Text(
                    "Вес питомца — ${formatPetWeight(state.petWeightKg)} кг",
                    modifier = Modifier.testTag(PetMeasurementTestTags.Result),
                )
                is PetMeasurementUiState.Error -> Text(
                    state.message,
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
                ) { Text("Новый питомец") }
                is PetMeasurementUiState.Error -> TextButton(
                    onClick = callbacks.onOpen,
                    modifier = Modifier.testTag(PetMeasurementTestTags.BackToSelection),
                ) {
                    Text("Вернуться к выбору")
                }
                is PetMeasurementUiState.Result -> TextButton(
                    onClick = callbacks.onDone,
                    modifier = Modifier.testTag(PetMeasurementTestTags.Done),
                ) {
                    Text("Готово")
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (state !is PetMeasurementUiState.Saving) {
                TextButton(
                    onClick = callbacks.onCancel,
                    modifier = Modifier.testTag(PetMeasurementTestTags.Cancel),
                ) { Text(if (state is PetMeasurementUiState.Error) "Закрыть" else "Отмена") }
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
    val saving = state is PetMeasurementUiState.Saving
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
                        title = { Text("Взвешивание питомца") },
                        navigationIcon = {
                            HuaweiIconButton(
                                icon = HuaweiIcons.Back,
                                contentDescription = "Закрыть взвешивание питомца",
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
                                ) { Text("Отмена") }
                                Button(
                                    onClick = callbacks.onDone,
                                    modifier = Modifier.weight(1f).testTag(PetMeasurementTestTags.Done),
                                ) { Text("Готово") }
                            } else if (!saving) {
                                if (state is PetMeasurementUiState.ConnectionError) {
                                    Button(
                                        onClick = callbacks.onRetry,
                                        modifier = Modifier.weight(1f).testTag(PetMeasurementTestTags.Retry),
                                    ) { Text("Повторить") }
                                }
                                OutlinedButton(
                                    onClick = callbacks.onCancel,
                                    modifier = Modifier.weight(1f).testTag(PetMeasurementTestTags.Cancel),
                                ) { Text("Отменить") }
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
                            Text("Первое взвешивание", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Взвесьтесь с питомцем или без него и дождитесь стабильного показания.",
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                            CircularProgressIndicator()
                            state.currentWeightKg?.let {
                                Text("${formatPetWeight(it)} кг", style = MaterialTheme.typography.headlineMedium)
                            }
                            Text("Ожидаем стабильное значение")
                        }
                        is PetMeasurementUiState.AwaitingSecondWeight -> {
                            Text("Второе взвешивание", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Первое показание: ${formatPetWeight(state.firstWeightKg)} кг",
                                modifier = Modifier.padding(vertical = 16.dp)
                                    .testTag(PetMeasurementTestTags.FirstWeight),
                            )
                            Text(
                                "Повторите взвешивание в другом варианте: с питомцем, если первое было без него, " +
                                    "или без питомца, если первое было с ним.",
                            )
                            CircularProgressIndicator(Modifier.padding(16.dp))
                            state.currentWeightKg?.let {
                                Text("${formatPetWeight(it)} кг", style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                        is PetMeasurementUiState.Result -> {
                            Text("✓", style = MaterialTheme.typography.headlineLarge)
                            Text("Готово", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Вес питомца — ${formatPetWeight(state.petWeightKg)} кг",
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.padding(top = 16.dp).testTag(PetMeasurementTestTags.Result),
                            )
                            state.previousPetWeightKg?.let { previous ->
                                val delta = state.petWeightKg - previous
                                val prefix = if (delta > 0) "+" else ""
                                Text("$prefix${formatPetWeight(delta)} кг с прошлого измерения")
                            }
                        }
                        is PetMeasurementUiState.Saving -> {
                            CircularProgressIndicator()
                            Text("Сохраняем результат…", modifier = Modifier.padding(top = 16.dp))
                        }
                        is PetMeasurementUiState.ConnectionError -> {
                            Text("Соединение прервано", style = MaterialTheme.typography.titleLarge)
                            state.firstWeightKg?.let {
                                Text("Первое показание сохранено: ${formatPetWeight(it)} кг")
                            }
                            Text(state.message, color = MaterialTheme.colorScheme.error)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pets.isEmpty()) Text("Питомцев пока нет. Создайте первого питомца.")
        pets.forEach { item ->
            val detail = item.latestMeasurement?.let {
                "Последний вес: ${formatPetWeight(it.petWeightKg)} кг · ${formatMeasurementDateTime(it.measuredAt)}"
            } ?: "Измерений пока нет"
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    item.latestMeasurement?.let {
                        ManualOriginIndicator(it.origin, Modifier.testTag("pet-latest-manual-origin-${item.pet.id}"))
                    }
                }
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
        trimmed.isEmpty() -> "Введите имя питомца"
        trimmed.length !in PET_NAME_LENGTH -> "Имя должно содержать не больше 50 символов"
        pets.any { normalizePetName(it.pet.displayName) == normalizePetName(trimmed) } ->
            "Питомец с таким именем уже есть"
        species == null -> "Выберите вид питомца"
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; submitted = false },
            label = { Text("Имя питомца") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
            modifier = Modifier.fillMaxWidth().testTag(PetMeasurementTestTags.NameField),
        )
        PetSpeciesSelector(species) { species = it; submitted = false }
        error?.takeIf { it == "Выберите вид питомца" }?.let {
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
        ) { Text("Создать и взвесить") }
    }
}

@Composable
internal fun PetSpeciesSelector(selected: PetSpecies?, onSelected: (PetSpecies) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PetSpecies.CAT to "Кошка", PetSpecies.DOG to "Собака").forEach { (value, label) ->
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

private fun dialogTitle(state: PetMeasurementUiState): String = when (state) {
    PetMeasurementUiState.SelectingPet -> "Выберите питомца"
    PetMeasurementUiState.CreatingPet -> "Новый питомец"
    is PetMeasurementUiState.AwaitingFirstWeight -> "Первое взвешивание"
    is PetMeasurementUiState.AwaitingSecondWeight -> "Второе взвешивание"
    is PetMeasurementUiState.Saving -> "Сохранение"
    is PetMeasurementUiState.Result -> "Готово"
    is PetMeasurementUiState.ConnectionError -> "Соединение прервано"
    is PetMeasurementUiState.Error -> "Не удалось взвесить"
    PetMeasurementUiState.Idle, PetMeasurementUiState.Cancelled -> ""
}

private fun formatPetWeight(value: Double, locale: Locale = Locale.getDefault()): String =
    formatWeight(value, locale)
