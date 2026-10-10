package com.kxsxlxv.jetmeal.ui

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class MonthlyDeviationTest {
    private val month=YearMonth.of(2026,10)
    private val today=LocalDate.of(2026,10,10)

    @Test fun requiresSixCompletedRecordedDaysWithActualTargets() {
        val base=(1..6).associate { month.atDay(it) to (1800.0+it*20) }
        val goals=(1..6).associate { month.atDay(it) to 1800.0 }
        val five=monthDeviations(month,base.minus(month.atDay(6)),goals,today)
        assertFalse(showMonthDeviation(five))
        val six=monthDeviations(month,base,goals,today)
        assertTrue(showMonthDeviation(six))
        assertEquals(6,six.size)
        assertEquals(month.atDay(1),six.first().date)
    }

    @Test fun usesDynamicDailyTargetsAndKeepsSignedMagnitude() {
        val consumed=mapOf(month.atDay(1) to 1200.0,
            month.atDay(2) to 1600.0,
            month.atDay(3) to 0.0)
        val goals=mapOf(month.atDay(1) to 1000.0,
            month.atDay(2) to 2000.0,
            month.atDay(3) to 1800.0)
        val points=monthDeviations(month,consumed,goals,today)
        assertEquals(20.0,points[0].percent,1e-6)
        assertEquals(-20.0,points[1].percent,1e-6)
        assertEquals(-100.0,points[2].percent,1e-6)
    }

    @Test fun adaptiveScaleEnlargesNormalDeviationsWithoutFlatteningByOneOutlier() {
        val points=listOf(2.0,3.0,4.0,5.0,6.0,95.0)
            .mapIndexed { index,pct -> MonthDeviation(month.atDay(index+1),pct) }
        assertEquals(10.0,monthlyDeviationScale(points),0.0)
        assertEquals("+95%",deviationLabel(points.last().percent))
        assertEquals("−5%",deviationLabel(-5.0))
        val ordinary=listOf(-11.0,19.0,14.0,17.0,6.0,21.0)
            .mapIndexed { index,pct -> MonthDeviation(month.atDay(index+1),pct) }
        assertEquals(20.0,monthlyDeviationScale(ordinary),0.0)
    }

    @Test fun omitsTodayFutureMissingAndZeroTargetDays() {
        val consumed=mapOf(month.atDay(1) to 2000.0,
            month.atDay(2) to 1500.0,
            month.atDay(10) to 100.0,
            month.atDay(11) to 1500.0)
        val goals=mapOf(month.atDay(1) to 2000.0,
            month.atDay(2) to 0.0,
            month.atDay(3) to 1900.0,
            month.atDay(10) to 2000.0,
            month.atDay(11) to 2000.0)
        val points=monthDeviations(month,consumed,goals,today)
        assertEquals(listOf(month.atDay(1)),points.map {it.date})
    }
}

class WeeklyBudgetRailTest {
    @Test fun targetRemainsVisibleBeforeAndAfterOverflow() {
        val partial=weeklyBudgetRail(7000.0,14000.0)
        assertFalse(partial.overBudget)
        assertEquals(7000.0,partial.remaining,0.0)
        assertEquals(.8f,partial.goalFraction,1e-5f)
        assertTrue(partial.consumedFraction < partial.goalFraction)

        val over=weeklyBudgetRail(16400.0,14000.0)
        assertTrue(over.overBudget)
        assertEquals(-2400.0,over.remaining,0.0)
        assertTrue(over.consumedFraction > over.goalFraction)
        assertTrue(over.consumedFraction <= 1f)
    }

    @Test fun zeroAndUnusualTargetsStaySafe() {
        val zero=weeklyBudgetRail(0.0,0.0)
        assertEquals(0f,zero.goalFraction,0f)
        assertEquals(0f,zero.consumedFraction,0f)
        val missingBudget=weeklyBudgetRail(400.0,0.0)
        assertTrue(missingBudget.overBudget)
        assertTrue(missingBudget.consumedFraction>0f)
    }

    @Test fun rejectsNonFiniteTotalsRatherThanPaintingBrokenRail() {
        try {
            weeklyBudgetRail(Double.NaN,1200.0)
            fail("Expected reject")
        } catch(_:IllegalArgumentException) {}
    }
}
