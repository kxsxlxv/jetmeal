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
}
