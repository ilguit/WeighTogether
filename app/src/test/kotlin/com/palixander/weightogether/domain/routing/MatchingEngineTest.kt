package com.palixander.weightogether.domain.routing

import com.palixander.weightogether.core.RawScaleMeasurement
import com.palixander.weightogether.core.Sex
import com.palixander.weightogether.domain.Account
import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.domain.AccountProfile
import com.palixander.weightogether.domain.AccountSettings
import com.palixander.weightogether.domain.PendingMeasurementId
import com.palixander.weightogether.domain.RoutingCandidate
import com.palixander.weightogether.domain.RoutingDecision
import com.palixander.weightogether.domain.toPendingMeasurement
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MatchingEngineTest {
    private val engine = MatchingEngine()
    private val primary = account("primary")
    private val secondary = account("secondary")

    @Test
    fun emptyPrimaryHistoryAssignsPrimaryWithoutDifference() {
        val decision = match(accounts = listOf(primary), histories = emptyMap())

        assertEquals(
            RoutingDecision.AssignPrimary(accountId = primary.id),
            decision,
        )
        decision as RoutingDecision.AssignPrimary
        assertNull(decision.differenceKg)
        assertNull(decision.medianWeightKg)
    }

    @Test
    fun onePrimaryRecordIsTheMedian() {
        val decision = match(
            accounts = listOf(primary),
            histories = histories(primary.id to weights(69.0)),
            raw = raw(weightKg = 70.0),
        )

        assertEquals(
            RoutingDecision.AssignPrimary(primary.id, differenceKg = 1.0, medianWeightKg = 69.0),
            decision,
        )
    }

    @Test
    fun twoPrimaryRecordsUseTheirAverageAsMedian() {
        val decision = match(
            accounts = listOf(primary),
            histories = histories(primary.id to weights(68.0, 72.0)),
            raw = raw(weightKg = 70.0),
        )

        assertEquals(
            RoutingDecision.AssignPrimary(primary.id, differenceKg = 0.0, medianWeightKg = 70.0),
            decision,
        )
    }

    @Test
    fun threePrimaryRecordsUseTheMiddleWeightAsMedian() {
        val decision = match(
            accounts = listOf(primary),
            histories = histories(primary.id to weights(72.0, 68.0, 70.0)),
            raw = raw(weightKg = 70.5),
        )

        assertEquals(
            RoutingDecision.AssignPrimary(primary.id, differenceKg = 0.5, medianWeightKg = 70.0),
            decision,
        )
    }

    @Test
    fun onlyLatestThreeEligibleRecordsAreUsed() {
        val history = listOf(
            history(weightKg = 10.0, secondsBeforeRaw = 4),
            history(weightKg = 60.0, secondsBeforeRaw = 3),
            history(weightKg = 70.0, secondsBeforeRaw = 2),
            history(weightKg = 80.0, secondsBeforeRaw = 1),
        )

        val decision = match(
            accounts = listOf(primary),
            histories = histories(primary.id to history),
            raw = raw(weightKg = 70.0),
            delta = 1.0,
        )

        assertEquals(
            RoutingDecision.AssignPrimary(primary.id, differenceKg = 0.0, medianWeightKg = 70.0),
            decision,
        )
    }

    @Test
    fun exactDeltaBoundaryIsInclusive() {
        val decision = match(
            accounts = listOf(primary),
            histories = histories(primary.id to weights(67.0)),
            raw = raw(weightKg = 70.0),
            delta = 3.0,
        )

        assertEquals(
            RoutingDecision.AssignPrimary(primary.id, differenceKg = 3.0, medianWeightKg = 67.0),
            decision,
        )
    }

    @Test
    fun primaryWinsEvenWhenSecondaryIsCloser() {
        val decision = match(
            accounts = listOf(secondary, primary),
            histories = histories(
                primary.id to weights(68.0),
                secondary.id to weights(70.0),
            ),
            raw = raw(weightKg = 70.0),
            delta = 3.0,
        )

        assertEquals(
            RoutingDecision.AssignPrimary(primary.id, differenceKg = 2.0, medianWeightKg = 68.0),
            decision,
        )
    }

    @Test
    fun oneMatchingSecondaryIsAssignedAutomatically() {
        val decision = match(
            accounts = listOf(primary, secondary),
            histories = histories(
                primary.id to weights(60.0),
                secondary.id to weights(71.0),
            ),
            raw = raw(weightKg = 70.0),
            delta = 3.0,
        )

        assertEquals(
            RoutingDecision.AssignSingle(
                candidate(secondary.id, difference = 1.0, median = 71.0, order = 1),
            ),
            decision,
        )
    }

    @Test
    fun multipleSecondariesAreSortedByDifferenceThenOriginalAccountOrder() {
        val farther = account("farther")
        val tiedFirst = account("tied-first")
        val tiedSecond = account("tied-second")
        val accounts = listOf(farther, primary, tiedFirst, tiedSecond)

        val decision = match(
            accounts = accounts,
            histories = histories(
                primary.id to weights(50.0),
                farther.id to weights(72.0),
                tiedFirst.id to weights(69.0),
                tiedSecond.id to weights(71.0),
            ),
            raw = raw(weightKg = 70.0),
            delta = 3.0,
        )

        assertEquals(
            RoutingDecision.ChooseAccount(
                listOf(
                    candidate(tiedFirst.id, difference = 1.0, median = 69.0, order = 2),
                    candidate(tiedSecond.id, difference = 1.0, median = 71.0, order = 3),
                    candidate(farther.id, difference = 2.0, median = 72.0, order = 0),
                ),
            ),
            decision,
        )
    }

    @Test
    fun historiesOutsideDeltaProduceNoMatch() {
        val decision = match(
            accounts = listOf(primary, secondary),
            histories = histories(
                primary.id to weights(50.0),
                secondary.id to weights(60.0),
            ),
            raw = raw(weightKg = 70.0),
            delta = 3.0,
        )

        assertSame(RoutingDecision.NoMatch, decision)
    }

    @Test
    fun secondaryWithoutEligibleHistoryIsNeverACandidate() {
        val withHistory = account("with-history")
        val decision = match(
            accounts = listOf(primary, secondary, withHistory),
            histories = histories(
                primary.id to weights(50.0),
                secondary.id to emptyList(),
                withHistory.id to weights(70.0),
            ),
            raw = raw(weightKg = 70.0),
            delta = 3.0,
        )

        assertEquals(
            RoutingDecision.AssignSingle(
                candidate(withHistory.id, difference = 0.0, median = 70.0, order = 2),
            ),
            decision,
        )
    }

    @Test
    fun recordsAtOrAfterRawTimestampAreExcludedBeforeTakingLatestThree() {
        val raw = raw(weightKg = 70.0)
        val decision = match(
            accounts = listOf(primary),
            histories = histories(
                primary.id to listOf(
                    WeightHistoryRecord(raw.measuredAt.plusSeconds(1), 70.0),
                    WeightHistoryRecord(raw.measuredAt, 70.0),
                    WeightHistoryRecord(raw.measuredAt.minusSeconds(1), 50.0),
                ),
            ),
            raw = raw,
            delta = 3.0,
        )

        assertSame(RoutingDecision.NoMatch, decision)
    }

    @Test
    fun delayedOutOfOrderPacketUsesOnlyHistoryEarlierThanItsOwnTimestamp() {
        val delayedRaw = raw(weightKg = 80.0, measuredAt = RAW_TIME.minusSeconds(20))
        val decision = match(
            accounts = listOf(primary, secondary),
            histories = histories(
                primary.id to listOf(
                    WeightHistoryRecord(RAW_TIME.minusSeconds(5), 80.0),
                    WeightHistoryRecord(RAW_TIME.minusSeconds(30), 60.0),
                ),
                secondary.id to listOf(
                    WeightHistoryRecord(RAW_TIME.minusSeconds(1), 40.0),
                    WeightHistoryRecord(RAW_TIME.minusSeconds(40), 79.0),
                ),
            ),
            raw = delayedRaw,
            delta = 3.0,
        )

        assertEquals(
            RoutingDecision.AssignSingle(
                candidate(secondary.id, difference = 1.0, median = 79.0, order = 1),
            ),
            decision,
        )
    }

    @Test
    fun inputHistoryOrderDoesNotChangeWhichRecordsAreLatest() {
        val decision = match(
            accounts = listOf(primary),
            histories = histories(
                primary.id to listOf(
                    history(80.0, secondsBeforeRaw = 1),
                    history(10.0, secondsBeforeRaw = 20),
                    history(60.0, secondsBeforeRaw = 3),
                    history(70.0, secondsBeforeRaw = 2),
                ),
            ),
            raw = raw(weightKg = 70.0),
            delta = 1.0,
        )

        assertEquals(
            RoutingDecision.AssignPrimary(primary.id, differenceKg = 0.0, medianWeightKg = 70.0),
            decision,
        )
    }

    @Test
    fun incompleteProfilesDoNotAffectWeightMatching() {
        val incompletePrimary = account(
            id = "incomplete-primary",
            profile = AccountProfile.IncompleteRecovery(heightCm = 180.0),
        )
        val incompleteSecondary = account(
            id = "incomplete-secondary",
            profile = AccountProfile.IncompleteRecovery(),
        )
        val decision = match(
            accounts = listOf(incompleteSecondary, incompletePrimary),
            histories = histories(
                incompletePrimary.id to weights(50.0),
                incompleteSecondary.id to weights(70.0),
            ),
            raw = raw(weightKg = 70.0),
            primaryId = incompletePrimary.id,
            delta = 3.0,
        )

        assertEquals(
            RoutingDecision.AssignSingle(
                candidate(
                    incompleteSecondary.id,
                    difference = 0.0,
                    median = 70.0,
                    order = 0,
                ),
            ),
            decision,
        )
    }

    @Test
    fun duplicateAccountIdsUseFirstStablePositionOnly() {
        val duplicateSecondary = secondary.copy(
            displayName = "secondary duplicate",
            normalizedName = "secondary duplicate",
        )
        val decision = match(
            accounts = listOf(primary, secondary, duplicateSecondary),
            histories = histories(
                primary.id to weights(50.0),
                secondary.id to weights(70.0),
            ),
            raw = raw(weightKg = 70.0),
        )

        assertEquals(
            RoutingDecision.AssignSingle(
                candidate(secondary.id, difference = 0.0, median = 70.0, order = 1),
            ),
            decision,
        )
    }

    @Test
    fun invalidHistoryWeightsAreIgnored() {
        val decision = match(
            accounts = listOf(primary, secondary),
            histories = histories(
                primary.id to weights(50.0),
                secondary.id to listOf(
                    history(Double.NaN, 1),
                    history(Double.POSITIVE_INFINITY, 2),
                    history(-1.0, 3),
                    history(9.99, 4),
                    history(300.01, 5),
                ),
            ),
            raw = raw(weightKg = 70.0),
        )

        assertSame(RoutingDecision.NoMatch, decision)
    }

    @Test
    fun invalidRawWeightsReturnNoMatch() {
        val valid = raw(weightKg = 70.0)
        val invalidValues = listOf(
            valid.copy(weightKg = Double.NaN),
            valid.copy(weightKg = Double.POSITIVE_INFINITY),
            valid.copy(weightKg = Double.NEGATIVE_INFINITY),
            valid.copy(weightKg = 9.99),
            valid.copy(weightKg = 300.01),
        )

        invalidValues.forEach { invalid ->
            assertSame(
                "Expected NoMatch for $invalid",
                RoutingDecision.NoMatch,
                match(accounts = listOf(primary), histories = emptyMap(), raw = invalid),
            )
        }
    }

    @Test
    fun matchingUsesOnlyWeightAfterIngestion() {
        val nonFinalValues = listOf(
            raw(weightKg = 70.0).copy(isStable = false),
            raw(weightKg = 70.0).copy(hasImpedance = false, impedanceOhm = 0),
            raw(weightKg = 70.0).copy(impedanceOhm = 79),
            raw(weightKg = 70.0).copy(impedanceOhm = 3_001),
        )

        nonFinalValues.forEach { value ->
            assertEquals(
                "Expected weight-only routing for $value",
                RoutingDecision.AssignPrimary(
                    accountId = primary.id,
                    differenceKg = 1.0,
                    medianWeightKg = 69.0,
                ),
                match(
                    accounts = listOf(primary),
                    histories = histories(primary.id to weights(69.0)),
                    raw = value,
                ),
            )
        }
    }

    @Test
    fun missingOrUnknownPrimaryReturnsNoMatch() {
        val noPrimary = engine.match(
            raw = raw(weightKg = 70.0),
            accounts = listOf(primary),
            histories = emptyMap(),
            settings = AccountSettings(primaryAccountId = null),
        )
        val unknownPrimary = match(
            accounts = listOf(primary),
            histories = emptyMap(),
            primaryId = AccountId("unknown"),
        )

        assertSame(RoutingDecision.NoMatch, noPrimary)
        assertSame(RoutingDecision.NoMatch, unknownPrimary)
    }

    @Test
    fun pendingMeasurementOverloadUsesSameRoutingRules() {
        val raw = raw(weightKg = 70.0)
        val pending = raw.toPendingMeasurement(
            id = PendingMeasurementId("pending"),
            deduplicationHash = "hash",
            enqueuedAt = RAW_TIME.plusSeconds(1),
        )

        val decision = engine.match(
            raw = pending,
            accounts = listOf(primary),
            histories = histories(primary.id to weights(69.0)),
            settings = AccountSettings(primaryAccountId = primary.id, weightDeltaKg = 3.0),
        )

        assertEquals(
            RoutingDecision.AssignPrimary(primary.id, differenceKg = 1.0, medianWeightKg = 69.0),
            decision,
        )
    }

    private fun match(
        accounts: List<Account>,
        histories: Map<AccountId, List<WeightHistoryRecord>>,
        raw: RawScaleMeasurement = raw(weightKg = 70.0),
        primaryId: AccountId = primary.id,
        delta: Double = 3.0,
    ): RoutingDecision = engine.match(
        raw = raw,
        accounts = accounts,
        histories = histories,
        settings = AccountSettings(primaryAccountId = primaryId, weightDeltaKg = delta),
    )

    private fun account(
        id: String,
        profile: AccountProfile = AccountProfile.Complete(
            heightCm = 180.0,
            birthDate = LocalDate.of(1990, 1, 1),
            sex = Sex.MALE,
        ),
    ): Account = Account(
        id = AccountId(id),
        displayName = id,
        profile = profile,
        createdAt = Instant.parse("2025-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2025-01-01T00:00:00Z"),
    )

    private fun raw(
        weightKg: Double,
        measuredAt: Instant = RAW_TIME,
    ): RawScaleMeasurement = RawScaleMeasurement(
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAt = measuredAt,
        weightKg = weightKg,
        impedanceOhm = 500,
        isStable = true,
        hasImpedance = true,
        rawPayload = byteArrayOf(1, 2, 3),
    )

    private fun histories(
        vararg entries: Pair<AccountId, List<WeightHistoryRecord>>,
    ): Map<AccountId, List<WeightHistoryRecord>> = mapOf(*entries)

    private fun weights(vararg weights: Double): List<WeightHistoryRecord> =
        weights.mapIndexed { index, weight ->
            history(weightKg = weight, secondsBeforeRaw = weights.size - index)
        }

    private fun history(
        weightKg: Double,
        secondsBeforeRaw: Int,
    ): WeightHistoryRecord = WeightHistoryRecord(
        measuredAt = RAW_TIME.minusSeconds(secondsBeforeRaw.toLong()),
        weightKg = weightKg,
    )

    private fun candidate(
        accountId: AccountId,
        difference: Double,
        median: Double,
        order: Int,
    ): RoutingCandidate = RoutingCandidate(
        accountId = accountId,
        differenceKg = difference,
        medianWeightKg = median,
        stableOrder = order,
    )

    private companion object {
        val RAW_TIME: Instant = Instant.parse("2025-01-02T12:00:00Z")
    }
}
