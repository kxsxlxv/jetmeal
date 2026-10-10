package com.kxsxlxv.jetmeal.ui

import org.junit.Assert.*
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Test

class CalendarAdherenceTest {
    @Test fun zeroEffectiveAllowanceIsARealTarget() {
        assertEquals("Близко к норме",CalendarAdherence.status(0.0,0.0))
        assertEquals("Выше нормы",CalendarAdherence.status(123.0,0.0))
        assertEquals("↑",CalendarAdherence.marker(123.0,0.0))
        assertEquals(0.0,CalendarAdherence.distance(0.0,0.0)!!,0.0)
        assertTrue(CalendarAdherence.distance(123.0,0.0)!!.isInfinite())
    }
    @Test fun missingGoalIsDistinctFromZeroAllowance() {
        assertEquals("Без цели",CalendarAdherence.status(123.0,null))
        assertNull(CalendarAdherence.distance(123.0,null))
    }
    @Test fun statusAndMarkerRemainUsefulWithoutColor() {
        assertEquals("≈",CalendarAdherence.marker(2050.0,2000.0))
        assertEquals("↑",CalendarAdherence.marker(2500.0,2000.0))
        assertEquals("↓",CalendarAdherence.marker(1500.0,2000.0))
    }

    @Test fun monthUsesMondayFirstAndWholeSquareWeekRows() {
        val october = YearMonth.of(2026,10) // Thursday the first
        assertEquals(5, calendarWeekCount(october))
        assertNull(calendarDateAt(october,0,0)) // Monday is blank
        assertNull(calendarDateAt(october,0,2))
        assertEquals(LocalDate.of(2026,10,1), calendarDateAt(october,0,3))
        assertEquals(LocalDate.of(2026,10,4), calendarDateAt(october,0,6))
        assertEquals(LocalDate.of(2026,10,31), calendarDateAt(october,4,5))
        assertNull(calendarDateAt(october,4,6))
        val may2026 = YearMonth.of(2026,5)
        assertEquals(5,calendarWeekCount(may2026))
        assertEquals(6,calendarWeekCount(YearMonth.of(2026,8)))
        assertEquals(4,calendarWeekCount(YearMonth.of(2027,2)))
    }

    @Test fun dayColorsHaveOneDocumentedMeaning() {
        val now=LocalDate.of(2026,10,10)
        val yesterday=now.minusDays(1)
        assertEquals(CalendarTone.Missing,calendarTone(yesterday,null,1800.0,now))
        assertEquals(CalendarTone.ConfirmedZero,calendarTone(yesterday,0.0,1800.0,now,true))
        assertEquals(CalendarTone.OnTarget,calendarTone(yesterday,1850.0,1800.0,now))
        assertEquals(CalendarTone.Above,calendarTone(yesterday,2200.0,1800.0,now))
        assertEquals(CalendarTone.Below,calendarTone(yesterday,1200.0,1800.0,now))
        assertEquals(CalendarTone.InProgress,calendarTone(now,650.0,1800.0,now))
        assertEquals(CalendarTone.NoTarget,calendarTone(yesterday,650.0,null,now))
    }
}
