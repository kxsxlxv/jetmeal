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
}
