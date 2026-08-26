package com.example.huaweimisync

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
import com.example.huaweimisync.domain.PET_NAME_LENGTH
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetWithLatestWeight
import com.example.huaweimisync.domain.normalizePetName
import com.example.huaweimisync.measurements.formatMeasurementDateTime
import java.text.NumberFormat
import java.util.Locale

internal object PetMeasurementTestTags {
    const val Dialog = "pet-measurement-dialog"
    const val CreateAction = "pet-create-action"
    const val NameField = "pet-name-field"
    const val CreateConfirm = "pet-create-confirm"
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
    val onCreateAndStart: (String) -> Unit,
    val onStart: (PetId) -> Unit,
    val onCancel: () -> Unit,
) {
    companion object {
        val None = PetMeasurementCallbacks({}, {}, {}, {}, {})
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
        modifier = Modifier.testTag(PetMeasurementTestTags.Dialog),
        title = { Text(dialogTitle(state)) },
        text = {
            when (state) {
                PetMeasurementUiState.SelectingPet -> PetSelection(pets, callbacks)
                PetMeasurementUiState.CreatingPet -> PetCreation(pets, callbacks)
                is PetMeasurementUiState.AwaitingFirstWeight -> Column {
                    Text("Встаньте на весы без ${state.pet.displayName}. Дождитесь стабильного значения.")
                }
                is PetMeasurementUiState.AwaitingSecondWeight -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Первое значение принято: ${formatPetWeight(state.firstWeightKg)} кг",
                        modifier = Modifier.testTag(PetMeasurementTestTags.FirstWeight),
                    )
                    Text("Возьмите ${state.pet.displayName} на руки и снова встаньте на весы.")
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
    val trimmed = name.trim()
    val error = when {
        !submitted -> null
        trimmed.isEmpty() -> "Введите имя питомца"
        trimmed.length !in PET_NAME_LENGTH -> "Имя должно содержать не больше 50 символов"
        pets.any { normalizePetName(it.pet.displayName) == normalizePetName(trimmed) } ->
            "Питомец с таким именем уже есть"
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
        Button(
            onClick = {
                submitted = true
                if (trimmed.isNotEmpty() && trimmed.length in PET_NAME_LENGTH &&
                    pets.none { normalizePetName(it.pet.displayName) == normalizePetName(trimmed) }
                ) callbacks.onCreateAndStart(trimmed)
            },
            modifier = Modifier.fillMaxWidth().testTag(PetMeasurementTestTags.CreateConfirm),
        ) { Text("Создать и взвесить") }
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
    NumberFormat.getNumberInstance(locale).run {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        format(value)
    }
