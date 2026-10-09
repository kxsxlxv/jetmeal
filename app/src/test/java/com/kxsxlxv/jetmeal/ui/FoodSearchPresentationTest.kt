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

    @Test fun searchBrandIsNotDuplicatedOrMixedWithPhotoSource() {
        assertEquals("Ростикс",compactFoodBrand(food("1","1","Ростмастер","Ростикс","Ростикс")))
        assertNull(compactFoodBrand(food("2","2","Ростикс Чизбургер","Ростикс","Фото пользователя 07.10.2026")))
        assertNull(compactFoodBrand(food("3","3","Борщ",source="Фото пользователя 07.10.2026")))
    }
}
