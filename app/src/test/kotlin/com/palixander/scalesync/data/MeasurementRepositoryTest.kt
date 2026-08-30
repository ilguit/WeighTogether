package com.palixander.scalesync.data

import com.palixander.scalesync.core.BodyCompositionCalculator
import com.palixander.scalesync.core.RawScaleMeasurement
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.core.UserProfile
import com.palixander.scalesync.domain.ExternalSyncPolicy
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.worker.ExternalSyncOperationSerializer
import com.palixander.scalesync.worker.ExternalSyncPauseCoordinator
import com.palixander.scalesync.worker.MeasurementSyncScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementRepositoryTest {
    private val profile = UserProfile(175.0, LocalDate.of(1990, 1, 1), Sex.MALE)
    private val raw = RawScaleMeasurement(
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAt = Instant.parse("2026-08-11T12:34:56Z"),
        weightKg = 70.0,
        impedanceOhm = 500,
        isStable = true,
        hasImpedance = true,
        rawPayload = ByteArray(13),
    )

    @Test
    fun preliminaryCompositionRequiresFullPacketAndCompleteProfile() {
        val calculator = BodyCompositionCalculator(ZoneId.of("UTC"))
        val pending = PendingMeasurement(
            id = PendingMeasurementId("preview"),
            deviceAddress = raw.deviceAddress,
            measuredAt = raw.measuredAt,
            weightKg = raw.weightKg,
            impedanceOhm = raw.impedanceOhm,
            isStable = raw.isStable,
            hasImpedance = raw.hasImpedance,
            rawPayload = raw.rawPayload,
            deduplicationHash = "preview-hash",
            enqueuedAt = raw.measuredAt,
        )
        val complete = AccountProfile.Complete(profile.heightCm, profile.birthDate, profile.sex)

        assertTrue(calculatePreliminaryComposition(pending, complete, calculator) != null)
        assertNull(
            calculatePreliminaryComposition(
                pending.copy(hasImpedance = false, impedanceOhm = 0),
                complete,
                calculator,
            ),
        )
        assertNull(
            calculatePreliminaryComposition(
                pending,
                AccountProfile.IncompleteRecovery(heightCm = 175.0),
                calculator,
            ),
        )
    }

    @Test
    fun repeatedPacketIsStoredAndScheduledOnlyOnce() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        val first = repository.store(raw)
        val second = repository.store(raw.copy(rawPayload = ByteArray(13) { 1 }))

        assertTrue(first is StoreResult.Inserted)
        assertEquals(StoreResult.Duplicate, second)
        assertEquals(1, dao.values.size)
        assertEquals(listOf(dao.values.keys.single()), scheduler.enqueued)
        assertEquals(scheduler.enqueued, scheduler.initiallyEnqueued)
        assertTrue(scheduler.cancelled.isEmpty())
        val stored = dao.values.values.single()
        assertEquals(SyncStatus.DISABLED.name, stored.huaweiStatus)
        assertEquals(profile.heightCm, requireNotNull(stored.ratingHeightCm), 0.0)
        assertEquals(RatingHeightOrigin.CAPTURED, stored.ratingHeightOrigin)
    }

    @Test
    fun stableWeightWithoutImpedanceIsStoredWithoutProfileOrCalculatedValues() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = MeasurementRepository(
            dao = dao,
            profileProvider = { null },
            calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
            syncScheduler = scheduler,
            huaweiSyncEnabled = false,
        )
        val partial = raw.copy(impedanceOhm = 0, hasImpedance = false)

        val first = repository.store(partial)
        val repeated = repository.store(partial.copy(rawPayload = ByteArray(13) { 1 }))

        assertTrue(first is StoreResult.Inserted)
        assertEquals(StoreResult.Duplicate, repeated)
        val stored = dao.values.values.single()
        assertEquals(MeasurementType.WEIGHT_ONLY, stored.measurementType)
        assertEquals(70.0, stored.weightKg, 0.0)
        assertNull(stored.impedanceOhm)
        assertNull(stored.fullValues)
        assertNull(stored.ratingHeightCm)
        assertEquals(RatingHeightOrigin.CAPTURED, stored.ratingHeightOrigin)
        assertEquals(listOf(stored.id), scheduler.enqueued)
    }

    @Test
    fun unstableAndOutOfRangeWeightsAreRejected() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        assertEquals(StoreResult.Rejected, repository.store(raw.copy(isStable = false)))
        assertEquals(StoreResult.Rejected, repository.store(raw.copy(weightKg = 9.0, rawWeight = 1_800)))
        assertTrue(dao.values.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun fullPacketUpgradesWeightOnlyRowAndRequeuesSyncedDestinations() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler, huaweiSyncEnabled = true)
        val partial = raw.copy(impedanceOhm = 0, hasImpedance = false)

        val inserted = repository.store(partial) as StoreResult.Inserted
        dao.values[inserted.value.id] = inserted.value.copy(
            huaweiStatus = SyncStatus.SYNCED.name,
            healthConnectStatus = SyncStatus.SYNCED.name,
            huaweiWeightSynced = true,
            healthConnectWeightSynced = true,
        )
        val result = repository.store(raw)

        assertTrue(result is StoreResult.Upgraded)
        assertEquals(1, dao.values.size)
        val upgraded = dao.values.values.single()
        assertEquals(inserted.value.id, upgraded.id)
        assertEquals(MeasurementType.FULL, upgraded.measurementType)
        assertEquals(500, upgraded.impedanceOhm)
        assertTrue(upgraded.fullValues != null)
        assertEquals(SyncStatus.PENDING.name, upgraded.huaweiStatus)
        assertEquals(SyncStatus.PENDING.name, upgraded.healthConnectStatus)
        assertTrue(upgraded.huaweiWeightSynced)
        assertTrue(upgraded.healthConnectWeightSynced)
        assertEquals(listOf(upgraded.id, upgraded.id), scheduler.enqueued)
    }

    @Test
    fun fullPacketUpgradePreservesLocalOnlyDirectionsWithoutSchedulingAgain() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler, huaweiSyncEnabled = true)
        val partial = raw.copy(impedanceOhm = 0, hasImpedance = false)

        val inserted = repository.store(partial) as StoreResult.Inserted
        dao.values[inserted.value.id] = inserted.value.copy(
            huaweiStatus = SyncStatus.LOCAL_ONLY.name,
            healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
            huaweiWeightSynced = true,
            healthConnectWeightSynced = true,
        )
        val result = repository.store(raw)

        assertTrue(result is StoreResult.Upgraded)
        val upgraded = dao.values.getValue(inserted.value.id)
        assertEquals(MeasurementType.FULL, upgraded.measurementType)
        assertEquals(SyncStatus.LOCAL_ONLY.name, upgraded.huaweiStatus)
        assertEquals(SyncStatus.LOCAL_ONLY.name, upgraded.healthConnectStatus)
        assertTrue(upgraded.huaweiWeightSynced)
        assertTrue(upgraded.healthConnectWeightSynced)
        assertEquals(listOf(inserted.value.id), scheduler.enqueued)
    }

    @Test
    fun laterWeightOnlyPacketCannotDowngradeFullRow() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        val inserted = repository.store(raw) as StoreResult.Inserted
        val result = repository.store(raw.copy(impedanceOhm = 0, hasImpedance = false))

        assertEquals(StoreResult.Duplicate, result)
        assertEquals(1, dao.values.size)
        assertEquals(MeasurementType.FULL, dao.values.getValue(inserted.value.id).measurementType)
        assertEquals(listOf(inserted.value.id), scheduler.enqueued)
    }

    @Test
    fun missingProfileDoesNotInsertOrSchedule() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = MeasurementRepository(
            dao = dao,
            profileProvider = { null },
            calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
            syncScheduler = scheduler,
            huaweiSyncEnabled = false,
        )

        assertEquals(StoreResult.ProfileMissing, repository.store(raw))
        assertTrue(dao.values.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun pendingDestinationsAreRequeuedAfterPermissionGrant() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["health-1"] = measurement(
            id = "health-1",
            measuredAt = 1L,
            huaweiStatus = SyncStatus.DISABLED,
            healthConnectStatus = SyncStatus.PENDING,
        )
        dao.values["health-2"] = measurement(
            id = "health-2",
            measuredAt = 2L,
            huaweiStatus = SyncStatus.SYNCED,
            healthConnectStatus = SyncStatus.BLOCKED,
        )
        dao.values["huawei-1"] = measurement(
            id = "huawei-1",
            measuredAt = 3L,
            huaweiStatus = SyncStatus.PENDING,
            healthConnectStatus = SyncStatus.SYNCED,
        )
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        repository.retryPendingHealthConnect()
        repository.retryPendingHuawei()

        assertEquals(listOf("health-1", "health-2", "huawei-1"), scheduler.enqueued)
    }

    @Test
    fun updateChangesAllValuesButPreservesIdentityDateAndProvenance() = runBlocking {
        val dao = FakeMeasurementDao()
        val original = measurement(
            id = "edited",
            measuredAt = 123_456L,
            huaweiStatus = SyncStatus.DISABLED,
            healthConnectStatus = SyncStatus.FAILED,
        ).copy(
            fingerprint = "immutable-fingerprint",
            deviceAddress = "immutable-device",
            rawPayloadHex = "immutable-payload",
            algorithmVersion = "immutable-algorithm",
            huaweiError = "old huawei error",
            healthConnectError = "old health error",
            huaweiWeightSynced = true,
            healthConnectWeightSynced = true,
            createdAtEpochMillis = 654_321L,
        )
        dao.values[original.id] = original
        val values = editedValues()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        val result = repository.update(original.id, values)

        assertEquals(MeasurementMutationResult.Success, result)
        assertEquals(values, dao.values.getValue(original.id).values)
        assertEquals(
            original.copy(
                weightKg = values.weightKg,
                impedanceOhm = values.impedanceOhm,
                bmi = values.bmi,
                bodyFatPercent = values.bodyFatPercent,
                bodyFatMassKg = values.bodyFatMassKg,
                waterPercent = values.waterPercent,
                waterMassKg = values.waterMassKg,
                muscleMassKg = values.muscleMassKg,
                skeletalMuscleMassKg = values.skeletalMuscleMassKg,
                boneMassKg = values.boneMassKg,
                proteinPercent = values.proteinPercent,
                proteinMassKg = values.proteinMassKg,
                visceralFatLevel = values.visceralFatLevel,
                basalMetabolicRateKcal = values.basalMetabolicRateKcal,
                metabolicAge = values.metabolicAge,
                leanBodyMassKg = values.leanBodyMassKg,
                huaweiStatus = SyncStatus.DISABLED.name,
                healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
                huaweiError = null,
                healthConnectError = null,
                externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
            ),
            dao.values.getValue(original.id),
        )
        assertEquals(listOf(original.id), scheduler.cancelled)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun updateKeepsExistingLocalOnlyEditSemanticsForAvailableDirections() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["edited"] = measurement(
            id = "edited",
            huaweiStatus = SyncStatus.SYNCED,
            healthConnectStatus = SyncStatus.SYNCED,
        )
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        assertEquals(
            MeasurementMutationResult.Success,
            repository.update("edited", editedValues()),
        )

        assertEquals(SyncStatus.LOCAL_ONLY.name, dao.values.getValue("edited").huaweiStatus)
        assertEquals(SyncStatus.LOCAL_ONLY.name, dao.values.getValue("edited").healthConnectStatus)
        assertEquals(listOf("edited"), scheduler.cancelled)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun weightOnlyUpdateStaysLocalAndPreservesIdentityCompositionAndProviderHistory() = runBlocking {
        val dao = FakeMeasurementDao()
        val original = measurement(
            id = "weight-only",
            huaweiStatus = SyncStatus.SYNCED,
            healthConnectStatus = SyncStatus.FAILED,
        ).copy(
            fingerprint = "immutable-weight-fingerprint",
            rawWeight = 14_001,
            measurementType = MeasurementType.WEIGHT_ONLY,
            impedanceOhm = null,
            bmi = null,
            bodyFatPercent = null,
            bodyFatMassKg = null,
            waterPercent = null,
            waterMassKg = null,
            muscleMassKg = null,
            skeletalMuscleMassKg = null,
            boneMassKg = null,
            proteinPercent = null,
            proteinMassKg = null,
            visceralFatLevel = null,
            basalMetabolicRateKcal = null,
            metabolicAge = null,
            leanBodyMassKg = null,
            algorithmVersion = null,
            huaweiWeightSynced = true,
            healthConnectWeightSynced = true,
        )
        dao.values[original.id] = original
        val scheduler = FakeSyncScheduler()

        val result = repository(dao, scheduler, huaweiSyncEnabled = true)
            .updateWeightOnly(original.id, 69.25)

        assertEquals(MeasurementMutationResult.Success, result)
        val updated = dao.values.getValue(original.id)
        assertEquals(69.25, updated.weightKg, 0.0)
        assertEquals(14_001, updated.rawWeight)
        assertEquals(MeasurementType.WEIGHT_ONLY, updated.measurementType)
        assertEquals(original.fingerprint, updated.fingerprint)
        assertNull(updated.fullValues)
        assertTrue(updated.huaweiWeightSynced)
        assertTrue(updated.healthConnectWeightSynced)
        assertEquals(SyncStatus.LOCAL_ONLY.name, updated.huaweiStatus)
        assertEquals(SyncStatus.LOCAL_ONLY.name, updated.healthConnectStatus)
        assertEquals(ExternalSyncPolicy.USER_LOCAL.name, updated.externalSyncPolicy)
        assertEquals(listOf(original.id), scheduler.cancelled)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun invalidValuesAreRejectedWithoutChangingOrCancelling() = runBlocking {
        val original = measurement(id = "edited")
        val invalidValues = listOf(
            editedValues().copy(weightKg = Double.NaN),
            editedValues().copy(bmi = Double.POSITIVE_INFINITY),
            editedValues().copy(bodyFatMassKg = -0.01),
            editedValues().copy(bodyFatPercent = 100.01),
            editedValues().copy(waterPercent = 100.01),
            editedValues().copy(proteinPercent = 100.01),
            editedValues().copy(impedanceOhm = -1),
            editedValues().copy(metabolicAge = -1),
        )

        invalidValues.forEach { invalid ->
            val dao = FakeMeasurementDao().also { it.values[original.id] = original }
            val scheduler = FakeSyncScheduler()
            val result = repository(dao, scheduler).update(original.id, invalid)

            assertEquals(MeasurementMutationResult.Invalid, result)
            assertEquals(original, dao.values.getValue(original.id))
            assertTrue(scheduler.cancelled.isEmpty())
            assertTrue(scheduler.enqueued.isEmpty())
        }
    }

    @Test
    fun missingUpdateAndDeleteDoNotChangeStateOrCancel() = runBlocking {
        val dao = FakeMeasurementDao()
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        assertEquals(
            MeasurementMutationResult.NotFound,
            repository.update("missing", editedValues()),
        )
        assertEquals(MeasurementMutationResult.NotFound, repository.delete("missing"))
        assertTrue(dao.values.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun deleteMarksLocalOnlyThenCancelsThenDeletesWithoutEnqueueing() = runBlocking {
        val events = mutableListOf<String>()
        val dao = FakeMeasurementDao(events)
        dao.values["deleted"] = measurement(id = "deleted")
        val scheduler = FakeSyncScheduler(onCancel = { id -> events += "cancel:$id" })
        val repository = repository(dao, scheduler)

        assertEquals(MeasurementMutationResult.Success, repository.delete("deleted"))

        assertEquals(
            listOf("mark-local-only:deleted", "cancel:deleted", "delete:deleted"),
            events,
        )
        assertTrue(dao.values.isEmpty())
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun deleteDuringPauseDoesNotReturnDeletedRowsToQueueAndPreservesOthers() = runBlocking {
        val dao = FakeMeasurementDao().also {
            it.values["delete-one"] = measurement(id = "delete-one")
            it.values["keep"] = measurement(id = "keep", measuredAt = 2_000L)
            it.values["delete-two"] = measurement(id = "delete-two", measuredAt = 3_000L)
        }
        val scheduler = FakeSyncScheduler()
        val operations = ExternalSyncOperationSerializer()
        val repository = repository(dao, scheduler, operations = operations)
        val pause = ExternalSyncPauseCoordinator(
            settings = FakePauseSettings(),
            currentSyncIds = dao::idsNeedingSync,
            scheduler = scheduler,
            operations = operations,
        )

        pause.pause()
        repository.delete("delete-one")
        repository.delete("delete-two")
        scheduler.rescheduled.clear()
        pause.resume()

        assertEquals(setOf("keep"), dao.values.keys)
        assertEquals(
            listOf("delete-one", "keep", "delete-two", "delete-one", "delete-two"),
            scheduler.cancelled,
        )
        assertEquals(listOf("keep" to 0L), scheduler.rescheduled)
    }

    @Test
    fun concurrentResumeCannotLeaveDeletedIdRequeued() = runBlocking {
        val events = mutableListOf<String>()
        val dao = FakeMeasurementDao().also { it.values["deleted"] = measurement(id = "deleted") }
        val scheduler = FakeSyncScheduler(
            onCancel = { id -> events += "cancel:$id" },
            onReschedule = { id, _ -> events += "reschedule:$id" },
        )
        val operations = ExternalSyncOperationSerializer()
        val repository = repository(dao, scheduler, operations = operations)
        val idsReadStarted = CompletableDeferred<Unit>()
        val allowIdsRead = CompletableDeferred<Unit>()
        val pause = ExternalSyncPauseCoordinator(
            settings = FakePauseSettings(true),
            currentSyncIds = {
                idsReadStarted.complete(Unit)
                allowIdsRead.await()
                dao.idsNeedingSync()
            },
            scheduler = scheduler,
            operations = operations,
        )

        val resume = async { pause.resume() }
        idsReadStarted.await()
        val deletion = async { repository.delete("deleted") }
        allowIdsRead.complete(Unit)
        resume.await()
        deletion.await()

        assertTrue("deleted" !in dao.values)
        assertEquals(listOf("reschedule:deleted", "cancel:deleted"), events)
    }

    @Test
    fun latestMeasurementFromCurrentlyLinkedScaleDeletesNormally() =
        runBlocking {
            val events = mutableListOf<String>()
            val dao = FakeMeasurementDao(events)
            val protected = measurement(
                id = "protected",
                measuredAt = 2_000L,
                huaweiStatus = SyncStatus.FAILED,
                healthConnectStatus = SyncStatus.PENDING,
            ).copy(
                huaweiError = "keep huawei error",
                healthConnectError = "keep health error",
            )
            dao.values[protected.id] = protected
            dao.values["newer-manual"] = measurement(
                id = "newer-manual",
                measuredAt = 3_000L,
            ).copy(deviceAddress = "manual")
            val scheduler = FakeSyncScheduler()
            val repository = repository(
                dao = dao,
                scheduler = scheduler,
            )

            assertEquals(MeasurementMutationResult.Success, repository.delete(protected.id))

            assertTrue(protected.id !in dao.values)
            assertEquals(listOf("mark-local-only:protected", "delete:protected"), events)
            assertEquals(listOf(protected.id), scheduler.cancelled)
            assertTrue(scheduler.enqueued.isEmpty())
        }

    @Test
    fun previousMeasurementFromLinkedScaleDeletesNormally() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["previous"] = measurement(id = "previous", measuredAt = 1_000L)
        dao.values["latest"] = measurement(id = "latest", measuredAt = 2_000L)
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        assertEquals(MeasurementMutationResult.Success, repository.delete("previous"))

        assertTrue("previous" !in dao.values)
        assertEquals(listOf("previous"), scheduler.cancelled)
    }

    @Test
    fun manualAndDifferentScaleMeasurementsDeleteNormally() = runBlocking {
        val deletable = listOf(
            measurement(id = "manual", measuredAt = 3_000L).copy(deviceAddress = "manual"),
            measurement(id = "old-scale", measuredAt = 4_000L).copy(
                deviceAddress = "11:22:33:44:55:66",
            ),
        )

        deletable.forEach { measurement ->
            val dao = FakeMeasurementDao().also { it.values[measurement.id] = measurement }
            val scheduler = FakeSyncScheduler()
            val repository = repository(
                dao = dao,
                scheduler = scheduler,
            )

            assertEquals(
                MeasurementMutationResult.Success,
                repository.delete(measurement.id),
            )
            assertTrue(dao.values.isEmpty())
            assertEquals(listOf(measurement.id), scheduler.cancelled)
        }
    }

    @Test
    fun soleScaleMeasurementDeletesNormally() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["previous"] = measurement(id = "previous", measuredAt = 1_000L)
        val scheduler = FakeSyncScheduler()
        val repository = repository(
            dao = dao,
            scheduler = scheduler,
        )

        assertEquals(MeasurementMutationResult.Success, repository.delete("previous"))
        assertEquals(listOf("previous"), scheduler.cancelled)
    }

    @Test
    fun localOnlyMeasurementCannotBeRetriedOrReturnedToQueue() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["local"] = measurement(
            id = "local",
            huaweiStatus = SyncStatus.LOCAL_ONLY,
            healthConnectStatus = SyncStatus.LOCAL_ONLY,
        )
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        repository.retry("local")
        repository.retryPendingHealthConnect()
        repository.retryPendingHuawei()

        assertTrue(scheduler.enqueued.isEmpty())
        assertTrue(dao.idsNeedingSync().isEmpty())
    }

    @Test
    fun accountAndUserLocalPoliciesCannotBeRetriedEvenWithInconsistentPendingStatuses() =
        runBlocking {
            listOf(ExternalSyncPolicy.ACCOUNT_LOCAL, ExternalSyncPolicy.USER_LOCAL).forEach { policy ->
                val dao = FakeMeasurementDao()
                dao.values[policy.name] = measurement(id = policy.name).copy(
                    externalSyncPolicy = policy.name,
                )
                val scheduler = FakeSyncScheduler()
                val repository = repository(dao, scheduler)

                repository.retry(policy.name)
                repository.retryPendingHealthConnect()
                repository.retryPendingHuawei()
                repository.sweepPendingSync()

                assertTrue("Scheduled $policy", scheduler.enqueued.isEmpty())
            }
        }

    @Test
    fun localOnlyDirectionDoesNotBlockRetryForOtherAvailableDirection() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["mixed"] = measurement(
            id = "mixed",
            huaweiStatus = SyncStatus.LOCAL_ONLY,
            healthConnectStatus = SyncStatus.FAILED,
        )
        dao.values["opposite"] = measurement(
            id = "opposite",
            measuredAt = 2_000L,
            huaweiStatus = SyncStatus.FAILED,
            healthConnectStatus = SyncStatus.LOCAL_ONLY,
        )
        val scheduler = FakeSyncScheduler()
        val repository = repository(dao, scheduler)

        repository.retry("mixed")
        repository.retryPendingHealthConnect()
        repository.retryPendingHuawei()

        assertEquals(listOf("mixed", "mixed", "opposite"), scheduler.enqueued)
        assertEquals(listOf("mixed"), scheduler.immediatelyEnqueued)
        assertEquals(listOf("mixed", "opposite"), dao.idsNeedingSync())
    }

    @Test
    fun sweepPreservesTerminalImportedStatusesAndQueuesOnlyRetryableRows() = runBlocking {
        val dao = FakeMeasurementDao().also {
            it.values["terminal"] = measurement(
                id = "terminal",
                huaweiStatus = SyncStatus.DISABLED,
                healthConnectStatus = SyncStatus.SYNCED,
            )
            it.values["local"] = measurement(
                id = "local",
                measuredAt = 2_000L,
                huaweiStatus = SyncStatus.LOCAL_ONLY,
                healthConnectStatus = SyncStatus.LOCAL_ONLY,
            )
            it.values["retry"] = measurement(
                id = "retry",
                measuredAt = 3_000L,
                huaweiStatus = SyncStatus.SYNCED,
                healthConnectStatus = SyncStatus.FAILED,
            )
        }
        val scheduler = FakeSyncScheduler()

        assertEquals(1, repository(dao, scheduler).sweepPendingSync())

        assertEquals(listOf("retry"), scheduler.enqueued)
        assertEquals(SyncStatus.DISABLED.name, dao.values.getValue("terminal").huaweiStatus)
        assertEquals(SyncStatus.SYNCED.name, dao.values.getValue("terminal").healthConnectStatus)
        assertEquals(SyncStatus.LOCAL_ONLY.name, dao.values.getValue("local").huaweiStatus)
        assertEquals(SyncStatus.LOCAL_ONLY.name, dao.values.getValue("local").healthConnectStatus)
    }

    @Test
    fun observeAllIsDescendingAndRangeIsHalfOpenAscending() = runBlocking {
        val dao = FakeMeasurementDao()
        listOf(9_000L, 10_000L, 15_000L, 20_000L, 21_000L).forEach { timestamp ->
            val value = measurement(id = timestamp.toString(), measuredAt = timestamp)
            dao.values[value.id] = value
        }
        val repository = repository(dao, FakeSyncScheduler())

        assertEquals(
            listOf(21_000L, 20_000L, 15_000L, 10_000L, 9_000L),
            repository.observeAll().first().map(MeasurementEntity::measuredAtEpochMillis),
        )
        assertEquals(
            listOf(10_000L, 15_000L),
            repository.observeRange(Instant.ofEpochSecond(10L), Instant.ofEpochSecond(20L))
                .first()
                .map(MeasurementEntity::measuredAtEpochMillis),
        )
    }

    @Test
    fun localOnlyStatusCannotBeOverwrittenByWorkerUpdate() = runBlocking {
        val dao = FakeMeasurementDao()
        dao.values["local"] = measurement(
            id = "local",
            huaweiStatus = SyncStatus.LOCAL_ONLY,
            healthConnectStatus = SyncStatus.LOCAL_ONLY,
        )

        assertEquals(
            0,
            dao.applyHuaweiSyncResult(
                "local",
                MeasurementType.FULL.name,
                SyncStatus.SYNCED.name,
                null,
                true,
            ),
        )
        assertEquals(
            0,
            dao.applyHealthConnectSyncResult(
                "local",
                MeasurementType.FULL.name,
                SyncStatus.SYNCED.name,
                null,
                true,
            ),
        )
        assertEquals(SyncStatus.LOCAL_ONLY.name, dao.values.getValue("local").huaweiStatus)
        assertEquals(
            SyncStatus.LOCAL_ONLY.name,
            dao.values.getValue("local").healthConnectStatus,
        )
    }

    private fun repository(
        dao: MeasurementDao,
        scheduler: MeasurementSyncScheduler,
        huaweiSyncEnabled: Boolean = false,
        operations: ExternalSyncOperationSerializer = ExternalSyncOperationSerializer(),
    ) = MeasurementRepository(
        dao = dao,
        profileProvider = { profile },
        calculator = BodyCompositionCalculator(ZoneId.of("UTC")),
        syncScheduler = scheduler,
        huaweiSyncEnabled = huaweiSyncEnabled,
        externalSyncOperations = operations,
    )
}

