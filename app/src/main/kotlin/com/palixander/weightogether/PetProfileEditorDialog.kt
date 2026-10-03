package com.palixander.weightogether

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import java.time.LocalDate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.palixander.weightogether.domain.PetSex
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.ui.text.resolve
import com.palixander.weightogether.domain.reference.DogAdultWeightCategory
import com.palixander.weightogether.ui.components.ScaleSyncIconButton
import com.palixander.weightogether.ui.currentAppLocale
import com.palixander.weightogether.ui.components.EditableProfileAvatar
import com.palixander.weightogether.ui.components.currentProfilePhotoStore
import com.palixander.weightogether.ui.icons.ScaleSyncIcons
import com.palixander.weightogether.ui.theme.ScaleSyncDimensions
import com.palixander.weightogether.profile.ProfilePhotoError
import com.palixander.weightogether.profile.PreparedProfilePhoto
import com.palixander.weightogether.profile.ProfilePhotoPicker
import com.palixander.weightogether.profile.ProfilePhotoStore
import com.palixander.weightogether.profile.ProfilePhotoOwner
import com.palixander.weightogether.profile.ProfilePhotoOwnerType
import com.palixander.weightogether.profile.rememberProfilePhotoCropController
import com.palixander.weightogether.profile.rememberProfilePhotoPicker
import kotlinx.coroutines.launch

