package com.kxsxlxv.jetmeal.domain

import java.time.Instant
import java.time.LocalDate

/** Values are totals for a specific quantity, never implicitly values per 100 g. */
data class Nutrition(
    val calories: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
) {
    init {
        require(listOf(calories, protein, fat, carbs).all { it.isFinite() && it >= 0.0 }) {
            "Nutrition must contain finite, non-negative values."
        }
    }

    operator fun plus(other: Nutrition) = Nutrition(
        calories + other.calories,
        protein + other.protein,
        fat + other.fat,
        carbs + other.carbs,
    )

    operator fun times(factor: Double): Nutrition {
        require(factor.isFinite() && factor >= 0.0) { "Scale must be finite and non-negative." }
        return Nutrition(calories * factor, protein * factor, fat * factor, carbs * factor)
    }

    companion object {
        val Zero = Nutrition(0.0, 0.0, 0.0, 0.0)
    }
}

data class Targets(
    val calories: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val limitRatio: Double = 0.10,
) {
    init {
        require(calories.isFinite() && calories > 0.0) { "Daily calories must be positive." }
        require(listOf(protein, fat, carbs).all { it.isFinite() && it >= 0.0 }) {
            "Macro targets must be finite and non-negative."
        }
        require(limitRatio.isFinite() && limitRatio in 0.0..1.0) { "Adjustment limit must be between 0 and 1." }
    }
}

enum class MealPeriod(val wireValue: String) {
    Morning("morning"), Day("day"), Evening("evening"), Snack("snack");

    companion object {
        fun fromWire(value: String): MealPeriod = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown meal period.")
    }
}

data class DiaryEntry(
    val id: String,
    val name: String,
    val brand: String?,
    val quantity: Double,
    val unit: String,
    val basisAmount: Double,
    val basisNutrition: Nutrition,
    val nutrition: Nutrition,
    val consumedAt: Instant,
    val meal: MealPeriod,
    val variantId: String?,
    val foodId: String?,
    val confidence: Double?,
    val estimated: Boolean = false,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && unit.isNotBlank()) { "Entry identity, name and unit are required." }
        require(quantity.isFinite() && quantity > 0.0) { "Quantity must be finite and positive." }
        require(basisAmount.isFinite() && basisAmount > 0.0) { "Basis amount must be finite and positive." }
        require(confidence == null || confidence.isFinite() && confidence in 0.0..1.0) {
            "Confidence must be between 0 and 1."
        }
    }
}

data class FoodCandidate(
    val id: String,
    val foodId: String,
    val name: String,
    val brand: String?,
    val source: String?,
    val amount: Double,
    val unit: String,
    val nutrition: Nutrition,
    val estimated: Boolean,
    val usageCount: Int = 0,
    val lastUsed: Instant? = null,
    val matchConfidence: Double = 0.0,
    val matchReason: String = "Personal catalogue",
) {
    init {
        require(id.isNotBlank() && foodId.isNotBlank() && name.isNotBlank() && unit.isNotBlank()) {
            "Food identity, name and natural unit are required."
        }
        require(amount.isFinite() && amount > 0.0) { "Serving amount must be finite and positive." }
        require(usageCount >= 0) { "Usage count must be non-negative." }
    }
}

enum class DayLoggingStatus { Recorded, ConfirmedZero, Missing, InProgress, Future }

data class BudgetDay(
    val date: LocalDate,
    val actual: Double,
    val target: Double,
    val status: DayLoggingStatus = DayLoggingStatus.Recorded,
)

data class WeekState(
    val start: LocalDate,
    val end: LocalDate,
    val days: List<BudgetDay>,
    val baseBudget: Double,
    val totalConsumed: Double,
    val deviation: Double,
    val remainingDays: Int,
    val effectiveTarget: Double,
    /** Signed variance left if the remaining days meet their clamped allowance. */
    val residual: Double,
    val baseDailyCalories: Double = baseBudget / 7,
    val adjustmentLimitRatio: Double = 0.10,
    val missingCompletedDays: List<LocalDate> = emptyList(),
)

/** Measurements are immutable values with stable external identity for deduplicated sync. */
data class WeightMeasurement(
    val id: String,
    val measuredAt: Instant,
    val kilograms: Double,
    val bodyFatPercent: Double?,
    val source: String,
)

data class TargetVersion(val date: LocalDate, val targets: Targets)

/** Estimate the underlying trend rather than reacting to hydration fluctuations. */
object WeightTrend {
    fun smoothedLast7Days(entries: List<WeightMeasurement>, zone: java.time.ZoneId): Double? {
        val latestDay = entries.maxOfOrNull { it.measuredAt.atZone(zone).toLocalDate() } ?: return null
        val daily = entries.groupBy { it.measuredAt.atZone(zone).toLocalDate() }
            .mapValues { (_, measurements) -> measurements.map { it.kilograms }.average() }
        val values = (0L..6L).mapNotNull { daily[latestDay.minusDays(it)] }
        return values.takeIf { it.isNotEmpty() }?.average()
    }
}

data class WeightGoal(
    val startDate: LocalDate,
    val targetDate: LocalDate,
    val startKilograms: Double,
    val targetKilograms: Double,
) {
    init { require(targetDate > startDate && startKilograms in 20.0..500.0
        && targetKilograms in 20.0..500.0) }
    fun expected(date: LocalDate): Double {
        val total = java.time.temporal.ChronoUnit.DAYS.between(startDate,targetDate).toDouble()
        val elapsed = java.time.temporal.ChronoUnit.DAYS.between(startDate,date).toDouble()
        val portion = (elapsed/total).coerceIn(0.0,1.0)
        return startKilograms+(targetKilograms-startKilograms)*portion
    }
}
