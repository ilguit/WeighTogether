package com.palixander.scalesync.ui.manualweight

import com.palixander.scalesync.domain.*
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ManualWeightStateOwnerTest {
    private val human = ManualWeightOwner.Human(AccountId("person"))
    private val pet = ManualWeightOwner.Pet(PetId("pet"))
    private val clock = Clock.fixed(Instant.parse("2026-09-04T09:12:45Z"), ZoneId.of("Asia/Yekaterinburg"))

    @Test fun openingUsesCurrentLocalMinuteAndReopeningDiscardsDraft() = runBlocking {
        val owner = ManualWeightStateOwner(this, { _, _ -> ManualWeightResult.Invalid }, { _, _ -> }, clock)
        owner.open(human, "Имя")
        val first = requireNotNull(owner.draft.value)
        assertEquals("", first.weight)
        assertEquals(LocalDate.of(2026, 9, 4), first.date)
        assertEquals(LocalTime.of(14, 12), first.time)
        owner.changeWeight("4,125")
        owner.open(pet, "Другой")
        assertEquals(human, owner.draft.value?.owner)
        owner.dismiss()
        assertNull(owner.draft.value)
        owner.open(pet, "Питомец")
        assertEquals("", owner.draft.value?.weight)
        assertNotEquals(first.requestId, owner.draft.value?.requestId)
    }

    @Test fun validationRejectsFutureAndAllowsPastBeforeEpochAndThreeDecimals() = runBlocking {
        val requests = mutableListOf<ManualWeightRequest>()
        val owner = ManualWeightStateOwner(this, { request, _ -> requests += request; ManualWeightResult.Invalid }, { _, _ -> }, clock)
        owner.open(human, "Неполный профиль")
        for (invalid in listOf("0", "-1", "abc", "4.1251")) {
            owner.changeWeight(invalid)
            assertFalse(owner.draft.value!!.canSave)
        }
        owner.changeWeight("0,001")
        owner.changeDate(LocalDate.of(2027, 1, 1))
        assertFalse(owner.draft.value!!.canSave)
        owner.submit()
        yield()
        assertTrue(requests.isEmpty())
        owner.changeDate(LocalDate.of(1800, 1, 1))
        assertTrue(owner.draft.value!!.canSave)
        owner.submit()
        yield()
        assertEquals(0.001, requests.single().weightKg, 0.0)
        assertTrue(requests.single().measuredAt.isBefore(Instant.EPOCH))
    }

    @Test fun duplicateCancelPreservesInputAndConfirmationIsSeparateOperationPermission() = runBlocking {
        val requests = mutableListOf<Pair<ManualWeightRequest, Boolean>>()
        var saved: ManualWeightOwner? = null
        val owner = ManualWeightStateOwner(this, { request, allowed ->
            requests += request to allowed
            if (allowed) ManualWeightResult.Saved("new", request.measuredAt) else ManualWeightResult.Duplicate("old")
        }, { profile, _ -> saved = profile }, clock)
        owner.open(pet, "Кот")
        owner.changeWeight("4.125")
        owner.submit(true) // Cannot invent confirmation before the repository reports a match.
        assertTrue(requests.isEmpty())
        owner.submit()
        yield()
        assertTrue(owner.draft.value!!.duplicate)
        owner.dismissDuplicate()
        assertEquals("4.125", owner.draft.value?.weight)
        owner.submit()
        yield()
        owner.submit(true)
        yield()
        assertEquals(listOf(false, false, true), requests.map { it.second })
        assertEquals(1, requests.map { it.first.requestId }.distinct().size)
        assertEquals(pet, saved)
        assertNull(owner.draft.value)
    }

    @Test fun doubleClickIsIgnoredAndFailedSaveRetainsUuidAndFieldsForRetry() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val requests = mutableListOf<ManualWeightRequest>()
        val owner = ManualWeightStateOwner(this, { request, _ ->
            requests += request
            gate.await()
            if (requests.size == 1) error("storage unavailable")
            ManualWeightResult.Saved("saved", request.measuredAt)
        }, { _, _ -> }, clock)
        owner.open(human, "Имя")
        owner.changeWeight("4,125")
        owner.submit()
        owner.submit()
        yield()
        assertEquals(1, requests.size)
        assertTrue(owner.draft.value!!.saving)
        gate.complete(Unit)
        yield()
        assertEquals("4,125", owner.draft.value?.weight)
        assertNotNull(owner.draft.value?.error)
        owner.submit()
        yield()
        assertEquals(requests.first(), requests.last())
        assertNull(owner.draft.value)
    }

    @Test fun deletedOwnerCannotBeReassignedAndClosingDuringSaveDoesNotReplaceNewDraft() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var savedCalls = 0
        val owner = ManualWeightStateOwner(this, { request, _ -> gate.await(); ManualWeightResult.Saved("saved", request.measuredAt) }, { _, _ -> savedCalls++ }, clock)
        owner.open(human, "Первый")
        owner.changeWeight("70")
        owner.setOwnerAvailable(false)
        assertFalse(owner.draft.value!!.canSave)
        owner.open(pet, "Второй")
        assertEquals(human, owner.draft.value?.owner)
        owner.setOwnerAvailable(true)
        owner.submit()
        yield()
        owner.dismiss()
        owner.open(pet, "Второй")
        gate.complete(Unit)
        yield()
        assertEquals(pet, owner.draft.value?.owner)
        assertEquals(0, savedCalls)
    }

    @Test fun repositoryOwnerDeletionLeavesFormUnavailableAndRetryableFailuresDoNotClearIt() = runBlocking {
        val owner = ManualWeightStateOwner(this, { _, _ -> ManualWeightResult.OwnerUnavailable }, { _, _ -> fail() }, clock)
        owner.open(pet, "Кот")
        owner.changeWeight("4")
        owner.submit()
        yield()
        assertFalse(owner.draft.value!!.ownerAvailable)
        assertEquals("4", owner.draft.value?.weight)
    }

    @Test fun nonexistentDstLocalMinuteIsRejected() = runBlocking {
        val owner = ManualWeightStateOwner(this, { _, _ -> fail(); ManualWeightResult.Invalid }, { _, _ -> },
            Clock.fixed(clock.instant(), ZoneId.of("Europe/Berlin")))
        owner.open(human, "Имя")
        owner.changeWeight("1")
        owner.changeDate(LocalDate.of(2026, 3, 29))
        owner.changeTime(LocalTime.of(2, 30))
        assertFalse(owner.draft.value!!.canSave)
        assertNotNull(owner.draft.value?.dateError)
    }
}
