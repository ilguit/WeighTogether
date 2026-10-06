package com.palixander.weightogether

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.palixander.weightogether.ui.theme.ScaleSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PrivacyPolicyTest {
    @get:Rule val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun settingsRowOpensOfflinePolicyActivity() {
        composeRule.setContent {
            ScaleSyncTheme {
                SettingsScreen(
                    state = MainUiState(),
                    callbacks = SettingsCallbacks(
                        onHealthConnectAuthorization = {},
                        onHealthConnectAccessManagement = {},
                        onManualScan = {},
                        onReliabilityMode = {},
                        openBatterySettings = {},
                        openApplicationSettings = {},
                    ),
                    contentPadding = PaddingValues(),
                )
            }
        }
        composeRule.onNodeWithTag(SettingsScreenTestTags.PrivacyPolicyRow)
            .performScrollTo().performClick()
        val intent = shadowOf(context as Application).nextStartedActivity
        assertEquals(ComponentName(context, PrivacyPolicyActivity::class.java), intent.component)
    }

    @Test
    fun policyShowsBundledTextAndReturnsViaBack() {
        val policy = context.resources.openRawResource(R.raw.privacy_policy_ru)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        var returned = false
        composeRule.setContent {
            ScaleSyncTheme { PrivacyPolicyScreen(policy, onBack = { returned = true }) }
        }
        composeRule.onNodeWithText("Политика конфиденциальности Weigh Together").assertIsDisplayed()
        assertTrue(policy.contains("Александр Палицин, i@palixander.ru"))
        composeRule.onNodeWithTag("privacy-policy-back").performClick()
        assertTrue(returned)
    }

    @Test
    fun healthConnectRationaleResolvesDirectlyToPolicyInsteadOfDiary() {
        val intent = Intent("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE")
            .setPackage(context.packageName)
        val activities = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(listOf(PrivacyPolicyActivity::class.java.name), activities.map { it.activityInfo.name })
    }

    @Test
    fun androidHealthPermissionUsageAliasTargetsPolicyAndKeepsSystemPermission() {
        val intent = Intent("android.intent.action.VIEW_PERMISSION_USAGE")
            .addCategory("android.intent.category.HEALTH_PERMISSIONS")
            .setPackage(context.packageName)
        val activity = context.packageManager.queryIntentActivities(intent, 0).single().activityInfo
        assertEquals(PrivacyPolicyActivity::class.java.name, activity.targetActivity)
        assertEquals("android.permission.START_VIEW_PERMISSION_USAGE", activity.permission)
    }

    @Test
    fun externalRationaleOpensWithoutRequestingPermissionsOrLaunchingDiary() {
        val intent = Intent("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE")
        Robolectric.buildActivity(PrivacyPolicyActivity::class.java, intent).use { controller ->
            val activity = controller.setup().get()
            assertNull(shadowOf(activity).lastRequestedPermission)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }
}
