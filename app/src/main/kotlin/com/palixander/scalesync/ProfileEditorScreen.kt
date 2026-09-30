package com.palixander.scalesync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.palixander.scalesync.core.Sex
import com.palixander.scalesync.ui.components.ScaleSyncFilterButton
import com.palixander.scalesync.ui.components.ScaleSyncSurface
import com.palixander.scalesync.ui.theme.ScaleSyncDimensions
import com.palixander.scalesync.ui.text.resolve

@Composable
internal fun ProfileEditorScreen(
    state: ProfileEditorUiState,
    onHeightChanged: (String) -> Unit,
    onBirthDateChanged: (String) -> Unit,
    onSexChanged: (Sex) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .testTag(SettingsScreenTestTags.ProfileEditor),
            contentPadding = PaddingValues(
                horizontal = ScaleSyncDimensions.ContentPadding,
                vertical = ScaleSyncDimensions.CompactItemSpacing,
            ),
            verticalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.ItemSpacing),
        ) {
            item {
                Text(
                    stringResource(R.string.settings_profile_editor_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.errorMessage?.let { message ->
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SettingsScreenTestTags.ProfileEditorError),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(message.resolve(LocalContext.current.resources), Modifier.padding(14.dp))
                    }
                }
            }
            item {
                ScaleSyncSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(ScaleSyncDimensions.ItemSpacing)) {
                        OutlinedTextField(
                            value = state.height,
                            onValueChange = onHeightChanged,
                            label = { Text(stringResource(R.string.settings_height_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            isError = state.errorMessage != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.birthDate,
                            onValueChange = onBirthDateChanged,
                            label = { Text(stringResource(R.string.settings_birth_date_label)) },
                            singleLine = true,
                            isError = state.errorMessage != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.settings_sex), style = MaterialTheme.typography.titleSmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(
                                ScaleSyncDimensions.CompactItemSpacing,
                            ),
                        ) {
                            Sex.entries.forEach { option ->
                                ScaleSyncFilterButton(
                                    text = stringResource(if (option == Sex.MALE) R.string.settings_sex_male else R.string.settings_sex_female),
                                    onClick = { onSexChanged(option) },
                                    selected = state.sex == option,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(ScaleSyncDimensions.ItemSpacing)) }
        }
    }
}

@Composable
internal fun ProfileEditorSaveBar(
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 2.dp,
    ) {
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScaleSyncDimensions.ContentPadding, vertical = 12.dp)
                .heightIn(min = ScaleSyncDimensions.TouchTarget),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.settings_save_profile))
        }
    }
}
