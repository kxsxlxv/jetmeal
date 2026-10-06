@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.kxsxlxv.jetmeal.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kxsxlxv.jetmeal.domain.*
import com.kxsxlxv.jetmeal.ui.theme.RingColors
import com.kxsxlxv.jetmeal.ui.theme.nutritionColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Initial expansion is presentation state only; meal assignment remains a domain rule. */
internal fun initialExpandedMeal(
    date: LocalDate,
    entries: List<DiaryEntry>,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): MealPeriod = if (date == now.atZone(zone).toLocalDate()) {
    MealPeriods.resolve(now, zone)
} else {
    entries.maxByOrNull { it.consumedAt }?.meal ?: MealPeriod.Morning
}

@Composable
fun DayContent(
    date: LocalDate,
    entries: List<DiaryEntry>,
    targets: Targets?,
    week: WeekState?,
    onAdd: (MealPeriod) -> Unit,
    onEdit: (DiaryEntry) -> Unit,
    onTargets: () -> Unit,
) {
    val total = entries.fold(Nutrition.Zero) { sum, entry -> sum + entry.nutrition }
    val effective = week?.days?.firstOrNull { it.date == date }?.target ?: targets?.calories
    // Wait for a historical day's first real records before choosing its initial expansion.
    var expandedName by rememberSaveable(date.toString()) {
        mutableStateOf(initialExpandedMeal(date, entries).name)
    }
    var hasChosen by rememberSaveable(date.toString()) { mutableStateOf(false) }
    LaunchedEffect(date, entries) {
        if (!hasChosen && entries.isNotEmpty() && date != LocalDate.now()) {
            expandedName = initialExpandedMeal(date, entries).name
            hasChosen = true
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("day-diary"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "nutrition") {
            if (targets == null) DayTargetsPrompt(onTargets)
            else DailyRings(total, targets, effective ?: targets.calories)
        }
        item {
            Text("Приёмы пищи", style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp).semantics { heading() })
        }
        MealPeriod.entries.forEach { meal ->
            item(key = "meal-${meal.name}") {
                val foods = entries.filter { it.meal == meal }.sortedBy { it.consumedAt }
                MealSection(meal, foods, expandedName == meal.name, {
                    expandedName = meal.name
                    hasChosen = true
                }, { onAdd(meal) }, onEdit)
            }
        }
    }
}

@Composable
private fun DayTargetsPrompt(onTargets: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.shapes.extraLarge).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ваши цели питания", style = MaterialTheme.typography.headlineSmallEmphasized)
        Text("Укажите свою норму калорий и БЖУ, чтобы видеть прогресс и недельный баланс.")
        Button(onClick = onTargets, shapes = ButtonDefaults.shapes()) { Text("Задать цели") }
    }
}

@Composable
private fun DailyRings(total: Nutrition, targets: Targets, effective: Double) {
    val rings = nutritionColors()
    val description = buildString {
        append(nutritionDescription("Калории", total.calories, effective, "ккал"))
        append(". ")
        append(nutritionDescription("Белки", total.protein, targets.protein, "г"))
        append(". ")
        append(nutritionDescription("Жиры", total.fat, targets.fat, "г"))
        append(". ")
        append(nutritionDescription("Углеводы", total.carbs, targets.carbs, "г"))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        ConcentricNutritionRings(
            total = total,
            targets = targets,
            effectiveCalories = effective,
            colors = rings,
            modifier = Modifier.size(292.dp),
        )
    }
}

private data class ConcentricRingVisual(
    val progress: Float,
    val label: String,
    val radiusDp: Float,
    val strokeDp: Float,
    val labelSp: Float,
    val colors: RingColors,
)

