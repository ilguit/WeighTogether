package com.palixander.weightogether.data

import com.palixander.weightogether.core.BodyComposition
import com.palixander.weightogether.core.BodyCompositionCalculator
import com.palixander.weightogether.core.RawScaleMeasurement
import com.palixander.weightogether.core.Sex
import com.palixander.weightogether.domain.Account
import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.domain.AccountMeasurement
import com.palixander.weightogether.domain.AccountProfile
import com.palixander.weightogether.domain.AccountRepository
import com.palixander.weightogether.domain.AccountSettings
import com.palixander.weightogether.domain.AccountUpdate
import com.palixander.weightogether.domain.CreateAccountAndAssignResult
import com.palixander.weightogether.domain.DiscardPendingResult
import com.palixander.weightogether.domain.DiscardPendingAndUpdateIgnorePolicyResult
import com.palixander.weightogether.domain.ExternalSyncPolicy
import com.palixander.weightogether.domain.FinalizePendingResult
import com.palixander.weightogether.domain.NewAccount
import com.palixander.weightogether.domain.PendingDiscardUndoToken
import com.palixander.weightogether.domain.PendingMeasurement
import com.palixander.weightogether.domain.PendingMeasurementId
import com.palixander.weightogether.domain.PrimaryHistorySyncMode
import com.palixander.weightogether.domain.RestorePendingResult
import com.palixander.weightogether.domain.RoutingDecision
import com.palixander.weightogether.domain.routing.MatchingEngine
import com.palixander.weightogether.domain.routing.WeightHistoryRecord
import com.palixander.weightogether.domain.toPendingMeasurement
import com.palixander.weightogether.worker.MeasurementSyncScheduler
import com.palixander.weightogether.worker.PendingDecisionFallback
import com.palixander.weightogether.worker.PendingDecisionPresentationCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementIngestionCoordinatorTest {
    private val primary = testAccount("primary", "Alice")
    private val secondary = testAccount("secondary", "Bob")

    @Test
    fun exactReplayPropagatesWithoutRoutingSyncOrNotification() = runBlocking {
        val events = mutableListOf<String>()
        val accounts = FakeAccountRepository(listOf(primary), primary.id, events)
        val persistence = FakeRoutingPersistence(accounts, events).apply {
            nextEnqueueResult = PendingPersistenceResult.ExactReplay
        }
        val scheduler = UniqueFakeScheduler(events)
        val notifier = RecordingNotifier()

        val result = coordinator(persistence, accounts, scheduler, notifier).ingest(raw(70.0))

        assertEquals(MeasurementIngestionResult.ExactReplay, result)
        assertEquals(listOf("enqueue"), events)
        assertTrue(scheduler.enqueued.isEmpty())
        assertTrue(notifier.counts.isEmpty())
    }

    @Test
    fun nonFinalPacketNeverCrossesTheDurableBoundary() = runBlocking {
        val events = mutableListOf<String>()
        val accounts = FakeAccountRepository(listOf(primary), primary.id, events)
        val persistence = FakeRoutingPersistence(accounts, events)
        val scheduler = UniqueFakeScheduler(events)
        val notifier = RecordingNotifier()

        val result = coordinator(persistence, accounts, scheduler, notifier).ingest(
            raw(70.0).copy(isStable = false),
        )

        assertEquals(MeasurementIngestionResult.IgnoredNotFinal, result)
        assertTrue(events.isEmpty())
        assertTrue(persistence.pendingSnapshot().isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
        assertTrue(notifier.counts.isEmpty())
    }

    @Test
    fun normalIngestionOnlyCreatesOrUpdatesAggregateWithoutRoutingOrSync() = runBlocking {
        val events = mutableListOf<String>()
        val accounts = FakeAccountRepository(listOf(primary), primary.id, events)
        val persistence = FakeRoutingPersistence(accounts, events)
        val scheduler = UniqueFakeScheduler(events)
        val coordinator = MeasurementIngestionCoordinator(
            persistence = persistence,
            accounts = accounts,
            calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
            syncScheduler = scheduler,
        )

        val created = coordinator.ingest(raw(70.0))
        val updated = coordinator.ingest(raw(70.0))

        assertTrue(created is MeasurementIngestionResult.CreatedAggregate)
        assertTrue(updated is MeasurementIngestionResult.UpdatedAggregate)
        assertEquals(listOf("enqueue", "enqueue"), events)
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(1, persistence.pendingSnapshot().size)
    }

    @Test
    fun finalizedUpgradeSchedulesOnlyEligiblePrimaryAfterPersistenceReturns() = runBlocking {
        val events = mutableListOf<String>()
        val accounts = FakeAccountRepository(listOf(primary, secondary), primary.id, events)
        val persistence = FakeRoutingPersistence(accounts, events)
        val scheduler = UniqueFakeScheduler(events)
        val eligible = AccountMeasurement(
            accountId = primary.id,
            composition = composition(raw(70.0).toPendingMeasurement(
                PendingMeasurementId("source"),
                "hash",
                RAW_TIME,
            ), "upgraded-primary"),
            externalSyncPolicy = ExternalSyncPolicy.AUTO,
            createdAt = RAW_TIME,
        )
        persistence.nextEnqueueResult = PendingPersistenceResult.UpgradedFinalized(eligible)

        val result = coordinator(persistence, accounts, scheduler).ingest(raw(70.0))

        assertTrue(result is MeasurementIngestionResult.UpgradedFinalized)
        assertEquals(setOf("upgraded-primary"), scheduler.enqueued)
        assertEquals(setOf("upgraded-primary"), scheduler.initiallyEnqueued)
        assertTrue(events.indexOf("enqueue") < events.indexOf("schedule"))

        val local = eligible.copy(
            accountId = secondary.id,
            externalSyncPolicy = ExternalSyncPolicy.ACCOUNT_LOCAL,
            measurementId = "upgraded-secondary",
        )
        persistence.nextEnqueueResult = PendingPersistenceResult.UpgradedFinalized(local)
        coordinator(persistence, accounts, scheduler).ingest(raw(71.0))
        assertEquals(setOf("upgraded-primary"), scheduler.enqueued)
    }

    @Test
    fun earlyClassificationAndSweepUseCoordinatorMatchingEngine() = runBlocking {
        val accounts = FakeAccountRepository(listOf(primary), primary.id)
        val persistence = FakeRoutingPersistence(accounts)
        val matchingEngine = MatchingEngine()
        val coordinator = MeasurementIngestionCoordinator(
            persistence = persistence,
            accounts = accounts,
            calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
            syncScheduler = UniqueFakeScheduler(),
            matchingEngine = matchingEngine,
        )

        coordinator.ingest(raw(70.0))
        coordinator.sweepPendingRouting()

        assertTrue(persistence.enqueueMatchingEngine === matchingEngine)
        assertTrue(persistence.reclassificationMatchingEngine === matchingEngine)
    }

    @Test
    fun updatedAwaitingAggregateRepostsAuthoritativePendingCount() = runBlocking {
        val incomplete = primary.copy(
            profile = AccountProfile.IncompleteRecovery(heightCm = 175.0),
        )
        val accounts = FakeAccountRepository(listOf(incomplete), incomplete.id)
        val persistence = FakeRoutingPersistence(accounts)
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence = persistence,
            accounts = accounts,
            scheduler = UniqueFakeScheduler(),
            notifier = notifier,
        )
        val waiting = coordinator.ingestAndFinalize(raw(70.0))
            as MeasurementIngestionResult.AwaitingDecision

        val updated = coordinator.ingest(raw(70.0))
            as MeasurementIngestionResult.UpdatedAggregate

        assertEquals(waiting.pending.id, updated.pending.id)
        assertEquals(listOf(1, 1), notifier.counts)
    }

    @Test
    fun finalizationRereadsDeadlineAndRoutesOnlyWhenDue() = runBlocking {
        val events = mutableListOf<String>()
        val accounts = FakeAccountRepository(listOf(primary), primary.id, events)
        val persistence = FakeRoutingPersistence(accounts, events)
        val scheduler = UniqueFakeScheduler(events)
        val coordinator = MeasurementIngestionCoordinator(
            persistence = persistence,
            accounts = accounts,
            calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
            syncScheduler = scheduler,
        )
        val created = coordinator.ingest(raw(70.0)) as MeasurementIngestionResult.CreatedAggregate

        val early = coordinator.finalizeDue(created.pending.id, created.pending.finalizeAfter.minusMillis(1))

        assertTrue(early is AggregateFinalizationResult.Reschedule)
        assertEquals(listOf("enqueue"), events)
        assertTrue(persistence.historyRequests.isEmpty())
        assertTrue(persistence.atomicRequests.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())

        val due = coordinator.finalizeDue(created.pending.id, created.pending.finalizeAfter)
            as AggregateFinalizationResult.Completed

        assertTrue(due.outcome is MeasurementIngestionResult.Assigned)
        assertEquals(1, persistence.finalized.size)
        assertEquals(1, scheduler.enqueued.size)

        val repeated = coordinator.finalizeDue(created.pending.id, created.pending.finalizeAfter)
            as AggregateFinalizationResult.Completed
        assertEquals(MeasurementIngestionResult.PendingMissing, repeated.outcome)
        assertEquals(1, persistence.finalized.size)
        assertEquals(1, scheduler.enqueued.size)
    }

    @Test
    fun manualRoutingReadsAccountsThenSettingsThenExclusiveHistoryInAccountOrder() = runBlocking {
        assertRoutingReadContract(finalizeDue = false)
    }

    @Test
    fun dueFallbackReadsAccountsThenSettingsThenExclusiveHistoryInAccountOrder() = runBlocking {
        assertRoutingReadContract(finalizeDue = true)
    }

    private suspend fun assertRoutingReadContract(finalizeDue: Boolean) {
        val events = mutableListOf<String>()
        val accounts = FakeAccountRepository(listOf(secondary, primary), primary.id, events)
        val persistence = FakeRoutingPersistence(accounts, events).apply {
            histories[primary.id] = listOf(history(50.0))
            histories[secondary.id] = listOf(
                history(70.0),
                WeightHistoryRecord(RAW_TIME, 100.0),
                WeightHistoryRecord(RAW_TIME.plusSeconds(1), 100.0),
            )
        }
        val scheduler = UniqueFakeScheduler(events)
        val successNotifier = RecordingSuccessfulMeasurementNotifier()
        val coordinator = coordinator(
            persistence, accounts, scheduler,
            successfulMeasurementNotifier = successNotifier,
        )
        val pending = (coordinator.ingest(raw(70.0)) as
            MeasurementIngestionResult.CreatedAggregate).pending
        events.clear()

        val result = coordinator.evaluate(pending, finalizeDue) as MeasurementIngestionResult.Assigned

        assertEquals(secondary.id, result.measurement.accountId)
        assertEquals(
            (if (finalizeDue) listOf("atomic") else emptyList()) +
                listOf("accounts", "settings", "history:secondary", "history:primary", "finalize:secondary"),
            events,
        )
        assertEquals(listOf(secondary.id to pending.measuredAt, primary.id to pending.measuredAt),
            persistence.historyRequests)
        assertTrue(persistence.pendingSnapshot().isEmpty())
        assertEquals(1, persistence.finalized.size)
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(listOf(SuccessfulNotification(result.measurement, secondary.displayName)),
            successNotifier.notifications)
    }

    @Test
    fun manualRoutingRereadsChangedSettingsHistoryAndAccounts() = runBlocking {
        assertRoutingUsesCurrentData(finalizeDue = false)
    }

    @Test
    fun dueFallbackRereadsChangedSettingsHistoryAndAccounts() = runBlocking {
        assertRoutingUsesCurrentData(finalizeDue = true)
    }

    private suspend fun assertRoutingUsesCurrentData(finalizeDue: Boolean) {
        val third = testAccount("third", "Cara")
        val accounts = FakeAccountRepository(listOf(primary, secondary, third), primary.id)
        accounts.updateWeightDeltaKg(1.0)
        val persistence = FakeRoutingPersistence(accounts).apply {
            histories[primary.id] = listOf(history(50.0))
            histories[secondary.id] = listOf(history(73.0))
            histories[third.id] = listOf(history(73.0))
        }
        val scheduler = UniqueFakeScheduler()
        val coordinator = coordinator(persistence, accounts, scheduler)
        val pending = (coordinator.ingest(raw(70.0)) as
            MeasurementIngestionResult.CreatedAggregate).pending
        val noMatch = coordinator.evaluate(pending, finalizeDue) as MeasurementIngestionResult.AwaitingDecision
        assertEquals(RoutingDecision.NoMatch, noMatch.decision)

        accounts.updateWeightDeltaKg(3.0)
        val widerMatch = coordinator.evaluate(pending, finalizeDue) as MeasurementIngestionResult.AwaitingDecision
        assertEquals(listOf(secondary.id, third.id),
            (widerMatch.decision as RoutingDecision.ChooseAccount).candidates.map { it.accountId })

        persistence.histories[secondary.id] = listOf(history(80.0))
        persistence.histories[third.id] = listOf(history(80.0))
        val changedHistory = coordinator.evaluate(pending, finalizeDue) as MeasurementIngestionResult.AwaitingDecision
        assertEquals(RoutingDecision.NoMatch, changedHistory.decision)

        persistence.histories[secondary.id] = listOf(history(70.0))
        persistence.histories[third.id] = listOf(history(70.0))
        accounts.deleteAccount(third.id)
        persistence.historyRequests.clear()
        val assigned = coordinator.evaluate(pending, finalizeDue) as MeasurementIngestionResult.Assigned
        assertEquals(secondary.id, assigned.measurement.accountId)
        assertEquals(listOf(primary.id, secondary.id), persistence.historyRequests.map { it.first })
        assertEquals(1, persistence.finalized.size)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun manualNoMatchStaysPendingButDueFallbackAppliesCurrentIgnorePolicy() = runBlocking {
        val accounts = FakeAccountRepository(emptyList(), null)
        val persistence = FakeRoutingPersistence(accounts)
        val notifier = RecordingNotifier()
        val scheduler = UniqueFakeScheduler()
        val coordinator = coordinator(persistence, accounts, scheduler, notifier)
        val pending = (coordinator.ingest(raw(70.0)) as
            MeasurementIngestionResult.CreatedAggregate).pending
        val initial = coordinator.evaluate(pending, true) as MeasurementIngestionResult.AwaitingDecision
        assertEquals(RoutingDecision.NoMatch, initial.decision)

        accounts.updateIgnoreUnknownMeasurements(true)
        val manual = coordinator.route(pending.id) as MeasurementIngestionResult.AwaitingDecision
        assertEquals(RoutingDecision.NoMatch, manual.decision)
        assertEquals(listOf(pending), persistence.pendingSnapshot())

        assertEquals(MeasurementIngestionResult.AutomaticallyIgnoredUnknown,
            coordinator.evaluate(pending, true))
        assertTrue(persistence.pendingSnapshot().isEmpty())
        assertEquals(listOf(1, 1, 0), notifier.counts)
        assertEquals(MeasurementIngestionResult.SuppressedTombstone, coordinator.ingest(raw(70.0)))
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun nonNullAtomicResultBypassesFallbackReadsAndPreservesReschedule() = runBlocking {
        val events = mutableListOf<String>()
        val accounts = FakeAccountRepository(listOf(primary), primary.id, events)
        val persistence = FakeRoutingPersistence(accounts, events)
        val scheduler = UniqueFakeScheduler(events)
        val notifier = RecordingNotifier()
        val engine = MatchingEngine()
        val coordinator = MeasurementIngestionCoordinator(
            persistence, accounts, BodyCompositionCalculator(ZoneId.of("UTC")), scheduler,
            notifier = notifier, matchingEngine = engine,
        )
        val pending = (coordinator.ingest(raw(70.0)) as
            MeasurementIngestionResult.CreatedAggregate).pending
        val extended = pending.copy(finalizeAfter = pending.finalizeAfter.plusSeconds(10))
        persistence.atomicResult = AtomicDueRoutingResult.NotDue(extended)
        events.clear()

        assertEquals(AggregateFinalizationResult.Reschedule(extended),
            coordinator.finalizeDue(pending.id, pending.finalizeAfter))
        assertEquals(listOf("atomic"), events)
        assertEquals(listOf(pending.id to pending.finalizeAfter), persistence.atomicRequests)
        assertTrue(persistence.atomicMatchingEngine === engine)
        assertTrue(persistence.historyRequests.isEmpty())
        assertTrue(persistence.finalized.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
        assertTrue(notifier.counts.isEmpty())
    }

    private suspend fun MeasurementIngestionCoordinator.evaluate(
        pending: PendingMeasurement,
        finalizeDue: Boolean,
    ): MeasurementIngestionResult = if (finalizeDue) {
        (finalizeDue(pending.id, pending.finalizeAfter) as AggregateFinalizationResult.Completed).outcome
    } else {
        route(pending.id)
    }

    @Test
    fun primaryAssignmentIsDurableBeforeMatchingAndSchedulesAfterAtomicFinalize() = runBlocking {
        val events = mutableListOf<String>()
        val accounts = FakeAccountRepository(listOf(primary), primary.id, events)
        val persistence = FakeRoutingPersistence(accounts, events)
        val scheduler = UniqueFakeScheduler(events)
        val coordinator = coordinator(persistence, accounts, scheduler)

        val result = coordinator.ingestAndFinalize(raw(70.0)) as MeasurementIngestionResult.Assigned

        assertEquals(ExternalSyncPolicy.AUTO, result.measurement.externalSyncPolicy)
        assertTrue(events.indexOf("enqueue") < events.indexOf("accounts"))
        assertTrue(events.indexOf("history:primary") < events.indexOf("finalize:primary"))
        assertTrue(events.indexOf("finalize:primary") < events.indexOf("schedule"))
        assertEquals(setOf(result.measurement.measurementId), scheduler.enqueued)
        assertTrue(persistence.pendingSnapshot().isEmpty())
    }

    @Test
    fun matchingSecondaryIsAccountLocalAndNeverReachesScheduler() = runBlocking {
        val accounts = FakeAccountRepository(listOf(primary, secondary), primary.id)
        val persistence = FakeRoutingPersistence(accounts).apply {
            histories[primary.id] = listOf(history(50.0))
            histories[secondary.id] = listOf(history(70.0))
        }
        val scheduler = UniqueFakeScheduler()

        val result = coordinator(persistence, accounts, scheduler).ingestAndFinalize(raw(70.0))
            as MeasurementIngestionResult.Assigned

        assertEquals(secondary.id, result.measurement.accountId)
        assertEquals(ExternalSyncPolicy.ACCOUNT_LOCAL, result.measurement.externalSyncPolicy)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun ambiguousMeasurementsStayDurableInFifoAndUpdateCount() = runBlocking {
        val third = testAccount("third", "Cara")
        val accounts = FakeAccountRepository(listOf(primary, secondary, third), primary.id)
        val persistence = FakeRoutingPersistence(accounts).apply {
            histories[primary.id] = listOf(history(50.0))
            histories[secondary.id] = listOf(history(69.0))
            histories[third.id] = listOf(history(71.0))
        }
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )

        val first = coordinator.ingestAndFinalize(raw(70.0, RAW_TIME))
            as MeasurementIngestionResult.AwaitingDecision
        val second = coordinator.ingestAndFinalize(raw(70.5, RAW_TIME.plusSeconds(1)))
            as MeasurementIngestionResult.AwaitingDecision

        assertTrue(first.decision is RoutingDecision.ChooseAccount)
        assertTrue(second.decision is RoutingDecision.ChooseAccount)
        assertEquals(listOf(first.pending.id, second.pending.id), persistence.pendingSnapshot().map { it.id })
        assertEquals(listOf(1, 2), notifier.counts)
    }

    @Test
    fun noMatchStaysPendingWhenIgnorePolicyIsDisabled() = runBlocking {
        val accounts = FakeAccountRepository(emptyList(), null)
        val persistence = FakeRoutingPersistence(accounts)
        val notifier = RecordingNotifier()

        val result = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        ).ingestAndFinalize(raw(70.0)) as MeasurementIngestionResult.AwaitingDecision

        assertEquals(RoutingDecision.NoMatch, result.decision)
        assertEquals(listOf(result.pending), persistence.pendingSnapshot())
        assertEquals(listOf(1), notifier.counts)
    }

    @Test
    fun enabledPolicyAutomaticallyTombstonesOnlyNoMatchAndClearsPresentation() = runBlocking {
        val accounts = FakeAccountRepository(emptyList(), null).apply {
            updateIgnoreUnknownMeasurements(true)
        }
        val persistence = FakeRoutingPersistence(accounts)
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )
        val packet = raw(70.0)

        assertEquals(
            MeasurementIngestionResult.AutomaticallyIgnoredUnknown,
            coordinator.ingestAndFinalize(packet),
        )
        assertTrue(persistence.pendingSnapshot().isEmpty())
        assertEquals(listOf(0), notifier.counts)
        assertEquals(MeasurementIngestionResult.SuppressedTombstone, coordinator.ingest(packet))
    }

    @Test
    fun enabledIgnorePolicyDoesNotChangeChooseAccountRouting() = runBlocking {
        val third = testAccount("third", "Cara")
        val accounts = FakeAccountRepository(listOf(primary, secondary, third), primary.id).apply {
            updateIgnoreUnknownMeasurements(true)
        }
        val persistence = FakeRoutingPersistence(accounts).apply {
            histories[primary.id] = listOf(history(50.0))
            histories[secondary.id] = listOf(history(69.0))
            histories[third.id] = listOf(history(71.0))
        }

        val result = coordinator(persistence, accounts, UniqueFakeScheduler()).ingestAndFinalize(raw(70.0))
            as MeasurementIngestionResult.AwaitingDecision

        assertTrue(result.decision is RoutingDecision.ChooseAccount)
        assertEquals(listOf(result.pending), persistence.pendingSnapshot())
    }

    @Test
    fun policyChangeDoesNotSweepExistingPendingAndAtomicDisableReturnsUndoToken() = runBlocking {
        val accounts = FakeAccountRepository(emptyList(), null)
        val persistence = FakeRoutingPersistence(accounts)
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )
        val waiting = coordinator.ingestAndFinalize(raw(70.0)) as MeasurementIngestionResult.AwaitingDecision

        accounts.updateIgnoreUnknownMeasurements(true)

        assertEquals(listOf(waiting.pending), persistence.pendingSnapshot())
        assertTrue(
            coordinator.route(waiting.pending.id) is MeasurementIngestionResult.AwaitingDecision,
        )
        assertEquals(listOf(waiting.pending), persistence.pendingSnapshot())
        val discarded = coordinator.discardAndUpdateIgnorePolicy(waiting.pending.id, false)
            as DiscardPendingAndUpdateIgnorePolicyResult.Discarded

        assertEquals(waiting.pending.id, requireNotNull(discarded.undoToken).pendingId)
        assertFalse(accounts.settings.value.ignoreUnknownMeasurements)
        assertTrue(persistence.pendingSnapshot().isEmpty())
        assertEquals(listOf(1, 1, 0), notifier.counts)
    }

    @Test
    fun duplicateWorkerAndDoubleResolverTapCreateOneMeasurement() = runBlocking {
        val accounts = FakeAccountRepository(listOf(primary), primary.id)
        val persistence = FakeRoutingPersistence(accounts)
        val scheduler = UniqueFakeScheduler()
        val coordinator = coordinator(persistence, accounts, scheduler)
        val packet = raw(70.0)

        val first = coordinator.ingestAndFinalize(packet) as MeasurementIngestionResult.Assigned
        val duplicate = coordinator.ingest(packet)

        assertEquals(MeasurementIngestionResult.SuppressedFinal, duplicate)
        assertEquals(1, persistence.finalized.size)
        assertEquals(setOf(first.measurement.measurementId), scheduler.enqueued)

        val waitingPersistence = FakeRoutingPersistence(
            FakeAccountRepository(listOf(primary, secondary), primary.id),
        )
        val waitingAccounts = waitingPersistence.accounts
        waitingPersistence.histories[primary.id] = listOf(history(50.0))
        val waitingCoordinator = coordinator(waitingPersistence, waitingAccounts, UniqueFakeScheduler())
        val waiting = waitingCoordinator.ingestAndFinalize(packet) as MeasurementIngestionResult.AwaitingDecision
        val chosen = waitingCoordinator.chooseAccount(waiting.pending.id, secondary.id)
        val tappedAgain = waitingCoordinator.chooseAccount(waiting.pending.id, secondary.id)

        assertTrue(chosen is FinalizePendingResult.Finalized)
        assertTrue(tappedAgain is FinalizePendingResult.AlreadyFinalized)
        assertEquals(1, waitingPersistence.finalized.size)
        assertTrue(
            waitingCoordinator.discard(waiting.pending.id) is
                DiscardPendingResult.AlreadyFinalized,
        )
    }

    @Test
    fun automaticFinalizationNotifiesExistingAccountExactlyOnceAcrossRepeatsAndSweep() = runBlocking {
        val accounts = FakeAccountRepository(listOf(primary), primary.id)
        val persistence = FakeRoutingPersistence(accounts).apply {
            useAtomicDueRouting = true
        }
        val successNotifier = RecordingSuccessfulMeasurementNotifier()
        val coordinator = coordinator(
            persistence = persistence,
            accounts = accounts,
            scheduler = UniqueFakeScheduler(),
            successfulMeasurementNotifier = successNotifier,
        )
        val packet = raw(70.0)
        val created = coordinator.ingest(packet) as MeasurementIngestionResult.CreatedAggregate

        val finalized = coordinator.finalizeDue(created.pending.id, created.pending.finalizeAfter)
            as AggregateFinalizationResult.Completed
        val repeated = coordinator.finalizeDue(created.pending.id, created.pending.finalizeAfter)
        val duplicate = coordinator.ingest(packet)
        coordinator.sweepPendingRouting()

        val assigned = finalized.outcome as MeasurementIngestionResult.Assigned
        assertEquals(
            MeasurementIngestionResult.PendingMissing,
            (repeated as AggregateFinalizationResult.Completed).outcome,
        )
        assertEquals(MeasurementIngestionResult.SuppressedFinal, duplicate)
        assertEquals(
            listOf(SuccessfulNotification(assigned.measurement, "Alice")),
            successNotifier.notifications,
        )
    }

    @Test
    fun nonDueRouteAndManualChoiceNotifyTheSelectedExistingAccount() = runBlocking {
        val automaticAccounts = FakeAccountRepository(listOf(primary), primary.id)
        val automaticPersistence = FakeRoutingPersistence(automaticAccounts)
        val automaticNotifier = RecordingSuccessfulMeasurementNotifier()
        val automaticCoordinator = coordinator(
            automaticPersistence,
            automaticAccounts,
            UniqueFakeScheduler(),
            successfulMeasurementNotifier = automaticNotifier,
        )
        val created = automaticCoordinator.ingest(raw(70.0))
            as MeasurementIngestionResult.CreatedAggregate

        val routed = automaticCoordinator.route(created.pending.id)
            as MeasurementIngestionResult.Assigned

        assertEquals(
            listOf(SuccessfulNotification(routed.measurement, "Alice")),
            automaticNotifier.notifications,
        )

        val manualAccounts = FakeAccountRepository(listOf(primary, secondary), primary.id)
        val manualPersistence = FakeRoutingPersistence(manualAccounts).apply {
            histories[primary.id] = listOf(history(50.0))
        }
        val manualNotifier = RecordingSuccessfulMeasurementNotifier()
        val manualCoordinator = coordinator(
            manualPersistence,
            manualAccounts,
            UniqueFakeScheduler(),
            successfulMeasurementNotifier = manualNotifier,
        )
        val waiting = manualCoordinator.ingestAndFinalize(raw(71.0))
            as MeasurementIngestionResult.AwaitingDecision

        val chosen = manualCoordinator.chooseAccount(waiting.pending.id, secondary.id)
            as FinalizePendingResult.Finalized
        val repeatedChoice = manualCoordinator.chooseAccount(waiting.pending.id, secondary.id)

        assertTrue(repeatedChoice is FinalizePendingResult.AlreadyFinalized)
        assertEquals(
            listOf(SuccessfulNotification(chosen.measurement, "Bob")),
            manualNotifier.notifications,
        )
    }

    @Test
    fun aggregateUpdatesReplayUpgradesAndRecoveryNeverNotify() = runBlocking {
        val accounts = FakeAccountRepository(listOf(primary), primary.id)
        val persistence = FakeRoutingPersistence(accounts)
        val successNotifier = RecordingSuccessfulMeasurementNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            successfulMeasurementNotifier = successNotifier,
        )
        val packet = raw(70.0)
        val created = coordinator.ingest(packet) as MeasurementIngestionResult.CreatedAggregate

        assertTrue(coordinator.ingest(packet) is MeasurementIngestionResult.UpdatedAggregate)
        persistence.nextEnqueueResult = PendingPersistenceResult.ExactReplay
        assertEquals(MeasurementIngestionResult.ExactReplay, coordinator.ingest(raw(71.0)))
        val upgraded = AccountMeasurement(
            accountId = primary.id,
            composition = composition(created.pending, "upgraded"),
            externalSyncPolicy = ExternalSyncPolicy.AUTO,
            createdAt = RAW_TIME,
        )
        persistence.nextEnqueueResult = PendingPersistenceResult.UpgradedFinalized(upgraded)
        assertTrue(coordinator.ingest(raw(72.0)) is MeasurementIngestionResult.UpgradedFinalized)
        val discarded = coordinator.discard(created.pending.id) as DiscardPendingResult.Discarded
        assertTrue(coordinator.restore(discarded.undoToken) is RestorePendingResult.Restored)

        assertTrue(successNotifier.notifications.isEmpty())
    }

    @Test
    fun createAccountAndAssignDoesNotNotifySuccess() = runBlocking {
        val accounts = FakeAccountRepository(emptyList(), null)
        val persistence = FakeRoutingPersistence(accounts)
        val successNotifier = RecordingSuccessfulMeasurementNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            successfulMeasurementNotifier = successNotifier,
        )
        val waiting = coordinator.ingestAndFinalize(raw(70.0))
            as MeasurementIngestionResult.AwaitingDecision

        val result = coordinator.createAccountAndAssign(
            waiting.pending.id,
            NewAccount("Cara", completeProfile()),
        )

        assertTrue(result is CreateAccountAndAssignResult.Created)
        assertTrue(successNotifier.notifications.isEmpty())
    }

    @Test
    fun notifierFailureDoesNotChangeDurableSaveOrResult() = runBlocking {
        val accounts = FakeAccountRepository(listOf(primary), primary.id)
        val persistence = FakeRoutingPersistence(accounts)
        val scheduler = UniqueFakeScheduler()
        val coordinator = coordinator(
            persistence,
            accounts,
            scheduler,
            successfulMeasurementNotifier = ThrowingSuccessfulMeasurementNotifier,
        )
        val created = coordinator.ingest(raw(70.0)) as MeasurementIngestionResult.CreatedAggregate

        val completed = coordinator.finalizeDue(created.pending.id, created.pending.finalizeAfter)
            as AggregateFinalizationResult.Completed

        val assigned = completed.outcome as MeasurementIngestionResult.Assigned
        assertEquals(assigned.measurement, persistence.finalized.getValue(created.pending.id.value))
        assertEquals(setOf(assigned.measurement.measurementId), scheduler.enqueued)
    }

    @Test
    fun incompleteAutoTargetStaysPendingAndOneShotPreviewDoesNotCrossPersistence() = runBlocking {
        val incomplete = primary.copy(profile = AccountProfile.IncompleteRecovery(heightCm = 175.0))
        val accounts = FakeAccountRepository(listOf(incomplete), incomplete.id)
        val persistence = FakeRoutingPersistence(accounts)
        val scheduler = UniqueFakeScheduler()
        val coordinator = coordinator(persistence, accounts, scheduler)

        val waiting = coordinator.ingestAndFinalize(raw(70.0)) as MeasurementIngestionResult.AwaitingDecision
        val pendingBeforePreview = persistence.pendingSnapshot()
        val accountsBeforePreview = accounts.observeAccounts().first()
        val historiesBeforePreview = persistence.histories.toMap()
        persistence.writeOperations.clear()
        val rawPreview = coordinator.previewWithoutSaving(waiting.pending.id)
        val calculated = coordinator.previewWithoutSaving(waiting.pending.id, completeProfile())

        assertNull(rawPreview?.composition)
        assertNotNull(calculated?.composition)
        assertEquals(pendingBeforePreview, persistence.pendingSnapshot())
        assertEquals(accountsBeforePreview, accounts.observeAccounts().first())
        assertEquals(historiesBeforePreview, persistence.histories)
        assertTrue(persistence.writeOperations.isEmpty())
        assertTrue(persistence.finalized.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
        val discarded = coordinator.discard(waiting.pending.id)
            as DiscardPendingResult.Discarded

        assertEquals(waiting.pending.id, discarded.undoToken.pendingId)
        assertEquals(waiting.pending.deduplicationHash, discarded.undoToken.deduplicationHash)
        assertEquals(waiting.pending.enqueuedAt, discarded.undoToken.enqueuedAt)
        assertEquals(waiting.pending, discarded.undoToken.pending)
        assertNull(persistence.getPending(waiting.pending.id))
        assertEquals(
            DiscardPendingResult.PendingNotFound,
            coordinator.discard(waiting.pending.id),
        )
    }

    @Test
    fun discardFailureDoesNotMintTokenOrRefreshPendingPresentation() = runBlocking {
        val incomplete = primary.copy(profile = AccountProfile.IncompleteRecovery(heightCm = 175.0))
        val accounts = FakeAccountRepository(listOf(incomplete), incomplete.id)
        val persistence = FakeRoutingPersistence(accounts)
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )
        val waiting = coordinator.ingestAndFinalize(raw(70.0))
            as MeasurementIngestionResult.AwaitingDecision
        persistence.discardFailure = IllegalStateException("discard failed")

        val failure = runCatching { coordinator.discard(waiting.pending.id) }

        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertNotNull(persistence.getPending(waiting.pending.id))
        assertEquals(listOf(1), notifier.counts)
    }

    @Test
    fun deletingLastPendingCancelsNotificationAndRestoreRepostsExactlyOnce() = runBlocking {
        val incomplete = primary.copy(profile = AccountProfile.IncompleteRecovery(heightCm = 175.0))
        val accounts = FakeAccountRepository(listOf(incomplete), incomplete.id)
        val persistence = FakeRoutingPersistence(accounts)
        val posted = mutableListOf<Int>()
        var cancelled = 0
        val presentation = PendingDecisionPresentationCoordinator(
            notificationsAllowed = { true },
            postNotification = { posted += it.size },
            cancelNotification = { cancelled += 1 },
        )
        val presentedCounts = mutableListOf<Int>()
        val notifier = object : PendingDecisionNotifier {
            override fun updatePendingMeasurements(pendingIds: Set<PendingMeasurementId>) {
                presentedCounts += pendingIds.size
                presentation.updatePendingMeasurements(pendingIds)
            }
        }
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )
        val waiting = coordinator.ingestAndFinalize(raw(70.0))
            as MeasurementIngestionResult.AwaitingDecision
        val token = (coordinator.discard(waiting.pending.id) as DiscardPendingResult.Discarded)
            .undoToken
        val repeatedDelete = coordinator.discard(waiting.pending.id)

        val restored = coordinator.restore(token)
        val repeated = coordinator.restore(token)

        assertEquals(DiscardPendingResult.PendingNotFound, repeatedDelete)
        assertEquals(RestorePendingResult.Restored(waiting.pending), restored)
        assertEquals(RestorePendingResult.AlreadyRestored(waiting.pending), repeated)
        assertEquals(listOf(waiting.pending), persistence.pendingSnapshot())
        assertEquals(listOf(1, 0, 1), presentedCounts)
        assertEquals(listOf(1, 1), posted)
        assertEquals(1, cancelled)
        assertEquals(PendingDecisionFallback.Hidden, presentation.notificationDeniedFallback.value)
    }

    @Test
    fun restoreFailureKeepsDiscardedStateAndDoesNotRefreshPresentation() = runBlocking {
        val incomplete = primary.copy(profile = AccountProfile.IncompleteRecovery(heightCm = 175.0))
        val accounts = FakeAccountRepository(listOf(incomplete), incomplete.id)
        val persistence = FakeRoutingPersistence(accounts)
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )
        val waiting = coordinator.ingestAndFinalize(raw(70.0))
            as MeasurementIngestionResult.AwaitingDecision
        val token = (coordinator.discard(waiting.pending.id) as DiscardPendingResult.Discarded)
            .undoToken
        persistence.restoreFailure = IllegalStateException("restore failed")

        val failure = runCatching { coordinator.restore(token) }

        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertNull(persistence.getPending(waiting.pending.id))
        assertEquals(listOf(1, 0), notifier.counts)
    }

    @Test
    fun concurrentRefreshCannotPublishAnOlderPendingCountLast() = runBlocking {
        val accounts = FakeAccountRepository(listOf(primary), primary.id)
        val persistence = FakeRoutingPersistence(accounts)
        val inserted = persistence.enqueue(raw(70.0)) as PendingPersistenceResult.Inserted
        val notifier = RecordingNotifier()
        val coordinator = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        )
        val firstSnapshotCaptured = CompletableDeferred<Unit>()
        val releaseFirstSnapshot = CompletableDeferred<Unit>()
        persistence.firstPendingSnapshotCaptured = firstSnapshotCaptured
        persistence.releaseFirstPendingSnapshot = releaseFirstSnapshot

        val olderRefresh = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.refreshPendingPresentation()
        }
        firstSnapshotCaptured.await()
        assertTrue(persistence.discardPending(inserted.pending.id) is DiscardPendingResult.Discarded)
        val newerRefresh = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.refreshPendingPresentation()
        }

        releaseFirstSnapshot.complete(Unit)
        olderRefresh.await()
        newerRefresh.await()

        assertEquals(listOf(1, 0), notifier.counts)
    }

    @Test
    fun refreshCountsOnlyDueUnassignedPendingMeasurements() = runBlocking {
        val accounts = FakeAccountRepository(listOf(primary), primary.id)
        val persistence = FakeRoutingPersistence(accounts)
        val dueUnassigned = persistence.enqueue(raw(70.0)) as PendingPersistenceResult.Inserted
        val dueAssigned = persistence.enqueue(raw(71.0)) as PendingPersistenceResult.Inserted
        val notDueUnassigned = persistence.enqueue(raw(72.0)) as PendingPersistenceResult.Inserted
        persistence.replacePending(
            dueUnassigned.pending.copy(finalizeAfter = Instant.EPOCH),
        )
        persistence.replacePending(
            dueAssigned.pending.copy(
                finalizeAfter = Instant.EPOCH,
                provisionalAccountId = primary.id,
            ),
        )
        persistence.replacePending(
            notDueUnassigned.pending.copy(finalizeAfter = Instant.parse("9999-01-01T00:00:00Z")),
        )
        val notifier = RecordingNotifier()

        val count = coordinator(
            persistence,
            accounts,
            UniqueFakeScheduler(),
            notifier,
        ).refreshPendingPresentation()

        assertEquals(1, count)
        assertEquals(listOf(1), notifier.counts)
        assertEquals(listOf(setOf(dueUnassigned.pending.id)), notifier.snapshots)
        assertEquals(1, persistence.pendingSnapshotCallCount)
    }

    @Test
    fun defaultUnassignedSnapshotFiltersProvisionallyAssignedMeasurements() = runBlocking {
        val accounts = FakeAccountRepository(listOf(primary), primary.id)
        val persistence = FakeRoutingPersistence(accounts)
        val unassigned = persistence.enqueue(raw(70.0)) as PendingPersistenceResult.Inserted
        val assigned = persistence.enqueue(raw(71.0)) as PendingPersistenceResult.Inserted
        persistence.replacePending(assigned.pending.copy(provisionalAccountId = primary.id))

        val snapshot = persistence.unassignedPendingSnapshot()

        assertEquals(listOf(unassigned.pending), snapshot)
    }

    private fun coordinator(
        persistence: FakeRoutingPersistence,
        accounts: FakeAccountRepository,
        scheduler: UniqueFakeScheduler,
        notifier: PendingDecisionNotifier = NoOpPendingDecisionNotifier,
        successfulMeasurementNotifier: SuccessfulMeasurementNotifier =
            NoOpSuccessfulMeasurementNotifier,
    ) = MeasurementIngestionCoordinator(
        persistence = persistence,
        accounts = accounts,
        calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
        syncScheduler = scheduler,
        notifier = notifier,
        successfulMeasurementNotifier = successfulMeasurementNotifier,
    )

    private fun history(weight: Double) = WeightHistoryRecord(RAW_TIME.minusSeconds(1), weight)

    private suspend fun MeasurementIngestionCoordinator.ingestAndFinalize(
        raw: RawScaleMeasurement,
    ): MeasurementIngestionResult = when (val ingested = ingest(raw)) {
        is MeasurementIngestionResult.CreatedAggregate ->
            (finalizeDue(ingested.pending.id, ingested.pending.finalizeAfter) as
                AggregateFinalizationResult.Completed).outcome
        is MeasurementIngestionResult.UpdatedAggregate ->
            (finalizeDue(ingested.pending.id, ingested.pending.finalizeAfter) as
                AggregateFinalizationResult.Completed).outcome
        else -> ingested
    }
}

