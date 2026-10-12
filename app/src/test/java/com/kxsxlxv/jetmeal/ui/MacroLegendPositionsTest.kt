package com.kxsxlxv.jetmeal.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroLegendPositionsTest {
    private fun widths(full: Int = 130, percent: Int = 46, icon: Int = 20) =
        MacroLegendWidths(full, percent, icon)

    @Test fun unbalancedCompositionKeepsAllThreePercentagesOnOneLine() {
        val fractions = listOf(.76, .06, .18)
        val widths = listOf(widths(), widths(), widths())
        val placements = macroLegendLayout(1000, fractions, widths, minGap = 8)
        assertEquals(MacroLegendStyle.Full, placements[0].style)
        assertEquals(MacroLegendStyle.PercentOnly, placements[1].style)
        assertEquals(MacroLegendStyle.Full, placements[2].style)
        assertEquals(380, placements[0].left + widths[0].full / 2)
        assertEquals(790, placements[1].left + widths[1].percent / 2)
        assertEquals(910, placements[2].left + widths[2].full / 2)
        assertNoOverlaps(placements, widths, 8)
    }

    @Test fun compactPhoneWidthDropsNeighbouringIconInsteadOfTinyPercentage() {
        val fractions = listOf(.76, .06, .18)
        val widths = listOf(widths(95, 30, 16), widths(95, 30, 16), widths(95, 30, 16))
        val placements = macroLegendLayout(400, fractions, widths, minGap = 8)
        assertEquals(MacroLegendStyle.Full, placements[0].style)
        assertEquals(MacroLegendStyle.PercentOnly, placements[1].style)
        assertEquals(MacroLegendStyle.PercentOnly, placements[2].style)
        assertNoOverlaps(placements, widths, 8)
    }

    @Test fun balancedSegmentsKeepIconAndPercentForAllThree() {
        val widths = listOf(widths(100), widths(100), widths(100))
        val placements = macroLegendLayout(900, listOf(1.0, 1.0, 1.0), widths)
        assertTrue(placements.all { it.style == MacroLegendStyle.Full })
        assertEquals(listOf(150, 450, 750), placements.map { it.left + 50 })
        assertNoOverlaps(placements, widths, 4)
    }

    @Test fun almostZeroAdjacentSlicesNeverDrawOverlappingLabels() {
        val widths = listOf(widths(130, 48), widths(130, 48), widths(130, 48))
        val placements = macroLegendLayout(800, listOf(.98, .01, .01), widths, minGap = 6)
        assertNoOverlaps(placements, widths, 6)
        placements.forEachIndexed { index, placement ->
            val labelWidth = widths[index].forStyle(placement.style)
            assertTrue(placement.left >= 0)
            assertTrue(placement.left + labelWidth <= 800)
        }
    }

    @Test fun absentSectorIsHiddenAndRemainingLabelsUseRealPositions() {
        val widths = listOf(widths(100), widths(100), widths(100))
        val placements = macroLegendLayout(1000, listOf(.8, .0, .2), widths)
        assertEquals(MacroLegendStyle.Hidden, placements[1].style)
        assertEquals(400, placements[0].left + 50)
        assertEquals(900, placements[2].left + 50)
    }

    @Test fun emptyNutritionDoesNotInventThreePercentages() {
        val placements = macroLegendLayout(
            900, listOf(0.0, 0.0, 0.0), listOf(widths(), widths(), widths()))
        assertTrue(placements.all { it.style == MacroLegendStyle.Hidden })
    }

    private fun assertNoOverlaps(
        placements: List<MacroLegendPlacement>, widths: List<MacroLegendWidths>, gap: Int
    ) {
        val shown = placements.indices.filter { placements[it].style != MacroLegendStyle.Hidden }
        for ((a, b) in shown.zipWithNext()) {
            val end = placements[a].left + widths[a].forStyle(placements[a].style)
            assertTrue("Labels $a and $b overlap", end + gap <= placements[b].left)
        }
    }
}
