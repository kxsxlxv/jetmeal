@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.kxsxlxv.jetmeal.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
            else DailyRings(date, total, targets, effective ?: targets.calories)
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

internal data class HealthProgressFrame(
    val calories: Float,
    val protein: Float,
    val fat: Float,
    val carbs: Float,
) {
    companion object {
        val Zero = HealthProgressFrame(0f, 0f, 0f, 0f)
    }
}

private fun HealthProgressFrame.interpolate(
    target: HealthProgressFrame,
    fraction: Float,
): HealthProgressFrame {
    val t = fraction.coerceIn(0f, 1f)
    if (t == 0f) return this
    if (t == 1f) return target
    fun lerp(start: Float, end: Float): Float = start + (end - start) * t
    return HealthProgressFrame(
        calories = lerp(calories, target.calories),
        protein = lerp(protein, target.protein),
        fat = lerp(fat, target.fat),
        carbs = lerp(carbs, target.carbs),
    )
}

@Composable
internal fun animatedHealthProgressFrame(
    animationKey: Any,
    target: HealthProgressFrame,
): State<HealthProgressFrame> {
    val clock = remember(animationKey) { Animatable(0f) }
    var start by remember(animationKey) { mutableStateOf(HealthProgressFrame.Zero) }
    var end by remember(animationKey) { mutableStateOf(target) }
    // Observe the clock only in the drawing phase. Reading it here recomposed all
    // four indicators (including BoxWithConstraints) for every animation frame.
    val frame = remember(animationKey) { derivedStateOf { start.interpolate(end, clock.value) } }

    LaunchedEffect(animationKey, target) {
        val current = frame.value
        start = current
        end = target
        clock.snapTo(0f)
        clock.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 700,
                easing = LinearEasing,
            ),
        )
    }

    return frame
}

