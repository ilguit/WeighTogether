package com.palixander.scalesync.domain

/** Provenance is independent of subsequent editing and external sync policy. */
enum class MeasurementOrigin {
    LEGACY,
    SCALE,
    MANUAL,
}
