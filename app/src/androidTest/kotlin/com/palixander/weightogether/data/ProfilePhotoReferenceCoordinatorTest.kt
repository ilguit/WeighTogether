package com.palixander.weightogether.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palixander.weightogether.core.Sex
import com.palixander.weightogether.domain.AccountProfile
import com.palixander.weightogether.domain.AccountUpdate
import com.palixander.weightogether.domain.NewAccount
import com.palixander.weightogether.domain.NewPet
import com.palixander.weightogether.domain.PetSpecies
import com.palixander.weightogether.domain.PetUpdate
import com.palixander.weightogether.domain.ProfileHistoryUpdateMode
import com.palixander.weightogether.profile.ProfilePhotoStore
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfilePhotoReferenceCoordinatorTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun concurrentAccountPetPhotoSwapDoesNotDeleteEitherIncomingPath() = runBlocking {
        val deleted = mutableListOf<String>()
        val coordinator = ProfilePhotoReferenceCoordinator(database) { deleted += it }
        val accounts = accountRepository(coordinator)
        val pets = petRepository(coordinator)
        val account = accounts.createAccount(newAccount("A"))
        val pet = pets.createPet(NewPet("Pet", PetSpecies.CAT, photoPath = PHOTO_B))

        val releaseOperations = CompletableDeferred<Unit>()
        val operationHeld = CompletableDeferred<Unit>()
        val holder = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.mutate(emptySet()) {
                operationHeld.complete(Unit)
                releaseOperations.await()
                ProfilePhotoMutation(Unit)
            }
        }
        operationHeld.await()
        val accountUpdate = async(start = CoroutineStart.UNDISPATCHED) {
            accounts.updateAccount(
                AccountUpdate(account.id, account.displayName, PROFILE, PHOTO_B),
                ProfileHistoryUpdateMode.KEEP_EXISTING,
            )
        }
        val petUpdate = async(start = CoroutineStart.UNDISPATCHED) {
            pets.updatePet(PetUpdate(pet.id, pet.displayName, pet.species, photoPath = PHOTO_A))
        }

        releaseOperations.complete(Unit)
        holder.await()
        accountUpdate.await()
        petUpdate.await()

        assertEquals(PHOTO_B, accounts.getAccount(account.id)?.photoPath)
        assertEquals(PHOTO_A, pets.getPet(pet.id)?.photoPath)
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun pathIsDeletedOnlyAfterLastCrossOwnerReferenceIsRemoved() = runBlocking {
        val deleted = mutableListOf<String>()
        val coordinator = ProfilePhotoReferenceCoordinator(database) { deleted += it }
        val accounts = accountRepository(coordinator)
        val pets = petRepository(coordinator)
        val account = accounts.createAccount(newAccount("A"))
        val pet = pets.createPet(NewPet("Pet", PetSpecies.CAT, photoPath = PHOTO_A))

        accounts.updateAccount(
            AccountUpdate(account.id, account.displayName, PROFILE, PHOTO_B),
            ProfileHistoryUpdateMode.KEEP_EXISTING,
        )
        assertEquals(emptyList<String>(), deleted)

        pets.updatePet(PetUpdate(pet.id, pet.displayName, pet.species, photoPath = PHOTO_B))
        assertEquals(listOf(PHOTO_A), deleted)
    }

    @Test
    fun cancellationAfterCommitStillCompletesPostReleaseReconciliation() = runBlocking {
        val lifecycleStarted = CompletableDeferred<Unit>()
        val releaseLifecycle = CompletableDeferred<Unit>()
        val deleted = mutableListOf<String>()
        val coordinator = ProfilePhotoReferenceCoordinator(database) { path ->
            lifecycleStarted.complete(Unit)
            releaseLifecycle.await()
            deleted += path
        }
        val accounts = accountRepository(coordinator)
        val account = accounts.createAccount(newAccount("A"))

        val update = launch(start = CoroutineStart.UNDISPATCHED) {
            accounts.updateAccount(
                AccountUpdate(account.id, account.displayName, PROFILE, PHOTO_B),
                ProfileHistoryUpdateMode.KEEP_EXISTING,
            )
        }
        lifecycleStarted.await()
        update.cancel()
        releaseLifecycle.complete(Unit)
        update.join()

        assertTrue(update.isCancelled)
        assertEquals(PHOTO_B, accounts.getAccount(account.id)?.photoPath)
        assertEquals(listOf(PHOTO_A), deleted)
    }

    @Test
    fun failedLifecycleKeepsCandidateForSuccessfulRetry() = runBlocking {
        val attempts = mutableListOf<String>()
        var failLifecycle = true
        val coordinator = ProfilePhotoReferenceCoordinator(database) { path ->
            attempts += path
            if (failLifecycle) error("filesystem unavailable")
        }
        val accounts = accountRepository(coordinator)
        val account = accounts.createAccount(newAccount("A"))

        val firstUpdate = runCatching {
            accounts.updateAccount(
                AccountUpdate(account.id, account.displayName, PROFILE, PHOTO_B),
                ProfileHistoryUpdateMode.KEEP_EXISTING,
            )
        }
        assertTrue(firstUpdate.isFailure)
        assertEquals(PHOTO_B, accounts.getAccount(account.id)?.photoPath)
        assertEquals(listOf(PHOTO_A), attempts)

        failLifecycle = false
        coordinator.mutate<Unit>(emptySet()) { ProfilePhotoMutation(Unit) }

        assertEquals(listOf(PHOTO_A, PHOTO_A), attempts)
    }

    @Test
    fun productionLifecycleDeleteFailureIsRetriedUntilFileIsRemoved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val photoFile = File(context.filesDir, PHOTO_A).apply {
            parentFile?.mkdirs()
            writeText("photo")
        }
        var allowDelete = false
        val store = ProfilePhotoStore.createForTest(context) { file ->
            allowDelete && file.delete()
        }
        val coordinator = ProfilePhotoReferenceCoordinator(database, store)
        val accounts = accountRepository(coordinator)
        val account = accounts.createAccount(newAccount("A"))

        val firstUpdate = runCatching {
            accounts.updateAccount(
                AccountUpdate(account.id, account.displayName, PROFILE, PHOTO_B),
                ProfileHistoryUpdateMode.KEEP_EXISTING,
            )
        }

        assertTrue(firstUpdate.isFailure)
        assertTrue(photoFile.isFile)
        allowDelete = true

        coordinator.mutate<Unit>(emptySet()) { ProfilePhotoMutation(Unit) }

        assertTrue(!photoFile.exists())
    }

    private fun accountRepository(coordinator: ProfilePhotoReferenceCoordinator) =
        RoomAccountRepository(
            database = database,
            now = { Instant.EPOCH },
            newId = { "account" },
            photoReferences = coordinator,
        )

    private fun petRepository(coordinator: ProfilePhotoReferenceCoordinator) =
        RoomPetRepository(
            database = database,
            now = { Instant.EPOCH },
            newId = { "pet" },
            photoReferences = coordinator,
        )

    private fun newAccount(name: String) = NewAccount(name, PROFILE, PHOTO_A)

    private companion object {
        const val PHOTO_A = "profile-photos/shared/a.webp"
        const val PHOTO_B = "profile-photos/shared/b.webp"
        val PROFILE = AccountProfile.Complete(
            heightCm = 170.0,
            birthDate = LocalDate.of(1990, 1, 1),
            sex = Sex.FEMALE,
        )
    }
}
