package com.example.huaweimisync.ui.routing

import androidx.compose.runtime.Immutable
import com.example.huaweimisync.core.BodyComposition
import com.example.huaweimisync.core.BodyCompositionCalculator
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.core.UserProfile
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.domain.toRawScaleMeasurement
import com.example.huaweimisync.ui.accounts.formatLocalizedDecimal
import com.example.huaweimisync.ui.accounts.parseLocalizedDecimal
import com.example.huaweimisync.ui.accounts.parseProfileDate
import java.time.LocalDate
import java.time.ZoneId

enum class UnsavedPreviewStep {
    RAW_SUMMARY,
    PROFILE_EDITOR,
    RESULT,
}

@Immutable
data class UnsavedPreviewProfileDraft(
    val heightCm: String = "",
    val birthDate: String = "",
    val sex: Sex? = null,
) {
    companion object {
        fun from(profile: UserProfile): UnsavedPreviewProfileDraft = UnsavedPreviewProfileDraft(
            heightCm = formatLocalizedDecimal(profile.heightCm),
            birthDate = profile.birthDate.toString(),
            sex = profile.sex,
        )
    }
}

@Immutable
data class UnsavedPreviewProfileValidation(
    val heightError: String? = null,
    val birthDateError: String? = null,
    val sexError: String? = null,
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
        "Допустимый рост: 100–230 см"
    } else {
        null
    }
    val birthDate = parseProfileDate(draft.birthDate)
    val birthDateError = if (birthDate == null || birthDate.isAfter(measurementDate)) {
        "Дата рождения должна быть не позже измерения"
    } else {
        null
    }
    val sexError = if (draft.sex == null) "Выберите пол" else null
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
) {
    val isPersisted: Boolean = false
    val canSyncExternally: Boolean = false
}

@Immutable
data class UnsavedMeasurementPreviewState(
    val pending: PendingMeasurement,
    val step: UnsavedPreviewStep = UnsavedPreviewStep.RAW_SUMMARY,
    val profileDraft: UnsavedPreviewProfileDraft = UnsavedPreviewProfileDraft(),
    val result: UnsavedPreviewResult? = null,
    val isCalculating: Boolean = false,
) {
    init {
        require((step == UnsavedPreviewStep.RESULT) == (result != null)) {
            "Only the result step may retain an in-memory result"
        }
        require(!isCalculating || step == UnsavedPreviewStep.PROFILE_EDITOR) {
            "Calculation may run only from the one-time profile editor"
        }
    }
}

sealed interface UnsavedPreviewAction {
    data object EnterProfileRequested : UnsavedPreviewAction
    data class ProfileChanged(val draft: UnsavedPreviewProfileDraft) : UnsavedPreviewAction
    data object CalculationStarted : UnsavedPreviewAction
    data class CalculationCompleted(val result: UnsavedPreviewResult) : UnsavedPreviewAction
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
        state.copy(profileDraft = action.draft)
    } else {
        state
    }
    UnsavedPreviewAction.CalculationStarted -> if (
        state.step == UnsavedPreviewStep.PROFILE_EDITOR && !state.isCalculating
    ) {
        state.copy(isCalculating = true)
    } else {
        state
    }
    is UnsavedPreviewAction.CalculationCompleted -> if (
        state.step == UnsavedPreviewStep.PROFILE_EDITOR && state.isCalculating
    ) {
        state.copy(
            step = UnsavedPreviewStep.RESULT,
            result = action.result,
            isCalculating = false,
        )
    } else {
        state
    }
    UnsavedPreviewAction.BackRequested -> when (state.step) {
        UnsavedPreviewStep.RAW_SUMMARY -> state
        UnsavedPreviewStep.PROFILE_EDITOR -> state.copy(
            step = UnsavedPreviewStep.RAW_SUMMARY,
            isCalculating = false,
        )
        UnsavedPreviewStep.RESULT -> state.copy(
            step = UnsavedPreviewStep.PROFILE_EDITOR,
            result = null,
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
    return UnsavedPreviewResult(
        composition = calculator.calculate(raw, profile),
    )
}

data class UnsavedPreviewCallbacks(
    /** Keep this state in memory only; do not place its profile or result in saved state. */
    val onStateChange: (UnsavedMeasurementPreviewState) -> Unit,
    /** Must discard pending data and leave only its deduplication tombstone. */
    val onCloseAndDiscard: (PendingMeasurementId) -> Unit,
) {
    companion object {
        val None = UnsavedPreviewCallbacks(onStateChange = {}, onCloseAndDiscard = {})
    }
}
