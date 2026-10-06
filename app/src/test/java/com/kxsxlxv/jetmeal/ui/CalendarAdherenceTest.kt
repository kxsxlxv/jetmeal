package com.kxsxlxv.jetmeal.ui

import org.junit.Assert.*
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
}
