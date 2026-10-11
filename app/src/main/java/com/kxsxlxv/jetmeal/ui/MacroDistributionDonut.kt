package com.kxsxlxv.jetmeal.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.DrawableCompat
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.ui.theme.RingColors
import com.kxsxlxv.jetmeal.ui.theme.nutritionColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Energy shares, not shares of grams or of the food-label calorie number. */
internal data class MacroEnergyShares(val protein: Double, val fat: Double, val carbs: Double) {
    val fractions: List<Double> get() = listOf(protein, fat, carbs)
}

internal fun macroEnergyShares(nutrition: Nutrition): MacroEnergyShares {
    val energies = listOf(nutrition.protein * 4, nutrition.fat * 9, nutrition.carbs * 4)
    val total = energies.sum()
    if (total <= 0.0) return MacroEnergyShares(0.0, 0.0, 0.0)
    return MacroEnergyShares(energies[0] / total, energies[1] / total, energies[2] / total)
}

/** Largest remainders ensure the three displayed whole percentages add to 100. */
internal fun macroEnergyPercentages(shares: MacroEnergyShares): List<Int> {
    val values = shares.fractions.map { it * 100.0 }
    if (values.all { it == 0.0 }) return listOf(0, 0, 0)
    val roundedDown = values.map { floor(it).toInt() }.toMutableList()
    val remaining = (100 - roundedDown.sum()).coerceIn(0, 3)
    val order = values.indices.sortedWith(compareByDescending<Int> { values[it] - floor(values[it]) }.thenBy { it })
    repeat(remaining) { roundedDown[order[it]]++ }
    return roundedDown
}

private data class MacroDonutSector(
    val fraction: Double,
    val percent: Int,
    val colors: RingColors,
    val icon: JetMealSymbol,
)

/**
 * Same diameter, centerline radius and 23dp stroke as CalorieHealthDial.
 * Percentages follow the arc, centered as a group with their original small icon.
 * For tiny slices, prioritize a legible percentage over the icon; for slices too
 * narrow even for that, TalkBack exposes the complete values on the toggle.
 */