private class FakeSyncScheduler(
    private val onCancel: (String) -> Unit = {},
    private val onReschedule: (String, Long) -> Unit = { _, _ -> },
) : MeasurementSyncScheduler {
    val enqueued = mutableListOf<String>()
    val initiallyEnqueued = mutableListOf<String>()
    val immediatelyEnqueued = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val rescheduled = mutableListOf<Pair<String, Long>>()

    override fun enqueue(measurementId: String) {
        enqueued += measurementId
    }

    override fun enqueueInitial(measurementId: String) {
        initiallyEnqueued += measurementId
        enqueue(measurementId)
    }

    override fun enqueueImmediately(measurementId: String) {
        immediatelyEnqueued += measurementId
        enqueue(measurementId)
    }

    override fun deferCurrent(measurementId: String, notBeforeEpochMillis: Long) = Unit

    override fun cancel(measurementId: String) {
        cancelled += measurementId
        onCancel(measurementId)
    }

    override fun reschedule(measurementId: String, notBeforeEpochMillis: Long) {
        rescheduled += measurementId to notBeforeEpochMillis
        onReschedule(measurementId, notBeforeEpochMillis)
    }
}

private class FakePauseSettings(
    initialPaused: Boolean = false,
) : ExternalSyncPauseSettingsStore {
    private var paused = initialPaused
    override val externalSyncPaused: Boolean get() = paused

    override fun setExternalSyncPaused(value: Boolean) {
        paused = value
    }
}

