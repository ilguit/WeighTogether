package com.palixander.weightogether.backup

import com.palixander.weightogether.recoverBackupImportAtStartup
import com.palixander.weightogether.data.AccountEntity
import com.palixander.weightogether.data.AppStateEntity
import com.palixander.weightogether.data.BackupImportCheckpointEntity
import com.palixander.weightogether.data.MeasurementType
import com.palixander.weightogether.data.RatingHeightOrigin
import com.palixander.weightogether.data.PortableProfileSettings
import com.palixander.weightogether.data.PetEntity
import com.palixander.weightogether.data.PetMeasurementEntity
import com.palixander.weightogether.data.SyncStatus
import com.palixander.weightogether.data.WeighingReminderOwnerType
import com.palixander.weightogether.data.WeighingReminderScheduleEntity
import com.palixander.weightogether.domain.ExternalSyncPolicy
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.domain.PetSex
import com.palixander.weightogether.domain.WeighingReminderImportance
import com.palixander.weightogether.core.breedreference.BreedReferenceSnapshotLoadResult
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking
import com.palixander.weightogether.worker.ExternalSyncOperationSerializer

class BackupImportServiceTest {
    private val service = BackupImportService()
    private val emptySettings = PortableProfileSettings(null, null, false, null, null)

    @Test
    fun `pre rename backup imports stable format breed and measurement identifiers`() {
        // Frozen synthetic pre-rename JSON, independent of current encoder/constants.
        val input = requireNotNull(javaClass.getResourceAsStream("/backup/scalesync-v6.json"))
        val preview = input.use {
            service.preview(it, emptySnapshot(), emptySettings, BackupImportMode.REPLACE)
        }

        assertEquals("scalesync-backup", BACKUP_FORMAT_ID)
        assertEquals("legacy-account", preview.result.accounts.single().id)
        assertEquals("legacy-pet", preview.result.pets.single().id)
        assertEquals("scalesync:cat:mixed-breed", preview.result.pets.single().breedId)
        assertEquals("legacy-weight", preview.result.petMeasurements.single().id)
        assertEquals(4.125, preview.result.petMeasurements.single().petWeightKg, 0.0)
        assertTrue(preview.result.petMeasurements.single().isManuallyEdited)
    }

    @Test
    fun `merge remaps reminder owner before semantic duplicate checks and counts`() {
        val localAccount = AccountEntity("local", "Account", "account", null, null, null, false, 1, 2)
        val localSchedule = schedule("local-s", "local")
        val current = emptySnapshot().copy(
            accounts = listOf(localAccount),
            reminderSchedules = listOf(localSchedule),
        )
        val incoming = document().copy(
            reminderSchedules = listOf(
                BackupReminderScheduleV7("imported-s", WeighingReminderOwnerType.ACCOUNT, "a", 540, 1,
                    WeighingReminderImportance.REGULAR, true, 3, 4),
            ),
        )

        val preview = service.preview(incoming, current, emptySettings, BackupImportMode.MERGE)

        assertEquals(listOf(localSchedule), preview.result.reminderSchedules)
        assertEquals(0, preview.counts.reminderSchedulesAdded)
        assertEquals(1, preview.counts.reminderSchedulesSkipped)
    }

    @Test
    fun `merge rejects schedule id mapped to different semantics`() {
        val current = emptySnapshot().copy(
            accounts = listOf(AccountEntity("local", "Account", "account", null, null, null, false, 1, 2)),
            reminderSchedules = listOf(schedule("same", "local")),
        )
        val incoming = document().copy(
            reminderSchedules = listOf(
                BackupReminderScheduleV7("same", WeighingReminderOwnerType.ACCOUNT, "a", 600, 1,
                    WeighingReminderImportance.REGULAR, true, 3, 4),
            ),
        )

        assertThrows(BackupImportConflicts::class.java) {
            service.preview(incoming, current, emptySettings, BackupImportMode.MERGE)
        }
    }

    @Test
    fun `merge rejects schedules that become semantic duplicates after owner remap`() {
        val current = emptySnapshot().copy(
            accounts = listOf(AccountEntity("local", "Account", "account", null, null, null, false, 1, 2)),
        )
        val secondAccount = document().accounts.single().copy(
            id = "second",
            displayName = "Also account",
            normalizedName = "account",
        )
        val incoming = document().copy(
            accounts = document().accounts + secondAccount,
            reminderSchedules = listOf(
                BackupReminderScheduleV7("one", WeighingReminderOwnerType.ACCOUNT, "a", 540, 1,
                    WeighingReminderImportance.REGULAR, true, 3, 4),
                BackupReminderScheduleV7("two", WeighingReminderOwnerType.ACCOUNT, "second", 540, 1,
                    WeighingReminderImportance.REGULAR, true, 3, 4),
            ),
        )

        assertThrows(BackupImportConflicts::class.java) {
            service.preview(incoming, current, emptySettings, BackupImportMode.MERGE)
        }
    }

    @Test
    fun `replace from legacy v6 clears schedules and reports replacements`() {
        val current = emptySnapshot().copy(
            accounts = listOf(AccountEntity("local", "Local", "local", null, null, null, false, 1, 2)),
            reminderSchedules = listOf(schedule("local-s", "local")),
        )
        val legacy = BackupJsonCodec().decode(BackupJsonCodec().encode(document().copy(schemaVersion = 6)))

        val preview = service.preview(legacy, current, emptySettings, BackupImportMode.REPLACE)

        assertEquals(emptyList<WeighingReminderScheduleEntity>(), preview.result.reminderSchedules)
        assertEquals(1, preview.counts.reminderSchedulesReplaced)
    }

