@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.kxsxlxv.jetmeal.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.roundToInt

/** Never infer a zero-calorie day from an absent entry, or treat today's partial
 * consumption as a completed result. Targets are per-day, not a fixed monthly mean. */
internal data class MonthDeviation(val date: LocalDate,val percent: Double)

internal fun monthDeviations(month: YearMonth,consumed: Map<LocalDate,Double>,
    targets: Map<LocalDate,Double>, today: LocalDate): List<MonthDeviation> =
    (1..month.lengthOfMonth()).mapNotNull { d ->
        val date=month.atDay(d)
        val calories=consumed[date]
        val target=targets[date]
        if(date>=today || calories==null || target==null ||
            !calories.isFinite() || calories<0.0 || !target.isFinite() || target<=0.0) null
        else MonthDeviation(date, (calories/target-1.0)*100.0)
    }

internal fun showMonthDeviation(points: List<MonthDeviation>): Boolean = points.size>=6

/** Robust, human-scale symmetric axis. A single unusual day should not flatten
 * the other entries. Values outside the axis are visibly marked as truncated. */
internal fun monthlyDeviationScale(points: List<MonthDeviation>): Double {
    if(points.isEmpty()) return 10.0
    val sorted=points.map { kotlin.math.abs(it.percent) }.filter {it.isFinite()}.sorted()
    if(sorted.isEmpty()) return 10.0
    val representative=sorted[floor((sorted.lastIndex)*.85).toInt()]
    return max(10.0, ceil(representative/5.0)*5.0)
}

internal fun deviationLabel(percent:Double):String =
    (if(percent>0) "+" else if(percent<0) "−" else "") +
        number(kotlin.math.abs(percent),1) + "%"

/** Show recorded dates equidistantly, with an explicitly adaptive percentage
 * axis, not a 31-slot near-flat series whose only visible data are on the left. */
