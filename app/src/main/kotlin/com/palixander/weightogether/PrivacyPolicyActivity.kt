package com.palixander.weightogether

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.palixander.weightogether.ui.icons.ScaleSyncIcons
import com.palixander.weightogether.ui.settings.SettingsGroupRow
import com.palixander.weightogether.ui.theme.ScaleSyncTheme

/** Also handles Health Connect rationale intents, without starting the diary or requesting BLE access. */
class PrivacyPolicyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val policy = resources.openRawResource(R.raw.privacy_policy_ru)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        setContent {
            ScaleSyncTheme {
                PrivacyPolicyScreen(policy = policy, onBack = ::finish)
            }
        }
    }
}

@Composable
internal fun PrivacyPolicySettingsRow() {
    val context = LocalContext.current
    SettingsGroupRow(
        leadingIcon = ScaleSyncIcons.LocalDevice,
        title = stringResource(R.string.privacy_policy_title),
        supportingText = stringResource(R.string.privacy_policy_supporting),
        modifier = Modifier.testTag(SettingsScreenTestTags.PrivacyPolicyRow),
        onClick = { context.startActivity(Intent(context, PrivacyPolicyActivity::class.java)) },
        leadingIconTag = SettingsScreenTestTags.PrivacyPolicyRow + SettingsScreenTestTags.LeadingIconSuffix,
        trailingTag = SettingsScreenTestTags.PrivacyPolicyRow + SettingsScreenTestTags.TrailingChevronSuffix,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivacyPolicyScreen(policy: String, onBack: () -> Unit) {
    val paragraphs = remember(policy) { policy.trim().split("\n\n") }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_policy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("privacy-policy-back")) {
                        Icon(ScaleSyncIcons.Back, stringResource(R.string.privacy_policy_back))
                    }
                },
            )
        },
    ) { padding ->
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("privacy-policy-content"),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(paragraphs) { index, paragraph ->
                    val isHeading = index == 0 || paragraph.matches(Regex("[1-8]\\. [^\\n]+"))
                    Text(
                        text = paragraph,
                        style = if (isHeading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = if (isHeading) Modifier.semantics { heading() } else Modifier,
                    )
                }
            }
        }
    }
}
