package com.palixander.scalesync.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.AccountUpdate
import com.palixander.scalesync.domain.NewAccount
import com.palixander.scalesync.domain.NewPet
import com.palixander.scalesync.domain.PetSpecies
import com.palixander.scalesync.domain.PetUpdate
import com.palixander.scalesync.domain.ProfileHistoryUpdateMode
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
