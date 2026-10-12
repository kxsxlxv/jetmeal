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

/** ProportionalOrdered is the current primary layout: P → F → C clockwise from 12.
 * FixedY is intentionally retained for experimentation and quick rollback.
 */
internal enum class MacroDonutLayout { ProportionalOrdered, FixedY }

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

/** A single motion clock drives the ordered clockwise sweep and label fades.
 * The last 18% of the clock is reserved for labels, including a tiny last slice.
 * Angles are measured clockwise from the x-axis (Android Canvas conventions).
 */
internal const val MacroDonutArcEnd = .82f
internal const val MacroDonutLabelFadeWindow = .14f

internal data class MacroDonutFrame(
    val startAngle: Float,
    val drawnSweep: Float,
    val labelAlpha: Float,
)

private fun smoothMacroFade(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** Pure geometry: the sweep head is shared across P → F → C.
 * The small seam is deducted only from the END of each colored arc,
 * so the protein stroke always begins at precisely -90 degrees.
 */
internal fun orderedDonutFrame(arc: MacroDonutArc, progress: Float): MacroDonutFrame {
    require(arc.sweepAngle.isFinite() && arc.sweepAngle >= 0f)
    require(arc.startAngle.isFinite() && progress.isFinite())
    if (arc.sweepAngle <= 0f) return MacroDonutFrame(arc.startAngle, 0f, 0f)

    val clock = progress.coerceIn(0f, 1f)
    val sweepHead = 360f * (clock / MacroDonutArcEnd).coerceIn(0f, 1f)
    val sectorStart = arc.startAngle + 90f // protein -90° maps to zero travel
    val seam = minOf(2.5f, arc.sweepAngle * .20f)
    val coloredSweep = arc.sweepAngle - seam
    val drawnSweep = (sweepHead - sectorStart).coerceIn(0f, coloredSweep)
    val completionTime = MacroDonutArcEnd * (sectorStart + arc.sweepAngle) / 360f
    val alpha = smoothMacroFade((clock - completionTime) / MacroDonutLabelFadeWindow)
    return MacroDonutFrame(arc.startAngle, drawnSweep, alpha)
}

private data class MacroDonutSector(
    val fraction: Double,
    val percent: Int,
    val colors: RingColors,
    val icon: JetMealSymbol,
)

/**
 * Same diameter, centerline radius and 23dp stroke as CalorieHealthDial.
 * Primary ProportionalOrdered layout: protein starts at 12 o'clock, followed
 * clockwise by fat and carbs. Arc lengths represent true 4/9/4 kcal energy
 * shares. A single clockwise reveal animates their progress in order, then
 * curved labels fade in when their respective sectors finish.
 * The experimental FixedY layout is kept intact as an optional alternative.
 */
@Composable
internal fun MacroDistributionDonut(
    nutrition: Nutrition,
    diameter: Dp,
    layout: MacroDonutLayout = MacroDonutLayout.ProportionalOrdered,
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

                val frame = when (layout) {
                    MacroDonutLayout.ProportionalOrdered -> orderedDonutFrame(arc, reveal)
                    MacroDonutLayout.FixedY -> {
                        // Preserve the earlier three-way center-out animation.
                        val sweep = visibleSweep * reveal
                        MacroDonutFrame(
                            startAngle = arc.centerAngle - sweep / 2f,
                            drawnSweep = sweep,
                            labelAlpha = smoothMacroFade((reveal - .72f) / .24f),
                        )
                    }
                }
                val start = frame.startAngle
                val drawnSweep = frame.drawnSweep
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
                // Each ordered-sector label fades AFTER its own arc completes.
                // A separate tail of the shared clock lets even a tiny last
                // sector's label fade smoothly, rather than appearing in one frame.
                val labelReveal = frame.labelAlpha
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
