package com.palixander.weightogether

import com.palixander.weightogether.core.RawScaleMeasurement
import com.palixander.weightogether.core.Sex
import com.palixander.weightogether.core.UserProfile
import com.palixander.weightogether.domain.PendingDiscardUndoToken
import com.palixander.weightogether.domain.PendingMeasurementId
import com.palixander.weightogether.domain.RestorePendingResult
import com.palixander.weightogether.ui.profiles.ProfileKey
import com.palixander.weightogether.ui.routing.PendingResolverReturnDestination
import com.palixander.weightogether.ui.text.UiText
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

sealed interface MainUiEvent {
    data class ShowSnackbar(val message: UiText) : MainUiEvent
    data class ShowPendingDiscardUndo(
        val snackbarId: Long,
        val pendingId: PendingMeasurementId,
        val message: UiText = UiText.Resource(R.string.message_pending_discarded),
        val actionLabel: UiText = UiText.Resource(R.string.action_undo),
    ) : MainUiEvent

    data class PendingResolutionCompleted(
        val pendingId: PendingMeasurementId,
        val returnDestination: PendingResolverReturnDestination,
    ) : MainUiEvent
}

/** One-shot navigation requested by a reminder notification content tap. */
data class ReminderProfileNavigationRequest(
    val id: Long,
    val profileKey: ProfileKey?,
    val ownerUnavailable: Boolean = false,
)

internal class MainUiEventEmitter {
    private val channel = Channel<MainUiEvent>(Channel.UNLIMITED)

    val events: Flow<MainUiEvent> = channel.receiveAsFlow()

    fun showSnackbar(message: UiText) {
        check(channel.trySend(MainUiEvent.ShowSnackbar(message)).isSuccess) {
            "Main UI event channel is closed"
        }
    }

    fun showPendingDiscardUndo(snackbarId: Long, pendingId: PendingMeasurementId) {
        check(
            channel.trySend(
                MainUiEvent.ShowPendingDiscardUndo(
                    snackbarId = snackbarId,
                    pendingId = pendingId,
                ),
            ).isSuccess,
        ) { "Main UI event channel is closed" }
    }

    fun pendingResolutionCompleted(
        pendingId: PendingMeasurementId,
        returnDestination: PendingResolverReturnDestination,
    ) {
        check(
            channel.trySend(
                MainUiEvent.PendingResolutionCompleted(pendingId, returnDestination),
            ).isSuccess,
        ) { "Main UI event channel is closed" }
    }
}

internal sealed interface ScaleRefreshPreflightResult {
    data class Ready(val address: String) : ScaleRefreshPreflightResult
    data class Rejected(val message: UiText) : ScaleRefreshPreflightResult
}

private val BLUETOOTH_DEVICE_ADDRESS =
    Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

internal fun scaleRefreshPreflight(address: String?): ScaleRefreshPreflightResult {
    val selectedAddress = address?.trim()?.takeIf(BLUETOOTH_DEVICE_ADDRESS::matches)
        ?: return ScaleRefreshPreflightResult.Rejected(UiText.Resource(R.string.error_scale_not_selected))
    return ScaleRefreshPreflightResult.Ready(selectedAddress)
}

internal fun isSelectedScaleAddress(selectedAddress: String, observedAddress: String): Boolean =
    selectedAddress.equals(observedAddress.trim(), ignoreCase = true)

/**
 * Android-free foreground scan contract. Valid transient readings are reported synchronously
 * before the caller returns to the BLE callback; stable noise never mutates pet protection.
 */
internal fun shouldProcessForegroundScaleReading(
    raw: RawScaleMeasurement,
    selectedAddress: String? = null,
    observeTransient: (RawScaleMeasurement) -> Unit,
): Boolean {
    if (selectedAddress != null && !isSelectedScaleAddress(selectedAddress, raw.deviceAddress)) {
        return false
    }
    if (!raw.isStable) {
        if (raw.weightKg.isFinite() &&
            raw.weightKg in RawScaleMeasurement.MIN_WEIGHT_KG..RawScaleMeasurement.MAX_WEIGHT_KG
        ) {
            observeTransient(raw)
        }
        return false
    }
    return raw.isStableWeight
}

