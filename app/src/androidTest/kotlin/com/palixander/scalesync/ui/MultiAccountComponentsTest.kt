package com.palixander.scalesync.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.AccountUpdate
import com.palixander.scalesync.domain.ProfileHistoryUpdateMode
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.domain.PrimaryHistorySyncMode
import com.palixander.scalesync.measurements.formatMeasurementDateTime
import com.palixander.scalesync.ui.accounts.AccountManagementCallbacks
import com.palixander.scalesync.ui.accounts.AccountManagementTestTags
import com.palixander.scalesync.ui.accounts.AccountManagementUiState
import com.palixander.scalesync.ui.accounts.ProfileUpdateConfirmation
import com.palixander.scalesync.ui.accounts.PrimaryAccountChangeRequest
import com.palixander.scalesync.ui.accounts.AccountDeletionRequest
import com.palixander.scalesync.ui.accounts.AccountEditorDialog
import com.palixander.scalesync.ui.accounts.AccountEditorDraft
import com.palixander.scalesync.ui.accounts.AccountSelector
import com.palixander.scalesync.ui.accounts.AccountSelectorTestTags
import com.palixander.scalesync.ui.accounts.AccountManagementSection
import com.palixander.scalesync.ui.accounts.reconcileAccountSelection
import com.palixander.scalesync.ui.routing.MeasurementResolverCallbacks
import com.palixander.scalesync.ui.routing.MeasurementResolverDialog
import com.palixander.scalesync.ui.routing.MeasurementResolverTestTags
import com.palixander.scalesync.ui.routing.MeasurementResolverUiState
import com.palixander.scalesync.ui.routing.ResolverAccountOption
import com.palixander.scalesync.ui.routing.UnsavedMeasurementPreviewDialog
import com.palixander.scalesync.ui.routing.UnsavedMeasurementPreviewState
import com.palixander.scalesync.ui.routing.UnsavedPreviewCallbacks
import com.palixander.scalesync.ui.routing.UnsavedPreviewProfileDraft
import com.palixander.scalesync.ui.routing.UnsavedPreviewStep
import com.palixander.scalesync.ui.routing.UnsavedPreviewTestTags
import com.palixander.scalesync.ui.routing.UnsavedPreviewTitle
import com.palixander.scalesync.ui.theme.ScaleSyncTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MultiAccountComponentsTest {
    @Test
    fun profileUpdatePromptShowsThreeActionsAndDisablesThemWhileSaving() {
        val account = account("one", "Анна")
        val update = AccountUpdate(
            account.id,
            account.displayName,
            (account.profile as AccountProfile.Complete).copy(heightCm = 171.0),
        )
        val modes = mutableListOf<ProfileHistoryUpdateMode>()
        composeRule.setContent {
            ScaleSyncTheme {
                AccountManagementSection(
                    state = AccountManagementUiState(
                        accounts = listOf(account),
                        profileUpdateConfirmation = ProfileUpdateConfirmation(
                            update,
                            AccountEditorDraft.edit(account).copy(heightCm = "171"),
                        ),
                        operationInProgress = true,
                        operationError = "Не удалось сохранить",
                    ),
                    callbacks = AccountManagementCallbacks.None.copy(
                        onConfirmProfileUpdate = { modes += it },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(AccountManagementTestTags.ProfileUpdatePrompt).assertIsDisplayed()
        composeRule.onNodeWithTag(AccountManagementTestTags.ProfileUpdateRecalculate).assertIsNotEnabled()
        composeRule.onNodeWithTag(AccountManagementTestTags.ProfileUpdateKeepExisting).assertIsNotEnabled()
        composeRule.onNodeWithTag(AccountManagementTestTags.ProfileUpdateCancel).assertIsNotEnabled()
        composeRule.onNodeWithTag(AccountManagementTestTags.OperationError).assertIsDisplayed()
        assertTrue(modes.isEmpty())
    }

    @Test
    fun profileUpdatePromptKeepsAllActionsInsideDialogInVerticalOrder() {
        val account = account("one", "Анна")
        val update = AccountUpdate(
            account.id,
            account.displayName,
            (account.profile as AccountProfile.Complete).copy(heightCm = 171.0),
        )
        composeRule.setContent {
            ScaleSyncTheme {
                AccountManagementSection(
                    state = AccountManagementUiState(
                        accounts = listOf(account),
                        profileUpdateConfirmation = ProfileUpdateConfirmation(
                            update,
                            AccountEditorDraft.edit(account).copy(heightCm = "171"),
                        ),
                    ),
                    callbacks = AccountManagementCallbacks.None,
                )
            }
        }

        val dialog = composeRule.onNodeWithTag(AccountManagementTestTags.ProfileUpdatePrompt)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val recalculateNode = composeRule.onNodeWithTag(AccountManagementTestTags.ProfileUpdateRecalculate)
            .assertIsDisplayed()
            .assertTextEquals("Сохранить и пересчитать")
        val keepExistingNode = composeRule.onNodeWithTag(AccountManagementTestTags.ProfileUpdateKeepExisting)
            .assertIsDisplayed()
            .assertTextEquals("Сохранить без пересчёта")
        val cancelNode = composeRule.onNodeWithTag(AccountManagementTestTags.ProfileUpdateCancel)
            .assertIsDisplayed()
            .assertTextEquals("Отмена")

        val recalculate = recalculateNode.getUnclippedBoundsInRoot()
        val keepExisting = keepExistingNode.getUnclippedBoundsInRoot()
        val cancel = cancelNode.getUnclippedBoundsInRoot()
        val recalculateText = composeRule.onNodeWithText(
            "Сохранить и пересчитать",
            substring = false,
            useUnmergedTree = true,
        ).assertIsDisplayed().getUnclippedBoundsInRoot()
        val keepExistingText = composeRule.onNodeWithText(
            "Сохранить без пересчёта",
            substring = false,
            useUnmergedTree = true,
        ).assertIsDisplayed().getUnclippedBoundsInRoot()
        val cancelText = composeRule.onNodeWithText(
            "Отмена",
            substring = false,
            useUnmergedTree = true,
        ).assertIsDisplayed().getUnclippedBoundsInRoot()

        listOf(recalculate, keepExisting, cancel).forEach { action ->
            assertTrue(action.left >= dialog.left)
            assertTrue(action.right <= dialog.right)
            assertTrue(action.top >= dialog.top)
            assertTrue(action.bottom <= dialog.bottom)
        }
        assertTrue(recalculate.bottom <= keepExisting.top)
        assertTrue(keepExisting.bottom <= cancel.top)

        listOf(
            recalculateText to recalculate,
            keepExistingText to keepExisting,
            cancelText to cancel,
        ).forEach { (text, action) ->
            assertTrue("Action text must start inside its action", text.left >= action.left)
            assertTrue("Action text must end inside its action", text.right <= action.right)
            assertTrue("Action text must start below its action top", text.top >= action.top)
            assertTrue("Action text must end above its action bottom", text.bottom <= action.bottom)
        }
        assertTrue(recalculateText.bottom <= keepExistingText.top)
        assertTrue(keepExistingText.bottom <= cancelText.top)
    }

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accountManagementShowsPrimaryBadgeAndDispatchesAdd() {
        val primary = account("primary", "Анна")
        var addRequested = false
        composeRule.setContent {
            ScaleSyncTheme {
                AccountManagementSection(
                    state = AccountManagementUiState(
                        accounts = listOf(primary),
                        primaryAccountId = primary.id,
                    ),
                    callbacks = AccountManagementCallbacks.None.copy(
                        onAction = { addRequested = true },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(AccountManagementTestTags.primaryBadge(primary.id)).assertExists()
        composeRule.onNodeWithTag(AccountManagementTestTags.Add).performClick()
        composeRule.runOnIdle { assertEquals(true, addRequested) }
    }

    @Test
    fun primaryChangeUsesFullScreenChoicesAndPinnedContinueAt320Dp() {
        val account = account("secondary", "Очень длинное имя нового основного профиля")
        var selectedMode: PrimaryHistorySyncMode? = null
        var confirmed: Pair<AccountId, PrimaryHistorySyncMode>? = null
        composeRule.setContent {
            var state by remember {
                mutableStateOf(
                    AccountManagementUiState(
                        accounts = listOf(account),
                        primaryChange = PrimaryAccountChangeRequest(account.id),
                    ),
                )
            }
            DeviceConfigurationOverride(
                override = DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp)),
            ) {
                ScaleSyncTheme {
                    AccountManagementSection(
                        state = state,
                        callbacks = AccountManagementCallbacks.None.copy(
                            onAction = { action ->
                                if (action is com.palixander.scalesync.ui.accounts.AccountManagementAction.SyncModeSelected) {
                                    selectedMode = action.mode
                                    state = state.copy(
                                        primaryChange = state.primaryChange?.copy(
                                            historySyncMode = action.mode,
                                        ),
                                    )
                                }
                            },
                            onSetPrimary = { accountId, mode -> confirmed = accountId to mode },
                        ),
                    )
                }
            }
        }

        val screen = composeRule.onNodeWithTag(AccountManagementTestTags.PrimaryChange)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(320.dp, screen.right - screen.left)
        composeRule.onNodeWithTag(AccountManagementTestTags.PrimaryChangeBack).assertIsDisplayed()
        composeRule.onNodeWithTag(AccountManagementTestTags.PrimaryChangeFutureOnly)
            .assertIsSelected().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(AccountManagementTestTags.PrimaryChangeIncludeHistory)
            .assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        composeRule.onNodeWithText("Очень длинное имя нового основного профиля", substring = true)
            .assertExists()
        composeRule.onNodeWithTag(AccountManagementTestTags.PrimaryChangeContinue)
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()

        composeRule.runOnIdle {
            assertEquals(PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY, selectedMode)
            assertEquals(
                account.id to PrimaryHistorySyncMode.INCLUDE_ELIGIBLE_HISTORY,
                confirmed,
            )
        }
    }

    @Test
    fun primaryChangeDisablesDismissAndShowsErrorWhileApplying() {
        val account = account("secondary", "Анна")
        var dismissals = 0
        composeRule.setContent {
            ScaleSyncTheme {
                AccountManagementSection(
                    state = AccountManagementUiState(
                        accounts = listOf(account),
                        primaryChange = PrimaryAccountChangeRequest(account.id),
                        operationInProgress = true,
                        operationError = "Не удалось изменить основной профиль",
                    ),
                    callbacks = AccountManagementCallbacks.None.copy(
                        onAction = { dismissals += 1 },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(AccountManagementTestTags.PrimaryChangeBack).assertIsNotEnabled()
        composeRule.onNodeWithTag(AccountManagementTestTags.PrimaryChangeContinue)
            .assertIsNotEnabled().assertTextEquals("Применение…")
        composeRule.onNodeWithTag(AccountManagementTestTags.PrimaryChangeFutureOnly)
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(AccountManagementTestTags.PrimaryChangeIncludeHistory)
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(AccountManagementTestTags.OperationError).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, dismissals) }
    }

    @Test
    fun sharedSelectorExposesFallbackAndSelectsAccount() {
        val primary = account("primary", "Анна")
        val state = reconcileAccountSelection(
            accounts = listOf(primary),
            requestedAccountId = AccountId("removed"),
            primaryAccountId = primary.id,
        )
        var selected: AccountId? = null
        composeRule.setContent {
            ScaleSyncTheme {
                AccountSelector(state = state, onAccountSelected = { selected = it })
            }
        }

        composeRule.onNodeWithTag(AccountSelectorTestTags.Fallback).assertExists()
        composeRule.onNodeWithTag(AccountSelectorTestTags.option(primary.id)).assertIsSelected().performClick()
        composeRule.runOnIdle { assertEquals(primary.id, selected) }
    }

    @Test
    fun resolverAllowsSelectingNonCandidateAccount() {
        val candidate = account("candidate", "Анна")
        val any = account("any", "Борис")
        var selected: AccountId? = null
        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementResolverDialog(
                    state = MeasurementResolverUiState(
                        pending = pending(),
                        accountOptions = listOf(
                            ResolverAccountOption(
                                accountId = candidate.id,
                                displayName = candidate.displayName,
                                isPrimary = true,
                                differenceKg = 0.5,
                                medianWeightKg = 69.5,
                            ),
                            ResolverAccountOption(
                                accountId = any.id,
                                displayName = any.displayName,
                                isPrimary = false,
                            ),
                        ),
                    ),
                    callbacks = MeasurementResolverCallbacks.None.copy(
                        onAccountSelected = { _, accountId -> selected = accountId },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(MeasurementResolverTestTags.account(any.id)).performClick()
        composeRule.runOnIdle { assertEquals(any.id, selected) }
    }

    @Test
    fun resolverUsesApprovedTerminologyWithoutExposingMatchingAlgorithm() {
        val recommended = account("recommended", "Анна")
        val suitable = account("suitable", "Борис")
        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementResolverDialog(
                    state = MeasurementResolverUiState(
                        pending = pending(),
                        accountOptions = listOf(
                            ResolverAccountOption(
                                recommended.id,
                                recommended.displayName,
                                isPrimary = true,
                                differenceKg = 0.2,
                                medianWeightKg = 69.8,
                            ),
                            ResolverAccountOption(
                                suitable.id,
                                suitable.displayName,
                                isPrimary = false,
                                differenceKg = 0.8,
                                medianWeightKg = 69.2,
                            ),
                        ),
                    ),
                    callbacks = MeasurementResolverCallbacks.None,
                )
            }
        }

        composeRule.onNodeWithText("Неназначенное измерение").assertIsDisplayed()
        composeRule.onNodeWithText("Кому назначить это измерение?").assertIsDisplayed()
        composeRule.onNodeWithText("Рекомендуется").assertIsDisplayed()
        composeRule.onNodeWithText("Подходит").assertIsDisplayed()
        composeRule.onNodeWithText("Решить позже").assertIsDisplayed()
        composeRule.onAllNodesWithText("разница", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Кому сохранить измерение?").assertDoesNotExist()
    }

    @Test
    fun resolverScrollsToEveryAccountAndKeepsLastOptionSelectable() {
        val accounts = (0..12).map { account("account-$it", "Аккаунт $it") }
        val last = accounts.last()
        var selected: AccountId? = null
        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementResolverDialog(
                    state = MeasurementResolverUiState(
                        pending = pending(),
                        accountOptions = accounts.mapIndexed { index, account ->
                            ResolverAccountOption(
                                accountId = account.id,
                                displayName = account.displayName,
                                isPrimary = index == 0,
                            )
                        },
                    ),
                    callbacks = MeasurementResolverCallbacks.None.copy(
                        onAccountSelected = { _, accountId -> selected = accountId },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(MeasurementResolverTestTags.account(last.id))
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(last.id, selected) }
    }

    @Test
    fun resolverDeleteAddressesDisplayedPendingMeasurement() {
        val pending = pending()
        var deleted: PendingMeasurementId? = null
        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementResolverDialog(
                    state = MeasurementResolverUiState(
                        pending = pending,
                        accountOptions = emptyList(),
                    ),
                    callbacks = MeasurementResolverCallbacks.None.copy(
                        onDelete = { deleted = it },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(MeasurementResolverTestTags.Delete).performClick()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.IgnoreUnknown).assertDoesNotExist()
        composeRule.onNodeWithText(formatMeasurementDateTime(pending.measuredAt)).assertExists()
        composeRule.runOnIdle { assertEquals(pending.id, deleted) }
    }

    @Test
    fun noMatchResolverShowsSavedIgnorePolicyAndDispatchesDraftChange() {
        val pending = pending()
        var changed: Pair<PendingMeasurementId, Boolean>? = null
        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementResolverDialog(
                    state = MeasurementResolverUiState(
                        pending = pending,
                        accountOptions = emptyList(),
                        ignoreUnknownMeasurements = true,
                    ),
                    callbacks = MeasurementResolverCallbacks.None.copy(
                        onIgnoreUnknownMeasurementsChanged = { pendingId, enabled ->
                            changed = pendingId to enabled
                        },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(MeasurementResolverTestTags.IgnoreUnknown).performClick()
        composeRule.runOnIdle { assertEquals(pending.id to false, changed) }
    }

    @Test
    fun unsavedPreviewCloseDispatchesDisplayedPendingOnlyOnce() {
        val pending = pending()
        val discarded = mutableListOf<PendingMeasurementId>()
        composeRule.setContent {
            ScaleSyncTheme {
                UnsavedMeasurementPreviewDialog(
                    state = UnsavedMeasurementPreviewState(pending),
                    callbacks = UnsavedPreviewCallbacks.None.copy(
                        onCloseAndDiscard = discarded::add,
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(UnsavedPreviewTestTags.Close)
            .performClick()
            .assertIsNotEnabled()

        composeRule.onNodeWithText("Время: ${formatMeasurementDateTime(pending.measuredAt)}")
            .assertExists()

        composeRule.runOnIdle { assertEquals(listOf(pending.id), discarded) }
    }

    @Test
    fun primaryDeletionRejectsAReplacementThatNoLongerExists() {
        val primary = account("primary", "Анна")
        val replacement = account("replacement", "Борис")
        composeRule.setContent {
            ScaleSyncTheme {
                AccountManagementSection(
                    state = AccountManagementUiState(
                        accounts = listOf(primary, replacement),
                        primaryAccountId = primary.id,
                        deletion = AccountDeletionRequest(
                            accountId = primary.id,
                            wasPrimary = true,
                            replacementAccountId = AccountId("removed"),
                        ),
                    ),
                    callbacks = AccountManagementCallbacks.None,
                )
            }
        }

        composeRule.onNodeWithTag(AccountManagementTestTags.DeleteConfirm).assertIsNotEnabled()
    }

    @Test
    fun accountEditorBirthDateUsesPickerAndDispatchesTypedDate() {
        val birthDate = LocalDate.of(2000, 2, 29)
        val draft = AccountEditorDraft(
            name = "Анна",
            heightCm = "170",
            birthDate = birthDate,
            sex = Sex.FEMALE,
        )
        var changedDraft: AccountEditorDraft? = null
        composeRule.setContent {
            ScaleSyncTheme {
                AccountEditorDialog(
                    draft = draft,
                    accounts = emptyList(),
                    operationInProgress = false,
                    onDraftChanged = { changedDraft = it },
                    onCreate = {},
                    onUpdate = {},
                    onDismiss = {},
                    today = LocalDate.of(2026, 8, 20),
                )
            }
        }

        composeRule.onNodeWithTag(AccountManagementTestTags.EditorBirthDate)
            .performScrollTo()
            .assert(hasClickAction() and !hasSetTextAction())
            .performClick()
        composeRule.onNodeWithText("Выбрать").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(birthDate, changedDraft?.birthDate) }
    }

    @Test
    fun accountEditorUsesFullScreenOrderedLayoutAt320Dp() {
        val draft = AccountEditorDraft(
            name = "Анна",
            heightCm = "170",
            birthDate = LocalDate.of(2000, 2, 29),
            sex = Sex.FEMALE,
        )
        composeRule.setContent {
            DeviceConfigurationOverride(
                override = DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp)),
            ) {
                ScaleSyncTheme {
                    AccountEditorDialog(
                        draft = draft,
                        accounts = emptyList(),
                        operationInProgress = false,
                        onDraftChanged = {},
                        onCreate = {},
                        onUpdate = {},
                        onDismiss = {},
                    )
                }
            }
        }

        val editor = composeRule.onNodeWithTag(AccountManagementTestTags.Editor)
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val name = composeRule.onNodeWithTag(AccountManagementTestTags.EditorName)
            .getUnclippedBoundsInRoot()
        val male = composeRule.onNodeWithTag(AccountManagementTestTags.EditorMale)
            .assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val female = composeRule.onNodeWithTag(AccountManagementTestTags.EditorFemale)
            .assertIsSelected().assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val birthDate = composeRule.onNodeWithTag(AccountManagementTestTags.EditorBirthDate)
            .getUnclippedBoundsInRoot()
        val height = composeRule.onNodeWithTag(AccountManagementTestTags.EditorHeight)
            .getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorSave)
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)

        assertEquals(320.dp, editor.right - editor.left)
        assertTrue(male.right <= female.left)
        assertTrue(name.bottom <= male.top)
        assertTrue(female.bottom <= birthDate.top)
        assertTrue(birthDate.bottom <= height.top)
    }

    @Test
    fun accountEditorBackConfirmsDirtyDraftAndKeepsEnteredData() {
        var dismissals = 0
        composeRule.setContent {
            var draft by remember {
                mutableStateOf(
                    AccountEditorDraft(
                        name = "Анна",
                        heightCm = "170",
                        birthDate = LocalDate.of(2000, 2, 29),
                        sex = Sex.FEMALE,
                    ),
                )
            }
            ScaleSyncTheme {
                AccountEditorDialog(
                    draft = draft,
                    accounts = emptyList(),
                    operationInProgress = false,
                    onDraftChanged = { draft = it },
                    onCreate = {},
                    onUpdate = {},
                    onDismiss = { dismissals++ },
                )
            }
        }

        composeRule.onNodeWithTag(AccountManagementTestTags.EditorMale).performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorBack).performClick()
        composeRule.onNodeWithText("Отказаться от изменений?").assertIsDisplayed()
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorKeepEditing).performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorMale).assertIsSelected()
        composeRule.runOnIdle { assertEquals(0, dismissals) }

        composeRule.onNodeWithTag(AccountManagementTestTags.EditorBack).performClick()
        composeRule.onNodeWithTag(AccountManagementTestTags.EditorDiscard).performClick()
        composeRule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun accountEditorBackDismissesUnchangedDraftImmediately() {
        var dismissals = 0
        composeRule.setContent {
            ScaleSyncTheme {
                AccountEditorDialog(
                    draft = AccountEditorDraft.add(),
                    accounts = emptyList(),
                    operationInProgress = false,
                    onDraftChanged = {},
                    onCreate = {},
                    onUpdate = {},
                    onDismiss = { dismissals++ },
                )
            }
        }

        composeRule.onNodeWithTag(AccountManagementTestTags.EditorBack).performClick()
        composeRule.runOnIdle { assertEquals(1, dismissals) }
        composeRule.onNodeWithText("Отказаться от изменений?").assertDoesNotExist()
    }

    @Test
    fun unsavedPreviewBirthDateUsesMeasurementBoundPickerAndTypedCallback() {
        val birthDate = LocalDate.of(2000, 2, 29)
        val state = UnsavedMeasurementPreviewState(
            pending = pending(),
            step = UnsavedPreviewStep.PROFILE_EDITOR,
            profileDraft = UnsavedPreviewProfileDraft(
                heightCm = "170",
                birthDate = birthDate,
                sex = Sex.FEMALE,
            ),
        )
        var changedState: UnsavedMeasurementPreviewState? = null
        composeRule.setContent {
            ScaleSyncTheme {
                UnsavedMeasurementPreviewDialog(
                    state = state,
                    callbacks = UnsavedPreviewCallbacks.None.copy(
                        onStateChange = { changedState = it },
                    ),
                    zoneId = ZoneOffset.UTC,
                )
            }
        }

        composeRule.onNodeWithTag(UnsavedPreviewTestTags.BirthDate)
            .performScrollTo()
            .assert(hasClickAction() and !hasSetTextAction())
            .performClick()
        composeRule.onNodeWithText("Выбрать").assertIsDisplayed().performClick()

        composeRule.runOnIdle { assertEquals(birthDate, changedState?.profileDraft?.birthDate) }
    }

    @Test
    fun unsavedPreviewBadgeStaysHorizontalBelowTitleAtNarrowWidth() {
        composeRule.setContent {
            ScaleSyncTheme {
                UnsavedPreviewTitle(modifier = Modifier.width(180.dp))
            }
        }

        val titleBounds = composeRule.onNodeWithTag(UnsavedPreviewTestTags.Title)
            .getUnclippedBoundsInRoot()
        val badgeBounds = composeRule.onNodeWithTag(UnsavedPreviewTestTags.UnsavedBadge)
            .getUnclippedBoundsInRoot()
        assertTrue(
            "Unsaved badge must be laid out on a separate row below the preview title",
            badgeBounds.top >= titleBounds.bottom,
        )

        val textLayouts = mutableListOf<TextLayoutResult>()
        val badgeText = composeRule.onNodeWithText("Не сохранено", useUnmergedTree = true)
            .fetchSemanticsNode()
        val getTextLayout = badgeText.config[SemanticsActions.GetTextLayoutResult].action
        assertTrue(getTextLayout?.invoke(textLayouts) == true)
        assertEquals(1, textLayouts.single().lineCount)
        assertTrue(textLayouts.single().size.width > textLayouts.single().size.height)
    }

    private fun account(id: String, name: String): Account = Account(
        id = AccountId(id),
        displayName = name,
        profile = AccountProfile.Complete(
            heightCm = 170.0,
            birthDate = LocalDate.of(1990, 1, 1),
            sex = Sex.FEMALE,
        ),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun pending(): PendingMeasurement = PendingMeasurement(
        id = PendingMeasurementId("pending"),
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        measuredAt = Instant.parse("2026-08-15T09:59:00.123456789Z"),
        weightKg = 70.0,
        impedanceOhm = 500,
        isStable = true,
        hasImpedance = true,
        rawPayload = byteArrayOf(1, 2, 3),
        deduplicationHash = "hash",
        enqueuedAt = Instant.parse("2026-08-15T10:00:00Z"),
    )
}
