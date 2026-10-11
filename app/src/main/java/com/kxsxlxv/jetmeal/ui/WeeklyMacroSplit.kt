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

/** Labels must be anchored to their sector centers, not spaced across the full row.
 * When a narrow sector causes overlapping labels, move the smaller sector's
 * label to the next lane rather than moving it away from its sector. */
internal data class MacroLegendPosition(val left: Int, val lane: Int)

internal fun macroLegendPositions(
    width: Int,
    fractions: List<Double>,
    visibleIndices: List<Int>,
    labelWidths: List<Int>,
    minGap: Int = 4,
): List<MacroLegendPosition> {
    require(width > 0 && fractions.isNotEmpty() && fractions.all { it.isFinite() && it >= 0.0 })
    require(visibleIndices.size == labelWidths.size && labelWidths.all { it >= 0 })
    require(visibleIndices.all { it in fractions.indices } && visibleIndices.distinct().size == visibleIndices.size)
    require(minGap >= 0)

    val total = fractions.sum()
    val shares = if (total > 0.0) fractions.map { it / total }
        else List(fractions.size) { 1.0 / fractions.size }
    var start = 0.0
    val centers = shares.map { share ->
        val center = start + share / 2
        start += share
        center
    }
    val lefts = visibleIndices.mapIndexed { position, sector ->
        val labelWidth = labelWidths[position]
        (centers[sector] * width - labelWidth / 2.0).roundToInt()
            .coerceIn(0, (width - labelWidth).coerceAtLeast(0))
    }

    val lanes = IntArray(visibleIndices.size)
    val laneContents = mutableListOf<MutableList<Int>>()
    // Larger sectors retain the main row, while cramped small ones are staggered.
    val priority = visibleIndices.indices.sortedWith(
        compareByDescending<Int> { shares[visibleIndices[it]] }.thenBy { it }
    )
    priority.forEach { i ->
        val availableLane = laneContents.indexOfFirst { others ->
            others.none { j ->
                lefts[i] < lefts[j] + labelWidths[j] + minGap &&
                    lefts[j] < lefts[i] + labelWidths[i] + minGap
            }
        }
        val lane = if (availableLane >= 0) availableLane else laneContents.size.also {
            laneContents.add(mutableListOf())
        }
        laneContents[lane].add(i)
        lanes[i] = lane
    }
    return visibleIndices.indices.map { MacroLegendPosition(lefts[it], lanes[it]) }
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
    val names = listOf("Белки", "Жиры", "Углеводы")
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
                val visible = if (selected == WeeklyMacroMode.Composition && parts.sum() > 0.0)
                    (0..2).filter { parts[it] > 0.0 } else (0..2).toList()
                Layout(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        visible.forEach { index ->
                            val value = if (selected == WeeklyMacroMode.Composition)
                                percents[index] else targetPercents[index]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    painter = painterResource(icons[index].resource),
                                    contentDescription = names[index],
                                    tint = colors[index].onContainer,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    "$value%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors[index].onContainer,
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                ) { measurables, constraints ->
                    val labels = measurables.map {
                        it.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    }
                    val positions = macroLegendPositions(
                        width = constraints.maxWidth,
                        fractions = slotShares,
                        visibleIndices = visible,
                        labelWidths = labels.map { it.width },
                        minGap = 4.dp.roundToPx(),
                    )
                    val laneHeight = labels.maxOfOrNull { it.height } ?: 0
                    val laneGap = 3.dp.roundToPx()
                    val lastLane = positions.maxOfOrNull { it.lane } ?: 0
                    layout(constraints.maxWidth, laneHeight * (lastLane + 1) + laneGap * lastLane) {
                        labels.forEachIndexed { index, label ->
                            label.place(
                                positions[index].left,
                                positions[index].lane * (laneHeight + laneGap),
                            )
                        }
                    }
                }
            }
        }
    }
}
