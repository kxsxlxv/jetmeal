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
    val foods: List<FoodCandidate> = emptyList(), val searching: Boolean = false, val notice: String? = null
)

@OptIn(SupabaseExperimental::class)
class JetMealViewModel(private val repository: SupabaseRepository?, private val saved: SavedStateHandle, private val widgetCoordinator: HeroWidgetCoordinator? = null) : ViewModel() {
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
        requireNotNull(repository).client.auth.signOut()
        widgetCoordinator?.showSignedOut()
        mutable.value = AppState(authLoading = false)
        saveNavigation()
    }

    /** Resume synchronizes the system timezone and refreshes external AI writes. */
    fun refresh() = refresh(showPullIndicator = false)
    fun pullToRefresh() = refresh(showPullIndicator = true)
    private fun refresh(showPullIndicator: Boolean) {
        if (repository?.client?.auth?.currentUserOrNull() == null) return
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            refreshInFlight = true
            mutable.update { it.copy(busy = true, refreshing = showPullIndicator, error = null) }
            try {
                val zone = ZoneId.systemDefault()
                repository.invalidateSearch()
                repository.syncTimezone(zone)
                ensureActive()
                if (generation != refreshGeneration) return@launch
                val newToday = LocalDate.now(zone)
                if (mutable.value.day == today && today != newToday) { mutable.update { it.copy(day = newToday, month = YearMonth.from(newToday)) }; saveNavigation() }
                today = newToday
                val snapshot = mutable.value
                val targets = repository.targets()
                ensureActive()
                if (generation != refreshGeneration) return@launch
                val weekStart = snapshot.day.minusDays((snapshot.day.dayOfWeek.value - 1).toLong())
                val visibleStart = TimelinePeriods.start(snapshot.scale, snapshot.day)
                // Include the complete preceding week to replay targets at month/quarter boundaries.
                val start = minOf(weekStart, visibleStart.minusDays(visibleStart.dayOfWeek.value - 1L))
                val end = maxOf(weekStart.plusDays(7), TimelinePeriods.endExclusive(snapshot.scale, snapshot.day))
                val all = repository.entries(start, end, zone)
                ensureActive()
                if (generation != refreshGeneration) return@launch
                val totals = all.groupBy { it.consumedAt.atZone(zone).toLocalDate() }
                    .mapValues { (_, entries) -> entries.sumOf { it.nutrition.calories } }
                val asOf = if (snapshot.scale == TimeScale.Day) snapshot.day else
                    when { weekStart.plusDays(6) < today -> weekStart.plusDays(6); weekStart > today -> weekStart; else -> today }
                val week = targets?.let { WeekBudget.calculate(asOf, it, totals) }
                val calendarTargets = if (targets == null) emptyMap() else totals.keys.associateWith {
                    WeekBudget.calculate(it, targets, totals).effectiveTarget
                }
                mutable.update { it.copy(entries = all.filter { row -> row.consumedAt.atZone(zone).toLocalDate() == snapshot.day },
                    targets = targets, week = week, monthCalories = totals,
                    monthTargets = calendarTargets) }
                widgetCoordinator?.updateFromLoaded(start, end, all, targets, zone)
                // The catalogue belongs to the Add/search flow. Loading and ranking it
                // here blocked the main thread while a newly opened day's Hero animated.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // Engines may surface canceled I/O as an IOException rather than CancellationException.
                ensureActive()
                repository.diagnostics?.failure(ConnectionOperation.Diary, error)
                if (generation == refreshGeneration) mutable.update { it.copy(error = userMessage(error)) }
            }
            finally {
                if (generation == refreshGeneration) {
                    refreshInFlight = false
                    mutable.update { it.copy(busy = writeInFlight, refreshing = false) }
                }
            }
        }
    }

    fun selectDestination(destination: Destination) {
        if (destination == Destination.Settings) openSettings()
        else setTimeScale(when(destination) { Destination.Week -> TimeScale.Week; Destination.Calendar -> TimeScale.Month; else -> TimeScale.Day })
    }
    fun openSettings() { mutable.update { it.copy(destination = Destination.Settings) }; saveNavigation() }
    fun closeSettings() { mutable.update { it.copy(destination = destinationFor(it.scale)) }; saveNavigation() }
    fun setTimeScale(scale: TimeScale) {
        mutable.update { it.copy(scale = scale, destination = destinationFor(scale), week = null) }
        saveNavigation(); refresh()
    }
    fun showPeriod(date: LocalDate) {
        mutable.update { it.copy(day = date, month = YearMonth.from(date), entries = if(it.day == date) it.entries else emptyList(),
            week = null, monthCalories = emptyMap(), monthTargets = emptyMap()) }
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
                val results = requireNotNull(tools).searchFood(query).data
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
    fun log(candidate: FoodCandidate, quantity: Double, meal: MealPeriod, date: LocalDate) = action {
        val zone = ZoneId.systemDefault()
        val consumedAt = if (date == LocalDate.now(zone)) Instant.now()
            else date.atTime(when (meal) { MealPeriod.Morning -> 8; MealPeriod.Day -> 13; MealPeriod.Evening -> 19; MealPeriod.Snack -> 15 }, 0)
                .atZone(zone).toInstant()
        requireNotNull(tools).logFood(LogFood(candidate.id, quantity, consumedAt, meal))
        widgetCoordinator?.requestSync()
        mutable.update { it.copy(notice = "Еда добавлена. Можно отменить.") }; refresh()
    }
    fun edit(entry: DiaryEntry, quantity: Double) = action {
        requireNotNull(tools).updateLog(LogCorrection(entry.id, quantity))
        widgetCoordinator?.requestSync()
        mutable.update { it.copy(notice = "Количество изменено. Можно отменить.") }; refresh()
    }
    fun delete(entry: DiaryEntry) = action {
        requireNotNull(tools).deleteLog(entry.id)
        widgetCoordinator?.requestSync()
        mutable.update { it.copy(notice = "Запись удалена. Можно отменить.") }; refresh()
    }
    fun undo() = action {
        requireNotNull(tools).undoLastAction(); widgetCoordinator?.requestSync()
        mutable.update { it.copy(notice = "Действие отменено.") }; refresh()
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
