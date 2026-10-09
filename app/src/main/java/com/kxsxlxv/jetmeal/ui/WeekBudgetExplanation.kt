@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.kxsxlxv.jetmeal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.domain.BudgetExplain
import com.kxsxlxv.jetmeal.domain.Targets
import com.kxsxlxv.jetmeal.domain.WeekState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Explains each input to the current Monday–Sunday redistribution. */
@Composable
internal fun WeekBudgetExplanation(week: WeekState, dailyTargets: Map<LocalDate,Targets>) {
    val info=remember(week,dailyTargets) {BudgetExplain.calculate(week,dailyTargets)}
    var expanded by remember(week.start) { mutableStateOf(false) }
    Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Откуда взялась норма",style=MaterialTheme.typography.titleLargeEmphasized)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                BudgetFact("Базовая",number(info.baselineToday)+" ккал",Modifier.weight(1f))
                BudgetFact("Коррекция",
                    (if(info.adjustment>0) "+" else "")+number(info.adjustment)+" ккал",
                    Modifier.weight(1f))
            }
            Text("На сегодня: ${number(info.baselineToday)} ${if(info.adjustment<0) "−" else "+"} " +
                "${number(abs(info.adjustment))} = ${number(info.effective)} ккал",
                style=MaterialTheme.typography.titleMediumEmphasized)
            Text(
                if(info.cumulativeDifference>0)
                    "За завершённые дни записано на ${number(info.cumulativeDifference)} ккал больше их базовых норм."
                else if(info.cumulativeDifference<0)
                    "За завершённые дни записано на ${number(abs(info.cumulativeDifference))} ккал меньше их базовых норм."
                else "По заполненным завершённым дням отклонения пока нет.",
                style=MaterialTheme.typography.bodyMedium)
            Text("Разница распределяется на ${info.remainingDays} оставшихся дней недели, " +
                "но изменение ограничено настроенным пределом.",
                color=MaterialTheme.colorScheme.onSurfaceVariant,
                style=MaterialTheme.typography.bodySmall)
            if(info.hasLimit) {
                Text("Сработало ограничение: разрешённый диапазон " +
                    "${number(info.lowerBound)}–${number(info.upperBound)} ккал. " +
                    "Без ограничения получилось бы ${number(info.theoretical)} ккал.",
                    style=MaterialTheme.typography.bodySmall)
            }
            if(info.missingDays>0) {
                Text("Нет записей за ${info.missingDays} завершённых " +
                    "${if(info.missingDays==1) "день" else "дня/дней"}. " +
                    "Эти даты исключены из расчёта, а не приравнены к нулю.",
                    style=MaterialTheme.typography.bodySmall)
            }
            if(abs(info.undistributed)>.5) {
                Text("Не распределено: ${signed(info.undistributed)} ккал. " +
                    "В следующую неделю остаток не переносится.",
                    style=MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick={expanded=!expanded}) {
                Text(if(expanded) "Свернуть расчёт" else "Показать расчёт по дням")
            }
            if(expanded) {
                HorizontalDivider()
                info.causes.forEach { cause ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(cause.date.format(DateTimeFormatter.ofPattern("EE, d MMM",RussianLocale)),
                            style=MaterialTheme.typography.bodySmall)
                        Text(
                            if(cause.consumed==null) "Нет данных"
                            else "${number(cause.consumed)} − ${number(cause.baseline)} = " +
                                "${signed(requireNotNull(cause.difference))}",
                            style=MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Формула: базовая норма − суммарное отклонение / " +
                    "число оставшихся дней = ${number(info.theoretical)} ккал до ограничения.",
                    color=MaterialTheme.colorScheme.onSurfaceVariant,
                    style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BudgetFact(label: String, value: String, modifier: Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.titleMediumEmphasized)
    }
}