private class FakeRoutingPersistence(
    val accounts: FakeAccountRepository,
    private val events: MutableList<String> = mutableListOf(),
) : MeasurementRoutingPersistence {
    val histories = mutableMapOf<AccountId, List<WeightHistoryRecord>>()
    val historyRequests = mutableListOf<Pair<AccountId, Instant>>()
    val atomicRequests = mutableListOf<Pair<PendingMeasurementId, Instant>>()
    var atomicMatchingEngine: MatchingEngine? = null
    var atomicResult: AtomicDueRoutingResult? = null
    val finalized = linkedMapOf<String, AccountMeasurement>()
    val writeOperations = mutableListOf<String>()
    private val pending = linkedMapOf<PendingMeasurementId, PendingMeasurement>()
    private val finalizedByHash = mutableMapOf<String, AccountMeasurement>()
    private val tombstones = mutableSetOf<String>()
    private var nextId = 0
    var discardFailure: Throwable? = null
    var restoreFailure: Throwable? = null
    var firstPendingSnapshotCaptured: CompletableDeferred<Unit>? = null
    var releaseFirstPendingSnapshot: CompletableDeferred<Unit>? = null
    var enqueueMatchingEngine: MatchingEngine? = null
    var reclassificationMatchingEngine: MatchingEngine? = null
    var nextEnqueueResult: PendingPersistenceResult? = null
    var useAtomicDueRouting = false
    var pendingSnapshotCallCount = 0
        private set

    override suspend fun enqueue(raw: RawScaleMeasurement): PendingPersistenceResult {
        events += "enqueue"
        writeOperations += "enqueue"
        nextEnqueueResult?.let {
            nextEnqueueResult = null
            return it
        }
        val hash = hash(raw)
        if (hash in tombstones) return PendingPersistenceResult.Tombstoned
        finalizedByHash[hash]?.let { return PendingPersistenceResult.AlreadyFinalized(it) }
        pending.values.firstOrNull { it.deduplicationHash == hash }?.let {
            return PendingPersistenceResult.AlreadyPending(it)
        }
        nextId += 1
        val value = raw.toPendingMeasurement(
            PendingMeasurementId("pending-$nextId"),
            hash,
            RAW_TIME.plusSeconds(nextId.toLong()),
        )
        pending[value.id] = value
        return PendingPersistenceResult.Inserted(value)
    }

    override suspend fun enqueue(
        raw: RawScaleMeasurement,
        matchingEngine: MatchingEngine,
    ): PendingPersistenceResult {
        enqueueMatchingEngine = matchingEngine
        return enqueue(raw)
    }

    override suspend fun reclassifyPending(
        matchingEngine: MatchingEngine,
    ): List<PendingMeasurement> {
        reclassificationMatchingEngine = matchingEngine
        return pending.values.toList()
    }

    override suspend fun getPending(id: PendingMeasurementId): PendingMeasurement? = pending[id]

    override suspend fun pendingSnapshot(): List<PendingMeasurement> {
        val snapshot = pending.values.toList()
        pendingSnapshotCallCount += 1
        if (pendingSnapshotCallCount == 1) {
            firstPendingSnapshotCaptured?.complete(Unit)
            releaseFirstPendingSnapshot?.await()
        }
        return snapshot
    }

    fun replacePending(value: PendingMeasurement) {
        require(pending.containsKey(value.id))
        pending[value.id] = value
    }

    override suspend fun latestHistoryBefore(
        accountId: AccountId,
        measuredAtExclusive: Instant,
    ): List<WeightHistoryRecord> {
        events += "history:${accountId.value}"
        historyRequests += accountId to measuredAtExclusive
        return histories[accountId].orEmpty().filter { it.measuredAt.isBefore(measuredAtExclusive) }
    }

    override suspend fun routeDueAtomically(
        pendingId: PendingMeasurementId,
        now: Instant,
        matchingEngine: MatchingEngine,
    ): AtomicDueRoutingResult? {
        events += "atomic"
        atomicRequests += pendingId to now
        atomicMatchingEngine = matchingEngine
        atomicResult?.let { return it }
        if (!useAtomicDueRouting) return null
        val value = pending[pendingId] ?: return AtomicDueRoutingResult.PendingNotFound
        if (now < value.finalizeAfter) return AtomicDueRoutingResult.NotDue(value)
        val account = accounts.observeAccounts().first().singleOrNull()
            ?: return AtomicDueRoutingResult.AccountUnavailable
        return when (val result = finalizePending(pendingId, account.id)) {
            is FinalizePendingResult.Finalized -> AtomicDueRoutingResult.Finalized(result.measurement)
            is FinalizePendingResult.AlreadyFinalized ->
                AtomicDueRoutingResult.AlreadyFinalized(result.measurement)
            FinalizePendingResult.PendingNotFound -> AtomicDueRoutingResult.PendingNotFound
            FinalizePendingResult.AccountNotFound,
            FinalizePendingResult.ProfileIncomplete,
            -> AtomicDueRoutingResult.AccountUnavailable
        }
    }

    override suspend fun finalizePending(
        pendingId: PendingMeasurementId,
        accountId: AccountId,
    ): FinalizePendingResult {
        writeOperations += "finalize"
        finalized[pendingId.value]?.let { return FinalizePendingResult.AlreadyFinalized(it) }
        val value = pending[pendingId] ?: return FinalizePendingResult.PendingNotFound
        val account = accounts.getAccount(accountId) ?: return FinalizePendingResult.AccountNotFound
        if (account.profile !is AccountProfile.Complete) return FinalizePendingResult.ProfileIncomplete
        events += "finalize:${accountId.value}"
        val policy = if (accounts.settings.value.primaryAccountId == accountId) {
            ExternalSyncPolicy.AUTO
        } else {
            ExternalSyncPolicy.ACCOUNT_LOCAL
        }
        val measurement = AccountMeasurement(
            accountId = accountId,
            composition = composition(value, "measurement-${finalized.size + 1}"),
            externalSyncPolicy = policy,
            createdAt = RAW_TIME,
        )
        finalized[pendingId.value] = measurement
        finalizedByHash[value.deduplicationHash] = measurement
        pending.remove(pendingId)
        return FinalizePendingResult.Finalized(measurement)
    }

    override suspend fun createAccountAndAssignPending(
        pendingId: PendingMeasurementId,
        account: NewAccount,
    ): CreateAccountAndAssignResult {
        writeOperations += "create-account-and-assign"
        val created = accounts.createAccount(account)
        return when (val result = finalizePending(pendingId, created.id)) {
            is FinalizePendingResult.Finalized -> CreateAccountAndAssignResult.Created(created, result.measurement)
            is FinalizePendingResult.AlreadyFinalized -> CreateAccountAndAssignResult.AlreadyFinalized(result.measurement)
            FinalizePendingResult.PendingNotFound -> CreateAccountAndAssignResult.PendingNotFound
            FinalizePendingResult.AccountNotFound,
            FinalizePendingResult.ProfileIncomplete,
            -> error("fake created a complete account")
        }
    }

    override suspend fun discardPending(
        pendingId: PendingMeasurementId,
    ): DiscardPendingResult {
        writeOperations += "discard"
        discardFailure?.let { throw it }
        finalized[pendingId.value]?.let { finalizedMeasurement ->
            return DiscardPendingResult.AlreadyFinalized(finalizedMeasurement)
        }
        val value = pending.remove(pendingId) ?: return DiscardPendingResult.PendingNotFound
        tombstones += value.deduplicationHash
        return DiscardPendingResult.Discarded(PendingDiscardUndoToken(value))
    }

    override suspend fun discardUnknownPendingIfEnabled(
        pendingId: PendingMeasurementId,
    ): AutoIgnorePendingPersistenceResult {
        if (!accounts.settings.value.ignoreUnknownMeasurements) {
            return AutoIgnorePendingPersistenceResult.PolicyDisabled
        }
        finalized[pendingId.value]?.let {
            return AutoIgnorePendingPersistenceResult.AlreadyFinalized(it)
        }
        val value = pending.remove(pendingId)
            ?: return AutoIgnorePendingPersistenceResult.PendingNotFound
        tombstones += value.deduplicationHash
        return AutoIgnorePendingPersistenceResult.Discarded
    }

    override suspend fun discardPendingAndUpdateIgnorePolicy(
        pendingId: PendingMeasurementId,
        ignoreUnknownMeasurements: Boolean,
    ): DiscardPendingAndUpdateIgnorePolicyResult {
        finalized[pendingId.value]?.let {
            return DiscardPendingAndUpdateIgnorePolicyResult.AlreadyFinalized(it)
        }
        val value = pending.remove(pendingId)
            ?: return DiscardPendingAndUpdateIgnorePolicyResult.PendingNotFound
        tombstones += value.deduplicationHash
        accounts.updateIgnoreUnknownMeasurements(ignoreUnknownMeasurements)
        return DiscardPendingAndUpdateIgnorePolicyResult.Discarded(
            PendingDiscardUndoToken(value).takeUnless { ignoreUnknownMeasurements },
        )
    }

    override suspend fun restorePending(
        undoToken: PendingDiscardUndoToken,
    ): RestorePendingResult {
        restoreFailure?.let { throw it }
        val value = undoToken.pending
        finalized[value.id.value]?.let { return RestorePendingResult.AlreadyFinalized(it) }
        finalizedByHash[value.deduplicationHash]?.let {
            return RestorePendingResult.AlreadyFinalized(it)
        }
        pending[value.id]?.let { existing ->
            return if (existing == value) {
                RestorePendingResult.AlreadyRestored(existing)
            } else {
                RestorePendingResult.Conflict(existing)
            }
        }
        pending.values.firstOrNull {
            it.deduplicationHash == value.deduplicationHash
        }?.let { return RestorePendingResult.Conflict(it) }
        pending[value.id] = value
        tombstones -= value.deduplicationHash
        return RestorePendingResult.Restored(value)
    }
}

