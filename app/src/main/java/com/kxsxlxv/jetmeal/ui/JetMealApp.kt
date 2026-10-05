@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.kxsxlxv.jetmeal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.kxsxlxv.jetmeal.BuildConfig
import com.kxsxlxv.jetmeal.domain.*
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

@Serializable
private data class ScreenKey(val destination: String, val date: String? = null) : NavKey

private sealed interface Editor {
    data class Add(val meal: MealPeriod) : Editor
    data class Edit(val entry: DiaryEntry) : Editor
}

@Composable
fun JetMealApp(viewModel: JetMealViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf<Editor?>(null) }
    var chosenFood by remember { mutableStateOf<FoodCandidate?>(null) }
    val backStack = rememberNavBackStack(ScreenKey(Destination.Today.name))
    val today = LocalDate.now()
    val activeKey = ScreenKey(state.destination.name, state.day.takeIf {
        state.destination == Destination.Today && it != today
    }?.toString())

    LaunchedEffect(activeKey) {
        if (backStack.lastOrNull() != activeKey) {
            if (activeKey.date != null && (backStack.lastOrNull() as? ScreenKey)?.destination == Destination.Calendar.name) {
                backStack.add(activeKey)
            } else {
                backStack.clear()
                backStack.add(activeKey)
            }
        }
    }
    val historical = state.destination == Destination.Today && state.day != today
    fun backToCalendar() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        viewModel.selectDestination(Destination.Calendar)
    }
    BackHandler(historical && editor == null) { backToCalendar() }

    Surface(Modifier.fillMaxSize()) {
        when {
            BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_KEY.isBlank() ->
                ConfigurationContent()
            state.authLoading -> LoadingContent("Restoring your account…")
            state.email == null -> AuthContent(state, viewModel::signIn)
            else -> {
                val windowWidth = with(LocalDensity.current) {
                    LocalWindowInfo.current.containerSize.width.toDp()
                }
                val wide = windowWidth >= 600.dp
                val navigation: @Composable () -> Unit = {
                    JetMealNavigation(state.destination, wide) { destination ->
                        editor = null
                        chosenFood = null
                        if (destination == Destination.Today) viewModel.openDate(today)
                        else viewModel.selectDestination(destination)
                    }
                }
                Row(Modifier.fillMaxSize()) {
                    if (wide) navigation()
                    Scaffold(
                        modifier = Modifier.weight(1f),
                        topBar = {
                            TopAppBar(
                                title = {
                                    Column {
                                        Text(if (historical) state.day.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
                                            else state.destination.name,
                                            style = MaterialTheme.typography.titleLargeEmphasized)
                                        Text("JetMeal", style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                navigationIcon = {
                                    if (historical) TextButton(onClick = { backToCalendar() }) { Text("Back") }
                                },
                                actions = {
                                    TextButton(onClick = viewModel::refresh, enabled = !state.busy) { Text("Refresh") }
                                },
                            )
                        },
                        bottomBar = { if (!wide) navigation() },
                    ) { insets ->
                        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            state.error?.let { ErrorBanner(it, viewModel::dismissError) }
                            state.notice?.let { notice ->
                                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    if (notice.contains("Undo is available")) {
                                        TextButton(onClick = viewModel::undo, enabled = !state.busy) { Text("Undo") }
                                    TextButton(onClick = viewModel::dismissError, enabled = !state.busy) { Text("Dismiss") }
                                    }
                                    TextButton(onClick = viewModel::dismissError) { Text("Dismiss") }
                                }
                            }
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                NavDisplay(
                                    backStack = backStack,
                                    onBack = { if (historical) backToCalendar() },
                                    modifier = Modifier.widthIn(max = 900.dp).fillMaxSize(),
                                    entryProvider = entryProvider {
                                        entry<ScreenKey> { key ->
                                            when (Destination.valueOf(key.destination)) {
                                                Destination.Today -> DayContent(
                                                    state.day, state.entries, state.targets, state.week,
                                                    onAdd = { viewModel.dismissError(); editor = Editor.Add(it); chosenFood = null; viewModel.search("") },
                                                    onEdit = { viewModel.dismissError(); editor = Editor.Edit(it) },
                                                    onTargets = { viewModel.selectDestination(Destination.Settings) },
                                                )
                                                Destination.Week -> WeekContent(state.week) {
                                                    viewModel.selectDestination(Destination.Settings)
                                                }
                                                Destination.Calendar -> CalendarContent(state.month, state.monthCalories,
                                                    state.monthTargets, viewModel::setMonth, viewModel::openDate)
                                                Destination.Settings -> SettingsContent(state.targets, state.email.orEmpty(),
                                                    state.busy, viewModel::saveTargets, viewModel::signOut)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (state.email != null && editor != null) {
            val writeBusy by rememberUpdatedState(state.busy)
            ModalBottomSheet(onDismissRequest = { if (!state.busy) { editor = null; chosenFood = null } },
                sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                    confirmValueChange = { it != SheetValue.Hidden || !writeBusy }),
                sheetGesturesEnabled = !state.busy) {
                Box(Modifier.fillMaxWidth().imePadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
                    val current = editor
                    if (current is Editor.Add && chosenFood == null) {
                        FoodSearchContent(state.foods, state.searching, viewModel::search) { chosenFood = it }
                    } else {
                        val food = chosenFood
                        val entry = (current as? Editor.Edit)?.entry
                        if (food != null || entry != null) AmountContent(
                            name = food?.name ?: entry!!.name,
                            unit = food?.unit ?: entry!!.unit,
                            amount = food?.amount ?: entry!!.quantity,
                            basisAmount = food?.amount ?: entry!!.basisAmount,
                            nutrition = food?.nutrition ?: entry!!.basisNutrition,
                            estimated = food?.estimated ?: entry?.estimated ?: false,
                            busy = state.busy,
                            error = state.error,
                            successNotice = state.notice,
                            onSave = { quantity ->
                                if (entry != null) viewModel.edit(entry, quantity)
                                else if (food != null && current is Editor.Add)
                                    viewModel.log(food, quantity, current.meal, state.day)
                            },
                            onDelete = entry?.let { { viewModel.delete(it) } },
                            onSuccess = { editor = null; chosenFood = null },
                            onBack = if (food != null) ({ chosenFood = null }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JetMealNavigation(selected: Destination, wide: Boolean, onSelect: (Destination) -> Unit) {
    if (wide) {
        WideNavigationRail(state = rememberWideNavigationRailState(initialValue = WideNavigationRailValue.Expanded)) {
            Destination.entries.forEach { destination ->
                WideNavigationRailItem(selected == destination, { onSelect(destination) },
                    railExpanded = true,
                    icon = { NavigationIcon(destination) },
                    label = { Text(destination.name) })
            }
        }
    } else {
        ShortNavigationBar {
            Destination.entries.forEach { destination ->
                ShortNavigationBarItem(selected == destination, { onSelect(destination) },
                    icon = { NavigationIcon(destination) },
                    label = { Text(destination.name) })
            }
        }
    }
}

@Composable
private fun NavigationIcon(destination: Destination) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val stroke = Stroke(1.8f * unit)
        when (destination) {
            Destination.Today -> {
                drawCircle(color, 8f * unit, center, style = stroke)
                drawCircle(color, 3f * unit, center)
            }
            Destination.Week -> listOf(6f to 10f, 12f to 5f, 18f to 8f).forEach { (x, y) ->
                drawRoundRect(color, Offset((x - 2) * unit, y * unit), Size(4 * unit, (20 - y) * unit),
                    androidx.compose.ui.geometry.CornerRadius(unit))
            }
            Destination.Calendar -> {
                drawRoundRect(color, Offset(3 * unit, 5 * unit), Size(18 * unit, 16 * unit),
                    androidx.compose.ui.geometry.CornerRadius(2 * unit), style = stroke)
                drawLine(color, Offset(3 * unit, 10 * unit), Offset(21 * unit, 10 * unit), strokeWidth = 1.8f * unit)
                drawLine(color, Offset(8 * unit, 3 * unit), Offset(8 * unit, 7 * unit), strokeWidth = 1.8f * unit)
                drawLine(color, Offset(16 * unit, 3 * unit), Offset(16 * unit, 7 * unit), strokeWidth = 1.8f * unit)
            }
            Destination.Settings -> {
                drawCircle(color, 6f * unit, center, style = stroke)
                drawCircle(color, 2f * unit, center, style = stroke)
                repeat(8) { i ->
                    val angle = i * Math.PI / 4
                    drawLine(color, center + Offset((6 * kotlin.math.cos(angle)).toFloat() * unit,
                        (6 * kotlin.math.sin(angle)).toFloat() * unit),
                        center + Offset((9 * kotlin.math.cos(angle)).toFloat() * unit,
                            (9 * kotlin.math.sin(angle)).toFloat() * unit), strokeWidth = 2.6f * unit)
                }
            }
        }
    }
}

@Composable
private fun ConfigurationContent() {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Welcome to JetMeal", style = MaterialTheme.typography.headlineLargeEmphasized)
        Spacer(Modifier.height(16.dp))
        Text("Connect your Supabase project to use your food diary.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text("Set supabase.url and supabase.publishableKey in jetmeal.local.properties, then rebuild the app.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingContent(message: String) {
    Column(Modifier.fillMaxSize().safeDrawingPadding(), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        LoadingIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message)
    }
}

@Composable
private fun ErrorBanner(message: String, dismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
        TextButton(onClick = dismiss) { Text("Dismiss") }
    }
}

@Composable
fun DayContent(date: LocalDate, entries: List<DiaryEntry>, targets: Targets?, week: WeekState?,
    onAdd: (MealPeriod) -> Unit, onEdit: (DiaryEntry) -> Unit, onTargets: () -> Unit) {
    val total = entries.fold(Nutrition.Zero) { sum, entry -> sum + entry.nutrition }
    val effective = week?.days?.firstOrNull { it.date == date }?.target ?: targets?.calories
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            if (targets == null) TargetsPrompt(onTargets)
            else NutritionSummary(total, targets, effective ?: targets.calories)
        }
        MealPeriod.entries.forEach { meal ->
            item(key = "heading-${meal.name}") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(meal.name, style = MaterialTheme.typography.titleLargeEmphasized,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    FilledTonalButton(onClick = { onAdd(meal) }, shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Add food to ${meal.name}"
                        }) { Text("+ Add") }
                }
            }
            val mealEntries = entries.filter { it.meal == meal }
            if (mealEntries.isEmpty()) item(key = "empty-${meal.name}") {
                Text("No food logged", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
            }
            items(mealEntries, key = { it.id }) { entry ->
                DiaryRow(entry, onClick = { onEdit(entry) }, modifier = Modifier.animateItem())
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun NutritionSummary(total: Nutrition, targets: Targets, effective: Double) {
    val progress by animateFloatAsState(calorieProgress(total.calories, effective),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "Daily calorie progress")
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.shapes.extraLarge).padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${number(total.calories)} / ${number(effective)} kcal",
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(if (total.calories <= effective) "${number(effective - total.calories)} kcal remaining"
                    else "${number(total.calories - effective)} kcal above today's allowance",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MacroLabel("Protein", total.protein, targets.protein)
            MacroLabel("Fat", total.fat, targets.fat)
            MacroLabel("Carbs", total.carbs, targets.carbs)
        }
    }
}

@Composable
private fun MacroLabel(label: String, value: Double, target: Double) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text("${number(value, 1)} / ${number(target)} g", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(if (value <= target) "${number(target - value, 1)} g left" else "${number(value - target, 1)} g above",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun DiaryRow(entry: DiaryEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(onClick = onClick, modifier = modifier.fillMaxWidth(),
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
        overlineContent = { Text("${number(entry.quantity, 1)} ${entry.unit}" + if (entry.estimated) " · Estimated" else "") },
        supportingContent = { Text("Protein ${number(entry.nutrition.protein, 1)} g · Fat ${number(entry.nutrition.fat, 1)} g · Carbs ${number(entry.nutrition.carbs, 1)} g") },
        trailingContent = { Text("${number(entry.nutrition.calories)}\nkcal", style = MaterialTheme.typography.titleMediumEmphasized) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Text(entry.name + (entry.brand?.let { " · $it" } ?: "")) }
}

@Composable
private fun TargetsPrompt(onTargets: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.shapes.extraLarge).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Set your nutrition targets", style = MaterialTheme.typography.titleLargeEmphasized)
        Text("Enter the calorie and macro values you have already decided. JetMeal will keep your weekly allowance in view.")
        Button(onClick = onTargets, shapes = ButtonDefaults.shapes()) { Text("Open target settings") }
    }
}

@Composable
fun WeekContent(week: WeekState?, onTargets: () -> Unit) {
    if (week == null) {
        Column(Modifier.padding(16.dp)) { TargetsPrompt(onTargets) }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.shapes.extraLarge).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${week.start.format(DateTimeFormatter.ofPattern("d MMM"))} – ${week.end.format(DateTimeFormatter.ofPattern("d MMM"))}",
                    style = MaterialTheme.typography.titleMedium)
                Text("${number(week.totalConsumed)} / ${number(week.baseBudget)} kcal",
                    style = MaterialTheme.typography.headlineSmallEmphasized)
                Text("${week.remainingDays} days remain · ${number(week.effectiveTarget)} kcal current daily allowance")
                Text("Completed-day deviation: ${signed(week.deviation)} kcal", style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(week.days, key = { it.date }) { day ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer,
                MaterialTheme.shapes.large).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(day.date.format(DateTimeFormatter.ofPattern("EEE, d")), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMediumEmphasized)
                    Text("${number(day.actual)} / ${number(day.target)} kcal", style = MaterialTheme.typography.bodyMedium)
                }
                LinearProgressIndicator(progress = { calorieProgress(day.actual, day.target) }, Modifier.fillMaxWidth())
            }
        }
        item {
            Text("Only calories are redistributed. Macro targets stay fixed. Your budget resets every Monday.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (abs(week.residual) > 0.5) {
                Spacer(Modifier.height(10.dp))
                Text("Adjustment limit leaves ${signed(week.residual)} kcal unreconciled if the remaining days meet their allowance. This does not carry into next week.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Provisional visual mapping only; never used by budget arithmetic or persisted in the database. */
internal fun adherenceLabel(actual: Double, target: Double?): String = when {
    target == null || target < 0 -> "Target unavailable"
    target == 0.0 -> if (actual == 0.0) "Close to allowance" else "Outside allowance"
    abs(actual - target) / target <= 0.10 -> "Close to allowance"
    abs(actual - target) / target <= 0.25 -> "Near allowance"
    else -> "Outside allowance"
}

@Composable
fun CalendarContent(month: YearMonth, calories: Map<LocalDate, Double>, targets: Map<LocalDate, Double>,
    onMonth: (YearMonth) -> Unit, onDate: (LocalDate) -> Unit) {
    val firstOffset = month.atDay(1).dayOfWeek.value - 1
    val cells = (firstOffset + month.lengthOfMonth() + 6) / 7 * 7
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onMonth(month.minusMonths(1)) }) { Text("Previous") }
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f),
                style = MaterialTheme.typography.titleLargeEmphasized)
            TextButton(onClick = { onMonth(month.plusMonths(1)) }) { Text("Next") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gridWidth = maxOf(maxWidth, 348.dp)
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(gridWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                    repeat(cells / 7) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(7) { column ->
                                val dayNumber = row * 7 + column - firstOffset + 1
                                if (dayNumber !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(66.dp))
                                else {
                                    val date = month.atDay(dayNumber)
                                    val amount = calories[date]
                                    val status = amount?.let { adherenceLabel(it, targets[date]) }
                                    val container = when (status) {
                                        "Close to allowance" -> Color(0xFFD9EED3)
                                        "Near allowance" -> Color(0xFFFFE6AD)
                                        "Outside allowance" -> MaterialTheme.colorScheme.errorContainer
                                        else -> MaterialTheme.colorScheme.surfaceContainer
                                    }
                                    val foreground = when (status) {
                                        "Close to allowance" -> Color(0xFF233F22)
                                        "Near allowance" -> Color(0xFF503B09)
                                        "Outside allowance" -> MaterialTheme.colorScheme.onErrorContainer
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                    Surface(onClick = { onDate(date) }, shape = MaterialTheme.shapes.medium,
                                    color = container, contentColor = foreground,
                                    modifier = Modifier.weight(1f).heightIn(min = 66.dp).semantics {
                                        contentDescription = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")) +
                                        (amount?.let { ", ${number(it)} kilocalories, $status" } ?: ", no food logged")
                                    }) {
                                        Column(Modifier.padding(vertical = 10.dp, horizontal = 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(dayNumber.toString(), style = MaterialTheme.typography.titleSmallEmphasized)
                                            amount?.let { Text(number(it), style = MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("Logged days show kcal. Green: close to allowance; amber: near; red: outside. Tap a date to open its diary.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun FoodSearchContent(foods: List<FoodCandidate>, searching: Boolean, onSearch: (String) -> Unit,
    onSelect: (FoodCandidate) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("Add food", style = MaterialTheme.typography.headlineSmallEmphasized)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(query, { query = it; onSearch(it) }, label = { Text("Search your foods") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text(if (query.isBlank()) "Frequently used" else "Catalogue matches", style = MaterialTheme.typography.titleMediumEmphasized)
        LazyColumn(Modifier.heightIn(min = 140.dp, max = 440.dp), contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (searching) item { LoadingIndicator() }
            else if (foods.isEmpty()) item {
                Text(if (query.isBlank()) "Your catalogue is empty. Add foods through your connected Nutrition Tools to start logging."
                    else "No matching foods. Try a name, brand or source.",
                    modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(foods, key = { it.id }) { food ->
                ListItem(onClick = { onSelect(food) },
                    shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
                    supportingContent = {
                        Text(listOfNotNull(food.brand, food.source, "${number(food.amount, 1)} ${food.unit}").joinToString(" · "))
                    },
                    trailingContent = { Text("${number(food.nutrition.calories)}\nkcal", style = MaterialTheme.typography.labelLarge) },
                ) { Text(food.name) }
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
        if (pending && !busy && error != null) {
            pending = false
        } else if (pending && !busy && successNotice?.contains("Undo is available") == true && error == null) {
            onSuccess()
            pending = false
        }
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        onBack?.let { TextButton(onClick = it, enabled = !busy) { Text("Back to foods") } }
        Text(name, style = MaterialTheme.typography.headlineSmallEmphasized)
        Text("Nutrition basis: ${number(basisAmount, 1)} $unit", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (estimated) Text("Estimated nutrition", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(text, { text = it }, label = { Text("Quantity ($unit)") }, singleLine = true,
            enabled = !busy, isError = scaled == null, supportingText = { if (scaled == null) Text("Enter a positive, valid quantity") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        scaled?.let {
            Text("${number(it.calories)} kcal", style = MaterialTheme.typography.headlineSmallEmphasized)
            Text("Protein ${number(it.protein, 1)} g · Fat ${number(it.fat, 1)} g · Carbs ${number(it.carbs, 1)} g")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { quantity?.let { pending = true; onSave(it) } }, shapes = ButtonDefaults.shapes(),
            enabled = scaled != null && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(if (onDelete == null) "Confirm food" else "Save quantity")
        }
        onDelete?.let { action ->
            TextButton(onClick = { pending = true; action() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Delete entry", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun SettingsContent(targets: Targets?, email: String, busy: Boolean, onSave: (Targets) -> Unit, onSignOut: () -> Unit) {
    var calories by rememberSaveable(targets) { mutableStateOf(targets?.calories?.let(::decimalInput) ?: "") }
    var protein by rememberSaveable(targets) { mutableStateOf(targets?.protein?.let(::decimalInput) ?: "") }
    var fat by rememberSaveable(targets) { mutableStateOf(targets?.fat?.let(::decimalInput) ?: "") }
    var carbs by rememberSaveable(targets) { mutableStateOf(targets?.carbs?.let(::decimalInput) ?: "") }
    var limit by rememberSaveable(targets) { mutableStateOf(decimalInput((targets?.limitRatio ?: 0.10) * 100)) }
    var review by remember { mutableStateOf<Targets?>(null) }
    val proposed = runCatching {
        Targets(parseInput(calories), parseInput(protein), parseInput(fat), parseInput(carbs), parseInput(limit) / 100)
    }.getOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Daily targets", style = MaterialTheme.typography.headlineSmallEmphasized)
        Text("Use values you have already decided. Only calories adjust across the week.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TargetField("Base daily calories (kcal)", calories, { calories = it }, !busy)
        TargetField("Protein (g)", protein, { protein = it }, !busy)
        TargetField("Fat (g)", fat, { fat = it }, !busy)
        TargetField("Carbohydrates (g)", carbs, { carbs = it }, !busy)
        TargetField("Weekly adjustment limit (±%)", limit, { limit = it }, !busy)
        Text("The limit is symmetric, from 0% to 100%. Any unreconciled variance resets on Monday.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { review = proposed }, shapes = ButtonDefaults.shapes(), enabled = proposed != null && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Review target changes") }
        HorizontalDivider()
        Text("Your account", style = MaterialTheme.typography.titleLargeEmphasized)
        Text(email)
        Text("Timezone and appearance follow your Android system settings.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onSignOut, enabled = !busy) { Text("Sign out") }
    }
    review?.let { values ->
        AlertDialog(onDismissRequest = { review = null }, title = { Text("Confirm your targets") },
            text = { Text("Daily calories: ${number(values.calories)} kcal\nProtein: ${number(values.protein, 1)} g\nFat: ${number(values.fat, 1)} g\nCarbs: ${number(values.carbs, 1)} g\nWeekly adjustment: ±${number(values.limitRatio * 100, 1)}%") },
            confirmButton = { Button(onClick = { review = null; onSave(values) }, shapes = ButtonDefaults.shapes()) { Text("Save targets") } },
            dismissButton = { TextButton(onClick = { review = null }) { Text("Keep editing") } })
    }
}

@Composable
private fun TargetField(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(value, onValue, label = { Text(label) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

private fun parseInput(text: String): Double = text.trim().replace(',', '.').toDouble()
private fun decimalInput(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
private fun number(value: Double, decimals: Int = 0): String = String.format(Locale.getDefault(), "%.${decimals}f", value)
private fun signed(value: Double): String = (if (value > 0) "+" else "") + number(value)
private fun calorieProgress(actual: Double, target: Double): Float =
    if (target > 0) (actual / target).toFloat().coerceIn(0f, 1f) else if (actual > 0) 1f else 0f
