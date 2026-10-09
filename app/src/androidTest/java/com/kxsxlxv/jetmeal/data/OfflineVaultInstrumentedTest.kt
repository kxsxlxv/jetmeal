package com.kxsxlxv.jetmeal.data

import androidx.test.platform.app.InstrumentationRegistry
import com.kxsxlxv.jetmeal.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Android Keystore and actual private file I/O require device instrumentation. */
class OfflineVaultInstrumentedTest {
    @Test fun snapshotAndPendingOutboxSurviveReopenWithoutPlaintextOnDisk()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val owner=UUID.randomUUID().toString()
        val file=File(app.noBackupFilesDir,"nutrition-cache-$owner.aes")
        val at=Instant.parse("2026-10-09T08:00:00Z")
        try {
            val food=DiaryEntry("entry","Private breakfast oats",null,100.0,"g",100.0,
                Nutrition(200.0,8.0,4.0,32.0),Nutrition(200.0,8.0,4.0,32.0),
                at,MealPeriod.Morning,null,null,null,updatedAt=at)
            val snapshot=OfflineSnapshot(LocalDate.of(2026,10,5),LocalDate.of(2026,10,12),
                listOf(food),Targets(2000.0,140.0,70.0,180.0),emptyList(),emptySet())
            val request=PendingDiaryMutation(UUID.randomUUID().toString(),"delete_log",
                buildJsonObject { put("entry_id",food.id) },expectedUpdatedAt=at)
            val catalogue=(1..250).map { index ->
                FoodCandidate("variant-$index","food-$index",
                    "Локальный продукт $index",null,"PICOOC owner",100.0,"g",
                    Nutrition(120.0,7.0,3.0,12.0),false,usageCount=index)
            }
            OfflineVault(app).change(owner){_,_,_->Triple(snapshot,catalogue,listOf(request))}
            assertTrue(file.isFile)
            assertFalse(String(file.readBytes(),Charsets.ISO_8859_1)
                .contains("Private breakfast oats"))
            val recovered=OfflineVault(app).readState(owner)
            assertEquals(snapshot,recovered.first)
            assertEquals(250,recovered.second.size)
            assertEquals(listOf("Локальный продукт 230"),
                rankFoodCandidates(recovered.second,"продукт 230").map{it.name})
            assertEquals(request,recovered.third.single())
            assertFalse(String(file.readBytes(),Charsets.ISO_8859_1)
                .contains("Локальный продукт 230"))
        } finally { file.delete() }
    }
}
