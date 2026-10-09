package com.kxsxlxv.jetmeal.data

import com.kxsxlxv.jetmeal.domain.FoodCandidate
import com.kxsxlxv.jetmeal.domain.Nutrition
import org.junit.Assert.*
import org.junit.Test

class OfflineFoodSearchTest {
    private fun food(name:String,brand:String?=null,source:String?=null,
        usage:Int=0,id:String=name) = FoodCandidate(id,"food-$id",name,brand,source,
            100.0,"g",Nutrition(200.0,10.0,7.0,22.0),
            false,usageCount=usage)

    @Test fun cyrillicCaseAndYoNormalizationAreIdenticalOfflineAndOnline() {
        val catalogue=listOf(food("Гречнёвая каша"),food("Рисовая каша"))
        assertEquals(listOf("Гречнёвая каша"),
            rankFoodCandidates(catalogue,"ГРЕЧНЕВАЯ").map{it.name})
    }

    @Test fun queryCanMatchBrandOrSourceAndScoreExactNamesFirst() {
        val catalogue=listOf(food("Йогурт","Марка",usage=4),
            food("Марка",usage=1),
            food("Ряженка",source="Марка",usage=3))
        assertEquals(listOf("Марка","Йогурт","Ряженка"),
            rankFoodCandidates(catalogue,"марка").map{it.name})
    }

    @Test fun emptyQueryUsesUsageRankAndNoMatchReturnsEmptyWithoutTransport() {
        val catalogue=listOf(food("A",usage=3),food("B",usage=9))
        assertEquals(listOf("B","A"),
            rankFoodCandidates(catalogue,"").map{it.name})
        assertTrue(rankFoodCandidates(catalogue,"несуществующий").isEmpty())
    }

    @Test fun completeCachedCatalogueSearchesBeyondFirstPage() {
        val catalogue=(1..600).map{food("Еда $it",id="$it")}
        assertEquals(600,rankFoodCandidates(catalogue,"еда",Int.MAX_VALUE).size)
        assertEquals(listOf("Еда 555"),
            rankFoodCandidates(catalogue,"еда 555").map{it.name})
    }
}