    @Test
    fun `v5 import retains manual provenance and keeps it on merge collisions`() {
        val document = document().copy(
            measurements = listOf(document().measurements.single().copy(
                origin = com.palixander.weightogether.domain.MeasurementOrigin.MANUAL,
                measurementType = MeasurementType.WEIGHT_ONLY,
            )),
            pets = listOf(BackupPetV2("pet", "Кот", "кот", PetSpecies.CAT, 1, 1)),
            petMeasurements = listOf(BackupPetMeasurementV2("pm", "pet", -60, null, null, 4.125,
                com.palixander.weightogether.domain.MeasurementOrigin.MANUAL)),
        )
        val imported = service.preview(document, emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        assertEquals(com.palixander.weightogether.domain.MeasurementOrigin.MANUAL, imported.result.measurements.single().origin)
        assertEquals(com.palixander.weightogether.domain.MeasurementOrigin.MANUAL, imported.result.petMeasurements.single().origin)
        assertEquals(4.125, imported.result.petMeasurements.single().toDomain().petWeightKg, 0.0)
        val repeated = service.preview(document, imported.result, emptySettings, BackupImportMode.MERGE)
        assertEquals(0, repeated.counts.measurementsAdded)
        val changedOrigin = service.preview(
            document.copy(measurements = listOf(document.measurements.single().copy(
                origin = com.palixander.weightogether.domain.MeasurementOrigin.LEGACY,
            ))),
            imported.result,
            emptySettings,
            BackupImportMode.MERGE,
        )
        assertEquals(1, changedOrigin.counts.measurementsSkipped)
        assertEquals(
            com.palixander.weightogether.domain.MeasurementOrigin.MANUAL,
            changedOrigin.result.measurements.single().origin,
        )
    }

    @Test
    fun `read uses codec validation and enforces byte limit without closing stream`() {
        val json = BackupJsonCodec().encode(document())
        val input = TrackingInputStream(json.toByteArray())
        assertEquals("a", service.read(input).accounts.single().id)
        assertEquals(false, input.closed)
        assertThrows(BackupException.Limits::class.java) {
            BackupImportService(byteLimit = 4).read(ByteArrayInputStream(json.toByteArray()))
        }
        assertThrows(BackupException.Corrupt::class.java) {
            service.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertThrows(BackupException.Io::class.java) { service.read(object : InputStream() {
            override fun read(): Int = throw IOException("lost provider")
        }) }
    }

    @Test
    fun `read accepts exact byte limit including multibyte account name`() {
        val json = BackupJsonCodec().encode(document(accountDisplayName = "Анна 🌿"))
        val bytes = json.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size > json.length)
        val input = TrackingInputStream(bytes)

        val result = BackupImportService(byteLimit = bytes.size).read(input)

        assertEquals("Анна 🌿", result.accounts.single().displayName)
        assertEquals("m", result.measurements.single().id)
        assertFalse(input.closed)
    }

    @Test
    fun `read rejects one byte beyond limit for ascii and multibyte documents`() {
        for (name in listOf("Account", "Анна 🌿")) {
            val bytes = BackupJsonCodec().encode(document(accountDisplayName = name)).toByteArray(Charsets.UTF_8)
            val input = TrackingInputStream(bytes)
            val limit = bytes.size - 1

            val error = assertThrows(BackupException.Limits::class.java) {
                BackupImportService(byteLimit = limit).read(input)
            }

            assertEquals("$", error.path)
            assertEquals(limit, error.limit)
            assertFalse(input.closed)
        }
    }

    @Test
    fun `read joins fragmented multibyte input across finite zero reads`() {
        val bytes = BackupJsonCodec().encode(document(accountDisplayName = "Анна 🌿")).toByteArray(Charsets.UTF_8)
        val input = FragmentedInputStream(bytes, zeroReads = true)

        val result = BackupImportService(byteLimit = bytes.size).read(input)

        assertEquals("Анна 🌿", result.accounts.single().displayName)
        assertEquals("m", result.measurements.single().id)
        assertFalse(input.closed)
    }

    @Test
    fun `read rejects malformed and truncated utf8 with coding cause and leaves stream open`() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xe2.toByte(), 0x82.toByte()))) {
            val input = TrackingInputStream(bytes)

            val error = assertThrows(BackupException.Corrupt::class.java) { service.read(input) }

            assertTrue(error.cause is CharacterCodingException)
            assertFalse(input.closed)
        }
    }

    @Test
    fun `read reports size limit before malformed utf8 or json`() {
        val input = FragmentedInputStream(byteArrayOf(0xff.toByte(), 0xff.toByte()))

        val error = assertThrows(BackupException.Limits::class.java) {
            BackupImportService(byteLimit = 1).read(input)
        }

        assertEquals("$", error.path)
        assertEquals(1, error.limit)
        assertFalse(input.closed)
    }

    @Test
    fun `read wraps partial io failure with original cause before decoding`() {
        val failure = IOException("provider disconnected")
        val input = FragmentedInputStream(byteArrayOf(0xff.toByte()), failure = failure)

        val error = assertThrows(BackupException.Io::class.java) { service.read(input) }

        assertSame(failure, error.cause)
        assertFalse(input.closed)
    }

    @Test
    fun `read preserves backup exception from stream`() {
        val failure = BackupException.Invalid("provider", "unavailable")
        val input = FragmentedInputStream(byteArrayOf(0x7b), failure = failure)

        val error = assertThrows(BackupException.Invalid::class.java) { service.read(input) }

        assertSame(failure, error)
        assertFalse(input.closed)
    }

    @Test
    fun `read delegates empty input and invalid json to codec validation`() {
        for (json in listOf("", "{", "{}")) {
            val expected = assertThrows(BackupException::class.java) { BackupJsonCodec().decode(json) }
            val input = TrackingInputStream(json.toByteArray(Charsets.UTF_8))

            val actual = assertThrows(BackupException::class.java) { service.read(input) }

            assertEquals(expected.javaClass, actual.javaClass)
            assertEquals(expected.message, actual.message)
            assertEquals(expected.cause?.javaClass, actual.cause?.javaClass)
            assertFalse(input.closed)
        }
    }

    @Test
    fun `service rejects nonpositive byte limits before reading`() {
        for (limit in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) { BackupImportService(byteLimit = limit) }
        }
    }

    @Test
    fun `merge is idempotent and keeps stable identities and local configuration`() {
        val document = document()
        val imported = service.preview(document, emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        val localSettings = PortableProfileSettings("LOCAL", "Local", false, setOf("weight"), null)
        val repeated = service.preview(document, imported.result, localSettings, BackupImportMode.MERGE)

        assertEquals(1, imported.counts.accountsAdded)
        assertEquals(1, imported.counts.measurementsAdded)
        assertEquals(1, repeated.counts.accountsSkipped)
        assertEquals(1, repeated.counts.measurementsSkipped)
        assertEquals("m", repeated.result.measurements.single().id)
        assertEquals("f", repeated.result.measurements.single().fingerprint)
        assertEquals("d", repeated.result.measurements.single().deduplicationHash)
        assertEquals("a", repeated.result.measurements.single().accountId)
        assertEquals("LOCAL", repeated.settings.scaleAddress)
        assertEquals(emptySet<String>(), repeated.settings.homeKgChartSeriesKeys)
    }

    @Test
    fun `merge keeps local rows on matching ids names fingerprints and hashes`() {
        val base = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE).result
        val changedAccount = service.preview(
            document(accountDisplayName = "Changed"),
            base,
            emptySettings,
            BackupImportMode.MERGE,
        )
        assertEquals(base.accounts.single(), changedAccount.result.accounts.single())
        assertEquals(1, changedAccount.counts.accountsSkipped)

        val renamedId = document().copy(accounts = listOf(document().accounts.single().copy(id = "other")),
            appState = document().appState.copy(primaryAccountId = "other"),
            measurements = listOf(document().measurements.single().copy(
                id = "other-m",
                fingerprint = "other-f",
                deduplicationHash = "other-d",
                accountId = "other",
            )))
        val matchedByName = service.preview(renamedId, base, emptySettings, BackupImportMode.MERGE)
        assertEquals(listOf("a"), matchedByName.result.accounts.map { it.id })
        assertEquals("a", matchedByName.result.measurements.last().accountId)

        val collision = document().copy(measurements = listOf(document().measurements.single().copy(id = "new", weightKg = 71.0)))
        val skippedCollision = service.preview(collision, base, emptySettings, BackupImportMode.MERGE)
        assertEquals(base.measurements, skippedCollision.result.measurements)
        assertEquals(1, skippedCollision.counts.measurementsSkipped)
    }

    @Test
    fun `merge keeps local measurement on matching source pending id and stays idempotent`() {
        val localDocument = document().copy(
            measurements = listOf(
                document().measurements.single().copy(sourcePendingId = "pending-stable"),
            ),
        )
        val local = service.preview(
            localDocument,
            emptySnapshot(),
            emptySettings,
            BackupImportMode.MERGE,
        ).result
        val incoming = localDocument.copy(
            measurements = listOf(
                localDocument.measurements.single().copy(
                    id = "incoming-id",
                    fingerprint = "incoming-fingerprint",
                    deduplicationHash = "incoming-hash",
                    weightKg = 71.0,
                ),
            ),
        )

        val first = service.preview(incoming, local, emptySettings, BackupImportMode.MERGE)
        val repeated = service.preview(incoming, first.result, emptySettings, BackupImportMode.MERGE)

        assertEquals(0, first.counts.measurementsAdded)
        assertEquals(1, first.counts.measurementsSkipped)
        assertEquals(local.measurements, first.result.measurements)
        assertEquals(0, repeated.counts.measurementsAdded)
        assertEquals(1, repeated.counts.measurementsSkipped)
        assertEquals(local.measurements, repeated.result.measurements)
    }

    @Test
    fun `merge rejects source pending id matching a different local measurement key`() {
        val source = document()
        val current = service.preview(
            source.copy(
                measurements = listOf(
                    source.measurements.single().copy(sourcePendingId = "pending-one"),
                    source.measurements.single().copy(
                        id = "m2",
                        fingerprint = "f2",
                        sourcePendingId = "pending-two",
                        deduplicationHash = "d2",
                    ),
                ),
            ),
            emptySnapshot(),
            emptySettings,
            BackupImportMode.MERGE,
        ).result

        val error = assertThrows(BackupImportConflicts::class.java) {
            service.preview(
                source.copy(
                    measurements = listOf(
                        source.measurements.single().copy(
                            fingerprint = "incoming-fingerprint",
                            sourcePendingId = "pending-two",
                            deduplicationHash = "incoming-hash",
                        ),
                    ),
                ),
                current,
                emptySettings,
                BackupImportMode.MERGE,
            )
        }

        assertEquals(true, error.conflicts.contains(BackupImportConflict.MeasurementId("m")))
        assertEquals(
            true,
            error.conflicts.contains(
                BackupImportConflict.MeasurementSourcePendingId("pending-two"),
            ),
        )
    }

    @Test
    fun `merge remaps matched owners and stays idempotent with correct counts`() {
        val localAccount = document().accounts.single().let {
            AccountEntity(
                id = "local-account",
                displayName = it.displayName,
                normalizedName = it.normalizedName,
                heightCm = 190.0,
                birthDateEpochDay = null,
                sex = null,
                isProfileComplete = false,
                createdAtEpochMillis = 10,
                updatedAtEpochMillis = 20,
            )
        }
        val localPet = PetEntity("local-pet", "Cat", "cat", PetSpecies.CAT, 10, 20)
        val current = BackupDatabaseSnapshot(
            accounts = listOf(localAccount),
            appState = AppStateEntity(primaryAccountId = null),
            measurements = emptyList(),
            pets = listOf(localPet),
            petMeasurements = emptyList(),
        )
        val incoming = document().copy(
            accounts = listOf(document().accounts.single().copy(id = "remote-account")),
            appState = document().appState.copy(primaryAccountId = "remote-account"),
            measurements = listOf(document().measurements.single().copy(
                id = "imported-measurement",
                fingerprint = "imported-fingerprint",
                deduplicationHash = "imported-hash",
                accountId = "remote-account",
            )),
            pets = listOf(BackupPetV2("remote-pet", "Cat", "cat", PetSpecies.DOG, 1, 2)),
            petMeasurements = listOf(BackupPetMeasurementV2("imported-pet-measurement", "remote-pet", 3, 70.0, 74.0, 4.0)),
        )

        val imported = service.preview(incoming, current, emptySettings, BackupImportMode.MERGE)
        val repeated = service.preview(incoming, imported.result, emptySettings, BackupImportMode.MERGE)

        assertEquals(localAccount, imported.result.accounts.single())
        assertEquals("local-account", imported.result.appState.primaryAccountId)
        assertEquals("local-account", imported.result.measurements.single().accountId)
        assertEquals(localPet, imported.result.pets.single())
        assertEquals("local-pet", imported.result.petMeasurements.single().petId)
        assertEquals(BackupImportCounts(0, 1, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0), imported.counts)
        assertEquals(BackupImportCounts(0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0), repeated.counts)
        assertEquals(imported.result, repeated.result)
    }

    @Test
    fun `merge rejects account pet and measurement keys that point to different local entities`() {
        val base = service.preview(
            document().copy(
                accounts = document().accounts + document().accounts.single().copy(
                    id = "b",
                    displayName = "Second",
                    normalizedName = "second",
                ),
                measurements = document().measurements + document().measurements.single().copy(
                    id = "m2",
                    fingerprint = "f2",
                    deduplicationHash = "d2",
                    accountId = "b",
                ),
                pets = listOf(
                    BackupPetV2("p1", "Cat", "cat", PetSpecies.CAT, 1, 2),
                    BackupPetV2("p2", "Dog", "dog", PetSpecies.DOG, 1, 2),
                ),
            ),
            emptySnapshot(),
            emptySettings,
            BackupImportMode.MERGE,
        ).result

        assertThrows(BackupImportConflicts::class.java) {
            service.preview(
                document().copy(
                    accounts = listOf(document().accounts.single().copy(
                        displayName = "Second",
                        normalizedName = "second",
                    )),
                ),
                base,
                emptySettings,
                BackupImportMode.MERGE,
            )
        }.also {
            assertEquals(true, it.conflicts.contains(BackupImportConflict.AccountId("a")))
            assertEquals(true, it.conflicts.contains(BackupImportConflict.AccountName("second")))
        }

        assertThrows(BackupImportConflicts::class.java) {
            service.preview(
                document().copy(measurements = listOf(document().measurements.single().copy(
                    fingerprint = "f2",
                ))),
                base,
                emptySettings,
                BackupImportMode.MERGE,
            )
        }.also {
            assertEquals(true, it.conflicts.contains(BackupImportConflict.MeasurementId("m")))
            assertEquals(true, it.conflicts.contains(BackupImportConflict.MeasurementFingerprint("f2")))
        }

        assertThrows(BackupImportConflicts::class.java) {
            service.preview(
                document().copy(
                    pets = listOf(BackupPetV2("p1", "Dog", "dog", PetSpecies.CAT, 1, 2)),
                ),
                base,
                emptySettings,
                BackupImportMode.MERGE,
            )
        }.also {
            assertEquals(true, it.conflicts.contains(BackupImportConflict.PetId("p1")))
            assertEquals(true, it.conflicts.contains(BackupImportConflict.PetName("dog")))
        }
    }

    @Test
    fun `pet merge keeps local rows on matching id or normalized name`() {
        val petDocument = document().copy(
            pets = listOf(BackupPetV2("p", "Cat", "cat", PetSpecies.CAT, 1, 2)),
            petMeasurements = listOf(BackupPetMeasurementV2("pm", "p", 3, 70.0, 74.0, 4.0)),
        )
        val imported = service.preview(petDocument, emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        val repeated = service.preview(petDocument, imported.result, emptySettings, BackupImportMode.MERGE)
        assertEquals(1, repeated.counts.petsSkipped)
        assertEquals(1, repeated.counts.petMeasurementsSkipped)

        val changedById = service.preview(
            petDocument.copy(pets = listOf(petDocument.pets.single().copy(species = PetSpecies.DOG))),
            imported.result,
            emptySettings,
            BackupImportMode.MERGE,
        )
        assertEquals(PetSpecies.CAT, changedById.result.pets.single().species)

        val changedProfileById = service.preview(
            petDocument.copy(pets = listOf(petDocument.pets.single().copy(
                sex = PetSex.FEMALE,
                breedId = "external:cat:future",
                birthYear = 2020,
            ))),
            imported.result,
            emptySettings,
            BackupImportMode.MERGE,
        )
        assertEquals(null, changedProfileById.result.pets.single().sex)

        val renamed = petDocument.copy(
            pets = listOf(petDocument.pets.single().copy(id = "other")),
            petMeasurements = listOf(petDocument.petMeasurements.single().copy(id = "other-pm", petId = "other")),
        )
        val matchedByName = service.preview(renamed, imported.result, emptySettings, BackupImportMode.MERGE)
        assertEquals(listOf("p"), matchedByName.result.pets.map { it.id })
        assertEquals("p", matchedByName.result.petMeasurements.last().petId)
    }

    @Test
    fun `import normalizes breed ids at the backup compatibility boundary`() {
        val pets = listOf(
            BackupPetV2("supported", "Supported", "supported", PetSpecies.DOG, 1, 2,
                breedId = "VBO:0200995"),
            BackupPetV2("alias", "Alias", "alias", PetSpecies.DOG, 1, 2,
                breedId = "VBO:0201146"),
            BackupPetV2("unsupported", "Unsupported", "unsupported", PetSpecies.DOG, 1, 2,
                breedId = "external:dog:future"),
            BackupPetV2("cat", "Cat", "cat", PetSpecies.CAT, 1, 2,
                breedId = "VBO:0100061"),
            BackupPetV2("future-cat", "Future cat", "future cat", PetSpecies.CAT, 1, 2,
                breedId = "external:cat:future"),
        )

        val imported = service.preview(
            document().copy(pets = pets),
            emptySnapshot(),
            emptySettings,
            BackupImportMode.REPLACE,
        ).result.pets.associateBy { it.id }

        assertEquals("VBO:0200995", imported.getValue("supported").breedId)
        assertEquals("VBO:0200174", imported.getValue("alias").breedId)
        assertEquals("external:dog:future", imported.getValue("unsupported").breedId)
        assertEquals("VBO:0100230", imported.getValue("cat").breedId)
        assertEquals("external:cat:future", imported.getValue("future-cat").breedId)
    }

    @Test
    fun `import remains available and preserves breed ids when breed snapshot is unavailable`() {
        val unavailableService = BackupImportService(
            breedSnapshotResult = BreedReferenceSnapshotLoadResult.Unavailable("checksum mismatch"),
        )
        val imported = unavailableService.preview(
            document().copy(
                pets = listOf(
                    BackupPetV2(
                        "dog",
                        "Dog",
                        "dog",
                        PetSpecies.DOG,
                        1,
                        2,
                        breedId = "VBO:0200995",
                    ),
                ),
            ),
            emptySnapshot(),
            emptySettings,
            BackupImportMode.REPLACE,
        )

        assertEquals("VBO:0200995", imported.result.pets.single().breedId)
    }

    @Test
    fun `pet measurement id collision keeps local row and replace removes old pet graph`() {
        val current = BackupDatabaseSnapshot(
            emptyList(),
            AppStateEntity(),
            emptyList(),
            listOf(PetEntity("old", "Old", "old", PetSpecies.DOG, 1, 2)),
            listOf(PetMeasurementEntity("pm", "old", 3, 80.0, 85.0, 5.0)),
        )
        val incoming = document().copy(
            pets = listOf(BackupPetV2("new", "New", "new", PetSpecies.CAT, 4, 5)),
            petMeasurements = listOf(BackupPetMeasurementV2("pm", "new", 6, 70.0, 74.0, 4.0)),
        )
        val merged = service.preview(incoming, current, emptySettings, BackupImportMode.MERGE)
        assertEquals(current.petMeasurements, merged.result.petMeasurements)
        assertEquals(1, merged.counts.petMeasurementsSkipped)

        val replacement = service.preview(incoming, current, emptySettings, BackupImportMode.REPLACE)
        assertEquals(listOf("new"), replacement.result.pets.map { it.id })
        assertEquals("new", replacement.result.petMeasurements.single().petId)
        assertEquals(1, replacement.counts.petsReplaced)
        assertEquals(1, replacement.counts.petMeasurementsReplaced)
    }

    @Test
    fun `replace reports disjoint imported rows as added and uses imported links and settings`() {
        val base = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE).result
        val replacement = document().copy(
            accounts = listOf(document().accounts.single().copy(id = "b", normalizedName = "b")),
            appState = document().appState.copy(primaryAccountId = "b"),
            measurements = listOf(document().measurements.single().copy(id = "n", fingerprint = "nf", deduplicationHash = "nd", accountId = "b")),
        )
        val preview = service.preview(replacement, base, emptySettings, BackupImportMode.REPLACE)
        assertEquals(BackupImportCounts(1, 0, 1, 1, 0, 1), preview.counts)
        assertEquals("b", preview.result.appState.primaryAccountId)
        assertEquals("b", preview.result.measurements.single().accountId)
        assertEquals("AA:BB", preview.settings.scaleAddress)
    }

    @Test
    fun `replace reports all incoming rows as added and all local rows as replaced`() {
        val original = service.preview(
            document(),
            emptySnapshot(),
            emptySettings,
            BackupImportMode.MERGE,
        ).result
        val extra = document().copy(
            accounts = document().accounts + document().accounts.single().copy(
                id = "b",
                displayName = "Second",
                normalizedName = "second",
            ),
            measurements = document().measurements + document().measurements.single().copy(
                id = "n",
                fingerprint = "nf",
                deduplicationHash = "nd",
                accountId = "b",
            ),
        )

        val preview = service.preview(extra, original, emptySettings, BackupImportMode.REPLACE)

        assertEquals(BackupImportCounts(2, 0, 1, 2, 0, 1), preview.counts)
    }

    @Test
    fun `replace with empty backup reports all deleted local rows as replaced`() {
        val original = service.preview(
            document(),
            emptySnapshot(),
            emptySettings,
            BackupImportMode.MERGE,
        ).result
        val empty = document().copy(
            accounts = emptyList(),
            appState = BackupAppStateV1(null, 3.0, false),
            measurements = emptyList(),
        )

        val preview = service.preview(empty, original, emptySettings, BackupImportMode.REPLACE)

        assertEquals(BackupImportCounts(0, 0, 1, 0, 0, 1), preview.counts)
    }

    @Test
    fun `apply persists database before settings and invokes hooks only after success`() = runBlocking {
        val events = mutableListOf<String>()
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.REPLACE)
        val gateway = TrackingGateway(events)
        val result = BackupImportApplier(
            gateway = gateway,
            settingsWriter = PortableSettingsWriter { events += "settings:${it.scaleAddress}" },
            successHooks = listOf(BackupImportSuccessHook { events += "hook:${it.mode}" }),
        ).apply(preview)

        assertEquals(listOf("database", "settings:AA:BB", "checkpoint-cleanup", "hook:REPLACE"), events)
        assertEquals(BackupImportMode.REPLACE, result.mode)
        assertEquals(preview.counts, result.counts)
    }

    @Test
    fun `merge and replace reconcile before checkpoint cleanup outside import mutex`() =
        runBlocking {
            BackupImportMode.entries.forEach { mode ->
                val events = mutableListOf<String>()
                val operations = ExternalSyncOperationSerializer()
                val preview = service.preview(document(), emptySnapshot(), emptySettings, mode)
                BackupImportApplier(
                    gateway = TrackingGateway(events),
                    settingsWriter = PortableSettingsWriter { events += "settings" },
                    operations = operations,
                    completionHooks = listOf(
                        BackupImportCompletionHook {
                            operations.runExclusive { events += "sweep:$mode" }
                        },
                    ),
                ).apply(preview)

                assertEquals(
                    listOf("database", "settings", "sweep:$mode", "checkpoint-cleanup"),
                    events,
                )
            }
        }

    @Test
    fun `post commit reconcile failure keeps checkpoint for recovery`() =
        runBlocking {
            val events = mutableListOf<String>()
            val preview = service.preview(
                document(),
                emptySnapshot(),
                emptySettings,
                BackupImportMode.MERGE,
            )
            val result = BackupImportApplier(
                gateway = TrackingGateway(events),
                settingsWriter = PortableSettingsWriter { events += "settings" },
                successHooks = listOf(
                    BackupImportSuccessHook {
                        events += "failed-success-hook"
                        error("analytics unavailable")
                    },
                    BackupImportSuccessHook { events += "later-success-hook" },
                ),
                completionHooks = listOf(
                    BackupImportCompletionHook {
                        events += "failed-scheduler"
                        error("WorkManager unavailable")
                    },
                    BackupImportCompletionHook { events += "scheduled" },
                ),
            ).apply(preview)

            assertEquals(true, result is BackupImportApplyResult.CompletedPendingRecovery)
            assertEquals(
                listOf(
                    "database",
                    "settings",
                    "failed-scheduler",
                ),
                events,
            )
        }

    @Test
    fun `cancellation while completing apply is propagated and leaves checkpoint retryable`() =
        runBlocking {
            val events = mutableListOf<String>()
            val preview = service.preview(
                document(),
                emptySnapshot(),
                emptySettings,
                BackupImportMode.MERGE,
            )
            val cancellation = CancellationException("cancel checkpoint cleanup")
            val gateway = TrackingGateway(
                events,
                completeFailure = cancellation,
                completeFailuresRemaining = 1,
            )
            val laterHooks = mutableListOf<String>()
            val applier = BackupImportApplier(
                gateway = gateway,
                settingsWriter = PortableSettingsWriter { events += "settings" },
                successHooks = listOf(BackupImportSuccessHook { laterHooks += "success" }),
                completionHooks = listOf(BackupImportCompletionHook { laterHooks += "completion" }),
            )

            val thrown = assertThrows(CancellationException::class.java) {
                runBlocking { applier.apply(preview) }
            }

            assertEquals(cancellation, thrown)
            assertEquals(listOf("database", "settings"), events)
            assertEquals(listOf("completion"), laterHooks)
            assertEquals(true, gateway.pendingRecovery() != null)

            applier.recoverPendingImport()

            assertEquals(null, gateway.pendingRecovery())
            assertEquals(listOf("completion", "completion"), laterHooks)
        }

    @Test
    fun `cancellation in success hook is propagated and stops all later hooks`() = runBlocking {
        val events = mutableListOf<String>()
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        val cancellation = CancellationException("cancel observer")
        val gateway = TrackingGateway(events)
        val applier = BackupImportApplier(
            gateway = gateway,
            settingsWriter = PortableSettingsWriter { events += "settings" },
            successHooks = listOf(
                BackupImportSuccessHook { throw cancellation },
                BackupImportSuccessHook { events += "later-success" },
            ),
            completionHooks = listOf(BackupImportCompletionHook { events += "completion" }),
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { applier.apply(preview) }
        }

        assertEquals(cancellation, thrown)
        assertEquals(listOf("database", "settings", "completion", "checkpoint-cleanup"), events)
        assertEquals(null, gateway.pendingRecovery())
    }

    @Test
    fun `cancellation in completion hook is propagated and stops later completion hooks`() =
        runBlocking {
            val events = mutableListOf<String>()
            val preview = service.preview(
                document(),
                emptySnapshot(),
                emptySettings,
                BackupImportMode.REPLACE,
            )
            val cancellation = CancellationException("cancel scheduling")
            val gateway = TrackingGateway(events)
            val applier = BackupImportApplier(
                gateway = gateway,
                settingsWriter = PortableSettingsWriter { events += "settings" },
                completionHooks = listOf(
                    BackupImportCompletionHook { throw cancellation },
                    BackupImportCompletionHook { events += "later-completion" },
                ),
            )

            val thrown = assertThrows(CancellationException::class.java) {
                runBlocking { applier.apply(preview) }
            }

            assertEquals(cancellation, thrown)
            assertEquals(listOf("database", "settings"), events)
            assertEquals(true, gateway.pendingRecovery() != null)
        }

    @Test
    fun `database failure does not change settings or invoke post success hooks`() = runBlocking {
        val events = mutableListOf<String>()
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        val failure = IllegalStateException("transaction rolled back")
        var sweepCount = 0
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                BackupImportApplier(
                    gateway = TrackingGateway(events, stageFailure = failure),
                    settingsWriter = PortableSettingsWriter { events += "settings" },
                    successHooks = listOf(BackupImportSuccessHook { events += "hook" }),
                    completionHooks = listOf(BackupImportCompletionHook { sweepCount++ }),
                ).apply(preview)
            }
        }

        assertEquals(failure, thrown)
        assertEquals(emptyList<String>(), events)
        assertEquals(0, sweepCount)
    }

    @Test
    fun `stale preview does not trigger completion sweep`() = runBlocking {
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        var sweepCount = 0

        assertThrows(BackupPreviewStale::class.java) {
            runBlocking {
                BackupImportApplier(
                    gateway = TrackingGateway(
                        mutableListOf(),
                        stageFailure = BackupPreviewStale(preview),
                    ),
                    settingsWriter = PortableSettingsWriter {},
                    completionHooks = listOf(BackupImportCompletionHook { sweepCount++ }),
                ).apply(preview)
            }
        }

        assertEquals(0, sweepCount)
    }

    @Test
    fun `settings failure returns completed pending recovery without rolling database back`() = runBlocking {
        val events = mutableListOf<String>()
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        var firstWrite = true
        var sweepCount = 0
        val result = BackupImportApplier(
            gateway = TrackingGateway(events),
            settingsWriter = PortableSettingsWriter {
                events += "settings:${it.scaleAddress}"
                if (firstWrite) {
                    firstWrite = false
                    throw IllegalArgumentException("preferences")
                }
            },
            successHooks = listOf(BackupImportSuccessHook { events += "hook" }),
            completionHooks = listOf(BackupImportCompletionHook { sweepCount++ }),
        ).apply(preview)

        assertEquals(true, result is BackupImportApplyResult.CompletedPendingRecovery)
        assertEquals(listOf("database", "settings:AA:BB"), events)
        assertEquals(0, sweepCount)
    }

    @Test
    fun `pending settings commit is retried idempotently on recovery`() = runBlocking {
        val events = mutableListOf<String>()
        val preview = service.preview(document(), emptySnapshot(), emptySettings, BackupImportMode.MERGE)
        var settingsAttempts = 0
        var sweeps = 0
        val applier = BackupImportApplier(
            gateway = TrackingGateway(events),
            settingsWriter = PortableSettingsWriter {
                settingsAttempts++
                events += "settings:$settingsAttempts"
                if (settingsAttempts == 1) error("preferences")
            },
            completionHooks = listOf(BackupImportCompletionHook { sweeps++ }),
        )

        assertEquals(
            true,
            applier.apply(preview) is BackupImportApplyResult.CompletedPendingRecovery,
        )
        applier.recoverPendingImport()
        applier.recoverPendingImport()

        assertEquals(listOf("database", "settings:1", "settings:2", "checkpoint-cleanup"), events)
        assertEquals(1, sweeps)
    }

    @Test
    fun `pending import schedules only after recovery cleanup and scheduler failure is best effort`() =
        runBlocking {
            val events = mutableListOf<String>()
            val preview = service.preview(
                document(),
                emptySnapshot(),
                emptySettings,
                BackupImportMode.MERGE,
            )
            var settingsAttempts = 0
            var schedulingAttempts = 0
            val applier = BackupImportApplier(
                gateway = TrackingGateway(events),
                settingsWriter = PortableSettingsWriter {
                    settingsAttempts++
                    events += "settings:$settingsAttempts"
                    if (settingsAttempts == 1) error("preferences unavailable")
                },
                completionHooks = listOf(
                    BackupImportCompletionHook {
                        schedulingAttempts++
                        events += "schedule:$schedulingAttempts"
                        if (schedulingAttempts == 1) error("WorkManager unavailable")
                    },
                ),
            )

            val applyResult = applier.apply(preview)
            assertEquals(true, applyResult is BackupImportApplyResult.CompletedPendingRecovery)
            assertEquals(0, schedulingAttempts)

            assertThrows(IllegalStateException::class.java) {
                runBlocking { applier.recoverPendingImport() }
            }
            applier.recoverPendingImport()

            assertEquals(
                listOf(
                    "database",
                    "settings:1",
                    "settings:2",
                    "schedule:1",
                    "settings:3",
                    "schedule:2",
                    "checkpoint-cleanup",
                ),
                events,
            )
            assertEquals(2, schedulingAttempts)
        }

    @Test
    fun `startup recovery commits journal settings and cleans checkpoint`() = runBlocking {
        val events = mutableListOf<String>()
        val gateway = TrackingGateway(events, recoverySettings = document().settings.let {
            PortableProfileSettings(
                it.scaleAddress,
                it.scaleName,
                it.reliabilityMode,
                it.selectedChartMetricKeys?.toSet(),
                it.homeKgChartSeriesKeys?.toSet(),
            )
        })

        BackupImportApplier(
            gateway = gateway,
            settingsWriter = PortableSettingsWriter { events += "settings:${it.scaleAddress}" },
        ).recoverPendingImport()

        assertEquals(listOf("settings:AA:BB", "checkpoint-cleanup"), events)
    }

    @Test
    fun `startup recovery keeps checkpoint after writer failure and succeeds on retry`() = runBlocking {
        val events = mutableListOf<String>()
        val failures = mutableListOf<Exception>()
        val preview = service.preview(
            document(),
            emptySnapshot(),
            emptySettings,
            BackupImportMode.REPLACE,
        )
        val gateway = TrackingGateway(events)
        gateway.stage(preview)
        var writeAttempts = 0
        var sweepCount = 0
        val applier = BackupImportApplier(
            gateway = gateway,
            settingsWriter = PortableSettingsWriter {
                writeAttempts++
                events += "settings:$writeAttempts"
                if (writeAttempts == 1) error("settings unavailable")
            },
            completionHooks = listOf(BackupImportCompletionHook { sweepCount++ }),
        )

        recoverBackupImportAtStartup(applier::recoverPendingImport, failures::add)

        assertEquals(1, failures.size)
        assertEquals("settings unavailable", failures.single().message)
        assertEquals(true, gateway.pendingRecovery() != null)
        assertEquals(0, sweepCount)

        recoverBackupImportAtStartup(applier::recoverPendingImport, failures::add)

        assertEquals(1, failures.size)
        assertEquals(null, gateway.pendingRecovery())
        assertEquals(1, sweepCount)
        assertEquals(
            listOf("database", "settings:1", "settings:2", "checkpoint-cleanup"),
            events,
        )
    }

    @Test
    fun `startup recovery does not swallow cancellation or fatal errors`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                recoverBackupImportAtStartup(
                    recovery = { throw CancellationException("cancelled") },
                    reportFailure = {},
                )
            }
        }
        assertThrows(AssertionError::class.java) {
            runBlocking {
                recoverBackupImportAtStartup(
                    recovery = { throw AssertionError("fatal") },
                    reportFailure = {},
                )
            }
        }
    }

    @Test
    fun `startup and foreground recovery propagate cleanup cancellation and remain retryable`() =
        runBlocking {
            listOf(false, true).forEach { useStartupWrapper ->
                val events = mutableListOf<String>()
                val cancellation = CancellationException("cancel recovery cleanup")
                val gateway = TrackingGateway(
                    events = events,
                    recoverySettings = emptySettings,
                    completeFailure = cancellation,
                    completeFailuresRemaining = 1,
                )
                var completionHooks = 0
                val applier = BackupImportApplier(
                    gateway = gateway,
                    settingsWriter = PortableSettingsWriter { events += "settings" },
                    completionHooks = listOf(BackupImportCompletionHook { completionHooks++ }),
                )

                val thrown = assertThrows(CancellationException::class.java) {
                    runBlocking {
                        if (useStartupWrapper) {
                            recoverBackupImportAtStartup(applier::recoverPendingImport) {
                                error("cancellation must not be reported as an ordinary failure")
                            }
                        } else {
                            applier.recoverPendingImport()
                        }
                    }
                }

                assertEquals(cancellation, thrown)
                assertEquals(true, gateway.pendingRecovery() != null)
                assertEquals(1, completionHooks)

                applier.recoverPendingImport()

                assertEquals(null, gateway.pendingRecovery())
                assertEquals(2, completionHooks)
            }
        }

    @Test
    fun `successful startup recovery sweeps only after cleanup while rollback recovery does not`() =
        runBlocking {
            listOf(true, false).forEach { successfulImport ->
                val events = mutableListOf<String>()
                val operations = ExternalSyncOperationSerializer()
                BackupImportApplier(
                    gateway = TrackingGateway(
                        events,
                        recoverySettings = emptySettings,
                        recoverySweepNeeded = successfulImport,
                    ),
                    settingsWriter = PortableSettingsWriter { events += "settings" },
                    operations = operations,
                    completionHooks = listOf(
                        BackupImportCompletionHook {
                            operations.runExclusive { events += "sweep" }
                        },
                    ),
                ).recoverPendingImport()

                assertEquals(
                    if (successfulImport) {
                        listOf("settings", "sweep", "checkpoint-cleanup")
                    } else {
                        listOf("settings", "checkpoint-cleanup")
                    },
                    events,
                )
            }
        }

    @Test
    fun `checkpoint codec round trips nullable portable settings`() {
        val codec = BackupImportCheckpointCodec()
        val settings = PortableProfileSettings(null, "Scale", true, setOf("weight"), emptySet())

        val decodedSettings = codec.decodeSettings(codec.encodeSettings(settings))

        assertEquals(settings, decodedSettings)
    }

    @Test
    fun `checkpoint codec distinguishes bounded and legacy version 7 rows`() {
        val codec = BackupImportCheckpointCodec()
        val target = PortableProfileSettings("AA:BB", "Imported", true, emptySet(), setOf("weight"))
        val previous = PortableProfileSettings("11:22", "Previous", false, null, emptySet())
        val rollbackJson =
            """{"accounts":[],"appState":{},"measurements":[],"pendingMeasurements":[],"tombstones":[]}"""

        val bounded = codec.decodeRecovery(
            BackupImportCheckpointEntity(
                operationId = "123e4567-e89b-12d3-a456-426614174000",
                sweepNeeded = "false",
                targetSettingsJson = codec.encodeSettings(target),
            ),
        )
        val legacyTarget = codec.decodeRecovery(
            BackupImportCheckpointEntity(
                operationId = rollbackJson,
                sweepNeeded = codec.encodeSettings(previous),
                targetSettingsJson = codec.encodeSettings(target),
            ),
        )
        val legacyRollback = codec.decodeRecovery(
            BackupImportCheckpointEntity(
                phase = BackupImportCheckpointEntity.PHASE_ROLLBACK_APPLIED,
                operationId = rollbackJson,
                sweepNeeded = codec.encodeSettings(previous),
                targetSettingsJson = codec.encodeSettings(target),
            ),
        )

        assertEquals(false, bounded?.sweepNeeded)
        assertEquals(target, bounded?.settings)
        assertEquals(true, legacyTarget?.sweepNeeded)
        assertEquals(target, legacyTarget?.settings)
        assertEquals(false, legacyRollback?.sweepNeeded)
        assertEquals(previous, legacyRollback?.settings)
    }

    @Test
    fun `checkpoint codec rejects malformed rows without strict boolean exceptions`() {
        val codec = BackupImportCheckpointCodec()
        val validSettings = codec.encodeSettings(emptySettings)

        listOf(
            BackupImportCheckpointEntity(
                operationId = "123e4567-e89b-12d3-a456-426614174000",
                sweepNeeded = "definitely",
                targetSettingsJson = validSettings,
            ),
            BackupImportCheckpointEntity(
                operationId = "{not-json",
                sweepNeeded = "{}",
                targetSettingsJson = validSettings,
            ),
            BackupImportCheckpointEntity(
                phase = BackupImportCheckpointEntity.PHASE_ROLLBACK_APPLIED,
                operationId = "{not-json",
                sweepNeeded = "{\"reliabilityMode\":\"not-a-boolean\"}",
                targetSettingsJson = validSettings,
            ),
        ).forEach { malformed ->
            assertEquals(null, codec.decodeRecovery(malformed))
        }
    }

    private class TrackingGateway(
        private val events: MutableList<String>,
        private val stageFailure: Throwable? = null,
        private var recoverySettings: PortableProfileSettings? = null,
        private var recoverySweepNeeded: Boolean = true,
        private val completeFailure: Throwable? = null,
        private var completeFailuresRemaining: Int = 0,
    ) : BackupImportGateway {
        override suspend fun stage(preview: BackupImportPreview) {
            stageFailure?.let { throw it }
            events += "database"
            recoverySettings = preview.settings
        }

        override suspend fun pendingRecovery(): BackupImportRecovery? = recoverySettings?.let {
            BackupImportRecovery("operation", it, recoverySweepNeeded)
        }

        override suspend fun complete() {
            if (completeFailuresRemaining > 0) {
                completeFailuresRemaining--
                throw requireNotNull(completeFailure)
            }
            events += "checkpoint-cleanup"
            recoverySettings = null
        }
    }

    private fun emptySnapshot() = BackupDatabaseSnapshot(emptyList(), AppStateEntity(), emptyList())

    private fun schedule(id: String, ownerId: String) = WeighingReminderScheduleEntity(
        id, WeighingReminderOwnerType.ACCOUNT, ownerId, 540, 1,
        WeighingReminderImportance.REGULAR, true, 1, 2,
    )

    private fun document(accountDisplayName: String = "Account") = BackupDocumentV1(
        exportedAt = "2026-08-25T00:00:00Z",
        accounts = listOf(BackupAccountV1("a", accountDisplayName, "account", BackupAccountProfileV1(null, null, null, false), 1, 2)),
        appState = BackupAppStateV1("a", 3.0, false),
        measurements = listOf(BackupMeasurementV1(
            id = "m", fingerprint = "f", measurementType = MeasurementType.WEIGHT_ONLY,
            deviceAddress = "AA:BB", measuredAtEpochSecond = 3, rawPayloadHex = "00ff",
            weightKg = 70.0, rawWeight = 14000, impedanceOhm = null, bmi = null,
            bodyFatPercent = null, bodyFatMassKg = null, waterPercent = null,
            waterMassKg = null, muscleMassKg = null, skeletalMuscleMassKg = null,
            boneMassKg = null, proteinPercent = null, proteinMassKg = null,
            visceralFatLevel = null, basalMetabolicRateKcal = null, metabolicAge = null,
            leanBodyMassKg = null, algorithmVersion = null,
            healthConnectStatus = SyncStatus.LOCAL_ONLY, healthConnectError = null,
            healthConnectWeightSynced = false, createdAtEpochMillis = 4, accountId = "a",
            externalSyncPolicy = ExternalSyncPolicy.USER_LOCAL, sourcePendingId = null,
            deduplicationHash = "d", healthConnectSyncedCalculatedValues = null,
        )),
        settings = BackupSettingsV1("AA:BB", "Scale", true, emptyList(), emptyList()),
    )

    private class FragmentedInputStream(
        private val bytes: ByteArray,
        private val zeroReads: Boolean = false,
        private val failure: Exception? = null,
    ) : InputStream() {
        var closed = false
        private var position = 0
        private var returnZero = zeroReads

        override fun read(): Int = error("Expected bulk read")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (returnZero) {
                returnZero = false
                return 0
            }
            if (position == bytes.size) {
                failure?.let { throw it }
                return -1
            }
            buffer[offset] = bytes[position++]
            returnZero = zeroReads
            return 1
        }

        override fun close() { closed = true }
    }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    @Test
    fun `v6 import preserves explicit nullable measurement heights without using local primary`() {
        val base = document()
        val ownerWithHeight = base.accounts.single().copy(
            id = "owner-height",
            displayName = "Imported A",
            normalizedName = "imported-a",
            profile = BackupAccountProfileV1(166.0, null, null, false),
        )
        val ownerWithoutHeight = ownerWithHeight.copy(
            id = "owner-null",
            displayName = "Imported B",
            normalizedName = "imported-b",
            profile = BackupAccountProfileV1(null, null, null, false),
        )
        val legacy = base.copy(
            schemaVersion = BACKUP_SCHEMA_VERSION,
            accounts = listOf(ownerWithHeight, ownerWithoutHeight),
            appState = base.appState.copy(primaryAccountId = "owner-null"),
            measurements = listOf(
                base.measurements.single().copy(
                    id = "height-measurement",
                    fingerprint = "height-fingerprint",
                    accountId = "owner-height",
                    deduplicationHash = "height-hash",
                    ratingHeightCm = 166.0,
                    ratingHeightOrigin = RatingHeightOrigin.RESTORED_CURRENT_ACCOUNT,
                ),
                base.measurements.single().copy(
                    id = "null-measurement",
                    fingerprint = "null-fingerprint",
                    accountId = "owner-null",
                    deduplicationHash = "null-hash",
                    ratingHeightCm = null,
                    ratingHeightOrigin = RatingHeightOrigin.RESTORED_CURRENT_ACCOUNT,
                ),
            ),
            pets = base.pets.map {
                it.copy(
                    sex = null,
                    breedId = null,
                    birthYear = null,
                    birthMonth = null,
                    birthDay = null,
                    dogAdultWeightCategory = null,
                )
            },
        )
        val localPrimary = AccountEntity(
            "local-primary", "Local", "local", 222.0, null, null, false, 1, 2,
        )
        val current = BackupDatabaseSnapshot(
            accounts = listOf(localPrimary),
            appState = AppStateEntity(primaryAccountId = localPrimary.id),
            measurements = emptyList(),
        )

        val result = service.preview(
            legacy,
            current,
            emptySettings,
            BackupImportMode.MERGE,
        ).result.measurements.associateBy { it.id }

        assertEquals(166.0, result.getValue("height-measurement").ratingHeightCm)
        assertEquals(null, result.getValue("null-measurement").ratingHeightCm)
        assertEquals(
            RatingHeightOrigin.RESTORED_CURRENT_ACCOUNT,
            result.getValue("height-measurement").ratingHeightOrigin,
        )
        assertEquals(
            RatingHeightOrigin.RESTORED_CURRENT_ACCOUNT,
            result.getValue("null-measurement").ratingHeightOrigin,
        )
    }

    @Test
    fun `v6 import preserves captured measurement context exactly`() {
        val source = document().copy(
            measurements = listOf(
                document().measurements.single().copy(
                    ratingHeightCm = 173.25,
                    ratingHeightOrigin = RatingHeightOrigin.CAPTURED,
                ),
            ),
        )

        val imported = service.preview(
            source,
            emptySnapshot(),
            emptySettings,
            BackupImportMode.REPLACE,
        ).result.measurements.single()

        assertEquals(173.25, imported.ratingHeightCm)
        assertEquals(RatingHeightOrigin.CAPTURED, imported.ratingHeightOrigin)
    }
}
