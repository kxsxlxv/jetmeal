package com.kxsxlxv.jetmeal.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kxsxlxv.jetmeal.data.*
import com.kxsxlxv.jetmeal.domain.*
import com.kxsxlxv.jetmeal.widget.HeroWidgetCoordinator
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.event.AuthEvent
import io.github.jan.supabase.auth.status.RefreshFailureCause
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.time.*

enum class Destination { Today, Week, Calendar, Settings }
data class AppState(
    val authLoading: Boolean = true, val email: String? = null, val authRecovering: Boolean = false,
    val busy: Boolean = false, val refreshing: Boolean = false, val error: String? = null, val day: LocalDate = LocalDate.now(),
    val destination: Destination = Destination.Today, val month: YearMonth = YearMonth.now(),
    val scale: TimeScale = TimeScale.Day,
    val entries: List<DiaryEntry> = emptyList(), val targets: Targets? = null, val week: WeekState? = null,
    val monthCalories: Map<LocalDate, Double> = emptyMap(), val monthTargets: Map<LocalDate, Double> = emptyMap(),
    val confirmedZeroDays: Set<LocalDate> = emptySet(),
    val targetVersions: List<TargetVersion> = emptyList(),
    val weightMeasurements: List<WeightMeasurement> = emptyList(),
    val weightGoal: WeightGoal? = null,
    val budgetTargets: Map<LocalDate,Targets> = emptyMap(),
    val cachedOffline: Boolean = false,
    val pendingWrites: Int = 0,
    val blockedWrites: Int = 0,
    val picoocConnected: Boolean = false, val weightLoading: Boolean = false,
    val foods: List<FoodCandidate> = emptyList(), val searching: Boolean = false, val notice: String? = null
)

