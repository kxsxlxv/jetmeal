package com.kxsxlxv.jetmeal.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WeightProgressTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val today = LocalDate.of(2026, 10, 9)
    private fun reading(date: LocalDate, kg: Double, hour: Int = 8) =
        WeightMeasurement("id:${date}:${hour}",date.atTime(hour,0).atZone(zone).toInstant(),
            kg,null,"picooc")
    private val plan = WeightGoal(today.minusDays(1),today.plusDays(57),105.9,100.0)

    @Test fun latestMeasurementIsComparedToPlanOnItsOwnDateNotWeeklyAverage() {
        val data = WeightProgress.build(listOf(
            reading(today.minusDays(4),108.0),
            reading(today.minusDays(2),106.7),
            reading(today.minusDays(1),105.9),
        ),plan,today,zone)
        assertEquals(105.9,data.latest!!.kilograms,.00001)
        assertEquals(105.9,data.latestPlanKg!!,.00001)
        assertEquals(0.0,data.actualMinusPlanKg!!,.00001)
        assertTrue(data.trend7Kg!! > data.latest!!.kilograms)
        assertEquals(57L,data.remainingDays)
        assertEquals((100.0-105.9)*7/57,data.requiredWeeklyKg!!,.00001)
    }

    @Test fun ignoresMeasurementsBeforeGoalWhenComputingDeviation() {
        val futureStart = WeightGoal(today, today.plusDays(60),105.0,100.0)
        val data = WeightProgress.build(listOf(reading(today.minusDays(1),105.9)),
            futureStart,today,zone)
        assertNull(data.latestPlanKg)
        assertNull(data.actualMinusPlanKg)
        assertEquals(105.0,data.planTodayKg!!,.00001)
    }

    @Test fun repeatWeighingSameDayUsesLatestReadingAndLocalCalendarDate() {
        val earlier = reading(today.minusDays(1),106.3,6)
        val later = reading(today.minusDays(1),105.9,10)
        val model = WeightProgress.build(listOf(later,earlier),plan,today,zone)
        assertEquals(1,model.days.size)
        assertEquals(105.9,model.latest!!.kilograms,.000001)
    }

    @Test fun weeklyPaceUsesCalendarTimeNotEquallySpacedChartDots() {
        // 8-day span: a measured -1.4 kg is about -1.225 kg/week,
        // not a spurious rate based on 2 equally spaced graph intervals.
        val points=listOf(reading(today.minusDays(8),107.0),
            reading(today.minusDays(7),106.8),reading(today,105.6))
        val model=WeightProgress.build(points,plan,today,zone)
        assertNotNull(model.observedWeeklyKg)
        assertTrue(model.observedWeeklyKg!! < 0)
        assertNull(WeightProgress.build(
            listOf(reading(today.minusDays(2),106.0),reading(today,105.6)),plan,today,zone
        ).observedWeeklyKg)
    }

    @Test fun graphHistoryLimitsToRecentEighteenDaysAndKeepsRealDates() {
        val model=WeightProgress.build((0L..30L).map {
            reading(today.minusDays(30L-it),104.0+it/10.0)
        },plan,today,zone)
        assertEquals(18,model.days.size)
        assertEquals(today.minusDays(17),model.days.first().date)
        assertEquals(today,model.days.last().date)
    }

    @Test fun missedGoalDeadlineDoesNotReportRequiredWeeklyRate() {
        val past = WeightGoal(today.minusDays(60),today.minusDays(1),110.0,100.0)
        val model = WeightProgress.build(listOf(reading(today,105.9)),past,today,zone)
        assertEquals(0L,model.remainingDays)
        assertNull(model.requiredWeeklyKg)
        assertNull(model.latestPlanKg)
    }
}
