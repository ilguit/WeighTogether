package com.palixander.weightogether.ui.routing

import com.palixander.weightogether.R
import androidx.compose.runtime.Immutable
import com.palixander.weightogether.core.BodyComposition
import com.palixander.weightogether.core.BodyCompositionCalculator
import com.palixander.weightogether.core.BodyMetric
import com.palixander.weightogether.core.MetricInterpretation
import com.palixander.weightogether.core.MetricReading
import com.palixander.weightogether.core.ReferenceClassifier
import com.palixander.weightogether.core.ReferenceContext
import com.palixander.weightogether.core.Sex
import com.palixander.weightogether.core.UserProfile
import com.palixander.weightogether.domain.PendingMeasurement
import com.palixander.weightogether.domain.PendingMeasurementId
import com.palixander.weightogether.domain.toRawScaleMeasurement
import com.palixander.weightogether.measurements.MeasurementUiValues
import com.palixander.weightogether.ui.accounts.formatLocalizedDecimal
import com.palixander.weightogether.ui.accounts.parseLocalizedDecimal
import com.palixander.weightogether.ui.text.UiText
import com.palixander.weightogether.ui.text.uiText
import com.palixander.weightogether.ui.reference.toReferenceReadings
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UnsavedPreviewStep {
    RAW_SUMMARY,
    PROFILE_EDITOR,
    RESULT,
}

@Immutable
data class UnsavedPreviewProfileDraft(
    val heightCm: String = "",
    val birthDate: LocalDate? = null,
    val sex: Sex? = null,
) {
    companion object {
        fun from(profile: UserProfile): UnsavedPreviewProfileDraft = UnsavedPreviewProfileDraft(
            heightCm = formatLocalizedDecimal(profile.heightCm),
            birthDate = profile.birthDate,
            sex = profile.sex,
        )
    }
}

@Immutable
data class UnsavedPreviewProfileValidation(
    val heightError: UiText? = null,
    val birthDateError: UiText? = null,
    val sexError: UiText? = null,
    val profile: UserProfile? = null,
) {
    val isValid: Boolean
        get() = profile != null && heightError == null && birthDateError == null && sexError == null
}

fun validateUnsavedPreviewProfile(
    draft: UnsavedPreviewProfileDraft,
    measurementDate: LocalDate,
): UnsavedPreviewProfileValidation {
    val height = parseLocalizedDecimal(draft.heightCm)
    val heightError = if (height == null || height !in 100.0..230.0) {
        uiText(R.string.account_error_height)
    } else {
        null
    }
    val birthDate = draft.birthDate
    val birthDateError = if (birthDate == null || birthDate.isAfter(measurementDate)) {
        uiText(R.string.unsaved_preview_birth_date_error)
    } else {
        null
    }
    val sexError = if (draft.sex == null) uiText(R.string.account_error_sex) else null
    val profile = if (heightError == null && birthDateError == null && sexError == null) {
        UserProfile(
            heightCm = requireNotNull(height),
            birthDate = requireNotNull(birthDate),
            sex = requireNotNull(draft.sex),
        )
    } else {
        null
    }
    return UnsavedPreviewProfileValidation(heightError, birthDateError, sexError, profile)
}

/** An in-memory-only result. Repository and sync types are intentionally absent from the contract. */
@Immutable
data class UnsavedPreviewResult(
    val composition: BodyComposition,
    val profile: UserProfile,
    val readings: List<MetricReading>,
    val interpretations: Map<BodyMetric, MetricInterpretation>,
) {
    val isPersisted: Boolean = false
    val canSyncExternally: Boolean = false
}

enum class UnsavedPreviewCalculationError {
    CALCULATION_FAILED,
}

@Immutable
data class UnsavedMeasurementPreviewState(
    val pending: PendingMeasurement,
    val step: UnsavedPreviewStep = UnsavedPreviewStep.RAW_SUMMARY,
    val profileDraft: UnsavedPreviewProfileDraft = UnsavedPreviewProfileDraft(),
    val result: UnsavedPreviewResult? = null,
    val isCalculating: Boolean = false,
    val calculationRequestId: Long? = null,
    val calculationError: UnsavedPreviewCalculationError? = null,
) {
    init {
        require((step == UnsavedPreviewStep.RESULT) == (result != null)) {
            "Only the result step may retain an in-memory result"
        }
        require(!isCalculating || step == UnsavedPreviewStep.PROFILE_EDITOR) {
            "Calculation may run only from the one-time profile editor"
        }
        require(isCalculating == (calculationRequestId != null)) {
            "Only an active calculation may retain its request id"
        }
        require(!isCalculating || calculationError == null) {
            "An active calculation cannot also expose a terminal error"
        }
    }
}

internal data class UnsavedPreviewCalculationRequest(
    val pending: PendingMeasurement,
    val profileDraft: UnsavedPreviewProfileDraft,
    val requestId: Long,
)

