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

/** Explain the same input that WeekBudget uses; never infer unknown days as fasting. */
object BudgetExplain {
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
