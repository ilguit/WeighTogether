package com.palixander.scalesync.reminder

import android.app.Application
import android.provider.Settings
import com.palixander.scalesync.NotificationChannelRegistry
import com.palixander.scalesync.domain.WeighingReminderImportance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WeighingReminderCapabilityTest {
    @Test
    fun regularScheduleIgnoresAlarmOnlyCapabilities() {
        val capability = capability(
            alarmChannelAllowed = false,
            exactAlarmsAllowed = false,
        )

        assertTrue(capability.canPublish(WeighingReminderImportance.REGULAR))
        assertEquals(emptyList<WeighingReminderCapabilityIssue>(), capability.issuesFor(setOf(WeighingReminderImportance.REGULAR)))
    }

    @Test
    fun alarmScheduleIgnoresRegularChannelAndReportsAlarmRequirements() {
        val capability = capability(
            regularChannelAllowed = false,
            alarmChannelAllowed = false,
            exactAlarmsAllowed = false,
        )

        assertEquals(
            listOf(
                WeighingReminderCapabilityIssue.ALARM_CHANNEL,
                WeighingReminderCapabilityIssue.EXACT_ALARM,
            ),
            capability.issuesFor(setOf(WeighingReminderImportance.ALARM)),
        )
    }

    @Test
    fun commonNotificationFailuresApplyOnlyWhenAtLeastOneScheduleIsEnabled() {
        val capability = capability(
            postNotificationsAllowed = false,
            appNotificationsAllowed = false,
        )

        assertEquals(emptyList<WeighingReminderCapabilityIssue>(), capability.issuesFor(emptySet()))
        assertEquals(
            listOf(
                WeighingReminderCapabilityIssue.POST_NOTIFICATIONS,
                WeighingReminderCapabilityIssue.APP_NOTIFICATIONS,
            ),
            capability.issuesFor(setOf(WeighingReminderImportance.REGULAR)),
        )
    }

    @Test
    fun channelIssuesOpenTheirOwnChannelThenNotificationAndAppFallbacks() {
        val gateway = WeighingReminderCapabilityGateway(RuntimeEnvironment.getApplication())

        val regular = gateway.settingsIntents(WeighingReminderCapabilityIssue.REGULAR_CHANNEL)
        val alarm = gateway.settingsIntents(WeighingReminderCapabilityIssue.ALARM_CHANNEL)

        assertEquals(NotificationChannelRegistry.weighingReminders.id, regular.first().getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(NotificationChannelRegistry.weighingAlarms.id, alarm.first().getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, regular[0].action)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, regular[1].action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, regular[2].action)
    }

    @Test
    fun notificationAndExactAlarmIssuesUseFocusedSettingsWithFallbacks() {
        val gateway = WeighingReminderCapabilityGateway(RuntimeEnvironment.getApplication())

        val notification = gateway.settingsIntents(WeighingReminderCapabilityIssue.POST_NOTIFICATIONS)
        val exactAlarm = gateway.settingsIntents(WeighingReminderCapabilityIssue.EXACT_ALARM)

        assertEquals(
            listOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            notification.map { it.action },
        )
        assertEquals(
            listOf(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            exactAlarm.map { it.action },
        )
    }

    private fun capability(
        postNotificationsAllowed: Boolean = true,
        appNotificationsAllowed: Boolean = true,
        regularChannelAllowed: Boolean = true,
        alarmChannelAllowed: Boolean = true,
        exactAlarmsAllowed: Boolean = true,
    ) = WeighingReminderCapability(
        postNotificationsAllowed = postNotificationsAllowed,
        appNotificationsAllowed = appNotificationsAllowed,
        regularChannelAllowed = regularChannelAllowed,
        alarmChannelAllowed = alarmChannelAllowed,
        exactAlarmsAllowed = exactAlarmsAllowed,
    )
}
