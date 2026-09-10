package com.palixander.scalesync

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.palixander.scalesync.domain.BirthDatePrecision
import com.palixander.scalesync.domain.PetSex
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.reference.DogAdultWeightCategory
import com.palixander.scalesync.ui.components.HuaweiIconButton
import com.palixander.scalesync.ui.icons.HuaweiIcons

internal object PetProfileEditorTestTags {
    const val Dialog = "pet-profile-editor-dialog"
    const val Content = "pet-profile-editor-content"
    const val NameField = "pet-profile-editor-name"
    const val SpeciesCat = "pet-profile-editor-species-cat"
    const val SpeciesDog = "pet-profile-editor-species-dog"
    const val SexMale = "pet-profile-editor-sex-male"
    const val SexFemale = "pet-profile-editor-sex-female"
    const val SexClear = "pet-profile-editor-sex-clear"
    const val BreedField = "pet-profile-editor-breed"
    const val BreedClear = "pet-profile-editor-breed-clear"
    const val BreedPicker = "pet-profile-editor-breed-picker"
    const val BreedQuery = "pet-profile-editor-breed-query"
    const val BreedNoResults = "pet-profile-editor-breed-no-results"
    const val BreedOther = "pet-profile-editor-breed-other"
    const val BirthPrecisionYear = "pet-profile-editor-birth-precision-year"
    const val BirthPrecisionMonth = "pet-profile-editor-birth-precision-month"
    const val BirthPrecisionDay = "pet-profile-editor-birth-precision-day"
    const val BirthYear = "pet-profile-editor-birth-year"
    const val BirthMonth = "pet-profile-editor-birth-month"
    const val BirthDay = "pet-profile-editor-birth-day"
    const val BirthClear = "pet-profile-editor-birth-clear"
    const val CategoryClear = "pet-profile-editor-category-clear"
    const val SaveError = "pet-profile-editor-save-error"
    const val Progress = "pet-profile-editor-progress"
    const val Save = "pet-profile-editor-save"
    const val Cancel = "pet-profile-editor-cancel"
    const val Back = "pet-profile-editor-back"
    const val DiscardConfirmation = "pet-profile-editor-discard-confirmation"
    const val Discard = "pet-profile-editor-discard"
    const val KeepEditing = "pet-profile-editor-keep-editing"
    const val SpeciesConfirmation = "pet-profile-editor-species-confirmation"
    const val SpeciesConfirm = "pet-profile-editor-species-confirm"
    const val SpeciesCancel = "pet-profile-editor-species-cancel"

    fun breedOption(id: String): String = "pet-profile-editor-breed-option-$id"

    fun category(category: DogAdultWeightCategory): String =
        "pet-profile-editor-category-${category.name.lowercase()}"
}

