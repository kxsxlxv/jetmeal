package com.kxsxlxv.jetmeal.data

import com.kxsxlxv.jetmeal.domain.DiaryEntry
import com.kxsxlxv.jetmeal.domain.MealPeriod
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.domain.Targets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class OfflineSnapshotMergeTest {
    private val zone=ZoneId.systemDefault()
    private val targets=Targets(2000.0,160.0,65.0,180.0)
    private fun date(value:String)=LocalDate.parse(value)
    private fun entry(id:String,day:String,amount:Double=100.0):DiaryEntry {
        val instant=date(day).atTime(12,0).atZone(zone).toInstant()
        val nutrition=Nutrition(amount,amount/10,0.0,0.0)
        return DiaryEntry(id,"Тест",null,amount,"g",100.0,
            Nutrition(100.0,10.0,0.0,0.0),nutrition,instant,
            MealPeriod.Day,null,null,null,updatedAt=instant)
    }
    private fun snapshot(start:String,end:String,vararg entries:DiaryEntry)=OfflineSnapshot(
        date(start),date(end),entries.toList(),targets,emptyList(),emptySet()
    )

    @Test fun visitedDaysRemainAvailableAfterDownloadingAnotherMonth() {
        val first=snapshot("2026-08-01","2026-09-01",entry("a","2026-08-08"))
        val second=snapshot("2026-10-01","2026-11-01",entry("b","2026-10-15"))
        val union=mergeOfflineSnapshots(first,second)
        assertEquals(listOf("a","b"),union.entries.map { it.id })
        assertTrue(union.covers(date("2026-08-01"),date("2026-09-01")))
        assertTrue(union.covers(date("2026-10-01"),date("2026-11-01")))
        assertFalse("Do not pretend an unvisited September has zero logged calories",
            union.covers(date("2026-09-01"),date("2026-10-01")))
        assertFalse(union.covers(date("2026-08-01"),date("2026-11-01")))
    }

    @Test fun overlappingServerPeriodReplacesUpdatedAndDeletedEntries() {
        val first=snapshot("2026-08-01","2026-10-01",
            entry("aug","2026-08-05"),entry("removed","2026-09-05"),
            entry("updated","2026-09-06"))
        val next=snapshot("2026-09-01","2026-11-01",
            entry("updated","2026-09-06",300.0),entry("oct","2026-10-08"))
        val merged=mergeOfflineSnapshots(first,next)
        assertEquals(listOf("aug","updated","oct"),merged.entries.map { it.id })
        assertEquals(300.0,merged.entries.first { it.id=="updated" }.quantity,.001)
        assertTrue(merged.covers(date("2026-08-01"),date("2026-11-01")))
        assertEquals(listOf(CachedDateRange(date("2026-08-01"),date("2026-11-01"))),
            merged.coverage)
    }

    @Test fun zeroDayConfirmationsOnlyReplacedInFetchedRange() {
        val first=snapshot("2026-09-01","2026-10-01").copy(
            zeroDays=setOf(date("2026-09-06"),date("2026-09-20")))
        val next=snapshot("2026-09-20","2026-10-21").copy(
            zeroDays=setOf(date("2026-10-02")))
        val merged=mergeOfflineSnapshots(first,next)
        assertEquals(setOf(date("2026-09-06"),date("2026-10-02")),merged.zeroDays)
    }

    @Test fun oldCacheWithoutExplicitCoverageIsStillValid() {
        val old=snapshot("2026-10-01","2026-11-01")
        assertEquals(listOf(CachedDateRange(date("2026-10-01"),date("2026-11-01"))),
            old.coverage)
        assertTrue(old.covers(date("2026-10-12"),date("2026-10-19")))
    }

    @Test fun joiningAdjacentWindowsEliminatesOnlyRealGaps() {
        val ranges=mergedCoverage(listOf(
            CachedDateRange(date("2026-07-01"),date("2026-08-01")),
            CachedDateRange(date("2026-08-01"),date("2026-10-01")),
            CachedDateRange(date("2026-11-01"),date("2026-12-01"))
        ))
        assertEquals(2,ranges.size)
        assertEquals(date("2026-10-01"),ranges.first().end)
    }
}