private class FakeMeasurementDao(
    private val events: MutableList<String> = mutableListOf(),
) : MeasurementDao {
    val values = linkedMapOf<String, MeasurementEntity>()

    override suspend fun insert(measurement: MeasurementEntity): Long {
        if (values.containsKey(measurement.id)) return -1L
        values[measurement.id] = measurement
        return values.size.toLong()
    }

    override suspend fun insertAll(measurements: List<MeasurementEntity>): List<Long> =
        measurements.map { insert(it) }

    override suspend fun deleteAll(): Int {
        val count = values.size
        values.clear()
        return count
    }

    override suspend fun get(id: String): MeasurementEntity? = values[id]

    override suspend fun getByFingerprint(fingerprint: String): MeasurementEntity? =
        values.values.firstOrNull { it.fingerprint == fingerprint }

    override suspend fun getLatestForDevice(deviceAddress: String): MeasurementEntity? = values.values
        .filter { it.deviceAddress.equals(deviceAddress, ignoreCase = true) }
        .maxWithOrNull(
            compareBy<MeasurementEntity>(MeasurementEntity::measuredAtEpochSecond)
                .thenBy(MeasurementEntity::createdAtEpochMillis)
                .thenBy(MeasurementEntity::id),
        )

    override fun observeLatest(limit: Int): Flow<List<MeasurementEntity>> = flowOf(
        values.values.sortedByDescending(MeasurementEntity::measuredAtEpochSecond).take(limit),
    )

    override fun observeAll(): Flow<List<MeasurementEntity>> = flowOf(
        values.values.sortedByDescending(MeasurementEntity::measuredAtEpochSecond),
    )

    override suspend fun getAllForBackup(): List<MeasurementEntity> = values.values
        .sortedWith(
            compareBy<MeasurementEntity>(MeasurementEntity::measuredAtEpochSecond)
                .thenBy(MeasurementEntity::createdAtEpochMillis)
                .thenBy(MeasurementEntity::id),
        )

    override fun observeRange(
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<MeasurementEntity>> = flowOf(
        values.values
            .filter { it.measuredAtEpochSecond >= startInclusive }
            .filter { it.measuredAtEpochSecond < endExclusive }
            .sortedBy(MeasurementEntity::measuredAtEpochSecond),
    )

    override suspend fun update(measurement: MeasurementEntity): Int {
        if (!values.containsKey(measurement.id)) return 0
        values[measurement.id] = measurement
        return 1
    }

    override suspend fun markLocalOnly(id: String): Int {
        val value = values[id] ?: return 0
        events += "mark-local-only:$id"
        values[id] = value.copy(
            huaweiStatus = if (value.huaweiStatus == SyncStatus.DISABLED.name) {
                SyncStatus.DISABLED.name
            } else {
                SyncStatus.LOCAL_ONLY.name
            },
            healthConnectStatus = SyncStatus.LOCAL_ONLY.name,
            huaweiError = null,
            healthConnectError = null,
            externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
        )
        return 1
    }

    override suspend fun delete(id: String): Int {
        if (!values.containsKey(id)) return 0
        events += "delete:$id"
        values.remove(id)
        return 1
    }

    override suspend fun applyHuaweiSyncResult(
        id: String,
        expectedMeasurementType: String,
        status: String,
        error: String?,
        markWeightSynced: Boolean,
        syncedCalculatedValues: String?,
    ): Int {
        val value = values[id] ?: return 0
        if (value.externalSyncPolicy != ExternalSyncPolicy.AUTO.name ||
            value.huaweiStatus == SyncStatus.LOCAL_ONLY.name
        ) return 0
        values[id] = value.copy(
            huaweiStatus = if (value.measurementType.name == expectedMeasurementType) {
                status
            } else {
                value.huaweiStatus
            },
            huaweiError = if (value.measurementType.name == expectedMeasurementType) {
                error
            } else {
                value.huaweiError
            },
            huaweiWeightSynced = value.huaweiWeightSynced || markWeightSynced,
            huaweiSyncedCalculatedValues = if (
                value.measurementType.name == expectedMeasurementType &&
                syncedCalculatedValues != null
            ) {
                syncedCalculatedValues
            } else {
                value.huaweiSyncedCalculatedValues
            },
        )
        return 1
    }

    override suspend fun applyHealthConnectSyncResult(
        id: String,
        expectedMeasurementType: String,
        status: String,
        error: String?,
        markWeightSynced: Boolean,
        syncedCalculatedValues: String?,
    ): Int {
        val value = values[id] ?: return 0
        if (value.externalSyncPolicy != ExternalSyncPolicy.AUTO.name ||
            value.healthConnectStatus == SyncStatus.LOCAL_ONLY.name
        ) return 0
        values[id] = value.copy(
            healthConnectStatus = if (value.measurementType.name == expectedMeasurementType) {
                status
            } else {
                value.healthConnectStatus
            },
            healthConnectError = if (value.measurementType.name == expectedMeasurementType) {
                error
            } else {
                value.healthConnectError
            },
            healthConnectWeightSynced = value.healthConnectWeightSynced || markWeightSynced,
            healthConnectSyncedCalculatedValues = if (
                value.measurementType.name == expectedMeasurementType &&
                syncedCalculatedValues != null
            ) {
                syncedCalculatedValues
            } else {
                value.healthConnectSyncedCalculatedValues
            },
        )
        return 1
    }

    override suspend fun idsNeedingSync(): List<String> = values.values
        .filter { it.externalSyncPolicy == ExternalSyncPolicy.AUTO.name }
        .filter {
            it.huaweiStatus !in setOf(
                SyncStatus.SYNCED.name,
                SyncStatus.DISABLED.name,
                SyncStatus.LOCAL_ONLY.name,
            ) || it.healthConnectStatus !in setOf(
                SyncStatus.SYNCED.name,
                SyncStatus.LOCAL_ONLY.name,
            )
        }
        .sortedBy(MeasurementEntity::measuredAtEpochSecond)
        .map(MeasurementEntity::id)

    override suspend fun idsNeedingHealthConnectSync(): List<String> = values.values
        .filter { it.externalSyncPolicy == ExternalSyncPolicy.AUTO.name }
        .filter { it.healthConnectStatus !in setOf(SyncStatus.SYNCED.name, SyncStatus.LOCAL_ONLY.name) }
        .sortedBy(MeasurementEntity::measuredAtEpochSecond)
        .map(MeasurementEntity::id)

    override suspend fun idsNeedingHuaweiSync(): List<String> = values.values
        .filter { it.externalSyncPolicy == ExternalSyncPolicy.AUTO.name }
        .filter {
            it.huaweiStatus !in setOf(
                SyncStatus.SYNCED.name,
                SyncStatus.DISABLED.name,
                SyncStatus.LOCAL_ONLY.name,
            )
        }
        .sortedBy(MeasurementEntity::measuredAtEpochSecond)
        .map(MeasurementEntity::id)
}

private fun editedValues() = MeasurementValues(
    weightKg = 81.25,
    impedanceOhm = 612,
    bmi = 26.5,
    bodyFatPercent = 25.5,
    bodyFatMassKg = 20.7,
    waterPercent = 52.1,
    waterMassKg = 42.3,
    muscleMassKg = 35.4,
    skeletalMuscleMassKg = 18.6,
    boneMassKg = 3.2,
    proteinPercent = 17.4,
    proteinMassKg = 13.8,
    visceralFatLevel = 9.0,
    basalMetabolicRateKcal = 1_602.0,
    metabolicAge = 41,
    leanBodyMassKg = 60.55,
)

private fun measurement(
    id: String,
    measuredAt: Long = 1_000L,
    huaweiStatus: SyncStatus = SyncStatus.PENDING,
    healthConnectStatus: SyncStatus = SyncStatus.PENDING,
) = MeasurementEntity(
    id = id,
    deviceAddress = "AA:BB:CC:DD:EE:FF",
    measuredAtEpochSecond = Math.floorDiv(measuredAt, 1_000L),
    rawPayloadHex = "010203",
    weightKg = 70.0,
    impedanceOhm = 500,
    bmi = 22.9,
    bodyFatPercent = 20.0,
    bodyFatMassKg = 14.0,
    waterPercent = 55.0,
    waterMassKg = 38.5,
    muscleMassKg = 40.0,
    skeletalMuscleMassKg = 20.0,
    boneMassKg = 3.0,
    proteinPercent = 18.0,
    proteinMassKg = 12.6,
    visceralFatLevel = 7.0,
    basalMetabolicRateKcal = 1_500.0,
    metabolicAge = 35,
    leanBodyMassKg = 56.0,
    algorithmVersion = "test-v1",
    huaweiStatus = huaweiStatus.name,
    healthConnectStatus = healthConnectStatus.name,
    createdAtEpochMillis = 2_000L,
)
