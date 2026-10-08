@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.kxsxlxv.jetmeal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.kxsxlxv.jetmeal.BuildConfig
import com.kxsxlxv.jetmeal.domain.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Serializable private enum class MainScreen : NavKey { Timeline, Settings }
private sealed interface Editor {
    data class Add(val meal: MealPeriod) : Editor
    data class Edit(val entry: DiaryEntry) : Editor
}

@Composable fun JetMealApp(viewModel: JetMealViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf<Editor?>(null) }
    var chosenFood by remember { mutableStateOf<FoodCandidate?>(null) }
    val backStack = rememberNavBackStack(MainScreen.Timeline)
    val settings = state.destination == Destination.Settings
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(settings) {
        if(settings && backStack.lastOrNull()!=MainScreen.Settings) backStack.add(MainScreen.Settings)
        if(!settings) while(backStack.size>1) backStack.removeAt(backStack.lastIndex)
    }
    BackHandler(settings && editor==null) { viewModel.closeSettings() }
    LaunchedEffect(state.error,state.notice) {
        val message = state.error ?: state.notice ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val undoable = state.notice?.contains("Можно отменить") == true && state.error==null
        val result = snackbar.showSnackbar(message,
            actionLabel=if(state.error!=null && !state.authRecovering) "Повторить" else if(undoable) "Отменить" else null,
            withDismissAction=true, duration=if(state.error!=null) SnackbarDuration.Indefinite else SnackbarDuration.Long)
        if(result==SnackbarResult.ActionPerformed) {
            if(undoable) viewModel.undo() else viewModel.refresh()
        } else viewModel.dismissError()
    }
    Surface(Modifier.fillMaxSize()) {
        when {
            BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_KEY.isBlank() -> ConfigurationContent()
            state.authLoading -> LoadingContent("Восстанавливаем сессию…")
            state.authRecovering && state.email == null -> SessionRecoveryContent(state.error ?: UserErrors.SESSION_RECOVERING)
            state.email==null -> AuthContent(state,viewModel::signIn)
            else -> Scaffold(
                topBar={ if(settings) TopAppBar(
                    title={ Text("Настройки",style=MaterialTheme.typography.titleLargeEmphasized) },
                    navigationIcon={ IconButton(onClick=viewModel::closeSettings) { SymbolIcon(JetMealSymbol.Back,"Назад") } },
                    colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface)
                ) },
                snackbarHost={ SnackbarHost(snackbar) },
            ) { insets ->
                Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                    if(settings && state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    NavDisplay(backStack=backStack,onBack=viewModel::closeSettings,
                        modifier=Modifier.fillMaxSize(),entryProvider=entryProvider {
                            entry<MainScreen> { screen -> when(screen) {
                                MainScreen.Settings -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
                                    Box(Modifier.widthIn(max=760.dp)) { SettingsContent(state.targets,state.email.orEmpty(),state.busy,viewModel::saveTargets,viewModel::signOut,
                                        targetVersions=state.targetVersions, weights=state.weightMeasurements,
                                        weightGoal=state.weightGoal, onSetWeightGoal=viewModel::setWeightGoal,
                                        picoocConnected=state.picoocConnected, weightLoading=state.weightLoading,
                                        onAddWeight=viewModel::addWeight, onConnectPicooc=viewModel::connectPicooc,
                                        onSyncPicooc=viewModel::syncPicooc, onDisconnectPicooc=viewModel::disconnectPicooc) }
                                }
                                MainScreen.Timeline -> NutritionTimeline(state,viewModel,
                                    onAdd={ viewModel.dismissError(); editor=Editor.Add(it); chosenFood=null; viewModel.search("") },
                                    onEdit={ viewModel.dismissError(); editor=Editor.Edit(it) })
                            } }
                        })
                }
            }
        }
        if(state.email!=null && editor!=null) {
            val writeBusy by rememberUpdatedState(state.busy)
            ModalBottomSheet(onDismissRequest={ if(!state.busy) { editor=null; chosenFood=null } },
                sheetState=rememberBottomSheetState(initialValue=SheetValue.Hidden,
                    enabledValues=setOf(SheetValue.Hidden,SheetValue.Expanded),confirmValueChange={it!=SheetValue.Hidden || !writeBusy}),
                sheetGesturesEnabled=!state.busy) {
                Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding(),horizontalAlignment=Alignment.CenterHorizontally) {
                    val current=editor
                    if(current is Editor.Add) Text("Добавить · ${current.meal.label()}",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(bottom=8.dp))
                    if(current is Editor.Add && chosenFood==null) FoodSearchContent(state.foods,state.searching,viewModel::search) { chosenFood=it }
                    else {
                        val food=chosenFood
                        val entry=(current as? Editor.Edit)?.entry
                        if(food!=null || entry!=null) AmountContent(
                            name=food?.name ?: entry!!.name,unit=food?.unit ?: entry!!.unit,
                            amount=food?.amount ?: entry!!.quantity,basisAmount=food?.amount ?: entry!!.basisAmount,
                            nutrition=food?.nutrition ?: entry!!.basisNutrition,estimated=food?.estimated ?: entry?.estimated ?: false,
                            busy=state.busy,error=state.error,successNotice=state.notice,
                            onSave={ quantity -> if(entry!=null) viewModel.edit(entry,quantity) else if(food!=null && current is Editor.Add) viewModel.log(food,quantity,current.meal,state.day) },
                            onDelete=entry?.let { { viewModel.delete(it) } },onSuccess={editor=null;chosenFood=null},
                            onBack=if(food!=null) ({ chosenFood=null }) else null)
                    }
                }
            }
        }
    }
}

