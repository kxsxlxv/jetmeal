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


    @Test fun acknowledgedAddSurvivesOutboxRemovalAndUsesServerId() {
        val day=at.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val snapshot=OfflineSnapshot(day,day.plusDays(1),emptyList(),null,emptyList(),emptySet())
        val preview=base.copy(id="local-preview",updatedAt=null)
        val logged=item("request-id","log_food",buildJsonObject {
            put("quantity",100)
        },preview)
        val server=base.copy(id="confirmed-id",mealGroupId="request-id")
        val confirmed=requireNotNull(reconcileSyncedSnapshot(snapshot,logged,server))
        assertEquals(listOf("confirmed-id"),confirmed.entries.map { it.id })
        assertEquals(listOf("confirmed-id"),applyPending(confirmed.entries,emptyList()).map { it.id })
        assertEquals(listOf("confirmed-id"),applyPending(confirmed.entries,listOf(logged)).map { it.id })
    }

    @Test fun acknowledgedEditAndDeleteReconcileCachedSnapshot() {
        val day=at.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val snapshot=OfflineSnapshot(day,day.plusDays(1),listOf(base),null,emptyList(),emptySet())
        val edit=item("update-id","update_log",buildJsonObject {
            put("entry_id",base.id); put("quantity",200)
        })
        val updated=base.copy(quantity=200.0,nutrition=base.basisNutrition*2.0)
        val afterEdit=requireNotNull(reconcileSyncedSnapshot(snapshot,edit,updated))
        assertEquals(200.0,afterEdit.entries.single().quantity,.00001)
        val delete=item("delete-id","delete_log",buildJsonObject { put("entry_id",base.id) })
        assertTrue(requireNotNull(reconcileSyncedSnapshot(afterEdit,delete,updated)).entries.isEmpty())
    }

    @Test fun acknowledgedEntryOutsideCachedCoverageDoesNotInventCoverage() {
        val day=at.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val snapshot=OfflineSnapshot(day.minusDays(2),day.minusDays(1),emptyList(),null,
            emptyList(),emptySet())
        val logged=item("request-id","log_food",buildJsonObject { put("quantity",100) },base)
        val partial=requireNotNull(reconcileSyncedSnapshot(snapshot,logged,base))
        assertEquals(listOf(base.id),partial.entries.map { it.id })
        assertEquals(snapshot.coverage,partial.coverage)
        assertFalse(partial.covers(day,day.plusDays(1)))

        val firstEver=requireNotNull(reconcileSyncedSnapshot(null,logged,base))
        assertEquals(listOf(base.id),firstEver.entries.map { it.id })
        assertTrue(firstEver.coverage.isEmpty())
        assertFalse(firstEver.covers(day,day.plusDays(1)))
    }
}
