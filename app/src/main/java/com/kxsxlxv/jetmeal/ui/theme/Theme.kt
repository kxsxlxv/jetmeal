package com.kxsxlxv.jetmeal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

private val JetMealShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
    largeIncreased = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(36.dp),
    extraExtraLarge = RoundedCornerShape(44.dp),
)

@Composable
fun JetmealTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalNutritionColors provides if (darkTheme) DarkNutritionColors else LightNutritionColors,
    ) {
        MaterialExpressiveTheme(
            colorScheme = if (darkTheme) MealDarkColors else MealLightColors,
            motionScheme = MotionScheme.expressive(),
            typography = Typography,
            shapes = JetMealShapes,
            content = content,
        )
    }
}
