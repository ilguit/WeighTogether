package com.palixander.scalesync.domain

data class RoutingCandidate(
    val accountId: AccountId,
    val differenceKg: Double,
    val medianWeightKg: Double,
    /** Original account-list index, used as the stable tie-breaker after differenceKg. */
    val stableOrder: Int,
) {
    init {
        require(differenceKg.isFinite() && differenceKg >= 0.0) {
            "Candidate difference must be finite and non-negative"
        }
        require(medianWeightKg.isFinite() && medianWeightKg >= 0.0) {
            "Candidate median weight must be finite and non-negative"
        }
        require(stableOrder >= 0) { "Candidate order must be non-negative" }
    }
}

val routingCandidateComparator: Comparator<RoutingCandidate> =
    compareBy<RoutingCandidate> { it.differenceKg }
        .thenBy { it.stableOrder }

fun Iterable<RoutingCandidate>.sortedForRouting(): List<RoutingCandidate> =
    sortedWith(routingCandidateComparator)

sealed interface RoutingDecision {
    data class AssignPrimary(
        val accountId: AccountId,
        val differenceKg: Double? = null,
        val medianWeightKg: Double? = null,
    ) : RoutingDecision {
        init {
            require((differenceKg == null) == (medianWeightKg == null)) {
                "Primary difference and median must either both be present or both be absent"
            }
            require(differenceKg == null || differenceKg.isFinite() && differenceKg >= 0.0)
            require(medianWeightKg == null || medianWeightKg.isFinite() && medianWeightKg >= 0.0)
        }
    }

    data class AssignSingle(val candidate: RoutingCandidate) : RoutingDecision

    data class ChooseAccount(val candidates: List<RoutingCandidate>) : RoutingDecision {
        init {
            require(candidates.size >= 2) { "Account choice requires at least two candidates" }
            require(candidates.distinctBy(RoutingCandidate::accountId).size == candidates.size) {
                "Routing candidates must have unique account ids"
            }
            require(candidates == candidates.sortedForRouting()) {
                "Routing candidates must be ordered by difference and stable account order"
            }
        }
    }

    data object NoMatch : RoutingDecision
}
