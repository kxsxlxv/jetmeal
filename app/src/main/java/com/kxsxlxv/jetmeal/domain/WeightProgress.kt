package com.kxsxlxv.jetmeal.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Immutable chart and summary data. All interpolation uses calendar dates, never point indices. */
data class WeightChartDay(val date: LocalDate, val kilograms: Double, val source: String)

data class WeightProgressData(
    val days: List<WeightChartDay>,
    val goal: WeightGoal?,
    val today: LocalDate,
    val trend7Kg: Double?,
    val latestPlanKg: Double?,
    val actualMinusPlanKg: Double?,
    val remainingDays: Long?,
    val requiredWeeklyKg: Double?,
    val observedWeeklyKg: Double?,
) {
    val latest: WeightChartDay? get() = days.lastOrNull()
    val planTodayKg: Double? get() = goal?.takeIf { today >= it.startDate }?.expected(today)
}

object WeightProgress {
    private const val MAX_POINTS = 18

    fun build(
        measurements: List<WeightMeasurement>,
        goal: WeightGoal?,
        today: LocalDate,
        zone: ZoneId,
    ): WeightProgressData {
        // One point per local day: the most recent weighing, never an arbitrary
        // average or one point per same-day repeat. The 7-day trend is separate.
        val days = measurements
            .filter { it.kilograms.isFinite() && it.kilograms in 20.0..500.0 }
            .groupBy { it.measuredAt.atZone(zone).toLocalDate() }
            .mapNotNull { (day, values) ->
                if (day > today) null
                else values.maxByOrNull { it.measuredAt }?.let {
                    WeightChartDay(day, it.kilograms, it.source)
                }
            }
            .sortedBy { it.date }
        val latest = days.lastOrNull()
        val latestPlan = if (latest != null && goal != null &&
            latest.date >= goal.startDate && latest.date <= goal.targetDate
        ) goal.expected(latest.date) else null
        val daysRemaining = goal?.let {
            ChronoUnit.DAYS.between(today, it.targetDate).coerceAtLeast(0L)
        }
        val required = if (daysRemaining != null && daysRemaining > 0 && latest != null &&
            goal != null
        ) (goal.targetKilograms - latest.kilograms) * 7.0 / daysRemaining else null

        val observed = days.filter { it.date >= today.minusDays(13) }
        val weeklyPace = if (observed.size >= 3 &&
            ChronoUnit.DAYS.between(observed.first().date, observed.last().date) >= 7L
        ) {
            // Least-squares slope per calendar day, not per measurement index.
            val origin = observed.first().date
            val xs = observed.map { ChronoUnit.DAYS.between(origin, it.date).toDouble() }
            val avgX = xs.average()
            val avgY = observed.map { it.kilograms }.average()
            val divisor = xs.sumOf { (it - avgX) * (it - avgX) }
            if (divisor > 0) {
                xs.zip(observed).sumOf { (x, y) ->
                    (x - avgX) * (y.kilograms - avgY)
                } / divisor * 7.0
            } else null
        } else null

        return WeightProgressData(
            days = days.takeLast(MAX_POINTS),
            goal = goal,
            today = today,
            trend7Kg = WeightTrend.smoothedLast7Days(measurements, zone),
            latestPlanKg = latestPlan,
            actualMinusPlanKg = if (latestPlan != null && latest != null)
                latest.kilograms - latestPlan else null,
            remainingDays = daysRemaining,
            requiredWeeklyKg = required,
            observedWeeklyKg = weeklyPace,
        )
    }
}