@OptIn(SupabaseExperimental::class)
class JetMealViewModel(private val repository: SupabaseRepository?, private val saved: SavedStateHandle, private val widgetCoordinator: HeroWidgetCoordinator? = null,
    private val picooc: PicoocIntegration? = null,
    private val offline: OfflineDiary? = null) : ViewModel() {
    private val mutable = MutableStateFlow(AppState(authLoading = repository != null,
        destination = saved.get<String>("destination")?.let { runCatching { Destination.valueOf(it) }.getOrNull() } ?: Destination.Today,
        scale = saved.get<String>("scale")?.let { runCatching { TimeScale.valueOf(it) }.getOrNull() } ?: TimeScale.Day,
        day = saved.get<String>("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(),
        month = saved.get<String>("month")?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.now()))
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private val tools = repository?.let(::NutritionTools)
    private val writes = Mutex()
    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var today = LocalDate.now()
    private var writeInFlight = false
    private var refreshInFlight = false
    private var refreshGeneration = 0
    private var searchGeneration = 0
    private var sessionRefreshError: String? = null
    private var authenticatedOwnerId: String? = null

    init {
        if (repository != null) viewModelScope.launch {
            repository.client.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val previous = mutable.value
                        val email = status.session.user?.email
                        val ownerId = status.session.user?.id
                        val reload = previous.authLoading || previous.authRecovering || authenticatedOwnerId != ownerId || status.isNew
                        authenticatedOwnerId = ownerId
                        repository.authFailures.clear()
                        repository.diagnostics?.authState(ConnectionAuthState.Authenticated)
                        sessionRefreshError = null
                        mutable.update { it.copy(authLoading = false, authRecovering = false, email = email,
                            error = if (previous.authRecovering) null else it.error) }
                        // A rotated JWT is not a changed diary snapshot. Reloading here used
                        // to cancel foreground refreshes and restart all progress animations.
                        if (reload) {
                            widgetCoordinator?.requestSync()
                            refresh()
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        authenticatedOwnerId = null
                        refreshGeneration++; searchGeneration++
                        refreshJob?.cancel(); searchJob?.cancel()
                        repository.invalidateSearch()
                        refreshInFlight = false
                        val ended = repository.authFailures.latest?.let(UserErrors::sessionEndedMessage)
                        repository.diagnostics?.authState(ConnectionAuthState.NotAuthenticated)
                        mutable.value = AppState(authLoading = false, error = ended ?: mutable.value.error)
                    }
                    is SessionStatus.RefreshFailure -> {
                        repository.diagnostics?.authState(ConnectionAuthState.Recovering)
                        mutable.update { it.copy(authLoading = false, authRecovering = true,
                            error = sessionRefreshError ?: repository.authFailures.latest?.let(UserErrors::sessionRecoveryMessage)
                                ?: UserErrors.SESSION_RECOVERING) }
                    }
                    else -> Unit
                }
            }
        }
        if (repository != null) viewModelScope.launch {
            repository.client.auth.events.collect { event ->
                if (event is AuthEvent.RefreshFailure &&
                    repository.client.auth.sessionStatus.value is SessionStatus.RefreshFailure) {
                    val error = when (val cause = event.cause) {
                        is RefreshFailureCause.NetworkError -> cause.exception
                        is RefreshFailureCause.InternalServerError -> cause.exception
                    }
                    repository.diagnostics?.failure(ConnectionOperation.Auth, error)
                    sessionRefreshError = UserErrors.sessionRecoveryMessage(repository.authFailures.resolve(error))
                    mutable.update { if (it.authRecovering) it.copy(error = sessionRefreshError) else it }
                }
            }
        }
    }

    fun signIn(email: String, password: String) = action(ErrorOperation.SignIn) {
        requireNotNull(repository).signIn(email, password)
    }
    fun signOut() = action(allowDuringRecovery = true) {
        repository?.authFailures?.clear()
        repository?.diagnostics?.authState(ConnectionAuthState.SignOutRequested)
        refreshGeneration++; searchGeneration++
        refreshJob?.cancel(); searchJob?.cancel()
        refreshInFlight = false
        mutable.update { it.copy(searching = false) }
        if ((offline?.status()?.remaining ?: 0) > 0) {
            mutable.update { it.copy(error =
                "В дневнике есть несинхронизированные изменения. Сначала отправьте их в Supabase или отмените.") }
            return@action
        }
        picooc?.disconnect()
        requireNotNull(repository).client.auth.signOut()
        widgetCoordinator?.showSignedOut()
        mutable.value = AppState(authLoading = false)
        saveNavigation()
    }

    /** Resume synchronizes the system timezone and refreshes external AI writes. */
    fun refresh() = refresh(showPullIndicator = false)
    fun pullToRefresh() = refresh(showPullIndicator = true)
    private fun refresh(showPullIndicator: Boolean) {
        if(repository?.client?.auth?.currentUserOrNull()==null) return
        val generation=++refreshGeneration
        refreshJob?.cancel()
        refreshJob=viewModelScope.launch {
            refreshInFlight=true
            val navigation=mutable.value
            mutable.update { it.copy(busy=true,refreshing=showPullIndicator,error=null) }
            try {
                val zone=ZoneId.systemDefault()
                if(offline!=null && !offline.onlineNow()) throw java.io.IOException("Network unavailable")
                offline?.sync()
                repository.invalidateSearch()
                repository.syncTimezone(zone)
                ensureActive()
                if(generation!=refreshGeneration) return@launch
                val newToday=LocalDate.now(zone)
                if(mutable.value.day==today && today!=newToday) {
                    mutable.update { it.copy(day=newToday,month=YearMonth.from(newToday)) }
                    saveNavigation()
                }
                today=newToday
                val nav=mutable.value
                val weekStart=nav.day.minusDays((nav.day.dayOfWeek.value-1).toLong())
                val visibleStart=TimelinePeriods.start(nav.scale,nav.day)
                val start=minOf(weekStart,visibleStart.minusDays(visibleStart.dayOfWeek.value-1L))
                val end=maxOf(weekStart.plusDays(7),TimelinePeriods.endExclusive(nav.scale,nav.day))
                val targets=repository.targets()
                val history=repository.targetHistory()
                ensureActive()
                if(generation!=refreshGeneration) return@launch
                val entries=repository.entries(start,end,zone)
                ensureActive()
                if(generation!=refreshGeneration) return@launch
                val zero=repository.confirmedZeroDays(start,end)
                val snap=OfflineSnapshot(start,end,entries,targets,history,zero)
                offline?.remember(snap)
                ensureActive()
                if(generation!=refreshGeneration) return@launch
                displaySnapshot(snap,nav,false)
                if(nav.destination==Destination.Settings) loadWeights()
                val perDay=weekTargets(snap)
                widgetCoordinator?.updateFromLoaded(start,end,entries,targets,zone,zero,perDay)
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) {
                ensureActive()
                val cached=runCatching { offline?.cachedSnapshot() }.getOrNull()
                val requested=mutable.value
                val cachedValid=cached?.let { validCacheFor(it,requested) } ?: false
                if(cachedValid && cached!=null) {
                    displaySnapshot(cached,requested,true)
                    // A stale read is never silently presented as a live Supabase response.
                } else {
                    repository.diagnostics?.failure(ConnectionOperation.Diary,error)
                    if(generation==refreshGeneration)
                        mutable.update {it.copy(error=
                            "Нет соединения или сохранённых данных для этого периода. " +
                            "Подключитесь к интернету и обновите дневник.")}
                }
            } finally {
                if(generation==refreshGeneration) {
                    refreshInFlight=false
                    mutable.update { it.copy(busy=writeInFlight,refreshing=false) }
                }
            }
        }
    }

    private fun weekTargets(snap:OfflineSnapshot):Map<LocalDate,Targets> {
        val dates=generateSequence(snap.start){it.plusDays(1)}.takeWhile {it<snap.end}
        return dates.mapNotNull {date ->
            (snap.history.lastOrNull {it.date<=date}?.targets ?: snap.targets)
                ?.let{date to it}
        }.toMap()
    }

    private fun validCacheFor(snap:OfflineSnapshot,nav:AppState):Boolean {
        val weekStart=nav.day.minusDays((nav.day.dayOfWeek.value-1).toLong())
        val visibleStart=TimelinePeriods.start(nav.scale,nav.day)
        val start=minOf(weekStart,visibleStart.minusDays(visibleStart.dayOfWeek.value-1L))
        val end=maxOf(weekStart.plusDays(7),TimelinePeriods.endExclusive(nav.scale,nav.day))
        return snap.start<=start && snap.end>=end
    }

    private suspend fun displaySnapshot(snap:OfflineSnapshot,nav:AppState,cached:Boolean) {
        val zone=ZoneId.systemDefault()
        val all=offline?.overlay(snap.entries) ?: snap.entries
        val zero=snap.zeroDays.filterNotTo(mutableSetOf()) {date ->
            all.any {it.consumedAt.atZone(zone).toLocalDate()==date}
        }
        val totals=all.groupBy {it.consumedAt.atZone(zone).toLocalDate()}
            .mapValues {(_,entries)->entries.sumOf{it.nutrition.calories}}
        val knownTotals=totals+zero.filterNot{it in totals}.associateWith{0.0}
        val dailyTargets=weekTargets(snap)
        fun targetFor(date:LocalDate)=dailyTargets[date] ?: snap.targets
        val weekStart=nav.day.minusDays((nav.day.dayOfWeek.value-1).toLong())
        val asOf=if(nav.scale==TimeScale.Day) nav.day else
            when {
                weekStart.plusDays(6)<today -> weekStart.plusDays(6)
                weekStart>today -> weekStart
                else -> today
            }
        val week=targetFor(asOf)?.let {
            WeekBudget.calculate(asOf,it,totals,zero,
                asOfDayCompleted=asOf<today,dailyTargets=dailyTargets)
        }
        val monthTargets=if(snap.targets==null) emptyMap() else knownTotals.keys.associateWith {
            WeekBudget.calculate(it,targetFor(it) ?: snap.targets,totals,zero,
                dailyTargets=dailyTargets).effectiveTarget
        }
        val pendingStatus=offline?.status()
        mutable.update {it.copy(
            entries=all.filter {row->row.consumedAt.atZone(zone).toLocalDate()==nav.day},
            targets=snap.targets,week=week,monthCalories=totals,monthTargets=monthTargets,
            confirmedZeroDays=zero,targetVersions=snap.history,budgetTargets=dailyTargets,
            cachedOffline=cached,pendingWrites=pendingStatus?.remaining ?: 0,
            blockedWrites=pendingStatus?.blocked ?: 0,
        )}
    }

    fun syncPending() {
        viewModelScope.launch {
            try {
                val result=offline?.sync()
                if(result!=null && result.blocked>0)
                    mutable.update{it.copy(error="Есть конфликтующие офлайн-изменения. Проверьте очередь перед удалением.")}
                refresh()
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                mutable.update{it.copy(error=userMessage(error))}
            }
        }
    }
    fun discardBlocked() {
        viewModelScope.launch {
            try {
                offline?.discardBlocked()
                refresh()
            } catch(error:Exception) {
                if(error is CancellationException) throw error
                mutable.update{it.copy(error=userMessage(error))}
            }
        }
    }

    fun selectDestination(destination: Destination) {
        if (destination == Destination.Settings) openSettings()
        else setTimeScale(when(destination) { Destination.Week -> TimeScale.Week; Destination.Calendar -> TimeScale.Month; else -> TimeScale.Day })
    }
    fun openSettings() {
        mutable.update { it.copy(destination = Destination.Settings) }
        saveNavigation()
        refresh()
        loadWeights()
    }

    fun loadWeights() {
        if (repository?.client?.auth?.currentUserOrNull() == null) return
        viewModelScope.launch {
            mutable.update { it.copy(weightLoading = true) }
            try {
                val owner = requireNotNull(repository.client.auth.currentUserOrNull()).id
                val measurements = repository.weights(
                    LocalDate.now().minusDays(180), LocalDate.now().plusDays(1), ZoneId.systemDefault())
                val goal = repository.weightGoal()
                mutable.update { it.copy(weightMeasurements = measurements, weightGoal = goal,
                    picoocConnected = picooc?.connected(owner) == true) }
            } catch (error: Exception) {
                if (error !is CancellationException) mutable.update { it.copy(error = userMessage(error)) }
            } finally { mutable.update { it.copy(weightLoading = false) } }
        }
    }

    fun setWeightGoal(targetKg: Double,deadline: LocalDate) = action {
        val latest = mutable.value.weightMeasurements.maxByOrNull { it.measuredAt }
            ?: throw IllegalStateException("Сначала запишите актуальный вес.")
        requireNotNull(repository).setWeightGoal(latest.kilograms,targetKg,deadline)
        mutable.update { it.copy(notice = "Цель веса сохранена.") }
        loadWeights()
    }

    fun addWeight(value: Double) = action {
        requireNotNull(repository).logWeight(value, Instant.now())
        mutable.update { it.copy(notice = "Вес записан.") }
        loadWeights()
    }

    fun connectPicooc(email: String, password: String, profileName: String) = action {
        val count = requireNotNull(picooc).connect(email,password,profileName)
        mutable.update { it.copy(notice = "PICOOC подключён. Загружено измерений: $count.") }
        loadWeights()
    }

    fun syncPicooc() = action {
        val count = requireNotNull(picooc).sync()
        mutable.update { it.copy(notice = "PICOOC: проверено измерений $count.") }
        loadWeights()
    }

    fun disconnectPicooc() {
        picooc?.disconnect()
        mutable.update { it.copy(picoocConnected = false, notice = "PICOOC отключён.") }
    }
    fun closeSettings() { mutable.update { it.copy(destination = destinationFor(it.scale)) }; saveNavigation() }
    fun setTimeScale(scale: TimeScale) {
        mutable.update { it.copy(scale = scale, destination = destinationFor(scale), week = null) }
        saveNavigation(); refresh()
    }
    fun showPeriod(date: LocalDate) {
        mutable.update { it.copy(day = date, month = YearMonth.from(date), entries = if(it.day == date) it.entries else emptyList(),
            week = null, monthCalories = emptyMap(), monthTargets = emptyMap(), confirmedZeroDays = emptySet()) }
        saveNavigation(); refresh()
    }
    fun movePeriod(offset: Int) = showPeriod(TimelinePeriods.move(mutable.value.scale, mutable.value.day, offset))
    fun resetPeriod() = showPeriod(LocalDate.now())
    fun openDate(date: LocalDate) {
        mutable.update { it.copy(scale = TimeScale.Day, destination = Destination.Today) }
        showPeriod(date)
    }
    fun setMonth(month: YearMonth) { mutable.update { it.copy(scale = TimeScale.Month, destination = Destination.Calendar) }; showPeriod(month.atDay(1)) }
    private fun destinationFor(scale: TimeScale): Destination = when(scale) { TimeScale.Day -> Destination.Today; TimeScale.Week -> Destination.Week; else -> Destination.Calendar }
    private fun saveNavigation() {
        saved["destination"] = mutable.value.destination.name
        saved["day"] = mutable.value.day.toString()
        saved["month"] = mutable.value.month.toString()
        saved["scale"] = mutable.value.scale.name
    }
    fun search(query: String) {
        val generation = ++searchGeneration
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutable.update { it.copy(searching = true) }
            try {
                if (query.isNotBlank()) delay(180)
                val results = try {
                    if(offline!=null && !offline.onlineNow()) throw java.io.IOException("Offline")
                    requireNotNull(tools).searchFood(query).data.also {
                        if(offline!=null) offline.rememberFoods(requireNotNull(repository).rankedCatalogue())
                    }
                } catch(failure:Exception) {
                    if(failure is CancellationException) throw failure
                    val cached = offline?.cachedFoods(query).orEmpty()
                    if(cached.isEmpty()) throw failure
                    cached
                }
                ensureActive()
                if (generation != searchGeneration) return@launch
                mutable.update { it.copy(foods = results, searching = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive()
                repository?.diagnostics?.failure(ConnectionOperation.Catalogue, error)
                if (generation == searchGeneration) mutable.update { it.copy(error = userMessage(error)) }
            }
            finally {
                if (generation == searchGeneration) mutable.update { it.copy(searching = false) }
            }
        }
    }
    fun setZeroDay(date: LocalDate, confirmed: Boolean) = action {
        require(date < LocalDate.now(ZoneId.systemDefault())) { "Only past days can be confirmed." }
        requireNotNull(tools).confirmZeroDay(date, confirmed)
        widgetCoordinator?.requestSync()
        mutable.update { it.copy(notice = if (confirmed) "Подтверждено: 0 ккал за день." else "Подтверждение нулевого дня снято.") }
        refresh()
    }

    private suspend fun persistQueued() {
        val result=offline?.sync()
        val pending=result?.remaining ?: 0
        mutable.update {
            it.copy(
                notice=if(pending==0) "Изменение сохранено в Supabase. Можно отменить."
                    else "Сохранено на телефоне: $pending действий ожидают синхронизации.",
                pendingWrites=pending,blockedWrites=result?.blocked ?: 0)
        }
        refresh()
    }

    fun log(candidate: FoodCandidate,quantity: Double,meal: MealPeriod,date: LocalDate) =
        action(allowDuringRecovery=true) {
            val zone=ZoneId.systemDefault()
            val at=if(date==LocalDate.now(zone)) Instant.now() else date.atTime(
                when(meal) {MealPeriod.Morning->8;MealPeriod.Day->13;
                    MealPeriod.Evening->19;MealPeriod.Snack->15},0).atZone(zone).toInstant()
            requireNotNull(offline).enqueueLog(candidate,quantity,at,meal)
            persistQueued()
        }
    fun edit(entry: DiaryEntry,quantity: Double)=action(allowDuringRecovery=true) {
        requireNotNull(offline).enqueueEdit(entry,quantity)
        persistQueued()
    }
    fun delete(entry: DiaryEntry)=action(allowDuringRecovery=true) {
        requireNotNull(offline).enqueueDelete(entry)
        persistQueued()
    }
    fun undo()=action(allowDuringRecovery=true) {
        val outstanding=offline?.status()?.remaining ?: 0
        if(outstanding>0) {
            if(offline?.cancelLast()==true) {
                mutable.update {it.copy(notice="Неотправленное действие отменено.")}
            } else {
                mutable.update {it.copy(error=
                    "Действие уже могло поступить на сервер. Сначала синхронизируйте очередь, " +
                    "чтобы отмена не привела к потере записи.")}
                return@action
            }
        } else {
            requireNotNull(tools).undoLastAction()
            mutable.update{it.copy(notice="Действие отменено.")}
        }
        widgetCoordinator?.requestSync()
        refresh()
    }
    /** Invoked only by the explicit human target-review confirmation action. */
    fun saveTargets(targets: Targets) = action {
        val application = requireNotNull(tools)
        application.updateTargets(targets, application.confirmedByUser(targets))
        widgetCoordinator?.requestSync()
        mutable.update { it.copy(notice = "Цели сохранены.") }; refresh()
    }
    fun dismissError() = mutable.update { it.copy(error = null, notice = null) }
    private fun action(operation: ErrorOperation = ErrorOperation.Data, allowDuringRecovery: Boolean = false, block: suspend () -> Unit) = viewModelScope.launch {
        if (!allowDuringRecovery && operation == ErrorOperation.Data && mutable.value.authRecovering) {
            mutable.update { it.copy(error = sessionRefreshError ?: UserErrors.SESSION_RECOVERING) }
            return@launch
        }
        if (!writes.tryLock()) return@launch
        writeInFlight = true
        mutable.update { it.copy(busy = true, error = null, notice = null) }
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            ensureActive()
            repository?.diagnostics?.failure(if (operation == ErrorOperation.SignIn) ConnectionOperation.SignIn else ConnectionOperation.Mutation, error)
            mutable.update { it.copy(error = if (it.authRecovering) sessionRefreshError ?: UserErrors.SESSION_RECOVERING
                else UserErrors.message(error, operation)) }
        }
        finally { writes.unlock(); writeInFlight = false; mutable.update { it.copy(busy = refreshInFlight) } }
    }
    private fun userMessage(error: Exception): String = if (mutable.value.authRecovering)
        sessionRefreshError ?: UserErrors.SESSION_RECOVERING else UserErrors.message(error)
}
