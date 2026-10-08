@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.kxsxlxv.jetmeal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.domain.WeightMeasurement
import com.kxsxlxv.jetmeal.domain.WeightGoal
import androidx.compose.ui.graphics.PathEffect
import com.kxsxlxv.jetmeal.domain.WeightTrend
import java.time.LocalDate
import java.time.ZoneId
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
    var deadlineText by remember(goal) { mutableStateOf(goal?.targetDate?.toString() ?: "") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf("") }
    val current = weights.maxByOrNull { it.measuredAt }
    val smoothed = WeightTrend.smoothedLast7Days(weights, zone)
    val normalized = weights.sortedBy { it.measuredAt }
    val early = normalized.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Вес и прогресс", style = MaterialTheme.typography.titleLargeEmphasized)
        Text("Измерения не изменяют автоматически калорийность тренировок или дневную норму.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if(current==null) "Пока нет измерений" else
                    "${number(current.kilograms,1)} кг",style=MaterialTheme.typography.headlineLargeEmphasized)
                Text(if(current==null) "Добавьте первое взвешивание или подключите PICOOC."
                    else "Сглаженный тренд за 7 дней: ${smoothed?.let { number(it,2) } ?: "—"} кг",
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                if (normalized.size>=2) {
                    val change = current!!.kilograms - requireNotNull(early).kilograms
                    Text("Изменение за доступную историю: ${if(change>0) "+" else ""}${number(change,1)} кг",
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    WeightLineGraph(normalized,goal)
                }
                goal?.let { plan ->
                    val expected=plan.expected(LocalDate.now(zone))
                    Text("План на сегодня: ${number(expected,1)} кг" +
                        (smoothed?.let { " · отклонение ${number(it-expected,1)} кг" } ?: ""),
                        color=MaterialTheme.colorScheme.primary)
                    Text("К ${plan.targetDate}: ${number(plan.targetKilograms,1)} кг",
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                current?.let {
                    Text("Последнее измерение: ${it.measuredAt.atZone(zone).format(
                        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm",RussianLocale))} · ${it.source.uppercase()}",
                        style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
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
        TextField(value=deadlineText,onValueChange={deadlineText=it},
            label={Text("Желаемая дата (ГГГГ-ММ-ДД)")},singleLine=true,
            modifier=Modifier.fillMaxWidth(),shape=TextFieldDefaults.roundedShape,
            colors=TextFieldDefaults.tonalColors())
        val targetKg=goalKg.replace(',','.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 20.0..500.0 }
        val targetDate=runCatching { LocalDate.parse(deadlineText.trim()) }.getOrNull()
            ?.takeIf { it>LocalDate.now() && it<=LocalDate.now().plusDays(730) }
        OutlinedButton(onClick={
            if(targetKg!=null && targetDate!=null) onSetGoal(targetKg,targetDate)
        },enabled=targetKg!=null && targetDate!=null && current!=null && !busy) {
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
private fun WeightLineGraph(weights: List<WeightMeasurement>, goal: WeightGoal?) {
    val points=weights.sortedBy { it.measuredAt }.takeLast(90)
    val min=minOf(points.minOf { it.kilograms },goal?.targetKilograms ?: Double.POSITIVE_INFINITY,
        goal?.startKilograms ?: Double.POSITIVE_INFINITY)
    val max=maxOf(points.maxOf { it.kilograms },goal?.targetKilograms ?: Double.NEGATIVE_INFINITY,
        goal?.startKilograms ?: Double.NEGATIVE_INFINITY)
    val range=(max-min).coerceAtLeast(.5)
    val zone=ZoneId.systemDefault()
    val start=minOf(points.first().measuredAt.epochSecond,
        goal?.startDate?.atStartOfDay(zone)?.toEpochSecond() ?: Long.MAX_VALUE)
    val end=maxOf(points.last().measuredAt.epochSecond,
        goal?.targetDate?.atStartOfDay(zone)?.toEpochSecond() ?: Long.MIN_VALUE)
    Canvas(Modifier.fillMaxWidth().height(132.dp)) {
        val path=Path()
        points.forEachIndexed { index, p ->
            val x=if(end==start) size.width*index/(points.size-1).coerceAtLeast(1)
                  else size.width*((p.measuredAt.epochSecond-start).toFloat()/(end-start))
            val y=size.height*(.9f-((p.kilograms-min)/range).toFloat()*.8f)
            if(index==0) path.moveTo(x,y) else path.lineTo(x,y)
        }
        drawPath(path,color=androidx.compose.ui.graphics.Color(0xFF43A68A),
            style=Stroke(width=3.dp.toPx()))
        goal?.let { plan ->
            fun x(date: LocalDate):Float=size.width*
                ((date.atStartOfDay(zone).toEpochSecond()-start).toFloat()/(end-start).coerceAtLeast(1))
            fun y(kg: Double):Float=size.height*(.9f-((kg-min)/range).toFloat()*.8f)
            drawLine(androidx.compose.ui.graphics.Color(0xFFB38C47),
                Offset(x(plan.startDate),y(plan.startKilograms)),
                Offset(x(plan.targetDate),y(plan.targetKilograms)),
                strokeWidth=2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(
                    floatArrayOf(7.dp.toPx(),4.dp.toPx())))
        }
    }
}
