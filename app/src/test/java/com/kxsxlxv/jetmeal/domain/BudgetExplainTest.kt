package com.kxsxlxv.jetmeal.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BudgetExplainTest {
    private val monday=LocalDate.of(2026,10,5)
    private val targets=Targets(2000.0,170.0,70.0,180.0)
    private fun daily():Map<LocalDate,Targets> =
        (0L..6L).associate { monday.plusDays(it) to targets }

    @Test fun surplusLowersTodayAndExplainsItsSource() {
        val tuesday=monday.plusDays(1)
        val week=WeekBudget.calculate(tuesday,targets,mapOf(monday to 2500.0))
        val explanation=BudgetExplain.calculate(week,daily())
        assertEquals(500.0,explanation.cumulativeDifference,.00001)
        assertEquals(2000.0-500.0/6,explanation.theoretical,.00001)
        assertEquals(week.effectiveTarget,explanation.effective,.00001)
        assertEquals(-500.0/6,explanation.adjustment,.00001)
        assertEquals(500.0,explanation.causes.single().difference!!,.00001)
        assertFalse(explanation.hasLimit)
    }

    @Test fun missingDatesCannotCreateFictitiousSaving() {
        val wed=monday.plusDays(2)
        val week=WeekBudget.calculate(wed,targets,
            mapOf(monday.plusDays(1) to 1800.0))
        val explanation=BudgetExplain.calculate(week,daily())
        assertEquals(1,explanation.missingDays)
        assertNull(explanation.causes.first().consumed)
        assertNull(explanation.causes.first().difference)
        assertEquals(-200.0,explanation.cumulativeDifference,.00001)
    }

    @Test fun clampDisplaysRawTargetAndResidualAndChangedHistoricalBaselines() {
        val tuesday=monday.plusDays(1)
        val historical=targets.copy(calories=2100.0)
        val versioned=daily().toMutableMap().apply { put(monday,historical) }
        val week=WeekBudget.calculate(tuesday,targets,
            mapOf(monday to 5000.0),dailyTargets=versioned)
        val explanation=BudgetExplain.calculate(week,versioned)
        assertEquals(2900.0,explanation.cumulativeDifference,.00001)
        assertEquals(1800.0,explanation.effective,.00001)
        assertEquals(2000.0-2900.0/6,explanation.theoretical,.00001)
        assertTrue(explanation.hasLimit)
        assertEquals(2900.0+(-200.0)*6,explanation.undistributed,.00001)
        assertEquals(2100.0,explanation.causes.single().baseline,.00001)
    }
    @Test fun closedWeekIncludesSundayAndReconcilesWithFullBudget() {
        val sunday = monday.plusDays(6)
        val consumed = (0L..5L).associate { monday.plusDays(it) to
            (if (it == 0L) 2960.0 else 2000.0) } + (sunday to 1624.6)
        val week = WeekBudget.calculate(sunday, targets, consumed,
            asOfDayCompleted = true, dailyTargets = daily())
        // The allowance calculation before Sunday ends deliberately has only 6 days.
        assertEquals(960.0, week.deviation, .00001)
        assertEquals(760.0, week.residual, .00001)
        // A CLOSED week, in contrast, includes the seventh day's consumption.
        val report = BudgetExplain.completedWeek(week, daily())
        assertTrue(report.allDaysRecorded)
        assertEquals(7, report.recordedDays)
        assertEquals(7, report.causes.size)
        assertEquals(sunday, report.causes.last().date)
        assertEquals(-375.4, report.causes.last().difference!!, .00001)
        assertEquals(584.6, report.recordedDifference, .00001)
        assertEquals(week.totalConsumed - week.baseBudget, report.recordedDifference, .00001)
    }

    @Test fun missingSundayIsStillUnknownInClosedWeek() {
        val sunday = monday.plusDays(6)
        val consumed = (0L..5L).associate { monday.plusDays(it) to 2000.0 }
        val week = WeekBudget.calculate(sunday, targets, consumed,
            asOfDayCompleted = true, dailyTargets = daily())
        val report = BudgetExplain.completedWeek(week, daily())
        assertFalse(report.allDaysRecorded)
        assertEquals(6, report.recordedDays)
        assertEquals(sunday, report.causes.last().date)
        assertNull(report.causes.last().consumed)
        assertNull(report.causes.last().difference)
    }


}
