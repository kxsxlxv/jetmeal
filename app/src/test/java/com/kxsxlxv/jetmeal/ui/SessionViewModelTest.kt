@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.kxsxlxv.jetmeal.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.kxsxlxv.jetmeal.data.SupabaseRepository
import com.kxsxlxv.jetmeal.data.protectSessionRefresh
import com.kxsxlxv.jetmeal.data.AuthFailureCapture
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionSource
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
import java.net.UnknownHostException
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class SessionViewModelTest {
    @Test fun offlineStartupIsRecoveryAndAutomaticallyReturnsToDiary() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val storage = MemorySessionManager(session(expired = true))
        var online = false
        var diaryLoads = 0
        val capture = AuthFailureCapture()
        val client = createSupabaseClient("https://session-regression.supabase.co", "sb_publishable_test") {
            coroutineDispatcher = dispatcher
            osInformation = null
            defaultLogLevel = LogLevel.NONE
            protectSessionRefresh(capture::capture)
            httpEngine = MockEngine(MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request ->
                    val path = request.url.encodedPath
                    if (!online) throw UnknownHostException("private-host")
                    if (path.endsWith("/diary_entries")) diaryLoads++
                    val body = when {
                        path.endsWith("/token") -> """{"access_token":"rotated-access","refresh_token":"rotated-refresh","expires_in":3600,"token_type":"bearer","user":{"aud":"authenticated","id":"test-owner","email":"test@example.test"}}"""
                        path.endsWith("/profiles") -> """[{"id":"test-owner","timezone":"${ZoneId.systemDefault().id}"}]"""
                        else -> "[]"
                    }
                    respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
            })
            install(Auth) {
                sessionManager = storage
                codeVerifierCache = MemoryCodeVerifierCache()
                enableLifecycleCallbacks = false
            }
            install(Postgrest) { maxRetries = 0 }
        }
        val store = ViewModelStore()
        val model = JetMealViewModel(SupabaseRepository(client).also { it.authFailures = capture }, SavedStateHandle())
        store.put("session", model)
        try {
            runCurrent()
            assertFalse(model.state.value.authLoading)
            assertTrue(model.state.value.authRecovering)
            assertNull(model.state.value.email)
            assertTrue(model.state.value.error.orEmpty().contains("Сессия сохранена"))
            assertTrue("Original DNS cause must survive the SDK's cause-less wrapper into UI",
                model.state.value.error.orEmpty().contains("DNS"))
            assertNotNull(storage.loadSessionOrNull())
            online = true
            advanceTimeBy(10_001)
            runCurrent()
            assertFalse(model.state.value.authRecovering)
            assertEquals("test@example.test", model.state.value.email)
            assertEquals(1, diaryLoads)
            assertEquals("rotated-refresh", storage.loadSession().refreshToken)
            // Same owner, new JWT: this must neither reload nor replace day data.
            val current = requireNotNull(client.auth.currentSessionOrNull())
            client.auth.importSession(current.copy(accessToken = "another-access"),
                source = SessionSource.Refresh(current))
            runCurrent()
            assertEquals("JWT rotation must not cancel/restart the diary refresh", 1, diaryLoads)
        } finally {
            store.clear()
            client.close()
            Dispatchers.resetMain()
        }
    }

    private fun session(expired: Boolean) = UserSession("test-access", "test-refresh", expiresIn = 3600,
        tokenType = "bearer", user = UserInfo(aud = "authenticated", id = "test-owner", email = "test@example.test"),
        expiresAt = Clock.System.now() + if (expired) -2.hours else 1.hours)
}
