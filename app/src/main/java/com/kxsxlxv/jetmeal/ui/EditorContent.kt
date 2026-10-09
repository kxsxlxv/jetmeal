@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.kxsxlxv.jetmeal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.kxsxlxv.jetmeal.ui.theme.RingColors
import com.kxsxlxv.jetmeal.ui.theme.nutritionColors
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.domain.FoodCandidate
import com.kxsxlxv.jetmeal.domain.FoodMeasure
import com.kxsxlxv.jetmeal.domain.ChosenMeasure
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.domain.QuantityScaling
import kotlinx.coroutines.flow.distinctUntilChanged

/** The catalogue is variant-based, but search offers one item per actual product. */
internal fun groupFoodSearchResults(foods: List<FoodCandidate>): List<List<FoodCandidate>> =
    foods.groupBy { it.foodId }.values.map { variants ->
        variants.sortedByDescending { it.measures.isNotEmpty() }
    }

internal fun compactFoodBrand(food: FoodCandidate): String? =
    food.brand?.trim()?.takeIf { it.isNotBlank() && !food.name.contains(it, ignoreCase = true) }

internal fun primaryFoodMeasure(food: FoodCandidate): FoodMeasure? =
    food.measures.firstOrNull { it.isDefault } ?: food.measures.firstOrNull()

internal fun variantTitle(food: FoodCandidate): String {
    val measure = primaryFoodMeasure(food)
    return if(measure != null) "1 ${measure.label}"
    else "${number(food.amount, 1)} ${unitLabel(food.unit)}"
}

internal fun variantCountLabel(count: Int): String {
    val suffix = if(count % 100 in 11..14) "вариантов" else when(count % 10) {
        1 -> "вариант"
        in 2..4 -> "варианта"
        else -> "вариантов"
    }
    return "$count $suffix"
}

