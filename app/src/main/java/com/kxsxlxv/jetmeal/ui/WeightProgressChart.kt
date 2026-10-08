package com.kxsxlxv.jetmeal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.domain.WeightProgressData
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Equal visual spacing between distinct weighing days. A future goal gets a separate projection area. */
internal enum class WeightChartMode { Measurements, Goal }

private data class ChartRange(val bottom: Double, val top: Double) {
    val middle: Double get() = (bottom + top) / 2.0
    fun y(weight: Double, topPx: Float, bottomPx: Float): Float =
        bottomPx - ((weight - bottom) / (top - bottom)).toFloat() * (bottomPx - topPx)
}

private fun chartRange(data: WeightProgressData, mode: WeightChartMode): ChartRange {
    val days = data.days
    val values = days.map { it.kilograms }.toMutableList()
    val goal = data.goal
    if (goal != null && days.isNotEmpty()) {
        val from = maxOf(goal.startDate, days.first().date)
        val until = if (mode == WeightChartMode.Goal) goal.targetDate
            else minOf(goal.targetDate, maxOf(data.today,days.last().date).plusDays(7))
        if (from <= until) {
            values += goal.expected(from)
            values += goal.expected(until)
            values += days.filter { it.date in from..until }.map { goal.expected(it.date) }
        }
    }
    val low = values.minOrNull() ?: 0.0
    val high = values.maxOrNull() ?: 1.0
    val span = (high - low).coerceAtLeast(.6)
    val padding = (span * .16).coerceAtLeast(.25)
    return ChartRange(low - padding, high + padding)
}

private fun shortDate(day: LocalDate) =
    day.format(DateTimeFormatter.ofPattern("d MMM", RussianLocale))

