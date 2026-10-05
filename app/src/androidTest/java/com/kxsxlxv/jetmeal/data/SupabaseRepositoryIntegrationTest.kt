package com.kxsxlxv.jetmeal.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kxsxlxv.jetmeal.BuildConfig
import com.kxsxlxv.jetmeal.domain.MealPeriod
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.domain.Targets
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Android SDK -> local Auth/PostgREST integration, with independently authenticated users.
 * Requires `supabase start`, reset migrations, and device routes for API 54321 and Mailpit 54324.
 * It creates local test users/data. There are no server/admin credentials or fixed OTP codes.
 */
@RunWith(AndroidJUnit4::class)
class SupabaseRepositoryIntegrationTest {
    @Test
    fun actualEmailOtpRepositoryMutationsUndoTargetsAndTwoUserIsolation() = runBlocking {
        val uri = runCatching { URI(BuildConfig.SUPABASE_URL) }.getOrNull()
        assumeTrue("Configure the real local Supabase URL and client-safe key before running this test.",
            uri != null && uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2") &&
                BuildConfig.SUPABASE_KEY.isNotBlank())
        val mailpit = URI("http", null, requireNotNull(uri).host, 54324, null, null, null).toString()
        val first = repository()
        val second = repository()
        try {
            authenticate(first, mailpit, "first")
            authenticate(second, mailpit, "second")
            val firstId = requireNotNull(first.client.auth.currentUserOrNull()).id
            val secondId = requireNotNull(second.client.auth.currentUserOrNull()).id
            assertTrue(firstId != secondId)

            val zone = ZoneId.of("Europe/Istanbul")
            first.syncTimezone(zone)
            assertEquals(zone, first.timezone())
            val firstTools = NutritionTools(first)
            val secondTools = NutritionTools(second)
            val uniqueName = "Android integration curd ${UUID.randomUUID()}"
            val created = firstTools.createFood(FoodDraft(
                name = uniqueName, kind = "packaged", brand = "Integration", source = "Local test",
                servingAmount = 300.0, servingUnit = "g", nutrition = Nutrition(435.0, 51.0, 15.0, 9.6),
            ))
            assertTrue(created.ok && created.undoable)
            assertNotNull(created.actionId)
            val variantId = created.data.jsonObject.getValue("variant").jsonObject.getValue("id").jsonPrimitive.content
            val foodId = created.data.jsonObject.getValue("food").jsonObject.getValue("id").jsonPrimitive.content
            val candidate = firstTools.searchFood(uniqueName).data.single()
            assertEquals(variantId, candidate.id)
            assertEquals(foodId, candidate.foodId)
            assertTrue(secondTools.searchFood(uniqueName).data.isEmpty())
            assertTrue(second.client.from("food_variants").select { filter { eq("id", variantId) } }
                .decodeList<JsonObject>().isEmpty())

            val date = LocalDate.now(zone)
            val consumed = date.atTime(8, 0).atZone(zone).toInstant()
            reject("Another user must not log the first user's variant.") {
                secondTools.logFood(LogFood(variantId, 125.0, consumed))
            }
            val logged = firstTools.logFood(LogFood(variantId, 125.0, consumed))
            val entryId = logged.data.jsonObject.getValue("id").jsonPrimitive.content
            var entry = firstTools.getDay(date).data.entries.single()
            assertEquals(entryId, entry.id)
            assertEquals(MealPeriod.Morning, entry.meal)
            assertEquals(125.0, entry.quantity, 0.0)
            assertEquals(300.0, entry.basisAmount, 0.0)
            assertEquals(181.25, entry.nutrition.calories, 0.001)
            assertEquals(21.25, entry.nutrition.protein, 0.001)
            assertTrue(secondTools.getDay(date).data.entries.isEmpty())
            assertTrue(second.client.from("diary_entries").select { filter { eq("id", entryId) } }
                .decodeList<JsonObject>().isEmpty())
            reject("Another user must not edit the first user's entry.") {
                secondTools.updateLog(LogCorrection(entryId, quantity = 250.0))
            }
            reject("Another user must not delete the first user's entry.") { secondTools.deleteLog(entryId) }
            assertEquals(125.0, firstTools.getDay(date).data.entries.single().quantity, 0.0)
            assertTrue(second.client.from("audit_events").select { filter { eq("owner_id", firstId) } }
                .decodeList<JsonObject>().isEmpty())

            firstTools.updateLog(LogCorrection(entryId, quantity = 250.0))
            entry = firstTools.getDay(date).data.entries.single()
            assertEquals(362.5, entry.nutrition.calories, 0.001)
            assertEquals(435.0, entry.basisNutrition.calories, 0.001)
            assertEquals(300.0, entry.basisAmount, 0.0)
            firstTools.deleteLog(entryId)
            assertTrue(firstTools.getDay(date).data.entries.isEmpty())
            val tombstone = first.client.from("diary_entries").select { filter { eq("id", entryId) } }
                .decodeList<JsonObject>().single()
            assertTrue(tombstone.getValue("deleted_at").jsonPrimitive.content != "null")
            firstTools.undoLastAction()
            assertEquals(entryId, firstTools.getDay(date).data.entries.single().id)
            firstTools.repeatMeal(listOf(entryId), consumed.plusSeconds(3600), MealPeriod.Snack)
            val repeated = firstTools.getDay(date).data.entries.single { it.id != entryId }
            assertEquals(MealPeriod.Snack, repeated.meal)
            assertEquals(250.0, repeated.quantity, 0.0)
            assertEquals(435.0, repeated.basisNutrition.calories, 0.001)
            firstTools.undoLastAction()
            assertEquals(entryId, firstTools.getDay(date).data.entries.single().id)

            val targets = Targets(2000.0, 170.0, 70.0, 180.0)
            reject("Targets must reject an unconfirmed typed mutation.", localValidation = true) { firstTools.updateTargets(targets, null) }
            assertEquals(null, firstTools.getTargets().data)
            val confirmation = firstTools.confirmedByUser(targets)
            reject("A confirmation must belong to the authenticated user.", localValidation = true) { secondTools.updateTargets(targets, confirmation) }
            firstTools.updateTargets(targets, confirmation)
            assertEquals(targets, firstTools.getTargets().data)
            assertEquals(null, secondTools.getTargets().data)
            reject("A consumed confirmation must not be reused.", localValidation = true) { firstTools.updateTargets(targets, confirmation) }
            val week = requireNotNull(firstTools.getWeek(date).data)
            assertEquals(14000.0, week.baseBudget, 0.0)
            assertEquals(362.5, week.totalConsumed, 0.001)
            firstTools.undoLastAction()
            assertEquals(null, firstTools.getTargets().data)

            // Actual SDK refresh request, without dumping either access or refresh tokens.
            first.client.auth.refreshCurrentSession()
            assertEquals(firstId, first.client.auth.currentUserOrNull()?.id)
            assertEquals(entryId, firstTools.getDay(date).data.entries.single().id)
            first.client.auth.signOut()
            assertEquals(null, first.client.auth.currentSessionOrNull())
        } finally {
            first.client.close()
            second.client.close()
        }
    }

    private fun repository() = SupabaseRepository(createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
        install(Auth) {
            // Isolate independent real sessions from the app's persisted account on this device.
            sessionManager = MemorySessionManager()
            autoLoadFromStorage = false
            autoSaveToStorage = false
            enableLifecycleCallbacks = false
        }
        install(Postgrest)
    })

    private suspend fun authenticate(repository: SupabaseRepository, mailpit: String, label: String) {
        repository.client.auth.awaitInitialization()
        val email = "jetmeal-android-$label-${UUID.randomUUID()}@example.test"
        repository.client.auth.signInWith(OTP) { this.email = email }
        val code = LocalEmailCapture.code(mailpit, email)
        repository.client.auth.verifyEmailOtp(OtpType.Email.EMAIL, email, code)
        assertEquals(email, repository.client.auth.currentUserOrNull()?.email)
        assertNotNull(repository.client.auth.currentSessionOrNull())
    }

    private suspend fun reject(message: String, localValidation: Boolean = false, action: suspend () -> Unit) {
        try {
            action()
            fail(message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            if (!localValidation) throw error
        } catch (error: RestException) {
            assertTrue("An authorization rejection must be a server 4xx response.", error.statusCode in 400..499)
        }
    }
}