@Composable
internal fun FoodSearchContent(foods: List<FoodCandidate>, searching: Boolean, onSearch: (String) -> Unit,
    cachedCount: Int = 0, catalogueLoading: Boolean = false, offline: Boolean = false,
    onDownload: () -> Unit = {}, catalogueError: String? = null,
    onSelect: (List<FoodCandidate>) -> Unit) {
    val query = rememberTextFieldState()
    val searchBar = rememberSearchBarState(initialValue = SearchBarValue.Expanded)
    val search by rememberUpdatedState(onSearch)
    val focus = LocalFocusManager.current
    val groups = remember(foods) { groupFoodSearchResults(foods) }
    LaunchedEffect(query) {
        snapshotFlow { query.text.toString() }.distinctUntilChanged().collect { search(it) }
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).imePadding()
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SearchBarDefaults.InputField(textFieldState = query, searchBarState = searchBar,
            onSearch = { focus.clearFocus() }, placeholder = { Text("Найти еду в каталоге") },
            leadingIcon = { SymbolIcon(JetMealSymbol.Search, null) }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(cachedCount>0) "$cachedCount продуктов · офлайн"
                else if(offline) "Нет офлайн-каталога" else "Каталог ещё не загружен",
                modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(catalogueLoading) LoadingIndicator(Modifier.size(32.dp))
            else TextButton(onClick=onDownload) { Text("Обновить") }
        }
        catalogueError?.let { message ->
            Text(message,style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (query.text.isBlank()) "Часто добавляете" else "Результаты поиска",
                style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
            if (searching) LoadingIndicator(Modifier.size(36.dp))
        }
        LazyColumn(Modifier.heightIn(min = 120.dp, max = 420.dp),
            contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!searching && groups.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (cachedCount==0 && offline) "Каталог недоступен без предварительной загрузки"
                        else if (query.text.isBlank()) "В каталоге пока пусто" else "Ничего не найдено",
                        style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(if(cachedCount==0 && offline) "Подключитесь к интернету и нажмите «Обновить», чтобы сохранить весь каталог."
                        else if (query.text.isBlank()) "Добавьте продукт через подключённые инструменты питания."
                        else "Попробуйте другое название или бренд.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(groups, key = { _, variants -> variants.first().foodId }) { index, variants ->
                val food = variants.first()
                SegmentedListItem(onClick = { focus.clearFocus(); onSelect(variants) },
                    shapes = ListItemDefaults.segmentedShapes(index, groups.size),
                    supportingContent = {
                        Text(listOfNotNull(compactFoodBrand(food),
                            if (variants.size > 1) variantCountLabel(variants.size)
                            else "${number(food.amount, 1)} ${unitLabel(food.unit)}").joinToString(" · "))
                    },
                    trailingContent = {
                        if(variants.size > 1) SymbolIcon(JetMealSymbol.Next, null, Modifier.size(24.dp))
                        else Text("${number(food.nutrition.calories)}\nккал",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }) {
                    Text(food.name, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Different nutrition/size variants belong inside a product, not in search results. */
@Composable
internal fun FoodVariantsContent(variants: List<FoodCandidate>,
    onSelect: (FoodCandidate) -> Unit, onBack: () -> Unit) {
    if (variants.isEmpty()) return
    Column(Modifier.fillMaxWidth().widthIn(max = 640.dp)
        .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) {
            SymbolIcon(JetMealSymbol.Back, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("К поиску")
        }
        Text(variants.first().name, style = MaterialTheme.typography.headlineSmallEmphasized)
        LazyColumn(Modifier.heightIn(min = 120.dp, max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 20.dp)) {
            itemsIndexed(variants, key = { _, variant -> variant.id }) { index, variant ->
                val measure = primaryFoodMeasure(variant)
                SegmentedListItem(onClick = { onSelect(variant) },
                    shapes = ListItemDefaults.segmentedShapes(index, variants.size),
                    supportingContent = if (measure != null) {{
                        Text("${number(measure.baseAmount, 1)} ${unitLabel(variant.unit)}")
                    }} else null,
                    trailingContent = {
                        Text("${number(variant.nutrition.calories)}\nккал",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                    }) {
                    Text(variantTitle(variant), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

internal fun measureSymbol(measure: FoodMeasure): JetMealSymbol = when(measure.key) {
    "egg_medium", "egg_large" -> JetMealSymbol.Protein
    "tsp", "tbsp" -> JetMealSymbol.Spoon
    else -> JetMealSymbol.Serving
}

@Composable
internal fun AmountContent(name: String, unit: String, amount: Double, basisAmount: Double, nutrition: Nutrition,
    estimated: Boolean, busy: Boolean, error: String?, onSave: (Double) -> Unit, onDelete: (() -> Unit)?,
    onSuccess: () -> Unit, onBack: (() -> Unit)?, successNotice: String? = null,
    measures: List<FoodMeasure> = emptyList(), measurePreferenceKey: String? = null,
    enteredMeasureKey: String? = null, enteredMeasureQuantity: Double? = null,
    enteredMeasureBaseAmount: Double? = null,
    onMeasuredSave: ((Double,ChosenMeasure)->Unit)? = null) {
    val context=LocalContext.current
    val prefs=remember {context.getSharedPreferences("jetmeal_measures",android.content.Context.MODE_PRIVATE)}
    val saved=remember(measurePreferenceKey) {
        measurePreferenceKey?.let {prefs.getString(it,null)}
    }
    val first=remember(measures,measurePreferenceKey,enteredMeasureKey) {
        measures.firstOrNull { it.key==enteredMeasureKey &&
            (enteredMeasureBaseAmount==null || kotlin.math.abs(it.baseAmount-enteredMeasureBaseAmount)<.002) }
            ?: measures.firstOrNull { it.key==saved }
            ?: measures.firstOrNull { it.isDefault }
    }
    var chosenId by rememberSaveable(name,basisAmount,measurePreferenceKey) {
        mutableStateOf(first?.id)
    }
    val chosen=measures.firstOrNull {it.id==chosenId}
    val initial=if(chosen!=null) {
        if(chosen.key==enteredMeasureKey && enteredMeasureQuantity!=null) enteredMeasureQuantity
        else amount/chosen.baseAmount
    } else amount
    var text by rememberSaveable(name, amount, measurePreferenceKey) { mutableStateOf(decimalInput(initial)) }
    var pending by remember { mutableStateOf(false) }
    val entered = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val measured = if(entered!=null && chosen!=null) runCatching {ChosenMeasure(chosen,entered)}.getOrNull() else null
    val quantity=if(measured!=null) measured.baseAmount else entered
    val scaled = quantity?.takeIf {it>0 && it<=1000000}?.let {
        runCatching { QuantityScaling.scale(basisAmount, nutrition, it) }.getOrNull() }
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
        if(measures.isNotEmpty()) {
            // Expressive connected toggle-button group: pictograms, not a second heading
            // or redundant weight/portion labels. Spoken descriptions remain accessible.
            val count = measures.size + 1
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                verticalAlignment = Alignment.CenterVertically) {
                ToggleButton(checked=chosen==null,
                    onCheckedChange={
                        text=decimalInput(quantity ?: amount)
                        chosenId=null
                    },
                    shapes=ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    enabled=!busy,
                    modifier=Modifier.weight(1f).heightIn(min=48.dp)
                        .semantics { role=Role.RadioButton; contentDescription="В ${unitLabel(unit)}" }) {
                    SymbolIcon(JetMealSymbol.Weight,null,Modifier.size(22.dp))
                }
                measures.forEachIndexed { index, measure ->
                    val symbol = measureSymbol(measure)
                    val repeated = measures.count { measureSymbol(it) == symbol } > 1
                    ToggleButton(checked=chosen?.id==measure.id,
                        onCheckedChange={
                            val current=quantity ?: amount
                            chosenId=measure.id
                            text=decimalInput(current/measure.baseAmount)
                        },
                        shapes=if(index==count-2) ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else ButtonGroupDefaults.connectedMiddleButtonShapes(),
                        enabled=!busy,
                        modifier=Modifier.weight(1f).heightIn(min=48.dp)
                            .semantics { role=Role.RadioButton; contentDescription=measure.label }) {
                        SymbolIcon(symbol,null,Modifier.size(22.dp))
                        if(repeated) {
                            Spacer(Modifier.width(6.dp))
                            Text(when(measure.key) {
                                "egg_medium" -> "M"
                                "egg_large" -> "L"
                                else -> measure.label
                            },style=MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        if (estimated) Text("Примерная пищевая ценность", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary)
        val step=when(chosen?.key) {
            "piece","serving","egg_medium","egg_large","package","slice" -> 1.0
            "tsp","tbsp" -> 0.5
            else -> 10.0
        }
        val minimum=when(chosen?.key) {
            "piece","serving","egg_medium","egg_large","package","slice" -> 1.0
            "tsp","tbsp" -> 0.5
            else -> 0.1
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),
            verticalAlignment=Alignment.CenterVertically) {
            OutlinedIconButton(
                onClick={text=decimalInput(((entered ?: step)-step).coerceAtLeast(minimum))},
                enabled=!busy && entered!=null && entered>minimum,
                modifier=Modifier.size(56.dp)) { Text("−") }
            TextField(text, { text = it },
                label = { Text("Количество (${chosen?.label ?: unitLabel(unit)})") }, singleLine = true,
                shape = TextFieldDefaults.roundedShape, colors = TextFieldDefaults.tonalColors(),
                enabled = !busy, isError = scaled == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).heightIn(min=56.dp))
            OutlinedIconButton(
                onClick={text=decimalInput((entered ?: 0.0)+step)},
                enabled=!busy,modifier=Modifier.size(56.dp)) { Text("+") }
        }
        if(scaled==null) Text("Введите количество больше нуля",
            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
        if(chosen!=null && quantity!=null) {
            Text("${if(chosen.approximate) "≈ " else ""}${number(quantity,1)} ${unitLabel(unit)} " +
                "(${number(entered ?: 0.0,1)} ${chosen.label})",
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        scaled?.let {
            val palette = nutritionColors()
            Surface(shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${number(it.calories)} ккал",
                        style = MaterialTheme.typography.headlineLargeEmphasized,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        MacroSummaryTile("Белки",it.protein,JetMealSymbol.Protein,palette.protein,
                            Modifier.weight(1f))
                        MacroSummaryTile("Жиры",it.fat,JetMealSymbol.Fat,palette.fat,
                            Modifier.weight(1f))
                        MacroSummaryTile("Углеводы",it.carbs,JetMealSymbol.Carbs,palette.carbs,
                            Modifier.weight(1f))
                    }
                }
            }
        }
        error?.let { EditorError(it) }
        Button(onClick = {
            quantity?.let {
                pending = true
                if(measured!=null && onMeasuredSave!=null) {
                    measurePreferenceKey?.let { key ->
                        prefs.edit().putString(key,measured.measure.key).apply()
                    }
                    onMeasuredSave(it,measured)
                } else {
                    measurePreferenceKey?.let { key -> prefs.edit().remove(key).apply() }
                    onSave(it)
                }
            }
        }, shapes = ButtonDefaults.shapes(),
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

/** Compact tonal Material 3 Expressive Surface, not a second progress indicator. */
@Composable
internal fun MacroSummaryTile(label: String, grams: Double, symbol: JetMealSymbol,
    palette: RingColors, modifier: Modifier = Modifier) {
    Surface(modifier=modifier.semantics(mergeDescendants=true) {
            contentDescription="$label: ${number(grams,1)} г"
        },
        shape=MaterialTheme.shapes.large, color=palette.container,
        contentColor=palette.onContainer) {
        Column(Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(
                0f to palette.container,
                1f to palette.start.copy(alpha=.38f)))
            .padding(horizontal=8.dp,vertical=10.dp),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(palette.container),
                contentAlignment=Alignment.Center) {
                SymbolIcon(symbol,null,Modifier.size(21.dp))
            }
            Text("${number(grams,1)} г",style=MaterialTheme.typography.titleSmallEmphasized,
                maxLines=1)
            Text(label,style=MaterialTheme.typography.labelSmall,maxLines=1)
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
