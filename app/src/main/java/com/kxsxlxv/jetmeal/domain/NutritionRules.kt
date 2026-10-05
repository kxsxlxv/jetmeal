package com.kxsxlxv.jetmeal.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object QuantityScaling {
    /** Always recalculate from immutable basis values; rounding belongs at presentation/storage. */
    fun scale(basisAmount: Double, basisNutrition: Nutrition, quantity: Double): Nutrition {
        require(basisAmount.isFinite() && basisAmount > 0.0) { "Basis amount must be finite and positive." }
        require(quantity.isFinite() && quantity > 0.0) { "Quantity must be finite and positive." }
        return basisNutrition * (quantity / basisAmount)
    }

    fun update(entry: DiaryEntry, quantity: Double): DiaryEntry = entry.copy(
        quantity = quantity,
        nutrition = scale(entry.basisAmount, entry.basisNutrition, quantity),
    )
}

object MealPeriods {
    fun resolve(consumedAt: Instant, timezone: ZoneId, explicit: MealPeriod? = null): MealPeriod {
        explicit?.let { return it }
        return when (consumedAt.atZone(timezone).hour) {
            in 5..11 -> MealPeriod.Morning
            in 12..16 -> MealPeriod.Day
            else -> MealPeriod.Evening
        }
    }
}

object WeekBudget {
    /**
     * An as-of local-date calculation. Missing completed days count as zero consumption.
     * Historical targets replay actuals preceding that date. Today and future dates share the
     * current allowance; future unlogged days must never be counted as completed days.
     */
    fun calculate(date: LocalDate, targets: Targets, actual: Map<LocalDate, Double>): WeekState {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val end = start.plusDays(6)
        val completedDays = date.dayOfWeek.value - 1
        val remainingDays = 7 - completedDays
        val weekActual = (0L..6L).associate { offset ->
            val day = start.plusDays(offset)
            val amount = if (day <= date) actual[day] ?: 0.0 else 0.0
            require(amount.isFinite() && amount >= 0.0) { "Actual calories must be finite and non-negative." }
            day to amount
        }
        val base = targets.calories
        val lowerBound = base * (1.0 - targets.limitRatio)
        val upperBound = base * (1.0 + targets.limitRatio)
        var deviation = 0.0
        val days = (0L..6L).map { offset ->
            val day = start.plusDays(offset)
            val count = 7 - offset.toInt()
            val target = (base - deviation / count).coerceIn(lowerBound, upperBound)
            val dayActual = weekActual.getValue(day)
            if (day < date) deviation += dayActual - base
            BudgetDay(day, dayActual, target)
        }
        val effective = (base - deviation / remainingDays).coerceIn(lowerBound, upperBound)
        val effectiveDays = days.map { day -> if (day.date >= date) day.copy(target = effective) else day }
        return WeekState(
            start = start,
            end = end,
            days = effectiveDays,
            baseBudget = base * 7,
            totalConsumed = weekActual.values.sum(),
            deviation = deviation,
            remainingDays = remainingDays,
            effectiveTarget = effective,
            residual = deviation + (effective - base) * remainingDays,
            baseDailyCalories = base,
            adjustmentLimitRatio = targets.limitRatio,
        )
    }
}