/**
 * Keeps address validation ahead of coordinator activation so a rejected gesture has no refresh,
 * scanner, background-service, or timeout lifecycle to unwind.
 */
internal fun beginScaleRefresh(
    address: String?,
    coordinator: ScaleRefreshCoordinator,
    showMessage: (UiText) -> Unit,
): ScaleRefreshStart? = when (val preflight = scaleRefreshPreflight(address)) {
    is ScaleRefreshPreflightResult.Rejected -> {
        showMessage(preflight.message)
        null
    }
    is ScaleRefreshPreflightResult.Ready -> coordinator.start()?.let { operation ->
        ScaleRefreshStart(operation = operation, address = preflight.address)
    }
}

internal data class ScaleRefreshStart(
    val operation: ScaleRefreshCoordinator.OperationToken,
    val address: String,
)

/**
 * Owns the lifecycle of the one-shot scale refresh independently from Settings scanning.
 * Scanner and timer implementations stay outside so the operation can be tested without Android.
 */
internal class ScaleRefreshCoordinator(
    private val setRefreshing: (Boolean) -> Unit,
    private val stopScanner: () -> Unit,
    private val restoreAutomaticScanning: () -> Unit,
    private val showMessage: (UiText) -> Unit,
) {
    private val lock = Any()
    private var nextOperationId = 0L
    private var operation: Operation? = null

    fun start(): OperationToken? {
        val token = synchronized(lock) {
            if (operation != null) return null
            OperationToken(++nextOperationId).also {
                operation = Operation(token = it)
            }
        }
        setRefreshing(true)
        return token
    }

    fun attachTimeout(token: OperationToken, cancel: () -> Unit) {
        val cancelImmediately = synchronized(lock) {
            val active = operation
            if (active?.token == token && active.phase == Phase.ACTIVE) {
                active.cancelTimeout = cancel
                false
            } else {
                true
            }
        }
        if (cancelImmediately) cancel()
    }

    fun complete(token: OperationToken) = finish(token)

    fun fail(token: OperationToken, message: UiText) = finish(token, message)

    fun timeout(token: OperationToken) = finish(token, UiText.Resource(R.string.error_scale_unavailable))

    fun clear() {
        val token = synchronized(lock) { operation?.token } ?: return
        finish(token)
    }

    private fun finish(token: OperationToken, message: UiText? = null) {
        val cancel = synchronized(lock) {
            val active = operation
            if (active?.token != token || active.phase != Phase.ACTIVE) return
            active.phase = Phase.FINISHING
            active.cancelTimeout.also { active.cancelTimeout = null }
        }
        try {
            cancel?.invoke()
            stopScanner()
            setRefreshing(false)
            restoreAutomaticScanning()
            message?.let(showMessage)
        } finally {
            synchronized(lock) {
                if (operation?.token == token) operation = null
            }
        }
    }

    internal class OperationToken internal constructor(internal val id: Long)

    private data class Operation(
        val token: OperationToken,
        var phase: Phase = Phase.ACTIVE,
        var cancelTimeout: (() -> Unit)? = null,
    )

    private enum class Phase { ACTIVE, FINISHING }
}

/**
 * Owns discard capabilities only while their addressed snackbar is active or waiting to be shown.
 * The UI event deliberately carries an id instead of the process-local token.
 */
internal class PendingDiscardUndoCoordinator(
    private val eventEmitter: MainUiEventEmitter,
) {
    private val lock = Any()
    private val activeTokens = mutableMapOf<Long, PendingDiscardUndoToken>()
    private var nextSnackbarId = 1L

    fun show(token: PendingDiscardUndoToken) {
        val snackbarId = synchronized(lock) {
            nextSnackbarId++.also { activeTokens[it] = token }
        }
        try {
            eventEmitter.showPendingDiscardUndo(snackbarId, token.pendingId)
        } catch (error: Throwable) {
            synchronized(lock) { activeTokens.remove(snackbarId) }
            throw error
        }
    }

    /** Returns a token exactly once and only when the addressed snackbar requested undo. */
    fun finish(snackbarId: Long, undoRequested: Boolean): PendingDiscardUndoToken? =
        synchronized(lock) {
            activeTokens.remove(snackbarId)?.takeIf { undoRequested }
        }

    internal val activeSnackbarCount: Int
        get() = synchronized(lock) { activeTokens.size }
}

