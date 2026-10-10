@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.kxsxlxv.jetmeal.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kxsxlxv.jetmeal.domain.WeekState
import com.kxsxlxv.jetmeal.domain.DayLoggingStatus
import com.kxsxlxv.jetmeal.ui.theme.nutritionColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** Presentation-only thresholds, deliberately not a backend or nutrition rule. */
internal object CalendarAdherence {
    const val CloseRatio=.10
    const val NearRatio=.25
    fun distance(actual:Double,target:Double?):Double? = when {
        target==null || target<0 -> null
        target==0.0 -> if(actual==0.0) 0.0 else Double.POSITIVE_INFINITY
        else -> abs(actual/target-1)
    }
    fun status(actual:Double,target:Double?):String = if(target==null || target<0) "Без цели" else when {
        distance(actual,target)!!<=CloseRatio -> "Близко к норме"
        actual>target -> "Выше нормы"
        else -> "Ниже нормы"
    }
    fun marker(actual:Double,target:Double?):String = when(status(actual,target)) {"Близко к норме"->"≈"; "Выше нормы"->"↑"; "Ниже нормы"->"↓"; else->"·"}
}

@Composable internal fun WeekContent(week:WeekState?,onTargets:()->Unit,onDay:(LocalDate)->Unit={},
    dailyTargets: Map<LocalDate, com.kxsxlxv.jetmeal.domain.Targets> = emptyMap()) {
    if(week==null) { TargetsPrompt(onTargets); return }
    val palette=nutritionColors()
    val maxValue=week.days.maxOf {maxOf(it.actual,it.target)}.coerceAtLeast(1.0)
    val today=LocalDate.now()
    val fontScale=LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compactChart=maxWidth<392.dp || fontScale>=1.3f
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item {
            Text(number(week.effectiveTarget),style=MaterialTheme.typography.displayMediumEmphasized)
            Text("ккал · ${if(today in week.start..week.end) "норма на сегодня" else if(today>week.end) "норма к концу недели" else "начальная дневная норма"}",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { WeekBudgetExplanation(week,dailyTargets) }
        item {
            Text("Ритм недели",style=MaterialTheme.typography.titleLargeEmphasized,modifier=Modifier.semantics {heading()})
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.Bottom) {
                week.days.forEach { day ->
                    val fill by animateFloatAsState((day.actual/maxValue).toFloat(),MaterialTheme.motionScheme.defaultSpatialSpec(),label="Съедено за день")
                    val mark=(day.target/maxValue).toFloat()
                    val dayModifier=Modifier.weight(1f).let {if(compactChart) it else it.clickable(role=Role.Button,onClick={onDay(day.date)})}
                    val known=day.status==DayLoggingStatus.Recorded || day.status==DayLoggingStatus.ConfirmedZero
                    Column(dayModifier.semantics(mergeDescendants=true) {
                        contentDescription="${day.date.format(DateTimeFormatter.ofPattern("d MMMM",RussianLocale))}, ${if(known) "${number(day.actual)} ккал" else if(day.status==DayLoggingStatus.Missing) "нет записей" else "день ещё не завершён"}, норма ${number(day.target)} ккал. Открыть день"
                    },horizontalAlignment=Alignment.CenterHorizontally) {
                        if(!compactChart) Text(if(known) day.actual.roundToInt().toString() else "—",style=MaterialTheme.typography.labelSmall,maxLines=1)
                        Canvas(Modifier.fillMaxWidth().height(190.dp).padding(horizontal=4.dp,vertical=8.dp)) {
                            drawRoundRect(palette.calories.container,cornerRadius=CornerRadius(size.width/2),size=size)
                            val height=(size.height*fill.coerceIn(0f,1f)).coerceAtLeast(if(fill>0) 4.dp.toPx() else 0f)
                            if(height>0 && known) drawRoundRect(Brush.verticalGradient(listOf(palette.calories.end,palette.calories.start)),topLeft=Offset(0f,size.height-height),size=Size(size.width,height),cornerRadius=CornerRadius(size.width/2))
                            val y=size.height*(1-mark)
                            drawLine(palette.calories.onContainer,Offset(0f,y),Offset(size.width,y),strokeWidth=2.dp.toPx())
                        }
                        Surface(color=if(day.date==today) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,shape=MaterialTheme.shapes.small) {
                            Text(day.date.format(DateTimeFormatter.ofPattern("EE",RussianLocale)).replaceFirstChar{it.titlecase(RussianLocale)},modifier=Modifier.padding(6.dp),style=MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Text("Полосы — съедено, отметки — дневная норма, прочерк — нет данных. " + if(compactChart) "Даты и значения — в списке ниже." else "Нажмите на день, чтобы открыть дневник.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=12.dp))
        }
        if(compactChart) item {
            Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                week.days.forEachIndexed {index,day->
                    SegmentedListItem(onClick={onDay(day.date)},shapes=ListItemDefaults.segmentedShapes(index,7),
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),
                        supportingContent={Text("${if(day.status==DayLoggingStatus.Missing) "Нет записей" else if(day.status==DayLoggingStatus.Future || day.status==DayLoggingStatus.InProgress) "Ещё не завершён" else "${number(day.actual)} ккал"} · норма ${number(day.target)} ккал")}) {
                        Text(day.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM",RussianLocale)))
                    }
                }
            }
        }
        if(week.missingCompletedDays.isNotEmpty()) item {
            Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Есть незаполненные дни",style=MaterialTheme.typography.titleMediumEmphasized)
                    Text("${week.missingCompletedDays.size} дней не включены в перераспределение. Это не нулевое питание; итог недели пока неполный.")
                    TextButton(onClick={onDay(week.missingCompletedDays.first())}) {Text("Открыть первый пропуск")}
                }
            }
        }
        item {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                AnalyticMetric("Съедено",number(week.totalConsumed),"ккал")
                AnalyticMetric("Бюджет недели",number(week.baseBudget),"ккал")
                AnalyticMetric("Отклонение",signed(week.deviation),"ккал за завершённые дни")
                if(today in week.start..week.end) AnalyticMetric("Дней осталось",week.remainingDays.toString(),"включая сегодня")
            }
        }
        if(abs(week.residual)>.5) item {
            Surface(color=MaterialTheme.colorScheme.tertiaryContainer,shape=MaterialTheme.shapes.large) {
                Column(Modifier.padding(18.dp)) {
                    Text("За пределами коррекции",style=MaterialTheme.typography.titleMediumEmphasized)
                    Text("${signed(week.residual)} ккал не удаётся распределить в пределах ±${number(week.adjustmentLimitRatio*100)}%. Остаток не переносится на следующую неделю.",modifier=Modifier.padding(top=8.dp))
                }
            }
        }
        item {Text("Белки, жиры и углеводы сохраняют заданные дневные цели.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    }
}

@Composable private fun AnalyticMetric(label:String,value:String,unit:String) {
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.widthIn(min=140.dp)) {
        Column(Modifier.padding(16.dp)) {Text(label,style=MaterialTheme.typography.labelLarge);Text(value,style=MaterialTheme.typography.headlineSmallEmphasized);Text(unit,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

/** Monday-first month grid, with either four, five or six complete week rows. */
internal fun calendarWeekCount(month: YearMonth): Int =
    (month.atDay(1).dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7

internal fun calendarDateAt(month: YearMonth, week: Int, weekday: Int): LocalDate? {
    require(week in 0 until calendarWeekCount(month) && weekday in 0..6)
    val day = week * 7 + weekday - (month.atDay(1).dayOfWeek.value - 1) + 1
    return if(day in 1..month.lengthOfMonth()) month.atDay(day) else null
}

/** A fixed categorical palette: no ambiguous 10–25% "almost" band. */
internal enum class CalendarTone { Missing, ConfirmedZero, InProgress, OnTarget, Above, Below, NoTarget }
internal fun calendarTone(date: LocalDate, actual: Double?, target: Double?,
    today: LocalDate, confirmedZero: Boolean = false): CalendarTone = when {
    actual == null -> CalendarTone.Missing
    confirmedZero -> CalendarTone.ConfirmedZero
    target == null || target < 0.0 -> CalendarTone.NoTarget
    date == today && actual <= target -> CalendarTone.InProgress
    CalendarAdherence.distance(actual,target)!! <= CalendarAdherence.CloseRatio -> CalendarTone.OnTarget
    actual > target -> CalendarTone.Above
    else -> CalendarTone.Below
}

@Composable internal fun CalendarContent(
    month: YearMonth, calories: Map<LocalDate,Double>, targets: Map<LocalDate,Double>,
    onMonth: (YearMonth)->Unit, onDay: (LocalDate)->Unit,
    selectedDate: LocalDate? = null, confirmedZeroDays: Set<LocalDate> = emptySet(),
) {
    val actual = (calories + confirmedZeroDays.filterNot { it in calories }
        .associateWith { 0.0 }).filterKeys { YearMonth.from(it) == month }
    val rows = calendarWeekCount(month)
    val palette = nutritionColors()
    val enlargedText = LocalDensity.current.fontScale >= 1.4f
    val weekdays = listOf("ПН","ВТ","СР","ЧТ","ПТ","СБ","ВС")
    // The month is intentionally a single non-scrollable viewport. The side length
    // of every date is constrained by both display width and usable screen height.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 4.dp
        val horizontalPadding = 12.dp
        val tileByWidth = (maxWidth - horizontalPadding * 2 - gap * 6) / 7
        val tileByHeight = (maxHeight - 174.dp - gap * (rows - 1)) / rows
        val tile = minOf(tileByWidth, tileByHeight).coerceAtLeast(28.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = horizontalPadding, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(gap,Alignment.CenterHorizontally),
                verticalAlignment=Alignment.CenterVertically) {
                weekdays.forEach { label ->
                    Box(Modifier.width(tile).height(20.dp),contentAlignment=Alignment.Center) {
                        Text(label,style=MaterialTheme.typography.labelSmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                    }
                }
            }
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(gap),
                horizontalAlignment=Alignment.CenterHorizontally) {
                repeat(rows) { row ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(gap,Alignment.CenterHorizontally)) {
                        repeat(7) { col ->
                            val date = calendarDateAt(month,row,col)
                            if (date == null) Spacer(Modifier.size(tile))
                            else CalendarDay(
                                date, actual[date], targets[date], selectedDate, onDay,
                                Modifier.size(tile), confirmedZero = date in confirmedZeroDays,
                                palette = palette,
                                showCalories = !enlargedText,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                CalendarLegendItem("В норме",palette.calories.container,Modifier.weight(1f))
                CalendarLegendItem("Выше",palette.carbs.container,Modifier.weight(1f))
                CalendarLegendItem("Ниже",palette.protein.container,Modifier.weight(1f))
                CalendarLegendItem("Нет данных",MaterialTheme.colorScheme.surfaceContainerLow,Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                CalendarSummaryItem("Дней с данными",actual.size.toString(),Modifier.weight(1f))
                CalendarSummaryItem("Съедено", "${number(actual.values.sum())} ккал",Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun CalendarLegendItem(label: String, tint: Color, modifier: Modifier) {
    Row(modifier.height(20.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(9.dp).background(tint,RoundedCornerShape(3.dp)))
        Text(label,style=MaterialTheme.typography.labelSmall,maxLines=1,
            color=MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap=false)
    }
}

@Composable private fun CalendarSummaryItem(label: String, value: String, modifier: Modifier) {
    Surface(modifier=modifier.height(68.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
        shape=MaterialTheme.shapes.large) {
        Column(Modifier.padding(horizontal=12.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(label,style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
            Text(value,style=MaterialTheme.typography.titleMediumEmphasized,maxLines=1)
        }
    }
}

@Composable private fun CalendarDay(
    date: LocalDate, actual: Double?, target: Double?, selected: LocalDate?,
    onDay: (LocalDate)->Unit, modifier: Modifier, confirmedZero: Boolean = false,
    palette: com.kxsxlxv.jetmeal.ui.theme.NutritionColors,
    showCalories: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val today = LocalDate.now()
    val tone = calendarTone(date,actual,target,today,confirmedZero)
    val tint = when(tone) {
        CalendarTone.Missing -> scheme.surfaceContainerLow
        CalendarTone.ConfirmedZero,CalendarTone.NoTarget -> scheme.surfaceContainerHigh
        CalendarTone.InProgress -> scheme.surfaceContainer
        CalendarTone.OnTarget -> palette.calories.container
        CalendarTone.Above -> palette.carbs.container
        CalendarTone.Below -> palette.protein.container
    }
    val animated by animateColorAsState(tint,MaterialTheme.motionScheme.fastEffectsSpec(),
        label="Цвет дня")
    val selection = date == selected
    val isToday = date == today
    val description = "${date.format(DateTimeFormatter.ofPattern("d MMMM yyyy",RussianLocale))}, ${actual?.let {
        if(confirmedZero) "0 ккал, подтверждённый нулевой день"
        else "${number(it)} ккал, ${if(tone==CalendarTone.InProgress) "день ещё продолжается" else CalendarAdherence.status(it,target)}"
    } ?: "Нет записей"}${if(isToday) ", сегодня" else ""}. Открыть день"
    Surface(onClick={onDay(date)},
        modifier=modifier.semantics(mergeDescendants=true) {contentDescription=description},
        shape=RoundedCornerShape(12.dp),
        color=animated,
        border=if(isToday||selection) androidx.compose.foundation.BorderStroke(
            if(selection) 2.dp else 1.dp,scheme.primary) else null) {
        Column(Modifier.fillMaxSize().padding(horizontal=2.dp,vertical=3.dp),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.Center) {
            Text(date.dayOfMonth.toString(),style=MaterialTheme.typography.titleMediumEmphasized,
                fontSize=16.sp,lineHeight=18.sp,maxLines=1)
            // At accessibility font scales the semantic description still contains
            // calories; prioritize readable date numbers over clipped tiny text.
            if(showCalories) Text(when {
                actual==null -> "·"
                confirmedZero -> "0 ✓"
                else -> actual.roundToInt().toString()
            },style=MaterialTheme.typography.labelSmall,
                fontSize=10.sp,lineHeight=12.sp,maxLines=1,
                color=if(actual==null) scheme.onSurfaceVariant else scheme.onSurface)
        }
    }
}

@Composable internal fun QuarterContent(start:LocalDate,calories:Map<LocalDate,Double>,targets:Map<LocalDate,Double>,onDay:(LocalDate)->Unit,confirmedZeroDays:Set<LocalDate> = emptySet()) {
    val quarter=TimelinePeriods.start(TimeScale.Quarter,start)
    val visible=(calories + confirmedZeroDays.filterNot { it in calories }.associateWith { 0.0 }).filterKeys {it>=quarter && it<quarter.plusMonths(3)}
    val palette=nutritionColors()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {
            Text("Питание в перспективе",style=MaterialTheme.typography.headlineSmallEmphasized)
            Text("${visible.size} дней с данными · ${number(visible.values.sum())} ккал",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
        }
        items(3) { index ->
            val month=YearMonth.from(quarter.plusMonths(index.toLong()))
            val data=visible.filterKeys {YearMonth.from(it)==month}
            var open by remember {mutableStateOf(false)}
            Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(month.format(DateTimeFormatter.ofPattern("LLLL",RussianLocale)).replaceFirstChar{it.titlecase(RussianLocale)},style=MaterialTheme.typography.titleLargeEmphasized,modifier=Modifier.weight(1f))
                        Box { TextButton(onClick={open=true},enabled=data.isNotEmpty()) {Text("Выбрать день")}
                            DropdownMenu(expanded=open,onDismissRequest={open=false},modifier=Modifier.heightIn(max=360.dp)) {
                                data.toSortedMap().forEach {(date,value)->DropdownMenuItem(text={Text("${date.dayOfMonth} · ${number(value)} ккал")},onClick={open=false;onDay(date)})}
                            }
                        }
                    }
                    val offset=month.atDay(1).dayOfWeek.value-1
                    val rows=(offset+month.lengthOfMonth()+6)/7
                    Column(Modifier.semantics(mergeDescendants=true) {contentDescription="Карта ${month.format(DateTimeFormatter.ofPattern("LLLL",RussianLocale))}: ${data.size} дней с данными. Для перехода используйте кнопку Выбрать день"},verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        repeat(rows) {row -> Row(horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                            repeat(7) {col ->
                                val n=row*7+col-offset+1
                                if(n !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(18.dp))
                                else {
                                    val day=month.atDay(n)
                                    val actual=data[day]
                                    val target=targets[day]
                                    val distance=actual?.let {CalendarAdherence.distance(it,target)}
                                    val tone=when {day in confirmedZeroDays->MaterialTheme.colorScheme.surfaceContainerHigh;actual==null->MaterialTheme.colorScheme.surfaceContainerHigh;distance==null||distance<=CalendarAdherence.CloseRatio->palette.protein.end;distance<=CalendarAdherence.NearRatio->palette.fat.end;else->palette.carbs.end}
                                    Box(Modifier.weight(1f).height(18.dp).background(tone,RoundedCornerShape(5.dp)))
                                }
                            }
                        } }
                    }
                    Text(if(data.isEmpty()) "Пока без записей" else "${data.size} дней · ${number(data.values.sum())} ккал",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {Text("Каждая ячейка — один день. Цвет показывает близость к норме калорий; подробности доступны в дневнике выбранного дня.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable internal fun TargetsPrompt(onTargets:()->Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Text("Ваш ритм начинается с цели",style=MaterialTheme.typography.headlineSmallEmphasized)
        Text("Укажите уже выбранные нормы калорий и макронутриентов.",modifier=Modifier.padding(vertical=16.dp))
        Button(onClick=onTargets,shapes=ButtonDefaults.shapes()) {Text("Настроить цели")}
    }
}
