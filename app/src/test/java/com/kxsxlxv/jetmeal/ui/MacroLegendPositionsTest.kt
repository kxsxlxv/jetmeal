package com.kxsxlxv.jetmeal.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroLegendPositionsTest {
    @Test fun unbalancedCompositionAnchorsEachIconAndPercentToItsOwnSector() {
        // Corresponds to the reported 76% / 6% / 18% layout.
        val positions = macroLegendPositions(
            width = 1000,
            fractions = listOf(.76, .06, .18),
            visibleIndices = listOf(0, 1, 2),
            labelWidths = listOf(130, 130, 130),
            minGap = 8,
        )
        assertEquals(380, positions[0].left + 65) // center of 76% purple sector
        assertEquals(790, positions[1].left + 65) // center of 6% yellow sector
        assertEquals(910, positions[2].left + 65) // center of 18% coral sector
        // The narrow middle sector must not cause label overlap with the right one.
        assertEquals(0, positions[0].lane)
        assertEquals(1, positions[1].lane)
        assertEquals(0, positions[2].lane)
    }

    @Test fun nearEqualSegmentsStayOnOneCompactLine() {
        val positions = macroLegendPositions(
            width = 900,
            fractions = listOf(1.0 / 3, 1.0 / 3, 1.0 / 3),
            visibleIndices = listOf(0, 1, 2),
            labelWidths = listOf(100, 100, 100),
        )
        assertEquals(listOf(150, 450, 750), positions.map { it.left + 50 })
        assertTrue(positions.all { it.lane == 0 })
    }

    @Test fun tinySegmentsRemainInsideTheAvailableWidthWithoutCollisions() {
        val labelWidths = listOf(140, 140, 140)
        val positions = macroLegendPositions(
            width = 800,
            fractions = listOf(.98, .01, .01),
            visibleIndices = listOf(0, 1, 2),
            labelWidths = labelWidths,
        )
        positions.forEachIndexed { i, position ->
            assertTrue(position.left >= 0)
            assertTrue(position.left + labelWidths[i] <= 800)
        }
        assertTrue(positions[1].lane != positions[2].lane)
    }

    @Test fun absentMacroHasNoFakeSectorAndRemainingLabelsStillFollowTheirArcs() {
        val positions = macroLegendPositions(
            width = 1000,
            fractions = listOf(.8, 0.0, .2),
            visibleIndices = listOf(0, 2),
            labelWidths = listOf(100, 100),
        )
        assertEquals(400, positions[0].left + 50)
        assertEquals(900, positions[1].left + 50)
    }

    @Test fun zeroEnergyUsesNonoverlappingPlaceholderPositions() {
        val positions = macroLegendPositions(
            width = 900,
            fractions = listOf(0.0, 0.0, 0.0),
            visibleIndices = listOf(0, 1, 2),
            labelWidths = listOf(100, 100, 100),
        )
        assertEquals(listOf(150, 450, 750), positions.map { it.left + 50 })
        assertTrue(positions.all { it.lane == 0 })
    }
}
