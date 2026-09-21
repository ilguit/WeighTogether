package com.palixander.scalesync

import com.palixander.scalesync.core.RawScaleMeasurement
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetMeasurement
import java.time.Instant
import kotlin.math.abs

internal const val PET_MEASUREMENT_TIMEOUT_MILLIS = 30_000L
internal const val PET_SCALE_REQUIRED_MESSAGE = "Сначала выберите весы в настройках"
internal const val PET_BLUETOOTH_PERMISSION_MESSAGE = "Разрешите Bluetooth для взвешивания питомца"
internal const val PET_MEASUREMENT_TIMEOUT_MESSAGE = "Весы не передали новое стабильное измерение"

internal enum class PetProfileMeasurementStartRoute {
    MANUAL_WEIGHT,
    BLE,
}

internal fun petProfileMeasurementStartRoute(scaleAddress: String?): PetProfileMeasurementStartRoute =
    if (scaleAddress == null) {
        PetProfileMeasurementStartRoute.MANUAL_WEIGHT
    } else {
        PetProfileMeasurementStartRoute.BLE
    }

internal fun startPetProfileMeasurement(
    pet: Pet,
    scaleAddress: String?,
    dismissPetMeasurement: () -> Unit,
    openManualWeight: (Pet) -> Unit,
    startBleMeasurement: (PetId) -> Unit,
) {
    when (petProfileMeasurementStartRoute(scaleAddress)) {
        PetProfileMeasurementStartRoute.MANUAL_WEIGHT -> {
            dismissPetMeasurement()
            openManualWeight(pet)
        }
        PetProfileMeasurementStartRoute.BLE -> startBleMeasurement(pet.id)
    }
}

sealed interface PetMeasurementUiState {
    data object Idle : PetMeasurementUiState
    data object SelectingPet : PetMeasurementUiState
    data object CreatingPet : PetMeasurementUiState

    data class AwaitingFirstWeight(
        val pet: Pet,
        val currentWeightKg: Double? = null,
    ) : PetMeasurementUiState

    data class AwaitingSecondWeight(
        val pet: Pet,
        val firstWeightKg: Double,
        val currentWeightKg: Double? = null,
    ) : PetMeasurementUiState

    data class Result(
        val pet: Pet,
        val measuredAt: Instant,
        val firstWeightKg: Double,
        val secondWeightKg: Double,
        val previousPetWeightKg: Double?,
    ) : PetMeasurementUiState {
        val petWeightKg: Double get() = abs(secondWeightKg - firstWeightKg)
    }

    data class Saving(
        val pet: Pet,
        val firstWeightKg: Double,
        val secondWeightKg: Double,
    ) : PetMeasurementUiState

    data class ConnectionError(
        val pet: Pet,
        val firstWeightKg: Double?,
        val message: String,
    ) : PetMeasurementUiState

    data class Error(val message: String) : PetMeasurementUiState
    data object Cancelled : PetMeasurementUiState
}

