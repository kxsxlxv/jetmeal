package com.kxsxlxv.jetmeal.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.event.AuthEvent
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** SDK regression tests, with test-only transport/storage; not real Auth integration evidence. */
@OptIn(SupabaseExperimental::class)
class SessionRefreshTest {
    @Test fun actualSdkRetainsDnsAndTlsDiagnosticsBeforeItDropsTheCause() = runBlocking {
        for ((original, kind) in listOf(UnknownHostException("private-host") to ConnectionFailureKind.Dns,
            SSLHandshakeException("private-certificate") to ConnectionFailureKind.Tls)) {
            val capture = AuthFailureCapture()
            val client = client(RecordingSessions(expiredSession()), protected = true, capture = capture) { throw original }
            try {
                val event = withTimeout(5_000) { client.auth.events.first { it is AuthEvent.RefreshFailure } } as AuthEvent.RefreshFailure
                val sdkError = (event.cause as RefreshFailureCause.NetworkError).exception
                assertTrue("Pinned SDK wraps transport failures", sdkError is HttpRequestException)
                assertNull("Pinned SDK drops the original cause", sdkError.cause)
                val retained = capture.resolve(sdkError)
                assertEquals(kind, retained.kind)
                assertEquals(kind, capture.latest?.kind)
                assertFalse(retained.toString().contains("private-"))
            } finally { client.close() }
        }
    }

    @Test fun actualSdkRetainsRateLimitStatusCodeAndRequestReference() = runBlocking {
        val capture = AuthFailureCapture()
        val client = client(RecordingSessions(expiredSession()), 429, protected = true, capture = capture)
        try {
            val event = withTimeout(5_000) { client.auth.events.first { it is AuthEvent.RefreshFailure } } as AuthEvent.RefreshFailure
            val sdkError = (event.cause as RefreshFailureCause.NetworkError).exception
            assertTrue(sdkError is HttpRequestException)
            val retained = capture.resolve(sdkError)
            assertEquals(ConnectionFailureKind.RateLimit, retained.kind)
            assertEquals(429, retained.status)
            assertEquals("over_request_rate_limit", retained.code)
            assertEquals("01234567-89ab-cdef-0123-456789abcdef", retained.requestId)
        } finally { client.close() }
    }
    @Test fun pinnedSdkDeletesStoredSessionOnRefreshRateLimit() = runBlocking {
        val storage = RecordingSessions(expiredSession())
        val client = client(storage, 429)
        try {
            withTimeout(5_000) { client.auth.sessionStatus.first { it is SessionStatus.NotAuthenticated } }
            assertEquals(1, storage.deletes)
            assertNull(storage.session)
        } finally { client.close() }
    }

    @Test fun guardedTransientResponsesKeepSavedSessionAndRetry() = runBlocking {
        for ((status, code) in listOf(408 to "request_timeout", 429 to "over_request_rate_limit",
            503 to "unexpected_failure", 501 to "unexpected_failure", 400 to "request_timeout")) {
            val stored = expiredSession()
            val storage = RecordingSessions(stored)
            val client = client(storage, status, code, protected = true)
            try {
                withTimeout(5_000) { client.auth.sessionStatus.first { it is SessionStatus.RefreshFailure } }
                assertEquals("HTTP $status must preserve the refresh token", stored, storage.session)
                assertEquals(0, storage.deletes)
            } finally { client.close() }
        }
    }

    @Test fun guardedRevokedRefreshTokenStillClearsSession() = runBlocking {
        val storage = RecordingSessions(expiredSession())
        val client = client(storage, 400, "refresh_token_already_used", protected = true)
        try {
            withTimeout(5_000) { client.auth.sessionStatus.first { it is SessionStatus.NotAuthenticated } }
            assertEquals(1, storage.deletes)
            assertNull(storage.session)
        } finally { client.close() }
    }

