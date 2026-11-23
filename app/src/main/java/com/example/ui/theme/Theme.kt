package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = OnTealPrimary,
    primaryContainer = TealContainer,
    onPrimaryContainer = OnTealContainer,
    secondary = AmberSecondary,
    onSecondary = OnAmberSecondary,
    secondaryContainer = TealTonalContainerLight,
    onSecondaryContainer = OnTealTonalContainerLight,
    tertiary = SageTertiary,
    onTertiary = OnSageTertiary,
    tertiaryContainer = SageContainer,
    onTertiaryContainer = OnSageTertiaryContainer,
    error = CrimsonError,
    onError = OnCrimsonError,
    errorContainer = CrimsonErrorContainer,
    onErrorContainer = OnCrimsonErrorContainer,
    background = AlabasterBackground,
    onBackground = InkOnBackground,
    surface = PureSurface,
    onSurface = InkOnBackground,
    surfaceVariant = SoftSurfaceVariant,
    onSurfaceVariant = MutedOnSurfaceVariant,
    outline = HairlineOutline,
    outlineVariant = SubtleOutlineVariant
)

private val DarkColorScheme = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = OnTealPrimaryDark,
    primaryContainer = TealContainerDark,
    onPrimaryContainer = OnTealContainerDark,
    secondary = AmberSecondaryDark,
    onSecondary = OnAmberSecondaryDark,
    secondaryContainer = TealTonalContainerDark,
    onSecondaryContainer = OnTealTonalContainerDark,
    tertiary = SageTertiaryDark,
    onTertiary = OnSageTertiaryDark,
    tertiaryContainer = SageContainerDark,
    onTertiaryContainer = OnSageTertiaryContainerDark,
    error = CrimsonErrorDark,
    onError = OnCrimsonErrorDark,
    errorContainer = CrimsonErrorContainerDark,
    onErrorContainer = OnCrimsonErrorContainerDark,
    background = DeepSlateBackground,
    onBackground = MistOnBackgroundDark,
    surface = SlateSurfaceDark,
    onSurface = MistOnBackgroundDark,
    surfaceVariant = ElevatedSurfaceDark,
    onSurfaceVariant = MutedOnSurfaceDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark
)

val SafeNestShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun SafeNestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = SafeNestShapes,
        content = content
    )
}