internal data class PetScaleReading(
    val address: String,
    val receivedAtNanos: Long,
    val measuredAt: Instant,
    val weightKg: Double,
    val rawWeight: Int,
    /** Raw scale stability bit, kept separate from the validated stable-weight predicate. */
    val isStable: Boolean,
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

/** Prevents a delayed pet creation result from reviving an invalidated measurement flow. */
internal class PetMeasurementCreationGuard {
    private val lock = Any()
    private var generation = 0L
    private var active = false

    fun begin(): Token? = synchronized(lock) {
        if (active) return@synchronized null
        active = true
        Token(++generation)
    }

    fun invalidate() = synchronized(lock) {
        generation += 1
        active = false
    }

    fun complete(token: Token): Boolean = synchronized(lock) {
        if (!active || token.generation != generation) return@synchronized false
        active = false
        true
    }

    internal class Token internal constructor(internal val generation: Long)
}

internal data class PetMeasurementSaveRequest(
    val token: PetMeasurementCoordinator.OperationToken,
    val petId: PetId,
    val measuredAt: Instant,
    val firstWeightKg: Double,
    val secondWeightKg: Double,
) {
    val petWeightKg: Double get() = abs(secondWeightKg - firstWeightKg)
}

internal data class PetMeasurementRetryRequest(
    val token: PetMeasurementCoordinator.OperationToken,
    val selectedAddress: String,
)

internal class PetIngestionSession(
    val registerPetPacket: (String, ByteArray) -> Unit,
    val release: () -> Unit,
    val protectPetPacket: (String, String) -> Unit = { _, _ -> },
    val protectPetReading: (String, String, Instant, Int) -> Unit = { _, _, _, _ -> },
    val preSessionBaseline: PetStableReadingBaseline? = null,
)

/** Closes ingestion before reading its durable baseline, eliminating the startup TOCTOU window. */
internal suspend fun acquirePetIngestionSession(
    selectedAddress: String,
    activateGate: suspend () -> PetIngestionSession,
    lookupBaseline: suspend (String) -> PetStableReadingBaseline?,
): PetIngestionSession {
    val activeSession = activateGate()
    return try {
        PetIngestionSession(
            registerPetPacket = activeSession.registerPetPacket,
            release = activeSession.release,
            protectPetPacket = activeSession.protectPetPacket,
            protectPetReading = activeSession.protectPetReading,
            preSessionBaseline = lookupBaseline(selectedAddress),
        )
    } catch (error: Throwable) {
        activeSession.release()
        throw error
    }
}

internal data class PetStableReadingBaseline(
    val address: String,
    val weightKg: Double,
    val rawIdentity: String,
)

/** Android-free state machine for the two distinct stable readings used by pet weighing. */
internal class PetMeasurementCoordinator(
    private val setState: (PetMeasurementUiState) -> Unit,
    private val stopScanner: () -> Unit,
    private val restoreAutomaticScanning: () -> Unit,
    private val showMessage: (String) -> Unit,
    private val acquirePetSessionGate: suspend (String) -> PetIngestionSession = {
        PetIngestionSession(registerPetPacket = { _, _ -> }, release = {})
    },
    private val monotonicNowNanos: () -> Long,
    private val beforeStartLockAttempt: () -> Unit = {},
) {
    private val lock = Any()
    private var nextOperationId = 0L
    private var startupReservation: Long? = null
    private var finishingReservation: Long? = null
    private var operation: Operation? = null

    fun showSelection(): Boolean = synchronized(lock) {
        if (operation != null || finishingReservation != null) return false
        setState(PetMeasurementUiState.SelectingPet)
        true
    }

    fun showCreating(): Boolean = synchronized(lock) {
        if (operation != null || finishingReservation != null) return false
        setState(PetMeasurementUiState.CreatingPet)
        true
    }

    suspend fun start(
        pet: Pet,
        selectedAddress: String,
        previousPetWeightKg: Double? = null,
    ): OperationToken? {
        beforeStartLockAttempt()
        val reservation = synchronized(lock) {
            if (operation != null || startupReservation != null || finishingReservation != null) return null
            (++nextOperationId).also { startupReservation = it }
        }
        val ingestionSession = try {
            acquirePetSessionGate(selectedAddress)
        } catch (error: Throwable) {
            synchronized(lock) {
                if (startupReservation == reservation) startupReservation = null
            }
            throw error
        }
        val token = synchronized(lock) {
            if (startupReservation != reservation || operation != null) {
                null
            } else {
                startupReservation = null
                OperationToken(reservation, ingestionSession).also {
                    operation = Operation(
                        token = it,
                        pet = pet,
                        selectedAddress = selectedAddress,
                        startedAtNanos = monotonicNowNanos(),
                        ingestionSession = ingestionSession,
                        preSessionBaseline = ingestionSession.preSessionBaseline,
                        previousPetWeightKg = previousPetWeightKg,
                    )
                    setState(PetMeasurementUiState.AwaitingFirstWeight(pet))
                }
            }
        }
        if (token == null) ingestionSession.release()
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
            if (!reading.weightKg.isFinite() ||
                reading.weightKg !in RawScaleMeasurement.MIN_WEIGHT_KG..RawScaleMeasurement.MAX_WEIGHT_KG ||
                reading.receivedAtNanos < active.startedAtNanos ||
                !isSelectedScaleAddress(active.selectedAddress, reading.address)
            ) return null
            if (!reading.isStable) {
                active.transientSeenSinceLastStable = true
                setState(
                    active.first?.let {
                        PetMeasurementUiState.AwaitingSecondWeight(active.pet, it.weightKg, reading.weightKg)
                    } ?: PetMeasurementUiState.AwaitingFirstWeight(active.pet, reading.weightKg),
                )
                return null
            }
            if (!reading.isStableWeight || !active.transientSeenSinceLastStable) return null
            // Transient permission is one-shot: every valid stable candidate consumes it,
            // including candidates later rejected as baseline, duplicate, or same-weight.
            active.transientSeenSinceLastStable = false
            val identity = ReadingIdentity(reading.measuredAt, reading.rawIdentity)
            val first = active.first
            if (first == null) {
                if (active.preSessionBaseline?.matches(reading) == true) return null
                active.ingestionSession.protectPetPacket(reading.address, reading.rawIdentity)
                active.ingestionSession.protectPetReading(
                    reading.address,
                    reading.rawIdentity,
                    reading.measuredAt,
                    reading.rawWeight,
                )
                active.first = CapturedReading(identity, reading.measuredAt, reading.weightKg)
                FirstAccepted(active.pet, reading.weightKg)
            } else {
                if (first.identity == identity) return null
                if (first.weightKg == reading.weightKg) return null
                active.ingestionSession.protectPetPacket(reading.address, reading.rawIdentity)
                active.ingestionSession.protectPetReading(
                    reading.address,
                    reading.rawIdentity,
                    reading.measuredAt,
                    reading.rawWeight,
                )
                active.cancelTimeout?.invoke()
                active.cancelTimeout = null
                val request = PetMeasurementSaveRequest(
                        token = token,
                        petId = active.pet.id,
                        measuredAt = reading.measuredAt,
                        firstWeightKg = first.weightKg,
                        secondWeightKg = reading.weightKg,
                    )
                active.secondRequest = request
                SecondAccepted(
                    request,
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
                val previous = synchronized(lock) {
                    operation?.takeIf { it.token == transition.request.token }?.previousPetWeightKg
                }
                setState(
                    PetMeasurementUiState.Result(
                        transition.pet,
                        transition.request.measuredAt,
                        transition.request.firstWeightKg,
                        transition.request.secondWeightKg,
                        previous,
                    ),
                )
                transition.request
            }
        }
    }

    fun beginSave(): PetMeasurementSaveRequest? = synchronized(lock) {
        val active = operation ?: return null
        val first = active.first ?: return null
        val second = active.secondRequest ?: return null
        if (active.saving) return null
        active.saving = true
        setState(PetMeasurementUiState.Saving(active.pet, first.weightKg, second.secondWeightKg))
        second
    }

    fun registerPetPacket(token: OperationToken, address: String, payload: ByteArray) {
        token.ingestionSession.registerPetPacket(address, payload)
    }

    fun pause(token: OperationToken, message: String) {
        val errorState = synchronized(lock) {
            val active = operation?.takeIf { it.token == token && !it.saving } ?: return
            active.cancelTimeout?.invoke()
            active.cancelTimeout = null
            PetMeasurementUiState.ConnectionError(active.pet, active.first?.weightKg, message)
        }
        setState(errorState)
        showMessage(message)
    }

    fun retry(): PetMeasurementRetryRequest? = synchronized(lock) {
        val active = operation?.takeIf { !it.saving } ?: return null
        active.startedAtNanos = monotonicNowNanos()
        active.transientSeenSinceLastStable = false
        setState(
            active.first?.let { PetMeasurementUiState.AwaitingSecondWeight(active.pet, it.weightKg) }
                ?: PetMeasurementUiState.AwaitingFirstWeight(active.pet),
        )
        PetMeasurementRetryRequest(active.token, active.selectedAddress)
    }

    fun saved(token: OperationToken, measurement: PetMeasurement) {
        synchronized(lock) { operation?.takeIf { it.token == token } } ?: return
        finish(token, PetMeasurementUiState.Idle)
    }

    fun fail(token: OperationToken, message: String) =
        finish(token, PetMeasurementUiState.Error(message), message)

    fun timeout(token: OperationToken) =
        finish(token, PetMeasurementUiState.Error(PET_MEASUREMENT_TIMEOUT_MESSAGE), PET_MEASUREMENT_TIMEOUT_MESSAGE)

    fun cancel() {
        val token = synchronized(lock) {
            startupReservation = null
            val activeToken = operation?.token
            if (activeToken == null && finishingReservation == null) {
                setState(PetMeasurementUiState.Idle)
            }
            activeToken
        }
        if (token != null) finish(token, PetMeasurementUiState.Cancelled)
    }

    /** Cancels only the operation that owns [token], ignoring stale coroutine callbacks. */
    fun cancel(token: OperationToken) = finish(token, PetMeasurementUiState.Cancelled)

    fun clear() {
        val token = synchronized(lock) {
            startupReservation = null
            operation?.token
        }
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
            finishingReservation = token.id
            Cleanup(
                stopScanner = active.takeStopScanner(),
                cancelTimeout = active.cancelTimeout,
                releaseIngestionGate = active.ingestionSession.release,
            )
        }
        try {
            cleanup.stopScanner?.invoke()
            cleanup.cancelTimeout?.invoke()
            cleanup.releaseIngestionGate()
            restoreAutomaticScanning()
            setState(terminalState)
            message?.let(showMessage)
        } finally {
            synchronized(lock) {
                if (finishingReservation == token.id) finishingReservation = null
            }
        }
    }

    val isActive: Boolean
        get() = synchronized(lock) {
            startupReservation != null || operation != null || finishingReservation != null
        }

    internal class OperationToken internal constructor(
        internal val id: Long,
        internal val ingestionSession: PetIngestionSession,
    )

    private data class Operation(
        val token: OperationToken,
        val pet: Pet,
        val selectedAddress: String,
        var startedAtNanos: Long,
        val ingestionSession: PetIngestionSession,
        val preSessionBaseline: PetStableReadingBaseline?,
        val previousPetWeightKg: Double?,
        var first: CapturedReading? = null,
        var secondRequest: PetMeasurementSaveRequest? = null,
        var transientSeenSinceLastStable: Boolean = false,
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

    private fun PetStableReadingBaseline.matches(reading: PetScaleReading): Boolean =
        isSelectedScaleAddress(address, reading.address) &&
            weightKg == reading.weightKg &&
            rawIdentity == reading.rawIdentity
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
