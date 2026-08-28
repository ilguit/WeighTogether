package com.example.huaweimisync.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.AccountUpdate
import com.example.huaweimisync.domain.ProfileHistoryUpdateMode
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.measurements.formatMeasurementDateTime
import com.example.huaweimisync.ui.accounts.AccountManagementCallbacks
import com.example.huaweimisync.ui.accounts.AccountManagementTestTags
import com.example.huaweimisync.ui.accounts.AccountManagementUiState
import com.example.huaweimisync.ui.accounts.ProfileUpdateConfirmation
import com.example.huaweimisync.ui.accounts.AccountDeletionRequest
import com.example.huaweimisync.ui.accounts.AccountEditorDialog
import com.example.huaweimisync.ui.accounts.AccountEditorDraft
import com.example.huaweimisync.ui.accounts.AccountSelector
import com.example.huaweimisync.ui.accounts.AccountSelectorTestTags
import com.example.huaweimisync.ui.accounts.AccountManagementSection
import com.example.huaweimisync.ui.accounts.reconcileAccountSelection
import com.example.huaweimisync.ui.routing.MeasurementResolverCallbacks
import com.example.huaweimisync.ui.routing.MeasurementResolverDialog
import com.example.huaweimisync.ui.routing.MeasurementResolverTestTags
import com.example.huaweimisync.ui.routing.MeasurementResolverUiState
import com.example.huaweimisync.ui.routing.ResolverAccountOption
import com.example.huaweimisync.ui.routing.UnsavedMeasurementPreviewDialog
import com.example.huaweimisync.ui.routing.UnsavedMeasurementPreviewState
import com.example.huaweimisync.ui.routing.UnsavedPreviewCallbacks
import com.example.huaweimisync.ui.routing.UnsavedPreviewProfileDraft
import com.example.huaweimisync.ui.routing.UnsavedPreviewStep
import com.example.huaweimisync.ui.routing.UnsavedPreviewTestTags
import com.example.huaweimisync.ui.routing.UnsavedPreviewTitle
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme
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
            HuaweiMiSyncTheme {
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
            HuaweiMiSyncTheme {
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
        val recalculate = composeRule.onNodeWithTag(AccountManagementTestTags.ProfileUpdateRecalculate)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val keepExisting = composeRule.onNodeWithTag(AccountManagementTestTags.ProfileUpdateKeepExisting)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val cancel = composeRule.onNodeWithTag(AccountManagementTestTags.ProfileUpdateCancel)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()

        listOf(recalculate, keepExisting, cancel).forEach { action ->
            assertTrue(action.left >= dialog.left)
            assertTrue(action.right <= dialog.right)
            assertTrue(action.top >= dialog.top)
            assertTrue(action.bottom <= dialog.bottom)
        }
        assertTrue(recalculate.bottom <= keepExisting.top)
        assertTrue(keepExisting.bottom <= cancel.top)
    }

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accountManagementShowsPrimaryBadgeAndDispatchesAdd() {
        val primary = account("primary", "Анна")
        var addRequested = false
        composeRule.setContent {
            HuaweiMiSyncTheme {
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
    fun sharedSelectorExposesFallbackAndSelectsAccount() {
        val primary = account("primary", "Анна")
        val state = reconcileAccountSelection(
            accounts = listOf(primary),
            requestedAccountId = AccountId("removed"),
            primaryAccountId = primary.id,
        )
        var selected: AccountId? = null
        composeRule.setContent {
            HuaweiMiSyncTheme {
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
            HuaweiMiSyncTheme {
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
    fun resolverScrollsToEveryAccountAndKeepsLastOptionSelectable() {
        val accounts = (0..12).map { account("account-$it", "Аккаунт $it") }
        val last = accounts.last()
        var selected: AccountId? = null
        composeRule.setContent {
            HuaweiMiSyncTheme {
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
            HuaweiMiSyncTheme {
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
            HuaweiMiSyncTheme {
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
            HuaweiMiSyncTheme {
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
            HuaweiMiSyncTheme {
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
            HuaweiMiSyncTheme {
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
            HuaweiMiSyncTheme {
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
            HuaweiMiSyncTheme {
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
