package com.palixander.weightogether.domain.routing

import com.palixander.weightogether.core.RawScaleMeasurement
import com.palixander.weightogether.domain.Account
import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.domain.AccountSettings
import com.palixander.weightogether.domain.PendingMeasurement
import com.palixander.weightogether.domain.RoutingCandidate
import com.palixander.weightogether.domain.RoutingDecision
import com.palixander.weightogether.domain.sortedForRouting
import com.palixander.weightogether.domain.toRawScaleMeasurement
import java.time.Instant
import kotlin.math.abs

/**
 * Minimal history projection required by account matching.
 *
 * The engine deliberately validates values while reading them instead of trusting persistence:
 * corrupt or otherwise invalid weights are ignored and cannot poison a routing decision.
 */
data class WeightHistoryRecord(
    val measuredAt: Instant,
    val weightKg: Double,
)

/**
 * Pure weight-only account matcher. It has no dependency on Room, Android, or UI state.
 *
 * Routing eligibility depends only on a valid scale weight. Stability and impedance are ingestion
 * and body-composition concerns, so they deliberately do not change the selected account. Account
 * profiles are likewise never inspected: incomplete recovery profiles participate in weight
 * matching exactly like complete profiles. The original [accounts] list defines stable candidate
 * order even when the primary account is not the first element.
 */
class MatchingEngine {
    fun match(
        raw: PendingMeasurement,
        accounts: List<Account>,
        histories: Map<AccountId, List<WeightHistoryRecord>>,
        settings: AccountSettings,
    ): RoutingDecision = match(
        raw = raw.toRawScaleMeasurement(),
        accounts = accounts,
        histories = histories,
        settings = settings,
    )

    fun match(
        raw: RawScaleMeasurement,
        accounts: List<Account>,
        histories: Map<AccountId, List<WeightHistoryRecord>>,
        settings: AccountSettings,
    ): RoutingDecision {
        if (!raw.weightKg.isValidScaleWeight()) return RoutingDecision.NoMatch

        val primaryAccountId = settings.primaryAccountId ?: return RoutingDecision.NoMatch
        if (accounts.none { it.id == primaryAccountId }) return RoutingDecision.NoMatch

        val primaryMedian = medianWeightBefore(
            records = histories[primaryAccountId].orEmpty(),
            measuredAt = raw.measuredAt,
        )
        if (primaryMedian == null) {
            return RoutingDecision.AssignPrimary(primaryAccountId)
        }

        val primaryDifference = abs(raw.weightKg - primaryMedian)
        if (primaryDifference <= settings.weightDeltaKg) {
            return RoutingDecision.AssignPrimary(
                accountId = primaryAccountId,
                differenceKg = primaryDifference,
                medianWeightKg = primaryMedian,
            )
        }

        val seenAccountIds = mutableSetOf(primaryAccountId)
        val candidates = accounts.mapIndexedNotNull { stableOrder, account ->
            if (!seenAccountIds.add(account.id)) return@mapIndexedNotNull null

            val median = medianWeightBefore(
                records = histories[account.id].orEmpty(),
                measuredAt = raw.measuredAt,
            ) ?: return@mapIndexedNotNull null
            val difference = abs(raw.weightKg - median)
            if (difference > settings.weightDeltaKg) return@mapIndexedNotNull null

            RoutingCandidate(
                accountId = account.id,
                differenceKg = difference,
                medianWeightKg = median,
                stableOrder = stableOrder,
            )
        }.sortedForRouting()

        return when (candidates.size) {
            0 -> RoutingDecision.NoMatch
            1 -> RoutingDecision.AssignSingle(candidates.single())
            else -> RoutingDecision.ChooseAccount(candidates)
        }
    }

    private fun medianWeightBefore(
        records: List<WeightHistoryRecord>,
        measuredAt: Instant,
    ): Double? {
        val weights = records.asSequence()
            .withIndex()
            .filter { (_, record) ->
                record.measuredAt.isBefore(measuredAt) && record.weightKg.isValidScaleWeight()
            }
            .sortedWith(
                compareByDescending<IndexedValue<WeightHistoryRecord>> { it.value.measuredAt }
                    .thenBy { it.index },
            )
            .take(MAX_HISTORY_RECORDS)
            .map { it.value.weightKg }
            .sorted()
            .toList()

        return when (weights.size) {
            0 -> null
            1 -> weights[0]
            2 -> (weights[0] + weights[1]) / 2.0
            else -> weights[1]
        }
    }

    private fun Double.isValidScaleWeight(): Boolean =
        isFinite() && this in MIN_SCALE_WEIGHT_KG..MAX_SCALE_WEIGHT_KG

    private companion object {
        const val MAX_HISTORY_RECORDS = 3
        const val MIN_SCALE_WEIGHT_KG = 10.0
        const val MAX_SCALE_WEIGHT_KG = 300.0
    }
}