@Composable private fun NutritionTimeline(state:AppState,model:JetMealViewModel,onAdd:(MealPeriod)->Unit,onEdit:(DiaryEntry)->Unit) {
    PullToRefreshBox(
        isRefreshing=state.refreshing,
        onRefresh=model::pullToRefresh,
        modifier=Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally) {
            Row(
                Modifier.widthIn(max=760.dp).fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp),
                verticalAlignment=Alignment.CenterVertically,
            ) {
                ScaleButtonGroup(TimeScale.entries.toList(),state.scale,model::setTimeScale,Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick={ model.openSettings() },
                    shapes=IconButtonDefaults.shapes(),
                    modifier=Modifier.size(48.dp),
                ) { SymbolIcon(JetMealSymbol.Settings,"Настройки") }
            }
            val effects=MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            AnimatedContent(state.scale,modifier=Modifier.weight(1f),label="Масштаб времени",
                transitionSpec={fadeIn(effects) togetherWith fadeOut(effects)}) { scale ->
                key(scale) { PeriodPager(state.copy(scale=scale),model,onAdd,onEdit) }
            }
        }
    }
}

@Composable private fun ScaleButtonGroup(
    options:List<TimeScale>,
    selected:TimeScale,
    onSelect:(TimeScale)->Unit,
    modifier:Modifier=Modifier,
) {
    val interactions=remember(options) { options.map {MutableInteractionSource()} }
    ButtonGroup(overflowIndicator={menu->ButtonGroupDefaults.OverflowIndicator(menu)},
        modifier=modifier,horizontalArrangement=Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed {index,scale->
            customItem(buttonGroupContent={
                ToggleButton(checked=selected==scale,onCheckedChange={if(it) onSelect(scale)},
                    shapes=when(index) {
                        0->ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex->ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else->ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    contentPadding=PaddingValues(horizontal=8.dp,vertical=10.dp),
                    interactionSource=interactions[index],
                    modifier=Modifier.animateWidth(interactions[index],compressionLimit=8.dp)
                        .heightIn(min=48.dp).semantics {role=Role.RadioButton}) {
                    Text(scale.label,style=MaterialTheme.typography.labelLarge,maxLines=1,softWrap=false)
                }
            },menuContent={menu->
                DropdownMenuItem(text={Text(scale.label)},onClick={onSelect(scale);menu.dismiss()})
            })
        }
    }
}

@Composable private fun PeriodPager(state:AppState,model:JetMealViewModel,onAdd:(MealPeriod)->Unit,onEdit:(DiaryEntry)->Unit) {
    val middle=5000
    val originIso=rememberSaveable { state.day.toString() }
    val origin=LocalDate.parse(originIso)
    val pager=rememberPagerState(initialPage=middle) {10001}
    val scope=rememberCoroutineScope()
    fun pageFor(date:LocalDate):Int {
        val start=TimelinePeriods.start(state.scale,origin)
        val target=TimelinePeriods.start(state.scale,date)
        val offset=when(state.scale) {TimeScale.Day->ChronoUnit.DAYS.between(start,target); TimeScale.Week->ChronoUnit.WEEKS.between(start,target); TimeScale.Month->ChronoUnit.MONTHS.between(start,target); TimeScale.Quarter->ChronoUnit.MONTHS.between(start,target)/3}
        return (middle+offset).toInt().coerceIn(0,10000)
    }
    LaunchedEffect(pager.settledPage) {
        val date=TimelinePeriods.move(state.scale,origin,pager.settledPage-middle)
        if(TimelinePeriods.start(state.scale,state.day)!=date) model.showPeriod(date)
    }
    LaunchedEffect(state.day) {
        val target=pageFor(state.day)
        if(target!=pager.settledPage && !pager.isScrollInProgress) pager.animateScrollToPage(target)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(TimelinePeriods.title(state.scale,state.day),style=MaterialTheme.typography.titleMediumEmphasized,modifier=Modifier.weight(1f))
            IconButton(onClick={scope.launch {pager.animateScrollToPage((pager.settledPage-1).coerceAtLeast(0))}}) { SymbolIcon(JetMealSymbol.Previous,"Предыдущий период") }
            IconButton(onClick={scope.launch {pager.animateScrollToPage((pager.settledPage+1).coerceAtMost(10000))}}) { SymbolIcon(JetMealSymbol.Next,"Следующий период") }
            IconButton(onClick=model::resetPeriod) { SymbolIcon(JetMealSymbol.Reset,"Текущий период") }
        }
        HorizontalPager(pager,modifier=Modifier.fillMaxSize().semantics {contentDescription="Шкала питания. Листайте периоды влево или вправо"},key={it}) { page ->
            val date=TimelinePeriods.move(state.scale,origin,page-middle)
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
                if(date!=TimelinePeriods.start(state.scale,state.day) || (state.busy && state.week==null)) LoadingContent("Загружаем период…")
                else Box(Modifier.widthIn(max=1000.dp).fillMaxSize()) { when(state.scale) {
                    TimeScale.Day -> DayContent(state.day,state.entries,
                        state.targetVersions.lastOrNull { it.date <= state.day }?.targets ?: state.targets,
                        state.week,onAdd,onEdit,model::openSettings,
                        confirmedZero=state.day in state.confirmedZeroDays,onZeroDay={model.setZeroDay(state.day,it)},busy=state.busy)
                    TimeScale.Week -> WeekContent(state.week,model::openSettings,onDay=model::openDate)
                    TimeScale.Month -> CalendarContent(java.time.YearMonth.from(state.day),state.monthCalories,state.monthTargets,model::setMonth,model::openDate,selectedDate=state.day,confirmedZeroDays=state.confirmedZeroDays)
                    TimeScale.Quarter -> QuarterContent(date,state.monthCalories,state.monthTargets,model::openDate,confirmedZeroDays=state.confirmedZeroDays)
                } }
            }
        }
    }
}

@Composable private fun ConfigurationContent() {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),verticalArrangement=Arrangement.Center) {
        Text("Добро пожаловать в JetMeal",style=MaterialTheme.typography.headlineLargeEmphasized)
        Text("Подключите проект Supabase, чтобы открыть дневник питания.",modifier=Modifier.padding(top=16.dp))
        Text("Укажите адрес проекта и публичный ключ в локальной конфигурации разработчика и пересоберите приложение.",modifier=Modifier.padding(top=12.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun LoadingContent(message:String) {
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        LoadingIndicator(); Spacer(Modifier.height(16.dp)); Text(message)
    }
}
