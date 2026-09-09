package com.palixander.scalesync

import com.palixander.scalesync.core.MiScalePacketParser
import com.palixander.scalesync.core.RawScaleMeasurement
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetMeasurement
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetMeasurementCoordinatorTest {
    private val operationStartedAt = Instant.parse("2026-08-26T10:00:00Z")
    private val operationStartedAtNanos = 10_000L
    private val states = mutableListOf<PetMeasurementUiState>()
    private val messages = mutableListOf<String>()
    private var scannerStops = 0
    private var automaticRestores = 0
    private var timeoutCancellations = 0
    private val sessionActivity = mutableListOf<Boolean>()
    private val coordinator = PetMeasurementCoordinator(
        setState = states::add,
        stopScanner = { scannerStops++ },
        restoreAutomaticScanning = { automaticRestores++ },
        showMessage = messages::add,
        acquirePetSessionGate = {
            sessionActivity += true
            PetIngestionSession(
                registerPetPacket = { _, _ -> },
                release = { sessionActivity += false },
            )
        },
        monotonicNowNanos = { operationStartedAtNanos },
    )

    @Test
    fun `selection and creation are exposed without starting BLE`() {
        assertTrue(coordinator.showSelection())
        assertTrue(coordinator.showCreating())

        assertEquals(
            listOf(PetMeasurementUiState.SelectingPet, PetMeasurementUiState.CreatingPet),
            states,
        )
        assertFalse(coordinator.isActive)
        assertEquals(0, scannerStops)
    }

    @Test
    fun `two distinct stable weights produce save request`() {
        val token = start()
        coordinator.attachTimeout(token) { timeoutCancellations++ }

        assertNull(acceptAfterTransient(token, reading(70.0, second = 1, raw = "first")))
        coordinator.attachTimeout(token) { timeoutCancellations++ }
        val request = acceptAfterTransient(token, reading(74.2, second = 2, raw = "second"))

        requireNotNull(request)
        assertEquals(pet.id, request.petId)
        assertEquals(70.0, request.firstWeightKg, 0.0)
        assertEquals(74.2, request.secondWeightKg, 0.0)
        assertEquals(4.2, abs(request.secondWeightKg - request.firstWeightKg), 0.000_001)
        assertEquals(1, scannerStops)
        assertEquals(2, timeoutCancellations)
        assertTrue(states.last() is PetMeasurementUiState.Saving)
        assertEquals(0, automaticRestores)
    }

    @Test
    fun `stale stable replay cannot become first weight before a transient`() {
        val token = start()
        val stableReplay = reading(70.0, second = 1, raw = "stable-replay")

        assertNull(coordinator.accept(token, stableReplay))
        assertEquals(PetMeasurementUiState.AwaitingFirstWeight(pet), states.last())

        coordinator.accept(
            token,
            reading(69.5, second = 2, stable = false, raw = "fresh-transient"),
        )
        assertNull(
            coordinator.accept(
                token,
                stableReplay.copy(receivedAtNanos = operationStartedAtNanos + 3),
            ),
        )

        assertEquals(PetMeasurementUiState.AwaitingSecondWeight(pet, 70.0), states.last())
    }

    @Test
    fun `new transient is required between first and second stable weights`() {
        val token = start()
        acceptAfterTransient(token, reading(70.0, second = 2, raw = "person"))

        assertNull(
            coordinator.accept(
                token,
                reading(74.2, second = 3, raw = "person-with-pet-replay"),
            ),
        )
        assertEquals(PetMeasurementUiState.AwaitingSecondWeight(pet, 70.0), states.last())

        coordinator.accept(
            token,
            reading(73.8, second = 4, stable = false, raw = "second-transient"),
        )
        val request = coordinator.accept(
            token,
            reading(74.2, second = 5, raw = "person-with-pet"),
        )

        requireNotNull(request)
        assertEquals(70.0, request.firstWeightKg, 0.0)
        assertEquals(74.2, request.secondWeightKg, 0.0)
    }

    @Test
    fun `a later pet cycle does not inherit transient state from the completed cycle`() {
        val firstToken = start()
        acceptAfterTransient(firstToken, reading(74.2, second = 2, raw = "first-cycle-with-pet"))
        val firstRequest = requireNotNull(
            acceptAfterTransient(firstToken, reading(70.0, second = 4, raw = "first-cycle-person")),
        )
        coordinator.saved(
            firstToken,
            PetMeasurement(
                id = "first-cycle",
                petId = pet.id,
                measuredAt = firstRequest.measuredAt,
                firstWeightKg = firstRequest.firstWeightKg,
                secondWeightKg = firstRequest.secondWeightKg,
            ),
        )

        val secondToken = start()
        assertNull(coordinator.accept(secondToken, reading(70.0, second = 5, raw = "replayed-person")))
        assertEquals(PetMeasurementUiState.AwaitingFirstWeight(pet), states.last())

        acceptAfterTransient(secondToken, reading(70.0, second = 7, raw = "second-cycle-person"))
        val secondRequest = requireNotNull(
            acceptAfterTransient(
                secondToken,
                reading(74.2, second = 9, raw = "second-cycle-with-pet"),
            ),
        )
        assertEquals(70.0, secondRequest.firstWeightKg, 0.0)
        assertEquals(74.2, secondRequest.secondWeightKg, 0.0)
    }

    @Test
    fun `only coordinator accepted stable readings are protected`() = runBlocking {
        val protected = mutableListOf<Pair<String, String>>()
        val guardedCoordinator = PetMeasurementCoordinator(
            setState = {},
            stopScanner = {},
            restoreAutomaticScanning = {},
            showMessage = {},
            acquirePetSessionGate = {
                PetIngestionSession(
                    registerPetPacket = { _, _ -> },
                    release = {},
                    protectPetPacket = { address, raw -> protected += address to raw },
                )
            },
            monotonicNowNanos = { operationStartedAtNanos },
        )
        val token = requireNotNull(guardedCoordinator.start(pet, SELECTED_ADDRESS))

        guardedCoordinator.accept(token, reading(69.0, stable = false, raw = "unstable"))
        guardedCoordinator.accept(token, reading(70.0, raw = "first"))
        guardedCoordinator.accept(token, reading(69.0, second = 2, stable = false, raw = "unstable-2"))
        guardedCoordinator.accept(token, reading(70.0, second = 2, raw = "same-weight"))
        guardedCoordinator.accept(token, reading(73.0, second = 3, stable = false, raw = "unstable-3"))
        guardedCoordinator.accept(token, reading(74.0, second = 3, raw = "second"))

        assertEquals(
            listOf(
                SELECTED_ADDRESS.lowercase() to "first",
                SELECTED_ADDRESS.lowercase() to "second",
            ),
            protected,
        )
    }

    @Test
    fun `lower second weight is equally valid and delta stays absolute`() {
        val token = start()
        acceptAfterTransient(token, reading(74.2, second = 1, raw = "person-with-pet"))

        val request = acceptAfterTransient(token, reading(70.0, second = 2, raw = "person"))

        requireNotNull(request)
        assertEquals(4.2, abs(request.secondWeightKg - request.firstWeightKg), 0.000_001)
    }

    @Test
    fun `same timestamp and raw identity cannot become second reading`() {
        val token = start()
        val first = reading(70.0, second = 1, raw = "same-packet")
        acceptAfterTransient(token, first)
        coordinator.accept(token, reading(69.0, second = 2, stable = false, raw = "new-transient"))

        assertNull(coordinator.accept(token, first.copy(weightKg = 74.0)))

        assertNull(coordinator.accept(token, reading(74.0, second = 3, raw = "fresh-packet")))
        coordinator.accept(token, reading(73.0, second = 4, stable = false, raw = "rearm"))
        val request = coordinator.accept(token, reading(74.0, second = 5, raw = "fresh-packet"))

        requireNotNull(request)
        assertEquals(1, scannerStops)
    }

    @Test
    fun `wrong scale and unstable weights do not advance`() {
        val token = start()

        assertNull(coordinator.accept(token, reading(70.0, stable = false)))
        assertNull(coordinator.accept(token, reading(70.0, address = "11:22:33:44:55:66")))

        assertEquals(PetMeasurementUiState.AwaitingFirstWeight(pet), states.last())
    }

    @Test
    fun `reading received before operation start does not advance`() {
        val token = start()

        coordinator.accept(token, reading(69.0, stable = false))

        assertNull(
            coordinator.accept(
                token,
                reading(70.0, receivedAtNanos = operationStartedAtNanos - 1),
            ),
        )

        assertEquals(PetMeasurementUiState.AwaitingFirstWeight(pet), states.last())
        assertEquals(0, scannerStops)
    }

    @Test
    fun `reading received at operation start is accepted after transient despite scale RTC skew`() {
        val token = start()

        coordinator.accept(
            token,
            reading(69.0, stable = false, receivedAtNanos = operationStartedAtNanos),
        )

        coordinator.accept(
            token,
            reading(
                70.0,
                receivedAtNanos = operationStartedAtNanos,
                measuredAt = operationStartedAt.minusSeconds(3_600),
            ),
        )

        assertEquals(PetMeasurementUiState.AwaitingSecondWeight(pet, 70.0), states.last())
    }

    @Test
    fun `pre-session stable replay is ignored despite a new receive timestamp`() = runBlocking {
        val baseline = PetStableReadingBaseline(
            address = SELECTED_ADDRESS,
            weightKg = 70.0,
            rawIdentity = "durable-packet",
        )
        val baselineCoordinator = PetMeasurementCoordinator(
            setState = states::add,
            stopScanner = { scannerStops++ },
            restoreAutomaticScanning = { automaticRestores++ },
            showMessage = messages::add,
            acquirePetSessionGate = { selectedAddress ->
                assertEquals(SELECTED_ADDRESS, selectedAddress)
                PetIngestionSession(
                    registerPetPacket = { _, _ -> },
                    release = {},
                    preSessionBaseline = baseline,
                )
            },
            monotonicNowNanos = { operationStartedAtNanos },
        )
        val token = requireNotNull(baselineCoordinator.start(pet, SELECTED_ADDRESS))
        baselineCoordinator.attachTimeout(token) { timeoutCancellations++ }

        baselineCoordinator.accept(token, reading(69.0, stable = false, raw = "transient"))

        assertNull(
            baselineCoordinator.accept(
                token,
                reading(
                    70.0,
                    second = 20,
                    raw = "durable-packet",
                    measuredAt = operationStartedAt.plusSeconds(20),
                ),
            ),
        )

        assertEquals(PetMeasurementUiState.AwaitingFirstWeight(pet), states.last())
        assertEquals(0, scannerStops)
        assertEquals(0, timeoutCancellations)

        assertNull(
            baselineCoordinator.accept(
                token,
                reading(74.2, second = 21, raw = "new-stable-packet"),
            ),
        )

        assertEquals(PetMeasurementUiState.AwaitingFirstWeight(pet), states.last())
        baselineCoordinator.accept(token, reading(73.0, second = 22, stable = false))
        assertNull(
            baselineCoordinator.accept(
                token,
                reading(74.2, second = 23, raw = "new-stable-packet"),
            ),
        )

        assertEquals(PetMeasurementUiState.AwaitingSecondWeight(pet, 74.2), states.last())
        assertEquals(0, timeoutCancellations)
    }

    @Test
    fun `new stable value after pre-session baseline is accepted after transient`() =
        runBlocking {
            val baselineCoordinator = PetMeasurementCoordinator(
                setState = states::add,
                stopScanner = { scannerStops++ },
                restoreAutomaticScanning = { automaticRestores++ },
                showMessage = messages::add,
                acquirePetSessionGate = {
                    PetIngestionSession(
                        registerPetPacket = { _, _ -> },
                        release = {},
                        preSessionBaseline = PetStableReadingBaseline(
                            address = SELECTED_ADDRESS,
                            weightKg = 70.0,
                            rawIdentity = "durable-packet",
                        ),
                    )
                },
                monotonicNowNanos = { operationStartedAtNanos },
            )
            val token = requireNotNull(baselineCoordinator.start(pet, SELECTED_ADDRESS))

            baselineCoordinator.accept(
                token,
                reading(69.0, stable = false, raw = "transient"),
            )

            assertNull(
                baselineCoordinator.accept(
                    token,
                    reading(74.2, second = 1, raw = "new-stable-packet"),
                ),
            )

            assertEquals(PetMeasurementUiState.AwaitingSecondWeight(pet, 74.2), states.last())
            assertEquals(0, scannerStops)
        }

    @Test
    fun `non finite and non positive weights do not advance`() {
        val token = start()

        coordinator.accept(token, reading(69.0, stable = false))

        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)
            .forEachIndexed { index, weight ->
                assertNull(coordinator.accept(token, reading(weight, second = index.toLong())))
            }

        assertEquals(PetMeasurementUiState.AwaitingFirstWeight(pet), states.last())
        assertEquals(0, scannerStops)
    }

    @Test
    fun `distinct second packet with unchanged weight keeps waiting`() {
        val token = start()
        coordinator.attachTimeout(token) { timeoutCancellations++ }
        acceptAfterTransient(token, reading(70.0, second = 1, raw = "first"))
        coordinator.attachTimeout(token) { timeoutCancellations++ }

        coordinator.accept(token, reading(69.0, second = 2, stable = false, raw = "transient-2"))

        assertNull(coordinator.accept(token, reading(70.0, second = 2, raw = "second")))

        assertEquals(PetMeasurementUiState.AwaitingSecondWeight(pet, 70.0), states.last())
        assertEquals(0, scannerStops)
        assertEquals(1, timeoutCancellations)

        val request = coordinator.accept(token, reading(72.0, second = 3, raw = "third"))
        assertNull(request)
        coordinator.accept(token, reading(71.0, second = 4, stable = false, raw = "rearm"))
        val rearmedRequest = coordinator.accept(token, reading(72.0, second = 5, raw = "third"))
        requireNotNull(rearmedRequest)
        assertEquals(70.0, rearmedRequest.firstWeightKg, 0.0)
        assertEquals(72.0, rearmedRequest.secondWeightKg, 0.0)
    }

    @Test
    fun `stable invalid noise neither arms nor consumes transient permission`() {
        val token = start()

        assertNull(coordinator.accept(token, reading(0.0, second = 1, stable = true)))
        assertNull(coordinator.accept(token, reading(70.0, second = 2)))
        coordinator.accept(token, reading(69.0, second = 3, stable = false))
        assertNull(coordinator.accept(token, reading(301.0, second = 4, stable = true)))
        assertNull(coordinator.accept(token, reading(70.0, second = 5)))

        assertEquals(PetMeasurementUiState.AwaitingSecondWeight(pet, 70.0), states.last())
    }

    @Test
    fun `invalid second readings keep first reading and continue waiting`() {
        val token = start()
        acceptAfterTransient(token, reading(70.0, second = 1, raw = "first"))

        coordinator.accept(token, reading(69.0, second = 2, stable = false, raw = "transient-2"))

        assertNull(coordinator.accept(token, reading(Double.NaN, second = 2, raw = "nan")))
        assertNull(coordinator.accept(token, reading(0.0, second = 3, raw = "zero")))
        assertNull(
            coordinator.accept(
                token,
                reading(
                    75.0,
                    raw = "stale",
                    receivedAtNanos = operationStartedAtNanos - 1,
                ),
            ),
        )

        val request = coordinator.accept(token, reading(74.0, second = 4, raw = "valid"))
        requireNotNull(request)
        assertEquals(70.0, request.firstWeightKg, 0.0)
        assertEquals(74.0, request.secondWeightKg, 0.0)
    }

    @Test
    fun `successful persistence finishes session and restores automatic mode`() {
        val token = start()
        acceptAfterTransient(token, reading(70.0, second = 1, raw = "first"))
        val request = requireNotNull(
            acceptAfterTransient(token, reading(72.0, second = 2, raw = "second")),
        )
        val measurement = PetMeasurement(
            id = "measurement",
            petId = pet.id,
            measuredAt = request.measuredAt,
            firstWeightKg = request.firstWeightKg,
            secondWeightKg = request.secondWeightKg,
        )

        coordinator.saved(token, measurement)

        assertEquals(PetMeasurementUiState.Completed(pet, measurement), states.last())
        assertFalse(coordinator.isActive)
        assertEquals(1, scannerStops)
        assertEquals(1, automaticRestores)
        assertEquals(listOf(true, false), sessionActivity)
    }

    @Test
    fun `terminal operation token retains its session registrar for a late callback`() = runBlocking {
        val registered = mutableListOf<Pair<String, List<Byte>>>()
        val lateCoordinator = PetMeasurementCoordinator(
            setState = {},
            stopScanner = {},
            restoreAutomaticScanning = {},
            showMessage = {},
            acquirePetSessionGate = {
                PetIngestionSession(
                    registerPetPacket = { address, payload ->
                        registered += address to payload.toList()
                    },
                    release = {},
                )
            },
            monotonicNowNanos = { operationStartedAtNanos },
        )
        val token = requireNotNull(lateCoordinator.start(pet, "AA"))

        lateCoordinator.cancel(token)
        lateCoordinator.registerPetPacket(token, "AA", byteArrayOf(1, 2))

        assertEquals(listOf("AA" to listOf<Byte>(1, 2)), registered)
    }

    @Test
    fun `timeout error and cancel are terminal and restore once`() {
        val timeoutToken = start()
        coordinator.attachTimeout(timeoutToken) { timeoutCancellations++ }
        coordinator.timeout(timeoutToken)
        coordinator.timeout(timeoutToken)
        coordinator.fail(timeoutToken, "late error")
        coordinator.cancel(timeoutToken)

        assertEquals(PetMeasurementUiState.Error(PET_MEASUREMENT_TIMEOUT_MESSAGE), states.last())
        assertEquals(listOf(PET_MEASUREMENT_TIMEOUT_MESSAGE), messages)
        assertEquals(1, scannerStops)
        assertEquals(1, automaticRestores)
        assertEquals(1, timeoutCancellations)

        val cancelledToken = start()
        coordinator.attachTimeout(cancelledToken) { timeoutCancellations++ }
        coordinator.cancel(cancelledToken)
        coordinator.cancel(cancelledToken)
        coordinator.timeout(cancelledToken)
        coordinator.fail(cancelledToken, "late error")

        assertEquals(PetMeasurementUiState.Cancelled, states.last())
        assertEquals(2, scannerStops)
        assertEquals(2, automaticRestores)
        assertEquals(2, timeoutCancellations)
        assertEquals(listOf(PET_MEASUREMENT_TIMEOUT_MESSAGE), messages)
    }

    @Test
    fun `callbacks from terminal operation cannot affect a later operation`() {
        val oldToken = start()
        coordinator.cancel()
        val newToken = start()

        assertNull(coordinator.accept(oldToken, reading(70.0)))
        coordinator.timeout(oldToken)
        coordinator.fail(oldToken, "stale error")
        coordinator.saved(
            oldToken,
            PetMeasurement(
                id = "stale",
                petId = pet.id,
                measuredAt = operationStartedAt,
                firstWeightKg = 70.0,
                secondWeightKg = 72.0,
            ),
        )

        assertTrue(coordinator.isActive)
        assertEquals(PetMeasurementUiState.AwaitingFirstWeight(pet), states.last())
        assertTrue(messages.isEmpty())
        assertNull(acceptAfterTransient(newToken, reading(70.0, second = 1, raw = "new-first")))
        assertEquals(PetMeasurementUiState.AwaitingSecondWeight(pet, 70.0), states.last())
    }

    @Test
    fun `stale save cancellation cannot cancel a later operation`() {
        val oldToken = start()
        acceptAfterTransient(oldToken, reading(70.0, second = 1, raw = "old-first"))
        requireNotNull(acceptAfterTransient(oldToken, reading(72.0, second = 2, raw = "old-second")))
        coordinator.fail(oldToken, "save failed")
        val newToken = start()

        coordinator.cancel(oldToken)

        assertTrue(coordinator.isActive)
        assertEquals(PetMeasurementUiState.AwaitingFirstWeight(pet), states.last())
        assertNull(acceptAfterTransient(newToken, reading(71.0, second = 3, raw = "new-first")))
        assertEquals(PetMeasurementUiState.AwaitingSecondWeight(pet, 71.0), states.last())
        assertEquals(1, scannerStops)
        assertEquals(1, automaticRestores)
        assertEquals(listOf(true, false, true), sessionActivity)
    }

    @Test
    fun `timeout contract is thirty seconds`() {
        assertEquals(30_000L, PET_MEASUREMENT_TIMEOUT_MILLIS)
    }

    @Test
    fun `clear deactivates ingestion gate with and without active operation`() {
        coordinator.clear()
        val token = start()
        coordinator.clear()

        assertEquals(listOf(true, false), sessionActivity)
        assertFalse(coordinator.isActive)
        assertNull(coordinator.accept(token, reading(70.0)))
    }

    @Test
    fun `canonical parser payload defines duplicate identity regardless of prefix`() {
        val canonical = validPayload()
        val parser = MiScalePacketParser(java.time.ZoneOffset.UTC)
        val first = requireNotNull(parser.parse(byteArrayOf(0x01) + canonical, SELECTED_ADDRESS))
        val second = requireNotNull(
            parser.parse(byteArrayOf(0x7f, 0x55) + canonical, SELECTED_ADDRESS),
        )

        assertEquals(
            petReadingRawIdentity(first.rawPayload),
            petReadingRawIdentity(second.rawPayload),
        )
        val token = start()
        val firstReading = reading(70.0, raw = petReadingRawIdentity(first.rawPayload))
        acceptAfterTransient(token, firstReading)
        coordinator.accept(token, reading(69.0, second = 2, stable = false, raw = "new-transient"))
        assertNull(
            coordinator.accept(
                token,
                firstReading.copy(
                    weightKg = 74.0,
                    rawIdentity = petReadingRawIdentity(second.rawPayload),
                ),
            ),
        )
    }

    @Test
    fun `invalidated suspended lookup cannot complete startup`() = runBlocking {
        val guard = PetMeasurementStartupGuard()
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val token = guard.begin()
        val resolved = async {
            guard.resolve(token) {
                lookupStarted.complete(Unit)
                releaseLookup.await()
                pet
            }
        }

        lookupStarted.await()
        guard.invalidate()
        releaseLookup.complete(Unit)

        assertNull(resolved.await())
        assertFalse(guard.isCurrent(token))
    }

    @Test
    fun `cancel before pet creation completes suppresses late success`() {
        val guard = PetMeasurementCreationGuard()
        val token = requireNotNull(guard.begin())

        guard.invalidate()

        assertFalse(guard.complete(token))
    }

    @Test
    fun `cancel before pet creation completes suppresses late error`() {
        val guard = PetMeasurementCreationGuard()
        val token = requireNotNull(guard.begin())

        guard.invalidate()

        assertFalse(guard.complete(token))
    }

    @Test
    fun `new pet creation session supersedes cancelled session`() {
        val guard = PetMeasurementCreationGuard()
        val cancelled = requireNotNull(guard.begin())
        guard.invalidate()
        val current = requireNotNull(guard.begin())

        assertFalse(guard.complete(cancelled))
        assertTrue(guard.complete(current))
    }

    @Test
    fun `concurrent start is rejected while ingestion gate activation is suspended`() = runBlocking {
        val gateEntered = CompletableDeferred<Unit>()
        val allowGate = CompletableDeferred<Unit>()
        val guardedCoordinator = PetMeasurementCoordinator(
            setState = states::add,
            stopScanner = { scannerStops++ },
            restoreAutomaticScanning = { automaticRestores++ },
            showMessage = messages::add,
            acquirePetSessionGate = {
                gateEntered.complete(Unit)
                allowGate.await()
                PetIngestionSession(registerPetPacket = { _, _ -> }, release = {})
            },
            monotonicNowNanos = { operationStartedAtNanos },
        )
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            guardedCoordinator.start(pet, SELECTED_ADDRESS)
        }
        gateEntered.await()

        assertTrue(guardedCoordinator.isActive)
        assertNull(guardedCoordinator.start(pet, SELECTED_ADDRESS))

        allowGate.complete(Unit)
        requireNotNull(first.await())
        guardedCoordinator.cancel()
    }

    @Test
    fun `cancelled gate activation clears startup reservation`() = runBlocking {
        val gateEntered = CompletableDeferred<Unit>()
        val neverActivate = CompletableDeferred<Unit>()
        val guardedCoordinator = PetMeasurementCoordinator(
            setState = states::add,
            stopScanner = { scannerStops++ },
            restoreAutomaticScanning = { automaticRestores++ },
            showMessage = messages::add,
            acquirePetSessionGate = {
                gateEntered.complete(Unit)
                neverActivate.await()
                PetIngestionSession(registerPetPacket = { _, _ -> }, release = {})
            },
            monotonicNowNanos = { operationStartedAtNanos },
        )
        val startup = async(start = CoroutineStart.UNDISPATCHED) {
            guardedCoordinator.start(pet, SELECTED_ADDRESS)
        }
        gateEntered.await()

        startup.cancelAndJoin()

        assertFalse(guardedCoordinator.isActive)
        assertEquals(0, scannerStops)
        assertEquals(0, automaticRestores)
    }

    @Test
    fun `startup activates and drains gate before baseline lookup`() = runBlocking {
        val order = mutableListOf<String>()
        val session = acquirePetIngestionSession(
            selectedAddress = SELECTED_ADDRESS,
            activateGate = {
                order += "gate-drained"
                PetIngestionSession(registerPetPacket = { _, _ -> }, release = {})
            },
            lookupBaseline = {
                order += "baseline"
                null
            },
        )

        assertEquals(listOf("gate-drained", "baseline"), order)
        session.release()
    }

    @Test
    fun `baseline lookup failure releases activated gate`() = runBlocking {
        var releases = 0

        val failure = runCatching {
            acquirePetIngestionSession(
                selectedAddress = SELECTED_ADDRESS,
                activateGate = {
                    PetIngestionSession(
                        registerPetPacket = { _, _ -> },
                        release = { releases++ },
                    )
                },
                lookupBaseline = { error("lookup failed") },
            )
        }.exceptionOrNull()

        assertEquals("lookup failed", failure?.message)
        assertEquals(1, releases)
    }

    @Test
    fun `cancellation during baseline lookup releases activated gate`() = runBlocking {
        val lookupStarted = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<PetStableReadingBaseline?>()
        var releases = 0
        val startup = async(start = CoroutineStart.UNDISPATCHED) {
            acquirePetIngestionSession(
                selectedAddress = SELECTED_ADDRESS,
                activateGate = {
                    PetIngestionSession(
                        registerPetPacket = { _, _ -> },
                        release = { releases++ },
                    )
                },
                lookupBaseline = {
                    lookupStarted.complete(Unit)
                    neverComplete.await()
                },
            )
        }
        lookupStarted.await()

        startup.cancelAndJoin()

        assertEquals(1, releases)
    }

    private fun start(): PetMeasurementCoordinator.OperationToken =
        runBlocking { requireNotNull(coordinator.start(pet, SELECTED_ADDRESS)) }

    private fun acceptAfterTransient(
        token: PetMeasurementCoordinator.OperationToken,
        stableReading: PetScaleReading,
    ): PetMeasurementSaveRequest? {
        coordinator.accept(
            token,
            stableReading.copy(
                receivedAtNanos = maxOf(operationStartedAtNanos, stableReading.receivedAtNanos - 1),
                measuredAt = stableReading.measuredAt.minusNanos(1),
                isStable = false,
                isStableWeight = false,
                rawIdentity = "transient-before-${stableReading.rawIdentity}",
            ),
        )
        return coordinator.accept(token, stableReading)
    }

    private fun reading(
        weightKg: Double,
        second: Long = 1,
        raw: String = "raw-$second",
        stable: Boolean = true,
        address: String = SELECTED_ADDRESS.lowercase(),
        receivedAtNanos: Long = operationStartedAtNanos + second,
        measuredAt: Instant = operationStartedAt.plusSeconds(second),
    ) = PetScaleReading(
        address = address,
        receivedAtNanos = receivedAtNanos,
        measuredAt = measuredAt,
        weightKg = weightKg,
        rawWeight = if (weightKg.isFinite()) {
            (weightKg / RawScaleMeasurement.WEIGHT_RESOLUTION_KG).roundToInt()
        } else {
            0
        },
        isStable = stable,
        isStableWeight = stable,
        rawIdentity = raw,
    )

    companion object {
        private const val SELECTED_ADDRESS = "AA:BB:CC:DD:EE:FF"
        private val pet = Pet(
            id = PetId("pet"),
            displayName = "Барсик",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

        private fun validPayload() = byteArrayOf(
            0x00, 0x20, 0xea.toByte(), 0x07, 0x08, 0x1a, 0x0c, 0x22, 0x38,
            0xf4.toByte(), 0x01, 0xb0.toByte(), 0x36,
        )
    }
}