@Composable
internal fun MonthDeviationChart(
    month: YearMonth, points: List<MonthDeviation>, height: Dp,
    onDay: (LocalDate)->Unit,
) {
    if(!showMonthDeviation(points)) return
    val palette=nutritionColors()
    val scheme=MaterialTheme.colorScheme
    val sorted=remember(points) {points.sortedBy {it.date}}
    val scale=remember(sorted) {monthlyDeviationScale(sorted)}
    val clipped=sorted.count { kotlin.math.abs(it.percent)>scale }
    val average=sorted.map {it.percent}.average()
    var revealed by remember(month) { mutableStateOf(false) }
    LaunchedEffect(month) { revealed=true }
    val animated=animateFloatAsState(if(revealed) 1f else 0f,
        animationSpec=tween(1000,easing=CubicBezierEasing(.3f,0f,.15f,1f)),
        label="Появление отклонений").value
    Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,
        color=scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),
            verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Отклонение от нормы",style=MaterialTheme.typography.titleSmallEmphasized,
                    modifier=Modifier.weight(1f))
                ExplanationInfoButton("Отклонение за месяц",
                    "Каждый столбец — один завершённый день с записями. " +
                    "Даты без данных не показаны; расстояние между столбцами не отражает пропущенные дни. " +
                    "Ноль — точная дневная норма; зелёная область — ±10%. " +
                    "Персиковый — превышение, сиреневый — недобор. " +
                    "Масштаб подбирается автоматически. Если отдельный день выходит за него, " +
                    "на конце столбца появляется метка. Нажатие открывает запись за эту дату.")
            }
            Text("${sorted.size} дней с данными · среднее ${deviationLabel(average)}" +
                if(clipped>0) " · за шкалой: $clipped" else "",
                style=MaterialTheme.typography.labelSmall,
                color=scheme.onSurfaceVariant,maxLines=1)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Column(Modifier.width(43.dp).height(height),
                    verticalArrangement=Arrangement.SpaceBetween,
                    horizontalAlignment=Alignment.End) {
                    Text("+${number(scale)}%",style=MaterialTheme.typography.labelSmall,
                        color=scheme.onSurfaceVariant,maxLines=1)
                    Text("0%",style=MaterialTheme.typography.labelSmall,
                        color=scheme.onSurfaceVariant,maxLines=1)
                    Text("−${number(scale)}%",style=MaterialTheme.typography.labelSmall,
                        color=scheme.onSurfaceVariant,maxLines=1)
                }
                Canvas(Modifier.weight(1f).height(height)
                    .pointerInput(sorted,onDay) {
                        detectTapGestures { position ->
                            val index=((position.x / size.width.toFloat())*sorted.size)
                                .toInt().coerceIn(0,sorted.lastIndex)
                            onDay(sorted[index].date)
                        }
                    }.semantics {
                        contentDescription="Отклонение от дневной нормы за ${sorted.size} завершённых дней. " +
                            "Среднее ${deviationLabel(average)}. Масштаб ±${number(scale)}%. " +
                            "Нажмите на столбец, чтобы открыть день."
                    }) {
                    val middle=size.height/2f
                    val half=(middle-3.dp.toPx()).coerceAtLeast(1f)
                    val tolerance=half*(10.0/scale).toFloat().coerceAtMost(1f)
                    drawRoundRect(color=palette.calories.container.copy(alpha=.6f),
                        topLeft=Offset(0f,middle-tolerance),
                        size=Size(size.width,tolerance*2),
                        cornerRadius=CornerRadius(4.dp.toPx()))
                    drawLine(color=scheme.outline.copy(alpha=.65f),
                        start=Offset(0f,middle),end=Offset(size.width,middle),
                        strokeWidth=1.dp.toPx())
                    val slot=size.width/sorted.size
                    val barWidth=(slot*.52f).coerceIn(5.dp.toPx(),16.dp.toPx())
                    sorted.forEachIndexed { index,point ->
                        val centerX=slot*(index+.5f)
                        val rawMagnitude=(kotlin.math.abs(point.percent)/scale).toFloat()
                        val outOfRange=rawMagnitude>1f
                        val magnitude=(rawMagnitude.coerceAtMost(1f)*half*animated)
                            .coerceAtLeast(if(point.percent!=0.0) 2.dp.toPx()*animated else 0f)
                        if(magnitude>0f) {
                            val top=if(point.percent>=0) middle-magnitude else middle
                            val colors=if(point.percent>=0)
                                listOf(palette.carbs.start,palette.carbs.end)
                                else listOf(palette.protein.start,palette.protein.end)
                            drawRoundRect(brush=Brush.verticalGradient(colors,
                                startY=top,endY=top+magnitude),
                                topLeft=Offset(centerX-barWidth/2,top),
                                size=Size(barWidth,magnitude),
                                cornerRadius=CornerRadius(barWidth/2))
                            if(outOfRange && animated>.98f) {
                                val edge=if(point.percent>0) middle-half else middle+half
                                drawCircle(scheme.onSurface,radius=2.3.dp.toPx(),
                                    center=Offset(centerX,edge))
                            }
                        } else if(animated>.98f) {
                            drawCircle(palette.calories.start,radius=2.dp.toPx(),
                                center=Offset(centerX,middle))
                        }
                    }
                }
            }
            if(sorted.size<=8) {
                // Few recorded days: show the REAL percentage and date for every
                // column, so the chart communicates more than color and height.
                Row(Modifier.fillMaxWidth().padding(start=49.dp)) {
                    sorted.forEach { point ->
                        Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(deviationLabel(point.percent),
                                style=MaterialTheme.typography.labelSmall,
                                color=scheme.onSurface,maxLines=1)
                            Text(point.date.dayOfMonth.toString(),
                                style=MaterialTheme.typography.labelSmall,
                                color=scheme.onSurfaceVariant,maxLines=1)
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(start=49.dp),
                    horizontalArrangement=Arrangement.SpaceBetween) {
                    listOf(sorted.first(),sorted[sorted.lastIndex/2],sorted.last()).forEach { point ->
                        Text(point.date.dayOfMonth.toString(),
                            style=MaterialTheme.typography.labelSmall,
                            color=scheme.onSurfaceVariant,maxLines=1)
                    }
                }
            }
        }
    }
}
