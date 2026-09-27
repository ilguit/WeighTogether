package com.palixander.scalesync.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.palixander.scalesync.domain.AccountId
import com.palixander.scalesync.R
import com.palixander.scalesync.ui.text.resolve
import com.palixander.scalesync.ui.components.HuaweiFilterButton
import com.palixander.scalesync.ui.theme.HuaweiDimensions

object AccountSelectorTestTags {
    const val Selector = "account-selector"
    const val Fallback = "account-selector-fallback"
    fun option(accountId: AccountId): String = "account-selector-${accountId.value}"
}

/** Shared, stateless selector intended for both Measurements and Charts feature surfaces. */
@Composable
fun AccountSelector(
    state: AccountSelectorUiState,
    onAccountSelected: (AccountId) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val resources = LocalResources.current
    val selectorDescription = stringResource(R.string.account_selector_cd)
    val primarySuffixText = stringResource(R.string.account_selector_primary_suffix)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(AccountSelectorTestTags.Selector)
            .semantics { contentDescription = selectorDescription },
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
    ) {
        Text(label ?: stringResource(R.string.account_selector_label), style = MaterialTheme.typography.labelLarge)
        if (state.accounts.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
                verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
            ) {
                state.accounts.forEach { account ->
                    val primarySuffix = if (account.id == state.primaryAccountId) primarySuffixText else ""
                    HuaweiFilterButton(
                        text = account.displayName,
                        selected = account.id == state.selectedAccountId,
                        enabled = !state.isLoading,
                        onClick = { onAccountSelected(account.id) },
                        modifier = Modifier
                            .testTag(AccountSelectorTestTags.option(account.id))
                            .semantics {
                                contentDescription = "${account.displayName}$primarySuffix"
                            },
                    )
                }
            }
        }
        state.fallbackMessage?.let { message ->
            Text(
                text = message.resolve(resources),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(AccountSelectorTestTags.Fallback),
            )
        }
    }
}