private class FakeAccountRepository(
    initialAccounts: List<Account>,
    primaryId: AccountId?,
    private val events: MutableList<String> = mutableListOf(),
) : AccountRepository {
    override suspend fun hasProfileRecalculationCandidates(accountId: AccountId): Boolean = false
    override suspend fun attemptProfileUpdate(
        account: AccountUpdate,
    ): com.palixander.weightogether.domain.ProfileUpdateAttemptResult = error("not needed")
    private val values = MutableStateFlow(initialAccounts)
    val settings = MutableStateFlow(AccountSettings(primaryId))

    override fun observeAccounts(): Flow<List<Account>> {
        events += "accounts"
        return values
    }

    override fun observeSettings(): Flow<AccountSettings> {
        events += "settings"
        return settings
    }

    override suspend fun getAccount(id: AccountId): Account? = values.value.firstOrNull { it.id == id }

    override suspend fun createAccount(account: NewAccount): Account {
        val value = testAccount("created-${values.value.size}", account.displayName, account.profile)
        values.value = values.value + value
        if (settings.value.primaryAccountId == null) settings.value = settings.value.copy(primaryAccountId = value.id)
        return value
    }

    override suspend fun updateAccount(
        account: AccountUpdate,
        historyUpdateMode: com.palixander.weightogether.domain.ProfileHistoryUpdateMode,
    ): Account = error("not needed")

    override suspend fun setPrimaryAccount(accountId: AccountId, historySyncMode: PrimaryHistorySyncMode) {
        settings.value = settings.value.copy(primaryAccountId = accountId)
    }

    override suspend fun updateWeightDeltaKg(weightDeltaKg: Double) {
        settings.value = settings.value.copy(weightDeltaKg = weightDeltaKg)
    }

    override suspend fun updateIgnoreUnknownMeasurements(enabled: Boolean) {
        settings.value = settings.value.copy(ignoreUnknownMeasurements = enabled)
    }

    override suspend fun deleteAccount(accountId: AccountId) {
        values.value = values.value.filterNot { it.id == accountId }
    }

    override suspend fun deletePrimaryWithReplacement(
        primaryAccountId: AccountId,
        replacementAccountId: AccountId?,
        historySyncMode: PrimaryHistorySyncMode,
    ) {
        deleteAccount(primaryAccountId)
        settings.value = settings.value.copy(primaryAccountId = replacementAccountId)
    }
}

