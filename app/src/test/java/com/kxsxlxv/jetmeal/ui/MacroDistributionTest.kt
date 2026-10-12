package com.kxsxlxv.jetmeal.ui

import com.kxsxlxv.jetmeal.domain.Nutrition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroDistributionTest {
    @Test fun energySharesUseFourNineFourRatherThanGramFractions() {
        val shares = macroEnergyShares(Nutrition(600.0, 20.0, 20.0, 20.0))
        assertEquals(80.0 / 340.0, shares.protein, 1e-9)
        assertEquals(180.0 / 340.0, shares.fat, 1e-9)
        assertEquals(80.0 / 340.0, shares.carbs, 1e-9)
        assertEquals(1.0, shares.fractions.sum(), 1e-9)
    }

    @Test fun lastWeeksRecordedMacrosDisplayTwentyTwoThirtyNineThirtyNine() {
        val percentages = macroEnergyPercentages(
            macroEnergyShares(Nutrition(14584.6, 820.1, 635.8, 1424.6)))
        assertEquals(listOf(22, 39, 39), percentages)
        assertEquals(100, percentages.sum())
    }

    @Test fun missingFoodDoesNotCreateNanOrInventPercentages() {
        val zero = macroEnergyShares(Nutrition.Zero)
        assertEquals(listOf(0.0, 0.0, 0.0), zero.fractions)
        assertEquals(listOf(0, 0, 0), macroEnergyPercentages(zero))
    }

    @Test fun singleMacroFillsTheEntireRing() {
        val percentages = macroEnergyPercentages(
            macroEnergyShares(Nutrition(100.0, 0.0, 10.0, 0.0)))
        assertEquals(listOf(0, 100, 0), percentages)
    }

    @Test fun smallRoundingFragmentsStillProduceExactlyOneHundred() {
        val shares = macroEnergyShares(Nutrition(1234.0, 37.0, 14.0, 62.0))
        val values = macroEnergyPercentages(shares)
        assertEquals(100, values.sum())
        assertTrue(values.all { it >= 0 })
    }

    @Test fun fixedYCategoryCentersAreStableAndExactlyOneHundredTwentyDegreesApart() {
        val balanced = macroDonutArcs(listOf(.34, .33, .33), MacroDonutLayout.FixedY)
        val stronglyUnbalanced = macroDonutArcs(listOf(.95, .01, .04), MacroDonutLayout.FixedY)
        assertEquals(balanced, stronglyUnbalanced)
        assertEquals(listOf(-150f, -30f, 90f), balanced.map { it.centerAngle })
        assertEquals(listOf(-210f, -90f, 30f), balanced.map { it.startAngle })
        assertTrue(balanced.all { it.sweepAngle == 120f })
        for (i in 0..1) {
            assertEquals(balanced[i].startAngle + 120f, balanced[i + 1].startAngle, 0.00001f)
        }
    }

    @Test fun oldProportionalSectorsRemainAvailableForQuickRollback() {
        val arcs = macroDonutArcs(listOf(.5, .25, .25), MacroDonutLayout.ProportionalOrdered)
        assertEquals(listOf(180f, 90f, 90f), arcs.map { it.sweepAngle })
        assertEquals(listOf(-90f, 90f, 180f), arcs.map { it.startAngle })
        assertEquals(listOf(0f, 135f, 225f), arcs.map { it.centerAngle })
    }


    @Test fun proteinAlwaysStartsAtTwelveThenFatAndCarbsFollowClockwise() {
        val input = listOf(.76, .06, .18)
        val arcs = macroDonutArcs(input, MacroDonutLayout.ProportionalOrdered)
        assertEquals(-90f, arcs[0].startAngle, .0001f)
        assertEquals(273.6f, arcs[0].sweepAngle, .001f)
        assertEquals(21.6f, arcs[1].sweepAngle, .001f)
        assertEquals(64.8f, arcs[2].sweepAngle, .001f)
        assertEquals(arcs[0].startAngle + arcs[0].sweepAngle,
            arcs[1].startAngle, .0001f)
        assertEquals(arcs[1].startAngle + arcs[1].sweepAngle,
            arcs[2].startAngle, .0001f)
        assertEquals(360f, arcs.sumOf { it.sweepAngle.toDouble() }.toFloat(), .001f)
    }

    @Test fun oneClockFillsEachSectorSequentiallyAndPreservesTwelveOClockStart() {
        val arcs = macroDonutArcs(listOf(.5, .3, .2), MacroDonutLayout.ProportionalOrdered)
        val initial = arcs.map { orderedDonutFrame(it, 0f) }
        assertEquals(-90f, initial[0].startAngle, .0001f)
        assertTrue(initial.all { it.drawnSweep == 0f && it.labelAlpha == 0f })

        // At 25% of the *draw phase*, the sweep head is at 90 degrees:
        // only protein is partially drawn, both later slices are untouched.
        val quarter = arcs.map { orderedDonutFrame(it, MacroDonutArcEnd * .25f) }
        assertEquals(90f, quarter[0].drawnSweep, .001f)
        assertEquals(0f, quarter[1].drawnSweep, .001f)
        assertEquals(0f, quarter[2].drawnSweep, .001f)
        assertEquals(-90f, quarter[0].startAngle, .0001f)

        // At 65% of the draw phase, protein is complete and fat draws next.
        val twoThirds = arcs.map { orderedDonutFrame(it, MacroDonutArcEnd * .65f) }
        assertTrue(twoThirds[0].drawnSweep > 170f)
        assertTrue(twoThirds[1].drawnSweep > 0f)
        assertEquals(0f, twoThirds[2].drawnSweep, .001f)

        // Last segment completes at 82% of the clock. Remaining 18% is
        // sufficient for the carb percentage to appear smoothly.
        val all = arcs.map { orderedDonutFrame(it, 1f) }
        assertTrue(all.all { it.drawnSweep > 0f && it.labelAlpha == 1f })
    }

    @Test fun curvedLabelFadesAfterItsOwnSectorFinishesIncludingSmallLastSlice() {
        val arcs = macroDonutArcs(listOf(.76, .06, .18), MacroDonutLayout.ProportionalOrdered)
        val duringProtein = arcs.map { orderedDonutFrame(it, MacroDonutArcEnd * .50f) }
        assertEquals(0f, duringProtein[0].labelAlpha, .0001f)
        assertEquals(0f, duringProtein[1].labelAlpha, .0001f)
        assertEquals(0f, duringProtein[2].labelAlpha, .0001f)

        val afterProtein = arcs.map { orderedDonutFrame(it, MacroDonutArcEnd * .79f) }
        assertTrue(afterProtein[0].labelAlpha > 0f)
        assertEquals(0f, afterProtein[1].labelAlpha, .0001f)
        assertEquals(0f, afterProtein[2].labelAlpha, .0001f)

        val afterLast = orderedDonutFrame(arcs[2], MacroDonutArcEnd + MacroDonutLabelFadeWindow / 2f)
        assertTrue(afterLast.labelAlpha > 0f && afterLast.labelAlpha < 1f)
        assertEquals(1f, orderedDonutFrame(arcs[2], 1f).labelAlpha, .0001f)
    }

    @Test fun tinyAndZeroEnergySegmentsDoNotCreateNaNOrNegativeSweep() {
        val arcs = macroDonutArcs(listOf(.999, 0.0, .001), MacroDonutLayout.ProportionalOrdered)
        assertEquals(-90f, arcs[0].startAngle, .0001f)
        assertEquals(0f, arcs[1].sweepAngle, .0001f)
        assertEquals(0f, orderedDonutFrame(arcs[1], 1f).drawnSweep, .0001f)
        val frames = arcs.map { orderedDonutFrame(it, 1f) }
        assertTrue(frames.all { it.drawnSweep.isFinite() && it.drawnSweep >= 0f })
        assertTrue(frames.all { it.labelAlpha in 0f..1f })
    }

    @Test fun aSingleMacroKeepsItsAssignedYPosition() {
        val arcs = macroDonutArcs(listOf(1.0, 0.0, 0.0), MacroDonutLayout.FixedY)
        assertEquals(-150f, arcs[0].centerAngle, .0001f)
        assertEquals(-30f, arcs[1].centerAngle, .0001f)
        assertEquals(90f, arcs[2].centerAngle, .0001f)
        val percent = macroEnergyPercentages(
            macroEnergyShares(Nutrition(100.0, 25.0, 0.0, 0.0)))
        assertEquals(listOf(100, 0, 0), percent)
    }

}
