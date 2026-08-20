package com.example.huaweimisync.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.huaweimisync.core.Sex
import com.example.huaweimisync.domain.Account
import com.example.huaweimisync.domain.AccountId
import com.example.huaweimisync.domain.AccountProfile
import com.example.huaweimisync.domain.PendingMeasurement
import com.example.huaweimisync.domain.PendingMeasurementId
import com.example.huaweimisync.ui.accounts.AccountManagementCallbacks
import com.example.huaweimisync.ui.accounts.AccountManagementTestTags
import com.example.huaweimisync.ui.accounts.AccountManagementUiState
import com.example.huaweimisync.ui.accounts.AccountDeletionRequest
import com.example.huaweimisync.ui.accounts.AccountSelector
import com.example.huaweimisync.ui.accounts.AccountSelectorTestTags
import com.example.huaweimisync.ui.accounts.AccountManagementSection
import com.example.huaweimisync.ui.accounts.reconcileAccountSelection
import com.example.huaweimisync.ui.routing.MeasurementResolverCallbacks
import com.example.huaweimisync.ui.routing.MeasurementResolverDialog
import com.example.huaweimisync.ui.routing.MeasurementResolverTestTags
import com.example.huaweimisync.ui.routing.MeasurementResolverUiState
import com.example.huaweimisync.ui.routing.ResolverAccountOption
import com.example.huaweimisync.ui.theme.HuaweiMiSyncTheme
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MultiAccountComponentsTest {
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
        composeRule.runOnIdle { assertEquals(pending.id, deleted) }
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
        measuredAt = Instant.parse("2026-08-15T09:59:00Z"),
        weightKg = 70.0,
        impedanceOhm = 500,
        isStable = true,
        hasImpedance = true,
        rawPayload = byteArrayOf(1, 2, 3),
        deduplicationHash = "hash",
        enqueuedAt = Instant.parse("2026-08-15T10:00:00Z"),
    )
}
