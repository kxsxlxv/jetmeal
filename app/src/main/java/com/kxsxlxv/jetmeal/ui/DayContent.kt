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
    val palette = nutritionColors()
    Row(
        Modifier
            .fillMaxWidth()
            .height(208.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            CalorieHealthDial(
                actual = total.calories,
                target = effective,
                colors = palette.calories,
            )
        }
        Column(
            Modifier
                .weight(1.1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MacroHealthCard(
                label = "Белки",
                actual = total.protein,
                target = targets.protein,
                colors = palette.protein,
                symbol = JetMealSymbol.Protein,
                modifier = Modifier.weight(1f),
            )
            MacroHealthCard(
                label = "Жиры",
                actual = total.fat,
                target = targets.fat,
                colors = palette.fat,
                symbol = JetMealSymbol.Fat,
                modifier = Modifier.weight(1f),
            )
            MacroHealthCard(
                label = "Углеводы",
                actual = total.carbs,
                target = targets.carbs,
                colors = palette.carbs,
                symbol = JetMealSymbol.Carbs,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CalorieHealthDial(
    actual: Double,
    target: Double,
    colors: RingColors,
) {
    val fraction = ringFraction(actual, target)
    val progress by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "Прогресс калорий",
    )
    val overflow by animateFloatAsState(
        targetValue = (fraction - 1f).coerceIn(0f, 1f),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "Превышение калорий",
    )
    val over = target >= 0.0 && actual > target
    val percent = if (target > 0.0) number(actual / target * 100.0) + "%" else "—"
    val delta = actual - target
    val deltaText = when {
        target <= 0.0 && actual <= 0.0 -> "0"
        delta > 0.0 -> "+${number(delta)}"
        else -> "−${number(kotlin.math.abs(delta))}"
    }
    val density = LocalDensity.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val accent = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val topPaint = remember(onSurface, density.density, density.fontScale) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = onSurface.toArgb()
            textSize = with(density) { 11.sp.toPx() }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }
    val bottomPaint = remember(accent, density.density, density.fontScale) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent.toArgb()
            textSize = with(density) { 10.5.sp.toPx() }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }
    val textPath = remember { Path() }
    val textBounds = remember { RectF() }

    Box(
        Modifier
            .size(160.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = nutritionDescription("Калории", actual, target, "ккал")
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
            val stroke = 25.dp.toPx()
            val radius = size.minDimension / 2f - stroke / 2f - 3.dp.toPx()
            val origin = Offset(center.x - radius, center.y - radius)
            val diameter = Size(radius * 2f, radius * 2f)

            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = origin,
                size = diameter,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to colors.start,
                        .65f to colors.end,
                        1f to colors.end,
                        center = center,
                    ),
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = origin,
                    size = diameter,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            if (overflow > 0f) {
                val overflowRadius = radius + stroke / 2f + 2.dp.toPx()
                drawArc(
                    color = MaterialTheme.colorScheme.error,
                    startAngle = -90f,
                    sweepAngle = 360f * overflow,
                    useCenter = false,
                    topLeft = Offset(center.x - overflowRadius, center.y - overflowRadius),
                    size = Size(overflowRadius * 2f, overflowRadius * 2f),
                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            val textRadius = radius - stroke / 2f - 8.dp.toPx()
            textBounds.set(
                center.x - textRadius,
                center.y - textRadius,
                center.x + textRadius,
                center.y + textRadius,
            )

            val topText = "Калории"
            val topSweep = 142f
            textPath.rewind()
            textPath.addArc(textBounds, 199f, topSweep)
            val topLength = (textRadius * Math.PI * topSweep / 180.0).toFloat()
            val topWidth = topPaint.measureText(topText)
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawTextOnPath(
                    topText,
                    textPath,
                    ((topLength - topWidth) / 2f).coerceAtLeast(0f),
                    0f,
                    topPaint,
                )
            }

            val bottomText = "${number(actual)} / ${number(target)} ккал"
            val bottomSweep = -142f
            textPath.rewind()
            textPath.addArc(textBounds, 161f, bottomSweep)
            val bottomLength = (textRadius * Math.PI * kotlin.math.abs(bottomSweep) / 180.0).toFloat()
            val originalBottomSize = bottomPaint.textSize
            val bottomWidth = bottomPaint.measureText(bottomText)
            if (bottomWidth > bottomLength * .94f && bottomWidth > 0f) {
                bottomPaint.textSize = originalBottomSize * (bottomLength * .94f / bottomWidth)
            }
            val fittedBottomWidth = bottomPaint.measureText(bottomText)
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawTextOnPath(
                    bottomText,
                    textPath,
                    ((bottomLength - fittedBottomWidth) / 2f).coerceAtLeast(0f),
                    0f,
                    bottomPaint,
                )
            }
            bottomPaint.textSize = originalBottomSize
        }

        Text(
            percent,
            style = MaterialTheme.typography.displaySmallEmphasized,
            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clearAndSetSemantics {},
        )

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 12.dp)
                .size(46.dp)
                .background(
                    if (over) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                    CircleShape,
                )
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(
                deltaText,
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = if (over) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MacroHealthCard(
    label: String,
    actual: Double,
    target: Double,
    colors: RingColors,
    symbol: JetMealSymbol,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = nutritionDescription(label, actual, target, "г")
            },
        shape = RoundedCornerShape(24.dp),
        color = colors.container,
        contentColor = colors.onContainer,
    ) {
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(5.dp)
                    .background(colors.start)
                    .clearAndSetSemantics {},
            )
            Box(
                Modifier
                    .padding(start = 7.dp)
                    .size(43.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = .72f),
                        RoundedCornerShape(16.dp),
                    )
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                SymbolIcon(symbol, null, Modifier.size(27.dp))
            }
            Column(
                Modifier
                    .padding(start = 8.dp, end = 8.dp)
                    .clearAndSetSemantics {},
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onContainer,
                    maxLines = 1,
                )
                Text(
                    "${number(actual, 1)} / ${number(target, 1)} г",
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = colors.onContainer,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun ringFraction(actual: Double, target: Double): Float = when {
    target > 0.0 -> (actual / target).toFloat()
    actual > 0.0 -> 2f
    else -> 0f
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