    @Test fun expiredSavedSessionRecoversAfterOfflineStartupWithoutSigningIn() = runBlocking {
        val storage = RecordingSessions(expiredSession())
        var attempts = 0
        val client = client(storage, protected = true, retrySeconds = 0.05) {
            attempts++
            if (attempts == 1) throw UnknownHostException("private-host")
            """{"access_token":"new-test-access","refresh_token":"new-test-refresh","expires_in":3600,"token_type":"bearer","user":{"aud":"authenticated","id":"test-owner","email":"test@example.test"}}"""
        }
        try {
            withTimeout(5_000) { client.auth.sessionStatus.first { it is SessionStatus.RefreshFailure } }
            assertNotNull(storage.session)
            val recovered = withTimeout(5_000) { client.auth.sessionStatus.first { it is SessionStatus.Authenticated } }
            assertEquals("new-test-access", (recovered as SessionStatus.Authenticated).session.accessToken)
            assertEquals("new-test-refresh", storage.session?.refreshToken)
            assertEquals(2, attempts)
            assertEquals(0, storage.deletes)
        } finally { client.close() }
    }

    @Test fun headlessEmptySessionInitializationCompletesWithoutActivity() = runBlocking {
        val client = client(RecordingSessions(null), protected = true)
        try {
            withTimeout(5_000) { client.auth.awaitInitialization() }
            assertTrue(client.auth.sessionStatus.value is SessionStatus.NotAuthenticated)
        } finally { client.close() }
    }

    @Test fun guardDoesNotRetryOrReclassifyPasswordSignIn() = runBlocking {
        val storage = RecordingSessions(null)
        var requests = 0
        val client = client(storage, 429, protected = true) {
            requests++
            """{"code":429,"error_code":"over_request_rate_limit","msg":"private payload"}"""
        }
        try {
            withTimeout(5_000) { client.auth.awaitInitialization() }
            try {
                client.auth.signInWith(Email) { email = "test@example.test"; password = "test-password" }
                fail("Password sign-in rate limits must remain explicit errors")
            } catch (error: AuthRestException) {
                assertEquals(429, error.statusCode)
            }
            assertEquals(1, requests)
            assertEquals(0, storage.deletes)
            assertNull(storage.session)
        } finally { client.close() }
    }

    private fun client(storage: RecordingSessions, status: Int = 200, code: String = "over_request_rate_limit",
        protected: Boolean = false, retrySeconds: Double = 60.0,
        capture: AuthFailureCapture? = null,
        body: ((HttpRequestData) -> String)? = null): SupabaseClient =
        createSupabaseClient("https://session-regression.supabase.co", "sb_publishable_test") {
            osInformation = null
            defaultLogLevel = LogLevel.NONE
            if (protected) protectSessionRefresh { capture?.capture(it) }
            httpEngine = MockEngine { request -> respond(body?.invoke(request) ?: """{"code":$status,"error_code":"$code","msg":"private payload"}""",
                HttpStatusCode.fromValue(status), headersOf("Content-Type" to listOf("application/json"),
                    "sb-request-id" to listOf("01234567-89ab-cdef-0123-456789abcdef"))) }
            install(Auth) {
                sessionManager = storage
                codeVerifierCache = MemoryCodeVerifierCache()
                enableLifecycleCallbacks = false
                retryDelay = retrySeconds.seconds
            }
        }

    private fun expiredSession() = UserSession("test-access", "test-refresh", expiresIn = 3600,
        tokenType = "bearer", user = UserInfo(aud = "authenticated", id = "test-owner", email = "test@example.test"),
        expiresAt = Clock.System.now() - 2.hours)

    private class RecordingSessions(@Volatile var session: UserSession?) : SessionManager {
        @Volatile var deletes = 0
        override suspend fun saveSession(session: UserSession) { this.session = session }
        override suspend fun loadSession(): UserSession = session ?: throw NoSessionFoundException()
        override suspend fun deleteSession() { deletes++; session = null }
    }
}
