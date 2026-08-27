package com.example.huaweimisync

import com.example.huaweimisync.core.MiScalePacketParser
import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetMeasurement
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
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

        assertNull(coordinator.accept(token, reading(70.0, second = 1, raw = "first")))
        coordinator.attachTimeout(token) { timeoutCancellations++ }
        val request = coordinator.accept(token, reading(74.2, second = 2, raw = "second"))

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
    fun `lower second weight is equally valid and delta stays absolute`() {
        val token = start()
        coordinator.accept(token, reading(74.2, second = 1, raw = "person-with-pet"))

        val request = coordinator.accept(token, reading(70.0, second = 2, raw = "person"))

        requireNotNull(request)
        assertEquals(4.2, abs(request.secondWeightKg - request.firstWeightKg), 0.000_001)
    }

    @Test
    fun `same timestamp and raw identity cannot become second reading`() {
        val token = start()
        val first = reading(70.0, second = 1, raw = "same-packet")
        coordinator.accept(token, first)

        assertNull(coordinator.accept(token, first.copy(weightKg = 74.0)))

        assertTrue(states.last() is PetMeasurementUiState.AwaitingSecondWeight)
        assertEquals(0, scannerStops)
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
    fun `reading received at operation start is accepted despite scale RTC skew`() {
        val token = start()

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

        assertEquals(PetMeasurementUiState.AwaitingSecondWeight(pet, 74.2), states.last())
        assertEquals(0, timeoutCancellations)
    }

    @Test
    fun `new stable value after pre-session baseline is accepted without zero transition`() =
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
        coordinator.accept(token, reading(70.0, second = 1, raw = "first"))
        coordinator.attachTimeout(token) { timeoutCancellations++ }

        assertNull(coordinator.accept(token, reading(70.0, second = 2, raw = "second")))

        assertEquals(PetMeasurementUiState.AwaitingSecondWeight(pet, 70.0), states.last())
        assertEquals(0, scannerStops)
        assertEquals(1, timeoutCancellations)

        val request = coordinator.accept(token, reading(72.0, second = 3, raw = "third"))
        requireNotNull(request)
        assertEquals(70.0, request.firstWeightKg, 0.0)
        assertEquals(72.0, request.secondWeightKg, 0.0)
    }

    @Test
    fun `invalid second readings keep first reading and continue waiting`() {
        val token = start()
        coordinator.accept(token, reading(70.0, second = 1, raw = "first"))

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
        coordinator.accept(token, reading(70.0, second = 1, raw = "first"))
        val request = requireNotNull(
            coordinator.accept(token, reading(72.0, second = 2, raw = "second")),
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
        assertNull(coordinator.accept(newToken, reading(70.0, second = 1, raw = "new-first")))
        assertEquals(PetMeasurementUiState.AwaitingSecondWeight(pet, 70.0), states.last())
    }

    @Test
    fun `stale save cancellation cannot cancel a later operation`() {
        val oldToken = start()
        coordinator.accept(oldToken, reading(70.0, second = 1, raw = "old-first"))
        requireNotNull(coordinator.accept(oldToken, reading(72.0, second = 2, raw = "old-second")))
        coordinator.fail(oldToken, "save failed")
        val newToken = start()

        coordinator.cancel(oldToken)

        assertTrue(coordinator.isActive)
        assertEquals(PetMeasurementUiState.AwaitingFirstWeight(pet), states.last())
        assertNull(coordinator.accept(newToken, reading(71.0, second = 3, raw = "new-first")))
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
        coordinator.accept(token, firstReading)
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
