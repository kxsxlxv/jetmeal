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

/** An experimental arrangement with fixed category positions, independent of shares.
 * To roll it back, change MacroDistributionDonut's default layout to Proportional.
 * Y-center angles (Android Canvas coordinates): B=-150° (upper-left),
 * J=-30° (upper-right), U=90° (bottom). Each slot is exactly 120°.
 */
internal enum class MacroDonutLayout { FixedY, Proportional }

internal data class MacroDonutArc(
    val startAngle: Float,
    val sweepAngle: Float,
    val centerAngle: Float,
)

internal fun macroDonutArcs(
    fractions: List<Double>,
    layout: MacroDonutLayout,
): List<MacroDonutArc> {
    require(fractions.size == 3 && fractions.all { it.isFinite() && it >= 0.0 })
    if (layout == MacroDonutLayout.FixedY) {
        return (0..2).map { i ->
            val start = -210f + i * 120f
            MacroDonutArc(startAngle = start, sweepAngle = 120f, centerAngle = start + 60f)
        }
    }
    var cursor = -90f
    return fractions.map { share ->
        val sweep = (share * 360.0).toFloat()
        MacroDonutArc(startAngle = cursor, sweepAngle = sweep, centerAngle = cursor + sweep / 2f)
            .also { cursor += sweep }
    }
}

private data class MacroDonutSector(
    val fraction: Double,
    val percent: Int,
    val colors: RingColors,
    val icon: JetMealSymbol,
)

/**
 * Same diameter, centerline radius and 23dp stroke as CalorieHealthDial.
 * In FixedY mode, macro categories occupy three equal, immovable 120-degree
 * positions. Their *labels* represent actual energy shares (4/9/4 kcal per g);
 * the colored arc lengths intentionally do not. Previous proportional layout
 * is preserved for a one-line rollback.
 * Percentages follow the arc at each category's fixed angle.
 */
@Composable
internal fun MacroDistributionDonut(
    nutrition: Nutrition,
    diameter: Dp,
    layout: MacroDonutLayout = MacroDonutLayout.FixedY,
    modifier: Modifier = Modifier,
) {
    val palette = nutritionColors()
    val shares = macroEnergyShares(nutrition)
    val fractions = shares.fractions
    val percents = macroEnergyPercentages(shares)
    val arcs = macroDonutArcs(fractions, layout)
    val hasFood = fractions.any { it > 0.0 }
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
    LaunchedEffect(nutrition, layout) {
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
            sectors.forEachIndexed { index, sector ->
                val arc = arcs[index]
                val fullSweep = arc.sweepAngle
                // No colored arcs when there is no logged nutrition. Zero-share
                // categories in FixedY remain muted on the neutral track.
                if (!hasFood || sector.fraction <= 0.0 || fullSweep <= 0f) return@forEachIndexed
                val gap = if (layout == MacroDonutLayout.FixedY) 3.5f
                    else minOf(2.5f, fullSweep * .20f)
                val visibleSweep = (fullSweep - gap).coerceAtLeast(0f)
                if (visibleSweep <= 0f) return@forEachIndexed

                val reveal = clock.value.coerceIn(0f, 1f)
                // FixedY: grow all THREE sectors together from their own centers,
                // keeping the Y silhouette steady through the entire 1450ms motion.
                // Proportional: preserve the original single sequential sweep.
                val start: Float
                val drawnSweep: Float
                if (layout == MacroDonutLayout.FixedY) {
                    drawnSweep = visibleSweep * reveal
                    start = arc.centerAngle - drawnSweep / 2f
                } else {
                    start = arc.startAngle + gap / 2f
                    drawnSweep = (360f * reveal - (start + 90f)).coerceIn(0f, visibleSweep)
                }
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
                // Labels fade in only during the final 25% of the Y arc
                // reveal. The earlier proportional mode retains its label timing.
                val labelReveal = if (layout == MacroDonutLayout.FixedY)
                    ((reveal - .72f) / .24f).coerceIn(0f, 1f)
                else if (drawnSweep >= visibleSweep * .98f) 1f else 0f
                if (labelReveal > 0f) {
                    val mid = arc.centerAngle
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
                        paint.alpha = (255 * labelReveal).toInt()
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
                            drawable.alpha = (255 * labelReveal).toInt()
                            drawable.setBounds(
                                (x - iconSize / 2).toInt(), (y - iconSize / 2).toInt(),
                                (x + iconSize / 2).toInt(), (y + iconSize / 2).toInt(),
                            )
                            // Rotate the vector icon to the same circle tangent as the
                            // percentage text rather than leaving it screen-horizontal.
                            val saveCount = nativeCanvas.save()
                            nativeCanvas.rotate(
                                iconAngle + if (direction > 0f) 90f else -90f,
                                x, y,
                            )
                            drawable.draw(nativeCanvas)
                            nativeCanvas.restoreToCount(saveCount)
                        }
                    }
                    paint.textSize = originalSize
                    paint.alpha = 255
                }
            }
        }
    }
}