internal fun RestorePendingResult.undoResultMessage(): UiText = when (this) {
    is RestorePendingResult.Restored -> UiText.Resource(R.string.message_pending_restored)
    is RestorePendingResult.AlreadyRestored -> UiText.Resource(R.string.message_pending_already_restored)
    is RestorePendingResult.AlreadyFinalized -> UiText.Resource(R.string.message_pending_restore_finalized)
    is RestorePendingResult.Conflict -> UiText.Resource(R.string.error_pending_restore_conflict)
}

internal suspend fun restorePendingForUndo(
    undoToken: PendingDiscardUndoToken,
    restorePending: suspend (PendingDiscardUndoToken) -> RestorePendingResult,
): UiText = try {
    restorePending(undoToken).undoResultMessage()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    UiText.Resource(
        R.string.error_pending_restore_failure_detail,
        listOf(UiText.Raw(error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName)),
    )
}

data class ProfileEditorUiState(
    val isOpen: Boolean = false,
    val height: String = "",
    val birthDate: String = "",
    val sex: Sex = Sex.MALE,
    val errorMessage: UiText? = null,
)

internal class ProfileEditorController(
    private val saveProfile: (UserProfile) -> Unit,
    private val eventEmitter: MainUiEventEmitter,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val mutableState = MutableStateFlow(ProfileEditorUiState())
    val state: StateFlow<ProfileEditorUiState> = mutableState.asStateFlow()

    fun open(profile: UserProfile?) {
        mutableState.value = ProfileEditorUiState(
            isOpen = true,
            height = profile?.heightCm?.toProfileHeightInput() ?: DEFAULT_HEIGHT,
            birthDate = profile?.birthDate?.toString() ?: DEFAULT_BIRTH_DATE,
            sex = profile?.sex ?: Sex.MALE,
        )
    }

    fun close() {
        mutableState.value = ProfileEditorUiState()
    }

    fun updateHeight(value: String) = updateOpenState { copy(height = value, errorMessage = null) }

    fun updateBirthDate(value: String) = updateOpenState { copy(birthDate = value, errorMessage = null) }

    fun updateSex(value: Sex) = updateOpenState { copy(sex = value, errorMessage = null) }

    fun save() {
        val editor = mutableState.value
        if (!editor.isOpen) return

        when (val result = validateProfile(editor.height, editor.birthDate, editor.sex, today())) {
            is ProfileValidationResult.Invalid -> {
                mutableState.value = editor.copy(errorMessage = result.message)
                eventEmitter.showSnackbar(result.message)
            }
            is ProfileValidationResult.Valid -> {
                saveProfile(result.profile)
                close()
                eventEmitter.showSnackbar(UiText.Resource(R.string.message_profile_saved))
            }
        }
    }

    private fun updateOpenState(transform: ProfileEditorUiState.() -> ProfileEditorUiState) {
        if (mutableState.value.isOpen) mutableState.value = mutableState.value.transform()
    }

    private companion object {
        const val DEFAULT_HEIGHT = "175"
        const val DEFAULT_BIRTH_DATE = "1990-01-01"
    }
}

internal sealed interface ProfileValidationResult {
    data class Valid(val profile: UserProfile) : ProfileValidationResult
    data class Invalid(val message: UiText) : ProfileValidationResult
}

