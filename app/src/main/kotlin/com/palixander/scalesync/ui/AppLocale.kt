package com.palixander.scalesync.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/** Locale from the app-specific configuration, rather than the process default. */
val Resources.appLocale: Locale
    get() = configuration.locales[0] ?: Locale.getDefault()

@Composable
fun currentAppLocale(): Locale = LocalConfiguration.current.locales[0]
