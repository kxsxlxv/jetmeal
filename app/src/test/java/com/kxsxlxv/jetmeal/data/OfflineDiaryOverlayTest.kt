package com.kxsxlxv.jetmeal.data

import com.kxsxlxv.jetmeal.domain.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OfflineDiaryOverlayTest {
    private val at=Instant.parse("2026-10-09T10:00:00Z")
    private val base=DiaryEntry("server-id","Творог",null,100.0,"g",100.0,
        Nutrition(150.0,18.0,5.0,3.0),Nutrition(150.0,18.0,5.0,3.0),
        at,MealPeriod.Morning,null,null,null,updatedAt=at)
    private fun item(id:String,kind:String,params:kotlinx.serialization.json.JsonObject,
                     preview:DiaryEntry?=null,blocked:Boolean=false) =
        PendingDiaryMutation(id,kind,params,preview=preview,blocked=blocked)

    @Test fun offlineQuantityUpdatesUseImmutableBasisAndDeleteHidesItem() {
        val update=item("r1","update_log",buildJsonObject {
            put("entry_id",base.id);put("quantity",250)
        })
        val amended=applyPending(listOf(base),listOf(update))
        assertEquals(250.0,amended.single().quantity,.00001)
        assertEquals(375.0,amended.single().nutrition.calories,.00001)
        val delete=item("r2","delete_log",buildJsonObject{put("entry_id",base.id)})
        assertTrue(applyPending(listOf(base),listOf(update,delete)).isEmpty())
    }

    @Test fun serverReceiptEntryAndPendingLocalPreviewCannotAppearTwice() {
        val preview=base.copy(id="request-id",mealGroupId=null,updatedAt=null)
        val server=base.copy(id="server-row",mealGroupId="request-id")
        val pending=item("request-id","log_food",buildJsonObject{put("quantity",100)},preview)
        assertEquals(listOf("server-row"),
            applyPending(listOf(server),listOf(pending)).map{it.id})
    }

    @Test fun serverRejectedStaleUpdateMustNotChangeDisplayedSnapshot() {
        val conflict=item("error","update_log",buildJsonObject{
            put("entry_id",base.id);put("quantity",750)
        },blocked=true)
        assertEquals(listOf(base),applyPending(listOf(base),listOf(conflict)))
    }
    @Test fun editingUnsentFoodPreservesIdempotencyAndChangesPreviewNutrition() {
        val newFood=base.copy(id="new-id",updatedAt=null,quantity=100.0)
        val pending=item("request-uuid","log_food",buildJsonObject {
            put("food_variant_id","variant-id")
            put("quantity",100)
            put("consumed_at",at.toString())
            put("meal_type","morning")
        },newFood)
        val amended=amendUnsentLog(pending,300.0,null)
        assertEquals(pending.id,amended.id)
        assertEquals(300.0,amended.input.getValue("quantity").toString().toDouble(),.001)
        assertEquals(300.0,amended.preview!!.quantity,.001)
        assertEquals(450.0,amended.preview!!.nutrition.calories,.001)
        assertEquals("variant-id",amended.input.getValue("food_variant_id").toString().trim('"'))
        assertEquals(listOf(300.0),applyPending(emptyList(),listOf(amended)).map{it.quantity})
        assertThrows(IllegalArgumentException::class.java) {
            amendUnsentLog(pending.copy(attempted=true),300.0,null)
        }
    }

    @Test fun correctingAnUnsentPieceRemovesStaleMeasureWhenChangedToGrams() {
        val piece=FoodMeasure("piece-id","burger","piece","шт.",109.0)
        val preview=base.copy(id="new-id",variantId="burger",updatedAt=null)
        val pending=item("uuid","log_food",buildJsonObject {
            put("food_variant_id","burger")
            put("quantity",109)
            put("measure_id","piece-id")
            put("measure_quantity",1)
        },preview)
        val twoPieces=amendUnsentLog(pending,218.0,ChosenMeasure(piece,2.0))
        assertEquals("2.0",twoPieces.input.getValue("measure_quantity").toString())
        val grams=amendUnsentLog(twoPieces,175.0,null)
        assertNull(grams.input["measure_id"])
        assertNull(grams.input["measure_quantity"])
        assertNull(grams.preview!!.enteredMeasureKey)
        assertEquals(175.0,grams.preview!!.quantity,.001)
    }

}
