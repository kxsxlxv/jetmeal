package com.kxsxlxv.jetmeal.ui

import java.time.LocalDate
import java.time.YearMonth
import androidx.compose.ui.unit.dp
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

    @Test fun chartIsKeptVisibleInCompactFiveAndSixRowMonths() {
        for (rows in 4..6) {
            for (height in listOf(580.dp,620.dp,700.dp)) {
                val sizing=monthCalendarSizing(height,390.dp,rows,true,true)
                val baseline=20.dp+20.dp+48.dp+91.dp+8.dp*4+4.dp*(rows-1)+116.dp
                val occupied=baseline+sizing.chartPlot+sizing.tile*rows
                assertTrue("No-scroll $rows row month at $height occupies $occupied",
                    occupied<=height)
                assertTrue(sizing.chartPlot>=36.dp)
            }
        }
        val six=monthCalendarSizing(600.dp,390.dp,6,true,true)
        assertTrue("Calendar days remain square with readable side",six.tile>=28.dp)
    }

    @Test fun chartVisibilityMustNotDependOnHeightAfterSixKnownDays() {
        val days=(1..6).map {MonthDeviation(month.atDay(it),it.toDouble())}
        assertTrue(showMonthDeviation(days))
        val small=monthCalendarSizing(580.dp,390.dp,5,true,true)
        assertTrue(small.chartPlot>0.dp)
        val wider=monthCalendarSizing(740.dp,390.dp,5,true,true)
        assertTrue(wider.chartPlot>=small.chartPlot)
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

    @Test fun positiveOnlyMonthsUseTheFullHeightWithoutClipping() {
        val points=listOf(40.2,16.3,1.0,7.3,7.5,4.1)
            .mapIndexed { index,pct-> MonthDeviation(month.atDay(index+4),pct) }
        val axis=monthlyDeviationAxis(points)
        assertEquals(0.0,axis.minimum,0.0)
        assertEquals(45.0,axis.maximum,0.0)
        assertEquals(1f,axis.y(0.0),1e-6f)
        assertEquals(0.0,axis.y(45.0).toDouble(),1e-6)
        assertTrue(axis.y(40.2)<axis.y(16.3))
        assertEquals(45.0,monthlyDeviationScale(points),0.0)
    }

    @Test fun negativeOnlyMonthsUseFullHeightAndMixedMonthsKeepZero() {
        val downs=listOf(-3.0,-8.0,-26.2)
            .mapIndexed { i,p ->MonthDeviation(month.atDay(i+1),p) }
        val downAxis=monthlyDeviationAxis(downs)
        assertEquals(0.0,downAxis.maximum,0.0)
        assertEquals(-30.0,downAxis.minimum,0.0)
        assertEquals(0f,downAxis.y(0.0),1e-6f)
        val mixed=monthlyDeviationAxis(downs+MonthDeviation(month.atDay(6),20.8))
        assertEquals(-30.0,mixed.minimum,0.0)
        assertEquals(25.0,mixed.maximum,0.0)
        assertTrue(mixed.y(20.8)<mixed.y(0.0))
        assertTrue(mixed.y(0.0)<mixed.y(-26.2))
    }

    @Test fun zeroOnlyMonthsAndSignsRemainReadable() {
        val axis=monthlyDeviationAxis(listOf(MonthDeviation(month.atDay(1),0.0)))
        assertEquals(-10.0,axis.minimum,0.0)
        assertEquals(10.0,axis.maximum,0.0)
        assertEquals("+40,2%",deviationLabel(40.2))
        assertEquals("−5%",deviationLabel(-5.0))
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