@Composable
internal fun MacroDistributionDonut(
    nutrition: Nutrition,
    diameter: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = nutritionColors()
    val shares = macroEnergyShares(nutrition)
    val fractions = shares.fractions
    val percents = macroEnergyPercentages(shares)
    val sectors = listOf(
        MacroDonutSector(fractions[0], percents[0], palette.protein, JetMealSymbol.Protein),
        MacroDonutSector(fractions[1], percents[1], palette.fat, JetMealSymbol.Fat),
        MacroDonutSector(fractions[2], percents[2], palette.carbs, JetMealSymbol.Carbs),
    )
    val density = LocalDensity.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // LocalResources triggers recomposition for configuration-dependent drawables
    // (locale, night mode, density), unlike LocalContext.getDrawable().
    val icons = remember(resources, context.theme) {
        listOf(JetMealSymbol.Protein, JetMealSymbol.Fat, JetMealSymbol.Carbs).map {
            resources.getDrawable(it.resource, context.theme).mutate()
        }
    }
    val paint = remember(density.density, density.fontScale) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = with(density) { 9.sp.toPx() }
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
    }
    val textPath = remember { Path() }
    val bounds = remember { RectF() }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(nutrition) {
        clock.snapTo(0f)
        clock.animateTo(1f, tween(durationMillis = 1450,
            easing = CubicBezierEasing(.35f, 0f, .15f, 1f)))
    }

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter).clearAndSetSemantics {}) {
            val stroke = 23.dp.toPx()
            val radius = size.minDimension / 2f - stroke / 2f - 3.dp.toPx()
            val centerPoint = center
            val origin = Offset(centerPoint.x - radius, centerPoint.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            val reveal = clock.value.coerceIn(0f, 1f)
            drawArc(track, -90f, 360f, false, origin, arcSize, style = Stroke(stroke))

            bounds.set(origin.x, origin.y, origin.x + arcSize.width, origin.y + arcSize.height)
            val nativeCanvas = drawContext.canvas.nativeCanvas
            var cursor = -90f
            sectors.forEachIndexed { index, sector ->
                val fullSweep = (sector.fraction * 360.0).toFloat()
                if (fullSweep <= 0f) return@forEachIndexed
                val gap = minOf(2.5f, fullSweep * .20f)
                val visibleSweep = (fullSweep - gap).coerceAtLeast(0f)
                if (visibleSweep <= 0f) {
                    cursor += fullSweep
                    return@forEachIndexed
                }
                val start = cursor + gap / 2f
                // One continuous 1450ms sweep, revealing protein, fat and carbs in order.
                val drawnSweep = (360f * reveal - (start + 90f)).coerceIn(0f, visibleSweep)
                if (drawnSweep > .001f) {
                    drawArc(
                        brush = Brush.linearGradient(
                            0f to sector.colors.container,
                            .42f to sector.colors.start,
                            1f to sector.colors.end,
                            start = Offset(origin.x, origin.y),
                            end = Offset(origin.x + arcSize.width, origin.y + arcSize.height),
                        ),
                        startAngle = start,
                        sweepAngle = drawnSweep,
                        useCenter = false,
                        topLeft = origin,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Butt),
                    )
                }
                // Do not display a label until the entire central group has been revealed.
                if (drawnSweep >= visibleSweep * .98f && sector.percent > 0) {
                    val mid = cursor + fullSweep / 2f
                    val normalizedMid = ((mid % 360f) + 360f) % 360f
                    val direction = if (normalizedMid > 0f && normalizedMid < 180f) -1f else 1f
                    val label = "${sector.percent}%"
                    val originalSize = paint.textSize
                    val availablePx = radius * Math.toRadians(visibleSweep.toDouble()).toFloat() * .82f
                    var textWidth = paint.measureText(label)
                    if (textWidth > availablePx) {
                        paint.textSize = (originalSize * availablePx / textWidth).coerceAtLeast(0f)
                        textWidth = paint.measureText(label)
                    }
                    if (paint.textSize >= with(density) { 7.sp.toPx() } && textWidth <= availablePx) {
                        paint.color = sector.colors.onContainer.toArgb()
                        val iconSize = 11.dp.toPx()
                        val iconGap = 2.dp.toPx()
                        val withIcon = icons[index] != null && textWidth + iconSize + iconGap < availablePx
                        // Center the PERCENTAGE itself precisely on the sector midpoint;
                        // the optional icon sits beside it, never shifting that center.
                        val degreesPerPx = 180f / (PI.toFloat() * radius)
                        val textStart = mid - direction * textWidth / 2f * degreesPerPx
                        textPath.rewind()
                        textPath.addArc(bounds, textStart, direction * textWidth * degreesPerPx)
                        val metrics = paint.fontMetrics
                        val centeredBaseline = -(metrics.ascent + metrics.descent) / 2f
                        nativeCanvas.drawTextOnPath(label, textPath, 0f, centeredBaseline, paint)
                        if (withIcon) {
                            val iconAngle = mid - direction * (textWidth / 2f + iconGap + iconSize / 2f) * degreesPerPx
                            val radians = Math.toRadians(iconAngle.toDouble())
                            val x = centerPoint.x + radius * cos(radians).toFloat()
                            val y = centerPoint.y + radius * sin(radians).toFloat()
                            val drawable = icons[index]!!
                            DrawableCompat.setTint(drawable, sector.colors.onContainer.toArgb())
                            drawable.setBounds(
                                (x - iconSize / 2).toInt(), (y - iconSize / 2).toInt(),
                                (x + iconSize / 2).toInt(), (y + iconSize / 2).toInt(),
                            )
                            drawable.draw(nativeCanvas)
                        }
                    }
                    paint.textSize = originalSize
                }
                cursor += fullSweep
            }
        }
    }
}
