package com.palixander.scalesync

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.WeighingReminderOwner
import com.palixander.scalesync.reminder.WeighingReminderNavigationTarget
import com.palixander.scalesync.ui.profiles.ProfileKey
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
}
