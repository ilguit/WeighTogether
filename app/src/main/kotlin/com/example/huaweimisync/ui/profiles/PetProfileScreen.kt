package com.example.huaweimisync.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.example.huaweimisync.charts.ChartRangePreset
import com.example.huaweimisync.charts.MetricChartCard
import com.example.huaweimisync.ui.components.HuaweiSurface
import com.example.huaweimisync.ui.theme.HuaweiDimensions

object PetProfileScreenTestTags {
    const val Shell = "pet-profile-shell"
    fun shell(petId: String) = "$Shell-$petId"
    const val StartMeasurement = "pet-history-start-measurement"
    const val PeriodFilter = "pet-history-period-filter"
    const val Chart = "pet-history-chart"
    const val Empty = "pet-history-empty"
    const val NotFound = "pet-history-not-found"
    const val Loading = "pet-history-loading"
    fun measurement(id: String) = "pet-history-measurement-$id"
    fun preset(preset: ChartRangePreset) = "pet-history-period-${preset.name.lowercase()}"
}

@Composable
internal fun PetProfileScreen(
    state: PetHistoryUiState,
    callbacks: PetHistoryCallbacks,
    contentPadding: PaddingValues,
    onStartMeasurement: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding)
            .padding(horizontal = HuaweiDimensions.ContentPadding)
            .testTag(PetProfileScreenTestTags.shell(state.petId.value))
            .semantics { contentDescription = "История измерений питомца ${state.pet?.displayName.orEmpty()}" },
        verticalArrangement = Arrangement.spacedBy(HuaweiDimensions.ItemSpacing),
    ) {
        item {
            Button(
                onClick = onStartMeasurement,
                enabled = state.pet != null && !state.isNotFound,
                modifier = Modifier.fillMaxWidth().testTag(PetProfileScreenTestTags.StartMeasurement)
                    .semantics { contentDescription = "Взвесить питомца ${state.pet?.displayName.orEmpty()}" },
            ) { Text("Взвесить питомца") }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().testTag(PetProfileScreenTestTags.PeriodFilter),
                horizontalArrangement = Arrangement.spacedBy(HuaweiDimensions.CompactItemSpacing),
            ) {
                listOf(ChartRangePreset.LAST_7_DAYS, ChartRangePreset.LAST_30_DAYS, ChartRangePreset.LAST_3_MONTHS)
                    .forEach { preset ->
                        FilterChip(
                            selected = state.rangePreset == preset,
                            onClick = { callbacks.selectRangePreset(preset) },
                            label = { Text(preset.petTitle()) },
                            modifier = Modifier.weight(1f).testTag(PetProfileScreenTestTags.preset(preset)),
                        )
                    }
            }
        }
        when {
            state.isLoading -> item {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag(PetProfileScreenTestTags.Loading),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
            }
            state.isNotFound -> item {
                Text("Питомец не найден", modifier = Modifier.testTag(PetProfileScreenTestTags.NotFound))
            }
            state.errorMessage != null -> item { Text(state.errorMessage, color = MaterialTheme.colorScheme.error) }
            else -> {
                item {
                    Column(Modifier.testTag(PetProfileScreenTestTags.Chart)) {
                        MetricChartCard(state.series, state.startDate, state.endDateInclusive, java.time.ZoneId.systemDefault())
                    }
                }
                if (state.measurements.isEmpty()) item {
                    Text("Нет измерений за выбранный период", modifier = Modifier.testTag(PetProfileScreenTestTags.Empty))
                } else {
                    item { Text("Измерения", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
                    items(state.measurements, key = { it.id }) { measurement ->
                        HuaweiSurface(
                            modifier = Modifier.fillMaxWidth().testTag(PetProfileScreenTestTags.measurement(measurement.id))
                                .semantics { contentDescription = "${measurement.measuredAtText}, ${measurement.weightText}" },
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(measurement.measuredAtText)
                                Text(measurement.weightText, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ChartRangePreset.petTitle() = when (this) {
    ChartRangePreset.LAST_7_DAYS -> "7 дней"
    ChartRangePreset.LAST_30_DAYS -> "30 дней"
    ChartRangePreset.LAST_3_MONTHS -> "3 месяца"
    ChartRangePreset.YEAR_TO_DATE -> "Год"
    ChartRangePreset.CUSTOM -> "Даты"
}
