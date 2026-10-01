package com.palixander.weightogether.charts

import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver

internal val ChartScrollOffset = SemanticsPropertyKey<Float>("ChartScrollOffset")

internal var SemanticsPropertyReceiver.chartScrollOffset by ChartScrollOffset