private class UniqueFakeScheduler(
    private val events: MutableList<String> = mutableListOf(),
) : MeasurementSyncScheduler {
    val enqueued = linkedSetOf<String>()
    val initiallyEnqueued = linkedSetOf<String>()
    val cancelled = linkedSetOf<String>()

    override fun enqueue(measurementId: String) {
        events += "schedule"
        enqueued += measurementId
    }

    override fun enqueueInitial(measurementId: String) {
        initiallyEnqueued += measurementId
        enqueue(measurementId)
    }

    override fun deferCurrent(measurementId: String, notBeforeEpochMillis: Long) = Unit

    override fun cancel(measurementId: String) {
        cancelled += measurementId
    }
}

private class RecordingNotifier : PendingDecisionNotifier {
    val counts = mutableListOf<Int>()
    val snapshots = mutableListOf<Set<PendingMeasurementId>>()
    override fun updatePendingMeasurements(pendingIds: Set<PendingMeasurementId>) {
        snapshots += pendingIds
        counts += pendingIds.size
    }
}

private data class SuccessfulNotification(
    val measurement: AccountMeasurement,
    val accountDisplayName: String,
)

private class RecordingSuccessfulMeasurementNotifier : SuccessfulMeasurementNotifier {
    val notifications = mutableListOf<SuccessfulNotification>()

