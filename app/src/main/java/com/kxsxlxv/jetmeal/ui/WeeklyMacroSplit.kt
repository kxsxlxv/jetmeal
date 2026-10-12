package com.kxsxlxv.jetmeal.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.ui.theme.nutritionColors
import java.time.LocalDate
import kotlin.math.roundToInt

internal enum class WeeklyMacroMode { Composition, Targets }

/** Compact, single-line legend for a proportional macro rail.
 * Prefer icon + percentage; show just percentage when a sector is too narrow.
 * Only for exceptionally crowded distributions fall back to an icon, or hide
 * an impossible label (its exact percentage remains in TalkBack semantics).
 */
internal enum class MacroLegendStyle { Full, PercentOnly, IconOnly, Hidden }
internal data class MacroLegendWidths(val full: Int, val percent: Int, val icon: Int) {
    init { require(full >= 0 && percent >= 0 && icon >= 0) }
    fun forStyle(style: MacroLegendStyle): Int = when (style) {
        MacroLegendStyle.Full -> full
        MacroLegendStyle.PercentOnly -> percent
        MacroLegendStyle.IconOnly -> icon
        MacroLegendStyle.Hidden -> 0
    }
}
internal data class MacroLegendPlacement(val left: Int, val style: MacroLegendStyle)

/** This pure solver uses exactly the same segment fractions as the Canvas.
 * All labels occupy ONE line; cramped labels degrade instead of adding a row.
 */
internal fun macroLegendLayout(
    width: Int,
    fractions: List<Double>,
    labelWidths: List<MacroLegendWidths>,
    minGap: Int = 4,
    sectorInset: Int = 4,
): List<MacroLegendPlacement> {
    require(width > 0 && fractions.isNotEmpty() && fractions.size == labelWidths.size)
    require(fractions.all { it.isFinite() && it >= 0.0 })
    require(minGap >= 0 && sectorInset >= 0)

    val total = fractions.sum()
    if (total <= 0.0) return fractions.map { MacroLegendPlacement(0, MacroLegendStyle.Hidden) }
    val shares = fractions.map { it / total }
    var previous = 0.0
    val centers = shares.map { fraction ->
        val midpoint = previous + fraction / 2.0
        previous += fraction
        midpoint
    }
    val styles = shares.indices.map { i ->
        when {
            shares[i] <= 0.0 -> MacroLegendStyle.Hidden
            labelWidths[i].full + sectorInset * 2 <= width * shares[i] ->
                MacroLegendStyle.Full
            else -> MacroLegendStyle.PercentOnly
        }
    }.toMutableList()

    fun left(i: Int): Int {
        val labelWidth = labelWidths[i].forStyle(styles[i])
        return (width * centers[i] - labelWidth / 2.0).roundToInt()
            .coerceIn(0, (width - labelWidth).coerceAtLeast(0))
    }
    fun overlappingPair(): Pair<Int, Int>? {
        val visible = shares.indices.filter { styles[it] != MacroLegendStyle.Hidden }
        return visible.zipWithNext().firstOrNull { (first, second) ->
            left(first) + labelWidths[first].forStyle(styles[first]) + minGap > left(second)
        }
    }
    fun downgrade(style: MacroLegendStyle) = when (style) {
        MacroLegendStyle.Full -> MacroLegendStyle.PercentOnly
        MacroLegendStyle.PercentOnly -> MacroLegendStyle.IconOnly
        MacroLegendStyle.IconOnly -> MacroLegendStyle.Hidden
        MacroLegendStyle.Hidden -> MacroLegendStyle.Hidden
    }

    // At most 3 downgrades per macro. Smaller sectors yield first;
    // a large sector never steals space from a smaller one's percentage.
    repeat(fractions.size * 3) {
        val conflict = overlappingPair() ?: return@repeat
        val (first, second) = conflict
        val victim = if (shares[first] < shares[second]) first
            else if (shares[second] < shares[first]) second
            else second
        styles[victim] = downgrade(styles[victim])
    }
    return shares.indices.map { i -> MacroLegendPlacement(left(i), styles[i]) }
}

