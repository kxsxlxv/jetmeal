package com.kxsxlxv.jetmeal.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kxsxlxv.jetmeal.JetMealApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class HeroWidgetSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as JetMealApplication
        return try {
            app.widgetCoordinator.syncFromServer()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

object HeroWidgetScheduler {
    private const val PERIODIC_WORK = "jetmeal-hero-widget-periodic"
    private const val IMMEDIATE_WORK = "jetmeal-hero-widget-immediate"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, HeroWidgetReceiver::class.java))
            .isNotEmpty()

    fun ensurePeriodicIfPresent(context: Context) {
        if (hasWidgets(context)) ensurePeriodic(context)
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequest.Builder(
            HeroWidgetSyncWorker::class.java,
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun enqueueImmediate(context: Context) {
        if (!hasWidgets(context)) return
        val request = OneTimeWorkRequest.Builder(HeroWidgetSyncWorker::class.java)
            .setConstraints(constraints())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
    }
}
