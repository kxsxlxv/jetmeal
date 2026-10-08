package com.kxsxlxv.jetmeal.data

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class SearchCacheTest {
    @Test fun invalidationWhileBackgroundQueryIsInFlightCannotPublishAnObsoleteCache() = runBlocking {
        val firstRead = CompletableDeferred<Unit>()
        val releaseFirstRead = CompletableDeferred<Unit>()
        val foodReads = AtomicInteger()
        val session = UserSession("test-access", "test-refresh", expiresIn = 3600,
            tokenType = "bearer", user = UserInfo(aud = "authenticated", id = "test-owner", email = "test@example.test"),
            expiresAt = Clock.System.now() + 1.hours)
        val client = createSupabaseClient("https://cache-regression.supabase.co", "sb_publishable_test") {
            osInformation = null
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                val body = when {
                    request.url.encodedPath.endsWith("/foods") -> {
                        val read = foodReads.incrementAndGet()
                        if (read == 1) { firstRead.complete(Unit); releaseFirstRead.await() }
                        """[{"id":"food","name":"${if (read == 1) "Old" else "Current"}"}]"""
                    }
                    request.url.encodedPath.endsWith("/food_variants") -> """[{"id":"variant","food_id":"food","serving_amount":100,"serving_unit":"g","calories_kcal":200,"protein_g":12,"fat_g":7,"carbs_g":21,"is_estimated":false}]"""
                    else -> "[]"
                }
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
            install(Auth) {
                sessionManager = MemorySessionManager(session)
                codeVerifierCache = MemoryCodeVerifierCache()
                enableLifecycleCallbacks = false
            }
            install(Postgrest) { maxRetries = 0 }
        }
        try {
            client.auth.awaitInitialization()
            val repository = SupabaseRepository(client)
            val tools = NutritionTools(repository)
            val obsolete = async(Dispatchers.Default) { tools.searchFood("").data }
            withTimeout(10_000) { firstRead.await() }
            // An external diary/catalogue update invalidates from the caller thread
            // while the search's network/decode/rank work continues in the background.
            repository.invalidateSearch()
            releaseFirstRead.complete(Unit)
            assertEquals("Old", obsolete.await().single().name)
            assertEquals("Current", tools.searchFood("").data.single().name)
            assertEquals("Current", tools.searchFood("").data.single().name)
            assertEquals("The obsolete in-flight result must not be installed as the cache", 2, foodReads.get())
        } finally { client.close() }
    }
}
