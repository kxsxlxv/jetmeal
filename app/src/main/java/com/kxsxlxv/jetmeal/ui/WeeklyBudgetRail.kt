@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.kxsxlxv.jetmeal.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.domain.WeekState
import com.kxsxlxv.jetmeal.ui.theme.nutritionColors
import kotlin.math.abs
import kotlin.math.max

/** The target stays visibly inside the rail at 80% until consumption overflows it.
 * The rail describes the FULL seven-day budget, not the daily remaining allowance. */
internal data class WeeklyBudgetRailState(
    val eaten: Double, val budget: Double, val remaining: Double,
    val scale: Double, val goalFraction: Float, val consumedFraction: Float,
) {
    val overBudget: Boolean get() = remaining < 0
}

internal fun weeklyBudgetRail(eaten: Double, budget: Double): WeeklyBudgetRailState {
    require(eaten.isFinite() && eaten >= 0 && budget.isFinite() && budget >= 0)
    val scale=max(1.0,max(budget*1.25,eaten*1.05))
    return WeeklyBudgetRailState(
        eaten=eaten, budget=budget, remaining=budget-eaten, scale=scale,
        goalFraction=(budget/scale).toFloat().coerceIn(0f,1f),
        consumedFraction=(eaten/scale).toFloat().coerceIn(0f,1f),
    )
}

@Composable
internal fun WeeklyBudgetRail(week: WeekState) {
    val palette=nutritionColors()
    val state=weeklyBudgetRail(week.totalConsumed,week.baseBudget)
    val progress=animateFloatAsState(
        targetValue=state.consumedFraction,
        animationSpec=tween(durationMillis=1100,easing=CubicBezierEasing(.25f,.1f,.18f,1f)),
        label="Заполнение недельного бюджета",
    ).value
    val remainingLabel=if(state.overBudget) "Сверх бюджета" else "До бюджета"
    Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.extraLarge,
        color=MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=18.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text("Съедено",style=MaterialTheme.typography.labelLarge,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(number(state.eaten),style=MaterialTheme.typography.headlineSmallEmphasized,
                        maxLines=1)
                    Text("ккал",style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.End,
                    verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(remainingLabel,style=MaterialTheme.typography.labelLarge,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(number(abs(state.remaining)),style=MaterialTheme.typography.headlineSmallEmphasized,
                        maxLines=1,color=if(state.overBudget) palette.carbs.onContainer
                            else MaterialTheme.colorScheme.onSurface)
                    Text("ккал",style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val scheme=MaterialTheme.colorScheme
            Canvas(Modifier.fillMaxWidth().height(24.dp).semantics {
                contentDescription="Съедено ${number(state.eaten)} ккал, " +
                    "недельный бюджет ${number(state.budget)} ккал, " +
                    "${if(state.overBudget) "превышено" else "до бюджета"} ${number(abs(state.remaining))} ккал"
            }) {
                val radius=CornerRadius(size.height/2,size.height/2)
                drawRoundRect(color=scheme.surfaceContainerLow,cornerRadius=radius,size=size)
                val filled=size.width*progress
                if(filled>0f) {
                    drawRoundRect(
                        brush=Brush.horizontalGradient(listOf(palette.calories.start,palette.calories.end)),
                        cornerRadius=radius,size=Size(filled.coerceAtMost(size.width),size.height))
                }
                val goalX=size.width*state.goalFraction
                if(progress>state.goalFraction && goalX<size.width) {
                    drawRect(brush=Brush.horizontalGradient(listOf(
                        palette.carbs.start,palette.carbs.end),startX=goalX,endX=size.width),
                        topLeft=Offset(goalX,0f),
                        size=Size((filled-goalX).coerceAtLeast(0f).coerceAtMost(size.width-goalX),size.height))
                }
                if(state.budget>0) {
                    drawLine(color=scheme.onSurface, start=Offset(goalX,1.dp.toPx()),
                        end=Offset(goalX,size.height-1.dp.toPx()),
                        strokeWidth=2.5.dp.toPx(), cap=StrokeCap.Round)
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("0",style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("│ Бюджет ${number(state.budget)} ккал",
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
