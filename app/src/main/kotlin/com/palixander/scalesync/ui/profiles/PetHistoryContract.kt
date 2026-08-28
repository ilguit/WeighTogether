package com.palixander.scalesync.ui.profiles

import com.palixander.scalesync.charts.ChartDateRange
import com.palixander.scalesync.charts.ChartMetricOption
import com.palixander.scalesync.charts.ChartPoint
import com.palixander.scalesync.charts.ChartRangePreset
import com.palixander.scalesync.charts.ChartSeries
import com.palixander.scalesync.charts.formatChartCurrentValue
import com.palixander.scalesync.charts.inclusiveDateRangeToEpochRange
import com.palixander.scalesync.domain.Pet
import com.palixander.scalesync.domain.PetId
import com.palixander.scalesync.domain.PetMeasurement
import com.palixander.scalesync.measurements.formatMeasurementDateTime
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

val PetWeightChartMetric = ChartMetricOption(
    key = "petWeightKg",
    displayName = "Вес питомца",
    unit = "кг",
    decimalPlaces = 2,
)

sealed interface PetHistoryContent {
    data object Empty : PetHistoryContent
    data class Single(val measurement: PetHistoryMeasurementUi) : PetHistoryContent
    data class Multiple(val measurements: List<PetHistoryMeasurementUi>) : PetHistoryContent
}

data class PetHistoryMeasurementUi(
    val id: String,
    val measuredAtEpochSecond: Long,
    val measuredAtText: String,
    val weightKg: Double,
    val weightText: String,
)

data class PetHistoryDeleteConfirmation(
    val petId: PetId,
    val measurement: PetHistoryMeasurementUi,
    val isDeleting: Boolean = false,
)

data class PetHistoryUiState(
    val petId: PetId,
    val pet: Pet? = null,
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val rangePreset: ChartRangePreset,
    val content: PetHistoryContent = PetHistoryContent.Empty,
    val series: ChartSeries = ChartSeries(PetWeightChartMetric, emptyList()),
    val isLoading: Boolean = true,
    val isNotFound: Boolean = false,
    val errorMessage: String? = null,
    val deleteConfirmation: PetHistoryDeleteConfirmation? = null,
    val actionErrorMessage: String? = null,
) {
    init {
        require(!endDateInclusive.isBefore(startDate)) { "The end date must not precede the start date." }
    }

    val measurements: List<PetHistoryMeasurementUi>
        get() = when (val current = content) {
            PetHistoryContent.Empty -> emptyList()
            is PetHistoryContent.Single -> listOf(current.measurement)
            is PetHistoryContent.Multiple -> current.measurements
        }

    companion object {
        fun initial(
            petId: PetId,
            clock: Clock = Clock.systemDefaultZone(),
        ): PetHistoryUiState {
            val today = LocalDate.now(clock)
            val range = requireNotNull(ChartRangePreset.LAST_30_DAYS.rangeEndingOn(today))
            return PetHistoryUiState(
                petId = petId,
                startDate = range.startDate,
                endDateInclusive = range.endDateInclusive,
                rangePreset = ChartRangePreset.LAST_30_DAYS,
            )
        }
    }
}

data class PetHistoryCallbacks(
    val selectRangePreset: (ChartRangePreset) -> Unit,
    val setDateRange: (startDate: LocalDate, endDateInclusive: LocalDate) -> Unit,
    val requestDelete: (measurementId: String) -> Unit = {},
    val confirmDelete: () -> Unit = {},
    val dismissDelete: () -> Unit = {},
    val dismissActionError: () -> Unit = {},
)

internal fun petHistoryPresentation(
    measurements: List<PetMeasurement>,
    range: ChartDateRange,
    zoneId: ZoneId,
    locale: Locale,
): Pair<PetHistoryContent, ChartSeries> {
    val epochRange = inclusiveDateRangeToEpochRange(
        range.startDate,
        range.endDateInclusive,
        zoneId,
    )
    val start = java.time.Instant.ofEpochSecond(epochRange.startInclusiveEpochSecond)
    val endExclusive = java.time.Instant.ofEpochSecond(epochRange.endExclusiveEpochSecond)
    val ordered = measurements
        .asSequence()
        .filter { !it.measuredAt.isBefore(start) && it.measuredAt.isBefore(endExclusive) }
        .sortedWith(compareByDescending<PetMeasurement> { it.measuredAt }.thenByDescending { it.id })
        .toList()
    val rows = ordered.map { measurement ->
        PetHistoryMeasurementUi(
            id = measurement.id,
            measuredAtEpochSecond = measurement.measuredAt.epochSecond,
            measuredAtText = formatMeasurementDateTime(measurement.measuredAt, zoneId, locale),
            weightKg = measurement.petWeightKg,
            weightText = formatChartCurrentValue(measurement.petWeightKg, PetWeightChartMetric, locale),
        )
    }
    val content = when (rows.size) {
        0 -> PetHistoryContent.Empty
        1 -> PetHistoryContent.Single(rows.single())
        else -> PetHistoryContent.Multiple(rows)
    }
    val points = ordered
        .asReversed()
        .map { ChartPoint(it.measuredAt.epochSecond, it.petWeightKg) }
    return content to ChartSeries(PetWeightChartMetric, points)
}