@Immutable
internal data class ActiveUnsavedPreview(
    val state: UnsavedMeasurementPreviewState,
    val resolverSession: PendingResolverSession,
) {
    init {
        require(state.pending.id == resolverSession.pendingId) {
            "An unsaved preview must retain the resolver session for its exact pending item"
        }
    }

    fun completionFor(requestedPendingId: PendingMeasurementId): PendingResolverCompletion? =
        resolverSession.completionFor(requestedPendingId)?.takeIf {
            state.pending.id == requestedPendingId
        }
}

/**
 * Owns the in-memory preview and its origin as one atomic session.
 *
 * [takeClose] clears the session before the repository is called, so duplicate callbacks from a
 * stale composition or Back cannot discard the same preview twice.
 */
internal class UnsavedPreviewSessionCoordinator {
    private val mutableActive = MutableStateFlow<ActiveUnsavedPreview?>(null)
    val active: StateFlow<ActiveUnsavedPreview?> = mutableActive.asStateFlow()

    fun show(
        state: UnsavedMeasurementPreviewState,
        resolverSession: PendingResolverSession,
    ) {
        mutableActive.value = ActiveUnsavedPreview(state, resolverSession)
    }

    fun update(state: UnsavedMeasurementPreviewState) {
        while (true) {
            val current = mutableActive.value ?: return
            if (current.state.pending.id != state.pending.id) return
            if (current.state.isCalculating || state.isCalculating) return
            if (mutableActive.compareAndSet(current, current.copy(state = state))) return
        }
    }

    fun startCalculation(
        pendingId: PendingMeasurementId,
        requestId: Long,
        zoneId: ZoneId,
    ): UnsavedPreviewCalculationRequest? {
        while (true) {
            val current = mutableActive.value ?: return null
            if (current.state.pending.id != pendingId) return null
            val measurementDate = current.state.pending.measuredAt.atZone(zoneId).toLocalDate()
            if (!validateUnsavedPreviewProfile(current.state.profileDraft, measurementDate).isValid) return null
            val nextState = reduceUnsavedPreview(
                current.state,
                UnsavedPreviewAction.CalculationStarted(requestId),
            )
            if (nextState === current.state) return null
            if (mutableActive.compareAndSet(current, current.copy(state = nextState))) {
                return UnsavedPreviewCalculationRequest(
                    pending = current.state.pending,
                    profileDraft = current.state.profileDraft,
                    requestId = requestId,
                )
            }
        }
    }

    fun completeCalculation(
        pendingId: PendingMeasurementId,
        requestId: Long,
        result: UnsavedPreviewResult,
    ): Boolean = applyCalculationTerminal(
        pendingId,
        UnsavedPreviewAction.CalculationCompleted(requestId, result),
    )

    fun failCalculation(
        pendingId: PendingMeasurementId,
        requestId: Long,
    ): Boolean = applyCalculationTerminal(
        pendingId,
        UnsavedPreviewAction.CalculationFailed(
            requestId,
            UnsavedPreviewCalculationError.CALCULATION_FAILED,
        ),
    )

    private fun applyCalculationTerminal(
        pendingId: PendingMeasurementId,
        action: UnsavedPreviewAction,
    ): Boolean {
        while (true) {
            val current = mutableActive.value ?: return false
            if (current.state.pending.id != pendingId) return false
            val nextState = reduceUnsavedPreview(current.state, action)
            if (nextState === current.state) return false
            if (mutableActive.compareAndSet(current, current.copy(state = nextState))) return true
        }
    }

    fun retainAvailable(pendingIds: Set<PendingMeasurementId>) {
        while (true) {
            val current = mutableActive.value ?: return
            if (current.state.pending.id in pendingIds) return
            if (mutableActive.compareAndSet(current, null)) return
        }
    }

    fun takeClose(requestedPendingId: PendingMeasurementId): PendingResolverCompletion? {
        while (true) {
            val current = mutableActive.value ?: return null
            val completion = current.completionFor(requestedPendingId) ?: return null
            if (mutableActive.compareAndSet(current, null)) return completion
        }
    }
}

sealed interface UnsavedPreviewAction {
    data object EnterProfileRequested : UnsavedPreviewAction
    data class ProfileChanged(val draft: UnsavedPreviewProfileDraft) : UnsavedPreviewAction
    data class CalculationStarted(val requestId: Long) : UnsavedPreviewAction
    data class CalculationCompleted(
        val requestId: Long,
        val result: UnsavedPreviewResult,
    ) : UnsavedPreviewAction
    data class CalculationFailed(
        val requestId: Long,
        val error: UnsavedPreviewCalculationError,
    ) : UnsavedPreviewAction
    data object BackRequested : UnsavedPreviewAction
}

