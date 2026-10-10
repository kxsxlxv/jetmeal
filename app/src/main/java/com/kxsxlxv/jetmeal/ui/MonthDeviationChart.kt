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
import androidx.compose.runtime.remember
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
import kotlin.math.min

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

/** A bounded sign chart centred on 0% with the goal-tolerance zone tinted green.
 * Values outside ±100% are visually clipped only, never changed in data/tooltips. */
@Composable
internal fun MonthDeviationChart(
    month: YearMonth, points: List<MonthDeviation>, height: Dp,
    onDay: (LocalDate)->Unit,
) {
    if(!showMonthDeviation(points)) return
    val palette=nutritionColors()
    val scheme=MaterialTheme.colorScheme
    val snapshot=remember(points) {points.associateBy {it.date.dayOfMonth}}
    val animated=animateFloatAsState(1f,
        animationSpec=tween(850,easing=CubicBezierEasing(.3f,0f,.15f,1f)),
        label="Появление отклонений").value
    Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,
        color=scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),
            verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Отклонение от нормы",style=MaterialTheme.typography.titleSmallEmphasized,
                    modifier=Modifier.weight(1f))
                ExplanationInfoButton("Отклонение за месяц",
                    "Столбцы отражают отклонение от цели каждого завершённого дня. " +
                    "Нулевой уровень — точная норма, зелёная полоса — пределы ±10%. " +
                    "Персиковый цвет — превышение, сиреневый — недобор. " +
                    "Дни без записей и незавершённый сегодняшний день не оцениваются. " +
                    "Для читаемости высота столбцов ограничена ±100%, " +
                    "но реальные значения не округляются в расчётах.")
            }
            Canvas(Modifier.fillMaxWidth().height(height)
                .pointerInput(month,points,onDay) {
                    detectTapGestures { position ->
                        val index=((position.x / size.width.toFloat())*month.lengthOfMonth())
                            .toInt().coerceIn(0,month.lengthOfMonth()-1)
                        val chosen=snapshot[index+1]
                        if(chosen!=null) onDay(chosen.date)
                    }
                }.semantics {
                    contentDescription="Отклонение от нормы по дням месяца. " +
                        "${points.size} завершённых дней с данными. " +
                        "Нажмите на цветной столбец, чтобы открыть дату."
                }) {
                val middle=size.height/2
                val half=middle-5.dp.toPx()
                val tolerance=half*.10f
                drawRoundRect(color=palette.calories.container.copy(alpha=.48f),
                    topLeft=Offset(0f,middle-tolerance),
                    size=Size(size.width,tolerance*2),
                    cornerRadius=CornerRadius(4.dp.toPx()))
                drawLine(color=scheme.outline.copy(alpha=.65f),
                    start=Offset(0f,middle),end=Offset(size.width,middle),
                    strokeWidth=1.dp.toPx())
                val slot=size.width/month.lengthOfMonth()
                val barWidth=min(slot*.70f,8.dp.toPx())
                points.forEach { point ->
                    val centerX=slot*(point.date.dayOfMonth-.5f)
                    val magnitude=(abs(point.percent).coerceAtMost(100.0)/100.0).toFloat()*
                        half*animated
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
                    } else {
                        drawCircle(palette.calories.start,radius=2.dp.toPx(),
                            center=Offset(centerX,middle))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("1",style=MaterialTheme.typography.labelSmall,
                    color=scheme.onSurfaceVariant)
                Text("10",style=MaterialTheme.typography.labelSmall,
                    color=scheme.onSurfaceVariant)
                Text("20",style=MaterialTheme.typography.labelSmall,
                    color=scheme.onSurfaceVariant)
                Text(month.lengthOfMonth().toString(),style=MaterialTheme.typography.labelSmall,
                    color=scheme.onSurfaceVariant)
            }
        }
    }
}
