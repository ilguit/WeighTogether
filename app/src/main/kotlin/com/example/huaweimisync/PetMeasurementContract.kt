package com.example.huaweimisync

import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetMeasurement
import java.time.Instant

internal const val PET_MEASUREMENT_TIMEOUT_MILLIS = 30_000L
internal const val PET_SCALE_REQUIRED_MESSAGE = "Сначала выберите весы в настройках"
internal const val PET_BLUETOOTH_PERMISSION_MESSAGE = "Разрешите Bluetooth для взвешивания питомца"
internal const val PET_MEASUREMENT_TIMEOUT_MESSAGE = "Весы не передали новое стабильное измерение"

sealed interface PetMeasurementUiState {
    data object Idle : PetMeasurementUiState
    data object SelectingPet : PetMeasurementUiState
    data object CreatingPet : PetMeasurementUiState

    data class AwaitingFirstWeight(val pet: Pet) : PetMeasurementUiState

    data class AwaitingSecondWeight(
        val pet: Pet,
        val firstWeightKg: Double,
    ) : PetMeasurementUiState

    data class Saving(
        val pet: Pet,
        val firstWeightKg: Double,
        val secondWeightKg: Double,
    ) : PetMeasurementUiState

    data class Completed(
        val pet: Pet,
        val measurement: PetMeasurement,
    ) : PetMeasurementUiState

    data class Error(val message: String) : PetMeasurementUiState
    data object Cancelled : PetMeasurementUiState
}

internal data class PetScaleReading(
    val address: String,
    val measuredAt: Instant,
    val weightKg: Double,
    val isStableWeight: Boolean,
    /** Hex keeps ByteArray identity value-based and immutable for duplicate detection. */
    val rawIdentity: String,
)

internal data class PetMeasurementSaveRequest(
    val token: PetMeasurementCoordinator.OperationToken,
    val petId: PetId,
    val measuredAt: Instant,
    val firstWeightKg: Double,
    val secondWeightKg: Double,
)

/** Android-free state machine for the two distinct stable readings used by pet weighing. */
internal class PetMeasurementCoordinator(
    private val setState: (PetMeasurementUiState) -> Unit,
    private val stopScanner: () -> Unit,
    private val restoreAutomaticScanning: () -> Unit,
    private val showMessage: (String) -> Unit,
) {
    private val lock = Any()
    private var nextOperationId = 0L
    private var operation: Operation? = null

    fun showSelection(): Boolean = synchronized(lock) {
        if (operation != null) return false
        setState(PetMeasurementUiState.SelectingPet)
        true
    }

    fun showCreating(): Boolean = synchronized(lock) {
        if (operation != null) return false
        setState(PetMeasurementUiState.CreatingPet)
        true
    }

    fun start(pet: Pet, selectedAddress: String): OperationToken? {
        val token = synchronized(lock) {
            if (operation != null) return null
            OperationToken(++nextOperationId).also {
                operation = Operation(it, pet, selectedAddress)
            }
        }
        setState(PetMeasurementUiState.AwaitingFirstWeight(pet))
        return token
    }

    fun attachTimeout(token: OperationToken, cancel: () -> Unit) {
        val cancelImmediately = synchronized(lock) {
            operation?.takeIf { it.token == token }?.let {
                it.cancelTimeout?.invoke()
                it.cancelTimeout = cancel
                false
            } ?: true
        }
        if (cancelImmediately) cancel()
    }

    fun accept(token: OperationToken, reading: PetScaleReading): PetMeasurementSaveRequest? {
        val transition = synchronized(lock) {
            val active = operation?.takeIf { it.token == token } ?: return null
            if (active.saving) return null
            if (!reading.isStableWeight ||
                !isSelectedScaleAddress(active.selectedAddress, reading.address)
            ) return null
            val identity = ReadingIdentity(reading.measuredAt, reading.rawIdentity)
            val first = active.first
            if (first == null) {
                active.first = CapturedReading(identity, reading.measuredAt, reading.weightKg)
                FirstAccepted(active.pet, reading.weightKg)
            } else {
                if (first.identity == identity) return null
                active.cancelTimeout?.invoke()
                active.cancelTimeout = null
                active.saving = true
                SecondAccepted(
                    PetMeasurementSaveRequest(
                        token = token,
                        petId = active.pet.id,
                        measuredAt = reading.measuredAt,
                        firstWeightKg = first.weightKg,
                        secondWeightKg = reading.weightKg,
                    ),
                    active.pet,
                )
            }
        }
        return when (transition) {
            is FirstAccepted -> {
                setState(
                    PetMeasurementUiState.AwaitingSecondWeight(
                        transition.pet,
                        transition.weightKg,
                    ),
                )
                null
            }
            is SecondAccepted -> {
                stopScanner()
                setState(
                    PetMeasurementUiState.Saving(
                        transition.pet,
                        transition.request.firstWeightKg,
                        transition.request.secondWeightKg,
                    ),
                )
                transition.request
            }
        }
    }

    fun saved(token: OperationToken, measurement: PetMeasurement) {
        val pet = synchronized(lock) { operation?.takeIf { it.token == token }?.pet } ?: return
        finish(token, PetMeasurementUiState.Completed(pet, measurement))
    }

    fun fail(token: OperationToken, message: String) =
        finish(token, PetMeasurementUiState.Error(message), message)

    fun timeout(token: OperationToken) =
        finish(token, PetMeasurementUiState.Error(PET_MEASUREMENT_TIMEOUT_MESSAGE), PET_MEASUREMENT_TIMEOUT_MESSAGE)

    fun cancel() {
        val token = synchronized(lock) { operation?.token }
        if (token == null) setState(PetMeasurementUiState.Idle)
        else finish(token, PetMeasurementUiState.Cancelled)
    }

    fun clear() {
        val token = synchronized(lock) { operation?.token } ?: return
        finish(token, PetMeasurementUiState.Idle)
    }

    private fun finish(
        token: OperationToken,
        terminalState: PetMeasurementUiState,
        message: String? = null,
    ) {
        val cancel = synchronized(lock) {
            val active = operation?.takeIf { it.token == token } ?: return
            operation = null
            active.cancelTimeout
        }
        cancel?.invoke()
        stopScanner()
        restoreAutomaticScanning()
        setState(terminalState)
        message?.let(showMessage)
    }

    val isActive: Boolean
        get() = synchronized(lock) { operation != null }

    internal class OperationToken internal constructor(internal val id: Long)

    private data class Operation(
        val token: OperationToken,
        val pet: Pet,
        val selectedAddress: String,
        var first: CapturedReading? = null,
        var cancelTimeout: (() -> Unit)? = null,
        var saving: Boolean = false,
    )

    private data class ReadingIdentity(val measuredAt: Instant, val rawIdentity: String)
    private data class CapturedReading(
        val identity: ReadingIdentity,
        val measuredAt: Instant,
        val weightKg: Double,
    )
    private sealed interface Transition
    private data class FirstAccepted(val pet: Pet, val weightKg: Double) : Transition
    private data class SecondAccepted(
        val request: PetMeasurementSaveRequest,
        val pet: Pet,
    ) : Transition
}