internal fun validateProfile(
    height: String,
    birthDate: String,
    sex: Sex,
    today: LocalDate,
): ProfileValidationResult {
    val profile = runCatching {
        UserProfile(
            heightCm = height.replace(',', '.').toDouble(),
            birthDate = LocalDate.parse(birthDate),
            sex = sex,
        )
    }.getOrElse {
        return ProfileValidationResult.Invalid(UiText.Resource(R.string.error_profile_format))
    }
    if (profile.birthDate.isAfter(today.minusYears(MINIMUM_PROFILE_AGE_YEARS)) ||
        profile.birthDate.isBefore(today.minusYears(MAXIMUM_PROFILE_AGE_YEARS))
    ) {
        return ProfileValidationResult.Invalid(UiText.Resource(R.string.error_profile_age))
    }
    return ProfileValidationResult.Valid(profile)
}

enum class HealthConnectAvailability {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
    PROVIDER_UPDATE_REQUIRED,
    CHECK_FAILED,
}

data class HealthConnectPermissionsUiState(
    val availability: HealthConnectAvailability = HealthConnectAvailability.CHECKING,
    val requiredPermissions: Set<String> = emptySet(),
    val grantedPermissions: Set<String> = emptySet(),
) {
    val permissionStates: Map<String, Boolean>
        get() = requiredPermissions.associateWith(grantedPermissions::contains)

    val isConnected: Boolean
        get() = availability == HealthConnectAvailability.AVAILABLE &&
            requiredPermissions.isNotEmpty() &&
            grantedPermissions.containsAll(requiredPermissions)

    val missingPermissions: Set<String>
        get() = requiredPermissions - grantedPermissions

    companion object {
        fun checking(
            requiredPermissions: Set<String>,
            grantedPermissions: Set<String> = emptySet(),
        ) = HealthConnectPermissionsUiState(
            availability = HealthConnectAvailability.CHECKING,
            requiredPermissions = requiredPermissions,
            grantedPermissions = grantedPermissions.intersect(requiredPermissions),
        )

        fun snapshot(
            isAvailable: Boolean,
            requiredPermissions: Set<String>,
            grantedPermissions: Set<String>,
        ) = HealthConnectPermissionsUiState(
            availability = if (isAvailable) {
                HealthConnectAvailability.AVAILABLE
            } else {
                HealthConnectAvailability.UNAVAILABLE
            },
            requiredPermissions = requiredPermissions,
            grantedPermissions = grantedPermissions.intersect(requiredPermissions),
        )

        fun checkFailed(
            requiredPermissions: Set<String>,
            grantedPermissions: Set<String> = emptySet(),
        ) = HealthConnectPermissionsUiState(
            availability = HealthConnectAvailability.CHECK_FAILED,
            requiredPermissions = requiredPermissions,
            grantedPermissions = grantedPermissions.intersect(requiredPermissions),
        )
    }
}

/**
 * Independent capabilities used by the settings integration row.
 *
 * Opening system-owned Health Connect management requires both an available SDK and a resolvable
 * canonical intent. Syncing a measurement additionally requires an eligible selected account and
 * every mandatory permission.
 */
internal data class HealthConnectIntegrationCapabilities(
    val systemManagementAvailable: Boolean,
    val selectedAccountSyncEligible: Boolean,
    val selectedAccountSyncReady: Boolean,
)

internal fun healthConnectIntegrationCapabilities(
    permissions: HealthConnectPermissionsUiState,
    managementIntentAvailable: Boolean,
    selectedAccountSyncEligible: Boolean,
): HealthConnectIntegrationCapabilities = HealthConnectIntegrationCapabilities(
    systemManagementAvailable = managementIntentAvailable &&
        permissions.availability == HealthConnectAvailability.AVAILABLE,
    selectedAccountSyncEligible = selectedAccountSyncEligible,
    selectedAccountSyncReady = selectedAccountSyncEligible && permissions.isConnected,
)

/** A foreground/recreation refresh observes system state but never overrides a durable opt-out. */
internal fun shouldActivateHealthConnectAfterPermissionRefresh(
    isConnected: Boolean,
    explicitAuthorization: Boolean,
): Boolean = isConnected && explicitAuthorization

internal const val MINIMUM_PROFILE_AGE_YEARS = 10L
internal const val MAXIMUM_PROFILE_AGE_YEARS = 100L

private fun Double.toProfileHeightInput(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()
