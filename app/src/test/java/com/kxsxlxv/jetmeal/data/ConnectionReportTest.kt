package com.kxsxlxv.jetmeal.data

import org.junit.Assert.*
import org.junit.Test

class ConnectionReportTest {
    @Test fun clipboardGetsLatestEventsNotOldest() {
        val events = (1..160).joinToString("\n") { "2026-10-28T17:00:${it}Z event-${it}" }
        val result = lastConnectionEvents(events, 20)
        assertEquals(20, result.lines().size)
        assertTrue(result.contains("event-160"))
        assertFalse(result.contains("event-140"))
    }

    @Test fun emptyLinesAreIgnored() {
        assertEquals("second\nthird", lastConnectionEvents("first\n\nsecond\nthird\n", 2))
        assertEquals("", lastConnectionEvents("\n\n", 20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveLimit() {
        lastConnectionEvents("some event", 0)
    }
}
