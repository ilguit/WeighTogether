package com.palixander.weightogether

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.palixander.weightogether.domain.AccountId
import com.palixander.weightogether.domain.PetId
import com.palixander.weightogether.domain.WeighingReminderOwner
import com.palixander.weightogether.reminder.WeighingReminderNavigationTarget
import com.palixander.weightogether.ui.profiles.ProfileKey
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
class ReminderProfileNavigationTest {
    @Test
    fun reminderMainActivityUsesSingleTopForWarmNavigation() {
        val info = ApplicationProvider.getApplicationContext<Application>().packageManager
            .getActivityInfo(
                android.content.ComponentName(
                    ApplicationProvider.getApplicationContext(),
                    MainActivity::class.java,
                ),
                0,
            )

        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }
    @Test
    fun coldStartIntentSelectsHumanProfileWithoutMeasurementAction() {
        val intent = WeighingReminderNavigationTarget(
            WeighingReminderOwner.Account(AccountId("human-1")),
        ).putInto(Intent().setAction(WeighingReminderNavigationTarget.ACTION_OPEN_PROFILE))

        val target = reminderProfileTarget(intent)

        assertEquals(ProfileKey.Human(AccountId("human-1")), target.profileKey)
        assertFalse(target.ownerUnavailable)
        assertEquals(WeighingReminderNavigationTarget.ACTION_OPEN_PROFILE, intent.action)
    }

    @Test
    fun warmStartIntentSelectsPetProfile() {
        val intent = WeighingReminderNavigationTarget(
            WeighingReminderOwner.Pet(PetId("pet-1")),
        ).putInto(Intent().setAction(WeighingReminderNavigationTarget.ACTION_OPEN_PROFILE))

        assertEquals(ProfileKey.Pet(PetId("pet-1")), reminderProfileTarget(intent).profileKey)
    }

    @Test
    fun repeatedIntentIsConsumedOnlyOnce() {
        val intent = WeighingReminderNavigationTarget(
            WeighingReminderOwner.Account(AccountId("human-1")),
        ).putInto(Intent().setAction(WeighingReminderNavigationTarget.ACTION_OPEN_PROFILE))

        assertEquals(ProfileKey.Human(AccountId("human-1")), consumeReminderProfileTarget(intent)?.profileKey)
        assertNull(consumeReminderProfileTarget(intent))
        assertNull(intent.action)
    }

    @Test
    fun staleOwnerFallsBackAndShowsUnavailableMessage() {
        val request = ReminderProfileNavigationRequest(1, ProfileKey.Pet(PetId("deleted")))

        val result = resolveReminderProfileNavigation(request, emptyList())

        assertNull(result.profileKey)
        assertTrue(result.showUnavailableMessage)
    }

    @Test
    fun existingHumanAndPetOwnersResolveWithoutFallback() {
        val humanKey = ProfileKey.Human(AccountId("human-1"))
        val petKey = ProfileKey.Pet(PetId("pet-1"))
        val profiles = listOf(humanKey, petKey)

        assertEquals(humanKey, resolveReminderProfileNavigation(ReminderProfileNavigationRequest(1, humanKey), profiles).profileKey)
        assertEquals(petKey, resolveReminderProfileNavigation(ReminderProfileNavigationRequest(2, petKey), profiles).profileKey)
    }

    @Test
    fun reminderRouteWinsOverRestoredPetAndMeasurementAccountInSameFrame() {
        val reminderHuman = ProfileKey.Human(AccountId("reminder-human"))
        val restoredPet = ProfileKey.Pet(PetId("last-pet"))

        val result = reminderWinningProfileKey(
            reminderNavigation = ReminderProfileNavigationRequest(3, reminderHuman),
            navigation = com.palixander.weightogether.ui.profiles.ProfileNavigationState().select(restoredPet),
            selectedMeasurementAccountId = AccountId("last-human"),
        )

        assertEquals(reminderHuman, result)
    }

    @Test
    fun restoredSelectionIsUsedAfterReminderRouteIsConsumed() {
        val restoredPet = ProfileKey.Pet(PetId("last-pet"))

        val result = reminderWinningProfileKey(
            reminderNavigation = null,
            navigation = com.palixander.weightogether.ui.profiles.ProfileNavigationState().select(restoredPet),
            selectedMeasurementAccountId = AccountId("last-human"),
        )

        assertEquals(restoredPet, result)
    }
}
