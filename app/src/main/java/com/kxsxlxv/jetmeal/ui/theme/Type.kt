package com.kxsxlxv.jetmeal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun type(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
)

// System sans-serif supports Russian and accessibility scaling without a font download.
val Typography = Typography(
    displayLarge = type(57, 64, FontWeight.Medium, -1.5f),
    displayMedium = type(45, 52, FontWeight.Medium, -1f),
    displaySmall = type(36, 44, FontWeight.Medium, -.8f),
    headlineLarge = type(32, 40, FontWeight.Medium, -.5f),
    headlineMedium = type(28, 36, FontWeight.Medium, -.4f),
    headlineSmall = type(24, 32, FontWeight.Medium, -.3f),
    titleLarge = type(22, 28, FontWeight.Medium, -.2f),
    titleMedium = type(16, 24, FontWeight.Medium, .1f),
    titleSmall = type(14, 20, FontWeight.Medium, .1f),
    bodyLarge = type(16, 24, FontWeight.Normal, .1f),
    bodyMedium = type(14, 20, FontWeight.Normal, .1f),
    bodySmall = type(12, 16, FontWeight.Normal, .2f),
    labelLarge = type(14, 20, FontWeight.Medium, .1f),
    labelMedium = type(12, 16, FontWeight.Medium, .2f),
    labelSmall = type(11, 16, FontWeight.Medium, .2f),
    displayLargeEmphasized = type(57, 64, FontWeight.Bold, -1.5f),
    displayMediumEmphasized = type(45, 52, FontWeight.Bold, -1f),
    displaySmallEmphasized = type(36, 44, FontWeight.Bold, -.8f),
    headlineLargeEmphasized = type(32, 40, FontWeight.Bold, -.5f),
    headlineMediumEmphasized = type(28, 36, FontWeight.Bold, -.4f),
    headlineSmallEmphasized = type(24, 32, FontWeight.Bold, -.3f),
    titleLargeEmphasized = type(22, 28, FontWeight.SemiBold, -.2f),
    titleMediumEmphasized = type(16, 24, FontWeight.SemiBold, .1f),
    titleSmallEmphasized = type(14, 20, FontWeight.SemiBold, .1f),
    bodyLargeEmphasized = type(16, 24, FontWeight.Medium, .1f),
    bodyMediumEmphasized = type(14, 20, FontWeight.Medium, .1f),
    bodySmallEmphasized = type(12, 16, FontWeight.Medium, .2f),
    labelLargeEmphasized = type(14, 20, FontWeight.SemiBold, .1f),
    labelMediumEmphasized = type(12, 16, FontWeight.SemiBold, .2f),
    labelSmallEmphasized = type(11, 16, FontWeight.SemiBold, .2f),
)