@Composable
internal fun WeightProgressChart(data: WeightProgressData, modifier: Modifier = Modifier) {
    if (data.days.isEmpty()) return
    var mode by remember { mutableStateOf(WeightChartMode.Measurements) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val days = data.days
    val selected = days.firstOrNull { it.date == selectedDate } ?: days.last()
    val actualColor = MaterialTheme.colorScheme.primary
    val planColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val range = remember(data, mode) { chartRange(data, mode) }
    val projectionEnd = data.goal?.let { goal ->
        if (mode == WeightChartMode.Goal) goal.targetDate
        else minOf(goal.targetDate, maxOf(data.today,days.last().date).plusDays(7))
    }
    val goalFuture = projectionEnd != null && projectionEnd > days.last().date
    val selectedSurface = MaterialTheme.colorScheme.surface

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Динамика веса · кг", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilterChip(selected = mode == WeightChartMode.Measurements,
                onClick = { mode = WeightChartMode.Measurements },
                label = { Text("Измерения") })
            if (data.goal != null) FilterChip(selected = mode == WeightChartMode.Goal,
                onClick = { mode = WeightChartMode.Goal },
                label = { Text("До цели") })
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChartLegend(color = actualColor, dashed = false,
                label = if (days.all { it.source == "picooc" }) "PICOOC · факт" else "Факт")
            if (data.goal != null) ChartLegend(color = planColor, dashed = true, label = "План")
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(44.dp).height(218.dp).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End) {
                Text(number(range.top,1),style=MaterialTheme.typography.labelSmall,color=labelColor)
                Text(number(range.middle,1),style=MaterialTheme.typography.labelSmall,color=labelColor)
                Text(number(range.bottom,1),style=MaterialTheme.typography.labelSmall,color=labelColor)
            }
            val chartLabel = buildString {
                append("График веса; зелёная линия — измерения, промежутки между ними равны.")
                if (data.goal != null) append(" Пунктир — план по календарным датам.")
                append(" Последнее измерение: ${number(days.last().kilograms,1)} кг, ${shortDate(days.last().date)}.")
            }
            Canvas(
                Modifier.weight(1f).height(218.dp)
                    .semantics {
                        contentDescription = chartLabel
                        onClick(label = "Следующее измерение") {
                            val index = days.indexOfFirst { it.date == selected.date }
                            selectedDate = days[(index + 1) % days.size].date
                            true
                        }
                    }
                    .pointerInput(days, goalFuture, mode) {
                        detectTapGestures { tap ->
                            val left = 12.dp.toPx()
                            val right = size.width - 12.dp.toPx()
                            val actualEnd = if (goalFuture) right *
                                (if (mode == WeightChartMode.Goal) .69f else .77f) else right
                            val count = days.size
                            val nearest = days.indices.minByOrNull { i ->
                                val x = if (count == 1) (left + actualEnd) / 2f
                                    else left + (actualEnd - left) * i / (count - 1)
                                abs(tap.x - x)
                            }
                            nearest?.let { selectedDate = days[it].date }
                        }
                    }
            ) {
                val left = 12.dp.toPx()
                val right = size.width - 12.dp.toPx()
                val top = 12.dp.toPx()
                val bottom = size.height - 12.dp.toPx()
                val actualEnd = if (goalFuture) right *
                    (if (mode == WeightChartMode.Goal) .69f else .77f) else right

                fun xIndex(index: Int): Float =
                    if (days.size == 1) (left + actualEnd) / 2f
                    else left + (actualEnd - left) * index / (days.size - 1)

                // Measured dates occupy evenly spaced slots, but the plan is always
                // calculated against the real date, including between two slots.
                fun xDate(date: LocalDate): Float {
                    if (date <= days.first().date) return xIndex(0)
                    for (i in 1 until days.size) {
                        val before = days[i-1].date
                        val after = days[i].date
                        if (date <= after) {
                            val length = ChronoUnit.DAYS.between(before,after).coerceAtLeast(1)
                            val elapsed = ChronoUnit.DAYS.between(before,date)
                            return xIndex(i-1)+(xIndex(i)-xIndex(i-1))*elapsed.toFloat()/length
                        }
                    }
                    if (goalFuture && projectionEnd != null) {
                        val length = ChronoUnit.DAYS.between(days.last().date,projectionEnd).coerceAtLeast(1)
                        val elapsed = ChronoUnit.DAYS.between(days.last().date,date).coerceIn(0,length)
                        return xIndex(days.lastIndex)+(right-xIndex(days.lastIndex))*elapsed.toFloat()/length
                    }
                    return xIndex(days.lastIndex)
                }

                val gridDash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),5.dp.toPx()))
                for (n in 0..2) {
                    val y = top + (bottom-top)*n/2f
                    drawLine(gridColor,Offset(left,y),Offset(right,y),
                        strokeWidth=.8.dp.toPx(),pathEffect=gridDash)
                }
                val actualPath = Path()
                days.forEachIndexed { index, sample ->
                    val p = Offset(xIndex(index),range.y(sample.kilograms,top,bottom))
                    if(index==0) actualPath.moveTo(p.x,p.y) else actualPath.lineTo(p.x,p.y)
                }
                if (days.size>=2) drawPath(actualPath,actualColor,style=Stroke(width=2.8.dp.toPx()))
                days.forEachIndexed { index, sample ->
                    val center = Offset(xIndex(index),range.y(sample.kilograms,top,bottom))
                    val chosen = sample.date == selected.date
                    drawCircle(actualColor,radius=(if(chosen) 5.5f else 3.3f).dp.toPx(),center=center)
                    if(chosen) drawCircle(selectedSurface,
                        radius=2.0.dp.toPx(),center=center)
                }
                data.goal?.let { goal ->
                    val start = maxOf(goal.startDate,days.first().date)
                    val end = projectionEnd ?: minOf(goal.targetDate,days.last().date)
                    if(start <= end) {
                        val planDates=(listOf(start)+days.map { it.date }
                            .filter { it>start && it<end }+end).distinct()
                        val planPath=Path()
                        planDates.forEachIndexed { i,date ->
                            val p=Offset(xDate(date),range.y(goal.expected(date),top,bottom))
                            if(i==0) planPath.moveTo(p.x,p.y) else planPath.lineTo(p.x,p.y)
                        }
                        if(planDates.size >= 2) drawPath(planPath,planColor,
                            style=Stroke(width=2.3.dp.toPx(),
                                pathEffect=PathEffect.dashPathEffect(
                                    floatArrayOf(8.dp.toPx(),5.dp.toPx()))))
                        if(mode==WeightChartMode.Goal && end==goal.targetDate) {
                            drawCircle(planColor,4.8.dp.toPx(),
                                center=Offset(xDate(end),range.y(goal.targetKilograms,top,bottom)))
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(start=44.dp),
            horizontalArrangement=Arrangement.SpaceBetween) {
            Text(shortDate(days.first().date),style=MaterialTheme.typography.labelSmall,color=labelColor)
            if(goalFuture && data.goal!=null) {
                Text(shortDate(days.last().date),style=MaterialTheme.typography.labelSmall,color=labelColor)
                Text((if (mode == WeightChartMode.Goal) "Цель" else "Прогноз") +
                    " · ${shortDate(projectionEnd!!)}",
                    style=MaterialTheme.typography.labelSmall,color=planColor)
            } else {
                Text(shortDate(days.last().date),style=MaterialTheme.typography.labelSmall,color=labelColor)
            }
        }
        Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,
            shape=MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("${shortDate(selected.date)} · ${number(selected.kilograms,1)} кг",
                    style=MaterialTheme.typography.titleSmall)
                val onPlan = data.goal?.takeIf {
                    selected.date in it.startDate..it.targetDate
                }?.expected(selected.date)
                Text(
                    if(onPlan!=null) "По плану: ${number(onPlan,1)} кг · факт − план: " +
                        "${signedKg(selected.kilograms-onPlan)}"
                    else if (data.goal == null) "План веса пока не задан"
                    else "На эту дату план ещё не действовал",
                    style=MaterialTheme.typography.bodySmall,
                    color=labelColor,
                )
                Text("Нажмите на точку, чтобы посмотреть измерение.",
                    style=MaterialTheme.typography.labelSmall,color=labelColor)
            }
        }
        Text(
            if (mode==WeightChartMode.Measurements)
                "Измерения показаны через равные промежутки, план рассчитан по датам. Справа — прогноз на ближайшую неделю."
            else "История показана с равными интервалами между измерениями; справа отдельно отведено место до целевой даты.",
            style=MaterialTheme.typography.labelSmall,color=labelColor)
    }
}

@Composable
private fun ChartLegend(color: androidx.compose.ui.graphics.Color, dashed: Boolean, label: String) {
    Row(verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.width(20.dp).height(10.dp)) {
            drawLine(color,Offset(0f,size.height/2),Offset(size.width,size.height/2),
                strokeWidth=2.2.dp.toPx(),
                pathEffect=if(dashed) PathEffect.dashPathEffect(
                    floatArrayOf(5.dp.toPx(),3.dp.toPx())) else null)
        }
        Text(label,style=MaterialTheme.typography.labelSmall)
    }
}

internal fun signedKg(value: Double): String =
    (if (value > .045) "+" else if (value < -.045) "−" else "") +
        number(abs(value),1) + " кг"