fun reduceUnsavedPreview(
    state: UnsavedMeasurementPreviewState,
    action: UnsavedPreviewAction,
): UnsavedMeasurementPreviewState = when (action) {
    UnsavedPreviewAction.EnterProfileRequested -> if (state.step == UnsavedPreviewStep.RAW_SUMMARY) {
        state.copy(step = UnsavedPreviewStep.PROFILE_EDITOR)
    } else {
        state
    }
    is UnsavedPreviewAction.ProfileChanged -> if (
        state.step == UnsavedPreviewStep.PROFILE_EDITOR && !state.isCalculating
    ) {
        state.copy(profileDraft = action.draft, calculationError = null)
    } else {
        state
    }
    is UnsavedPreviewAction.CalculationStarted -> if (
        state.step == UnsavedPreviewStep.PROFILE_EDITOR && !state.isCalculating
    ) {
        state.copy(
            isCalculating = true,
            calculationRequestId = action.requestId,
            calculationError = null,
        )
    } else {
        state
    }
    is UnsavedPreviewAction.CalculationCompleted -> if (
        state.step == UnsavedPreviewStep.PROFILE_EDITOR &&
        state.isCalculating &&
        state.calculationRequestId == action.requestId
    ) {
        state.copy(
            step = UnsavedPreviewStep.RESULT,
            result = action.result,
            isCalculating = false,
            calculationRequestId = null,
            calculationError = null,
        )
    } else {
        state
    }
    is UnsavedPreviewAction.CalculationFailed -> if (
        state.step == UnsavedPreviewStep.PROFILE_EDITOR &&
        state.isCalculating &&
        state.calculationRequestId == action.requestId
    ) {
        state.copy(
            isCalculating = false,
            calculationRequestId = null,
            calculationError = action.error,
        )
    } else {
        state
    }
    UnsavedPreviewAction.BackRequested -> when (state.step) {
        UnsavedPreviewStep.RAW_SUMMARY -> state
        UnsavedPreviewStep.PROFILE_EDITOR -> state.copy(
            step = UnsavedPreviewStep.RAW_SUMMARY,
            isCalculating = false,
            calculationRequestId = null,
            calculationError = null,
        )
        UnsavedPreviewStep.RESULT -> state.copy(
            step = UnsavedPreviewStep.PROFILE_EDITOR,
            result = null,
            calculationError = null,
        )
    }
}

fun calculateUnsavedPreview(
    pending: PendingMeasurement,
    draft: UnsavedPreviewProfileDraft,
    zoneId: ZoneId = ZoneId.systemDefault(),
    calculator: BodyCompositionCalculator = BodyCompositionCalculator(zoneId),
): UnsavedPreviewResult? {
    val measurementDate = pending.measuredAt.atZone(zoneId).toLocalDate()
    val profile = validateUnsavedPreviewProfile(draft, measurementDate).profile ?: return null
    val raw = pending.toRawScaleMeasurement()
    if (!raw.hasFullBodyComposition) return null
    val composition = calculator.calculate(raw, profile)
    val readings = composition.toUiValues().toReferenceReadings()
    val context = ReferenceContext(
        measurementDate = measurementDate,
        birthDate = profile.birthDate,
        sex = profile.sex,
        heightCm = profile.heightCm,
        weightKg = composition.weightKg,
        impedanceOhm = composition.impedanceOhm,
    )
    return UnsavedPreviewResult(
        composition = composition,
        profile = profile,
        readings = readings,
        interpretations = ReferenceClassifier().classifyAll(readings, context),
    )
}

data class UnsavedPreviewCallbacks(
    /** Keep this state in memory only; do not place its profile or result in saved state. */
    val onStateChange: (UnsavedMeasurementPreviewState) -> Unit,
    /** Starts an asynchronous in-memory calculation owned by MainViewModel. */
    val onCalculate: (PendingMeasurementId) -> Unit,
    /** Must discard pending data and leave only its deduplication tombstone. */
    val onCloseAndDiscard: (PendingMeasurementId) -> Unit,
) {
    companion object {
        val None = UnsavedPreviewCallbacks(onStateChange = {}, onCalculate = {}, onCloseAndDiscard = {})
    }
}

private fun BodyComposition.toUiValues(): MeasurementUiValues = MeasurementUiValues(
    weightKg = weightKg,
    impedanceOhm = impedanceOhm,
    bmi = bmi,
    bodyFatPercent = bodyFatPercent,
    bodyFatMassKg = bodyFatMassKg,
    waterPercent = waterPercent,
    waterMassKg = waterMassKg,
    muscleMassKg = muscleMassKg,
    skeletalMuscleMassKg = skeletalMuscleMassKg,
    boneMassKg = boneMassKg,
    proteinPercent = proteinPercent,
    proteinMassKg = proteinMassKg,
    visceralFatLevel = visceralFatLevel,
    basalMetabolicRateKcal = basalMetabolicRateKcal,
    metabolicAge = metabolicAge,
    leanBodyMassKg = leanBodyMassKg,
)
