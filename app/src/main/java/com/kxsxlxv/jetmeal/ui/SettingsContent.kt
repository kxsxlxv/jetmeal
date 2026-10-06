@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.kxsxlxv.jetmeal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.domain.Targets
import kotlin.math.roundToInt

@Composable
fun SettingsContent(targets: Targets?, email: String, busy: Boolean, onSave: (Targets) -> Unit, onSignOut: () -> Unit) {
    var calories by rememberSaveable(targets) { mutableStateOf(targets?.calories?.let(::decimalInput) ?: "") }
    var protein by rememberSaveable(targets) { mutableStateOf(targets?.protein?.let(::decimalInput) ?: "") }
    var fat by rememberSaveable(targets) { mutableStateOf(targets?.fat?.let(::decimalInput) ?: "") }
    var carbs by rememberSaveable(targets) { mutableStateOf(targets?.carbs?.let(::decimalInput) ?: "") }
    var limit by rememberSaveable(targets) { mutableStateOf(decimalInput((targets?.limitRatio ?: 0.10) * 100)) }
    var review by remember { mutableStateOf<Targets?>(null) }
    val percent = limit.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f }
    val slider = rememberSliderState(value = percent ?: 10f, trackRange = 0f..100f)
    LaunchedEffect(percent) { percent?.let { slider.value = it } }
    val proposed = runCatching {
        Targets(settingsNumber(calories), settingsNumber(protein), settingsNumber(fat), settingsNumber(carbs), settingsNumber(limit) / 100)
    }.getOrNull()
    val fontScale = LocalDensity.current.fontScale
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState())
            .imePadding().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ваши цели", style = MaterialTheme.typography.headlineMediumEmphasized)
                Text("Укажите выбранные вами значения.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TargetField("Базовая цель (ккал)", calories, { calories = it }, !busy)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Белки, жиры и углеводы", style = MaterialTheme.typography.titleMediumEmphasized)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val twoColumns = maxWidth >= 340.dp && fontScale < 1.3f
                    val fieldWidth = if (twoColumns) (maxWidth - 12.dp) / 2 else maxWidth
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TargetField("Белки (г)", protein, { protein = it }, !busy, Modifier.width(fieldWidth))
                        TargetField("Жиры (г)", fat, { fat = it }, !busy, Modifier.width(fieldWidth))
                        TargetField("Углеводы (г)", carbs, { carbs = it }, !busy, Modifier.fillMaxWidth())
                    }
                }
                Text("Цели БЖУ остаются постоянными всю неделю.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Корректировка калорий", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text("±${percent?.let { number(it.toDouble(), if (it % 1f == 0f) 0 else 1) } ?: "—"}%",
                        style = MaterialTheme.typography.headlineLargeEmphasized)
                    Slider(state = slider, onValueChange = {
                        slider.value = it
                        limit = it.roundToInt().toString()
                    }, enabled = !busy,
                        colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .18f)),
                        modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Предел недельной корректировки калорий"
                    })
                    TargetField("Точное значение (±%)", limit, { limit = it }, !busy,
                        isError = percent == null)
                    Text("Норма меняется в пределах этого процента. Остаток отклонения сбрасывается в понедельник.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = { review = proposed }, shapes = ButtonDefaults.shapes(), enabled = proposed != null && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Проверить изменения") }
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Аккаунт", style = MaterialTheme.typography.titleLargeEmphasized)
                Text(email, style = MaterialTheme.typography.bodyLarge)
                Text("Часовой пояс и светлая или тёмная тема — как в настройках устройства.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onSignOut, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                    SymbolIcon(JetMealSymbol.Logout, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("Выйти из аккаунта")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    review?.let { values ->
        AlertDialog(onDismissRequest = { review = null }, title = { Text("Сохранить новые цели?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${number(values.calories)} ккал", style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text("Белки: ${number(values.protein, 1)} г\nЖиры: ${number(values.fat, 1)} г\nУглеводы: ${number(values.carbs, 1)} г")
                    Text("Корректировка: ±${number(values.limitRatio * 100, 1)}%")
                }
            },
            confirmButton = { Button(onClick = { review = null; onSave(values) }, enabled = !busy,
                shapes = ButtonDefaults.shapes()) { Text("Сохранить цели") } },
            dismissButton = { TextButton(onClick = { review = null }) { Text("Продолжить редактирование") } })
    }
}

@Composable
private fun TargetField(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean,
    modifier: Modifier = Modifier, isError: Boolean = false) {
    TextField(value, onValue, label = { Text(label) }, enabled = enabled, singleLine = true,
        isError = isError, shape = TextFieldDefaults.roundedShape, colors = TextFieldDefaults.tonalColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier.fillMaxWidth())
}

private fun settingsNumber(text: String): Double = text.trim().replace(',', '.').toDouble()