/** One rail with two meanings. No three extra full-width bars or new cards. */
@Composable
internal fun WeeklyMacroSplit(
    weekStart: LocalDate,
    actual: Nutrition,
    target: Nutrition,
    fillFraction: () -> Float,
) {
    var mode by rememberSaveable(weekStart.toString()) { mutableStateOf(WeeklyMacroMode.Composition) }
    val shares = macroEnergyShares(actual)
    val parts = shares.fractions
    val percents = macroEnergyPercentages(shares)
    val targets = listOf(target.protein, target.fat, target.carbs)
    val consumed = listOf(actual.protein, actual.fat, actual.carbs)
    val targetPercents = targets.indices.map { index ->
        if (targets[index] > 0.0) (consumed[index] / targets[index] * 100).toInt()
        else 0
    }
    val palette = nutritionColors()
    val colors = listOf(palette.protein, palette.fat, palette.carbs)
    val icons = listOf(JetMealSymbol.Protein, JetMealSymbol.Fat, JetMealSymbol.Carbs)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val separator = MaterialTheme.colorScheme.surfaceContainerHigh

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clickable(role = Role.Button) {
                mode = if (mode == WeeklyMacroMode.Composition)
                    WeeklyMacroMode.Targets else WeeklyMacroMode.Composition
            }
            .semantics {
                stateDescription = if (mode == WeeklyMacroMode.Composition)
                    "Доли энергии БЖУ" else "Выполнение недельной нормы БЖУ"
                contentDescription = "Сменить вид недельных БЖУ. " +
                    "Белки ${percents[0]}%, жиры ${percents[1]}%, углеводы ${percents[2]}% по энергии"
            },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Crossfade(targetState = mode, animationSpec = tween(220),
            label = "weekly-macro-mode") { selected ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                    val capsule = Path().apply {
                        val radius = CornerRadius(size.height / 2f)
                        addRoundRect(RoundRect(0f, 0f, size.width, size.height,
                            radius, radius, radius, radius))
                    }
                    val animation = fillFraction().coerceIn(0f, 1f)
                    clipPath(capsule) {
                        drawRect(track)
                        var cursor = 0f
                        (0..2).forEach { index ->
                            val slotFraction = if (selected == WeeklyMacroMode.Composition)
                                parts[index].toFloat() else 1f / 3f
                            val slotWidth = size.width * slotFraction
                            val progress = if (selected == WeeklyMacroMode.Composition) 1f
                                else if (targets[index] > 0.0)
                                    (consumed[index] / targets[index]).toFloat().coerceIn(0f, 1f)
                                else 0f
                            val fillWidth = slotWidth * animation * progress
                            if (fillWidth > 0f) {
                                drawRect(Brush.horizontalGradient(
                                    listOf(colors[index].start, colors[index].end),
                                    startX = cursor, endX = cursor + slotWidth.coerceAtLeast(1f)),
                                    topLeft = Offset(cursor, 0f),
                                    size = Size(fillWidth, size.height))
                            }
                            cursor += slotWidth
                            if (index < 2) {
                                drawRect(separator, topLeft = Offset(cursor - 1.dp.toPx() / 2f, 0f),
                                    size = Size(1.dp.toPx(), size.height))
                            }
                        }
                    }
                }
                val slotShares = if (selected == WeeklyMacroMode.Composition)
                    parts else listOf(1.0 / 3, 1.0 / 3, 1.0 / 3)
                val visible = if (slotShares.sum() > 0.0)
                    (0..2).filter { slotShares[it] > 0.0 } else emptyList()
                Layout(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        visible.forEach { index ->
                            val value = if (selected == WeeklyMacroMode.Composition)
                                percents[index] else targetPercents[index]
                            val labelColor = colors[index].onContainer
                            // 3 presentations are measured. Only the chosen one is placed.
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(painter = painterResource(icons[index].resource),
                                    contentDescription = null,
                                    tint = labelColor, modifier = Modifier.size(15.dp))
                                Text("$value%", style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold, color = labelColor, maxLines = 1)
                            }
                            Text("$value%", style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold, color = labelColor, maxLines = 1)
                            Icon(painter = painterResource(icons[index].resource),
                                contentDescription = null,
                                tint = labelColor, modifier = Modifier.size(15.dp))
                        }
                    },
                ) { measurables, constraints ->
                    val variants = measurables.map {
                        it.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    }.chunked(3)
                    val widthsBySector = (0..2).map { index ->
                        val position = visible.indexOf(index)
                        if (position < 0) MacroLegendWidths(0, 0, 0)
                        else MacroLegendWidths(
                            variants[position][0].width,
                            variants[position][1].width,
                            variants[position][2].width,
                        )
                    }
                    val positions = macroLegendLayout(
                        width = constraints.maxWidth,
                        fractions = slotShares,
                        labelWidths = widthsBySector,
                        minGap = 4.dp.roundToPx(),
                        sectorInset = 2.dp.roundToPx(),
                    )
                    val height = variants.flatMap { it }.maxOfOrNull { it.height } ?: 0
                    layout(constraints.maxWidth, height) {
                        visible.forEachIndexed { indexInLayout, sector ->
                            val position = positions[sector]
                            val variantIndex = when (position.style) {
                                MacroLegendStyle.Full -> 0
                                MacroLegendStyle.PercentOnly -> 1
                                MacroLegendStyle.IconOnly -> 2
                                MacroLegendStyle.Hidden -> -1
                            }
                            if (variantIndex >= 0) {
                                val label = variants[indexInLayout][variantIndex]
                                label.place(position.left, (height - label.height) / 2)
                            }
                        }
                    }
                }
            }
        }
    }
}
