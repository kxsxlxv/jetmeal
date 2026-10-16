package com.kxsxlxv.jetmeal.data

import com.kxsxlxv.jetmeal.domain.FoodCandidate
import com.kxsxlxv.jetmeal.domain.Nutrition
import org.junit.Assert.*
import org.junit.Test

class SmartFoodSearchTest {
    private fun food(name:String, brand:String?=null, source:String?=null,
        usage:Int=0, amount:Double=100.0,calories:Double=100.0,
        protein:Double=10.0,fat:Double=2.0,id:String=name) =
        FoodCandidate(id,"food-$id",name,brand,source,amount,"g",
            Nutrition(calories,protein,fat,0.0),false,usageCount=usage)

    @Test fun typoSubstitutionDeletionAndTranspositionStillFindTheRightFood() {
        val foods=listOf(food("Творог обезжиренный"),food("Йогурт натуральный"))
        for (query in listOf("твoрог".replace('o','о'),"тврог","твроог","творогг")) {
            assertEquals("Творог обезжиренный",
                rankFoodCandidates(foods,query).first().name)
        }
        assertEquals(listOf("Йогурт натуральный"),
            rankFoodCandidates(foods,"йогуртт").map{it.name})
    }

    @Test fun wrongKeyboardAndLatinBrandsAreSupportedWithoutFalseReplacements() {
        val foods=listOf(food("Творог",id="curd"),
            food("Байт",brand="NDJHJU",id="actual-latin-brand"))
        val direct=rankFoodCandidates(foods,"ndjhju")
        assertEquals("Байт",direct.first().name)
        assertEquals("Творог",direct.last().name)
        assertEquals(listOf("Творог"),
            rankFoodCandidates(listOf(foods.first()),"ndjhju").map { it.name })
    }

    @Test fun familiarSynonymsAndRestaurantAbbreviationsWorkWithoutRemoteSearch() {
        val foods=listOf(
            food("Каша гречневая",id="buckwheat"),
            food("Каша овсяная",id="oats"),
            food("Молоко сгущённое",id="condensed"),
            food("Картофель отварной",id="potato"),
            food("Твистер",source="Rostic's",id="rostic"),
            food("Чизбургер",source="Вкусно — и точка",id="vkusno")
        )
        val expectations=mapOf(
            "гречка" to "Каша гречневая",
            "овсянка" to "Каша овсяная",
            "сгущенка" to "Молоко сгущённое",
            "картошка" to "Картофель отварной",
            "ростикс" to "Твистер",
            "кфс" to "Твистер",
            "макдак" to "Чизбургер",
        )
        for((query,expected) in expectations) {
            assertEquals("$query should resolve locally",listOf(expected),
                rankFoodCandidates(foods,query).map {it.name})
        }
    }

    @Test fun multiwordQueryMatchesAcrossProductAndBrandRegardlessOfOrder() {
        val foods=listOf(
            food("Йогурт натуральный",brand="Простоквашино"),
            food("Йогурт натуральный",brand="Другой",id="other"))
        assertEquals(listOf("Простоквашино"),
            rankFoodCandidates(foods,"простоквашино йогурт").map{it.brand})
        assertEquals(listOf("Простоквашино"),
            rankFoodCandidates(foods,"йогурт Простоквашино").map{it.brand})
    }

    @Test fun exactNameBeatsSynonymsTyposAndMuchHigherPersonalFrequency() {
        val foods=listOf(
            food("Гречка",usage=0,id="literal"),
            food("Каша гречневая",usage=80,id="alias"),
            food("Гречкa",usage=100,id="typo").copy(name="Гречкa") )
        assertEquals("Гречка",rankFoodCandidates(foods,"гречка").first().name)
    }

    @Test fun shortUnrelatedWordsNeverTriggerBroadFuzzyRecommendations() {
        val foods=listOf(food("Рак"),food("Мак"),food("Сок"))
        assertTrue(rankFoodCandidates(foods,"так").isEmpty())
        assertTrue(rankFoodCandidates(foods,"aaa").isEmpty())
    }

    @Test fun highProteinRankingUsesProteinPerCalorieNotServingSize() {
        val foods=listOf(
            food("Йогурт А",amount=400.0,calories=320.0,protein=30.0,id="large"),
            food("Йогурт Б",amount=100.0,calories=85.0,protein=17.0,id="lean"),
            food("Йогурт В",amount=100.0,calories=135.0,protein=0.0,id="zero"))
        assertEquals(listOf("Йогурт Б","Йогурт А","Йогурт В"),
            rankFoodCandidates(foods,"белковый йогурт").map{it.name})
        assertEquals(listOf("Йогурт Б","Йогурт А","Йогурт В"),
            rankFoodCandidates(foods,"много белка йогурт").map{it.name})
    }

    @Test fun lowCalorieRankingUsesPerHundredGramsNotPerReferencePortion() {
        val foods=listOf(
            food("Напиток А",amount=25.0,calories=90.0,id="small"), // 360 /100g
            food("Напиток Б",amount=250.0,calories=100.0,id="bottle"), // 40 /100g
            food("Напиток В",amount=100.0,calories=60.0,id="regular")) // 60 /100g
        assertEquals(listOf("Напиток Б","Напиток В","Напиток А"),
            rankFoodCandidates(foods,"низкокалорийный напиток").map{it.name})
        assertEquals(NutrientSearchIntent.LowCalorie,parseFoodSearch("мало калорий").nutrient)
    }

    @Test fun exactLexicalSearchStillOutranksMacroPreferenceAndUsage() {
        val foods=listOf(food("Йогурт",usage=0,calories=140.0,protein=7.0,id="exact"),
            food("Йогурт белковый",usage=900,calories=80.0,protein=20.0,id="enriched"))
        assertEquals("Йогурт",rankFoodCandidates(foods,"йогурт").first().name)
        assertEquals("Йогурт белковый",
            rankFoodCandidates(foods,"высокобелковый йогурт").first().name)
    }

    @Test fun punctuationYoAndNumericFatPercentageDoNotGetLost() {
        val foods=listOf(food("Творог 5%"),food("Творог 0%"),food("Гречнёвая каша"))
        assertEquals(listOf("Творог 5%"),
            rankFoodCandidates(foods,"творог-5%").map{it.name})
        assertEquals(listOf("Гречнёвая каша"),
            rankFoodCandidates(foods,"гречневая").map{it.name})
    }
}
