package com.example.huaweimisync

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.data.AppSettings
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.AccountSettings
import com.example.huaweimisync.domain.Pet
import com.example.huaweimisync.domain.PetId
import com.example.huaweimisync.domain.PetMeasurement
import com.example.huaweimisync.domain.PetSpecies
import com.example.huaweimisync.domain.PetWithLatestWeight
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SettingsShellUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun profilesDetailReplacesLegacyProfileAndOpensProfileEditor() {
        setSettingsShell(expandSections = false)

        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfileRow).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfilesRow).performClick()
        composeRule.onNodeWithText("Профили").assertIsDisplayed()
        composeRule.onNodeWithTag(AccountManagementTestTags.List).assertIsDisplayed()
        composeRule.onNodeWithTag(AccountManagementTestTags.Add).performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.Editor).assertIsDisplayed()
        composeRule.onNodeWithText("Новый профиль").assertIsDisplayed()
        composeRule.onNodeWithText("Отмена").performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.Editor).assertDoesNotExist()
        composeRule.onNodeWithTag(MainScreenTestTags.BottomNavigation).assertDoesNotExist()
    }

    @Test
    fun invalidProfileDraftShowsValidationErrors() {
        setSettingsShell(expandSections = false)
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfilesRow).performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.Add).performClick()

        composeRule.onNodeWithTag(AccountManagementTestTags.Editor).assertIsDisplayed()
        composeRule.onNodeWithText("Введите имя от 1 до 50 символов").assertIsDisplayed()
        composeRule.onNodeWithText("Допустимый рост: 100–230 см").assertIsDisplayed()
        composeRule.onNodeWithTag(MainScreenTestTags.SnackbarHost).assertExists()
    }

    @Test
    fun diagnosticsRowOpensExistingActions() {
        setSettingsShell(expandSections = false)

        composeRule.onNodeWithTag(SettingsScreenTestTags.DiagnosticsDetail).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsScreenTestTags.DiagnosticsRow)
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag(SettingsScreenTestTags.DiagnosticsDetail).assertExists()
        composeRule.onNodeWithText("Отправить тест").assertExists()
        composeRule.onNodeWithText("Повышенная надёжность").assertExists()
        composeRule.onNodeWithText("Батарея").assertExists()
        composeRule.onNodeWithText("Настройки приложения").assertExists()
    }

    @Test
    fun manualTestValuesSurviveNavigationAndRecreationAndDispatchEditedValues() {
        var submitted: Pair<String, String>? = null
        setSettingsShell(
            expandSections = false,
            onManualTest = { weight, impedance -> submitted = weight to impedance },
        )

        composeRule.onNodeWithTag(SettingsScreenTestTags.DiagnosticsRow).performClick()
        composeRule.onNodeWithTag(SettingsScreenTestTags.ManualTestWeight)
            .performTextReplacement("82.35")
        composeRule.onNodeWithTag(SettingsScreenTestTags.ManualTestImpedance)
            .performTextReplacement("612")

        composeRule.onNodeWithContentDescription("Вернуться к настройкам").performClick()
        composeRule.onNodeWithTag(SettingsScreenTestTags.DiagnosticsRow).performClick()

        composeRule.onNodeWithTag(SettingsScreenTestTags.ManualTestWeight)
            .assertTextEquals("Вес, кг", "82.35")
        composeRule.onNodeWithTag(SettingsScreenTestTags.ManualTestImpedance)
            .assertTextEquals("Импеданс, Ом", "612")
        composeRule.onNodeWithText("Отправить тест").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals("82.35" to "612", submitted) }

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag(SettingsScreenTestTags.ManualTestWeight)
            .assertTextEquals("Вес, кг", "82.35")
        composeRule.onNodeWithTag(SettingsScreenTestTags.ManualTestImpedance)
            .assertTextEquals("Импеданс, Ом", "612")
    }

    @Test
    fun ignoreUnknownSettingIsNextToRecognitionAndDispatchesSavedPolicyChange() {
        var enabled: Boolean? = null
        setSettingsShell(
            expandSections = false,
            onIgnoreUnknownMeasurementsChanged = { enabled = it },
        )
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfilesRow).performClick()

        composeRule.onNodeWithTag(WeightDeltaEditorTestTags.IgnoreUnknown)
            .performScrollTo()
            .performClick()

        composeRule.runOnIdle { assertEquals(true, enabled) }
    }

    @Test
    fun petsSectionShowsLocalizedLatestWeightAndExplicitNoHistoryState() {
        val measuredAt = Instant.parse("2026-08-26T10:00:00Z")
        val cat = pet("cat", "Мурка", PetSpecies.CAT)
        val dog = pet("dog", "Шарик", PetSpecies.DOG)
        setSettingsShell(
            expandSections = false,
            pets = listOf(
                PetWithLatestWeight(cat, PetMeasurement("m1", cat.id, measuredAt, 70.0, 74.25)),
                PetWithLatestWeight(dog, null),
            ),
        )

        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfilesRow).performClick()
        composeRule.onNodeWithText("Последний вес: 4,25 кг").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Измерений пока нет").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun profilesDetailShowsEmptyState() {
        setSettingsShell(expandSections = false, account = null)
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfilesRow).performClick()
        composeRule.onNodeWithText("Профилей пока нет.").assertIsDisplayed()
    }

    @Test
    fun profilesDetailShowsLongProfileNameAndHumanPrimaryBadge() {
        val longName = "Очень длинное имя профиля для проверки переноса строки"
        setSettingsShell(
            expandSections = false,
            account = completeAccount().copy(displayName = longName),
        )
        composeRule.onNodeWithTag(SettingsScreenTestTags.ProfilesRow).performClick()
        composeRule.onNodeWithText(longName).assertIsDisplayed()
        composeRule.onNodeWithText("Основной").assertIsDisplayed()
    }

    @Test
    fun rootUsesRequiredNavigationOrderWithoutAccordionHeaders() {
        setSettingsShell(expandSections = false)

        listOf(SettingsScreenTestTags.ProfilesRow, SettingsScreenTestTags.ScaleRow,
            SettingsScreenTestTags.HealthConnectRow, SettingsScreenTestTags.BackupRow,
            SettingsScreenTestTags.DiagnosticsRow, SettingsScreenTestTags.ChangelogRow).forEach {
            composeRule.onNodeWithTag(it).performScrollTo().assertHasClickAction()
        }
        composeRule.onNodeWithTag(SettingsScreenTestTags.AccountsSection).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsScreenTestTags.AdditionalSection).assertDoesNotExist()
    }

    @Test
    fun rootRowsArePartitionedIntoGroupedSurfacesWithInternalDividers() {
        setSettingsShell(expandSections = false)

        listOf(
            SettingsScreenTestTags.ProfilesGroup,
            SettingsScreenTestTags.ConnectionsGroup,
            SettingsScreenTestTags.SupportGroup,
        ).forEach { composeRule.onNodeWithTag(it).assertExists() }
        listOf(
            SettingsScreenTestTags.ProfilesRow to SettingsScreenTestTags.ProfilesGroup,
            SettingsScreenTestTags.ScaleRow to SettingsScreenTestTags.ConnectionsGroup,
            SettingsScreenTestTags.HealthConnectRow to SettingsScreenTestTags.ConnectionsGroup,
            SettingsScreenTestTags.BackupRow to SettingsScreenTestTags.SupportGroup,
            SettingsScreenTestTags.DiagnosticsRow to SettingsScreenTestTags.SupportGroup,
            SettingsScreenTestTags.ChangelogRow to SettingsScreenTestTags.SupportGroup,
        ).forEach { (row, group) ->
            composeRule.onNode(hasTestTag(row) and hasAnyAncestor(hasTestTag(group))).assertExists()
        }
        composeRule.onNodeWithTag(SettingsScreenTestTags.ConnectionsDivider)
            .assertExists()
            .assertHasNoClickAction()
        composeRule.onNodeWithTag(SettingsScreenTestTags.SupportDivider)
            .assertExists()
            .assertHasNoClickAction()
        composeRule.onNodeWithTag(SettingsScreenTestTags.SupportSecondDivider)
            .assertExists()
            .assertHasNoClickAction()
        composeRule.onAllNodesWithTag(
            SettingsScreenTestTags.RootDivider,
            useUnmergedTree = true,
        ).assertCountEquals(if (BuildConfig.HUAWEI_EXTENDED_ENABLED) 4 else 3)
        composeRule.onNodeWithTag(SettingsScreenTestTags.ConnectionsHeading)
            .assertTextEquals("Весы и синхронизация")
        composeRule.onNodeWithTag(SettingsScreenTestTags.SupportHeading)
            .assertTextEquals("Данные и приложение")
        val profilesGroup = composeRule.onNodeWithTag(SettingsScreenTestTags.ProfilesGroup)
            .getUnclippedBoundsInRoot()
        val rootList = composeRule.onNodeWithTag(SettingsScreenTestTags.List)
            .getUnclippedBoundsInRoot()
        assertEquals(12.dp, profilesGroup.left - rootList.left)
    }

    @Test
    fun narrowLargeTextRootRowsGrowAndKeepStatusAndChevronInsideTheRow() {
        val longScaleName =
            "Очень длинное имя весов для узкого экрана с увеличенным системным шрифтом"
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 2f),
            ) {
                Box(Modifier.width(320.dp)) {
                    SettingsScreen(
                        state = MainUiState(
                            settings = AppSettings(
                                scaleAddress = "AA:BB:CC:DD:EE:FF",
                                scaleName = longScaleName,
                            ),
                        ),
                        callbacks = settingsCallbacks(),
                        contentPadding = PaddingValues(),
                    )
                }
            }
        }

        val row = composeRule.onNodeWithTag(SettingsScreenTestTags.ScaleRow)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val status = composeRule.onNodeWithText("$longScaleName · AA:BB:CC:DD:EE:FF")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val chevron = composeRule.onNodeWithTag(
            SettingsScreenTestTags.ScaleRow + SettingsScreenTestTags.TrailingChevronSuffix,
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()

        composeRule.runOnIdle {
            assertTrue(row.bottom - row.top > 68.dp)
            assertTrue(status.left >= row.left && status.right <= row.right)
            assertTrue(chevron.left >= row.left && chevron.right <= row.right)
        }
    }

    @Test
    fun rootRowsHaveOneThematicLeadingIconAndOneDecorativeChevron() {
        setSettingsShell(expandSections = false)

        val rows = buildList {
            add(SettingsScreenTestTags.ProfilesRow)
            add(SettingsScreenTestTags.ScaleRow)
            add(SettingsScreenTestTags.HealthConnectRow)
            if (BuildConfig.HUAWEI_EXTENDED_ENABLED) add(SettingsScreenTestTags.HuaweiHealthRow)
            add(SettingsScreenTestTags.BackupRow)
            add(SettingsScreenTestTags.DiagnosticsRow)
            add(SettingsScreenTestTags.ChangelogRow)
        }
        rows.forEach { row ->
            composeRule.onAllNodes(
                hasTestTag(row + SettingsScreenTestTags.LeadingIconSuffix) and
                    hasAnyAncestor(hasTestTag(row)),
                useUnmergedTree = true,
            ).assertCountEquals(1)
            composeRule.onAllNodes(
                hasTestTag(row + SettingsScreenTestTags.TrailingChevronSuffix) and
                    hasAnyAncestor(hasTestTag(row)),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        }
    }

    @Test
    fun rootConnectionRowsExposeStateMarksWithoutReplacingTextStatus() {
        val permissions = setOf("weight")
        setSettingsShell(
            settings = AppSettings(
                scaleAddress = "AA:BB",
                scaleName = "Mi Body Composition Scale 2",
                healthConnectSyncEnabled = true,
                huaweiSyncEnabled = true,
            ),
            healthConnect = HealthConnectPermissionsUiState.snapshot(
                isAvailable = true,
                requiredPermissions = permissions,
                grantedPermissions = permissions,
            ),
            huawei = HuaweiIntegrationUiState(HuaweiIntegrationStatus.AUTHORIZED),
        )

        composeRule.onNodeWithTag(SettingsScreenTestTags.ScaleStatusMark).assertExists()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectStatusMark).assertExists()
        composeRule.onNodeWithText("Mi Body Composition Scale 2 · AA:BB").assertExists()
        composeRule.onNodeWithText("Подключено · все разрешения выданы").assertExists()
        if (BuildConfig.HUAWEI_EXTENDED_ENABLED) {
            composeRule.onNodeWithTag(SettingsScreenTestTags.HuaweiHealthStatusMark).assertExists()
            composeRule.onNodeWithText("Подключено").assertExists()
        } else {
            composeRule.onNodeWithTag(SettingsScreenTestTags.HuaweiHealthStatusMark)
                .assertDoesNotExist()
        }
    }

    @Test
    fun profilesRootSummaryShowsExplicitEmptyState() {
        setSettingsShell(expandSections = false, account = null)
        composeRule.onNodeWithText("Добавьте первый профиль").assertIsDisplayed()
    }

    @Test
    fun profilesRootSummaryShowsPeopleAndPetCounts() {
        setSettingsShell(
            expandSections = false,
            pets = listOf(PetWithLatestWeight(pet("cat", "Мурка", PetSpecies.CAT), null)),
        )
        composeRule.onNodeWithText("1 человек · 1 питомец").assertIsDisplayed()
    }

    @Test
    fun rootNavigationRowsExposeOnlyTheirSingleRowClickAction() {
        setSettingsShell(expandSections = false)

        listOf(
            SettingsScreenTestTags.ProfilesRow,
            SettingsScreenTestTags.ScaleRow,
            SettingsScreenTestTags.HealthConnectRow,
            SettingsScreenTestTags.BackupRow,
            SettingsScreenTestTags.DiagnosticsRow,
            SettingsScreenTestTags.ChangelogRow,
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertHasClickAction()
            composeRule.onAllNodes(
                hasClickAction() and hasAnyAncestor(hasTestTag(tag)),
                useUnmergedTree = true,
            ).assertCountEquals(0)
        }
    }

    @Test
    fun detailFocusStartsOnBackAndReturnsToOriginatingRootRow() {
        setSettingsShell(expandSections = false)

        composeRule.onNodeWithTag(SettingsScreenTestTags.DiagnosticsRow)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(MainScreenTestTags.SettingsBack).assertIsFocused()

        composeRule.onNodeWithTag(MainScreenTestTags.SettingsBack).performClick()
        composeRule.onNodeWithTag(SettingsScreenTestTags.DiagnosticsRow).assertIsFocused()
    }

    @Test
    fun navigationRowsExposeTheirTextualStatusToAccessibilityServices() {
        val longScaleStatus = "Очень длинное имя весов для узкого экрана и крупного шрифта без потери текста"
        setSettingsShell(
            expandSections = false,
            settings = AppSettings(scaleAddress = "AA:BB", scaleName = longScaleStatus),
        )

        val scaleNode = composeRule.onNodeWithTag(SettingsScreenTestTags.ScaleRow)
            .fetchSemanticsNode()
        assertEquals(longScaleStatus, scaleNode.config[SemanticsProperties.StateDescription])
        composeRule.onNodeWithText(longScaleStatus).assertExists()
    }

    @Test
    fun diagnosticsDestinationRestoresAfterRecreation() {
        setSettingsShell(expandSections = false)

        composeRule.onNodeWithTag(SettingsScreenTestTags.DiagnosticsRow).performClick()
        composeRule.onNodeWithTag(SettingsScreenTestTags.DiagnosticsDetail).assertExists()

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithTag(SettingsScreenTestTags.DiagnosticsDetail).assertExists()
    }

    @Test
    fun personalFlavorOmitsHuaweiIntegrationAndCannotDispatchItsCallbacks() {
        assumeFalse(BuildConfig.HUAWEI_EXTENDED_ENABLED)
        var refreshCalls = 0
        var authorizationCalls = 0
        setSettingsShell(
            huawei = HuaweiIntegrationUiState(HuaweiIntegrationStatus.CHECK_FAILED),
            onHuaweiAuthorization = { authorizationCalls++ },
            onHuaweiPermissionRefresh = { refreshCalls++ },
        )

        composeRule.onNodeWithTag(SettingsScreenTestTags.HealthConnectRow)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Huawei Health").assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HuaweiHealthRow).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HuaweiHealthAction).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HuaweiHealthDivider).assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(0, refreshCalls)
            assertEquals(0, authorizationCalls)
        }
    }

    @Test
    fun enterpriseFlavorShowsHuaweiIntegrationAndRetriesPermissionRefresh() {
        assumeTrue(BuildConfig.HUAWEI_EXTENDED_ENABLED)
        var refreshCalls = 0
        var authorizationCalls = 0
        setSettingsShell(
            huawei = HuaweiIntegrationUiState(HuaweiIntegrationStatus.CHECK_FAILED),
            onHuaweiAuthorization = { authorizationCalls++ },
            onHuaweiPermissionRefresh = { refreshCalls++ },
        )

        composeRule.onNodeWithTag(SettingsScreenTestTags.HuaweiHealthRow)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Huawei Health").assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HuaweiHealthDivider).assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HuaweiHealthAction)
            .assertIsEnabled()
            .performClick()

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
    fun locallyDisabledHealthConnectShowsReconnectAndHidesDestructiveAction() {
        var authorizationCalls = 0
        var managementCalls = 0
        val requiredPermissions = setOf("weight", "fat")
        setSettingsShell(
            settings = AppSettings(healthConnectSyncEnabled = false),
            healthConnect = HealthConnectPermissionsUiState.snapshot(
                isAvailable = true,
                requiredPermissions = requiredPermissions,
                grantedPermissions = requiredPermissions,
            ),
            onHealthConnectAuthorization = { authorizationCalls++ },
            onHealthConnectAccessManagement = { managementCalls++ },
        )

        composeRule.onNodeWithText("Основной: Анна · Отключено в приложении")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Подключить снова").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(SettingsScreenTestTags.DisableHealthConnect)
            .assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(1, authorizationCalls)
            assertEquals(0, managementCalls)
        }
    }

    @Test
    fun locallyDisabledHuaweiShowsReconnectAndHidesDestructiveAction() {
        assumeTrue(BuildConfig.HUAWEI_EXTENDED_ENABLED)
        var authorizationCalls = 0
        var refreshCalls = 0
        setSettingsShell(
            settings = AppSettings(huaweiSyncEnabled = false),
            huawei = HuaweiIntegrationUiState(HuaweiIntegrationStatus.AUTHORIZED),
            onHuaweiAuthorization = { authorizationCalls++ },
            onHuaweiPermissionRefresh = { refreshCalls++ },
        )

        composeRule.onNodeWithText("Основной: Анна · Отключено в приложении")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsScreenTestTags.HuaweiHealthAction).performClick()
        composeRule.onNodeWithTag(SettingsScreenTestTags.DisableHuawei).assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(1, authorizationCalls)
            assertEquals(0, refreshCalls)
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
        expandSections: Boolean = true,
        settings: AppSettings = AppSettings(),
        huawei: HuaweiIntegrationUiState = HuaweiIntegrationUiState(),
        healthConnect: HealthConnectPermissionsUiState = HealthConnectPermissionsUiState(),
        healthConnectSystemManagementAvailable: Boolean = true,
        account: Account? = completeAccount(),
        onHuaweiAuthorization: () -> Unit = {},
        onHuaweiPermissionRefresh: () -> Unit = {},
        onHealthConnectAuthorization: () -> Unit = {},
        onHealthConnectAccessManagement: () -> Unit = {},
        onIgnoreUnknownMeasurementsChanged: (Boolean) -> Unit = {},
        onManualTest: (String, String) -> Unit = { _, _ -> },
        pets: List<PetWithLatestWeight> = emptyList(),
    ) {
        composeRule.setContent {
            val settingsDestination = remember { mutableStateOf(SettingsDestination.ROOT) }
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
                    settings = settings,
                    healthConnect = healthConnect,
                    healthConnectSystemManagementAvailable =
                        healthConnectSystemManagementAvailable,
                    huawei = huawei,
                    accounts = listOfNotNull(account),
                    accountSettings = AccountSettings(primaryAccountId = account?.id),
                    accountManagement = management.value,
                    pets = pets,
                ),
                currentSection = AppSection.SETTINGS,
                settingsDestination = settingsDestination.value,
                measurementsDestination = MeasurementsDestination.SUMMARY,
                measurementsCallbacks = MeasurementsCallbacks.None,
                snackbarHostState = snackbarHostState,
                onSectionSelected = {},
                onSettingsDestinationChanged = { settingsDestination.value = it },
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
                    onManualTest = onManualTest,
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
        onManualTest: (String, String) -> Unit = { _, _ -> },
    ) = SettingsCallbacks(
        onHuaweiAuthorization = onHuaweiAuthorization,
        onHuaweiPermissionRefresh = onHuaweiPermissionRefresh,
        onHealthConnectAuthorization = onHealthConnectAuthorization,
        onHealthConnectAccessManagement = onHealthConnectAccessManagement,
        onManualTest = onManualTest,
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

    private fun pet(id: String, name: String, species: PetSpecies): Pet = Pet(
        id = PetId(id),
        displayName = name,
        species = species,
        createdAt = Instant.parse("2026-08-25T00:00:00Z"),
        updatedAt = Instant.parse("2026-08-25T00:00:00Z"),
    )

    private fun availableHealthConnectWithMissingPermissions(): HealthConnectPermissionsUiState =
        HealthConnectPermissionsUiState.snapshot(
            isAvailable = true,
            requiredPermissions = setOf("weight"),
            grantedPermissions = emptySet(),
        )
}
