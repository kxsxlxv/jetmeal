@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.kxsxlxv.jetmeal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.domain.WeightMeasurement
import com.kxsxlxv.jetmeal.domain.WeightGoal
import com.kxsxlxv.jetmeal.domain.WeightProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
internal fun WeightSection(
    weights: List<WeightMeasurement>,
    goal: WeightGoal?,
    connected: Boolean,
    loading: Boolean,
    busy: Boolean,
    onAddWeight: (Double) -> Unit,
    onSetGoal: (Double, LocalDate) -> Unit,
    onConnect: (String,String,String) -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var weightText by remember { mutableStateOf("") }
    var goalKg by remember(goal) { mutableStateOf(goal?.targetKilograms?.let { number(it,1) } ?: "") }
    var targetDate by remember(goal) { mutableStateOf(goal?.targetDate) }
    var showDatePicker by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf("") }
    val today = LocalDate.now(zone)
    val progress = remember(weights,goal,today,zone) {
        WeightProgress.build(weights,goal,today,zone)
    }
    val latest = progress.latest
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Вес и прогресс", style = MaterialTheme.typography.titleLargeEmphasized)
        Text("Вес отображается отдельно от дневной нормы калорий и тренировок.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if(latest==null) "Пока нет измерений" else
                    "${number(latest.kilograms,1)} кг",
                    style=MaterialTheme.typography.headlineLargeEmphasized)
                Text(if(latest==null) "Добавьте взвешивание или подключите PICOOC."
                    else "Последнее взвешивание · ${latest.date.format(
                        DateTimeFormatter.ofPattern("d MMMM yyyy",RussianLocale))}",
                    color=MaterialTheme.colorScheme.onSurfaceVariant,
                    style=MaterialTheme.typography.bodySmall)

                if(latest!=null) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        WeightFact("Средний вес · 7 дней",
                            progress.trend7Kg?.let { "${number(it,1)} кг" } ?: "—",
                            Modifier.weight(1f))
                        WeightFact("Факт − план",
                            progress.actualMinusPlanKg?.let(::signedKg) ?: "—",
                            Modifier.weight(1f))
                    }
                    if(goal!=null && progress.actualMinusPlanKg==null) {
                        Text("План действует с ${goal.startDate.format(
                            DateTimeFormatter.ofPattern("d MMM",RussianLocale))}. " +
                            "Дельта появится после взвешивания в период действия цели.",
                            style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if(progress.actualMinusPlanKg!=null) {
                        Text("Отклонение рассчитано по последнему фактическому весу " +
                            "и плану на дату этого взвешивания, не по среднему за неделю.",
                            style=MaterialTheme.typography.labelSmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    WeightProgressChart(progress)
                }
                goal?.let { plan ->
                    HorizontalDivider()
                    Text("Цель: ${number(plan.targetKilograms,1)} кг · " +
                        plan.targetDate.format(DateTimeFormatter.ofPattern("d MMM yyyy",RussianLocale)),
                        style=MaterialTheme.typography.titleSmall)
                    Text("План на сегодня: ${number(plan.expected(today),1)} кг",
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        WeightFact("До цели",
                            if(latest==null) "—" else signedKg(plan.targetKilograms-latest.kilograms),
                            Modifier.weight(1f))
                        WeightFact("Нужно в неделю",
                            progress.requiredWeeklyKg?.let(::signedKg) ?: "—",
                            Modifier.weight(1f))
                    }
                    Text(
                        if(progress.observedWeeklyKg!=null)
                            "Фактический темп за 14 дней: ${signedKg(progress.observedWeeklyKg)} в неделю"
                        else "Фактический темп: для оценки нужны хотя бы 3 дня измерений за период от 7 дней.",
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(progress.remainingDays==0L)
                        Text("Плановая дата прошла. Вы можете установить новую цель.",
                            style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextField(value=weightText,onValueChange={weightText=it},
                label={Text("Вес, кг")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
                singleLine=true,modifier=Modifier.weight(1f),
                shape=TextFieldDefaults.roundedShape,colors=TextFieldDefaults.tonalColors())
            val parsed=weightText.replace(',','.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 20.0..500.0 }
            Button(onClick={parsed?.let { onAddWeight(it);weightText="" }},enabled=parsed!=null && !busy,
                modifier=Modifier.heightIn(min=56.dp),shapes=ButtonDefaults.shapes()) {
                Text("Записать")
            }
        }

        Text("Цель веса",style=MaterialTheme.typography.titleMediumEmphasized)
        Text("Используется только для сравнения с траекторией. Не меняет автоматически дефицит калорий.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        TextField(value=goalKg,onValueChange={goalKg=it},
            label={Text("Желаемый вес, кг")},singleLine=true,modifier=Modifier.fillMaxWidth(),
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
            shape=TextFieldDefaults.roundedShape,colors=TextFieldDefaults.tonalColors())
        OutlinedButton(onClick={showDatePicker=true},enabled=!busy,
            modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
            Text("Дата достижения · " + (targetDate?.format(
                DateTimeFormatter.ofPattern("d MMMM yyyy",RussianLocale)) ?: "Выбрать в календаре"))
        }
        if(showDatePicker) {
            val minimum = today.plusDays(1)
            val maximum = today.plusDays(730)
            val validSelection = targetDate?.takeIf { it in minimum..maximum }
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = validSelection?.let {
                    it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                },
                yearRange = today.year..maximum.year,
                selectableDates = remember(today) {
                    object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                            val date = Instant.ofEpochMilli(utcTimeMillis)
                                .atZone(ZoneOffset.UTC).toLocalDate()
                            return date in minimum..maximum
                        }
                        override fun isSelectableYear(year: Int): Boolean =
                            year in today.year..maximum.year
                    }
                },
            )
            val selected = pickerState.selectedDateMillis?.let {
                Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
            }?.takeIf { it in minimum..maximum }
            DatePickerDialog(
                onDismissRequest={showDatePicker=false},
                confirmButton={
                    TextButton(onClick={
                        if(selected!=null) {
                            targetDate=selected
                            showDatePicker=false
                        }
                    },enabled=selected!=null) {Text("Выбрать")}
                },
                dismissButton={
                    TextButton(onClick={showDatePicker=false}) {Text("Отмена")}
                },
            ) {
                DatePicker(state=pickerState,showModeToggle=false)
            }
        }
        val targetKg=goalKg.replace(',','.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 20.0..500.0 }
        val validTargetDate=targetDate?.takeIf { it in today.plusDays(1)..today.plusDays(730) }
        OutlinedButton(onClick={
            if(targetKg!=null && validTargetDate!=null) onSetGoal(targetKg,validTargetDate)
        },enabled=targetKg!=null && validTargetDate!=null && latest!=null && !busy) {
            Text("Сохранить цель веса")
        }
        HorizontalDivider()
        Text("Автоматически из PICOOC",style=MaterialTheme.typography.titleMediumEmphasized)
        Text("Jetmeal считывает данные из облака PICOOC напрямую. Откройте PICOOC после взвешивания, чтобы весы отправили новые измерения в облако. Jetmeal проверяет их при подключении, вручную и примерно раз в час при наличии интернета.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Подключение использует неофициальный интерфейс PICOOC. Если производитель изменит протокол, синхронизация сообщит об ошибке. Пароль хранится в защищённом хранилище телефона, не в Supabase.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(connected) {
            Text("PICOOC подключён",color=MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick=onSync,enabled=!busy,shapes=ButtonDefaults.shapes()) {Text("Синхронизировать")}
                TextButton(onClick=onDisconnect,enabled=!busy) {Text("Отключить")}
            }
        } else {
            TextField(value=email,onValueChange={email=it},label={Text("Email PICOOC")},
                singleLine=true,modifier=Modifier.fillMaxWidth(),
                shape=TextFieldDefaults.roundedShape,colors=TextFieldDefaults.tonalColors())
            TextField(value=password,onValueChange={password=it},
                label={Text("Пароль PICOOC")},singleLine=true,
                visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),
                shape=TextFieldDefaults.roundedShape,colors=TextFieldDefaults.tonalColors())
            TextField(value=profileName,onValueChange={profileName=it},
                label={Text("Имя профиля PICOOC (если несколько)")},
                singleLine=true,modifier=Modifier.fillMaxWidth(),
                shape=TextFieldDefaults.roundedShape,colors=TextFieldDefaults.tonalColors())
            Button(onClick={
                onConnect(email,password,profileName)
                password=""
            },enabled=email.isNotBlank() && password.isNotEmpty() && !busy,
                modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),
                shapes=ButtonDefaults.shapes()) {Text("Подключить PICOOC")}
        }
    }
}

@Composable
private fun WeightFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.titleMediumEmphasized)
    }
}
