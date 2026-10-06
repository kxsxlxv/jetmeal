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
    val colors = MaterialTheme.colorScheme
    val rings = nutritionColors()
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val over = total.calories > effective
    // At a ±100% adjustment limit, the domain can legitimately exhaust the allowance.
    // A zero allowance has no percentage denominator; show its explicit state instead.
    val hasAllowance = effective > 0.0
    val fraction = if (hasAllowance) (total.calories / effective).toFloat()
        else if (total.calories > 0.0) 2f else 0f
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.sizeIn(maxWidth = 300.dp).fillMaxWidth().height(258.dp), contentAlignment = Alignment.Center) {
            GradientNutritionRing(
                fraction, "КАЛОРИИ · ${number(effective)} ККАЛ", rings.calories.start, rings.calories.end,
                Modifier.size(252.dp), strokeDp = 23f, curvedTextSp = 12f,
                overflowColor = colors.error,
            )
            Column(Modifier.widthIn(max = 166.dp).semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally) {
                val numberStyle = when {
                    number(total.calories).length > 5 -> MaterialTheme.typography.headlineMediumEmphasized
                    scale > 1.3f -> MaterialTheme.typography.headlineLargeEmphasized
                    else -> MaterialTheme.typography.displayMediumEmphasized
                }
                Text(number(total.calories), style = numberStyle,
                    color = if (over) colors.error else colors.onSurface, maxLines = 1)
                Text("ккал съедено", style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
                Text(if (hasAllowance) "${number(fraction * 100.0)}% нормы"
                    else if (over) "Норма исчерпана" else "0 ккал доступно",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (over) colors.error else colors.primary,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
        Text(
            when {
                over -> "+${number(total.calories - effective)} ккал сверх нормы"
                !hasAllowance -> "На этот день доступно 0 ккал"
                total.calories == effective -> "Дневная норма достигнута"
                else -> "Осталось ${number(effective - total.calories)} ккал"
            },
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = if (over) colors.error else colors.primary,
        )
        Text("Норма на этот день: ${number(effective)} ккал", style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp, bottom = 16.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MacroRing("Белки", total.protein, targets.protein, rings.protein, scale)
            MacroRing("Жиры", total.fat, targets.fat, rings.fat, scale)
            MacroRing("Углеводы", total.carbs, targets.carbs, rings.carbs, scale)
        }
    }
}

@Composable
private fun MacroRing(label: String, actual: Double, target: Double, colors: RingColors, fontScale: Float) {
    Column(
        Modifier.width((98 + (fontScale - 1) * 74).dp).background(colors.container,
            RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp).semantics(mergeDescendants = true) {
                contentDescription = "$label: ${number(actual, 1)} из ${number(target, 1)} граммов"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size((84 * fontScale).dp), contentAlignment = Alignment.Center) {
            GradientNutritionRing(if (target > 0) (actual / target).toFloat() else 0f,
                label, colors.start, colors.end, Modifier.fillMaxSize(), strokeDp = 7f, curvedTextSp = 11f,
                overflowColor = MaterialTheme.colorScheme.error)
            Text(number(actual, 1), style = MaterialTheme.typography.titleMediumEmphasized,
                color = colors.onContainer,
                modifier = Modifier.clearAndSetSemantics {})
        }
        Text(if (target > 0) "из ${number(target)} г" else "цель не задана",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {})
    }
}

/** Data visualization, not an icon: the platform draws type following the ring's path. */
@Composable
private fun GradientNutritionRing(
    fraction: Float,
    label: String,
    start: Color,
    end: Color,
    modifier: Modifier,
    strokeDp: Float,
    curvedTextSp: Float,
    overflowColor: Color,
) {
    val progress by animateFloatAsState(fraction.coerceIn(0f, 1f),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Заполнение кольца")
    val overflow by animateFloatAsState((fraction - 1f).coerceIn(0f, 1f),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Превышение нормы")
    val labelColor = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val density = LocalDensity.current
    val paint = remember(labelColor, density.density, density.fontScale, curvedTextSp) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textSize = with(density) { curvedTextSp.sp.toPx() }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }
    val textPath = remember { Path() }
    val textBounds = remember { RectF() }
    val textWidth = remember(label, paint) { paint.measureText(label) }
    Canvas(modifier) {
        val stroke = strokeDp.dp.toPx()
        val radius = size.minDimension / 2 - max(20.dp.toPx(), paint.textSize + stroke / 2)
        val origin = Offset(center.x - radius, center.y - radius)
        val diameter = Size(radius * 2, radius * 2)
        drawArc(track, -90f, 360f, false, origin, diameter, style = Stroke(stroke, cap = StrokeCap.Round))
        // Rotating the sweep keeps its gradient start aligned with the twelve-o'clock start.
        rotate(-90f, center) {
            if (progress >= 1f) {
                // A closed ring has no endpoint: return smoothly to its initial shade.
                drawArc(Brush.sweepGradient(0f to start, .5f to end, 1f to start, center = center),
                    0f, 360f, false, origin, diameter, style = Stroke(stroke, cap = StrokeCap.Butt))
            } else if (progress > 0f) {
                drawArc(Brush.sweepGradient(0f to start, progress.coerceAtLeast(.001f) to end,
                    1f to end, center = center), 0f, 360f * progress,
                    false, origin, diameter, style = Stroke(stroke, cap = StrokeCap.Butt))
                // Sweep shaders wrap at zero; solid caps avoid a split dark/light start cap.
                drawCircle(start, stroke / 2, Offset(center.x + radius, center.y))
                val angle = progress * 2 * PI
                drawCircle(end, stroke / 2, Offset(center.x + radius * cos(angle).toFloat(),
                    center.y + radius * sin(angle).toFloat()))
            }
        }
        if (overflow > 0f) {
            val compact = strokeDp < 10f || density.fontScale >= 1.3f
            val overflowRadius = if (compact) radius else radius - stroke / 2 - 7.dp.toPx()
            drawArc(overflowColor, -90f, 360f * overflow, false,
                Offset(center.x - overflowRadius, center.y - overflowRadius),
                Size(overflowRadius * 2, overflowRadius * 2),
                style = Stroke((if (compact) 2 else 3).dp.toPx(), cap = StrokeCap.Round))
        }
        val textRadius = radius + stroke / 2 + 5.dp.toPx()
        textBounds.set(center.x - textRadius, center.y - textRadius,
            center.x + textRadius, center.y + textRadius)
        textPath.rewind()
        textPath.addArc(textBounds, 200f, 140f)
        val length = (textRadius * PI * 140 / 180).toFloat()
        // Keep the user's scaled type size. Curved decoration is omitted if it cannot fit;
        // all values/labels have normal, scalable text and accessibility equivalents.
        if (textWidth < length) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawTextOnPath(label, textPath,
                    (length - textWidth) / 2, 0f, paint)
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