internal object PetProfileEditorTestTags {
    const val Dialog = "pet-profile-editor-dialog"
    const val Content = "pet-profile-editor-content"
    const val Title = "pet-profile-editor-title"
    const val NameField = "pet-profile-editor-name"
    const val Photo = "pet-profile-editor-photo"
    const val PhotoGallery = "pet-profile-editor-photo-gallery"
    const val PhotoCamera = "pet-profile-editor-photo-camera"
    const val PhotoRemove = "pet-profile-editor-photo-remove"
    const val SpeciesCat = "pet-profile-editor-species-cat"
    const val SpeciesDog = "pet-profile-editor-species-dog"
    const val SpeciesGroup = "pet-profile-editor-species-group"
    const val SexMale = "pet-profile-editor-sex-male"
    const val SexFemale = "pet-profile-editor-sex-female"
    const val SexClear = "pet-profile-editor-sex-clear"
    const val SexGroup = "pet-profile-editor-sex-group"
    const val BreedField = "pet-profile-editor-breed"
    const val BreedClear = "pet-profile-editor-breed-clear"
    const val BreedPicker = "pet-profile-editor-breed-picker"
    const val BreedQuery = "pet-profile-editor-breed-query"
    const val BreedNoResults = "pet-profile-editor-breed-no-results"
    const val BreedOther = "pet-profile-editor-breed-other"
    const val BirthYear = "pet-profile-editor-birth-year"
    const val BirthMonth = "pet-profile-editor-birth-month"
    const val BirthDay = "pet-profile-editor-birth-day"
    const val BirthClear = "pet-profile-editor-birth-clear"
    const val CategoryClear = "pet-profile-editor-category-clear"
    const val HeightField = "pet-profile-editor-height"
    const val SaveError = "pet-profile-editor-save-error"
    const val Progress = "pet-profile-editor-progress"
    const val Save = "pet-profile-editor-save"
    const val Cancel = "pet-profile-editor-cancel"
    const val SpeciesConfirmation = "pet-profile-editor-species-confirmation"
    const val SpeciesConfirm = "pet-profile-editor-species-confirm"
    const val SpeciesCancel = "pet-profile-editor-species-cancel"
    const val Back = "pet-profile-editor-back"
    const val DiscardConfirmation = "pet-profile-editor-discard-confirmation"
    const val Discard = "pet-profile-editor-discard"
    const val KeepEditing = "pet-profile-editor-keep-editing"

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
    onSaveAndContinue: () -> Unit = onSave,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    profilePhotoStore: ProfilePhotoStore? = currentProfilePhotoStore(),
    photoPickerFactory: @Composable (
        ProfilePhotoStore,
        (PreparedProfilePhoto) -> Unit,
        (ProfilePhotoError) -> Unit,
    ) -> ProfilePhotoPicker = { store, onPrepared, onError ->
        rememberProfilePhotoPicker(store, onPrepared, onError)
    },
) {
    var breedPickerOpen by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var discardRequested by rememberSaveable { mutableStateOf(false) }
    var remindersOpen by rememberSaveable { mutableStateOf(false) }
    var reminderGuardOpen by rememberSaveable { mutableStateOf(false) }
    var openAfterSave by rememberSaveable { mutableStateOf(false) }
    val locked = busy || submitted
    val draft = state.draft
    val initialState = remember(draft.mode) { state }
    var persistedState by remember(draft.mode) { mutableStateOf(initialState) }
    val dirty = draft != persistedState.draft
    val contentScrollState = rememberScrollState()
    val nameFocus = remember { FocusRequester() }
    val titleFocus = remember { FocusRequester() }
    val speciesFocus = remember { FocusRequester() }
    val breedFocus = remember { FocusRequester() }
    val birthDateFocus = remember { FocusRequester() }
    val heightFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val speciesLabel = stringResource(R.string.pet_editor_species)
    val sexLabel = stringResource(R.string.pet_editor_sex)
    val clearSexLabel = stringResource(R.string.pet_editor_clear_sex)
    val savingA11y = stringResource(R.string.pet_editor_saving_a11y)
    val savingState = stringResource(R.string.state_saving)
    val photoStore = profilePhotoStore
    var photoError by remember(draft.mode) { mutableStateOf<String?>(null) }
    val newPhotoOwnerId = rememberSaveable(draft.mode) {
        "new-pet-${java.util.UUID.randomUUID()}"
    }
    val photoOwner = remember(draft.mode, newPhotoOwnerId) {
        ProfilePhotoOwner(
            ProfilePhotoOwnerType.PET,
            (draft.mode as? PetProfileEditorMode.Edit)?.petId?.value
                ?: newPhotoOwnerId,
        )
    }
    fun deleteTransientPhoto(path: String?) {
        if (path != null && path != persistedState.draft.photoPath) {
            scope.launch { runCatching { photoStore?.onPhotoDereferenced(path) } }
        }
    }
    val photoCrop = photoStore?.let { store ->
        rememberProfilePhotoCropController(
            store = store,
            owner = photoOwner,
            onPhotoReady = { path ->
                photoError = null
                deleteTransientPhoto(draft.photoPath)
                dispatchPhoto(onAction, path, locked)
            },
            onError = { error -> photoError = resources.getString(petPhotoErrorResource(error)) },
        )
    }
    val photoPicker = photoStore?.let { store ->
        photoPickerFactory(
            store,
            { photoCrop?.open(it) },
            { error -> photoError = resources.getString(petPhotoErrorResource(error)) },
        )
    }

    LaunchedEffect(Unit) {
        if (!fieldErrors.hasErrors) titleFocus.requestFocus()
    }

    LaunchedEffect(busy, fieldErrors, repositoryError) {
        if (!busy && (fieldErrors.hasErrors || repositoryError != null)) submitted = false
        if (openAfterSave && !busy) {
            if (!fieldErrors.hasErrors && repositoryError == null) {
                persistedState = state
                remindersOpen = true
            }
            openAfterSave = false
        }
    }
    val reminderPetId = (draft.mode as? PetProfileEditorMode.Edit)?.petId
    if (remindersOpen && reminderPetId != null) {
        com.palixander.weightogether.ui.reminder.ReminderSettingsScreen(
            owner = com.palixander.weightogether.domain.WeighingReminderOwner.Pet(reminderPetId),
            onBack = { remindersOpen = false },
        )
        return
    }
    LaunchedEffect(busy) {
        if (busy) breedPickerOpen = false
    }

    fun dispatch(action: PetProfileAction) {
        if (!locked) {
            onAction(action)
        }
    }

    fun requestClose() {
        if (!locked) {
            if (dirty) discardRequested = true else onDismiss()
        }
    }

    fun requestFirstInvalidField() {
        scope.launch {
            val target = when {
                fieldErrors.displayName != null -> nameFocus
                fieldErrors.species != null -> speciesFocus
                fieldErrors.breed != null -> breedFocus
                fieldErrors.heightCm != null -> heightFocus
                fieldErrors.birthDate != null -> birthDateFocus
                fieldErrors.dogAdultWeightCategory != null -> categoryFocus
                else -> null
            }
            target?.requestFocus()
        }
    }

    LaunchedEffect(fieldErrors) {
        if (fieldErrors.hasErrors) requestFirstInvalidField()
    }

    BackHandler(onBack = ::requestClose)

    Scaffold(
        modifier = modifier.fillMaxSize().testTag(PetProfileEditorTestTags.Dialog),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (draft.mode) {
                            PetProfileEditorMode.Create -> stringResource(R.string.pet_editor_create_title)
                            is PetProfileEditorMode.Edit -> stringResource(R.string.pet_editor_edit_title)
                        },
                        modifier = Modifier
                            .focusRequester(titleFocus)
                            .focusable()
                            .testTag(PetProfileEditorTestTags.Title)
                            .semantics { heading() },
                    )
                },
                navigationIcon = {
                    ScaleSyncIconButton(
                        icon = ScaleSyncIcons.Back,
                        contentDescription = stringResource(R.string.action_back_to_profiles),
                        onClick = ::requestClose,
                        enabled = !locked,
                        modifier = Modifier.testTag(PetProfileEditorTestTags.Back),
                    )
                },
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().imePadding()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 2.dp,
            ) {
                Button(
                    onClick = {
                        if (!locked) {
                            submitted = true
                            onSave()
                        }
                    },
                    enabled = !locked,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScaleSyncDimensions.ContentPadding, vertical = 12.dp)
                        .heightIn(min = ScaleSyncDimensions.TouchTarget)
                        .testTag(PetProfileEditorTestTags.Save),
                ) { Text(stringResource(if (busy) R.string.state_saving else R.string.action_save)) }
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .verticalScroll(contentScrollState)
                    .padding(ScaleSyncDimensions.ContentPadding)
                    .testTag(PetProfileEditorTestTags.Content),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                EditableProfileAvatar(
                    photoPath = draft.photoPath,
                    fallbackIcon = when (draft.species) {
                        PetSpecies.CAT -> ScaleSyncIcons.Cat
                        PetSpecies.DOG -> ScaleSyncIcons.Dog
                        else -> ScaleSyncIcons.Profile
                    },
                    contentDescription = stringResource(R.string.pet_editor_photo),
                    store = photoStore,
                    size = 96.dp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                        .testTag(PetProfileEditorTestTags.Photo),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { photoPicker?.chooseFromGallery?.invoke() },
                        enabled = !locked && photoPicker != null,
                        modifier = Modifier.heightIn(min = ScaleSyncDimensions.TouchTarget)
                            .testTag(PetProfileEditorTestTags.PhotoGallery),
                    ) { Text(stringResource(R.string.photo_gallery)) }
                    OutlinedButton(
                        onClick = { photoPicker?.takePhoto?.invoke() },
                        enabled = !locked && photoPicker != null,
                        modifier = Modifier.heightIn(min = ScaleSyncDimensions.TouchTarget)
                            .testTag(PetProfileEditorTestTags.PhotoCamera),
                    ) { Text(stringResource(R.string.photo_camera)) }
                    if (draft.photoPath != null) OutlinedButton(
                        onClick = {
                            deleteTransientPhoto(draft.photoPath)
                            dispatch(PetProfileAction.PhotoChanged(null))
                        },
                        enabled = !locked,
                        modifier = Modifier.heightIn(min = ScaleSyncDimensions.TouchTarget)
                            .testTag(PetProfileEditorTestTags.PhotoRemove),
                    ) { Text(stringResource(R.string.photo_remove)) }
                }
                photoError?.let { FieldError(it) }
                OutlinedTextField(
                    value = draft.displayName,
                    onValueChange = { dispatch(PetProfileAction.DisplayNameChanged(it)) },
                    enabled = !locked,
                    singleLine = true,
                    label = { Text(stringResource(R.string.pet_editor_name)) },
                    isError = fieldErrors.displayName != null,
                    supportingText = fieldErrors.displayName?.let { error ->
                        { FieldError(stringResource(petNameErrorResource(error))) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocus)
                        .testTag(PetProfileEditorTestTags.NameField),
                )

                EditorSection(stringResource(R.string.pet_editor_species)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(speciesFocus)
                            .focusable()
                            .testTag(PetProfileEditorTestTags.SpeciesGroup)
                            .semantics {
                                contentDescription = speciesLabel
                                selectableGroup()
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PrimarySelectionButton(
                            label = stringResource(R.string.pet_editor_cat),
                            selected = draft.species == PetSpecies.CAT,
                            enabled = !locked,
                            tag = PetProfileEditorTestTags.SpeciesCat,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                dispatch(PetProfileAction.SpeciesChangeRequested(PetSpecies.CAT))
                            },
                        )
                        PrimarySelectionButton(
                            label = stringResource(R.string.pet_editor_dog),
                            selected = draft.species == PetSpecies.DOG,
                            enabled = !locked,
                            tag = PetProfileEditorTestTags.SpeciesDog,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                dispatch(PetProfileAction.SpeciesChangeRequested(PetSpecies.DOG))
                            },
                        )
                    }
                    fieldErrors.species?.let {
                        FieldError(stringResource(R.string.pet_editor_species_required))
                    }
                }

                EditorSection(stringResource(R.string.pet_editor_sex_optional)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(PetProfileEditorTestTags.SexGroup)
                            .semantics {
                                contentDescription = sexLabel
                                selectableGroup()
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PrimarySelectionButton(
                            label = stringResource(R.string.pet_editor_male),
                            selected = draft.sex == PetSex.MALE,
                            enabled = !locked,
                            tag = PetProfileEditorTestTags.SexMale,
                            modifier = Modifier.weight(1f),
                            onClick = { dispatch(PetProfileAction.SexChanged(PetSex.MALE)) },
                        )
                        PrimarySelectionButton(
                            label = stringResource(R.string.pet_editor_female),
                            selected = draft.sex == PetSex.FEMALE,
                            enabled = !locked,
                            tag = PetProfileEditorTestTags.SexFemale,
                            modifier = Modifier.weight(1f),
                            onClick = { dispatch(PetProfileAction.SexChanged(PetSex.FEMALE)) },
                        )
                    }
                    if (draft.sex != null) {
                        TextButton(
                            onClick = { dispatch(PetProfileAction.SexChanged(null)) },
                            enabled = !locked,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(PetProfileEditorTestTags.SexClear)
                                .semantics { contentDescription = clearSexLabel },
                        ) { Text(stringResource(R.string.action_clear)) }
                    }
                }

                if (draft.species == PetSpecies.DOG || draft.species == PetSpecies.CAT) {
                    EditorSection(stringResource(R.string.pet_editor_breed)) {
                        val locale = com.palixander.weightogether.ui.currentAppLocale()
                        val localizedBreed = draft.breed?.let {
                            breedCatalog.resolve(it.id, it.species, locale)
                        }
                        val breedLabel = petBreedLabel(localizedBreed).resolve(LocalContext.current.resources)
                        val breedA11y = stringResource(R.string.pet_editor_choose_breed_a11y, breedLabel)
                        OutlinedButton(
                            onClick = { breedPickerOpen = true },
                            enabled = !locked,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .focusRequester(breedFocus)
                                .testTag(PetProfileEditorTestTags.BreedField)
                                .semantics {
                                    contentDescription = breedA11y
                                },
                        ) {
                            Text(
                                text = breedLabel,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        fieldErrors.breed?.let {
                            FieldError(stringResource(R.string.pet_editor_breed_mismatch))
                        }
                    }
                }

                OutlinedTextField(
                    value = draft.heightCm,
                    onValueChange = { dispatch(PetProfileAction.HeightChanged(it)) },
                    enabled = !locked,
                    singleLine = true,
                    label = { Text(stringResource(R.string.pet_editor_height)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = fieldErrors.heightCm != null,
                    supportingText = {
                        if (fieldErrors.heightCm != null) {
                            FieldError(stringResource(R.string.pet_editor_height_invalid))
                        } else {
                            Text(stringResource(R.string.pet_editor_height_optional))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(heightFocus)
                        .testTag(PetProfileEditorTestTags.HeightField),
                )

                BirthDateEditor(
                    value = draft.birthDate,
                    error = fieldErrors.birthDate,
                    enabled = !locked,
                    onChange = { dispatch(PetProfileAction.BirthDateChanged(it)) },
                    modifier = Modifier.focusRequester(birthDateFocus).focusable(),
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
                        modifier = Modifier.focusRequester(categoryFocus).focusable(),
                    )
                }

                repositoryError?.let { message ->
                    val saveErrorA11y = stringResource(R.string.pet_editor_save_error_a11y, message)
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(PetProfileEditorTestTags.SaveError)
                            .semantics {
                                contentDescription = saveErrorA11y
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
                                contentDescription = savingA11y
                                stateDescription = savingState
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
                        Text(stringResource(R.string.state_saving))
                    }
                }
                reminderPetId?.let { petId ->
                    com.palixander.weightogether.ui.reminder.ReminderSettingsEntry(
                        owner = com.palixander.weightogether.domain.WeighingReminderOwner.Pet(petId),
                        onClick = { if (dirty) reminderGuardOpen = true else remindersOpen = true },
                    )
                }
            }
        }
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

    if (discardRequested) {
        AlertDialog(
            onDismissRequest = { discardRequested = false },
            modifier = Modifier.testTag(PetProfileEditorTestTags.DiscardConfirmation),
            title = { Text(stringResource(R.string.pet_editor_discard_title)) },
            text = { Text(stringResource(R.string.pet_editor_discard_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTransientPhoto(draft.photoPath)
                        onDismiss()
                    },
                    modifier = Modifier.testTag(PetProfileEditorTestTags.Discard),
                ) { Text(stringResource(R.string.pet_editor_discard)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { discardRequested = false },
                    modifier = Modifier.testTag(PetProfileEditorTestTags.KeepEditing),
                ) { Text(stringResource(R.string.pet_editor_keep_editing)) }
            },
        )
    }
    if (reminderGuardOpen) AlertDialog(
        onDismissRequest = { reminderGuardOpen = false },
        title = { Text(stringResource(R.string.reminder_unsaved_title)) },
        text = { Text(stringResource(R.string.reminder_unsaved_text)) },
        confirmButton = { TextButton(onClick = { reminderGuardOpen = false; openAfterSave = true; submitted = true; onSaveAndContinue() }) { Text(stringResource(R.string.reminder_save_continue)) } },
        dismissButton = { Column { TextButton(onClick = { reminderGuardOpen = false; deleteTransientPhoto(draft.photoPath); onAction(PetProfileAction.RestorePersisted(persistedState)); remindersOpen = true }) { Text(stringResource(R.string.reminder_discard_continue)) }; TextButton(onClick = { reminderGuardOpen = false }) { Text(stringResource(R.string.reminder_keep_editing)) } } },
    )
}

private fun dispatchPhoto(onAction: (PetProfileAction) -> Unit, path: String, locked: Boolean) {
    if (!locked) onAction(PetProfileAction.PhotoChanged(path))
}

private fun petPhotoErrorResource(error: ProfilePhotoError): Int = when (error) {
    ProfilePhotoError.UNREADABLE_SOURCE -> R.string.photo_error_unreadable
    ProfilePhotoError.INVALID_IMAGE -> R.string.photo_error_invalid
    ProfilePhotoError.PROCESSING_FAILED -> R.string.photo_error_processing
}

@Composable
private fun EditorSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
private fun PrimarySelectionButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val choiceModifier = modifier
        .heightIn(min = 48.dp)
        .testTag(tag)
        .semantics {
            role = Role.RadioButton
            this.selected = selected
        }
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = choiceModifier) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = choiceModifier) {
            Text(label)
        }
    }
}

