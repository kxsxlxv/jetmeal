@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.kxsxlxv.jetmeal.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.ui.theme.nutritionColors
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Missing days are NOT zero; do not evaluate today's unfinished diary. */
internal data class MonthDeviation(val date: LocalDate,val percent: Double)

internal fun monthDeviations(month: YearMonth,consumed: Map<LocalDate,Double>,
    targets: Map<LocalDate,Double>, today: LocalDate): List<MonthDeviation> =
    (1..month.lengthOfMonth()).mapNotNull { day ->
        val date=month.atDay(day)
        val amount=consumed[date]
        val target=targets[date]
        if(date>=today || amount==null || target==null ||
            !amount.isFinite() || amount<0 || !target.isFinite() || target<=0) null
        else MonthDeviation(date,(amount/target-1.0)*100)
    }

internal fun showMonthDeviation(points: List<MonthDeviation>): Boolean = points.size>=6

/** Scale to the ACTUAL extrema, never truncate an exceptional day.
 * Zero anchors the axis at the bottom of an all-positive month or at the top
 * of an all-negative month; negative half-height is not wasted if unused. */
internal data class MonthDeviationAxis(val minimum:Double,val maximum:Double) {
    init { require(minimum.isFinite() && maximum.isFinite() &&
        minimum<=0 && maximum>=0 && maximum>minimum) }
    fun y(percent: Double):Float =
        ((maximum-percent.coerceIn(minimum,maximum))/(maximum-minimum)).toFloat()
}

internal fun monthlyDeviationAxis(points: List<MonthDeviation>): MonthDeviationAxis {
    val finite=points.map {it.percent}.filter {it.isFinite()}
    if(finite.isEmpty()) return MonthDeviationAxis(-10.0,10.0)
    val lowest=finite.min()
    val highest=finite.max()
    if(lowest==0.0 && highest==0.0) return MonthDeviationAxis(-10.0,10.0)
    val bottom=if(lowest<0) min(-10.0,floor(lowest/5.0)*5) else 0.0
    val top=if(highest>0) max(10.0,ceil(highest/5.0)*5) else 0.0
    return MonthDeviationAxis(bottom,top)
}

internal fun monthlyDeviationScale(points: List<MonthDeviation>): Double =
    max(abs(monthlyDeviationAxis(points).minimum),monthlyDeviationAxis(points).maximum)

internal fun deviationLabel(percent:Double):String =
    (if(percent>0) "+" else if(percent<0) "−" else "") +
        number(abs(percent),1) + "%"

/** One recorded day = one bar. An asymmetric, data-driven axis avoids
 * wasting half the chart on negatives if the month contains only positives. */
@Composable internal fun MonthDeviationChart(month: YearMonth,points: List<MonthDeviation>,
    height: Dp,onDay:(LocalDate)->Unit) {
    if(!showMonthDeviation(points)) return
    val colors=nutritionColors()
    val scheme=MaterialTheme.colorScheme
    val sorted=remember(points) {points.sortedBy {it.date}}
    val axis=remember(sorted) {monthlyDeviationAxis(sorted)}
    val avg=sorted.map {it.percent}.average()
    var revealed by remember(month) {mutableStateOf(false)}
    LaunchedEffect(month) {revealed=true}
    val entrance=animateFloatAsState(if(revealed) 1f else 0f,
        tween(1050,easing=CubicBezierEasing(.35f,0f,.15f,1f)),
        label="Отклонения по завершённым дням").value
    Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,
        color=scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),
            verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Отклонение от нормы",style=MaterialTheme.typography.titleSmallEmphasized,
                    modifier=Modifier.weight(1f))
                ExplanationInfoButton("Отклонение за месяц",
                    "Каждый столбец соответствует завершённому дню с записью. " +
                    "Расстояния между столбцами не показывают пропущенные даты. " +
                    "Высота — процент отклонения от нормы конкретного дня, без обрезки. " +
                    "Персиковый — съедено больше нормы, сиреневый — меньше. " +
                    "Нулевая линия отмечает точную цель. " +
                    "Для просмотра дневника нажмите на нужный столбец.")
            }
            Text("${sorted.size} дней с данными · среднее ${deviationLabel(avg)}",
                style=MaterialTheme.typography.labelSmall,
                color=scheme.onSurfaceVariant,maxLines=1)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(43.dp).height(height)) {
                    Text(deviationLabel(axis.maximum),
                        style=MaterialTheme.typography.labelSmall,
                        color=scheme.onSurfaceVariant,maxLines=1,
                        modifier=Modifier.align(Alignment.TopEnd))
                    if(axis.minimum<0 && axis.maximum>0) {
                        Text("0%",style=MaterialTheme.typography.labelSmall,
                            color=scheme.onSurfaceVariant,maxLines=1,
                            modifier=Modifier.align(Alignment.TopEnd)
                                .offset(y=height*axis.y(0.0)-7.dp))
                    }
                    Text(if(axis.minimum==0.0) "0%" else deviationLabel(axis.minimum),
                        style=MaterialTheme.typography.labelSmall,
                        color=scheme.onSurfaceVariant,maxLines=1,
                        modifier=Modifier.align(Alignment.BottomEnd))
                }
                Box(Modifier.weight(1f).height(height)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val zeroY=size.height*axis.y(0.0)
                        drawLine(color=scheme.outline.copy(alpha=.75f),
                            start=Offset(0f,zeroY),end=Offset(size.width,zeroY),
                            strokeWidth=1.dp.toPx())
                        val slot=size.width/sorted.size
                        val width=(slot*.34f).coerceIn(8.dp.toPx(),17.dp.toPx())
                        sorted.forEachIndexed {i,p ->
                            val targetY=size.height*axis.y(p.percent)
                            val extent=(targetY-zeroY)*entrance
                            val barHeight=abs(extent)
                            val cx=(i+.5f)*slot
                            if(barHeight>1f) {
                                val top=min(zeroY,zeroY+extent)
                                val brush=if(p.percent>0)
                                    Brush.verticalGradient(listOf(colors.carbs.end,colors.carbs.start),
                                        startY=top,endY=top+barHeight)
                                else Brush.verticalGradient(listOf(colors.protein.start,colors.protein.end),
                                    startY=top,endY=top+barHeight)
                                drawRoundRect(brush=brush,
                                    topLeft=Offset(cx-width/2,top),size=Size(width,barHeight),
                                    cornerRadius=CornerRadius(width/2))
                            } else if(entrance>.95f) {
                                drawCircle(colors.calories.start,radius=2.dp.toPx(),
                                    center=Offset(cx,zeroY))
                            }
                        }
                    }
                    // Discrete accessible targets: tapping a day does not interfere
                    // with the parent pager's horizontal drag recognition.
                    Row(Modifier.fillMaxSize()) {
                        sorted.forEach { point ->
                            Box(Modifier.weight(1f).fillMaxHeight()
                                .clickable(role=Role.Button,onClick={onDay(point.date)})
                                .semantics {
                                    contentDescription="${point.date.dayOfMonth} ${month.monthValue}." +
                                        " ${deviationLabel(point.percent)} от нормы. Открыть день"
                                })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start=49.dp)) {
                sorted.forEach {p->
                    Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {
                        Text(p.date.dayOfMonth.toString(),
                            style=MaterialTheme.typography.labelSmall,
                            color=scheme.onSurfaceVariant,maxLines=1)
                    }
                }
            }
        }
    }
}
