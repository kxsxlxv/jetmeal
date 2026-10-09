package com.kxsxlxv.jetmeal.domain

import org.junit.Assert.*
import org.junit.Test

class FoodMeasureTest {
    private val medium = FoodMeasure("m","egg","egg_medium","Среднее",44.0,true)
    private val large = FoodMeasure("l","egg","egg_large","Большое",50.0,true)

    @Test fun onlyTwoEggSizesAreProvidedAndScaleCorrectly() {
        assertEquals("Среднее",medium.label)
        assertEquals("Большое",large.label)
        assertEquals(88.0,medium.toBase(2.0),.00001)
        assertEquals(150.0,large.toBase(3.0),.00001)
    }
    @Test fun cheesecakeThreePiecesMaintainCaloricAccuracy() {
        val burger = FoodMeasure("b","burger","piece","шт.",109.0)
        val grams=ChosenMeasure(burger,3.0).baseAmount
        assertEquals(327.0,grams,.0001)
        assertEquals(873.0,QuantityScaling.scale(109.0,
            Nutrition(291.0,15.0,12.0,34.0),grams).calories,.00001)
        assertEquals(109.0,burger.toBase(1.0),.00001)
    }
    @Test fun sugarSpoonsAreProductSpecificAndOnlyApproximate() {
        val tsp=FoodMeasure("t","sugar","tsp","ч. л.",4.0,true)
        assertEquals(6.0,tsp.toBase(1.5),.00001)
        assertTrue(tsp.approximate)
    }
    @Test fun rejectsInvalidQuantities() {
        val portion=FoodMeasure("p","burger","piece","шт.",109.0)
        assertThrows(IllegalArgumentException::class.java){ portion.toBase(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java){ portion.toBase(-1.0) }
    }
}
