package com.palixander.scalesync.ui.routing

import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.domain.RoutingCandidate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ResolverUiContractsTest {
    @Test
    fun `candidate accounts lead by difference and every other account remains selectable`() {
        val first = account("first", "Первый")
        val second = account("second", "Второй")
        val any = account("any", "Любой")
        val options = buildResolverAccountOptions(
            accounts = listOf(first, second, any),
            primaryAccountId = first.id,
            candidates = listOf(
                RoutingCandidate(second.id, differenceKg = 2.0, medianWeightKg = 72.0, stableOrder = 1),
                RoutingCandidate(first.id, differenceKg = 0.5, medianWeightKg = 69.5, stableOrder = 0),
            ),
        )

        assertEquals(listOf(first.id, second.id, any.id), options.map { it.accountId })
        assertTrue(options[0].isCandidate)
        assertTrue(options[1].isCandidate)
        assertFalse(options[2].isCandidate)
    }

    @Test
    fun `resolver options deduplicate noisy inputs without losing candidate priority`() {
        val candidate = account("candidate", "Первый")
        val any = account("any", "Любой")
        val options = buildResolverAccountOptions(
            accounts = listOf(candidate, any, any),
            primaryAccountId = candidate.id,
            candidates = listOf(
                RoutingCandidate(candidate.id, 2.0, 72.0, 1),
                RoutingCandidate(candidate.id, 0.5, 69.5, 0),
                RoutingCandidate(AccountId("removed"), 0.1, 70.1, 2),
            ),
        )

        assertEquals(listOf(candidate.id, any.id), options.map(ResolverAccountOption::accountId))
        assertEquals(0.5, options.first().differenceKg!!, 0.0)
        assertEquals(2, options.distinctBy(ResolverAccountOption::accountId).size)
    }

    @Test
    fun `resolver option rejects partial or invalid candidate metadata`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResolverAccountOption(
                accountId = AccountId("candidate"),
                displayName = "Кандидат",
                isPrimary = false,
                differenceKg = 0.5,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResolverAccountOption(
                accountId = AccountId("candidate"),
                displayName = "Кандидат",
                isPrimary = false,
                differenceKg = -0.5,
                medianWeightKg = 70.0,
            )
        }
    }

    @Test
    fun `pending queue is fifo and later keeps its head durable`() {
        val lateId = pending("late", "2026-08-15T10:01:00Z")
        val tieSecond = pending("b", "2026-08-15T10:00:00Z")
        val tieFirst = pending("a", "2026-08-15T10:00:00Z")
        val initial = ResolverQueueState.from(
            listOf(lateId, tieSecond, tieFirst),
            selectedPendingId = tieFirst.id,
        )
        assertEquals(tieFirst.id, initial.current?.id)

        val later = reduceResolverQueue(initial, ResolverQueueAction.LaterRequested)
        assertFalse(later.isResolverVisible)
        assertEquals(initial.pending, later.pending)
        assertEquals(tieFirst.id, later.current?.id)
    }

    @Test
    fun `selected non-head pending may be processed without disturbing fifo order`() {
        val first = pending("a", "2026-08-15T10:00:00Z")
        val second = pending("b", "2026-08-15T10:01:00Z")
        val third = pending("c", "2026-08-15T10:02:00Z")
        val initial = ResolverQueueState.from(
            listOf(third, second, first),
            selectedPendingId = second.id,
        )

        assertTrue(
            isActivePendingResolverTarget(
                pending = initial.pending,
                selectedPendingId = initial.selectedPendingId,
                requestedPendingId = second.id,
            ),
        )
        assertFalse(
            isActivePendingResolverTarget(
                pending = initial.pending,
                selectedPendingId = initial.selectedPendingId,
                requestedPendingId = first.id,
            ),
        )
        val processed = reduceResolverQueue(
            initial,
            ResolverQueueAction.PendingFinalized(second.id),
        )

        assertEquals(listOf(first.id, third.id), processed.pending.map(PendingMeasurement::id))
        assertEquals(first.id, processed.current?.id)
        assertNull(processed.selectedPendingId)
        assertFalse(processed.isResolverVisible)
    }

    @Test
    fun `queue resolver completion returns to queue while external completion preserves screen`() {
        val pendingId = PendingMeasurementId("selected")
        val queueCompletion = PendingResolverSession(
            pendingId = pendingId,
            source = PendingResolverSource.PENDING_QUEUE,
        ).completionFor(pendingId)
        val externalCompletion = PendingResolverSession(
            pendingId = pendingId,
            source = PendingResolverSource.EXTERNAL,
        ).completionFor(pendingId)

        assertEquals(
            PendingResolverReturnDestination.PENDING_QUEUE,
            queueCompletion?.returnDestination,
        )
        assertEquals(
            PendingResolverReturnDestination.PRESERVE_CURRENT,
            externalCompletion?.returnDestination,
        )
    }

    @Test
    fun `terminal completion rejects callback from a stale resolver`() {
        val session = PendingResolverSession(
            pendingId = PendingMeasurementId("selected"),
            source = PendingResolverSource.PENDING_QUEUE,
        )

        assertNull(session.completionFor(PendingMeasurementId("stale")))
    }

    @Test
    fun `external resolver delete addresses displayed pending and preserves current screen`() {
        val fifoHead = pending("a", "2026-08-15T10:00:00Z")
        val displayed = pending("b", "2026-08-15T10:01:00Z")
        val session = PendingResolverSession(
            pendingId = displayed.id,
            source = PendingResolverSource.EXTERNAL,
        )

        val completion = session.activeCompletionFor(
            pending = listOf(fifoHead, displayed),
            requestedPendingId = displayed.id,
        )

        assertEquals(displayed.id, completion?.pendingId)
        assertEquals(
            PendingResolverReturnDestination.PRESERVE_CURRENT,
            completion?.returnDestination,
        )
        assertNull(
            session.activeCompletionFor(
                pending = listOf(fifoHead, displayed),
                requestedPendingId = fifoHead.id,
            ),
        )
        assertNull(
            session.activeCompletionFor(
                pending = listOf(fifoHead),
                requestedPendingId = displayed.id,
            ),
        )
    }

    @Test
    fun `queue resolver delete returns to queue for the addressed pending`() {
        val selected = pending("selected", "2026-08-15T10:00:00Z")
        val session = PendingResolverSession(
            pendingId = selected.id,
            source = PendingResolverSource.PENDING_QUEUE,
        )

        val completion = session.activeCompletionFor(listOf(selected), selected.id)

        assertEquals(selected.id, completion?.pendingId)
        assertEquals(
            PendingResolverReturnDestination.PENDING_QUEUE,
            completion?.returnDestination,
        )
    }

    @Test
    fun `unsaved preview close consumes only its exact queue item and is one shot`() {
        val fifoHead = pending("head", "2026-08-15T10:00:00Z")
        val previewed = pending("previewed", "2026-08-15T10:01:00Z")
        val coordinator = UnsavedPreviewSessionCoordinator()
        coordinator.show(
            state = UnsavedMeasurementPreviewState(previewed),
            resolverSession = PendingResolverSession(
                pendingId = previewed.id,
                source = PendingResolverSource.PENDING_QUEUE,
            ),
        )

        assertNull(coordinator.takeClose(fifoHead.id))
        assertEquals(previewed.id, coordinator.active.value?.state?.pending?.id)

        val completion = coordinator.takeClose(previewed.id)

        assertEquals(previewed.id, completion?.pendingId)
        assertEquals(
            PendingResolverReturnDestination.PENDING_QUEUE,
            completion?.returnDestination,
        )
        assertNull(coordinator.active.value)
        assertNull(coordinator.takeClose(previewed.id))
    }

    @Test
    fun `external unsaved preview close preserves its source destination`() {
        val previewed = pending("previewed", "2026-08-15T10:00:00Z")
        val coordinator = UnsavedPreviewSessionCoordinator()
        coordinator.show(
            state = UnsavedMeasurementPreviewState(previewed),
            resolverSession = PendingResolverSession(
                pendingId = previewed.id,
                source = PendingResolverSource.EXTERNAL,
            ),
        )

        val completion = coordinator.takeClose(previewed.id)

        assertEquals(previewed.id, completion?.pendingId)
        assertEquals(
            PendingResolverReturnDestination.PRESERVE_CURRENT,
            completion?.returnDestination,
        )
    }

    @Test
    fun `discard completion closes selected item without opening the fifo head`() {
        val first = pending("a", "2026-08-15T10:00:00Z")
        val selected = pending("b", "2026-08-15T10:01:00Z")
        val initial = ResolverQueueState.from(
            pending = listOf(first, selected),
            selectedPendingId = selected.id,
        )

        val discarded = reduceResolverQueue(
            initial,
            ResolverQueueAction.PendingDiscarded(selected.id),
        )

        assertEquals(listOf(first.id), discarded.pending.map(PendingMeasurement::id))
        assertNull(discarded.selectedPendingId)
        assertFalse(discarded.isResolverVisible)
    }

    @Test
    fun `notification denial exposes foreground fallback without opening resolver`() {
        val state = ResolverQueueState.from(
            listOf(pending("a", "2026-08-15T10:00:00Z")),
            notificationPermissionGranted = false,
        )

        assertTrue(state.showForegroundFallback)
        assertFalse(state.isResolverVisible)
        assertFalse(state.copy(selectedPendingId = state.pending.first().id).showForegroundFallback)
    }

    @Test
    fun `cold notification launch waits for durable snapshot and selects fifo head`() = runBlocking {
        val durablePending = pending("cold", "2026-08-15T10:00:00Z")
        val laterPending = pending("later", "2026-08-15T10:01:00Z")

        assertEquals(
            durablePending.id,
            oldestPendingResolverTarget(
                emptyList(),
                flowOf(listOf(laterPending, durablePending)),
            ),
        )
        assertNull(oldestPendingResolverTarget(emptyList(), flowOf(emptyList())))
    }

    @Test
    fun `aggregating pending stays out of queue and cold notification routing`() = runBlocking {
        val now = Instant.parse("2026-08-15T10:00:00Z")
        val aggregating = pending("aggregating", "2026-08-15T09:59:59Z").copy(
            finalizeAfter = now.plusSeconds(9),
        )
        val awaiting = pending("awaiting", "2026-08-15T09:58:00Z").copy(
            finalizeAfter = now,
        )

        val queue = ResolverQueueState.from(listOf(aggregating, awaiting), now = now)

        assertEquals(listOf(awaiting.id), queue.pending.map(PendingMeasurement::id))
        assertEquals(
            awaiting.id,
            oldestPendingResolverTarget(
                observedPending = emptyList(),
                durablePendingSnapshots = flowOf(listOf(aggregating, awaiting)),
                now = now,
            ),
        )
    }

    @Test
    fun `already observed pending opens resolver without collecting another snapshot`() = runBlocking {
        val oldest = pending("oldest", "2026-08-15T10:00:00Z")
        val latest = pending("latest", "2026-08-15T10:01:00Z")

        assertEquals(
            oldest.id,
            oldestPendingResolverTarget(
                observedPending = listOf(latest, oldest),
                durablePendingSnapshots = flow { error("must not collect") },
            ),
        )
    }

    @Test
    fun `pending updates are re-sorted and preserve resolver visibility`() {
        val first = pending("a", "2026-08-15T10:00:00Z")
        val second = pending("b", "2026-08-15T10:01:00Z")
        val initial = ResolverQueueState.from(
            listOf(first),
            selectedPendingId = first.id,
        )

        val updated = reduceResolverQueue(
            initial,
            ResolverQueueAction.PendingChanged(listOf(second, first)),
        )

        assertEquals(listOf(first.id, second.id), updated.pending.map(PendingMeasurement::id))
        assertTrue(updated.isResolverVisible)
    }

    @Test
    fun `open resolver retains its snapshot across technical intermediate absence`() {
        val selected = pending("selected", "2026-08-15T10:00:00Z")
        val session = PendingResolverSession(
            pendingId = selected.id,
            source = PendingResolverSource.EXTERNAL,
            pendingSnapshot = selected,
        )

        val transient = pendingForResolverLifecycle(emptyList(), session)
        val queue = ResolverQueueState.from(transient, selectedPendingId = session.pendingId)

        assertEquals(listOf(selected), transient)
        assertEquals(selected, queue.selected)
        assertTrue(queue.isResolverVisible)
        assertTrue(pendingForResolverLifecycle(emptyList(), null).isEmpty())
    }

    @Test
    fun `durable pending update supersedes resolver snapshot`() {
        val selected = pending("selected", "2026-08-15T10:00:00Z")
        val enriched = selected.copy(impedanceOhm = 625)
        val session = PendingResolverSession(
            pendingId = selected.id,
            source = PendingResolverSource.PENDING_QUEUE,
            pendingSnapshot = selected,
        )

        val current = pendingForResolverLifecycle(listOf(enriched), session)

        assertEquals(listOf(enriched), current)
    }

    @Test
    fun `resolver lifecycle survives transient absence then accepts update and terminal removal`() {
        val selected = pending("selected", "2026-08-15T10:00:00Z")
        val session = PendingResolverSession(
            pendingId = selected.id,
            source = PendingResolverSource.EXTERNAL,
            pendingSnapshot = selected,
        )

        val transientQueue = ResolverQueueState.from(
            pendingForResolverLifecycle(emptyList(), session),
            selectedPendingId = session.pendingId,
        )
        val enriched = selected.copy(impedanceOhm = 625, hasImpedance = true)
        val updatedQueue = ResolverQueueState.from(
            pendingForResolverLifecycle(listOf(enriched), session),
            selectedPendingId = session.pendingId,
        )
        val terminalQueue = ResolverQueueState.from(
            pendingForResolverLifecycle(emptyList(), session = null),
            selectedPendingId = session.pendingId,
        )

        assertEquals(selected, transientQueue.selected)
        assertEquals(enriched, updatedQueue.selected)
        assertTrue(updatedQueue.isResolverVisible)
        assertTrue(terminalQueue.pending.isEmpty())
        assertFalse(terminalQueue.isResolverVisible)
    }

    @Test
    fun `durable removal clears stale resolver selection instead of opening fifo head`() {
        val first = pending("a", "2026-08-15T10:00:00Z")
        val selected = pending("b", "2026-08-15T10:01:00Z")
        val initial = ResolverQueueState.from(
            listOf(first, selected),
            selectedPendingId = selected.id,
        )

        val updated = reduceResolverQueue(
            initial,
            ResolverQueueAction.PendingChanged(listOf(first)),
        )

        assertEquals(first.id, updated.current?.id)
        assertNull(updated.selectedPendingId)
        assertFalse(updated.isResolverVisible)
        assertFalse(
            isActivePendingResolverTarget(
                pending = updated.pending,
                selectedPendingId = selected.id,
                requestedPendingId = selected.id,
            ),
        )
    }

    @Test
    fun `addressed open selects an existing pending item and rejects a stale id`() {
        val first = pending("a", "2026-08-15T10:00:00Z")
        val second = pending("b", "2026-08-15T10:01:00Z")
        val initial = ResolverQueueState.from(listOf(first, second))

        val selected = reduceResolverQueue(
            initial,
            ResolverQueueAction.OpenRequested(second.id),
        )

        assertEquals(second.id, selected.selectedPendingId)
        assertEquals(second, selected.selected)
        assertEquals(first, selected.current)
        assertEquals(
            second.id,
            reduceResolverQueue(
                selected,
                ResolverQueueAction.OpenRequested(PendingMeasurementId("missing")),
            ).selectedPendingId,
        )
    }

    @Test
    fun `unsaved preview calculates in memory and carries explicit no-persist contract`() {
        val pending = pending("preview", "2026-08-15T10:00:00Z")
        val draft = UnsavedPreviewProfileDraft(
            heightCm = "170,0",
            birthDate = LocalDate.of(1990, 1, 1),
            sex = Sex.FEMALE,
        )

        val result = calculateUnsavedPreview(
            pending = pending,
            draft = draft,
            zoneId = ZoneOffset.UTC,
        )

        assertNotNull(result)
        assertEquals(pending.weightKg, result!!.composition.weightKg, 0.0)
        assertFalse(result.isPersisted)
        assertFalse(result.canSyncExternally)
        assertEquals(16, result.readings.size)
        assertEquals(16, result.readings.map { it.metric }.toSet().size)
        assertEquals(result.readings.map { it.metric }.toSet(), result.interpretations.keys)
    }

    @Test
    fun `calculation failure preserves draft and pending then field change clears error for retry`() {
        val pending = pending("preview", "2026-08-15T10:00:00Z")
        val draft = UnsavedPreviewProfileDraft(
            heightCm = "170",
            birthDate = LocalDate.of(1990, 1, 1),
            sex = Sex.FEMALE,
        )
        val editing = UnsavedMeasurementPreviewState(
            pending = pending,
            step = UnsavedPreviewStep.PROFILE_EDITOR,
            profileDraft = draft,
        )
        val calculating = reduceUnsavedPreview(editing, UnsavedPreviewAction.CalculationStarted(41L))
        val failed = reduceUnsavedPreview(
            calculating,
            UnsavedPreviewAction.CalculationFailed(
                41L,
                UnsavedPreviewCalculationError.CALCULATION_FAILED,
            ),
        )

        assertEquals(UnsavedPreviewStep.PROFILE_EDITOR, failed.step)
        assertFalse(failed.isCalculating)
        assertEquals(pending, failed.pending)
        assertEquals(draft, failed.profileDraft)
        assertEquals(UnsavedPreviewCalculationError.CALCULATION_FAILED, failed.calculationError)

        val changed = reduceUnsavedPreview(
            failed,
            UnsavedPreviewAction.ProfileChanged(draft.copy(heightCm = "171")),
        )
        val retrying = reduceUnsavedPreview(changed, UnsavedPreviewAction.CalculationStarted(42L))

        assertNull(changed.calculationError)
        assertTrue(retrying.isCalculating)
        assertEquals(42L, retrying.calculationRequestId)
    }

    @Test
    fun `stale request and terminal from another preview session are ignored`() {
        val first = pending("first", "2026-08-15T10:00:00Z")
        val second = pending("second", "2026-08-15T10:01:00Z")
        val draft = UnsavedPreviewProfileDraft(
            heightCm = "170",
            birthDate = LocalDate.of(1990, 1, 1),
            sex = Sex.FEMALE,
        )
        val coordinator = UnsavedPreviewSessionCoordinator()
        coordinator.show(
            UnsavedMeasurementPreviewState(first, UnsavedPreviewStep.PROFILE_EDITOR, draft),
            PendingResolverSession(first.id, PendingResolverSource.PENDING_QUEUE),
        )
        val request = requireNotNull(coordinator.startCalculation(first.id, 1L, ZoneOffset.UTC))
        val firstResult = requireNotNull(calculateUnsavedPreview(first, request.profileDraft, ZoneOffset.UTC))
        coordinator.update(
            coordinator.active.value!!.state.copy(
                isCalculating = false,
                calculationRequestId = null,
                profileDraft = draft.copy(heightCm = "180"),
            ),
        )
        assertEquals(draft, coordinator.active.value?.state?.profileDraft)
        assertTrue(coordinator.active.value?.state?.isCalculating == true)
        assertFalse(coordinator.completeCalculation(first.id, 2L, firstResult))
        assertTrue(coordinator.active.value?.state?.isCalculating == true)

        coordinator.show(
            UnsavedMeasurementPreviewState(second, UnsavedPreviewStep.PROFILE_EDITOR, draft),
            PendingResolverSession(second.id, PendingResolverSource.PENDING_QUEUE),
        )

        assertFalse(coordinator.completeCalculation(first.id, 1L, firstResult))
        assertEquals(second.id, coordinator.active.value?.state?.pending?.id)
        assertEquals(UnsavedPreviewStep.PROFILE_EDITOR, coordinator.active.value?.state?.step)
    }

    @Test
    fun `one-shot profile and result disappear with their ViewModel memory owner`() {
        val pending = pending("preview", "2026-08-15T10:00:00Z")
        val owner = UnsavedPreviewMemoryState()
        owner.open(pending)
        val editing = reduceUnsavedPreview(
            requireNotNull(owner.value),
            UnsavedPreviewAction.EnterProfileRequested,
        ).copy(
            profileDraft = UnsavedPreviewProfileDraft(
                heightCm = "170",
                birthDate = LocalDate.of(1990, 1, 1),
                sex = Sex.FEMALE,
            ),
        )
        val calculating = reduceUnsavedPreview(editing, UnsavedPreviewAction.CalculationStarted(1L))
        val result = requireNotNull(
            calculateUnsavedPreview(pending, calculating.profileDraft, ZoneOffset.UTC),
        )
        owner.update(
            reduceUnsavedPreview(
                calculating,
                UnsavedPreviewAction.CalculationCompleted(1L, result),
            ),
        )

        assertEquals(UnsavedPreviewStep.RESULT, owner.value?.step)
        assertEquals(LocalDate.of(1990, 1, 1), owner.value?.profileDraft?.birthDate)
        assertNotNull(owner.value?.result)

        val recreatedOwner = UnsavedPreviewMemoryState()

        assertNull(recreatedOwner.value)
        assertEquals(0, UnsavedPreviewMemoryState::class.java.declaredConstructors.single().parameterCount)
    }

    @Test
    fun `preview memory owner never accepts another pending session state`() {
        val owner = UnsavedPreviewMemoryState()
        val current = pending("current", "2026-08-15T10:00:00Z")
        val other = pending("other", "2026-08-15T10:01:00Z")
        owner.open(current)

        owner.update(
            UnsavedMeasurementPreviewState(
                pending = other,
                step = UnsavedPreviewStep.PROFILE_EDITOR,
            ),
        )

        assertEquals(current.id, owner.value?.pending?.id)
        owner.retainPending(setOf(other.id))
        assertNull(owner.value)
    }

    @Test
    fun `unsaved preview rejects a profile dated after the measurement`() {
        val measurementDate = LocalDate.of(2024, 2, 29)
        val atMeasurementLimit = validateUnsavedPreviewProfile(
            draft = UnsavedPreviewProfileDraft(
                heightCm = "170",
                birthDate = measurementDate,
                sex = Sex.MALE,
            ),
            measurementDate = measurementDate,
        )
        val validation = validateUnsavedPreviewProfile(
            draft = UnsavedPreviewProfileDraft(
                heightCm = "170",
                birthDate = measurementDate.plusDays(1),
                sex = Sex.MALE,
            ),
            measurementDate = measurementDate,
        )

        assertTrue(atMeasurementLimit.isValid)
        assertEquals(measurementDate, atMeasurementLimit.profile?.birthDate)
        assertFalse(validation.isValid)
        assertNotNull(validation.birthDateError)
        assertNull(validation.profile)
    }

    @Test
    fun `unsaved preview rejects a missing birth date`() {
        val validation = validateUnsavedPreviewProfile(
            draft = UnsavedPreviewProfileDraft(
                heightCm = "170",
                birthDate = null,
                sex = Sex.FEMALE,
            ),
            measurementDate = LocalDate.of(2026, 8, 15),
        )

        assertFalse(validation.isValid)
        assertNotNull(validation.birthDateError)
        assertNull(validation.profile)
    }

    @Test
    fun `unsaved preview reducer preserves and clears typed leap birth date`() {
        val initial = UnsavedMeasurementPreviewState(pending("preview", "2026-08-15T10:00:00Z"))
        val editing = reduceUnsavedPreview(initial, UnsavedPreviewAction.EnterProfileRequested)
        val birthDate = LocalDate.of(2000, 2, 29)
        val selected = reduceUnsavedPreview(
            editing,
            UnsavedPreviewAction.ProfileChanged(
                editing.profileDraft.copy(birthDate = birthDate),
            ),
        )
        val cleared = reduceUnsavedPreview(
            selected,
            UnsavedPreviewAction.ProfileChanged(
                selected.profileDraft.copy(birthDate = null),
            ),
        )

        assertEquals(birthDate, selected.profileDraft.birthDate)
        assertNull(cleared.profileDraft.birthDate)
    }

    @Test
    fun `unsaved preview rejects non-final raw data instead of throwing`() {
        val invalidPending = pending("preview", "2026-08-15T10:00:00Z").copy(isStable = false)

        val result = calculateUnsavedPreview(
            pending = invalidPending,
            draft = UnsavedPreviewProfileDraft(
                heightCm = "170",
                birthDate = LocalDate.of(1990, 1, 1),
                sex = Sex.FEMALE,
            ),
            zoneId = ZoneOffset.UTC,
        )

        assertNull(result)
    }

    @Test
    fun `late calculation result cannot revive a preview after navigating back`() {
        val initial = UnsavedMeasurementPreviewState(pending("preview", "2026-08-15T10:00:00Z"))
        val editing = reduceUnsavedPreview(initial, UnsavedPreviewAction.EnterProfileRequested)
        val calculating = reduceUnsavedPreview(editing, UnsavedPreviewAction.CalculationStarted(1L))
        val backAtRaw = reduceUnsavedPreview(calculating, UnsavedPreviewAction.BackRequested)
        val result = requireNotNull(
            calculateUnsavedPreview(
                pending = initial.pending,
                draft = UnsavedPreviewProfileDraft(
                    heightCm = "170",
                    birthDate = LocalDate.of(1990, 1, 1),
                    sex = Sex.FEMALE,
                ),
                zoneId = ZoneOffset.UTC,
            ),
        )

        val afterLateResult = reduceUnsavedPreview(
            backAtRaw,
            UnsavedPreviewAction.CalculationCompleted(1L, result),
        )

        assertSame(backAtRaw, afterLateResult)
        assertEquals(UnsavedPreviewStep.RAW_SUMMARY, afterLateResult.step)
        assertNull(afterLateResult.result)

        val editingAgain = reduceUnsavedPreview(backAtRaw, UnsavedPreviewAction.EnterProfileRequested)
        val afterDuplicateResult = reduceUnsavedPreview(
            editingAgain,
            UnsavedPreviewAction.CalculationCompleted(1L, result),
        )
        assertSame(editingAgain, afterDuplicateResult)
        assertNull(afterDuplicateResult.result)
    }

    private fun account(id: String, name: String): Account = Account(
        id = AccountId(id),
        displayName = name,
        profile = AccountProfile.Complete(
            heightCm = 170.0,
            birthDate = LocalDate.of(1990, 1, 1),
            sex = Sex.FEMALE,
        ),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun pending(id: String, enqueuedAt: String): PendingMeasurement = PendingMeasurement(
        id = PendingMeasurementId(id),
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAt = Instant.parse("2026-08-15T09:59:00Z"),
        weightKg = 70.0,
        impedanceOhm = 500,
        isStable = true,
        hasImpedance = true,
        rawPayload = byteArrayOf(1, 2, 3),
        deduplicationHash = "hash-$id",
        enqueuedAt = Instant.parse(enqueuedAt),
    )
}
