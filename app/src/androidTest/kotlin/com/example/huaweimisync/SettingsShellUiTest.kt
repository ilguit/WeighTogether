package com.example.huaweimisync

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.data.AppSettings
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.AccountSettings
import com.example.huaweimisync.measurements.MeasurementsCallbacks
import com.example.huaweimisync.measurements.MeasurementsDestination
import java.time.LocalDate
import java.time.Instant
import com.example.huaweimisync.ui.accounts.AccountManagementCallbacks
import com.example.huaweimisync.ui.accounts.AccountManagementTestTags
import com.example.huaweimisync.ui.accounts.AccountManagementUiState
import com.example.huaweimisync.ui.accounts.reduceAccountManagement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsShellUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun accountsSectionReplacesLegacyProfileAndOpensAccountEditor() {
        setSettingsShell()

        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileRow).assertDoesNotExist()
        composeRule.onNodeWithTag(AccountManagementTestTags.List).assertIsDisplayed()
        composeRule.onNodeWithTag(AccountManagementTestTags.Add).performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.Editor).assertIsDisplayed()
        composeRule.onNodeWithText("Отмена").performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.Editor).assertDoesNotExist()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertIsDisplayed()
    }

    @Test
    fun invalidAccountDraftShowsValidationErrors() {
        setSettingsShell()
        composeRule.onNodeWithTag(AccountManagementTestTags.Add).performClick()

        composeRule.onNodeWithTag(AccountManagementTestTags.Editor).assertIsDisplayed()
        composeRule.onNodeWithText("Введите имя от 1 до 50 символов").assertIsDisplayed()
        composeRule.onNodeWithText("Допустимый рост: 100–230 см").assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.SnackbarHost).assertExists()
    }

    @Test
    fun additionalToggleRevealsExistingActions() {
        setSettingsShell()

        composeRule.onNodeWithTag(SettingsScreenTestTags.AdditionalContent).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsScreenTestTags.AdditionalToggle)
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag(SettingsScreenTestTags.AdditionalContent).assertExists()
        composeRule.onNodeWithText("Отправить тест").assertExists()
        composeRule.onNodeWithText("Повышенная надёжность").assertExists()
        composeRule.onNodeWithText("Батарея").assertExists()
        composeRule.onNodeWithText("Настройки приложения").assertExists()
    }

    @Test
    fun huaweiCheckFailureRetriesPermissionRefreshInsteadOfAuthorization() {
        var refreshCalls = 0
        var authorizationCalls = 0
        setSettingsShell(
            huawei = HuaweiIntegrationUiState(HuaweiIntegrationStatus.CHECK_FAILED),
            onHuaweiAuthorization = { authorizationCalls++ },
            onHuaweiPermissionRefresh = { refreshCalls++ },
        )

        composeRule.onNodeWithText("Повторить").performClick()

        composeRule.runOnIdle {
            assertEquals(1, refreshCalls)
            assertEquals(0, authorizationCalls)
        }
    }

    private fun setSettingsShell(
        huawei: HuaweiIntegrationUiState = HuaweiIntegrationUiState(),
        onHuaweiAuthorization: () -> Unit = {},
        onHuaweiPermissionRefresh: () -> Unit = {},
    ) {
        val profile = AccountProfile.Complete(
            heightCm = 181.5,
            birthDate = LocalDate.of(1988, 2, 29),
            sex = Sex.FEMALE,
        )
        val account = Account(
            id = AccountId("primary"),
            displayName = "Анна",
            profile = profile,
            createdAt = Instant.parse("2026-08-15T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-15T00:00:00Z"),
        )

        composeRule.setContent {
            val management = remember {
                mutableStateOf(
                    AccountManagementUiState(
                        accounts = listOf(account),
                        primaryAccountId = account.id,
                    ),
                )
            }
            val snackbarHostState = remember { SnackbarHostState() }
            HuaweiMiSyncScaffold(
                state = MainUiState(
                    settings = AppSettings(),
                    huawei = huawei,
                    accounts = listOf(account),
                    accountSettings = AccountSettings(primaryAccountId = account.id),
                    accountManagement = management.value,
                ),
                currentSection = AppSection.SETTINGS,
                measurementsDestination = MeasurementsDestination.SUMMARY,
                measurementsCallbacks = MeasurementsCallbacks.None,
                snackbarHostState = snackbarHostState,
                onSectionSelected = {},
                onCloseProfile = {},
                onSaveProfile = {},
                onProfileHeightChanged = {},
                onProfileBirthDateChanged = {},
                onProfileSexChanged = {},
                settingsCallbacks = settingsCallbacks(
                    onHuaweiAuthorization = onHuaweiAuthorization,
                    onHuaweiPermissionRefresh = onHuaweiPermissionRefresh,
                    accountManagement = AccountManagementCallbacks.None.copy(
                        onAction = { management.value = reduceAccountManagement(management.value, it) },
                    ),
                ),
                measurementsContent = {},
                chartsContent = {},
            )
        }
    }

    private fun settingsCallbacks(
        onHuaweiAuthorization: () -> Unit = {},
        onHuaweiPermissionRefresh: () -> Unit = {},
        accountManagement: AccountManagementCallbacks = AccountManagementCallbacks.None,
    ) = SettingsCallbacks(
        onHuaweiAuthorization = onHuaweiAuthorization,
        onHuaweiPermissionRefresh = onHuaweiPermissionRefresh,
        onHealthConnectAuthorization = {},
        onHealthConnectAccessManagement = {},
        onManualTest = { _, _ -> },
        onManualScan = {},
        onReliabilityMode = {},
        openBatterySettings = {},
        openApplicationSettings = {},
        accountManagement = accountManagement,
    )
}