/**
 * A stateless pet profile editor. The caller owns [PetProfileEditorState] and applies every
 * [PetProfileAction], so validation and persistence remain outside Compose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PetProfileEditorDialog(
    state: PetProfileEditorState,
    fieldErrors: PetProfileFieldErrors = PetProfileFieldErrors(),
    repositoryError: String? = null,
    busy: Boolean = false,
    breedCatalog: PetBreedCatalog = remember { PetBreedCatalog() },
    onAction: (PetProfileAction) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var breedPickerOpen by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val locked = busy || submitted
    val draft = state.draft
    val editorKey = when (val mode = draft.mode) {
        PetProfileEditorMode.Create -> "create"
        is PetProfileEditorMode.Edit -> "edit:${mode.petId.value}"
    }
    val initialDraft = remember(editorKey) { draft }
    var discardConfirmationVisible by remember(editorKey) { mutableStateOf(false) }

    LaunchedEffect(busy, fieldErrors, repositoryError) {
        if (!busy && (fieldErrors.hasErrors || repositoryError != null)) submitted = false
    }
    LaunchedEffect(busy) {
        if (busy) breedPickerOpen = false
    }

    fun dispatch(action: PetProfileAction) {
        if (!locked) {
            onAction(action)
        }
    }

    fun requestDismiss() {
        if (!locked) {
            if (draft == initialDraft) onDismiss() else discardConfirmationVisible = true
        }
    }

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .then(modifier)
                .testTag(PetProfileEditorTestTags.Dialog),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = when (draft.mode) {
                                    PetProfileEditorMode.Create -> "Новый питомец"
                                    is PetProfileEditorMode.Edit -> "Изменить питомца"
                                },
                            )
                        },
                        navigationIcon = {
                            HuaweiIconButton(
                                icon = HuaweiIcons.Back,
                                contentDescription = "Назад",
                                onClick = ::requestDismiss,
                                enabled = !locked,
                                modifier = Modifier.testTag(PetProfileEditorTestTags.Back),
                            )
                        },
                    )
                },
                bottomBar = {
                    Surface(shadowElevation = 3.dp) {
                        Button(
                            onClick = {
                                if (!locked) {
                                    submitted = true
                                    onSave()
                                }
                            },
                            enabled = !locked,
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .heightIn(min = 48.dp)
                                .testTag(PetProfileEditorTestTags.Save),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp).padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text("Сохранение…")
                            } else {
                                Text("Сохранить")
                            }
                        }
                    }
                },
            ) { contentPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .testTag(PetProfileEditorTestTags.Content),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                OutlinedTextField(
                    value = draft.displayName,
                    onValueChange = { dispatch(PetProfileAction.DisplayNameChanged(it)) },
                    enabled = !locked,
                    singleLine = true,
                    label = { Text("Имя питомца") },
                    isError = fieldErrors.displayName != null,
                    supportingText = fieldErrors.displayName?.let { error ->
                        { FieldError(petNameErrorMessage(error)) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PetProfileEditorTestTags.NameField),
                )

                EditorSection("Вид питомца") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChoiceButton(
                            label = "Кошка",
                            selected = draft.species == PetSpecies.CAT,
                            enabled = !locked,
                            tag = PetProfileEditorTestTags.SpeciesCat,
                            onClick = {
                                dispatch(PetProfileAction.SpeciesChangeRequested(PetSpecies.CAT))
                            },
                        )
                        ChoiceButton(
                            label = "Собака",
                            selected = draft.species == PetSpecies.DOG,
                            enabled = !locked,
                            tag = PetProfileEditorTestTags.SpeciesDog,
                            onClick = {
                                dispatch(PetProfileAction.SpeciesChangeRequested(PetSpecies.DOG))
                            },
                        )
                    }
                    fieldErrors.species?.let {
                        FieldError("Выберите вид питомца")
                    }
                }

                EditorSection("Пол (необязательно)") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChoiceButton(
                            label = "Самец",
                            selected = draft.sex == PetSex.MALE,
                            enabled = !locked,
                            tag = PetProfileEditorTestTags.SexMale,
                            onClick = { dispatch(PetProfileAction.SexChanged(PetSex.MALE)) },
                        )
                        ChoiceButton(
                            label = "Самка",
                            selected = draft.sex == PetSex.FEMALE,
                            enabled = !locked,
                            tag = PetProfileEditorTestTags.SexFemale,
                            onClick = { dispatch(PetProfileAction.SexChanged(PetSex.FEMALE)) },
                        )
                        if (draft.sex != null) {
                            TextButton(
                                onClick = { dispatch(PetProfileAction.SexChanged(null)) },
                                enabled = !locked,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag(PetProfileEditorTestTags.SexClear)
                                    .semantics { contentDescription = "Очистить пол питомца" },
                            ) { Text("Очистить") }
                        }
                    }
                }

                if (draft.species == PetSpecies.DOG || draft.species == PetSpecies.CAT) {
                    EditorSection("Порода") {
                        OutlinedButton(
                            onClick = { breedPickerOpen = true },
                            enabled = !locked,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag(PetProfileEditorTestTags.BreedField)
                                .semantics {
                                    contentDescription = "Выбрать породу. ${petBreedLabel(draft.breed)}"
                                },
                        ) {
                            Text(
                                text = petBreedLabel(draft.breed),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        fieldErrors.breed?.let {
                            FieldError("Порода не соответствует выбранному виду питомца")
                        }
                    }
                }

                BirthDateEditor(
                    value = draft.birthDate,
                    error = fieldErrors.birthDate,
                    enabled = !locked,
                    onChange = { dispatch(PetProfileAction.BirthDateChanged(it)) },
                )

                val dogCategoryApplicable =
                    isDogAdultWeightCategoryApplicable(draft.species, draft.breed)
                if (
                    dogCategoryApplicable ||
                    draft.dogAdultWeightCategory != null ||
                    fieldErrors.dogAdultWeightCategory != null
                ) {
                    DogCategoryEditor(
                        selected = draft.dogAdultWeightCategory,
                        error = fieldErrors.dogAdultWeightCategory,
                        showChoices = dogCategoryApplicable,
                        enabled = !locked,
                        onChange = {
                            dispatch(PetProfileAction.DogAdultWeightCategoryChanged(it))
                        },
                    )
                }

                repositoryError?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(PetProfileEditorTestTags.SaveError)
                            .semantics {
                                contentDescription = "Ошибка сохранения: $message"
                                liveRegion = LiveRegionMode.Polite
                            },
                    ) {
                        Text(message, Modifier.padding(12.dp))
                    }
                }

                if (busy) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Сохранение профиля питомца"
                                stateDescription = "Сохранение"
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .testTag(PetProfileEditorTestTags.Progress),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Сохраняем…")
                    }
                }
                }
            }
        }
    }

    if (discardConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { discardConfirmationVisible = false },
            modifier = Modifier.testTag(PetProfileEditorTestTags.DiscardConfirmation),
            title = { Text("Отказаться от изменений?") },
            text = { Text("Введённые данные не сохранятся.") },
            confirmButton = {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(PetProfileEditorTestTags.Discard),
                ) { Text("Отказаться") }
            },
            dismissButton = {
                TextButton(
                    onClick = { discardConfirmationVisible = false },
                    modifier = Modifier.testTag(PetProfileEditorTestTags.KeepEditing),
                ) { Text("Продолжить редактирование") }
            },
        )
    }

    if (
        breedPickerOpen &&
        (draft.species == PetSpecies.DOG || draft.species == PetSpecies.CAT) &&
        !locked
    ) {
        BreedPickerDialog(
            species = draft.species,
            selected = draft.breed,
            breedCatalog = breedCatalog,
            onSelect = {
                breedPickerOpen = false
                dispatch(PetProfileAction.BreedChanged(it?.let(PetBreedSelection::Available)))
            },
            onDismiss = { breedPickerOpen = false },
        )
    }

    state.pendingSpeciesChange?.let { pending ->
        SpeciesChangeConfirmationDialog(
            pending = pending,
            enabled = !locked,
            onConfirm = { dispatch(PetProfileAction.ConfirmSpeciesChange) },
            onCancel = { dispatch(PetProfileAction.CancelSpeciesChange) },
        )
    }
}

@Composable
private fun EditorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun ChoiceButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(tag)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            },
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun BirthDateEditor(
    value: PetBirthDateInput,
    error: PetBirthDateValidationError?,
    enabled: Boolean,
    onChange: (PetBirthDateInput) -> Unit,
) {
    EditorSection("Дата рождения (необязательно)") {
        Text(
            "Точность даты",
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BirthPrecisionChoice(BirthDatePrecision.YEAR, value, enabled, onChange)
            BirthPrecisionChoice(BirthDatePrecision.MONTH, value, enabled, onChange)
            BirthPrecisionChoice(BirthDatePrecision.DAY, value, enabled, onChange)
        }
        if (value != PetBirthDateInput.Empty) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NumberComponentField(
                    value = value.yearComponent(),
                    label = "Год",
                    tag = PetProfileEditorTestTags.BirthYear,
                    maxLength = 4,
                    enabled = enabled,
                    isError = error != null,
                    modifier = Modifier.width(112.dp),
                    onValueChange = { onChange(value.withYear(it)) },
                )
                if (value is PetBirthDateInput.Month || value is PetBirthDateInput.Day) {
                    NumberComponentField(
                        value = value.monthComponent(),
                        label = "Месяц",
                        tag = PetProfileEditorTestTags.BirthMonth,
                        maxLength = 2,
                        enabled = enabled,
                        isError = error != null,
                        modifier = Modifier.width(112.dp),
                        onValueChange = { onChange(value.withMonth(it)) },
                    )
                }
                if (value is PetBirthDateInput.Day) {
                    NumberComponentField(
                        value = value.day,
                        label = "День",
                        tag = PetProfileEditorTestTags.BirthDay,
                        maxLength = 2,
                        enabled = enabled,
                        isError = error != null,
                        modifier = Modifier.width(112.dp),
                        onValueChange = { onChange(value.copy(day = it)) },
                    )
                }
            }
            TextButton(
                onClick = { onChange(PetBirthDateInput.Empty) },
                enabled = enabled,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(PetProfileEditorTestTags.BirthClear)
                    .semantics { contentDescription = "Очистить дату рождения питомца" },
            ) { Text("Очистить дату") }
        }
        error?.let { FieldError(birthDateErrorMessage(it)) }
    }
}

@Composable
private fun BirthPrecisionChoice(
    precision: BirthDatePrecision,
    value: PetBirthDateInput,
    enabled: Boolean,
    onChange: (PetBirthDateInput) -> Unit,
) {
    val tag = when (precision) {
        BirthDatePrecision.YEAR -> PetProfileEditorTestTags.BirthPrecisionYear
        BirthDatePrecision.MONTH -> PetProfileEditorTestTags.BirthPrecisionMonth
        BirthDatePrecision.DAY -> PetProfileEditorTestTags.BirthPrecisionDay
    }
    ChoiceButton(
        label = birthDatePrecisionLabel(precision),
        selected = value.precision == precision,
        enabled = enabled,
        tag = tag,
        onClick = { onChange(value.withPrecision(precision)) },
    )
}

@Composable
private fun NumberComponentField(
    value: String,
    label: String,
    tag: String,
    maxLength: Int,
    enabled: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            onValueChange(input.filter(Char::isDigit).take(maxLength))
        },
        label = { Text(label) },
        enabled = enabled,
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.testTag(tag),
    )
}

@Composable
private fun DogCategoryEditor(
    selected: DogAdultWeightCategory?,
    error: DogAdultWeightCategoryValidationError?,
    showChoices: Boolean,
    enabled: Boolean,
    onChange: (DogAdultWeightCategory?) -> Unit,
) {
    EditorSection("Весовая категория взрослой собаки (необязательно)") {
        Text(
            "Определяет категорийную центильную кривую Salt для возраста от 12 недель до 2 лет.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showChoices) {
            DogAdultWeightCategory.entries.forEach { category ->
                SelectionRow(
                    label = dogAdultWeightCategoryLabel(category),
                    selected = selected == category,
                    enabled = enabled,
                    tag = PetProfileEditorTestTags.category(category),
                    onClick = { onChange(category) },
                )
            }
        } else if (selected != null) {
            Text("Сохранена категория: ${dogAdultWeightCategoryLabel(selected)}")
        }
        if (selected != null) {
            TextButton(
                onClick = { onChange(null) },
                enabled = enabled,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(PetProfileEditorTestTags.CategoryClear)
                    .semantics { contentDescription = "Очистить весовую категорию собаки" },
            ) { Text("Очистить категорию") }
        }
        error?.let {
            FieldError("Весовая категория недоступна для выбранной породы")
        }
    }
}

@Composable
private fun BreedPickerDialog(
    species: PetSpecies,
    selected: PetBreedSelection?,
    breedCatalog: PetBreedCatalog,
    onSelect: (PetBreedOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable(species) { mutableStateOf("") }
    val options = remember(query, species, breedCatalog) {
        breedCatalog.search(query, species)
    }
    val listState = rememberLazyListState()
    val normalizedQuery = query.trim().lowercase()
    val showOther = normalizedQuery.isEmpty() || "другая порода".contains(normalizedQuery)
    LaunchedEffect(normalizedQuery, selected?.id, options) {
        if (normalizedQuery.isEmpty()) {
            val selectedIndex = options.indexOfFirst { it.id == selected?.id }
            if (selectedIndex >= 0) listState.scrollToItem(selectedIndex + 1)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(PetProfileEditorTestTags.BreedPicker),
        title = {
            Text(
                "Выберите породу",
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск породы") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PetProfileEditorTestTags.BreedQuery),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (showOther) {
                        item(key = "other") {
                            SelectionRow(
                                label = "Другая порода",
                                selected = selected == null,
                                enabled = true,
                                tag = PetProfileEditorTestTags.BreedOther,
                                onClick = { onSelect(null) },
                            )
                        }
                    }
                    if (options.isEmpty() && !showOther) {
                        item {
                            Text(
                                "Поддерживаемые породы не найдены",
                                modifier = Modifier.testTag(PetProfileEditorTestTags.BreedNoResults),
                            )
                        }
                    } else if (options.isNotEmpty()) {
                        items(
                            items = options,
                            key = { option -> option.id.value },
                        ) { option ->
                            SelectionRow(
                                label = option.displayName,
                                supportingLabel = option.canonicalName.takeIf { option.species == PetSpecies.DOG },
                                selected = selected?.id == option.id,
                                enabled = true,
                                tag = PetProfileEditorTestTags.breedOption(option.id.value),
                                onClick = { onSelect(option) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun SelectionRow(
    label: String,
    supportingLabel: String? = null,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(label)
                supportingLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeciesChangeConfirmationDialog(
    pending: PendingPetSpeciesChange,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val clearedFields = buildList {
        if (pending.clearBreed) add("порода")
        if (pending.clearDogAdultWeightCategory) add("весовая категория")
    }.joinToString(" и ")
    AlertDialog(
        onDismissRequest = { if (enabled) onCancel() },
        modifier = Modifier.testTag(PetProfileEditorTestTags.SpeciesConfirmation),
        title = {
            Text(
                "Сменить вид питомца?",
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Text("При смене вида будут очищены: $clearedFields. Остальные данные сохранятся.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = enabled,
                modifier = Modifier.testTag(PetProfileEditorTestTags.SpeciesConfirm),
            ) { Text("Сменить вид") }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = enabled,
                modifier = Modifier.testTag(PetProfileEditorTestTags.SpeciesCancel),
            ) { Text("Отмена") }
        },
    )
}

@Composable
private fun FieldError(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun PetBirthDateInput.withPrecision(precision: BirthDatePrecision): PetBirthDateInput =
    when (precision) {
        BirthDatePrecision.YEAR -> PetBirthDateInput.Year(yearComponent())
        BirthDatePrecision.MONTH -> PetBirthDateInput.Month(yearComponent(), monthComponent())
        BirthDatePrecision.DAY -> PetBirthDateInput.Day(
            yearComponent(),
            monthComponent(),
            (this as? PetBirthDateInput.Day)?.day.orEmpty(),
        )
    }

private fun PetBirthDateInput.yearComponent(): String = when (this) {
    PetBirthDateInput.Empty -> ""
    is PetBirthDateInput.Year -> year
    is PetBirthDateInput.Month -> year
    is PetBirthDateInput.Day -> year
}

private fun PetBirthDateInput.monthComponent(): String = when (this) {
    is PetBirthDateInput.Month -> month
    is PetBirthDateInput.Day -> month
    PetBirthDateInput.Empty,
    is PetBirthDateInput.Year,
    -> ""
}

private fun PetBirthDateInput.withYear(year: String): PetBirthDateInput = when (this) {
    PetBirthDateInput.Empty -> PetBirthDateInput.Year(year)
    is PetBirthDateInput.Year -> copy(year = year)
    is PetBirthDateInput.Month -> copy(year = year)
    is PetBirthDateInput.Day -> copy(year = year)
}

private fun PetBirthDateInput.withMonth(month: String): PetBirthDateInput = when (this) {
    is PetBirthDateInput.Month -> copy(month = month)
    is PetBirthDateInput.Day -> copy(month = month)
    PetBirthDateInput.Empty,
    is PetBirthDateInput.Year,
    -> this
}

private fun petNameErrorMessage(error: PetNameValidationError): String = when (error) {
    PetNameValidationError.REQUIRED -> "Введите имя питомца"
    PetNameValidationError.TOO_LONG -> "Имя должно содержать не больше 50 символов"
    PetNameValidationError.DUPLICATE -> "Питомец с таким именем уже есть"
}

private fun birthDateErrorMessage(error: PetBirthDateValidationError): String = when (error) {
    PetBirthDateValidationError.INCOMPLETE -> "Заполните все выбранные части даты"
    PetBirthDateValidationError.INVALID -> "Введите существующую дату"
    PetBirthDateValidationError.FUTURE -> "Дата рождения не может быть в будущем"
}
