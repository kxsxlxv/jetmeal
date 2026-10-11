package com.kxsxlxv.jetmeal.domain

import java.time.LocalDate

data class DailyBudgetCause(
    val date: LocalDate,
    val consumed: Double?,
    val baseline: Double,
    val difference: Double?,
)

data class BudgetExplanation(
    val baselineToday: Double,
    val theoretical: Double,
    val effective: Double,
    val lowerBound: Double,
    val upperBound: Double,
    val adjustment: Double,
    val cumulativeDifference: Double,
    val remainingDays: Int,
    val undistributed: Double,
    val causes: List<DailyBudgetCause>,
    val missingDays: Int,
    val hasLimit: Boolean,
)

/** Historic weeks are reports, not "today's allowance" forecasts.
 * Include Sunday after the week closes; never count missing days as zero. */
data class CompletedWeekReport(
    val causes: List<DailyBudgetCause>,
    val recordedDays: Int,
    val recordedDifference: Double,
    val allDaysRecorded: Boolean,
)

/** Explain the same input that WeekBudget uses; never infer unknown days as fasting. */
object BudgetExplain {
    fun completedWeek(week: WeekState, dailyTargets: Map<LocalDate, Targets>): CompletedWeekReport {
        val causes = week.days.map { day ->
            val baseline = dailyTargets[day.date]?.calories ?: week.baseDailyCalories
            val known = day.status == DayLoggingStatus.Recorded ||
                day.status == DayLoggingStatus.ConfirmedZero
            DailyBudgetCause(
                date = day.date,
                consumed = if (known) day.actual else null,
                baseline = baseline,
                difference = if (known) day.actual - baseline else null,
            )
        }
        return CompletedWeekReport(
            causes = causes,
            recordedDays = causes.count { it.consumed != null },
            recordedDifference = causes.sumOf { it.difference ?: 0.0 },
            allDaysRecorded = causes.all { it.consumed != null },
        )
    }

    fun calculate(week: WeekState, dailyTargets: Map<LocalDate, Targets>): BudgetExplanation {
        val current = week.days.first { day ->
            day.date == week.start.plusDays((7 - week.remainingDays).toLong())
        }
        val base = dailyTargets[current.date]?.calories ?: week.baseDailyCalories
        val limit = dailyTargets[current.date]?.limitRatio ?: week.adjustmentLimitRatio
        val lower = base * (1.0 - limit)
        val upper = base * (1.0 + limit)
        val theoretical = base - week.deviation / week.remainingDays
        val causes = week.days.filter { it.date < current.date }.map { day ->
            val dayBase = dailyTargets[day.date]?.calories ?: week.baseDailyCalories
            val known = day.status == DayLoggingStatus.Recorded ||
                day.status == DayLoggingStatus.ConfirmedZero
            DailyBudgetCause(day.date, if (known) day.actual else null,
                dayBase, if (known) day.actual - dayBase else null)
        }
        return BudgetExplanation(
            baselineToday = base,
            theoretical = theoretical,
            effective = week.effectiveTarget,
            lowerBound = lower,
            upperBound = upper,
            adjustment = week.effectiveTarget - base,
            cumulativeDifference = week.deviation,
            remainingDays = week.remainingDays,
            undistributed = week.residual,
            causes = causes,
            missingDays = causes.count { it.consumed == null },
            hasLimit = theoretical < lower || theoretical > upper,
        )
    }
}
