package com.palixander.scalesync

import com.palixander.scalesync.data.ExternalSyncDestination
import com.palixander.scalesync.core.BodyComposition
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.data.MeasurementEntity
import com.palixander.scalesync.data.MeasurementMutationResult
import com.palixander.scalesync.domain.ExternalSyncPolicy
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.measurements.MeasurementDeleteConfirmation
import com.palixander.scalesync.measurements.MeasurementEditorDraft
import com.palixander.scalesync.measurements.MeasurementEditorOrigin
import com.palixander.scalesync.measurements.MeasurementEditorState
import com.palixander.scalesync.measurements.MeasurementUiType
import com.palixander.scalesync.measurements.MeasurementUiValues
import com.palixander.scalesync.measurements.MeasurementsDestination
import com.palixander.scalesync.measurements.MeasurementsNavigationState
import com.palixander.scalesync.measurements.buildHomeKgChartUiState
import com.palixander.scalesync.ui.accounts.AccountSelectorUiState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class MeasurementsViewModelTest {
    @Test
    fun manualOriginSurvivesEditedLocalOnlyPresentation() {
        for (origin in com.palixander.scalesync.domain.MeasurementOrigin.entries) {
            val item = measurement("origin").copy(
                origin = origin,
                measurementType = com.palixander.scalesync.data.MeasurementType.WEIGHT_ONLY,
                weightKg = 4.125,
                externalSyncPolicy = "USER_LOCAL",
            ).toMeasurementUiItem(false)
            assertEquals(origin, item.origin)
            assertTrue(item.isManuallyEdited)
            assertTrue(item.isWeightOnly)
            assertEquals(4.125, item.values.weightKg, 0.0)
        }
    }

    @Test
    fun delayedOldHeavyPresentationCannotAppearUnderNewAccountSelector() = runBlocking {
        val accountA = account("account-a")
        val accountB = account("account-b")
        val selectionA = measurementSelection(accountA, epoch = 0L)
        val selectionB = measurementSelection(accountB, epoch = 1L)
        val itemA = buildMeasurementPresentationItems(
            AccountMeasurementPresentationSource(finalized = listOf(measurement("measurement-a"))),
            Instant.EPOCH,
        ) { _, _ -> null }.single()
        val itemB = buildMeasurementPresentationItems(
            AccountMeasurementPresentationSource(finalized = listOf(measurement("measurement-b"))),
            Instant.EPOCH,
        ) { _, _ -> null }.single()
        val presentationA = measurementPresentation(selectionA, itemA)
        val presentationB = measurementPresentation(selectionB, itemB)
        val firstCombined = CompletableDeferred<Unit>()
        val selectionBPublished = CompletableDeferred<Unit>()

        val heavy = flow {
            emit(presentationA)
            selectionBPublished.await()
            emit(presentationA) // Non-cooperative old calculation finishes late.
            emit(presentationB)
        }
        val lightweight = flow {
            emit(measurementLoad(selectionA, accountA))
            firstCombined.await()
            emit(measurementLoad(selectionB, accountB))
            selectionBPublished.complete(Unit)
        }
        val states = mutableListOf<MeasurementsPresentation>()

        mergeMeasurementPresentationUpdates(
            presentations = heavy,
            measurements = lightweight,
            now = { Instant.EPOCH },
            preliminaryComposition = { _, _ -> null },
        ).take(4).collect { state ->
            states += state
            if (states.size == 1) firstCombined.complete(Unit)
        }

        assertEquals(listOf("measurement-a"), states.first().items.map { it.id })
        assertTrue(states.drop(1).filter { it.accountSelection == selectionB }.all { state ->
            state.items.none { it.id == "measurement-a" }
        })
        assertTrue(states[1].loadState is AccountScopedLoad.Loading)
        assertTrue(states[1].items.isEmpty())
        assertEquals(null, states[1].summary)
        assertTrue(states[1].homeKgChart.series.all { it.points.isEmpty() })
        assertEquals(listOf("measurement-b"), states.last().items.map { it.id })
    }

    @Test
    fun newAccountSnapshotPreservesHistoryAndAtomicallyClearsOldEditorAndDelete() {
        val oldAccount = AccountId("account-a")
        val editor = MeasurementEditorState(
            measurementId = "old-measurement",
            measuredAtEpochSecond = 1L,
            draft = MeasurementEditorDraft.fromWeight(70.0),
            type = MeasurementUiType.WEIGHT_ONLY,
        )
        val stale = MeasurementsInteractionState(
            selection = AccountSelection(oldAccount),
            navigation = MeasurementsNavigationState()
                .showHistory()
                .showEditor(MeasurementEditorOrigin.HISTORY),
            editor = editor,
            deleteConfirmation = MeasurementDeleteConfirmation(
                measurementId = "old-measurement",
                measuredAtEpochSecond = 1L,
                weightKg = 70.0,
            ),
        )

        val normalized = stale.normalizedFor(AccountSelection(AccountId("account-b"), 1L))

        assertEquals(AccountId("account-b"), normalized.accountId)
        assertEquals(MeasurementsDestination.HISTORY, normalized.navigation.destination)
        assertEquals(null, normalized.editor)
        assertEquals(null, normalized.deleteConfirmation)
    }

    @Test
    fun staleDeleteOperationCannotAttachConfirmationToNewAccount() {
        val confirmation = MeasurementDeleteConfirmation(
            measurementId = "old-measurement",
            measuredAtEpochSecond = 1L,
            weightKg = 70.0,
        )
        val current = MeasurementsInteractionState(
            selection = AccountSelection(AccountId("account-b"), 1L),
            navigation = MeasurementsNavigationState().showHistory(),
        )

        val rejected = current.withDeleteConfirmation(
            ownerSelection = AccountSelection(AccountId("account-a")),
            confirmation = confirmation,
        )

        assertEquals(current, rejected)
        assertEquals(null, rejected.deleteConfirmation)
    }

    @Test
    fun delayedSaveFromOldAccountCannotCloseReturnedAccountsNewEditorOrEmitEffect() {
        val accountA = AccountId("account-a")
        val selection = AccountSelection(accountA)
        val oldOperation = MeasurementOperationToken(selection, "same-id", 1L)
        val newOperation = MeasurementOperationToken(selection, "same-id", 2L)
        val newEditor = editor("same-id").copy(isSaving = true)
        val state = MutableStateFlow(
            MeasurementsInteractionState(
                selection = selection,
                navigation = MeasurementsNavigationState()
                    .showHistory()
                    .showEditor(MeasurementEditorOrigin.HISTORY),
                editor = newEditor,
                saveOperation = newOperation,
            ),
        )

        val accepted = state.acceptOperation(oldOperation, selection) {
            it.afterSaveCompletion(MeasurementMutationResult.Success)
        }

        assertFalse(accepted)
        assertEquals(newEditor, state.value.editor)
        assertEquals(newOperation, state.value.saveOperation)
        assertEquals(MeasurementsDestination.EDITOR, state.value.navigation.destination)
    }

    @Test
    fun delayedDeleteRequestAfterAccountSwitchCannotOpenDialogOrEmitEffect() {
        val accountA = AccountId("account-a")
        val selectionA = AccountSelection(accountA)
        val selectionB = AccountSelection(AccountId("account-b"), 1L)
        val oldOperation = MeasurementOperationToken(selectionA, "same-id", 1L)
        val state = MutableStateFlow(
            MeasurementsInteractionState(
                selection = selectionA,
                deleteRequestOperation = oldOperation,
            ).normalizedFor(selectionB),
        )

        val accepted = state.acceptOperation(oldOperation, selectionB) {
            it.copy(
                deleteConfirmation = confirmation("same-id"),
                deleteRequestOperation = null,
            )
        }

        assertFalse(accepted)
        assertEquals(AccountId("account-b"), state.value.accountId)
        assertEquals(null, state.value.deleteConfirmation)
    }

    @Test
    fun delayedDeleteAfterReturnCannotClearNewConfirmationForSameMeasurement() {
        val accountA = AccountId("account-a")
        val selection = AccountSelection(accountA)
        val oldOperation = MeasurementOperationToken(selection, "same-id", 1L)
        val newOperation = MeasurementOperationToken(selection, "same-id", 2L)
        val newConfirmation = confirmation("same-id").copy(isDeleting = true)
        val state = MutableStateFlow(
            MeasurementsInteractionState(
                selection = selection,
                deleteConfirmation = newConfirmation,
                deleteOperation = newOperation,
            ),
        )

        val accepted = state.acceptOperation(oldOperation, selection) {
            it.copy(deleteConfirmation = null, deleteOperation = null)
        }

        assertFalse(accepted)
        assertEquals(newConfirmation, state.value.deleteConfirmation)
        assertEquals(newOperation, state.value.deleteOperation)
    }

    @Test
    fun rawOldOperationIsNormalizedAndRejectedByAuthoritativeAccountWithoutObserverCallback() {
        val accountA = AccountId("account-a")
        val accountB = AccountId("account-b")
        val selectionA = AccountSelection(accountA)
        val selectionB = AccountSelection(accountB, 1L)
        val operation = MeasurementOperationToken(selectionA, "same-id", 1L)
        val editor = editor("same-id").copy(isSaving = true)
        val state = MutableStateFlow(
            MeasurementsInteractionState(
                selection = selectionA,
                navigation = MeasurementsNavigationState()
                    .showHistory()
                    .showEditor(MeasurementEditorOrigin.HISTORY),
                editor = editor,
                saveOperation = operation,
            ),
        )
        var effectApplied = false

        val accepted = state.acceptOperation(operation, selectionB) {
            effectApplied = true
            it.afterSaveCompletion(MeasurementMutationResult.Success)
        }

        assertFalse(accepted)
        assertFalse(effectApplied)
        assertEquals(accountB, state.value.accountId)
        assertEquals(null, state.value.editor)
        assertEquals(null, state.value.saveOperation)
        assertEquals(MeasurementsDestination.HISTORY, state.value.navigation.destination)
    }

    @Test
    fun synchronousAccountPublicationInvalidatesOldTokenAcrossReturnToSameAccount() {
        val accountA = AccountId("account-a")
        val accountB = AccountId("account-b")
        val selectionA = AccountSelection(accountA)
        val operation = MeasurementOperationToken(selectionA, "same-id", 1L)
        val state = MutableStateFlow(
            MeasurementsInteractionState(
                selection = selectionA,
                editor = editor("same-id").copy(isSaving = true),
                saveOperation = operation,
            ),
        )
        val coordinator = AccountSelectionCoordinator()
        coordinator.select(accountA)

        state.value = state.value.normalizedFor(coordinator.select(accountB))
        assertEquals(accountB, state.value.accountId)
        assertEquals(accountB, coordinator.selection.value.accountId)
        state.value = state.value.normalizedFor(coordinator.select(accountA))

        assertEquals(accountA, state.value.accountId)
        assertEquals(accountA, coordinator.selection.value.accountId)
        assertEquals(null, state.value.editor)
        assertEquals(null, state.value.saveOperation)
        assertFalse(state.acceptOperation(operation, coordinator.selection.value) { it })
    }

    @Test
    fun latestDeleteRequestPreservesConfirmationData() {
        val value = measurement(id = "latest")
        assertEquals(
            MeasurementDeleteRequest.Confirm(
                confirmation = com.palixander.scalesync.measurements.MeasurementDeleteConfirmation(
                    measurementId = value.id,
                    measuredAtEpochSecond = value.measuredAtEpochSecond,
                    weightKg = value.weightKg,
                ),
            ),
            measurementDeleteRequest(
                id = "latest",
                measurements = listOf(value),
            ),
        )
    }

    @Test
    fun ordinaryDeleteRequestPreservesConfirmationData() {
        val value = measurement(id = "previous")

        assertEquals(
            MeasurementDeleteRequest.Confirm(
                confirmation = com.palixander.scalesync.measurements.MeasurementDeleteConfirmation(
                    measurementId = value.id,
                    measuredAtEpochSecond = value.measuredAtEpochSecond,
                    weightKg = value.weightKg,
                ),
            ),
            measurementDeleteRequest(
                id = value.id,
                measurements = listOf(value),
            ),
        )
    }

    @Test
    fun missingDeleteRequestDoesNotCreateConfirmation() {
        assertEquals(
            MeasurementDeleteRequest.NotFound,
            measurementDeleteRequest(
                id = "missing",
                measurements = listOf(measurement(id = "existing")),
            ),
        )
    }

    @Test
    fun successfulDeleteUsesRequiredSnackbarMessage() {
        assertEquals(
            "Локальное измерение удалено",
            measurementDeleteResultMessage(MeasurementMutationResult.Success),
        )
    }

    @Test
    fun historyFlagsDoNotInferManualEditFromLocalOnlySyncStatus() {
        val accountLocal = measurement(id = "account-local").copy(
            externalSyncPolicy = ExternalSyncPolicy.ACCOUNT_LOCAL.name,
            healthConnectStatus = "LOCAL_ONLY",
        ).toMeasurementUiItem(false)

        val userLocal = measurement(id = "user-local").copy(
            externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
            healthConnectStatus = "SYNCED",
        ).toMeasurementUiItem(false)

        assertFalse(accountLocal.isManuallyEdited)
        assertTrue(accountLocal.isLocalOnly)
        assertTrue(userLocal.isManuallyEdited)
        assertFalse(userLocal.isLocalOnly)
    }

    @Test
    fun historyMismatchMapsSnapshotDivergenceIndependentlyFromManualEdit() {
        val original = measurement(id = "mismatch")
        val syncedSnapshot = original.currentCalculatedValuesSnapshot(
            ExternalSyncDestination.HEALTH_CONNECT,
        )!!.encode()

        val item = original.copy(
            externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
            healthConnectSyncedCalculatedValues = syncedSnapshot,
            bodyFatPercent = original.bodyFatPercent!! + 1.0,
        ).toMeasurementUiItem(false)

        assertTrue(item.isManuallyEdited)
        assertTrue(item.hasProfileSyncMismatch)
        assertEquals(
            com.palixander.scalesync.measurements.MeasurementSyncPresentationState.PENDING,
            item.sync.state,
        )
    }

    @Test
    fun finalizedProjectionKeepsPendingPresentationKeyAndUsesRowIdForMutations() {
        val item = measurement(id = "measurement-row").copy(
            sourcePendingId = "pending-stable",
        ).toMeasurementUiItem(false)

        assertEquals("pending-stable", item.presentationKey)
        assertEquals(PendingMeasurementId("pending-stable"), item.sourcePendingId)
        assertEquals("measurement-row", item.finalMeasurementId)
        assertEquals("measurement-row", item.mutationId)
        assertFalse(item.isPreliminary)
        assertTrue(item.canEdit)
        assertTrue(item.canDelete)
        assertTrue(item.canSync)
    }

    @Test
    fun immediatePreliminaryUsesStableKeyAndInMemoryFullComposition() {
        val pending = pending(id = "pending-live", hasImpedance = true)
        val account = account("account-a")

        val item = buildMeasurementPresentationItems(
            source = AccountMeasurementPresentationSource(
                preliminary = listOf(pending),
                account = account,
            ),
            now = pending.enqueuedAt,
            preliminaryComposition = { value, _ -> composition(value) },
        ).single()

        assertEquals("pending-live", item.presentationKey)
        assertEquals(PendingMeasurementId("pending-live"), item.sourcePendingId)
        assertTrue(item.isPreliminary)
        assertFalse(item.isReadyForDecision)
        assertFalse(item.canEdit)
        assertFalse(item.canDelete)
        assertFalse(item.canRetry)
        assertFalse(item.canSync)
        assertEquals(22.9, item.values.bmi!!, 0.0)
        assertEquals(com.palixander.scalesync.measurements.MeasurementUiType.FULL, item.type)
    }

    @Test
    fun weightOnlyPreliminaryUpgradesInPlaceWhenFullAggregateArrives() {
        val account = account("account-a")
        val weightOnly = pending(id = "pending-upgrade", hasImpedance = false)
        val full = weightOnly.copy(hasImpedance = true, impedanceOhm = 500)

        fun project(value: PendingMeasurement) = buildMeasurementPresentationItems(
            source = AccountMeasurementPresentationSource(listOf(), listOf(value), account),
            now = value.enqueuedAt,
            preliminaryComposition = { pending, _ ->
                pending.takeIf { it.hasImpedance }?.let(::composition)
            },
        ).single()

        val before = project(weightOnly)
        val after = project(full)
        assertEquals(before.presentationKey, after.presentationKey)
        assertTrue(before.isWeightOnly)
        assertFalse(after.isWeightOnly)
        assertEquals(22.9, after.values.bmi!!, 0.0)
    }

    @Test
    fun finalizedReplacementSuppressesTemporaryPendingDuplicateAndKeepsOrdering() {
        val pending = pending(id = "pending-replaced", hasImpedance = true)
        val final = measurement(id = "final-row").copy(
            measuredAtEpochSecond = pending.measuredAt.epochSecond,
            sourcePendingId = pending.id.value,
        )
        val older = measurement(id = "older").copy(measuredAtEpochSecond = 1L)

        val items = buildMeasurementPresentationItems(
            source = AccountMeasurementPresentationSource(
                finalized = listOf(older, final),
                preliminary = listOf(pending),
                account = account("account-a"),
            ),
            now = pending.finalizeAfter,
            preliminaryComposition = { value, _ -> composition(value) },
        )

        assertEquals(listOf("pending-replaced", "older"), items.map { it.presentationKey })
        assertEquals("final-row", items.first().mutationId)
        assertFalse(items.first().isPreliminary)
    }

    @Test
    fun accountScopedSourcesMovePreliminaryWithoutLeakingAcrossSelection() {
        val pending = pending(id = "pending-move", hasImpedance = true)
        val first = buildMeasurementPresentationItems(
            AccountMeasurementPresentationSource(
                preliminary = listOf(pending),
                account = account("account-a"),
            ),
            pending.enqueuedAt,
        ) { value, _ -> composition(value) }
        val second = buildMeasurementPresentationItems(
            AccountMeasurementPresentationSource(account = account("account-b")),
            pending.enqueuedAt,
        ) { value, _ -> composition(value) }

        assertEquals(listOf("pending-move"), first.map { it.presentationKey })
        assertTrue(second.isEmpty())
    }

    @Test
    fun preliminaryUpdatesDoNotInvalidateFinalizedPresentationInput() = runBlocking {
        val account = account("account-a")
        val initialSource = AccountMeasurementPresentationSource(
            finalized = listOf(measurement(id = "final-existing")),
            account = account,
        )
        val initial = AccountSelectionScopedLoad(
            selection = "account-a",
            load = AccountScopedLoad.Loaded(initialSource),
        )
        val weightOnly = pending(id = "pending-live", hasImpedance = false)
        val full = weightOnly.copy(hasImpedance = true, impedanceOhm = 500)
        val finalized = measurement(id = "final-live").copy(sourcePendingId = weightOnly.id.value)

        val inputs = flowOf(
            initial,
            initial.copy(
                load = AccountScopedLoad.Loaded(
                    initialSource.copy(
                        preliminary = listOf(weightOnly),
                    ),
                ),
            ),
            initial.copy(
                load = AccountScopedLoad.Loaded(
                    initialSource.copy(
                        preliminary = listOf(full),
                    ),
                ),
            ),
            initial.copy(
                load = AccountScopedLoad.Loaded(
                    initialSource.copy(
                        finalized = listOf(finalized, measurement(id = "final-existing")),
                    ),
                ),
            ),
        ).withoutPreliminaryUpdates().toList()

        assertEquals(2, inputs.size)
        assertEquals(
            listOf("final-existing"),
            (inputs.first().load as AccountScopedLoad.Loaded).value.finalized.map { it.id },
        )
        assertEquals(
            listOf("final-live", "final-existing"),
            (inputs.last().load as AccountScopedLoad.Loaded).value.finalized.map { it.id },
        )
    }

    private fun measurementSelection(account: Account, epoch: Long) = MeasurementAccountSelection(
        selection = AccountSelection(account.id, epoch),
        selector = AccountSelectorUiState(
            accounts = listOf(account),
            selectedAccountId = account.id,
            primaryAccountId = account.id,
        ),
    )

    private fun measurementLoad(selection: MeasurementAccountSelection, account: Account) =
        AccountSelectionScopedLoad(
            selection = selection,
            load = AccountScopedLoad.Loaded(AccountMeasurementPresentationSource(account = account)),
        )

    private fun measurementPresentation(
        selection: MeasurementAccountSelection,
        item: com.palixander.scalesync.measurements.MeasurementUiItem,
    ) = MeasurementsPresentation(
        loadState = AccountScopedLoad.Loaded(AccountMeasurementPresentationSource()),
        accountSelection = selection,
        items = listOf(item),
        summary = null,
        homeKgChart = buildHomeKgChartUiState(listOf(item)),
    )

    @Test
    fun preliminaryToFinalizedTransitionKeepsPresentationIdentityWithoutDuplicate() {
        val pending = pending(id = "pending-transition", hasImpedance = true)
        val preliminaryItems = mergePreliminaryMeasurementPresentationItems(
            finalizedItems = emptyList(),
            preliminary = listOf(pending),
            account = account("account-a"),
            now = pending.enqueuedAt,
            preliminaryComposition = { value, _ -> composition(value) },
        )
        val finalizedItem = measurement(id = "final-transition").copy(
            sourcePendingId = pending.id.value,
        ).toMeasurementUiItem(false)
        val transitionedItems = mergePreliminaryMeasurementPresentationItems(
            finalizedItems = listOf(finalizedItem),
            preliminary = listOf(pending),
            account = account("account-a"),
            now = pending.finalizeAfter,
            preliminaryComposition = { value, _ -> composition(value) },
        )

        assertEquals("pending-transition", preliminaryItems.single().presentationKey)
        assertTrue(preliminaryItems.single().isPreliminary)
        assertEquals("pending-transition", transitionedItems.single().presentationKey)
        assertFalse(transitionedItems.single().isPreliminary)
        assertEquals("final-transition", transitionedItems.single().mutationId)
    }

    @Test
    fun unassignedQueueImmediatelyIncludesNullProvisionalAndExcludesAssignedPreliminary() {
        val unassigned = pending(id = "pending-unassigned", hasImpedance = false)
        val assigned = pending(id = "pending-assigned", hasImpedance = true).copy(
            provisionalAccountId = AccountId("account-a"),
        )

        assertEquals(
            listOf(unassigned),
            unassignedPendingMeasurements(listOf(assigned, unassigned)),
        )
    }

    @Test
    fun referenceContextUsesSavedHeightAndCurrentOwnerSexAndBirthDate() {
        val owner = account("owner").copy(
            profile = AccountProfile.Complete(
                heightCm = 190.0,
                birthDate = LocalDate.of(1990, 6, 12),
                sex = Sex.FEMALE,
            ),
        )
        val result = measurementReferenceContext(
            values = measurementValues(),
            measuredAt = Instant.parse("2026-08-15T12:42:00Z"),
            ratingHeightCm = 168.5,
            account = owner,
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(168.5, result.context.heightCm)
        assertEquals(Sex.FEMALE, result.context.sex)
        assertEquals(LocalDate.of(1990, 6, 12), result.context.birthDate)
        assertEquals(36, result.age)
    }

    @Test
    fun referenceContextTreatsBirthDateAfterHistoricalMeasurementAsMissing() {
        val result = measurementReferenceContext(
            values = measurementValues(),
            measuredAt = Instant.parse("1980-08-15T12:42:00Z"),
            ratingHeightCm = 168.5,
            account = account("owner"),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(null, result.context.birthDate)
        assertEquals(null, result.age)
        assertEquals(168.5, result.context.heightCm)
    }
}

private fun editor(id: String) = MeasurementEditorState(
    measurementId = id,
    measuredAtEpochSecond = 1L,
    draft = MeasurementEditorDraft.fromWeight(70.0),
    type = MeasurementUiType.WEIGHT_ONLY,
)

private fun confirmation(id: String) = MeasurementDeleteConfirmation(
    measurementId = id,
    measuredAtEpochSecond = 1L,
    weightKg = 70.0,
)

private fun account(id: String) = Account(
    id = AccountId(id),
    displayName = id,
    profile = AccountProfile.Complete(175.0, LocalDate.of(1990, 1, 1), Sex.MALE),
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
)

private fun pending(id: String, hasImpedance: Boolean) = PendingMeasurement(
    id = PendingMeasurementId(id),
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAt = Instant.ofEpochSecond(100),
    weightKg = 70.0,
    impedanceOhm = if (hasImpedance) 500 else 0,
    isStable = true,
    hasImpedance = hasImpedance,
    rawPayload = byteArrayOf(1),
    deduplicationHash = "hash-$id",
    enqueuedAt = Instant.ofEpochSecond(101),
)

private fun composition(pending: PendingMeasurement) = BodyComposition(
    measurementId = "preview-only",
    deviceAddress = pending.deviceAddress,
    measuredAt = pending.measuredAt,
    weightKg = pending.weightKg,
    impedanceOhm = pending.impedanceOhm,
    bmi = 22.9,
    bodyFatPercent = 20.0,
    bodyFatMassKg = 14.0,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 40.0,
    skeletalMuscleMassKg = 20.0,
    boneMassKg = 3.0,
    proteinPercent = 18.0,
    proteinMassKg = 12.6,
    visceralFatLevel = 7.0,
    basalMetabolicRateKcal = 1_500.0,
    metabolicAge = 35,
    leanBodyMassKg = 56.0,
    algorithmVersion = "preview",
)

private fun measurement(id: String) = MeasurementEntity(
    id = id,
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAtEpochSecond = 1L,
    rawPayloadHex = "010203",
    weightKg = 70.0,
    impedanceOhm = 500,
    bmi = 22.9,
    bodyFatPercent = 20.0,
    bodyFatMassKg = 14.0,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 40.0,
    skeletalMuscleMassKg = 20.0,
    boneMassKg = 3.0,
    proteinPercent = 18.0,
    proteinMassKg = 12.6,
    visceralFatLevel = 7.0,
    basalMetabolicRateKcal = 1_500.0,
    metabolicAge = 35,
    leanBodyMassKg = 56.0,
    algorithmVersion = "test-v1",
)

private fun measurementValues() = MeasurementUiValues(
    weightKg = 70.0,
    impedanceOhm = 500,
    bmi = 22.9,
    bodyFatPercent = 20.0,
    bodyFatMassKg = 14.0,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 40.0,
    skeletalMuscleMassKg = 20.0,
    boneMassKg = 3.0,
    proteinPercent = 18.0,
    proteinMassKg = 12.6,
    visceralFatLevel = 7.0,
    basalMetabolicRateKcal = 1_500.0,
    metabolicAge = 35,
    leanBodyMassKg = 56.0,
)
