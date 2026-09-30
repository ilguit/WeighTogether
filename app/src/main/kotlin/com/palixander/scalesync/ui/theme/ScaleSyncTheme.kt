package com.palixander.scalesync.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
object ScaleSyncColors {
    val Background = Color(0xFFF5F7F3)
    val OnBackground = Color(0xFF19211E)
    val Primary = Color(0xFF28766B)
    val PrimaryPressed = Color(0xFF1E6259)
    val OnPrimary = Color.White
    val PrimaryContainer = Color(0xFFDDEEE8)
    val OnPrimaryContainer = Color(0xFF173A34)
    val PrimaryContainerDim = Color(0xFFCFE1DB)
    val Secondary = Color(0xFF4F625C)
    val SecondaryContainer = Color(0xFFE5F0EC)
    val OnSecondaryContainer = Color(0xFF215F57)
    val Surface = Color.White
    val SurfaceSubtle = Color(0xFFF9FCFA)
    val SurfaceInfo = Color(0xFFEDF5F1)
    val SurfaceContainer = Background
    val SurfaceContainerHigh = Color(0xFFEDF3EF)
    val SurfaceContainerHighest = Color(0xFFE4E9E6)
    val SurfaceDim = Color(0xFFDCE5E1)
    val OnSurface = OnBackground
    val OnSurfaceVariant = Color(0xFF68736F)
    val InverseSurface = Color(0xFF23312C)
    val InverseOnSurface = Color.White
    val Outline = Color(0xFFBCCBC6)
    val OutlineVariant = Color(0xFFE0E5E2)
    val Error = Color(0xFFBA4D45)
    val OnError = Color.White
    val ErrorContainer = Color(0xFFFFF8F7)
    val OnErrorContainer = Color(0xFFA33E38)
    val Warning = Color(0xFF9C5B34)
    val WarningContainer = Color(0xFFF6E6D9)
    val OnWarningContainer = Color(0xFF4F2A18)
    val Local = Color(0xFF6E7471)
    val LocalContainer = Color(0xFFE9ECEA)
    val Scrim = Color(0x6119211E)
}

val ScaleSyncLightColorScheme = lightColorScheme(
    primary = ScaleSyncColors.Primary,
    onPrimary = ScaleSyncColors.OnPrimary,
    primaryContainer = ScaleSyncColors.PrimaryContainer,
    onPrimaryContainer = ScaleSyncColors.OnPrimaryContainer,
    inversePrimary = ScaleSyncColors.PrimaryContainer,
    secondary = ScaleSyncColors.Secondary,
    onSecondary = ScaleSyncColors.OnPrimary,
    secondaryContainer = ScaleSyncColors.SecondaryContainer,
    onSecondaryContainer = ScaleSyncColors.OnSecondaryContainer,
    tertiary = ScaleSyncColors.Warning,
    onTertiary = Color.White,
    tertiaryContainer = ScaleSyncColors.WarningContainer,
    onTertiaryContainer = ScaleSyncColors.OnWarningContainer,
    background = ScaleSyncColors.Background,
    onBackground = ScaleSyncColors.OnBackground,
    surface = ScaleSyncColors.Surface,
    onSurface = ScaleSyncColors.OnSurface,
    surfaceVariant = ScaleSyncColors.SurfaceSubtle,
    onSurfaceVariant = ScaleSyncColors.OnSurfaceVariant,
    surfaceTint = ScaleSyncColors.Primary,
    inverseSurface = ScaleSyncColors.InverseSurface,
    inverseOnSurface = ScaleSyncColors.InverseOnSurface,
    outline = ScaleSyncColors.Outline,
    outlineVariant = ScaleSyncColors.OutlineVariant,
    error = ScaleSyncColors.Error,
    onError = ScaleSyncColors.OnError,
    errorContainer = ScaleSyncColors.ErrorContainer,
    onErrorContainer = ScaleSyncColors.OnErrorContainer,
    scrim = ScaleSyncColors.Scrim,
    surfaceBright = ScaleSyncColors.Surface,
    surfaceDim = ScaleSyncColors.SurfaceDim,
    surfaceContainer = ScaleSyncColors.SurfaceContainer,
    surfaceContainerHigh = ScaleSyncColors.SurfaceContainerHigh,
    surfaceContainerHighest = ScaleSyncColors.SurfaceContainerHighest,
    surfaceContainerLow = ScaleSyncColors.SurfaceSubtle,
    surfaceContainerLowest = ScaleSyncColors.Surface,
    primaryFixed = ScaleSyncColors.PrimaryContainer,
    primaryFixedDim = ScaleSyncColors.PrimaryContainerDim,
    onPrimaryFixed = ScaleSyncColors.OnPrimaryContainer,
    onPrimaryFixedVariant = ScaleSyncColors.OnSecondaryContainer,
    secondaryFixed = ScaleSyncColors.SecondaryContainer,
    secondaryFixedDim = ScaleSyncColors.SurfaceContainerHighest,
    onSecondaryFixed = ScaleSyncColors.OnBackground,
    onSecondaryFixedVariant = ScaleSyncColors.Secondary,
    tertiaryFixed = ScaleSyncColors.WarningContainer,
    tertiaryFixedDim = Color(0xFFEFCFB9),
    onTertiaryFixed = ScaleSyncColors.OnWarningContainer,
    onTertiaryFixedVariant = ScaleSyncColors.Warning,
)

val ScaleSyncShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

private val Roboto = FontFamily.SansSerif

val ScaleSyncTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Roboto,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun ScaleSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ScaleSyncLightColorScheme,
        typography = ScaleSyncTypography,
        shapes = ScaleSyncShapes,
        content = content,
    )
}
