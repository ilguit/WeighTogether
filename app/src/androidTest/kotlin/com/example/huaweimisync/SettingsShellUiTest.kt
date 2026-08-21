package com.example.huaweimisync

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
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
import com.example.huaweimisync.ui.accounts.WeightDeltaEditorTestTags
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
    fun ignoreUnknownSettingIsNextToRecognitionAndDispatchesSavedPolicyChange() {
        var enabled: Boolean? = null
        setSettingsShell(onIgnoreUnknownMeasurementsChanged = { enabled = it })

        composeRule.onNodeWithTag(WeightDeltaEditorTestTags.IgnoreUnknown)
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle { assertEquals(true, enabled) }
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

    @Test
    fun availableHealthConnectRowOpensManagementWithoutPrimaryAccount() {
        var managementCalls = 0
        setSettingsShell(
            healthConnect = availableHealthConnectWithMissingPermissions(),
            account = null,
            onHealthConnectAccessManagement = { managementCalls++ },
        )

        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectRow)
            .performScrollTo()
            .assertHasClickAction()
            .assertContentDescriptionEquals(SettingsScreenContentDescriptions.HealthConnectRow)
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, managementCalls)
        }
    }

    @Test
    fun availableHealthConnectRowOpensManagementWithIncompletePrimaryProfile() {
        var managementCalls = 0
        val incompleteAccount = completeAccount().copy(
            profile = AccountProfile.IncompleteRecovery(heightCm = 181.5),
        )
        setSettingsShell(
            healthConnect = availableHealthConnectWithMissingPermissions(),
            account = incompleteAccount,
            onHealthConnectAccessManagement = { managementCalls++ },
        )

        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectRow)
            .performScrollTo()
            .assertHasClickAction()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, managementCalls)
        }
    }

    @Test
    fun partialHealthConnectActionRequestsMissingPermissionsInsteadOfOpeningManagement() {
        var authorizationCalls = 0
        var managementCalls = 0
        setSettingsShell(
            healthConnect = HealthConnectPermissionsUiState.snapshot(
                isAvailable = true,
                requiredPermissions = setOf("weight", "fat"),
                grantedPermissions = setOf("weight"),
            ),
            onHealthConnectAuthorization = { authorizationCalls++ },
            onHealthConnectAccessManagement = { managementCalls++ },
        )

        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectAction)
            .performScrollTo()
            .assertIsEnabled()
            .assertContentDescriptionEquals(
                SettingsScreenContentDescriptions.HealthConnectConnectAction,
            )
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, authorizationCalls)
            assertEquals(0, managementCalls)
        }
    }

    @Test
    fun availableHealthConnectRowAndOpenActionLaunchManagement() {
        var authorizationCalls = 0
        var managementCalls = 0
        val requiredPermissions = setOf("weight", "fat")
        setSettingsShell(
            healthConnect = HealthConnectPermissionsUiState.snapshot(
                isAvailable = true,
                requiredPermissions = requiredPermissions,
                grantedPermissions = requiredPermissions,
            ),
            onHealthConnectAuthorization = { authorizationCalls++ },
            onHealthConnectAccessManagement = { managementCalls++ },
        )

        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectRow)
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectAction)
            .performScrollTo()
            .assertIsEnabled()
            .assertContentDescriptionEquals(
                SettingsScreenContentDescriptions.HealthConnectOpenAction,
            )
            .performClick()

        composeRule.runOnIdle {
            assertEquals(0, authorizationCalls)
            assertEquals(2, managementCalls)
        }
    }

    @Test
    fun missingHealthConnectManagementHandlerHidesOnlyOpenAndDisablesRow() {
        var managementCalls = 0
        val requiredPermissions = setOf("weight", "fat")
        setSettingsShell(
            healthConnect = HealthConnectPermissionsUiState.snapshot(
                isAvailable = true,
                requiredPermissions = requiredPermissions,
                grantedPermissions = requiredPermissions,
            ),
            healthConnectSystemManagementAvailable = false,
            onHealthConnectAccessManagement = { managementCalls++ },
        )

        composeRule.onNodeWithText("Основной: Анна · Подключено · все разрешения выданы")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectRow)
            .assertHasNoClickAction()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectAction)
            .assertDoesNotExist()

        composeRule.runOnIdle { assertEquals(0, managementCalls) }
    }

    @Test
    fun missingHealthConnectManagementHandlerKeepsPermissionAction() {
        var authorizationCalls = 0
        setSettingsShell(
            healthConnect = availableHealthConnectWithMissingPermissions(),
            healthConnectSystemManagementAvailable = false,
            onHealthConnectAuthorization = { authorizationCalls++ },
        )

        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectRow)
            .performScrollTo()
            .assertHasNoClickAction()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectAction)
            .assertIsEnabled()
            .assertContentDescriptionEquals(
                SettingsScreenContentDescriptions.HealthConnectConnectAction,
            )
            .performClick()

        composeRule.runOnIdle { assertEquals(1, authorizationCalls) }
    }

    @Test
    fun unavailableHealthConnectRowIsInactiveAndExplainsWhy() {
        var managementCalls = 0
        setSettingsShell(
            healthConnect = HealthConnectPermissionsUiState(
                availability = HealthConnectAvailability.UNAVAILABLE,
            ),
            onHealthConnectAccessManagement = { managementCalls++ },
        )

        composeRule.onNodeWithText(
            "Основной: Анна · Недоступно: устройство не поддерживает Health Connect",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectRow)
            .assertHasNoClickAction()
            .assertContentDescriptionEquals(SettingsScreenContentDescriptions.HealthConnectRow)
        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectAction).assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(0, managementCalls)
        }
    }

    @Test
    fun providerUpdateRequiredHealthConnectRowIsInactiveDespiteManagementHandler() {
        var managementCalls = 0
        setSettingsShell(
            healthConnect = HealthConnectPermissionsUiState(
                availability = HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED,
            ),
            healthConnectSystemManagementAvailable = true,
            onHealthConnectAccessManagement = { managementCalls++ },
        )

        composeRule.onNodeWithText(
            "Основной: Анна · Недоступно: установите или обновите Health Connect",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectRow)
            .assertHasNoClickAction()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectAction).assertDoesNotExist()

        composeRule.runOnIdle { assertEquals(0, managementCalls) }
    }

    private fun setSettingsShell(
        huawei: HuaweiIntegrationUiState = HuaweiIntegrationUiState(),
        healthConnect: HealthConnectPermissionsUiState = HealthConnectPermissionsUiState(),
        healthConnectSystemManagementAvailable: Boolean = true,
        account: Account? = completeAccount(),
        onHuaweiAuthorization: () -> Unit = {},
        onHuaweiPermissionRefresh: () -> Unit = {},
        onHealthConnectAuthorization: () -> Unit = {},
        onHealthConnectAccessManagement: () -> Unit = {},
        onIgnoreUnknownMeasurementsChanged: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            val management = remember {
                mutableStateOf(
                    AccountManagementUiState(
                        accounts = listOfNotNull(account),
                        primaryAccountId = account?.id,
                    ),
                )
            }
            val snackbarHostState = remember { SnackbarHostState() }
            HuaweiMiSyncScaffold(
                state = MainUiState(
                    settings = AppSettings(),
                    healthConnect = healthConnect,
                    healthConnectSystemManagementAvailable =
                        healthConnectSystemManagementAvailable,
                    huawei = huawei,
                    accounts = listOfNotNull(account),
                    accountSettings = AccountSettings(primaryAccountId = account?.id),
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
                    onHealthConnectAuthorization = onHealthConnectAuthorization,
                    onHealthConnectAccessManagement = onHealthConnectAccessManagement,
                    accountManagement = AccountManagementCallbacks.None.copy(
                        onAction = { management.value = reduceAccountManagement(management.value, it) },
                    ),
                    onIgnoreUnknownMeasurementsChanged = onIgnoreUnknownMeasurementsChanged,
                ),
                measurementsContent = {},
                chartsContent = {},
            )
        }
    }

    private fun settingsCallbacks(
        onHuaweiAuthorization: () -> Unit = {},
        onHuaweiPermissionRefresh: () -> Unit = {},
        onHealthConnectAuthorization: () -> Unit = {},
        onHealthConnectAccessManagement: () -> Unit = {},
        accountManagement: AccountManagementCallbacks = AccountManagementCallbacks.None,
        onIgnoreUnknownMeasurementsChanged: (Boolean) -> Unit = {},
    ) = SettingsCallbacks(
        onHuaweiAuthorization = onHuaweiAuthorization,
        onHuaweiPermissionRefresh = onHuaweiPermissionRefresh,
        onHealthConnectAuthorization = onHealthConnectAuthorization,
        onHealthConnectAccessManagement = onHealthConnectAccessManagement,
        onManualTest = { _, _ -> },
        onManualScan = {},
        onReliabilityMode = {},
        openBatterySettings = {},
        openApplicationSettings = {},
        accountManagement = accountManagement,
        onIgnoreUnknownMeasurementsChanged = onIgnoreUnknownMeasurementsChanged,
    )

    private fun completeAccount(): Account = Account(
        id = AccountId("primary"),
        displayName = "Анна",
        profile = AccountProfile.Complete(
            heightCm = 181.5,
            birthDate = LocalDate.of(1988, 2, 29),
            sex = Sex.FEMALE,
        ),
        createdAt = Instant.parse("2026-08-15T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-15T00:00:00Z"),
    )

    private fun availableHealthConnectWithMissingPermissions(): HealthConnectPermissionsUiState =
        HealthConnectPermissionsUiState.snapshot(
            isAvailable = true,
            requiredPermissions = setOf("weight"),
            grantedPermissions = emptySet(),
        )
}
