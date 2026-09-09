package com.palixander.scalesync

import com.palixander.scalesync.measurements.formatWeight
import com.palixander.scalesync.ui.components.ManualOriginIndicator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    fun pet(id: PetId) = "pet-select-${id.value}"
}

internal data class PetMeasurementCallbacks(
    val onOpen: () -> Unit,
    val onShowCreate: () -> Unit,
    val onCreateAndStart: (String, PetSpecies) -> Unit,
    val onStart: (PetId) -> Unit,
    val onCancel: () -> Unit,
) {
    companion object {
        val None = PetMeasurementCallbacks({}, {}, { _, _ -> }, {}, {})
    }
}

@Composable
internal fun PetMeasurementDialog(
    state: PetMeasurementUiState,
    pets: List<PetWithLatestWeight>,
    callbacks: PetMeasurementCallbacks,
) {
    if (state == PetMeasurementUiState.Idle || state == PetMeasurementUiState.Cancelled) return

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
                is PetMeasurementUiState.Completed -> Text(
                    "Вес ${state.pet.displayName}: ${formatPetWeight(state.measurement.petWeightKg)} кг",
                    modifier = Modifier.testTag(PetMeasurementTestTags.Result),
                )
                is PetMeasurementUiState.Error -> Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                )
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
                is PetMeasurementUiState.Completed -> TextButton(
                    onClick = callbacks.onCancel,
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
    is PetMeasurementUiState.Completed -> "Готово"
    is PetMeasurementUiState.Error -> "Не удалось взвесить"
    PetMeasurementUiState.Idle, PetMeasurementUiState.Cancelled -> ""
}

private fun formatPetWeight(value: Double, locale: Locale = Locale.getDefault()): String =
    formatWeight(value, locale)
