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
    val receivedAtNanos: Long,
    val measuredAt: Instant,
    val weightKg: Double,
    val isStableWeight: Boolean,
    /** Hex keeps ByteArray identity value-based and immutable for duplicate detection. */
    val rawIdentity: String,
)

internal fun petReadingRawIdentity(rawPayload: ByteArray): String =
    rawPayload.joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Invalidates suspended pet lookups so stale selections cannot start BLE later. */
internal class PetMeasurementStartupGuard {
    private val lock = Any()
    private var generation = 0L

    fun begin(): Token = synchronized(lock) { Token(++generation) }

    fun invalidate() = synchronized(lock) {
        generation += 1
    }

    fun isCurrent(token: Token): Boolean = synchronized(lock) { token.generation == generation }

    suspend fun <T> resolve(token: Token, lookup: suspend () -> T): T? {
        val result = lookup()
        return result.takeIf { isCurrent(token) }
    }

    internal class Token internal constructor(internal val generation: Long)
}

internal data class PetMeasurementSaveRequest(
    val token: PetMeasurementCoordinator.OperationToken,
    val petId: PetId,
    val measuredAt: Instant,
    val firstWeightKg: Double,
    val secondWeightKg: Double,
)

internal class PetIngestionSession(
    val registerPetPacket: (String, ByteArray) -> Unit,
    val release: () -> Unit,
)

/** Android-free state machine for the two distinct stable readings used by pet weighing. */
internal class PetMeasurementCoordinator(
    private val setState: (PetMeasurementUiState) -> Unit,
    private val stopScanner: () -> Unit,
    private val restoreAutomaticScanning: () -> Unit,
    private val showMessage: (String) -> Unit,
    private val acquirePetSessionGate: suspend () -> PetIngestionSession = {
        PetIngestionSession(registerPetPacket = { _, _ -> }, release = {})
    },
    private val monotonicNowNanos: () -> Long,
) {
    private val lock = Any()
    private var nextOperationId = 0L
    private var starting = false
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

    suspend fun start(pet: Pet, selectedAddress: String): OperationToken? {
        synchronized(lock) {
            if (operation != null || starting) return null
            starting = true
        }
        val ingestionSession = try {
            acquirePetSessionGate()
        } catch (error: Throwable) {
            synchronized(lock) { starting = false }
            throw error
        }
        val token = synchronized(lock) {
            starting = false
            if (operation != null) {
                ingestionSession.release()
                return null
            }
            OperationToken(++nextOperationId, ingestionSession).also {
                operation = Operation(
                    token = it,
                    pet = pet,
                    selectedAddress = selectedAddress,
                    startedAtNanos = monotonicNowNanos(),
                    ingestionSession = ingestionSession,
                )
                setState(PetMeasurementUiState.AwaitingFirstWeight(pet))
            }
        }
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
                !reading.weightKg.isFinite() ||
                reading.weightKg <= 0.0 ||
                reading.receivedAtNanos < active.startedAtNanos ||
                !isSelectedScaleAddress(active.selectedAddress, reading.address)
            ) return null
            val identity = ReadingIdentity(reading.measuredAt, reading.rawIdentity)
            val first = active.first
            if (first == null) {
                active.first = CapturedReading(identity, reading.measuredAt, reading.weightKg)
                FirstAccepted(active.pet, reading.weightKg)
            } else {
                if (first.identity == identity) return null
                if (first.weightKg == reading.weightKg) return null
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
                    requireNotNull(active.takeStopScanner()),
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
                transition.stopScanner()
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

    fun registerPetPacket(token: OperationToken, address: String, payload: ByteArray) {
        token.ingestionSession.registerPetPacket(address, payload)
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

    /** Cancels only the operation that owns [token], ignoring stale coroutine callbacks. */
    fun cancel(token: OperationToken) = finish(token, PetMeasurementUiState.Cancelled)

    fun clear() {
        val token = synchronized(lock) { operation?.token }
        if (token != null) finish(token, PetMeasurementUiState.Idle)
    }

    private fun finish(
        token: OperationToken,
        terminalState: PetMeasurementUiState,
        message: String? = null,
    ) {
        val cleanup = synchronized(lock) {
            val active = operation?.takeIf { it.token == token } ?: return
            operation = null
            Cleanup(
                stopScanner = active.takeStopScanner(),
                cancelTimeout = active.cancelTimeout,
                releaseIngestionGate = active.ingestionSession.release,
            )
        }
        cleanup.stopScanner?.invoke()
        cleanup.cancelTimeout?.invoke()
        cleanup.releaseIngestionGate()
        restoreAutomaticScanning()
        setState(terminalState)
        message?.let(showMessage)
    }

    val isActive: Boolean
        get() = synchronized(lock) { starting || operation != null }

    internal class OperationToken internal constructor(
        internal val id: Long,
        internal val ingestionSession: PetIngestionSession,
    )

    private data class Operation(
        val token: OperationToken,
        val pet: Pet,
        val selectedAddress: String,
        val startedAtNanos: Long,
        val ingestionSession: PetIngestionSession,
        var first: CapturedReading? = null,
        var cancelTimeout: (() -> Unit)? = null,
        var saving: Boolean = false,
        var scannerStopped: Boolean = false,
    )

    private fun Operation.takeStopScanner(): (() -> Unit)? =
        if (scannerStopped) null else {
            scannerStopped = true
            { runCatching(stopScanner) }
        }

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
        val stopScanner: () -> Unit,
    ) : Transition

    private data class Cleanup(
        val stopScanner: (() -> Unit)?,
        val cancelTimeout: (() -> Unit)?,
        val releaseIngestionGate: () -> Unit,
    )
}
