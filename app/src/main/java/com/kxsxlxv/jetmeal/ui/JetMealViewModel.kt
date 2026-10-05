package com.kxsxlxv.jetmeal.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.kxsxlxv.jetmeal.data.*
import com.kxsxlxv.jetmeal.domain.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.time.*

enum class Destination { Today, Week, Calendar, Settings }
data class AppState(
    val authLoading: Boolean = true, val email: String? = null,
    val busy: Boolean = false, val error: String? = null, val day: LocalDate = LocalDate.now(),
    val destination: Destination = Destination.Today, val month: YearMonth = YearMonth.now(),
    val entries: List<DiaryEntry> = emptyList(), val targets: Targets? = null, val week: WeekState? = null,
    val monthCalories: Map<LocalDate, Double> = emptyMap(), val monthTargets: Map<LocalDate, Double> = emptyMap(),
    val foods: List<FoodCandidate> = emptyList(), val searching: Boolean = false, val notice: String? = null
)

class JetMealViewModel(private val repository: SupabaseRepository?, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(AppState(authLoading = repository != null,
        destination = saved.get<String>("destination")?.let { runCatching { Destination.valueOf(it) }.getOrNull() } ?: Destination.Today,
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

    init {
        if (repository != null) viewModelScope.launch {
            repository.client.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        mutable.update { it.copy(authLoading = false, email = repository.client.auth.currentUserOrNull()?.email) }
                        refresh()
                    }
                    is SessionStatus.NotAuthenticated -> {
                        refreshGeneration++; searchGeneration++
                        refreshJob?.cancel(); searchJob?.cancel()
                        repository.invalidateSearch()
                        refreshInFlight = false
                        mutable.value = AppState(authLoading = false, error = mutable.value.error)
                    }
                    is SessionStatus.RefreshFailure -> mutable.update { it.copy(authLoading = false,
                        error = "Session refresh failed. Check your connection or sign in again.") }
                    else -> Unit
                }
            }
        }
    }

    fun signIn(email: String, password: String) = action(ErrorOperation.SignIn) {
        requireNotNull(repository).signIn(email, password)
    }
    fun signOut() = action {
        refreshGeneration++; searchGeneration++
        refreshJob?.cancel(); searchJob?.cancel()
        refreshInFlight = false
        mutable.update { it.copy(searching = false) }
        requireNotNull(repository).client.auth.signOut()
        mutable.value = AppState(authLoading = false)
        saveNavigation()
    }

    /** Resume synchronizes the system timezone and refreshes external AI writes. */
    fun refresh() {
        if (repository?.client?.auth?.currentUserOrNull() == null) return
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            refreshInFlight = true
            mutable.update { it.copy(busy = true, error = null) }
            try {
                val zone = ZoneId.systemDefault()
                repository.invalidateSearch()
                repository.syncTimezone(zone)
                ensureActive()
                if (generation != refreshGeneration) return@launch
                val newToday = LocalDate.now(zone)
                if (mutable.value.day == today && today != newToday) { mutable.update { it.copy(day = newToday) }; saveNavigation() }
                today = newToday
                val snapshot = mutable.value
                val targets = repository.targets()
                ensureActive()
                if (generation != refreshGeneration) return@launch
                val monthStart = snapshot.month.atDay(1)
                val weekStart = snapshot.day.minusDays((snapshot.day.dayOfWeek.value - 1).toLong())
                val calendarStart = monthStart.minusDays((monthStart.dayOfWeek.value - 1).toLong())
                val start = minOf(weekStart, calendarStart)
                val end = maxOf(weekStart.plusDays(7), snapshot.month.plusMonths(1).atDay(1))
                val all = repository.entries(start, end, zone)
                ensureActive()
                if (generation != refreshGeneration) return@launch
                val totals = all.groupBy { it.consumedAt.atZone(zone).toLocalDate() }
                    .mapValues { (_, entries) -> entries.sumOf { it.nutrition.calories } }
                val week = targets?.let { WeekBudget.calculate(snapshot.day, it, totals) }
                val calendarTargets = if (targets == null) emptyMap() else totals.keys.associateWith {
                    WeekBudget.calculate(it, targets, totals).effectiveTarget
                }
                mutable.update { it.copy(entries = all.filter { row -> row.consumedAt.atZone(zone).toLocalDate() == snapshot.day },
                    targets = targets, week = week, monthCalories = totals.filterKeys { YearMonth.from(it) == snapshot.month },
                    monthTargets = calendarTargets) }
                search("")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // Engines may surface canceled I/O as an IOException rather than CancellationException.
                ensureActive()
                if (generation == refreshGeneration) mutable.update { it.copy(error = userMessage(error)) }
            }
            finally {
                if (generation == refreshGeneration) {
                    refreshInFlight = false
                    mutable.update { it.copy(busy = writeInFlight) }
                }
            }
        }
    }

    fun selectDestination(destination: Destination) {
        val date = if (destination in listOf(Destination.Today, Destination.Week)) LocalDate.now() else mutable.value.day
        mutable.update { it.copy(destination = destination, day = date,
            entries = if (it.day == date) it.entries else emptyList(), week = if (it.day == date) it.week else null) }
        saveNavigation()
        refresh()
    }
    fun openDate(date: LocalDate) { mutable.update { it.copy(day = date, destination = Destination.Today,
        entries = if (it.day == date) it.entries else emptyList(), week = if (it.day == date) it.week else null) }; saveNavigation(); refresh() }
    fun setMonth(month: YearMonth) { mutable.update { it.copy(month = month, monthCalories = emptyMap(), monthTargets = emptyMap()) }; saveNavigation(); refresh() }
    private fun saveNavigation() {
        saved["destination"] = mutable.value.destination.name
        saved["day"] = mutable.value.day.toString()
        saved["month"] = mutable.value.month.toString()
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
        mutable.update { it.copy(notice = "Food logged. Undo is available.") }; refresh()
    }
    fun edit(entry: DiaryEntry, quantity: Double) = action {
        requireNotNull(tools).updateLog(LogCorrection(entry.id, quantity))
        mutable.update { it.copy(notice = "Quantity updated. Undo is available.") }; refresh()
    }
    fun delete(entry: DiaryEntry) = action {
        requireNotNull(tools).deleteLog(entry.id)
        mutable.update { it.copy(notice = "Entry removed. Undo is available.") }; refresh()
    }
    fun undo() = action {
        requireNotNull(tools).undoLastAction(); mutable.update { it.copy(notice = "Action undone.") }; refresh()
    }
    /** Invoked only by the explicit human target-review confirmation action. */
    fun saveTargets(targets: Targets) = action {
        val application = requireNotNull(tools)
        application.updateTargets(targets, application.confirmedByUser(targets))
        mutable.update { it.copy(notice = "Targets saved.") }; refresh()
    }
    fun dismissError() = mutable.update { it.copy(error = null, notice = null) }
    private fun action(operation: ErrorOperation = ErrorOperation.Data, block: suspend () -> Unit) = viewModelScope.launch {
        if (!writes.tryLock()) return@launch
        writeInFlight = true
        mutable.update { it.copy(busy = true, error = null, notice = null) }
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            ensureActive()
            mutable.update { it.copy(error = UserErrors.message(error, operation)) }
        }
        finally { writes.unlock(); writeInFlight = false; mutable.update { it.copy(busy = refreshInFlight) } }
    }
    private fun userMessage(error: Exception): String = UserErrors.message(error)
}