    override fun notifyMeasurementSaved(
        measurement: AccountMeasurement,
        accountDisplayName: String,
    ) {
        notifications += SuccessfulNotification(measurement, accountDisplayName)
    }
}

private object ThrowingSuccessfulMeasurementNotifier : SuccessfulMeasurementNotifier {
    override fun notifyMeasurementSaved(
        measurement: AccountMeasurement,
        accountDisplayName: String,
    ) {
        throw IllegalStateException("notification unavailable")
    }
}

private fun testAccount(
    id: String,
    name: String,
    profile: AccountProfile = completeProfile(),
) = Account(
    id = AccountId(id),
    displayName = name,
    profile = profile,
    createdAt = RAW_TIME.minusSeconds(100),
    updatedAt = RAW_TIME.minusSeconds(100),
)

private fun completeProfile() = AccountProfile.Complete(
    heightCm = 175.0,
    birthDate = LocalDate.of(1990, 1, 1),
    sex = Sex.MALE,
)

private fun raw(weight: Double, measuredAt: Instant = RAW_TIME) = RawScaleMeasurement(
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAt = measuredAt,
    weightKg = weight,
    impedanceOhm = 500,
    isStable = true,
    hasImpedance = true,
    rawPayload = byteArrayOf(1, 2, 3),
)

private fun hash(raw: RawScaleMeasurement): String =
    "${raw.deviceAddress}|${raw.measuredAt}|${raw.weightKg}|${raw.impedanceOhm}"

private fun composition(pending: PendingMeasurement, id: String) = BodyComposition(
    measurementId = id,
    deviceAddress = pending.deviceAddress,
    measuredAt = pending.measuredAt,
    weightKg = pending.weightKg,
    impedanceOhm = pending.impedanceOhm,
    bmi = 22.0,
    bodyFatPercent = 20.0,
    bodyFatMassKg = 14.0,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 50.0,
    skeletalMuscleMassKg = 25.0,
    boneMassKg = 3.0,
    proteinPercent = 18.0,
    proteinMassKg = 12.6,
    visceralFatLevel = 7.0,
    basalMetabolicRateKcal = 1_500.0,
    metabolicAge = 35,
    leanBodyMassKg = 56.0,
    algorithmVersion = "test",
)

private val RAW_TIME: Instant = Instant.parse("2026-08-15T12:00:00Z")
