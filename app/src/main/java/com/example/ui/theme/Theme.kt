package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ColorOutlineSubtle = ObsidianOutline.copy(alpha = 0.55f)

private val CinemaDarkColorScheme = darkColorScheme(
    primary = CinemaAmber,
    onPrimary = OnCinemaAmber,
    primaryContainer = CinemaAmberDim,
    onPrimaryContainer = CinemaWhite,
    secondary = PhosphorCyan,
    onSecondary = ObsidianVoid,
    secondaryContainer = ObsidianElevated,
    onSecondaryContainer = CinemaWhite,
    tertiary = PhosphorGreen,
    onTertiary = ObsidianVoid,
    background = ObsidianVoid,
    onBackground = CinemaWhite,
    surface = ObsidianSurface,
    onSurface = CinemaWhite,
    surfaceVariant = ObsidianContainer,
    onSurfaceVariant = CinemaSilver,
    surfaceContainerHigh = ObsidianElevated,
    outline = ObsidianOutline,
    outlineVariant = ColorOutlineSubtle,
    error = TallyRed,
    onError = CinemaWhite
)

val CinemaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun Honor600ProCamTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CinemaDarkColorScheme,
        typography = CinemaTypography,
        shapes = CinemaShapes,
        content = content
    )
}
