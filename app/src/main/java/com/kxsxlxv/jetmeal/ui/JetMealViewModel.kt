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
    val weekNutrition: Nutrition = Nutrition.Zero,
    val confirmedZeroDays: Set<LocalDate> = emptySet(),
    val targetVersions: List<TargetVersion> = emptyList(),
    val weightMeasurements: List<WeightMeasurement> = emptyList(),
    val weightGoal: WeightGoal? = null,
    val budgetTargets: Map<LocalDate,Targets> = emptyMap(),
    val cachedOffline: Boolean = false,
    val connectionWarning: String? = null,
    val catalogueCount: Int = 0,
    val catalogueMeasures: Map<String,List<FoodMeasure>> = emptyMap(),
    val catalogueLoading: Boolean = false,
    val catalogueError: String? = null,
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
    private var cachedNavigationJob: Job? = null
    private val recentSnapshots = ArrayDeque<OfflineSnapshot>()
    private var snapshotOwner: String? = null
    private var searchJob: Job? = null
    private var retryJob: Job? = null
    private var catalogueJob: Job? = null
    private var offlineHistoryJob: Job? = null
    private var offlineHistoryOwner: String? = null
    private var catalogueOwner: String? = null
    private var retryAttempt = 0
    private var today = LocalDate.now()
    private var writeInFlight = false
    private var refreshInFlight = false
    private var refreshGeneration = 0
    private var searchGeneration = 0
    private var searchMeal: MealPeriod? = null
    private var latestSearchQuery = ""
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
                        if (authenticatedOwnerId != ownerId) {
                            recentSnapshots.clear()
                            snapshotOwner = ownerId
                            cachedNavigationJob?.cancel()
                            catalogueJob?.cancel()
                            offlineHistoryJob?.cancel()
                            offlineHistoryOwner = null
                            catalogueOwner = null
                        }
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
                            restoreCatalogueCount()
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        authenticatedOwnerId = null
                        snapshotOwner = null
                        recentSnapshots.clear()
                        cachedNavigationJob?.cancel()
                        retryJob?.cancel(); catalogueJob?.cancel()
                        offlineHistoryJob?.cancel(); offlineHistoryOwner = null
                        catalogueOwner = null
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

    init {
        if (offline != null) viewModelScope.launch {
            offline.networkChanges().collect { available ->
                val loggedIn = repository?.client?.auth?.currentUserOrNull() != null
                if (!loggedIn) return@collect
                if (available && (mutable.value.cachedOffline || mutable.value.connectionWarning!=null)) {
                    retryJob?.cancel()
                    retryAttempt = 0
                    refresh()
                }
            }
        }
    }

    private fun restoreCatalogueCount() {
        val diary = offline ?: return
        viewModelScope.launch {
            val count = runCatching { diary.catalogueCount() }.getOrNull() ?: return@launch
            val measures = runCatching {diary.cachedMeasures()}.getOrDefault(emptyMap())
            mutable.update { it.copy(catalogueCount = count,catalogueMeasures=measures) }
        }
    }

    /** Warm *all* personal food variants without delaying the Hero or timeline. */
    private fun warmCatalogue(force: Boolean = false) {
        val repo = repository ?: return
        val diary = offline ?: return
        val ownerId = repo.client.auth.currentUserOrNull()?.id ?: return
        if (catalogueJob?.isActive == true) return
        if (!force && catalogueOwner == ownerId) return
        catalogueJob = viewModelScope.launch(Dispatchers.Default) {
            mutable.update { it.copy(catalogueLoading = true, catalogueError=null) }
            try {
                val count = diary.catalogueCount()
                if (repo.client.auth.currentUserOrNull()?.id != ownerId) return@launch
                mutable.update { it.copy(catalogueCount = count) }
                if (!diary.onlineNow()) {
                    mutable.update { it.copy(catalogueError=
                        "Нет подключения: каталог загрузится, когда появится интернет.") }
                    return@launch
                }
                if (force) repo.invalidateSearch()
                val foods = repo.rankedCatalogue()
                ensureActive()
                if (repo.client.auth.currentUserOrNull()?.id != ownerId) return@launch
                diary.rememberFoods(foods)
                catalogueOwner = ownerId
                mutable.update { it.copy(catalogueCount = foods.map { it.foodId }.distinct().size,
                    catalogueMeasures=foods.associate { it.id to it.measures },catalogueError=null) }
                // Previously an already-open food search kept showing the old
                // recommendation list even after catalogue refresh succeeded.
                if (searchMeal != null) search(latestSearchQuery)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                repo.diagnostics?.failure(ConnectionOperation.Catalogue, error)
                mutable.update { it.copy(catalogueError=
                    "Не удалось загрузить каталог с Supabase. Сохранённые продукты доступны; повторите позже.") }
            } finally {
                if (repo.client.auth.currentUserOrNull()?.id == ownerId)
                    mutable.update { it.copy(catalogueLoading = false) }
            }
        }
    }

    fun downloadCatalogue() = warmCatalogue(force = true)

    /** Prefetch six calendar months for offline Day/Week/Month/Quarter
     * navigation without delaying the visible timeline or food search.
     * Snapshots are merged instead of replacing one another. */
    private fun prepareOfflineHistory(seed: OfflineSnapshot) {
        val repo=repository ?: return
        val diary=offline ?: return
        val owner=repo.client.auth.currentUserOrNull()?.id ?: return
        if (offlineHistoryOwner==owner || offlineHistoryJob?.isActive==true) return
        offlineHistoryJob=viewModelScope.launch(Dispatchers.IO) {
            try {
                val zone=ZoneId.systemDefault()
                val current=LocalDate.now(zone)
                val quarterStart=TimelinePeriods.start(TimeScale.Quarter,current)
                // Include Monday-based budget context around both quarters.
                val start=quarterStart.minusMonths(3).minusDays(7)
                val end=quarterStart.plusMonths(3).plusDays(7)
                // Avoid a redundant fetch if another period already filled it.
                val cached=diary.cachedSnapshotFor(start,end)
                if (cached == null && diary.onlineNow()) {
                    val entries=repo.entries(start,end,zone)
                    val zero=repo.confirmedZeroDays(start,end)
                    ensureActive()
                    if (repo.client.auth.currentUserOrNull()?.id != owner) return@launch
                    // Persist the exact server coverage; leave previously visited
                    // date intervals and all unsent mutations untouched.
                    diary.remember(OfflineSnapshot(start,end,entries,
                        seed.targets,seed.history,zero))
                }
                offlineHistoryOwner=owner
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error:Exception) {
                repo.diagnostics?.failure(ConnectionOperation.Diary,error,ConnectionStage.Cache)
            }
        }
    }

    private fun scheduleConnectionRetry() {
        val diary = offline ?: return
        if (!diary.onlineNow()) return
        if (retryJob?.isActive == true) return
        val seconds = minOf(60L, 3L * (1L shl retryAttempt.coerceAtMost(5)))
        retryAttempt++
        retryJob = viewModelScope.launch {
            delay(seconds * 1_000L)
            if (diary.onlineNow() &&
                (mutable.value.cachedOffline || mutable.value.connectionWarning != null) &&
                repository?.client?.auth?.currentUserOrNull() != null) refresh()
        }
    }

    fun signIn(email: String, password: String) = action(ErrorOperation.SignIn) {
        requireNotNull(repository).signIn(email, password)
    }
    fun signOut() = action(allowDuringRecovery = true) {
        repository?.authFailures?.clear()
        repository?.diagnostics?.authState(ConnectionAuthState.SignOutRequested)
        retryJob?.cancel(); catalogueJob?.cancel()
        offlineHistoryJob?.cancel(); offlineHistoryOwner = null
        refreshGeneration++; searchGeneration++
        refreshJob?.cancel(); searchJob?.cancel()
        cachedNavigationJob?.cancel()
        recentSnapshots.clear(); snapshotOwner = null
        searchMeal = null
        latestSearchQuery = ""
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
        cachedNavigationJob?.cancel()
        val generation=++refreshGeneration
        refreshJob?.cancel()
        refreshJob=viewModelScope.launch {
            var stage = ConnectionStage.Profile
            refreshInFlight=true
            val navigation=mutable.value
            mutable.update { it.copy(busy=true,refreshing=showPullIndicator,error=null) }
            try {
                // Do not wait for a doomed HTTP/DNS request when Android has no
                // validated network. This also works on cold app startup.
                if(offline?.onlineNow()==false) {
                    val nav=mutable.value
                    val (cacheStart,cacheEnd)=requiredRange(nav)
                    val cached=offline.cachedSnapshotFor(cacheStart,cacheEnd)
                    if(cached!=null) {
                        displaySnapshot(cached,nav,true)
                        mutable.update { it.copy(connectionWarning=
                            "Нет подключения к интернету. Показаны сохранённые данные.") }
                    } else displayUncachedPending(nav)
                    return@launch
                }
                val zone=ZoneId.systemDefault()
                // Treat the Supabase request, not Android's network icon, as
                // the source of truth for server availability. Outbox replay is
                // independent and must never prevent the diary from refreshing.
                repository.invalidateSearch()
                repository.syncTimezone(zone)
                stage = ConnectionStage.Targets
                ensureActive()
                if(generation!=refreshGeneration) return@launch
                val newToday=LocalDate.now(zone)
                if(mutable.value.day==today && today!=newToday) {
                    mutable.update { it.copy(day=newToday,month=YearMonth.from(newToday)) }
                    saveNavigation()
                }
                today=newToday
                val nav=mutable.value
                val (start,end) = fetchRange(nav)
                val targets=repository.targets()
                stage = ConnectionStage.TargetHistory
                val history=repository.targetHistory()
                stage = ConnectionStage.Entries
                ensureActive()
                if(generation!=refreshGeneration) return@launch
                val entries=repository.entries(start,end,zone)
                stage = ConnectionStage.ZeroDays
                ensureActive()
                if(generation!=refreshGeneration) return@launch
                val zero=repository.confirmedZeroDays(start,end)
                stage = ConnectionStage.Cache
                val snap=OfflineSnapshot(start,end,entries,targets,history,zero)
                // Cache authenticated server snapshots for immediate date/scale navigation.
                val owner = repository.client.auth.currentUserOrNull()?.id
                if (owner != null && owner == authenticatedOwnerId) {
                    if (snapshotOwner != owner) {
                        recentSnapshots.clear()
                        snapshotOwner = owner
                    }
                    val combined=mergeOfflineSnapshots(recentSnapshots.lastOrNull(),snap)
                    recentSnapshots.clear()
                    recentSnapshots.addLast(combined)
                }
                // A Keystore/file-system failure must not mislabel a perfectly
                // successful Supabase download as a remote outage.
                try {
                    offline?.remember(snap)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // No local cache was saved, but live data can still be shown.
                    repository.diagnostics?.failure(ConnectionOperation.Diary,error,ConnectionStage.Cache)
                }
                stage = ConnectionStage.Presentation
                ensureActive()
                if(generation!=refreshGeneration) return@launch
                displaySnapshot(snap,nav,false)
                retryAttempt=0
                retryJob?.cancel()
                warmCatalogue()
                prepareOfflineHistory(snap)
                if(nav.destination==Destination.Settings) loadWeights()
                val perDay=weekTargets(snap)
                // Widget rendering is a separate concern: it cannot turn a
                // successful diary refresh back into "offline".
                if(widgetCoordinator!=null) viewModelScope.launch {
                    try {
                        widgetCoordinator.updateFromLoaded(start,end,entries,targets,zone,zero,perDay)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        repository.diagnostics?.failure(ConnectionOperation.Widget,error)
                    }
                }
                // Upload queued actions after successfully loading live data. A
                // transient replay error cannot turn a healthy read into "offline".
                if (offline != null) viewModelScope.launch {
                    try {
                        if (offline.status().remaining > 0) {
                            val result = offline.sync()
                            if (result.synced > 0) refresh()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        repository.diagnostics?.failure(ConnectionOperation.Mutation,error)
                    }
                }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) {
                ensureActive()
                repository.diagnostics?.failure(ConnectionOperation.Diary,error,stage)
                val issue = ConnectionFailure.from(error)
                val retryable = issue.kind in setOf(
                    ConnectionFailureKind.Dns,ConnectionFailureKind.Tls,
                    ConnectionFailureKind.Timeout,ConnectionFailureKind.Transport,
                    ConnectionFailureKind.Service,ConnectionFailureKind.RateLimit)
                val connected=offline?.onlineNow() == true
                val reason = when {
                    !retryable && issue.status != null -> "Supabase отклонил запрос (HTTP ${issue.status})."
                    !retryable -> "Ошибка обработки данных приложения."
                    connected -> "Не удалось завершить запрос к Supabase."
                    else -> "Нет подключения к интернету."
                }
                val requested=mutable.value
                val (requiredStart,requiredEnd)=requiredRange(requested)
                val cached=runCatching {
                    offline?.cachedSnapshotFor(requiredStart,requiredEnd)
                }.getOrNull()
                if(cached!=null) {
                    displaySnapshot(cached,requested,true)
                    mutable.update { it.copy(connectionWarning="$reason Показаны сохранённые данные.") }
                } else {
                    if(generation==refreshGeneration)
                        mutable.update {it.copy(connectionWarning=reason,
                            error=if (retryable) "Не удалось обновить период с Supabase. Повторите попытку."
                                else "Ошибка обработки данных. Откройте диагностику подключения и сообщите детали.")}
                }
                if(retryable && generation==refreshGeneration) scheduleConnectionRetry()
            } finally {
                if(generation==refreshGeneration) {
                    refreshInFlight=false
                    mutable.update { it.copy(busy=writeInFlight,refreshing=false) }
                }
            }
        }
    }

    /** The smallest date interval whose data must be present for the selected period. */
    private fun requiredRange(nav: AppState): Pair<LocalDate,LocalDate> =
        timelineRequiredRange(nav.scale,nav.day)

    private fun fetchRange(nav: AppState): Pair<LocalDate,LocalDate> =
        timelineFetchRange(nav.scale,nav.day)

    private fun weekTargets(snap:OfflineSnapshot):Map<LocalDate,Targets> {
        val dates=generateSequence(snap.start){it.plusDays(1)}.takeWhile {it<snap.end}
        return dates.mapNotNull {date ->
            (snap.history.lastOrNull {it.date<=date}?.targets ?: snap.targets)
                ?.let{date to it}
        }.toMap()
    }

    private fun validCacheFor(snap:OfflineSnapshot,nav:AppState):Boolean {
        val (start,end) = requiredRange(nav)
        return snap.covers(start,end)
    }

    private suspend fun displaySnapshot(snap:OfflineSnapshot,nav:AppState,cached:Boolean) {
        val zone=ZoneId.systemDefault()
        val all=try { offline?.overlay(snap.entries) ?: snap.entries }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { snap.entries }
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
        val weekNutrition=all.filter { entry ->
            entry.consumedAt.atZone(zone).toLocalDate() in weekStart..weekStart.plusDays(6)
        }.fold(Nutrition.Zero) { sum, entry -> sum + entry.nutrition }
        val monthTargets=if(snap.targets==null) emptyMap() else knownTotals.keys.associateWith {
            WeekBudget.calculate(it,targetFor(it) ?: snap.targets,totals,zero,
                dailyTargets=dailyTargets).effectiveTarget
        }
        val pendingStatus=try { offline?.status() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        // A navigation may cancel this coroutine while encrypted offline data is read.
        if (mutable.value.day != nav.day || mutable.value.scale != nav.scale) return
        mutable.update {it.copy(
            entries=all.filter {row->row.consumedAt.atZone(zone).toLocalDate()==nav.day},
            targets=snap.targets,week=week,monthCalories=totals,monthTargets=monthTargets,
            weekNutrition=weekNutrition,
            confirmedZeroDays=zero,targetVersions=snap.history,budgetTargets=dailyTargets,
            cachedOffline=cached,connectionWarning=if(cached) it.connectionWarning else null,
            pendingWrites=pendingStatus?.remaining ?: 0,
            blockedWrites=pendingStatus?.blocked ?: 0,
        )}
    }

    /** Show queued changes even if the target day has never been opened
     * online. Do not invent zeroes for days without downloaded history. */
    private suspend fun displayUncachedPending(nav:AppState) {
        val additions=runCatching {
            applyPending(emptyList(),offline?.pending().orEmpty())
        }.getOrDefault(emptyList())
        val zone=ZoneId.systemDefault()
        val status=runCatching { offline?.status() }.getOrNull()
        if (mutable.value.day!=nav.day || mutable.value.scale!=nav.scale) return
        mutable.update { it.copy(
            entries=additions.filter { row -> row.consumedAt.atZone(zone).toLocalDate()==nav.day },
            pendingWrites=status?.remaining ?: 0,blockedWrites=status?.blocked ?: 0,
            cachedOffline=true,week=null,weekNutrition=Nutrition.Zero,
            monthCalories=emptyMap(),monthTargets=emptyMap(),
            connectionWarning="Этот период ещё не загружен целиком. Показаны только неотправленные записи.",
        ) }
    }

    fun syncPending() {
        viewModelScope.launch {
            try {
                // An older APK treated Supabase HttpRequestException as permanent.
                // Explicit retry is safe because server requests are idempotent.
                if ((offline?.status()?.blocked ?: 0) > 0) offline?.retryBlocked()
                val result=offline?.sync()
                if(result != null && result.synced > 0) {
                    recentSnapshots.clear()
                    catalogueOwner = null
                }
                updateWidgetFromLocalCache()
                widgetCoordinator?.requestSync()
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
    /** Preserve a loaded period when the date or selected scale changes.
     * Foreground resume, pull-to-refresh and diary writes still fetch from Supabase. */
    private fun navigate(updated: AppState) {
        val owner = repository?.client?.auth?.currentUserOrNull()?.id
        val cached = if(owner != null && snapshotOwner == owner)
            recentSnapshots.lastOrNull { validCacheFor(it,updated) } else null
        cachedNavigationJob?.cancel()
        if(cached == null) {
            mutable.value = updated.copy(entries=emptyList(),week=null,monthCalories=emptyMap(),
                monthTargets=emptyMap(),confirmedZeroDays=emptySet())
            saveNavigation()
            val (start,end)=requiredRange(updated)
            // Read the durable, merged encrypted cache *before* a network
            // request: offline period arrows must work immediately.
            cachedNavigationJob=viewModelScope.launch {
                val stored=runCatching { offline?.cachedSnapshotFor(start,end) }.getOrNull()
                if (mutable.value.day != updated.day || mutable.value.scale != updated.scale) return@launch
                if (stored != null) {
                    displaySnapshot(stored,updated,true)
                    mutable.update { it.copy(connectionWarning=if(offline?.onlineNow()==false)
                        "Нет подключения к интернету. Показаны сохранённые данные." else it.connectionWarning) }
                    if (offline?.onlineNow()==true) refresh()
                } else if(offline?.onlineNow()==false) {
                    displayUncachedPending(updated)
                } else refresh()
            }
            return
        }
        ++refreshGeneration
        refreshJob?.cancel()
        refreshInFlight = false
        mutable.value = updated.copy(busy=writeInFlight,refreshing=false)
        saveNavigation()
        val expectedDay=updated.day
        val expectedScale=updated.scale
        cachedNavigationJob=viewModelScope.launch {
            if(mutable.value.day==expectedDay && mutable.value.scale==expectedScale)
                displaySnapshot(cached,updated,offline?.onlineNow()==false)
        }
    }
    fun setTimeScale(scale: TimeScale) {
        if(mutable.value.scale==scale) return
        navigate(mutable.value.copy(scale=scale, destination=destinationFor(scale)))
    }
    fun showPeriod(date: LocalDate) {
        if(mutable.value.day==date) return
        navigate(mutable.value.copy(day=date,month=YearMonth.from(date),
            entries=emptyList()))
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
    /** The requested diary meal, not the device clock, defines the
     * recommendation context (including backfilled meals). */
    fun beginFoodSearch(meal: MealPeriod) {
        searchMeal = meal
        search("")
    }

    fun endFoodSearch() { searchMeal = null }

    fun search(query: String) {
        latestSearchQuery = query
        val generation = ++searchGeneration
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutable.update { it.copy(searching = true) }
            try {
                if (query.isNotBlank()) delay(180)
                val diary=offline
                val results=if (diary != null && diary.catalogueCount() > 0) {
                    diary.cachedFoods(query, meal = searchMeal)
                } else if(diary != null && !diary.onlineNow()) {
                    mutable.update { it.copy(catalogueCount=0) }
                    emptyList()
                } else {
                    val foods=withContext(Dispatchers.Default) {
                        requireNotNull(repository).rankedCatalogue()
                    }
                    diary?.rememberFoods(foods)
                    if(diary!=null) mutable.update { it.copy(catalogueCount=foods.map { it.foodId }.distinct().size) }
                    // Both online and offline paths use the same cached-history
                    // ranking and latest-portion overlay for consistency.
                    diary?.cachedFoods(query, meal = searchMeal) ?: rankFoodCandidates(foods,query,
                        meal = searchMeal, currentHour = java.time.LocalTime.now().hour)
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
        recentSnapshots.clear()
        widgetCoordinator?.requestSync()
        mutable.update { it.copy(notice = if (confirmed) "Подтверждено: 0 ккал за день." else "Подтверждение нулевого дня снято.") }
        refresh()
    }

    /** Never let widget rendering interfere with an already queued diary write. */
    private suspend fun updateWidgetFromLocalCache() {
        try {
            widgetCoordinator?.updateFromLocalCache()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            repository?.diagnostics?.failure(ConnectionOperation.Widget,error)
        }
    }

    private suspend fun persistQueued() {
        val diary=offline ?: return
        val nav=mutable.value
        val (start,end)=requiredRange(nav)
        // Publish local writes instantly, before waiting for a broken network.
        // A failed Supabase request must not hide a successfully persisted edit.
        val cached=runCatching {diary.cachedSnapshotFor(start,end)}.getOrNull()
        if(cached!=null) displaySnapshot(cached,nav,true)
        else displayUncachedPending(nav)
        val connected=diary.onlineNow()
        val result=if(connected) diary.sync() else diary.status()
        val pending=result.remaining
        if (pending == 0) recentSnapshots.clear()
        if (result.synced > 0) catalogueOwner = null
        mutable.update {
            it.copy(
                notice=if(pending==0 && !connected) "Неотправленная запись удалена с телефона."
                    else if(pending==0) "Изменение сохранено в Supabase. Можно отменить."
                    else "Сохранено на телефоне: $pending действий ожидают синхронизации.",
                pendingWrites=pending,blockedWrites=result.blocked)
        }
        if(pending==0) widgetCoordinator?.requestSync()
        if(connected) refresh()
    }

    fun log(candidate: FoodCandidate,quantity: Double,meal: MealPeriod,date: LocalDate,
            chosen: ChosenMeasure? = null) =
        action(allowDuringRecovery=true) {
            val zone=ZoneId.systemDefault()
            val at=if(date==LocalDate.now(zone)) Instant.now() else date.atTime(
                when(meal) {MealPeriod.Morning->8;MealPeriod.Day->13;
                    MealPeriod.Evening->19;MealPeriod.Snack->15},0).atZone(zone).toInstant()
            requireNotNull(offline).enqueueLog(candidate,quantity,at,meal,chosen)
            updateWidgetFromLocalCache()
            persistQueued()
        }
    fun edit(entry: DiaryEntry,quantity: Double,chosen: ChosenMeasure? = null)=action(allowDuringRecovery=true) {
        requireNotNull(offline).enqueueEdit(entry,quantity,chosen)
        updateWidgetFromLocalCache()
        persistQueued()
    }
    fun delete(entry: DiaryEntry)=action(allowDuringRecovery=true) {
        requireNotNull(offline).enqueueDelete(entry)
        updateWidgetFromLocalCache()
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
            recentSnapshots.clear()
            mutable.update{it.copy(notice="Действие отменено.")}
        }
        updateWidgetFromLocalCache()
        widgetCoordinator?.requestSync()
        refresh()
    }
    /** Invoked only by the explicit human target-review confirmation action. */
    fun saveTargets(targets: Targets) = action {
        val application = requireNotNull(tools)
        application.updateTargets(targets, application.confirmedByUser(targets))
        recentSnapshots.clear()
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