@Composable
private fun ConcentricNutritionRings(
    total: Nutrition,
    targets: Targets,
    effectiveCalories: Double,
    colors: com.kxsxlxv.jetmeal.ui.theme.NutritionColors,
    modifier: Modifier = Modifier,
) {
    val calories by animateFloatAsState(
        targetValue = ringFraction(total.calories, effectiveCalories),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "Калории",
    )
    val protein by animateFloatAsState(
        targetValue = ringFraction(total.protein, targets.protein),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "Белки",
    )
    val fat by animateFloatAsState(
        targetValue = ringFraction(total.fat, targets.fat),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "Жиры",
    )
    val carbs by animateFloatAsState(
        targetValue = ringFraction(total.carbs, targets.carbs),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "Углеводы",
    )

    val visuals = listOf(
        ConcentricRingVisual(
            calories,
            compactRingLabel(null, total.calories, effectiveCalories, "ккал"),
            radiusDp = 135f,
            strokeDp = 14f,
            labelSp = 10.5f,
            colors = colors.calories,
        ),
        ConcentricRingVisual(
            protein,
            compactRingLabel("Б", total.protein, targets.protein, "г"),
            radiusDp = 105f,
            strokeDp = 13f,
            labelSp = 10f,
            colors = colors.protein,
        ),
        ConcentricRingVisual(
            fat,
            compactRingLabel("Ж", total.fat, targets.fat, "г"),
            radiusDp = 77f,
            strokeDp = 12f,
            labelSp = 9.5f,
            colors = colors.fat,
        ),
        ConcentricRingVisual(
            carbs,
            compactRingLabel("У", total.carbs, targets.carbs, "г"),
            radiusDp = 51f,
            strokeDp = 11f,
            labelSp = 9f,
            colors = colors.carbs,
        ),
    )

    val density = LocalDensity.current
    val paints = visuals.map { visual ->
        remember(visual.colors.onContainer, visual.labelSp, density.density, density.fontScale) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = visual.colors.onContainer.toArgb()
                textSize = with(density) { visual.labelSp.sp.toPx() }
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        }
    }
    val textPath = remember { Path() }
    val textBounds = remember { RectF() }
    val overflowColor = MaterialTheme.colorScheme.error

    Canvas(modifier) {
        visuals.forEachIndexed { index, visual ->
            val radius = visual.radiusDp.dp.toPx()
            val stroke = visual.strokeDp.dp.toPx()
            val origin = Offset(center.x - radius, center.y - radius)
            val diameter = Size(radius * 2, radius * 2)
            val progress = visual.progress.coerceIn(0f, 1f)
            val overflow = (visual.progress - 1f).coerceIn(0f, 1f)

            drawArc(
                color = visual.colors.container,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = origin,
                size = diameter,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )

            rotate(-90f, center) {
                when {
                    progress >= 1f -> drawArc(
                        brush = Brush.sweepGradient(
                            0f to visual.colors.start,
                            .5f to visual.colors.end,
                            1f to visual.colors.start,
                            center = center,
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = origin,
                        size = diameter,
                        style = Stroke(stroke, cap = StrokeCap.Butt),
                    )
                    progress > 0f -> {
                        drawArc(
                            brush = Brush.sweepGradient(
                                0f to visual.colors.start,
                                progress.coerceAtLeast(.001f) to visual.colors.end,
                                1f to visual.colors.end,
                                center = center,
                            ),
                            startAngle = 0f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = origin,
                            size = diameter,
                            style = Stroke(stroke, cap = StrokeCap.Butt),
                        )
                        drawCircle(
                            color = visual.colors.start,
                            radius = stroke / 2,
                            center = Offset(center.x + radius, center.y),
                        )
                        val angle = progress * 2 * PI
                        drawCircle(
                            color = visual.colors.end,
                            radius = stroke / 2,
                            center = Offset(
                                center.x + radius * cos(angle).toFloat(),
                                center.y + radius * sin(angle).toFloat(),
                            ),
                        )
                    }
                }
            }

            if (overflow > 0f) {
                drawArc(
                    color = overflowColor,
                    startAngle = -90f,
                    sweepAngle = 360f * overflow,
                    useCenter = false,
                    topLeft = origin,
                    size = diameter,
                    style = Stroke(2.25.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // The label belongs to the ring itself: it sits in the radial gap
            // immediately inside the track instead of creating a second legend/card.
            val labelRadius = radius - stroke / 2 - 6.dp.toPx()
            val labelSweep = if (index == visuals.lastIndex) 172f else 152f
            val labelStart = 270f - labelSweep / 2f
            textBounds.set(
                center.x - labelRadius,
                center.y - labelRadius,
                center.x + labelRadius,
                center.y + labelRadius,
            )
            textPath.rewind()
            textPath.addArc(textBounds, labelStart, labelSweep)

            val paint = paints[index]
            val originalTextSize = paint.textSize
            val available = (labelRadius * PI * labelSweep / 180f).toFloat() * .92f
            val measured = paint.measureText(visual.label)
            if (measured > available && measured > 0f) {
                paint.textSize = originalTextSize * (available / measured)
            }
            val fittedWidth = paint.measureText(visual.label)
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawTextOnPath(
                    visual.label,
                    textPath,
                    (available - fittedWidth) / 2f,
                    0f,
                    paint,
                )
            }
            paint.textSize = originalTextSize
        }
    }
}

private fun ringFraction(actual: Double, target: Double): Float = when {
    target > 0.0 -> (actual / target).toFloat()
    actual > 0.0 -> 2f
    else -> 0f
}

private fun compactRingLabel(prefix: String?, actual: Double, target: Double, unit: String): String {
    val value = if (unit == "ккал") number(actual) else number(actual, 1)
    val goal = if (unit == "ккал") number(target) else number(target, 1)
    val status = if (actual > target) " ↑" else ""
    return buildString {
        if (prefix != null) append(prefix).append(' ')
        append(value).append(" / ").append(goal).append(' ').append(unit).append(status)
    }
}

private fun nutritionDescription(name: String, actual: Double, target: Double, unit: String): String {
    val value = if (unit == "ккал") number(actual) else number(actual, 1)
    val goal = if (unit == "ккал") number(target) else number(target, 1)
    return buildString {
        append(name).append(": ").append(value).append(" из ").append(goal).append(' ').append(unit)
        when {
            target <= 0.0 && actual > 0.0 -> append(", норма исчерпана")
            target <= 0.0 -> append(", цель равна нулю")
            actual > target -> {
                val delta = actual - target
                append(", превышение ")
                append(if (unit == "ккал") number(delta) else number(delta, 1))
                append(' ').append(unit)
            }
        }
    }
}

@Composable
private fun MealSection(
    meal: MealPeriod,
    entries: List<DiaryEntry>,
    expanded: Boolean,
    onExpand: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (DiaryEntry) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val calories = entries.sumOf { it.nutrition.calories }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntSize>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            onClick = onExpand,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" },
            shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
            colors = ListItemDefaults.colors(containerColor = if (expanded) colors.secondaryContainer else colors.surfaceContainerLow),
            supportingContent = { Text("${number(calories)} ккал · " + if (entries.isEmpty()) "нет записей"
                else "${entries.size} ${entryCountWord(entries.size)}") },
            leadingContent = { SymbolIcon(if (expanded) JetMealSymbol.ExpandLess else JetMealSymbol.ExpandMore, null) },
            trailingContent = {
                FilledTonalIconButton(onClick = onAdd, shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    SymbolIcon(JetMealSymbol.Add, "Добавить еду: ${meal.label()}")
                }
            },
        ) { Text(meal.label(), style = MaterialTheme.typography.titleMediumEmphasized) }
        AnimatedVisibility(expanded,
            enter = expandVertically(animationSpec = spatial, expandFrom = Alignment.Top) + fadeIn(effects),
            exit = shrinkVertically(animationSpec = spatial, shrinkTowards = Alignment.Top) + fadeOut(effects)) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp).animateContentSize(animationSpec = spatial),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                if (entries.isEmpty()) {
                    Text("Добавьте первое блюдо", style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 14.dp))
                } else entries.forEachIndexed { index, entry -> key(entry.id) {
                    SegmentedListItem(
                        onClick = { onEdit(entry) },
                        shapes = ListItemDefaults.segmentedShapes(index, entries.size),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ListItemDefaults.segmentedColors(containerColor = colors.surfaceContainer),
                        overlineContent = { Text("${number(entry.quantity, 1)} ${unitLabel(entry.unit)}" +
                            if (entry.estimated) " · оценка" else "") },
                        supportingContent = {
                            Text("Б ${number(entry.nutrition.protein, 1)} · Ж ${number(entry.nutrition.fat, 1)} · У ${number(entry.nutrition.carbs, 1)} г",
                                style = MaterialTheme.typography.bodySmall)
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(number(entry.nutrition.calories), style = MaterialTheme.typography.titleMediumEmphasized)
                                Text("ккал", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                    ) { Text(entry.name + (entry.brand?.let { " · $it" } ?: "")) }
                } }
            }
        }
    }
}

private fun entryCountWord(count: Int): String = when {
    count % 100 in 11..14 -> "записей"
    count % 10 == 1 -> "запись"
    count % 10 in 2..4 -> "записи"
    else -> "записей"
}
