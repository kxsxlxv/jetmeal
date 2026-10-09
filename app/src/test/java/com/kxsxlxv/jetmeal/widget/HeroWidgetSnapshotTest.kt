package com.kxsxlxv.jetmeal.widget

import com.kxsxlxv.jetmeal.data.PendingDiaryMutation
import com.kxsxlxv.jetmeal.data.applyPending
import kotlinx.serialization.json.buildJsonObject
import com.kxsxlxv.jetmeal.domain.DiaryEntry
import com.kxsxlxv.jetmeal.domain.MealPeriod
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.domain.Targets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HeroWidgetSnapshotTest {
    private val zone = ZoneId.of("UTC")
    private val date = LocalDate.of(2026, 10, 6)

    @Test
    fun snapshotUsesTodayMacrosAndEffectiveWeeklyCalorieTarget() {
        val targets = Targets(2000.0, 120.0, 65.0, 230.0)
        val entries = listOf(
            entry(
                "monday",
                "2026-10-05T12:00:00Z",
                Nutrition(2500.0, 100.0, 80.0, 260.0),
            ),
            entry(
                "today-1",
                "2026-10-06T08:00:00Z",
                Nutrition(300.0, 20.0, 10.0, 35.0),
            ),
            entry(
                "today-2",
                "2026-10-06T13:00:00Z",
                Nutrition(200.0, 10.0, 5.0, 25.0),
            ),
        )

        val snapshot =
            HeroWidgetSnapshot.calculate(date, entries, targets, zone, updatedAtMillis = 123L)

        assertEquals(500.0, snapshot.total.calories, 0.0)
        assertEquals(30.0, snapshot.total.protein, 0.0)
        assertEquals(15.0, snapshot.total.fat, 0.0)
        assertEquals(60.0, snapshot.total.carbs, 0.0)
        assertEquals(2000.0 - 500.0 / 6.0, snapshot.calorieTarget, 0.0001)
        assertEquals(123L, snapshot.updatedAtMillis)
    }

    @Test
    fun localUnsentFoodAppearsImmediatelyInWidgetCalculation() {
        val targets = Targets(2000.0, 120.0, 65.0, 230.0)
        val saved = entry("saved", "2026-10-06T08:00:00Z",
            Nutrition(250.0, 18.0, 9.0, 20.0))
        val preview = entry("pending", "2026-10-06T12:00:00Z",
            Nutrition(420.0, 28.0, 17.0, 36.0))
        val visible = applyPending(listOf(saved),listOf(
            PendingDiaryMutation("pending","log_food",buildJsonObject { },preview=preview)
        ))
        val widget = HeroWidgetSnapshot.calculate(date, visible, targets, zone)
        assertEquals(670.0, widget.total.calories, 0.0)
        assertEquals(46.0, widget.total.protein, 0.0)
    }

    private fun entry(id: String, instant: String, nutrition: Nutrition) = DiaryEntry(
        id = id,
        name = id,
        brand = null,
        quantity = 1.0,
        unit = "serving",
        basisAmount = 1.0,
        basisNutrition = nutrition,
        nutrition = nutrition,
        consumedAt = Instant.parse(instant),
        meal = MealPeriod.Day,
        variantId = null,
        foodId = null,
        confidence = null,
    )
}
