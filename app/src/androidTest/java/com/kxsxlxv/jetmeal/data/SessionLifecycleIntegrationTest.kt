package com.kxsxlxv.jetmeal.data

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kxsxlxv.jetmeal.BuildConfig
import com.kxsxlxv.jetmeal.JetMealApplication
import com.kxsxlxv.jetmeal.MainActivity
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Activity/process stop + shared app client + real local Auth/PostgREST/widget reads. */
@RunWith(AndroidJUnit4::class)
class SessionLifecycleIntegrationTest {
    @Test fun stoppingActivityKeepsHeadlessSharedSessionUsable() {
        val endpoint = URI(BuildConfig.SUPABASE_URL)
        assumeTrue("Uses only generated local test accounts; never signs out the person's hosted account.",
            endpoint.scheme == "http" && endpoint.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as JetMealApplication
        val data = requireNotNull(app.repository)
        val mailpit = URI("http", null, endpoint.host, 54324, null, null, null).toString()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            runBlocking(Dispatchers.IO) {
                withTimeout(20_000) { data.client.auth.awaitInitialization() }
                if (data.client.auth.currentSessionOrNull() != null) data.client.auth.signOut()
                val email = "jetmeal-lifecycle-${UUID.randomUUID()}@example.test"
                val password = "Local-${UUID.randomUUID()}-aA1!"
                data.client.auth.signUpWith(Email) { this.email = email; this.password = password }
                if (data.client.auth.currentSessionOrNull() == null) {
                    data.client.auth.verifyEmailOtp(OtpType.Email.SIGNUP, email, LocalEmailCapture.code(mailpit, email))
                }
                assertTrue(data.client.auth.sessionStatus.value is SessionStatus.Authenticated)
            }
            val owner = requireNotNull(data.client.auth.currentUserOrNull()).id
            scenario.moveToState(Lifecycle.State.CREATED)
            runBlocking(Dispatchers.IO) {
                // ProcessLifecycleOwner deliberately delays ON_STOP; observe the real event.
                withTimeout(5_000) {
                    while (true) {
                        var stopped = false
                        instrumentation.runOnMainSync {
                            stopped = !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        }
                        if (stopped) break
                        delay(50)
                    }
                }
                assertFalse("Backgrounding must not erase SDK's usable session state",
                    data.client.auth.sessionStatus.value is SessionStatus.Initializing)
                withTimeout(3_000) { data.client.auth.awaitInitialization() }
                assertEquals(owner, data.client.auth.currentUserOrNull()?.id)
                withTimeout(20_000) { app.widgetCoordinator.syncFromServer() }
                assertEquals(owner, data.client.auth.currentUserOrNull()?.id)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(owner, data.client.auth.currentUserOrNull()?.id)
            runBlocking(Dispatchers.IO) { data.client.auth.signOut() }
        }
    }
}
