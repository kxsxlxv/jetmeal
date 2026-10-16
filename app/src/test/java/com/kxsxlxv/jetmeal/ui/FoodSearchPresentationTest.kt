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

    @Test fun oneCountableItemIsDefaultRegardlessOfGramBasis() {
        val banana = FoodMeasure("banana-piece","banana","piece","шт.",118.0,isDefault=true)
        val egg = FoodMeasure("medium","egg","egg_medium","Среднее",44.0)
        val sugar = FoodMeasure("spoon","sugar","tsp","ч. л.",4.0)
        assertEquals(1.0, initialMeasureQuantity(100.0,banana,editing=false),0.0)
        assertEquals(1.0, initialMeasureQuantity(50.0,egg,editing=false),0.0)
        assertEquals(1.0, initialMeasureQuantity(100.0,sugar,editing=false),0.0)
        assertEquals(100.0, initialMeasureQuantity(100.0,null,editing=false),0.0)
    }

    @Test fun existingDiaryKeepsTheRecordedCountWhenEditing() {
        val egg = FoodMeasure("medium","egg","egg_medium","Среднее",44.0)
        assertEquals(3.0, initialMeasureQuantity(132.0,egg,editing=true,
            recordedMeasureKey="egg_medium",recordedMeasureQuantity=3.0),0.0)
        assertEquals(2.0, initialMeasureQuantity(88.0,egg,editing=true),0.00001)
    }

    @Test fun conversionsHaveReadablePrecisionWithoutChangingManualTyping() {
        assertEquals("0,85",decimalInput(100.0/118.0))
        assertEquals("1,14",decimalInput(50.0/44.0))
        assertEquals("1",decimalInput(1.0000000000000002))
        assertEquals("2,5",decimalInput(2.5))
        assertEquals("100",decimalInput(100.0))
        assertEquals("0,005",recordedDecimalInput(0.005))
        assertEquals("1,1363636363636365",recordedDecimalInput(1.1363636363636365))
    }

    @Test fun searchBrandIsNotDuplicatedOrMixedWithPhotoSource() {
        assertEquals("Ростикс",compactFoodBrand(food("1","1","Ростмастер","Ростикс","Ростикс")))
        assertNull(compactFoodBrand(food("2","2","Ростикс Чизбургер","Ростикс","Фото пользователя 07.10.2026")))
        assertNull(compactFoodBrand(food("3","3","Борщ",source="Фото пользователя 07.10.2026")))
    }
    @Test fun lastPortionPrefersPreviouslyEnteredPiecesOverOneDefaultPiece() {
        val piece = FoodMeasure("burger-piece","burger","piece","шт.",109.0,isDefault=true)
        val selected = initialFoodMeasure(listOf(piece),editing=false,
            enteredMeasureKey=null,enteredMeasureBaseAmount=null,
            suggestedQuantity=327.0,suggestedMeasureKey="piece",
            suggestedMeasureBaseAmount=109.0,savedKey=null)
        assertEquals(piece,selected)
        assertEquals(3.0,initialMeasureQuantity(100.0,selected,editing=false,
            suggestedQuantity=327.0,suggestedMeasureKey="piece",
            suggestedMeasureQuantity=3.0),0.00001)
    }

    @Test fun lastGramPortionStaysInGramsAndMeasureChangeFallsBackSafely() {
        val piece = FoodMeasure("burger-piece","burger","piece","шт.",109.0,isDefault=true)
        assertNull(initialFoodMeasure(listOf(piece),editing=false,
            enteredMeasureKey=null,enteredMeasureBaseAmount=null,
            suggestedQuantity=175.5,suggestedMeasureKey=null,
            suggestedMeasureBaseAmount=null,savedKey="piece"))
        assertEquals(175.5,initialMeasureQuantity(100.0,null,editing=false,
            suggestedQuantity=175.5),0.00001)
        // The database may have had a 109-g portion while the catalogue now
        // defines it differently. Never silently turn three pieces into another mass.
        assertNull(initialFoodMeasure(listOf(piece.copy(baseAmount=105.0)),
            editing=false,enteredMeasureKey=null,enteredMeasureBaseAmount=null,
            suggestedQuantity=327.0,suggestedMeasureKey="piece",
            suggestedMeasureBaseAmount=109.0,savedKey=null))
        assertEquals(327.0,initialMeasureQuantity(100.0,null,editing=false,
            suggestedQuantity=327.0),0.00001)
    }

    @Test fun editorUsesOneDefaultMeasureWhenNoPreviousLogExists() {
        val piece = FoodMeasure("piece","burger","piece","шт.",109.0,isDefault=true)
        assertEquals(piece,initialFoodMeasure(listOf(piece),editing=false,
            enteredMeasureKey=null,enteredMeasureBaseAmount=null,
            suggestedQuantity=null,suggestedMeasureKey=null,
            suggestedMeasureBaseAmount=null,savedKey=null))
        assertEquals(1.0,initialMeasureQuantity(100.0,piece,editing=false),0.0)
    }

    @Test fun contextualUnitsOrderDefaultFirstAndDistinguishSpoonSizes() {
        val tablespoon=FoodMeasure("tb","sugar","tbsp","ст. л.",12.0,approximate=true)
        val teaspoon=FoodMeasure("ts","sugar","tsp","ч. л.",4.0,approximate=true)
        val ordered=orderedFoodMeasures(listOf(tablespoon,teaspoon))
        assertEquals(listOf("tsp","tbsp"),ordered.map { it.key })
        val cup=FoodMeasure("cup","milk","cup","стакан 250 мл",254.0,
            approximate=true,isDefault=true)
        assertEquals(cup,orderedFoodMeasures(listOf(tablespoon,cup,teaspoon)).first())
        assertEquals(cup,initialFoodMeasure(listOf(tablespoon,cup),
            editing=false,enteredMeasureKey=null,enteredMeasureBaseAmount=null,
            suggestedQuantity=null,suggestedMeasureKey=null,suggestedMeasureBaseAmount=null,
            savedKey=null))
    }

    @Test fun cupsCanBeQuarteredByButtonsAndSugarHalfSpoons() {
        assertEquals(Pair(.25,.25),foodMeasureIncrement("cup"))
        assertEquals(Pair(.5,.5),foodMeasureIncrement("tsp"))
        assertEquals(Pair(.5,.5),foodMeasureIncrement("tbsp"))
        assertEquals(Pair(.5,.5),foodMeasureIncrement("serving"))
        assertEquals(Pair(1.0,1.0),foodMeasureIncrement("piece"))
        assertEquals(Pair(10.0,.1),foodMeasureIncrement(null))
        val cup=FoodMeasure("cup","milk","cup","стакан 250 мл",254.0,approximate=true)
        assertEquals(127.0,cup.toBase(.5),.001)
        assertEquals(63.5,cup.toBase(.25),.001)
        assertEquals("≈ 127 г",measureConversionHint(.5,cup,cup,"g"))
        val juice=FoodMeasure("juice-cup","juice","cup","стакан 250 мл",250.0)
        assertEquals(500.0,juice.toBase(2.0),.001)
        assertEquals("= 500 мл",measureConversionHint(2.0,juice,juice,"ml"))
    }

    @Test fun approximateAndNutritionSearchResultsExplainTheirRanking() {
        val nutrients=com.kxsxlxv.jetmeal.domain.Nutrition(80.0,20.0,1.0,3.0)
        val base=com.kxsxlxv.jetmeal.domain.FoodCandidate(
            "v1","f1","Йогурт",null,null,100.0,"g",nutrients,false)
        assertEquals("Похожее написание",
            foodSearchResultHint(base.copy(matchReason="Similar spelling")))
        assertEquals("По синониму или раскладке",
            foodSearchResultHint(base.copy(matchReason="Matching synonym or keyboard layout")))
        assertEquals("Б 25 г / 100 ккал",
            foodSearchResultHint(base.copy(matchReason="Protein density")))
        assertEquals("80 ккал / 100 г",
            foodSearchResultHint(base.copy(matchReason="Calorie density")))
        assertNull(foodSearchResultHint(base))
    }

}
