package com.palixander.weightogether.changelog

import androidx.compose.runtime.Immutable

@Immutable
data class ReleaseChange(
    val issueNumber: Int,
    val description: String,
)

@Immutable
data class AppRelease(
    val version: String,
    val changes: List<ReleaseChange>,
)
