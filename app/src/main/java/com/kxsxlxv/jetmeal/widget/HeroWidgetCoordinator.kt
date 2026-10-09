package com.kxsxlxv.jetmeal.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.kxsxlxv.jetmeal.data.SupabaseRepository
import com.kxsxlxv.jetmeal.data.OfflineDiary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.kxsxlxv.jetmeal.data.ConnectionStage
import com.kxsxlxv.jetmeal.data.atConnectionStage
import io.github.jan.supabase.auth.status.SessionStatus
import java.io.IOException
import com.kxsxlxv.jetmeal.domain.DiaryEntry
import com.kxsxlxv.jetmeal.domain.Targets
import io.github.jan.supabase.auth.auth
import java.time.LocalDate
import java.time.ZoneId

class HeroWidgetCoordinator(
    context: Context,
    private val repository: SupabaseRepository?,
    private val offlineDiary: OfflineDiary? = null,
) {
    private val appContext = context.applicationContext
    private val store = HeroWidgetStore(appContext)
    private val writeMutex = Mutex()

    fun requestSync() = HeroWidgetScheduler.enqueueImmediate(appContext)

    suspend fun syncFromServer() {
        val startedAt = System.currentTimeMillis()
        val data = repository
        if (data == null) {
            store.write(HeroWidgetState.Empty)
            updateWidgets()
            return
        }

        atConnectionStage(ConnectionStage.Session) { data.client.auth.awaitInitialization() }
        if (data.client.auth.sessionStatus.value is SessionStatus.NotAuthenticated) {
            showSignedOut()
            return
        }
        // RefreshFailure retains the saved session and retries. Never replace a person's
        // last widget snapshot with "signed out" merely because the network is unavailable.
        if (data.client.auth.sessionStatus.value !is SessionStatus.Authenticated) {
            throw IOException("Session refresh is pending")
        }

        val zone = ZoneId.systemDefault()
        atConnectionStage(ConnectionStage.Profile) { data.syncTimezone(zone) }
        val today = LocalDate.now(zone)
        val targets = atConnectionStage(ConnectionStage.Targets) { data.targets() }

        if (targets == null) {
            atConnectionStage(ConnectionStage.WidgetUpdate) {
                writeMutex.withLock {
                    store.write(HeroWidgetState.MissingTargets)
                    updateWidgets()
                }
            }
            return
        } else {
            val weekStart = today.minusDays(today.dayOfWeek.value - 1L)
            val entries = atConnectionStage(ConnectionStage.Entries) {
                data.entries(weekStart, today.plusDays(1), zone)
            }
            val zeroDays = atConnectionStage(ConnectionStage.ZeroDays) {
                data.confirmedZeroDays(weekStart, today.plusDays(1))
            }
            val history = atConnectionStage(ConnectionStage.TargetHistory) { data.targetHistory() }
            val perDay = (0L..6L).associate { offset ->
                val day = weekStart.plusDays(offset)
                day to (history.lastOrNull { it.date <= day }?.targets ?: targets)
            }
            atConnectionStage(ConnectionStage.Presentation) {
                // Apply the user's unsent offline diary before calculating today's totals.
                // A background network fetch must not erase an optimistic local update.
                writeMutex.withLock {
                    val currentlyShown = store.read() as? HeroWidgetState.Ready
                    if (currentlyShown != null && currentlyShown.updatedAtMillis > startedAt) {
                        return@withLock
                    }
                    val visibleEntries = offlineDiary?.overlay(entries) ?: entries
                    store.write(HeroWidgetSnapshot.calculate(today, visibleEntries,
                        perDay[today] ?: targets, zone,
                        confirmedZeroDays = zeroDays, dailyTargets = perDay))
                    updateWidgets()
                }
            }
        }
    }

    suspend fun updateFromLoaded(
        start: LocalDate,
        endExclusive: LocalDate,
        entries: List<DiaryEntry>,
        targets: Targets?,
        zone: ZoneId,
        confirmedZeroDays: Set<LocalDate> = emptySet(),
        dailyTargets: Map<LocalDate, Targets> = emptyMap(),
    ) {
        val today = LocalDate.now(zone)
        val weekStart = today.minusDays(today.dayOfWeek.value - 1L)
        if (start > weekStart || endExclusive <= today) return

        atConnectionStage(ConnectionStage.Presentation) {
            writeMutex.withLock {
                val visibleEntries = offlineDiary?.overlay(entries) ?: entries
                store.write(
                    if (targets == null) HeroWidgetState.MissingTargets
                    else HeroWidgetSnapshot.calculate(today, visibleEntries,
                        dailyTargets[today] ?: targets, zone,
                        confirmedZeroDays = confirmedZeroDays, dailyTargets = dailyTargets)
                )
                updateWidgets()
            }
        }
    }

    /** Fast path for a manual add/edit/delete. No network or WorkManager delay. */
    suspend fun updateFromLocalCache() {
        val snapshot = offlineDiary?.cachedSnapshot() ?: return
        val zone = ZoneId.systemDefault()
        val targetsByDate = (generateSequence(snapshot.start) { it.plusDays(1) }
            .takeWhile { it < snapshot.end }).mapNotNull { date ->
                (snapshot.history.lastOrNull { it.date <= date }?.targets ?: snapshot.targets)
                    ?.let { date to it }
            }.toMap()
        updateFromLoaded(snapshot.start, snapshot.end, snapshot.entries, snapshot.targets,
            zone, snapshot.zeroDays, targetsByDate)
    }

    suspend fun showSignedOut() {
        writeMutex.withLock {
            store.write(HeroWidgetState.SignedOut)
            updateWidgets()
        }
    }

    private suspend fun updateWidgets() {
        HeroWidget().updateAll(appContext)
        HeroTonalWidget().updateAll(appContext)
        CalorieRingWidget().updateAll(appContext)
    }
}