@Composable
private fun BirthDateEditor(
    value: PetBirthDateInput,
    error: PetBirthDateValidationError?,
    enabled: Boolean,
    onChange: (PetBirthDateInput) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activePart by rememberSaveable { mutableStateOf<PetBirthDatePart?>(null) }
    val locale = currentAppLocale()
    val today = LocalDate.now()
    LaunchedEffect(enabled) { if (!enabled) activePart = null }
    EditorSection(stringResource(R.string.pet_editor_birth_date_optional), modifier) {
        Text(stringResource(R.string.pet_editor_birth_date_help))
        PetBirthDatePart.entries.forEach { part ->
            if (petBirthDateOptions(value, part, today).isNotEmpty()) {
                val selected = value.component(part)
                OutlinedButton(
                    onClick = { activePart = part },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag(birthPartTag(part)),
                ) { Text(stringResource(R.string.pet_editor_birth_part_value, stringResource(petBirthPartResource(part)), selected?.let { petBirthDatePartLabel(part, it, locale) } ?: stringResource(R.string.action_select))) }
            }
        }
        error?.let { FieldError(stringResource(birthDateErrorResource(it))) }
    }
    activePart?.takeIf { enabled }?.let { part ->
        val options = petBirthDateOptions(value, part, today)
        val selected = value.component(part)
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = options.indexOf(selected).coerceAtLeast(0),
        )
        AlertDialog(
            onDismissRequest = { activePart = null },
            title = { Text(stringResource(R.string.pet_editor_birth_picker_title, stringResource(petBirthPartResource(part)).lowercase())) },
            text = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                        .testTag("pet-birth-options"),
                ) {
                    items(options, key = { it }) { option ->
                        SelectionRow(
                            label = petBirthDatePartLabel(part, option, locale),
                            selected = selected == option,
                            enabled = enabled,
                            tag = "pet-birth-option-$option",
                            onClick = {
                                onChange(selectPetBirthDatePart(value, part, option, today))
                                activePart = null
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activePart = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    onChange(selectPetBirthDatePart(value, part, null, today))
                    activePart = null
                }, modifier = Modifier.testTag("pet-birth-part-clear")) {
                    Text(when (part) {
                        PetBirthDatePart.YEAR -> stringResource(R.string.pet_editor_clear_date)
                        PetBirthDatePart.MONTH -> stringResource(R.string.pet_editor_keep_year)
                        PetBirthDatePart.DAY -> stringResource(R.string.pet_editor_keep_year_month)
                    })
                }
            },
        )
    }
}

