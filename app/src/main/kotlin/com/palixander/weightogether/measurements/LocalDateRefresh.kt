package com.palixander.weightogether.measurements

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

internal data class HomeChartRefreshInput<T>(
    val measurements: T,
    val persistedActiveSeriesKeys: Set<String>?,
    val currentDate: LocalDate,
)

internal fun <T> homeChartRefreshInputs(
    measurements: Flow<T>,
    persistedActiveSeriesKeys: Flow<Set<String>?>,
    currentDates: Flow<LocalDate>,
): Flow<HomeChartRefreshInput<T>> = combine(
    measurements,
    persistedActiveSeriesKeys,
    currentDates,
) { values, activeSeriesKeys, currentDate ->
    HomeChartRefreshInput(values, activeSeriesKeys, currentDate)
}

internal fun currentLocalDates(
    zoneId: ZoneId,
    clock: Clock = Clock.system(zoneId),
    awaitDuration: suspend (Duration) -> Unit = { duration ->
        delay(duration.toMillis().coerceAtLeast(1L))
    },
): Flow<LocalDate> = flow {
    while (currentCoroutineContext().isActive) {
        val now = clock.instant()
        emit(now.atZone(zoneId).toLocalDate())
        awaitDuration(Duration.between(now, nextLocalDayStart(now, zoneId)))
    }
}.distinctUntilChanged()

internal fun nextLocalDayStart(now: Instant, zoneId: ZoneId): Instant =
    now.atZone(zoneId)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zoneId)
        .toInstant()
