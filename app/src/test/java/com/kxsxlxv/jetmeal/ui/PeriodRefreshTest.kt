@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kxsxlxv.jetmeal.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.kxsxlxv.jetmeal.data.SupabaseRepository
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** HTTP fixtures only; real Supabase and real-frame coverage are separate instrumentation. */
class PeriodRefreshTest {
    @Test fun initialLoadDaySwitchAndCurrentDayUpdateNeverLoadTheSearchCatalogue() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val requests = mutableListOf<String>()
        val session = UserSession("test-access", "test-refresh", expiresIn = 3600,
            tokenType = "bearer", user = UserInfo(aud = "authenticated", id = "test-owner", email = "test@example.test"),
            expiresAt = Clock.System.now() + 1.hours)
        val client = createSupabaseClient("https://period-regression.supabase.co", "sb_publishable_test") {
            coroutineDispatcher = dispatcher
            osInformation = null
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine(MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request ->
                    val path = request.url.encodedPath
                    requests += path
                    val body = when {
                        path.endsWith("/profiles") -> """[{"id":"test-owner","timezone":"${ZoneId.systemDefault().id}"}]"""
                        path.endsWith("/nutrition_targets") -> """[{"daily_calories_kcal":2200,"daily_protein_g":130,"daily_fat_g":80,"daily_carbs_g":250,"adjustment_limit_ratio":0.1}]"""
                        else -> "[]"
                    }
                    respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
            })
            install(Auth) {
                sessionManager = MemorySessionManager(session)
                codeVerifierCache = MemoryCodeVerifierCache()
                enableLifecycleCallbacks = false
            }
            install(Postgrest) { maxRetries = 0 }
        }
        val store = ViewModelStore()
        val day = LocalDate.of(2020, 10, 6)
        val model = JetMealViewModel(SupabaseRepository(client), SavedStateHandle(mapOf("day" to day.toString())))
        store.put("period", model)
        try {
            runCurrent()
            assertNotNull(model.state.value.week)
            model.showPeriod(day.minusDays(1))
            runCurrent()
            assertEquals(day.minusDays(1), model.state.value.day)
            assertNotNull(model.state.value.week)
            model.refresh()
            runCurrent()
            assertFalse(model.state.value.busy)
            assertEquals(3, requests.count { it.endsWith("/diary_entries") })
            assertEquals("Day navigation must not start catalogue decoding/ranking during Hero animation", 0,
                requests.count { it.endsWith("/foods") || it.endsWith("/food_variants") })
        } finally {
            store.clear()
            client.close()
            Dispatchers.resetMain()
        }
    }
}