private fun birthPartTag(part: PetBirthDatePart): String = when (part) {
    PetBirthDatePart.YEAR -> PetProfileEditorTestTags.BirthYear
    PetBirthDatePart.MONTH -> PetProfileEditorTestTags.BirthMonth
    PetBirthDatePart.DAY -> PetProfileEditorTestTags.BirthDay
}

@Composable
private fun DogCategoryEditor(
    selected: DogAdultWeightCategory?,
    error: DogAdultWeightCategoryValidationError?,
    showChoices: Boolean,
    enabled: Boolean,
    onChange: (DogAdultWeightCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    val clearCategoryA11y = stringResource(R.string.pet_editor_clear_category_a11y)
    EditorSection(stringResource(R.string.pet_editor_dog_category_optional), modifier) {
        Text(
            stringResource(R.string.pet_editor_dog_category_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showChoices) {
            DogAdultWeightCategory.entries.forEach { category ->
                SelectionRow(
                    label = dogAdultWeightCategoryLabel(category).resolve(resources),
                    selected = selected == category,
                    enabled = enabled,
                    tag = PetProfileEditorTestTags.category(category),
                    onClick = { onChange(category) },
                )
            }
        } else if (selected != null) {
            Text(stringResource(R.string.pet_editor_saved_category, dogAdultWeightCategoryLabel(selected).resolve(resources)))
        }
        if (selected != null) {
            TextButton(
                onClick = { onChange(null) },
                enabled = enabled,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(PetProfileEditorTestTags.CategoryClear)
                    .semantics { contentDescription = clearCategoryA11y },
            ) { Text(stringResource(R.string.pet_editor_clear_category)) }
        }
        error?.let {
            FieldError(stringResource(R.string.pet_editor_category_unavailable))
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
    val locale = com.palixander.weightogether.ui.currentAppLocale()
    var query by rememberSaveable(species) { mutableStateOf("") }
    val options = remember(query, species, breedCatalog, locale) {
        breedCatalog.search(query, species, locale)
    }
    val listState = rememberLazyListState()
    val normalizedQuery = query.trim().lowercase()
    val otherBreedLabel = stringResource(R.string.pet_editor_other_breed)
    val showOther = normalizedQuery.isEmpty() || otherBreedLabel.lowercase().contains(normalizedQuery)
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
                stringResource(R.string.pet_editor_choose_breed),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.pet_editor_search_breed)) },
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
                                label = otherBreedLabel,
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
                                stringResource(R.string.pet_editor_no_breeds),
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
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
        if (pending.clearBreed) add(stringResource(R.string.pet_editor_breed).lowercase())
        if (pending.clearDogAdultWeightCategory) add(stringResource(R.string.pet_editor_weight_category).lowercase())
    }.joinToString(stringResource(R.string.list_and_separator))
    AlertDialog(
        onDismissRequest = { if (enabled) onCancel() },
        modifier = Modifier.testTag(PetProfileEditorTestTags.SpeciesConfirmation),
        title = {
            Text(
                stringResource(R.string.pet_editor_change_species_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Text(stringResource(R.string.pet_editor_change_species_text, clearedFields))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = enabled,
                modifier = Modifier.testTag(PetProfileEditorTestTags.SpeciesConfirm),
            ) { Text(stringResource(R.string.pet_editor_change_species)) }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = enabled,
                modifier = Modifier.testTag(PetProfileEditorTestTags.SpeciesCancel),
            ) { Text(stringResource(R.string.action_cancel)) }
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

private fun petNameErrorResource(error: PetNameValidationError): Int = when (error) {
    PetNameValidationError.REQUIRED -> R.string.pet_editor_name_required
    PetNameValidationError.TOO_LONG -> R.string.pet_editor_name_too_long
    PetNameValidationError.DUPLICATE -> R.string.pet_editor_name_duplicate
}

private fun birthDateErrorResource(error: PetBirthDateValidationError): Int = when (error) {
    PetBirthDateValidationError.INCOMPLETE -> R.string.pet_editor_birth_incomplete
    PetBirthDateValidationError.INVALID -> R.string.pet_editor_birth_invalid
    PetBirthDateValidationError.FUTURE -> R.string.pet_editor_birth_future
}

private fun petBirthPartResource(part: PetBirthDatePart): Int = when (part) {
    PetBirthDatePart.YEAR -> R.string.date_part_year
    PetBirthDatePart.MONTH -> R.string.date_part_month
    PetBirthDatePart.DAY -> R.string.date_part_day
}
