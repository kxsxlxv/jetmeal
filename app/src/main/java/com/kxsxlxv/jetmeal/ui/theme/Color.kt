package com.kxsxlxv.jetmeal.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val MealLightColors = lightColorScheme(
    primary = Color(0xFF3D6841), onPrimary = Color.White,
    primaryContainer = Color(0xFFBEEBC0), onPrimaryContainer = Color(0xFF173D1D),
    secondary = Color(0xFF52634F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E8CD), onSecondaryContainer = Color(0xFF303E2C),
    tertiary = Color(0xFF80543D), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBCA), onTertiaryContainer = Color(0xFF653D28),
    background = Color(0xFFF6FAF1), onBackground = Color(0xFF191D18),
    surface = Color(0xFFF6FAF1), onSurface = Color(0xFF191D18),
    surfaceContainer = Color(0xFFEBF0E6), surfaceContainerLow = Color(0xFFF0F5EB),
    surfaceContainerHigh = Color(0xFFE5EADF), surfaceContainerHighest = Color(0xFFDFE4DA),
    onSurfaceVariant = Color(0xFF424A40), outline = Color(0xFF727A6F),
)

internal val MealDarkColors = darkColorScheme(
    primary = Color(0xFFA3CEA5), onPrimary = Color(0xFF0C3715),
    primaryContainer = Color(0xFF25502B), onPrimaryContainer = Color(0xFFBEEBC0),
    secondary = Color(0xFFB9CCB2), onSecondary = Color(0xFF253422),
    secondaryContainer = Color(0xFF3B4B37), onSecondaryContainer = Color(0xFFD5E8CD),
    tertiary = Color(0xFFF1BA9D), onTertiary = Color(0xFF492611),
    tertiaryContainer = Color(0xFF653D28), onTertiaryContainer = Color(0xFFFFDBCA),
    background = Color(0xFF10150F), onBackground = Color(0xFFDFE4DA),
    surface = Color(0xFF10150F), onSurface = Color(0xFFDFE4DA),
    surfaceContainer = Color(0xFF1C211B), surfaceContainerLow = Color(0xFF181D17),
    surfaceContainerHigh = Color(0xFF262C25), surfaceContainerHighest = Color(0xFF31372F),
    onSurfaceVariant = Color(0xFFC2C9BD), outline = Color(0xFF8C9488),
)
