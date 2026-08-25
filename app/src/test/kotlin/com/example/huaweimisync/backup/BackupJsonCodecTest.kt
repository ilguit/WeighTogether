package com.example.huaweimisync.backup

import com.example.huaweimisync.data.MeasurementType
import com.example.huaweimisync.data.SyncStatus
import com.example.huaweimisync.domain.ExternalSyncPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonCodecTest {
    private val codec = BackupJsonCodec()

    @Test
    fun roundTripPreservesUnicodeAndEmptyOptionalCollections() {
        val source = document(accountName = "Иван ⚖")

        val decoded = codec.decode(codec.encode(source))

        assertEquals(source, decoded)
        assertTrue(codec.encode(source).toByteArray(Charsets.UTF_8).isNotEmpty())
    }

    @Test
    fun emptyBackupRoundTrips() {
        val source = document().copy(accounts = emptyList(), measurements = emptyList())

        assertEquals(source, codec.decode(codec.encode(source)))
    }

    @Test
    fun unsupportedVersionIsReportedBeforeUnknownFields() {
        val json = codec.encode(document()).replace("\"schemaVersion\":1", "\"schemaVersion\":2").replaceFirst("{", "{\"future\":true,")

        assertThrows(BackupException.UnsupportedVersion::class.java) { codec.decode(json) }
    }

    @Test
    fun corruptAndUnknownOrMissingFieldsAreTyped() {
        assertThrows(BackupException.Corrupt::class.java) { codec.decode("{") }
        val encoded = codec.encode(document())
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(encoded.replaceFirst("{", "{\"future\":true,"))
        }
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(encoded.replace("\"exportedAt\":\"2026-08-25T00:00:00Z\",", ""))
        }
    }

    @Test
    fun enumAndNumericBoundariesAreValidated() {
        val encoded = codec.encode(document())
        assertThrows(BackupException.Invalid::class.java) {
            codec.decode(encoded.replace("\"measurementType\":\"WEIGHT_ONLY\"", "\"measurementType\":\"FUTURE\""))
        }
        assertEquals(0.1, codec.decode(codec.encode(document().copy(appState = BackupAppStateV1("a", 0.1, false)))).appState.weightDeltaKg, 0.0)
        assertEquals(50.0, codec.decode(codec.encode(document().copy(appState = BackupAppStateV1("a", 50.0, false)))).appState.weightDeltaKg, 0.0)
        assertThrows(BackupException.Invalid::class.java) {
            codec.encode(document().copy(appState = BackupAppStateV1("a", 50.1, false)))
        }
    }

    @Test
    fun duplicateStableValuesAndMissingAccountsAreRejected() {
        val source = document()
        assertThrows(BackupException.Duplicate::class.java) {
            codec.encode(source.copy(measurements = listOf(source.measurements.single(), source.measurements.single().copy(accountId = "a"))))
        }
        assertThrows(BackupException.MissingAccount::class.java) {
            codec.encode(source.copy(measurements = listOf(source.measurements.single().copy(accountId = "missing"))))
        }
    }

    @Test
    fun collectionLimitsAreRejected() {
        val account = document().accounts.single()
        assertThrows(BackupException.Limits::class.java) {
            codec.encode(document().copy(accounts = List(MAX_BACKUP_ACCOUNTS + 1) { account.copy(id = "a$it") }, measurements = emptyList()))
        }
        assertThrows(BackupException.Limits::class.java) {
            codec.encode(document().copy(settings = BackupSettingsV1(null, null, false, List(MAX_BACKUP_SERIES_KEYS + 1) { "k$it" }, null)))
        }
    }

    private fun document(accountName: String = "Account") = BackupDocumentV1(
        exportedAt = "2026-08-25T00:00:00Z",
        accounts = listOf(
            BackupAccountV1(
                id = "a",
                displayName = accountName,
                normalizedName = accountName.lowercase(),
                profile = BackupAccountProfileV1(null, null, null, false),
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 2,
            ),
        ),
        appState = BackupAppStateV1("a", 3.0, false),
        measurements = listOf(
            BackupMeasurementV1(
                id = "m", fingerprint = "f", measurementType = MeasurementType.WEIGHT_ONLY,
                deviceAddress = "AA:BB", measuredAtEpochSecond = 3, rawPayloadHex = "00ff",
                weightKg = 70.0, rawWeight = 14000, impedanceOhm = null, bmi = null,
                bodyFatPercent = null, bodyFatMassKg = null, waterPercent = null,
                waterMassKg = null, muscleMassKg = null, skeletalMuscleMassKg = null,
                boneMassKg = null, proteinPercent = null, proteinMassKg = null,
                visceralFatLevel = null, basalMetabolicRateKcal = null, metabolicAge = null,
                leanBodyMassKg = null, algorithmVersion = null, huaweiStatus = SyncStatus.LOCAL_ONLY,
                healthConnectStatus = SyncStatus.LOCAL_ONLY, huaweiError = null,
                healthConnectError = null, huaweiWeightSynced = false,
                healthConnectWeightSynced = false, createdAtEpochMillis = 4, accountId = "a",
                externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL, sourcePendingId = null,
                deduplicationHash = "d", huaweiSyncedCalculatedValues = null,
                healthConnectSyncedCalculatedValues = null,
            ),
        ),
        settings = BackupSettingsV1("AA:BB", "Весы", true, emptyList(), emptyList()),
    )
}
