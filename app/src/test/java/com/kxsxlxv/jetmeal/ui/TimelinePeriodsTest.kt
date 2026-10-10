package com.kxsxlxv.jetmeal.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelinePeriodsTest {
    private val today = LocalDate.of(2026, 10, 6)

    @Test fun staleWeekNeverMatchesNextPagerPage() {
        val monday=LocalDate.of(2026,10,5)
        val days=(0L..6L).map { index ->
            com.kxsxlxv.jetmeal.domain.BudgetDay(monday.plusDays(index),
                1000.0,1800.0)
        }
        val week=com.kxsxlxv.jetmeal.domain.WeekState(
            monday,monday.plusDays(6),days,12600.0,7000.0,0.0,3,1800.0,0.0)
        assertTrue(weekMatchesPeriod(week,monday.plusDays(3)))
        assertTrue(!weekMatchesPeriod(week,monday.plusDays(7)))
        assertTrue(!weekMatchesPeriod(null,monday))
        assertEquals(1450,weekFillMotion().durationMillis)
    }

    @Test fun swipesChangeOnlyScaleAndStopAtEdges() {
        assertEquals(TimeScale.Week,adjacentTimeScale(TimeScale.Day,1))
        assertEquals(TimeScale.Month,adjacentTimeScale(TimeScale.Week,1))
        assertEquals(TimeScale.Quarter,adjacentTimeScale(TimeScale.Month,1))
        assertEquals(TimeScale.Week,adjacentTimeScale(TimeScale.Month,-1))
        assertEquals(TimeScale.Day,adjacentTimeScale(TimeScale.Day,-1))
        assertEquals(TimeScale.Quarter,adjacentTimeScale(TimeScale.Quarter,1))
        assertEquals(listOf("День","Неделя","Месяц","3 месяца"),
            TimeScale.entries.map {it.label})
    }

    @Test fun dateArrowsStayWithinTheSelectedScale() {
        assertEquals(today.plusDays(1),TimelinePeriods.move(TimeScale.Day,today,1))
        assertEquals(LocalDate.of(2026,10,12),
            TimelinePeriods.move(TimeScale.Week,today,1))
        assertEquals(LocalDate.of(2026,11,1),
            TimelinePeriods.move(TimeScale.Month,today,1))
        assertEquals(LocalDate.of(2027,1,1),
            TimelinePeriods.move(TimeScale.Quarter,today,1))
    }

    @Test fun weekUsesMondayThroughSundayAcrossNewYear() {
        val date = LocalDate.of(2027, 1, 1)
        assertEquals(LocalDate.of(2026, 12, 28), TimelinePeriods.start(TimeScale.Week, date))
        assertEquals(LocalDate.of(2027, 1, 4), TimelinePeriods.endExclusive(TimeScale.Week, date))
        assertEquals(LocalDate.of(2026, 12, 28), TimelinePeriods.start(TimeScale.Week, date.plusDays(2)))
        assertEquals(LocalDate.of(2027, 1, 4), TimelinePeriods.start(TimeScale.Week, date.plusDays(3)))
    }

    @Test fun monthIncludesLeapDayAndEndsAtNextMonth() {
        val leapDay = LocalDate.of(2028, 2, 29)
        assertEquals(LocalDate.of(2028, 2, 1), TimelinePeriods.start(TimeScale.Month, leapDay))
        assertEquals(LocalDate.of(2028, 3, 1), TimelinePeriods.endExclusive(TimeScale.Month, leapDay))
        assertEquals(LocalDate.of(2027, 3, 1), TimelinePeriods.endExclusive(TimeScale.Month, LocalDate.of(2027, 2, 28)))
    }

    @Test fun quarterUsesCalendarAlignedThreeMonthBlocks() {
        for (month in 1..12) {
            val date = LocalDate.of(2026, month, 19)
            val firstMonth = ((month - 1) / 3) * 3 + 1
            val start = LocalDate.of(2026, firstMonth, 1)
            assertEquals(start, TimelinePeriods.start(TimeScale.Quarter, date))
            assertEquals(start.plusMonths(3), TimelinePeriods.endExclusive(TimeScale.Quarter, date))
        }
    }

    @Test fun movingQuarterAcrossYearsDoesNotDependOnSelectedDay() {
        val endOfYear = LocalDate.of(2026, 12, 31)
        assertEquals(LocalDate.of(2027, 1, 1), TimelinePeriods.move(TimeScale.Quarter, endOfYear, 1))
        assertEquals(LocalDate.of(2026, 7, 1), TimelinePeriods.move(TimeScale.Quarter, endOfYear, -1))
        assertEquals(LocalDate.of(2026, 10, 1), TimelinePeriods.move(TimeScale.Quarter, endOfYear, 0))
    }

    @Test fun movingMonthFromLastDayUsesPeriodStarts() {
        val january = LocalDate.of(2028, 1, 31)
        assertEquals(LocalDate.of(2028, 2, 1), TimelinePeriods.move(TimeScale.Month, january, 1))
        assertEquals(LocalDate.of(2027, 12, 1), TimelinePeriods.move(TimeScale.Month, january, -1))
        assertEquals(LocalDate.of(2029, 1, 1), TimelinePeriods.move(TimeScale.Month, january, 12))
    }

    @Test fun arrowsAndPagingHaveReversiblePeriodOffsets() {
        for (scale in TimeScale.entries) {
            for (offset in listOf(-4, -1, 0, 1, 4)) {
                val moved = TimelinePeriods.move(scale, today, offset)
                assertEquals(TimelinePeriods.start(scale, today), TimelinePeriods.move(scale, moved, -offset))
                assertEquals(moved, TimelinePeriods.start(scale, moved))
                assertTrue(TimelinePeriods.endExclusive(scale, moved).isAfter(moved))
            }
        }
    }

    @Test fun dayTitlesUseRussianRelativeNamesAndHistoricalYear() {
        assertEquals("Сегодня", TimelinePeriods.title(TimeScale.Day, today, today))
        assertEquals("Вчера, 5 октября", TimelinePeriods.title(TimeScale.Day, today.minusDays(1), today))
        assertEquals("4 октября", TimelinePeriods.title(TimeScale.Day, today.minusDays(2), today))
        assertEquals("6 октября 2025", TimelinePeriods.title(TimeScale.Day, today.minusYears(1), today))
    }

    @Test fun periodTitlesUseRussianGrammaticalMonthForms() {
        assertEquals("5–11 октября", TimelinePeriods.title(TimeScale.Week, today, today))
        assertEquals("28 сентября – 4 октября", TimelinePeriods.title(TimeScale.Week, LocalDate.of(2026, 10, 1), today))
        assertEquals("Октябрь 2026", TimelinePeriods.title(TimeScale.Month, today, today))
        assertEquals("Октябрь–декабрь 2026", TimelinePeriods.title(TimeScale.Quarter, today, today))
    }

    @Test fun weekTitleRetainsYearWhenIntervalCrossesYearBoundary() {
        assertEquals(
            "28 декабря 2026 – 3 января 2027",
            TimelinePeriods.title(TimeScale.Week, LocalDate.of(2026, 12, 31), today),
        )
    }

    @Test fun nearbyDayNavigationFitsPrefetchedRangeWithoutNewRequests() {
        val (start,end)=timelineFetchRange(TimeScale.Day,LocalDate.of(2026,10,10))
        val october20=timelineRequiredRange(TimeScale.Day,LocalDate.of(2026,10,20))
        assertTrue(start<=october20.first && end>=october20.second)
        val november2=timelineRequiredRange(TimeScale.Day,LocalDate.of(2026,11,2))
        assertTrue(start<=november2.first && end>=november2.second)
    }

    @Test fun nextMonthStillRequiresRefreshRatherThanDisplayingPartialData() {
        val (start,end)=timelineFetchRange(TimeScale.Day,LocalDate.of(2026,10,10))
        val november20=timelineRequiredRange(TimeScale.Day,LocalDate.of(2026,11,20))
        assertTrue(start>november20.first || end<november20.second)
        assertEquals(LocalDate.of(2026,9,28),
            timelineRequiredRange(TimeScale.Month,LocalDate.of(2026,10,10)).first)
        assertEquals(LocalDate.of(2026,11,1),
            timelineRequiredRange(TimeScale.Month,LocalDate.of(2026,10,10)).second)
    }
}
