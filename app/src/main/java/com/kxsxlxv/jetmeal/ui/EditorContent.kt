@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kxsxlxv.jetmeal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.domain.FoodCandidate
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.domain.QuantityScaling
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun FoodSearchContent(foods: List<FoodCandidate>, searching: Boolean, onSearch: (String) -> Unit,
    onSelect: (FoodCandidate) -> Unit, cachedCount: Int = 0,
    catalogueLoading: Boolean = false, offline: Boolean = false,
    onDownload: () -> Unit = {}) {
    val query = rememberTextFieldState()
    val searchBar = rememberSearchBarState(initialValue = SearchBarValue.Expanded)
    val search by rememberUpdatedState(onSearch)
    val focus = LocalFocusManager.current
    LaunchedEffect(query) {
        snapshotFlow { query.text.toString() }.distinctUntilChanged().collect { search(it) }
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).imePadding()
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The official search input remains inline in the existing sheet; a second fullscreen
        // search overlay would compete with the sheet's quantity-confirmation navigation.
        SearchBarDefaults.InputField(textFieldState = query, searchBarState = searchBar,
            onSearch = { focus.clearFocus() }, placeholder = { Text("Найти еду в каталоге") },
            leadingIcon = { SymbolIcon(JetMealSymbol.Search, null) }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if(cachedCount>0) "Сохранено для офлайна: $cachedCount продуктов"
                    else "Локальный каталог ещё не загружен",
                    style=MaterialTheme.typography.labelMedium)
                Text(if(offline) "Поиск по сохранённому каталогу"
                    else "Каталог доступен без сети после загрузки",
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(catalogueLoading) LoadingIndicator(Modifier.size(32.dp))
            else TextButton(onClick=onDownload) { Text("Обновить") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (query.text.isBlank()) "Часто добавляете" else "Результаты поиска",
                style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
            if (searching) LoadingIndicator(Modifier.size(36.dp))
        }
        LazyColumn(Modifier.heightIn(min = 120.dp, max = 420.dp),
            contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!searching && foods.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (cachedCount==0 && offline) "Каталог недоступен без предварительной загрузки"
                        else if (query.text.isBlank()) "В каталоге пока пусто" else "Ничего не найдено",
                        style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(if(cachedCount==0 && offline) "Подключитесь к интернету и нажмите «Обновить», чтобы сохранить весь каталог."
                        else if (query.text.isBlank()) "Добавьте продукт через подключённые инструменты питания."
                        else "Попробуйте другое название, бренд или источник.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(foods, key = { _, food -> food.id }) { index, food ->
                SegmentedListItem(onClick = { focus.clearFocus(); onSelect(food) },
                    shapes = ListItemDefaults.segmentedShapes(index, foods.size),
                    supportingContent = { Text(listOfNotNull(food.brand, food.source,
                        "${number(food.amount, 1)} ${unitLabel(food.unit)}").joinToString(" · ")) },
                    overlineContent = if (food.estimated) {{ Text("Примерная пищевая ценность") }} else null,
                    trailingContent = { Text("${number(food.nutrition.calories)}\nккал",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }) {
                    Text(food.name, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
internal fun AmountContent(name: String, unit: String, amount: Double, basisAmount: Double, nutrition: Nutrition,
    estimated: Boolean, busy: Boolean, error: String?, onSave: (Double) -> Unit, onDelete: (() -> Unit)?,
    onSuccess: () -> Unit, onBack: (() -> Unit)?, successNotice: String? = null) {
    var text by rememberSaveable(name, amount) { mutableStateOf(decimalInput(amount)) }
    var pending by remember { mutableStateOf(false) }
    val quantity = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val scaled = quantity?.let { runCatching { QuantityScaling.scale(basisAmount, nutrition, it) }.getOrNull() }
    LaunchedEffect(busy, pending, error, successNotice) {
        if (pending && !busy && error != null) pending = false
        else if (pending && !busy && (successNotice?.contains("Можно отменить") == true ||
            successNotice?.startsWith("Сохранено на телефоне:") == true) && error == null) {
            pending = false
            onSuccess()
        }
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).verticalScroll(rememberScrollState())
        .imePadding().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        onBack?.let {
            TextButton(onClick = it, enabled = !busy) {
                SymbolIcon(JetMealSymbol.Back, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("К выбору еды")
            }
        }
        Text(name, style = MaterialTheme.typography.headlineSmallEmphasized)
        Text("Основа расчёта: ${number(basisAmount, 1)} ${unitLabel(unit)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (estimated) Text("Примерная пищевая ценность", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary)
        TextField(text, { text = it }, label = { Text("Количество (${unitLabel(unit)})") }, singleLine = true,
            shape = TextFieldDefaults.roundedShape, colors = TextFieldDefaults.tonalColors(),
            enabled = !busy, isError = scaled == null,
            supportingText = { if (scaled == null) Text("Введите количество больше нуля") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        scaled?.let {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${number(it.calories)} ккал", style = MaterialTheme.typography.headlineLargeEmphasized,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Б ${number(it.protein, 1)} г · Ж ${number(it.fat, 1)} г · У ${number(it.carbs, 1)} г",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        error?.let { EditorError(it) }
        Button(onClick = { quantity?.let { pending = true; onSave(it) } }, shapes = ButtonDefaults.shapes(),
            enabled = scaled != null && !busy && !pending, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(if (onDelete == null) "Добавить еду" else "Сохранить количество")
        }
        if (busy) LoadingIndicator(Modifier.align(Alignment.CenterHorizontally).size(40.dp))
        onDelete?.let { action ->
            TextButton(onClick = { pending = true; action() }, enabled = !busy && !pending, modifier = Modifier.fillMaxWidth()) {
                Text("Удалить запись", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
internal fun EditorError(message: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
