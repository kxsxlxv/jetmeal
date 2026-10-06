package com.kxsxlxv.jetmeal.ui

import com.kxsxlxv.jetmeal.domain.DiaryEntry
import com.kxsxlxv.jetmeal.domain.MealPeriod
import com.kxsxlxv.jetmeal.domain.Nutrition
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DayPresentationTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private val today = LocalDate.of(2026, 10, 6)
    private fun entry(id: String, meal: MealPeriod, time: String) = DiaryEntry(
        id, "Блюдо", null, 100.0, "g", 100.0, Nutrition.Zero, Nutrition.Zero,
        Instant.parse(time), meal, null, null, null,
    )

    @Test fun todayUsesExistingInferenceEvenWhenSnackWasLastLogged() {
        val snack = entry("snack", MealPeriod.Snack, "2026-10-06T06:00:00Z")
        assertEquals(MealPeriod.Day, initialExpandedMeal(today, listOf(snack),
            Instant.parse("2026-10-06T09:00:00Z"), zone))
    }

    @Test fun overnightTodayExpandsEvening() {
        assertEquals(MealPeriod.Evening, initialExpandedMeal(today, emptyList(),
            Instant.parse("2026-10-05T22:00:00Z"), zone))
    }

    @Test fun historicalDefaultUsesLastRecordedMealWithoutAssumingEnumOrder() {
        val meals = listOf(entry("late-morning", MealPeriod.Morning, "2026-10-05T19:00:00Z"),
            entry("early-evening", MealPeriod.Evening, "2026-10-05T06:00:00Z"))
        assertEquals(MealPeriod.Morning, initialExpandedMeal(today.minusDays(1), meals,
            Instant.parse("2026-10-06T09:00:00Z"), zone))
    }

    @Test fun emptyHistoricalDayHasPredictableMorningDefault() {
        assertEquals(MealPeriod.Morning, initialExpandedMeal(today.minusDays(1), emptyList(),
            Instant.parse("2026-10-06T09:00:00Z"), zone))
    }
}
