package com.example.huaweimisync.sync

import com.example.huaweimisync.data.MeasurementEntity
import com.example.huaweimisync.data.MeasurementValues

/** The exact subset of one measurement that still needs to be written to a destination. */
data class MeasurementSyncPayload(
    val measurement: MeasurementEntity,
    val includesWeight: Boolean,
) {
    val composition: MeasurementValues?
        get() = measurement.fullValues

    val isEmpty: Boolean
        get() = !includesWeight && composition == null
}
