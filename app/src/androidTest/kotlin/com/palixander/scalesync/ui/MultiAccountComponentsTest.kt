package com.palixander.scalesync.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.domain.Account
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.domain.AccountProfile
import com.palixander.scalesync.domain.AccountUpdate
import com.palixander.scalesync.domain.ProfileHistoryUpdateMode
import com.palixander.scalesync.domain.PendingMeasurement
import com.palixander.scalesync.domain.PendingMeasurementId
import com.palixander.scalesync.measurements.formatMeasurementDateTime
import com.palixander.scalesync.ui.accounts.AccountManagementCallbacks
import com.palixander.scalesync.ui.accounts.AccountManagementTestTags
import com.palixander.scalesync.ui.accounts.AccountManagementUiState
import com.palixander.scalesync.ui.accounts.ProfileUpdateConfirmation
import com.palixander.scalesync.ui.accounts.AccountDeletionRequest
import com.palixander.scalesync.ui.accounts.AccountEditorScreen
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
    fun resolverRecommendsFirstCandidateAfterNonCandidateWithAccessibleDescription() {
        val any = account("any", "Любой")
        val recommended = account("recommended", "Анна")
        val otherCandidate = account("other", "Борис")
        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementResolverDialog(
                    state = MeasurementResolverUiState(
                        pending = pending(),
                        accountOptions = listOf(
                            ResolverAccountOption(any.id, any.displayName, false),
                            ResolverAccountOption(recommended.id, recommended.displayName, true, 0.1, 70.0),
                            ResolverAccountOption(otherCandidate.id, otherCandidate.displayName, false, 0.2, 70.0),
                        ),
                    ),
                    callbacks = MeasurementResolverCallbacks.None,
                )
            }
        }

        composeRule.onAllNodesWithText("Рекомендуется").assertCountEquals(1)
        composeRule.onNodeWithTag(MeasurementResolverTestTags.account(recommended.id))
            .assertContentDescriptionEquals("Анна. Рекомендуется. Основной профиль")
        composeRule.onNodeWithTag(MeasurementResolverTestTags.account(otherCandidate.id))
            .assertContentDescriptionEquals("Борис")
        composeRule.onNodeWithTag(MeasurementResolverTestTags.account(any.id))
            .assertContentDescriptionEquals("Любой")
    }

    @Test
    fun resolverShowsNoRecommendationWithoutCandidates() {
        val account = account("any", "Любой")
        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementResolverDialog(
                    state = MeasurementResolverUiState(
                        pending = pending(),
                        accountOptions = listOf(
                            ResolverAccountOption(account.id, account.displayName, false),
                        ),
                    ),
                    callbacks = MeasurementResolverCallbacks.None,
                )
            }
        }

        composeRule.onAllNodesWithText("Рекомендуется").assertCountEquals(0)
        composeRule.onNodeWithTag(MeasurementResolverTestTags.account(account.id))
            .assertContentDescriptionEquals("Любой")
    }

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
    fun resolverRevision17PreservesEveryActionCallbackAndMarksOnlyBestCandidateRecommended() {
        val pending = pending()
        val recommended = account("recommended", "Анна")
        val otherCandidate = account("candidate", "Борис")
        val events = mutableListOf<String>()
        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementResolverDialog(
                    state = MeasurementResolverUiState(
                        pending = pending,
                        accountOptions = listOf(
                            ResolverAccountOption(
                                accountId = recommended.id,
                                displayName = recommended.displayName,
                                isPrimary = true,
                                differenceKg = 0.1,
                                medianWeightKg = 70.0,
                            ),
                            ResolverAccountOption(
                                accountId = otherCandidate.id,
                                displayName = otherCandidate.displayName,
                                isPrimary = false,
                                differenceKg = 0.2,
                                medianWeightKg = 70.0,
                            ),
                        ),
                        ignoreUnknownMeasurements = false,
                    ),
                    callbacks = MeasurementResolverCallbacks(
                        onAccountSelected = { pendingId, accountId ->
                            events += "assign:${pendingId.value}:${accountId.value}"
                        },
                        onCreateAccount = { events += "create:${it.value}" },
                        onShowWithoutSaving = { events += "preview:${it.value}" },
                        onIgnoreUnknownMeasurementsChanged = { pendingId, enabled ->
                            events += "ignore:${pendingId.value}:$enabled"
                        },
                        onDelete = { events += "delete:${it.value}" },
                        onLater = { events += "later" },
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Неназначенное измерение").assertExists()
        composeRule.onNodeWithText("Кому назначить это измерение?").assertExists()
        composeRule.onAllNodesWithText("Рекомендуется").assertCountEquals(1)
        composeRule.onNodeWithText("${pending.impedanceOhm} Ом", substring = true)
            .assertDoesNotExist()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.account(recommended.id)).performClick()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.WithoutSaving).performClick()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.CreateAccount).performClick()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.IgnoreUnknown)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.Delete)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.Later).performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    "assign:${pending.id.value}:${recommended.id.value}",
                    "preview:${pending.id.value}",
                    "create:${pending.id.value}",
                    "ignore:${pending.id.value}:true",
                    "delete:${pending.id.value}",
                    "later",
                ),
                events,
            )
        }
    }

    @Test
    fun resolverInProgressBlocksEveryActionAndShowsProgress() {
        val pending = pending()
        val account = account("one", "Анна")
        composeRule.setContent {
            ScaleSyncTheme {
                MeasurementResolverDialog(
                    state = MeasurementResolverUiState(
                        pending = pending,
                        accountOptions = listOf(
                            ResolverAccountOption(
                                accountId = account.id,
                                displayName = account.displayName,
                                isPrimary = true,
                            ),
                        ),
                        ignoreUnknownMeasurements = false,
                        operationInProgress = true,
                    ),
                    callbacks = MeasurementResolverCallbacks.None,
                )
            }
        }

        composeRule.onNodeWithTag(MeasurementResolverTestTags.account(account.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.WithoutSaving).assertIsNotEnabled()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.CreateAccount).assertIsNotEnabled()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.IgnoreUnknown)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.Delete)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.Later).assertIsNotEnabled()
        composeRule.onNodeWithTag(MeasurementResolverTestTags.Progress)
            .performScrollTo()
            .assertIsDisplayed()
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
                AccountEditorScreen(
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
