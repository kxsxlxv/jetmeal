package com.kxsxlxv.jetmeal.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

internal val MealLightColors = lightColorScheme(
    primary = Color(0xFF23634B), onPrimary = Color.White,
    primaryContainer = Color(0xFFC2EFD4), onPrimaryContainer = Color(0xFF123C2C),
    secondary = Color(0xFF565D74), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEE2FA), onSecondaryContainer = Color(0xFF33394F),
    tertiary = Color(0xFF8B4D35), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBCC), onTertiaryContainer = Color(0xFF5C2916),
    background = Color(0xFFF8FAF5), onBackground = Color(0xFF18221C),
    surface = Color(0xFFF8FAF5), onSurface = Color(0xFF18221C),
    surfaceDim = Color(0xFFD8DDD6), surfaceBright = Color(0xFFF8FAF5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F4ED),
    surfaceContainer = Color(0xFFEAEFE6), surfaceContainerHigh = Color(0xFFE3E9E0),
    surfaceContainerHighest = Color(0xFFDDE3DA),
    onSurfaceVariant = Color(0xFF46534A), outline = Color(0xFF748178),
    outlineVariant = Color(0xFFC4CEC4),
    error = Color(0xFFAF342E), onError = Color.White,
    errorContainer = Color(0xFFFFDAD5), onErrorContainer = Color(0xFF76201C),
    inverseSurface = Color(0xFF2D3830), inverseOnSurface = Color(0xFFEFF5EC),
    inversePrimary = Color(0xFF9BD6B5), surfaceTint = Color(0xFF23634B),
)

internal val MealDarkColors = darkColorScheme(
    primary = Color(0xFF9BD6B5), onPrimary = Color(0xFF073A26),
    primaryContainer = Color(0xFF214E3A), onPrimaryContainer = Color(0xFFC2EFD4),
    secondary = Color(0xFFBEC4E1), onSecondary = Color(0xFF282F46),
    secondaryContainer = Color(0xFF3E465E), onSecondaryContainer = Color(0xFFDEE2FA),
    tertiary = Color(0xFFF9B59A), onTertiary = Color(0xFF52220F),
    tertiaryContainer = Color(0xFF703720), onTertiaryContainer = Color(0xFFFFDBCC),
    background = Color(0xFF101711), onBackground = Color(0xFFE0E7DC),
    surface = Color(0xFF101711), onSurface = Color(0xFFE0E7DC),
    surfaceDim = Color(0xFF101711), surfaceBright = Color(0xFF343D35),
    surfaceContainerLowest = Color(0xFF0B120D), surfaceContainerLow = Color(0xFF182019),
    surfaceContainer = Color(0xFF1D261F), surfaceContainerHigh = Color(0xFF273128),
    surfaceContainerHighest = Color(0xFF323C33),
    onSurfaceVariant = Color(0xFFBECABD), outline = Color(0xFF89978A),
    outlineVariant = Color(0xFF404E43),
    error = Color(0xFFFFB4AA), onError = Color(0xFF690E0B),
    errorContainer = Color(0xFF8C241E), onErrorContainer = Color(0xFFFFDAD5),
    inverseSurface = Color(0xFFE0E7DC), inverseOnSurface = Color(0xFF273128),
    inversePrimary = Color(0xFF23634B), surfaceTint = Color(0xFF9BD6B5),
)

@Immutable
data class RingColors(val start: Color, val end: Color, val container: Color, val onContainer: Color)

@Immutable
data class NutritionColors(val calories: RingColors, val protein: RingColors, val fat: RingColors, val carbs: RingColors)

internal val LightNutritionColors = NutritionColors(
    calories = RingColors(Color(0xFF79BE86), Color(0xFF23634B), Color(0xFFDCEFD9), Color(0xFF16442C)),
    protein = RingColors(Color(0xFFBD9AE6), Color(0xFF7445A3), Color(0xFFEEE2FB), Color(0xFF54277D)),
    fat = RingColors(Color(0xFFEBC67A), Color(0xFF946000), Color(0xFFF8EBCC), Color(0xFF704900)),
    carbs = RingColors(Color(0xFFE89E85), Color(0xFFAF4A31), Color(0xFFFFE3D8), Color(0xFF853321)),
)

internal val DarkNutritionColors = NutritionColors(
    calories = RingColors(Color(0xFF507C54), Color(0xFF9FE0B4), Color(0xFF243E2D), Color(0xFFBCEDC9)),
    protein = RingColors(Color(0xFF80619D), Color(0xFFDAC0FC), Color(0xFF3A2B4C), Color(0xFFE4CBFF)),
    fat = RingColors(Color(0xFF9F803F), Color(0xFFF1D28A), Color(0xFF44391D), Color(0xFFFFDE9D)),
    carbs = RingColors(Color(0xFF9B6150), Color(0xFFFFBA9F), Color(0xFF4B2E24), Color(0xFFFFCDBA)),
)

internal val LocalNutritionColors = staticCompositionLocalOf { LightNutritionColors }

@Composable @ReadOnlyComposable
fun nutritionColors(): NutritionColors = LocalNutritionColors.current
