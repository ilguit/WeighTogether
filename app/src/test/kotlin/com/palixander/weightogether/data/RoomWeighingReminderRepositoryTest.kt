package com.palixander.weightogether.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.domain.WeighingReminderId
import com.palixander.weightogether.domain.WeighingReminderImportance
import com.palixander.weightogether.domain.WeighingReminderOwner
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomWeighingReminderRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ids = ArrayDeque(listOf("schedule", "regular", "snooze", "next-regular"))
    private val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .addCallback(object : androidx.room.RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = createWeighingReminderOwnerTriggers(db)
        })
        .allowMainThreadQueries()
        .build()
    private val repository = RoomWeighingReminderRepository(database, newId = { ids.removeFirst() })

    @After
    fun close() = database.close()

    @Test
    fun duplicateUsesNormalizedWeekdaySetAndOwnerDeletionCascadesRuntime() = runBlocking {
        insertAccount("account")
        val owner = WeighingReminderOwner.Account(AccountId("account"))
        val first = repository.create(draft(owner, linkedSetOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))
        val duplicate = repository.create(draft(owner, linkedSetOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)))

        assertTrue(first is SaveWeighingReminderResult.Saved)
        assertEquals(SaveWeighingReminderResult.Duplicate, duplicate)
        assertEquals(listOf(WeighingReminderId("schedule")), repository.idsForOwner(owner))
        database.accountDao().delete("account")
        assertTrue(repository.observe(owner).first().isEmpty())
        assertNull(database.weighingReminderDao().getRuntime("schedule"))
    }

    @Test
    fun regularAtSameInstantWinsAndDuplicateActionsAreIgnored() = runBlocking {
        insertAccount("account")
        val owner = WeighingReminderOwner.Account(AccountId("account"))
        repository.create(draft(owner))
        val id = WeighingReminderId("schedule")
        val regular = requireNotNull(repository.prepareRegularOccurrence(id, 1_000))
        assertEquals(ReminderClaimResult.Publish(regular), repository.claimDue(id, ReminderCallbackKind.REGULAR, regular))
        val snooze = requireNotNull(repository.snooze(id, regular, 2_000))
        repository.prepareRegularOccurrence(id, 2_000)

        assertEquals(ReminderClaimResult.NoOp, repository.claimDue(id, ReminderCallbackKind.SNOOZE, snooze))
        assertEquals(
            ReminderClaimResult.Publish("next-regular"),
            repository.claimDue(id, ReminderCallbackKind.REGULAR, "next-regular"),
        )
        assertTrue(repository.consumeAction(id, "next-regular"))
        assertFalse(repository.consumeAction(id, "next-regular"))
    }

    @Test
    fun discardingFailedNextRegularPreservesActiveOccurrenceActions() = runBlocking {
        insertAccount("account")
        repository.create(draft(WeighingReminderOwner.Account(AccountId("account"))))
        val id = WeighingReminderId("schedule")
        val active = requireNotNull(repository.prepareRegularOccurrence(id, 1_000))
        repository.claimDue(id, ReminderCallbackKind.REGULAR, active)
        val failedNext = requireNotNull(repository.prepareRegularOccurrence(id, 2_000))

        assertTrue(repository.discardRegularOccurrence(id, failedNext))
        assertEquals(active, repository.snapshot(id)?.activeOccurrenceToken)
        assertTrue(repository.consumeAction(id, active))
        assertFalse(repository.consumeAction(id, active))
    }

    @Test
    fun earlierSnoozeWinsRegardlessOfRegularCallbackOrder() = runBlocking {
        insertAccount("account")
        repository.create(draft(WeighingReminderOwner.Account(AccountId("account"))))
        val id = WeighingReminderId("schedule")
        val regular = requireNotNull(repository.prepareRegularOccurrence(id, 1_000))
        repository.claimDue(id, ReminderCallbackKind.REGULAR, regular)
        val snooze = requireNotNull(repository.snooze(id, regular, 2_000))
        val laterRegular = requireNotNull(repository.prepareRegularOccurrence(id, 3_000))

        assertEquals(ReminderClaimResult.NoOp, repository.claimDue(id, ReminderCallbackKind.REGULAR, laterRegular))
        assertEquals(ReminderClaimResult.Publish(snooze), repository.claimDue(id, ReminderCallbackKind.SNOOZE, snooze))
        assertEquals(ReminderClaimResult.NoOp, repository.claimDue(id, ReminderCallbackKind.REGULAR, laterRegular))
    }

    @Test
    fun earlierSnoozeInvalidatesLaterRegularWhenSnoozeCallbackArrivesFirst() = runBlocking {
        insertAccount("account")
        repository.create(draft(WeighingReminderOwner.Account(AccountId("account"))))
        val id = WeighingReminderId("schedule")
        val regular = requireNotNull(repository.prepareRegularOccurrence(id, 1_000))
        repository.claimDue(id, ReminderCallbackKind.REGULAR, regular)
        val snooze = requireNotNull(repository.snooze(id, regular, 2_000))
        val laterRegular = requireNotNull(repository.prepareRegularOccurrence(id, 3_000))

        assertEquals(ReminderClaimResult.Publish(snooze), repository.claimDue(id, ReminderCallbackKind.SNOOZE, snooze))
        assertEquals(ReminderClaimResult.NoOp, repository.claimDue(id, ReminderCallbackKind.REGULAR, laterRegular))
    }

    @Test
    fun enabledSnapshotIsConsistentAndExcludesDisabledSchedules() = runBlocking {
        insertAccount("account")
        val owner = WeighingReminderOwner.Account(AccountId("account"))
        repository.create(draft(owner))
        val id = WeighingReminderId("schedule")
        val token = requireNotNull(repository.prepareRegularOccurrence(id, 1_000))

        val snapshot = repository.snapshotEnabled().single()
        assertEquals(id, snapshot.schedule.id)
        assertEquals(token, snapshot.regularOccurrenceToken)
        assertEquals(ReminderOccurrenceStatus.SCHEDULED, snapshot.regularStatus)

        repository.setEnabled(id, false)
        assertTrue(repository.snapshotEnabled().isEmpty())
    }

    @Test
    fun ownerPresentationIncludesManagedPhotoPath() = runBlocking {
        insertAccount("account", "profile-photos/accounts/account/avatar.jpg")
        val presentation = repository.ownerPresentation(
            WeighingReminderOwner.Account(AccountId("account")),
        )

        assertEquals("Alice", presentation?.displayName)
        assertEquals("profile-photos/accounts/account/avatar.jpg", presentation?.photoPath)
    }

    @Test
    fun editCancelsPendingSnoozeAndInvalidatesTokens() = runBlocking {
        insertAccount("account")
        val owner = WeighingReminderOwner.Account(AccountId("account"))
        repository.create(draft(owner))
        val id = WeighingReminderId("schedule")
        val regular = requireNotNull(repository.prepareRegularOccurrence(id, 1_000))
        repository.claimDue(id, ReminderCallbackKind.REGULAR, regular)
        val snooze = requireNotNull(repository.snooze(id, regular, 2_000))

        repository.update(id, draft(owner).copy(time = LocalTime.of(10, 0)))

        assertEquals(ReminderClaimResult.NoOp, repository.claimDue(id, ReminderCallbackKind.SNOOZE, snooze))
    }

    @Test
    fun alarmSoundIsStoredOnlyForAlarmSchedules() = runBlocking {
        insertAccount("account")
        val owner = WeighingReminderOwner.Account(AccountId("account"))
        val alarm = draft(owner).copy(
            importance = WeighingReminderImportance.ALARM,
            alarmSoundUri = "content://media/alarm/7",
        )
        repository.create(alarm)

        assertEquals("content://media/alarm/7", repository.get(WeighingReminderId("schedule"))?.alarmSoundUri)
        repository.update(WeighingReminderId("schedule"), alarm.copy(importance = WeighingReminderImportance.REGULAR))
        assertNull(repository.get(WeighingReminderId("schedule"))?.alarmSoundUri)
    }

    @Test
    fun expiredSnoozeCannotSuppressNextRegularOccurrence() = runBlocking {
        insertAccount("account")
        val owner = WeighingReminderOwner.Account(AccountId("account"))
        repository.create(draft(owner))
        val id = WeighingReminderId("schedule")
        val regular = requireNotNull(repository.prepareRegularOccurrence(id, 1_000))
        repository.claimDue(id, ReminderCallbackKind.REGULAR, regular)
        val snooze = requireNotNull(repository.snooze(id, regular, 2_000))

        assertTrue(repository.expireSnooze(id, 2_000))
        val nextRegular = requireNotNull(repository.prepareRegularOccurrence(id, 3_000))

        assertEquals(
            ReminderClaimResult.Publish(nextRegular),
            repository.claimDue(id, ReminderCallbackKind.REGULAR, nextRegular),
        )
        assertEquals(ReminderClaimResult.NoOp, repository.claimDue(id, ReminderCallbackKind.SNOOZE, snooze))
    }

    private suspend fun insertAccount(id: String, photoPath: String? = null) {
        database.accountDao().insert(
            AccountEntity(id, "Alice", "alice", null, null, null, false, 1, 1, photoPath),
        )
    }

    private fun draft(
        owner: WeighingReminderOwner,
        weekdays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY),
    ) = WeighingReminderDraft(
        owner = owner,
        time = LocalTime.of(9, 0),
        weekdays = weekdays,
        importance = WeighingReminderImportance.REGULAR,
    )
}