@Composable
private fun DailyRings(animationKey: LocalDate, total: Nutrition, targets: Targets, effective: Double) {
    val palette = nutritionColors()
    val targetProgress = HealthProgressFrame(
        calories = ringFraction(total.calories, effective).coerceIn(0f, 2f),
        protein = ringFraction(total.protein, targets.protein).coerceIn(0f, 1f),
        fat = ringFraction(total.fat, targets.fat).coerceIn(0f, 1f),
        carbs = ringFraction(total.carbs, targets.carbs).coerceIn(0f, 1f),
    )
    val displayed = animatedHealthProgressFrame(animationKey, targetProgress)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 6.dp
        val dialSize = ((maxWidth - gap) / 2.08f).coerceAtMost(168.dp)

        Row(
            Modifier
                .fillMaxWidth()
                .height(dialSize),
            horizontalArrangement = Arrangement.spacedBy(gap),
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
                    displayedFraction = { displayed.value.calories },
                    colors = palette.calories,
                    modifier = Modifier.size(dialSize),
                )
            }
            Column(
                Modifier
                    .weight(1.08f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                MacroHealthCard(
                    label = "Белки",
                    actual = total.protein,
                    target = targets.protein,
                    progress = { displayed.value.protein },
                    colors = palette.protein,
                    symbol = JetMealSymbol.Protein,
                    modifier = Modifier.weight(1f),
                )
                MacroHealthCard(
                    label = "Жиры",
                    actual = total.fat,
                    target = targets.fat,
                    progress = { displayed.value.fat },
                    colors = palette.fat,
                    symbol = JetMealSymbol.Fat,
                    modifier = Modifier.weight(1f),
                )
                MacroHealthCard(
                    label = "Углеводы",
                    actual = total.carbs,
                    target = targets.carbs,
                    progress = { displayed.value.carbs },
                    colors = palette.carbs,
                    symbol = JetMealSymbol.Carbs,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CalorieHealthDial(
    actual: Double,
    target: Double,
    displayedFraction: () -> Float,
    colors: RingColors,
    modifier: Modifier = Modifier,
) {
    val percent = if (target > 0.0) number(actual / target * 100.0) + "%" else "—"
    val density = LocalDensity.current
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val errorColor = MaterialTheme.colorScheme.error
    val badgeTextColor = Color(0xFF102018)
    val badgeOverlayColor = Color.Black.copy(alpha = .14f)
    val warningColor = Color(0xFFFFC34D)
    val deltaText = signed(actual - target)
    val bottomPaint = remember(labelColor, density.density, density.fontScale) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textSize = with(density) { 10.5.sp.toPx() }
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }
    val ringRenderer = remember { CalorieRingRenderer() }
    val textPath = remember { Path() }
    val textBounds = remember { RectF() }
    val badgeTextPath = remember { Path() }
    val badgeTextBounds = remember { RectF() }
    val badgeTextPaint = remember(
        density.density,
        density.fontScale,
        badgeTextColor,
    ) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = with(density) { 9.sp.toPx() }
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    Box(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = nutritionDescription("Калории", actual, target, "ккал")
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
            val progress = displayedFraction()
            val lapProgress = splitRingProgress(progress)
            val firstLap = lapProgress.firstLap
            val overflowLap = lapProgress.overflowLap
            val stroke = 23.dp.toPx()
            val radius = size.minDimension / 2f - stroke / 2f - 3.dp.toPx()
            val origin = Offset(center.x - radius, center.y - radius)
            val diameter = Size(radius * 2f, radius * 2f)

            drawIntoCanvas { canvas ->
                ringRenderer.draw(
                    canvas = canvas.nativeCanvas,
                    centerX = center.x,
                    centerY = center.y,
                    radius = radius,
                    stroke = stroke,
                    progress = progress,
                    track = track.toArgb(),
                    startColor = colors.start.toArgb(),
                    endColor = colors.end.toArgb(),
                    warningColor = warningColor.toArgb(),
                    errorColor = errorColor.toArgb(),
                )
            }
            // The delta is a curved sub-segment of the ring itself, not a rotated pill.
            // Its visible front edge remains inside the parent round cap; longer values grow
            // backwards along the arc. Text follows the same curvature and reverses on the
            // lower half so it never becomes upside-down.
            val activeLap = if (overflowLap > 0f) overflowLap else firstLap
            if (activeLap > .001f) {
                val endAngle = -90f + 360f * activeLap
                val badgeHeight = minOf(14.dp.toPx(), stroke - 4.dp.toPx())
                val horizontalPadding = 4.dp.toPx()
                val frontInset = (stroke - badgeHeight) / 2f
                badgeTextPaint.color = badgeTextColor.toArgb()
                val originalBadgeSize = badgeTextPaint.textSize
                val textWidthLimit = curvedBadgeTextWidthLimit(progress, radius, badgeHeight, horizontalPadding)
                val requestedTextWidth = badgeTextPaint.measureText(deltaText)
                if (requestedTextWidth > textWidthLimit && requestedTextWidth > 0f) {
                    badgeTextPaint.textSize *= textWidthLimit / requestedTextWidth
                }
                val badgeTextWidth = badgeTextPaint.measureText(deltaText)
                val geometry = curvedBadgeGeometry(
                    endAngle = endAngle,
                    radius = radius,
                    badgeHeight = badgeHeight,
                    parentCapRadius = stroke / 2f,
                    textWidth = badgeTextWidth,
                    horizontalPadding = horizontalPadding,
                    frontInset = frontInset,
                )
                drawArc(
                    color = badgeOverlayColor,
                    startAngle = geometry.badgeStartAngle,
                    sweepAngle = geometry.badgeSweepAngle,
                    useCenter = false,
                    topLeft = origin,
                    size = diameter,
                    style = Stroke(badgeHeight, cap = StrokeCap.Round),
                )

                badgeTextBounds.set(
                    center.x - radius,
                    center.y - radius,
                    center.x + radius,
                    center.y + radius,
                )
                badgeTextPath.rewind()
                badgeTextPath.addArc(
                    badgeTextBounds,
                    geometry.textStartAngle,
                    geometry.textSweepAngle,
                )
                val metrics = badgeTextPaint.fontMetrics
                val verticalOffset = -(metrics.ascent + metrics.descent) / 2f
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawTextOnPath(
                        deltaText,
                        badgeTextPath,
                        0f,
                        verticalOffset,
                        badgeTextPaint,
                    )
                }
                badgeTextPaint.textSize = originalBadgeSize
            }

            val textRadius = radius - stroke / 2f - 8.dp.toPx()
            textBounds.set(
                center.x - textRadius,
                center.y - textRadius,
                center.x + textRadius,
                center.y + textRadius,
            )

            val bottomText = "${number(actual)} / ${number(target)} ккал"
            val bottomSweep = -150f
            textPath.rewind()
            textPath.addArc(textBounds, 165f, bottomSweep)
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
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun MacroHealthCard(
    label: String,
    actual: Double,
    target: Double,
    progress: () -> Float,
    colors: RingColors,
    symbol: JetMealSymbol,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = nutritionDescription(label, actual, target, "г")
            },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = onSurface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val reveal = progress().coerceIn(0f, 1f)
                        if (reveal > 0f) {
                            clipRect(right = size.width * reveal) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0f to colors.container,
                                        .42f to colors.start,
                                        1f to colors.end,
                                        startX = 0f,
                                        endX = size.width,
                                    ),
                                )
                            }
                        }
                    }
                    .clearAndSetSemantics {},
            )
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(35.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = .72f),
                            RoundedCornerShape(13.dp),
                        )
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) {
                    SymbolIcon(symbol, null, Modifier.size(23.dp))
                }
                Column(
                    Modifier
                        .padding(start = 7.dp)
                        .clearAndSetSemantics {},
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurface,
                        maxLines = 1,
                    )
                    Text(
                        "${number(actual, 1)} / ${number(target, 1)} г",
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        color = onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

internal data class RingLapProgress(
    val firstLap: Float,
    val overflowLap: Float,
)

internal fun splitRingProgress(progress: Float): RingLapProgress {
    val bounded = progress.coerceIn(0f, 2f)
    return RingLapProgress(
        firstLap = bounded.coerceAtMost(1f),
        overflowLap = (bounded - 1f).coerceIn(0f, 1f),
    )
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
