package com.kxsxlxv.jetmeal.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.kxsxlxv.jetmeal.data.SupabaseRepository
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
) {
    private val appContext = context.applicationContext
    private val store = HeroWidgetStore(appContext)

    fun requestSync() = HeroWidgetScheduler.enqueueImmediate(appContext)

    suspend fun syncFromServer() {
        val data = repository
        if (data == null) {
            store.write(HeroWidgetState.Empty)
            updateWidgets()
            return
        }

        data.client.auth.awaitInitialization()
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
        data.syncTimezone(zone)
        val today = LocalDate.now(zone)
        val targets = data.targets()

        if (targets == null) {
            store.write(HeroWidgetState.MissingTargets)
        } else {
            val weekStart = today.minusDays(today.dayOfWeek.value - 1L)
            val entries = data.entries(weekStart, today.plusDays(1), zone)
            store.write(HeroWidgetSnapshot.calculate(today, entries, targets, zone))
        }
        updateWidgets()
    }

    suspend fun updateFromLoaded(
        start: LocalDate,
        endExclusive: LocalDate,
        entries: List<DiaryEntry>,
        targets: Targets?,
        zone: ZoneId,
    ) {
        val today = LocalDate.now(zone)
        val weekStart = today.minusDays(today.dayOfWeek.value - 1L)
        if (start > weekStart || endExclusive <= today) return

        store.write(
            if (targets == null) HeroWidgetState.MissingTargets
            else HeroWidgetSnapshot.calculate(today, entries, targets, zone)
        )
        updateWidgets()
    }

    suspend fun showSignedOut() {
        store.write(HeroWidgetState.SignedOut)
        updateWidgets()
    }

    private suspend fun updateWidgets() {
        HeroWidget().updateAll(appContext)
        HeroTonalWidget().updateAll(appContext)
        CalorieRingWidget().updateAll(appContext)
    }
}
