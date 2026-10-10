package com.kxsxlxv.jetmeal.ui

import com.kxsxlxv.jetmeal.domain.FoodCandidate
import com.kxsxlxv.jetmeal.domain.FoodMeasure
import com.kxsxlxv.jetmeal.domain.Nutrition
import org.junit.Assert.*
import org.junit.Test

class FoodSearchPresentationTest {
    private fun food(id: String, foodId: String, name: String, brand: String? = null,
        source: String? = null, measure: Boolean = false): FoodCandidate {
        val portions = if(measure) listOf(FoodMeasure("measure-$id",id,"piece","шт.",109.0)) else emptyList()
        return FoodCandidate(id,foodId,name,brand,source,100.0,"g",
            Nutrition(267.0,12.8,10.1,30.3),false,measures=portions)
    }

    @Test fun multipleVariantsBecomeOneSearchItemWithSelectableMeasuresFirst() {
        val grams=food("grams","burger","Чизбургер","Вкусно — и точка")
        val piece=food("piece","burger","Чизбургер","Вкусно — и точка",measure=true)
        val another=food("another","other","Яблоко")
        val grouped=groupFoodSearchResults(listOf(grams,piece,another))
        assertEquals(2,grouped.size)
        assertEquals(listOf("piece","grams"),grouped.first().map { it.id })
    }

    @Test fun russianVariantCountsAreGrammaticallyCorrect() {
        assertEquals("1 вариант", variantCountLabel(1))
        assertEquals("2 варианта", variantCountLabel(2))
        assertEquals("5 вариантов", variantCountLabel(5))
        assertEquals("11 вариантов", variantCountLabel(11))
        assertEquals("21 вариант", variantCountLabel(21))
    }

    @Test fun portionEntryShowsEquivalentGramsInsideInput() {
        val serving=FoodMeasure("m1","v1","serving","порция",274.0)
        assertEquals("= 274 г", measureConversionHint(1.0,serving,serving,"g"))
        assertEquals("= 548 г", measureConversionHint(2.0,serving,serving,"g"))
    }

    @Test fun gramsEntryShowsDynamicPortionEquivalentInsideInput() {
        val serving=FoodMeasure("m1","v1","serving","порция",274.0)
        assertEquals("= 1 порция",measureConversionHint(274.0,null,serving,"g"))
        assertEquals("= 2 порции",measureConversionHint(548.0,null,serving,"g"))
        assertEquals("= 5 порций",measureConversionHint(1370.0,null,serving,"g"))
    }

    @Test fun approximateAndInvalidConversionsAreSafe() {
        val serving=FoodMeasure("m1","v1","serving","порция",274.0,approximate=true)
        assertEquals("≈ 274 г",measureConversionHint(1.0,serving,serving,"g"))
        assertEquals("≈ 1 порция",measureConversionHint(274.0,null,serving,"g"))
        assertNull(measureConversionHint(100.0,null,null,"g"))
        assertNull(measureConversionHint(null,null,serving,"g"))
        assertNull(measureConversionHint(0.0,null,serving,"g"))
        assertNull(measureConversionHint(Double.POSITIVE_INFINITY,null,serving,"g"))
    }

    @Test fun searchBrandIsNotDuplicatedOrMixedWithPhotoSource() {
        assertEquals("Ростикс",compactFoodBrand(food("1","1","Ростмастер","Ростикс","Ростикс")))
        assertNull(compactFoodBrand(food("2","2","Ростикс Чизбургер","Ростикс","Фото пользователя 07.10.2026")))
        assertNull(compactFoodBrand(food("3","3","Борщ",source="Фото пользователя 07.10.2026")))
    }
}
