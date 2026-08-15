package com.example.huaweimisync.ui.theme

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
object HuaweiColors {
    val Background = Color(0xFFF5F7F3)
    val OnBackground = Color(0xFF19211E)
    val Primary = Color(0xFF28766B)
    val PrimaryPressed = Color(0xFF1E6259)
    val OnPrimary = Color.White
    val PrimaryContainer = Color(0xFFDDEEE8)
    val OnPrimaryContainer = Color(0xFF173A34)
    val Surface = Color.White
    val SurfaceSubtle = Color(0xFFF9FCFA)
    val SurfaceInfo = Color(0xFFEDF5F1)
    val OnSurface = OnBackground
    val OnSurfaceVariant = Color(0xFF68736F)
    val Outline = Color(0xFFBCCBC6)
    val OutlineVariant = Color(0xFFE0E5E2)
    val Error = Color(0xFFBA4D45)
    val OnError = Color.White
    val Warning = Color(0xFF9C5B34)
    val WarningContainer = Color(0xFFF6E6D9)
    val Local = Color(0xFF6E7471)
    val LocalContainer = Color(0xFFE9ECEA)
    val Scrim = Color(0x6119211E)
}

val HuaweiLightColorScheme = lightColorScheme(
    primary = HuaweiColors.Primary,
    onPrimary = HuaweiColors.OnPrimary,
    primaryContainer = HuaweiColors.PrimaryContainer,
    onPrimaryContainer = HuaweiColors.OnPrimaryContainer,
    secondary = Color(0xFF4F625C),
    onSecondary = HuaweiColors.OnPrimary,
    secondaryContainer = Color(0xFFE5F0EC),
    onSecondaryContainer = Color(0xFF215F57),
    tertiary = HuaweiColors.Warning,
    onTertiary = Color.White,
    tertiaryContainer = HuaweiColors.WarningContainer,
    onTertiaryContainer = Color(0xFF4F2A18),
    background = HuaweiColors.Background,
    onBackground = HuaweiColors.OnBackground,
    surface = HuaweiColors.Surface,
    onSurface = HuaweiColors.OnSurface,
    surfaceVariant = HuaweiColors.SurfaceSubtle,
    onSurfaceVariant = HuaweiColors.OnSurfaceVariant,
    outline = HuaweiColors.Outline,
    outlineVariant = HuaweiColors.OutlineVariant,
    error = HuaweiColors.Error,
    onError = HuaweiColors.OnError,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = HuaweiColors.Scrim,
)

val HuaweiShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

private val Roboto = FontFamily.SansSerif

val HuaweiTypography = Typography(
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
fun HuaweiMiSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HuaweiLightColorScheme,
        typography = HuaweiTypography,
        shapes = HuaweiShapes,
        content = content,
    )
}
