package com.kxsxlxv.jetmeal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NutritionRulesTest {
    private val monday = LocalDate.of(2026, 10, 5)
    private val targets = Targets(2000.0, 170.0, 70.0, 180.0)

    @Test fun mondayStartsAtBaseRegardlessOfLastWeekAndCurrentDay() {
        val state = WeekBudget.calculate(monday, targets, mapOf(monday.minusDays(1) to 8000.0, monday to 2500.0))
        assertEquals(monday, state.start)
        assertEquals(monday.plusDays(6), state.end)
        assertEquals(7, state.remainingDays)
        assertEquals(14000.0, state.baseBudget, 0.0)
        assertEquals(2000.0, state.effectiveTarget, 0.0)
        assertEquals(0.0, state.deviation, 0.0)
        assertEquals(2500.0, state.totalConsumed, 0.0)
    }

    @Test fun overConsumptionIsSpreadAcrossRemainingDaysIncludingToday() {
        val state = WeekBudget.calculate(monday.plusDays(1), targets, mapOf(monday to 2500.0))
        assertEquals(6, state.remainingDays)
        assertEquals(500.0, state.deviation, 0.0)
        assertEquals(2000.0 - 500.0 / 6.0, state.effectiveTarget, 0.000001)
        assertEquals(0.0, state.residual, 0.000001)
        assertEquals(2000.0, state.days.first().target, 0.0)
        state.days.drop(1).forEach { assertEquals(state.effectiveTarget, it.target, 0.0) }
    }

    @Test fun underConsumptionIsSymmetricWithOverConsumption() {
        val above = WeekBudget.calculate(monday.plusDays(1), targets, mapOf(monday to 2500.0))
        val below = WeekBudget.calculate(monday.plusDays(1), targets, mapOf(monday to 1500.0))
        assertEquals(4000.0, above.effectiveTarget + below.effectiveTarget, 0.000001)
        assertEquals(-500.0, below.deviation, 0.0)
        assertEquals(0.0, below.residual, 0.000001)
    }

    @Test fun clampsPreserveSignedUnreconciledResidual() {
        val above = WeekBudget.calculate(monday.plusDays(1), targets, mapOf(monday to 4000.0))
        val below = WeekBudget.calculate(monday.plusDays(1), targets, mapOf(monday to 0.0))
        assertEquals(1800.0, above.effectiveTarget, 0.0)
        assertEquals(800.0, above.residual, 0.0)
        assertEquals(2200.0, below.effectiveTarget, 0.0)
        assertEquals(-800.0, below.residual, 0.0)
    }

    @Test fun customClampCanBeDisabledOrExpanded() {
        val actual = mapOf(monday to 4000.0)
        val fixed = WeekBudget.calculate(monday.plusDays(1), targets.copy(limitRatio = 0.0), actual)
        val expanded = WeekBudget.calculate(monday.plusDays(1), targets.copy(limitRatio = 0.5), actual)
        assertEquals(2000.0, fixed.effectiveTarget, 0.0)
        assertEquals(2000.0, fixed.residual, 0.0)
        assertEquals(2000.0 - 2000.0 / 6.0, expanded.effectiveTarget, 0.000001)
        assertEquals(0.0, expanded.residual, 0.000001)
    }

    @Test fun sundayHasOneRemainingDayAndNextMondayDiscardsResidual() {
        val sunday = monday.plusDays(6)
        val actual = (0L..5L).associate { monday.plusDays(it) to 2400.0 }
        val state = WeekBudget.calculate(sunday, targets, actual)
        assertEquals(1, state.remainingDays)
        assertEquals(2400.0, state.deviation, 0.0)
        assertEquals(1800.0, state.effectiveTarget, 0.0)
        assertEquals(2200.0, state.residual, 0.0)
        val reset = WeekBudget.calculate(sunday.plusDays(1), targets, actual)
        assertEquals(2000.0, reset.effectiveTarget, 0.0)
        assertEquals(0.0, reset.residual, 0.0)
        assertEquals(0.0, reset.totalConsumed, 0.0)
    }

    @Test fun historicalDailyTargetsReplayEachDaysPrecedingActuals() {
        val state = WeekBudget.calculate(monday.plusDays(2), targets, mapOf(monday to 2500.0, monday.plusDays(1) to 1900.0))
        assertEquals(2000.0, state.days[0].target, 0.0)
        assertEquals(2000.0 - 500.0 / 6.0, state.days[1].target, 0.000001)
        assertEquals(1920.0, state.days[2].target, 0.0)
        assertEquals(400.0, state.deviation, 0.0)
        assertEquals(4400.0, state.totalConsumed, 0.0)
    }

    @Test fun currentAndFutureActualsCannotChangeCurrentAllowance() {
        val actual = mapOf(monday to 2100.0, monday.plusDays(1) to 4500.0, monday.plusDays(2) to 9000.0)
        val state = WeekBudget.calculate(monday.plusDays(1), targets, actual)
        assertEquals(100.0, state.deviation, 0.0)
        assertEquals(2000.0 - 100.0 / 6.0, state.effectiveTarget, 0.000001)
        assertEquals(6600.0, state.totalConsumed, 0.0)
        assertEquals(0.0, state.days[2].actual, 0.0)
    }

    @Test fun missingCompletedDayDoesNotCreateArtificialCalorieCredit() {
        val tuesday = monday.plusDays(1)
        val state = WeekBudget.calculate(tuesday, targets, emptyMap())
        assertEquals(0.0, state.deviation, 0.0)
        assertEquals(2000.0, state.effectiveTarget, 0.0)
        assertEquals(listOf(monday), state.missingCompletedDays)
        assertEquals(DayLoggingStatus.Missing, state.days[0].status)
        assertEquals(DayLoggingStatus.InProgress, state.days[1].status)
        assertEquals(Targets(2000.0, 170.0, 70.0, 180.0), targets)
    }

    @Test fun confirmedZeroCountsAsARealZeroAndCanBeReverted() {
        val tuesday = monday.plusDays(1)
        val confirmed = WeekBudget.calculate(tuesday, targets, emptyMap(), setOf(monday))
        assertEquals(-2000.0, confirmed.deviation, 0.0)
        assertEquals(2200.0, confirmed.effectiveTarget, 0.0)
        assertEquals(emptyList<LocalDate>(), confirmed.missingCompletedDays)
        assertEquals(DayLoggingStatus.ConfirmedZero, confirmed.days[0].status)
        val reverted = WeekBudget.calculate(tuesday, targets, emptyMap())
        assertEquals(2000.0, reverted.effectiveTarget, 0.0)
    }

    @Test fun gapsBeforeRecordedDaysAreExcludedWhileKnownDeviationsRemain() {
        val wednesday = monday.plusDays(2)
        val state = WeekBudget.calculate(wednesday, targets, mapOf(monday.plusDays(1) to 2400.0))
        assertEquals(400.0, state.deviation, 0.0)
        assertEquals(1920.0, state.effectiveTarget, 0.0)
        assertEquals(listOf(monday), state.missingCompletedDays)
        assertEquals(DayLoggingStatus.Recorded, state.days[1].status)
    }

    @Test fun historicalLastDayIsMissingWhenPastButNotWhenStillInProgress() {
        val sunday = monday.plusDays(6)
        val beforeFinish = WeekBudget.calculate(sunday, targets, emptyMap())
        val afterFinish = WeekBudget.calculate(sunday, targets, emptyMap(), asOfDayCompleted = true)
        assertEquals(DayLoggingStatus.InProgress, beforeFinish.days.last().status)
        assertEquals(DayLoggingStatus.Missing, afterFinish.days.last().status)
        assertEquals(6, beforeFinish.missingCompletedDays.size)
        assertEquals(7, afterFinish.missingCompletedDays.size)
    }

    @Test fun targetRevisionsDoNotRewriteEarlierDayAllowances() {
        val tuesday = monday.plusDays(1)
        val newTargets = targets.copy(calories = 2300.0)
        val history = (0L..6L).associate { day ->
            val date = monday.plusDays(day)
            date to (if (date < tuesday) targets else newTargets)
        }
        val state = WeekBudget.calculate(tuesday, newTargets,
            mapOf(monday to 2200.0), dailyTargets = history)
        assertEquals(2000.0, state.days.first().target, .000001)
        assertEquals(2300.0 - 200.0 / 6, state.effectiveTarget, .000001)
        assertEquals(200.0, state.deviation, .000001)
        assertEquals(2000.0 + 2300.0 * 6, state.baseBudget, .000001)
    }

    @Test fun weightTrendUsesDailyMeanAndSevenCalendarDays() {
        val zone = ZoneId.of("UTC")
        fun weight(day: Long, kg: Double) = WeightMeasurement(
            "item$day:$kg", monday.plusDays(day).atStartOfDay(zone).toInstant(), kg, null, "manual")
        val measurements = listOf(weight(0, 80.0),weight(0, 82.0),
            weight(1, 80.0),weight(2, 79.0),weight(9, 76.0))
        assertEquals((79.0 + 76.0)/2.0,
            WeightTrend.smoothedLast7Days(measurements,zone)!!, .000001)
    }

    @Test fun yearAndMonthBoundariesFollowLocalMondayThroughSunday() {
        val newYear = LocalDate.of(2027, 1, 1)
        val state = WeekBudget.calculate(newYear, targets, emptyMap())
        assertEquals(LocalDate.of(2026, 12, 28), state.start)
        assertEquals(LocalDate.of(2027, 1, 3), state.end)
        assertEquals(3, state.remainingDays)
    }

    @Test fun invalidTargetsAndActualsAreRejected() {
        listOf(-0.1, 1.1, Double.NaN).forEach { ratio ->
            assertThrows(IllegalArgumentException::class.java) { targets.copy(limitRatio = ratio) }
        }
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { calories ->
            assertThrows(IllegalArgumentException::class.java) {
                WeekBudget.calculate(monday, targets, mapOf(monday to calories))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { targets.copy(calories = 0.0) }
    }

    @Test fun mealTimeBoundariesUseSystemZoneWithNoAutomaticSnacks() {
        val zone = ZoneId.of("Europe/Istanbul")
        val times = mapOf(
            "04:59:59" to MealPeriod.Evening,
            "05:00:00" to MealPeriod.Morning,
            "11:59:59" to MealPeriod.Morning,
            "12:00:00" to MealPeriod.Day,
            "16:59:59" to MealPeriod.Day,
            "17:00:00" to MealPeriod.Evening,
            "23:59:59" to MealPeriod.Evening,
            "00:00:00" to MealPeriod.Evening,
        )
        times.forEach { (time, expected) ->
            val instant = LocalDateTime.parse("2026-10-06T$time").atZone(zone).toInstant()
            assertEquals(expected, MealPeriods.resolve(instant, zone))
            assertNotEquals(MealPeriod.Snack, MealPeriods.resolve(instant, zone))
        }
    }

    @Test fun explicitMorningAndSnackAlwaysOverrideInferredEvening() {
        val instant = Instant.parse("2026-10-06T17:00:00Z")
        val zone = ZoneId.of("Europe/Istanbul")
        assertEquals(MealPeriod.Evening, MealPeriods.resolve(instant, zone))
        assertEquals(MealPeriod.Morning, MealPeriods.resolve(instant, zone, MealPeriod.Morning))
        assertEquals(MealPeriod.Snack, MealPeriods.resolve(instant, zone, MealPeriod.Snack))
    }

    @Test fun identicalInstantClassifiesDifferentlyAcrossZones() {
        val instant = Instant.parse("2026-10-06T03:00:00Z")
        assertEquals(MealPeriod.Evening, MealPeriods.resolve(instant, ZoneId.of("UTC")))
        assertEquals(MealPeriod.Morning, MealPeriods.resolve(instant, ZoneId.of("Europe/Istanbul")))
    }

    @Test fun daylightSavingInstantConversionUsesIanaRules() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(MealPeriod.Evening, MealPeriods.resolve(Instant.parse("2026-03-29T02:30:00Z"), berlin))
        assertEquals(MealPeriod.Morning, MealPeriods.resolve(Instant.parse("2026-03-29T03:00:00Z"), berlin))
    }

    @Test fun gramsScaleFractionOfStoredPackageAndEveryMacro() {
        val basis = Nutrition(435.0, 51.0, 15.0, 9.6)
        val result = QuantityScaling.scale(300.0, basis, 125.0)
        assertEquals(181.25, result.calories, 0.000001)
        assertEquals(21.25, result.protein, 0.000001)
        assertEquals(6.25, result.fat, 0.000001)
        assertEquals(4.0, result.carbs, 0.000001)
    }

    @Test fun piecesAndMillilitresUseTheirNaturalBasis() {
        assertEquals(Nutrition(600.0, 30.0, 20.0, 70.0), QuantityScaling.scale(1.0, Nutrition(300.0, 15.0, 10.0, 35.0), 2.0))
        assertEquals(Nutrition(50.0, 1.0, 0.0, 11.0), QuantityScaling.scale(250.0, Nutrition(100.0, 2.0, 0.0, 22.0), 125.0))
    }

    @Test fun repeatedEditsAlwaysUseImmutableBasisRatherThanRoundedCurrentTotals() {
        val entry = DiaryEntry(
            id = "entry", name = "Творог 5%", brand = null,
            quantity = 125.0, unit = "g", basisAmount = 300.0,
            basisNutrition = Nutrition(435.0, 51.0, 15.0, 9.6),
            nutrition = Nutrition(181.0, 21.0, 6.3, 4.0),
            consumedAt = Instant.parse("2026-10-06T06:00:00Z"), meal = MealPeriod.Morning,
            variantId = "variant", foodId = "food", confidence = null,
        )
        var edited = entry
        repeat(100) {
            edited = QuantityScaling.update(QuantityScaling.update(edited, 77.0), 125.0)
        }
        assertEquals(181.25, edited.nutrition.calories, 0.0)
        assertEquals(entry.basisNutrition, edited.basisNutrition)
        assertEquals(entry.basisAmount, edited.basisAmount, 0.0)
        assertEquals(entry.unit, edited.unit)
        assertEquals(entry.consumedAt, edited.consumedAt)
        assertEquals(entry.meal, edited.meal)
        assertEquals(entry.basisNutrition, QuantityScaling.update(edited, 300.0).nutrition)
    }

    @Test fun quantitiesAndBasisRejectZeroNegativeAndNonFiniteValues() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { amount ->
            assertThrows(IllegalArgumentException::class.java) { QuantityScaling.scale(amount, Nutrition.Zero, 1.0) }
            assertThrows(IllegalArgumentException::class.java) { QuantityScaling.scale(1.0, Nutrition.Zero, amount) }
        }
    }

    @Test fun canonicalMealWireValuesRoundTripAndRejectUnknownInput() {
        MealPeriod.entries.forEach { assertEquals(it, MealPeriod.fromWire(it.wireValue)) }
        assertThrows(IllegalArgumentException::class.java) { MealPeriod.fromWire("breakfast") }
    }
}
