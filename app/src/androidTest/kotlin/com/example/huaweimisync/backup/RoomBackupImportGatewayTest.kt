package com.example.huaweimisync.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.huaweimisync.data.AccountEntity
import com.example.huaweimisync.data.AppDatabase
import com.example.huaweimisync.data.AppStateEntity
import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementTombstoneEntity
import com.example.huaweimisync.data.MeasurementType
import com.example.huaweimisync.data.PendingMeasurementEntity
import com.example.huaweimisync.data.PortableProfileSettings
import com.example.huaweimisync.data.SyncStatus
import com.example.huaweimisync.domain.ExternalSyncPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBackupImportGatewayTest {
    private lateinit var database: AppDatabase
    private lateinit var gateway: RoomBackupImportGateway

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).build()
        gateway = RoomBackupImportGateway(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun mergeSkipsExactRowsAndPreservesImportedSyncState() = runBlocking {
        val account = account("a")
        val measurement = measurement("m", "a", SyncStatus.SYNCED)
        database.accountDao().insert(account)
        database.measurementDao().insert(measurement)

        apply(preview(BackupImportMode.MERGE, listOf(account, account("b")),
            listOf(measurement, measurement("n", "b", SyncStatus.FAILED))))

        assertEquals(listOf("a", "b"), database.accountDao().getAll().map { it.id })
        val rows = database.measurementDao().getAllForBackup()
        assertEquals(2, rows.size)
        assertEquals(SyncStatus.SYNCED.name, rows.single { it.id == "m" }.huaweiStatus)
        assertEquals(SyncStatus.FAILED.name, rows.single { it.id == "n" }.huaweiStatus)
    }

    @Test
    fun replaceClearsTransientTablesAndRollsBackWholeReplacementOnForeignKeyFailure() = runBlocking {
        val original = account("old")
        database.accountDao().insert(original)
        database.measurementDao().insert(measurement("old-m", "old", SyncStatus.LOCAL_ONLY))
        database.pendingMeasurementDao().insert(pending("pending"))
        database.pendingMeasurementDao().upsertTombstone(
            MeasurementTombstoneEntity("hash", 10_000L),
        )

        apply(preview(BackupImportMode.REPLACE, listOf(account("new")),
            listOf(measurement("new-m", "new", SyncStatus.SYNCED))))
        assertEquals(listOf("new"), database.accountDao().getAll().map { it.id })
        assertEquals(0, database.pendingMeasurementDao().getAll().size)
        assertEquals(0, database.pendingMeasurementDao().tombstoneCount())

        assertThrows(Exception::class.java) {
            runBlocking {
                gateway.stage(
                    preview(
                        BackupImportMode.REPLACE,
                        listOf(account("broken")),
                        listOf(measurement("orphan", "missing", SyncStatus.SYNCED)),
                    ),
                    PortableProfileSettings(null, null, false, null, null),
                )
            }
        }
        assertEquals(listOf("new"), database.accountDao().getAll().map { it.id })
        assertEquals(listOf("new-m"), database.measurementDao().getAllForBackup().map { it.id })
        assertEquals(null, gateway.pendingRecoverySettings())
    }

    @Test
    fun rollbackRestoresAllChangedTablesAndLeavesRecoverablePreviousSettings() = runBlocking {
        database.accountDao().insert(account("old"))
        database.measurementDao().insert(measurement("old-m", "old", SyncStatus.LOCAL_ONLY))
        database.pendingMeasurementDao().insert(pending("pending"))
        database.pendingMeasurementDao().upsertTombstone(MeasurementTombstoneEntity("hash", 10_000L))
        val previous = PortableProfileSettings("OLD", "Old", false, setOf("weight"), null)

        gateway.stage(
            preview(
                BackupImportMode.REPLACE,
                listOf(account("new")),
                listOf(measurement("new-m", "new", SyncStatus.SYNCED)),
            ),
            previous,
        )
        gateway.beginRollback()

        assertEquals(listOf("old"), database.accountDao().getAll().map { it.id })
        assertEquals(listOf("old-m"), database.measurementDao().getAllForBackup().map { it.id })
        assertEquals(listOf("pending"), database.pendingMeasurementDao().getAll().map { it.id })
        assertEquals(listOf("hash"), database.pendingMeasurementDao().getAllTombstones().map { it.deduplicationHash })
        assertEquals(previous, gateway.pendingRecoverySettings())
        gateway.complete()
        assertEquals(null, gateway.pendingRecoverySettings())
    }

    @Test
    fun stagedImportLeavesTargetSettingsForStartupRollForward() = runBlocking {
        val target = preview(
            BackupImportMode.MERGE,
            listOf(account("new")),
            listOf(measurement("new-m", "new", SyncStatus.SYNCED)),
        )

        gateway.stage(target, PortableProfileSettings(null, null, false, null, null))

        assertEquals(target.settings, gateway.pendingRecoverySettings())
        assertEquals(listOf("new"), database.accountDao().getAll().map { it.id })
    }

    private suspend fun apply(preview: BackupImportPreview) {
        gateway.stage(preview, PortableProfileSettings(null, null, false, null, null))
        gateway.complete()
    }

    private fun preview(mode: BackupImportMode, accounts: List<AccountEntity>, measurements: List<MeasurementEntity>) =
        BackupImportPreview(mode, BackupImportCounts(0, 0, 0, 0, 0, 0),
            BackupDatabaseSnapshot(accounts, AppStateEntity(primaryAccountId = accounts.first().id), measurements),
            PortableProfileSettings(null, null, false, null, null))

    private fun account(id: String) = AccountEntity(id, id, id, null, null, null, false, 1, 1)

    private fun measurement(id: String, accountId: String, status: SyncStatus) = MeasurementEntity(
        id = id, fingerprint = "fingerprint-$id", measurementType = MeasurementType.WEIGHT_ONLY,
        deviceAddress = "AA:BB", measuredAtEpochSecond = 1, rawPayloadHex = "00", weightKg = 70.0,
        impedanceOhm = null, bmi = null, bodyFatPercent = null, bodyFatMassKg = null,
        waterPercent = null, waterMassKg = null, muscleMassKg = null, skeletalMuscleMassKg = null,
        boneMassKg = null, proteinPercent = null, proteinMassKg = null, visceralFatLevel = null,
        basalMetabolicRateKcal = null, metabolicAge = null, leanBodyMassKg = null,
        algorithmVersion = null, huaweiStatus = status.name, healthConnectStatus = status.name,
        accountId = accountId, externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL.name,
        deduplicationHash = "hash-$id",
    )

    private fun pending(id: String) = PendingMeasurementEntity(
        id, "AA:BB", 1, 70.0, 0, true, false, byteArrayOf(0), "pending-$id", 1,
    )
}
