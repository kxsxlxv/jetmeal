package com.kxsxlxv.jetmeal.data

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kxsxlxv.jetmeal.BuildConfig
import com.kxsxlxv.jetmeal.JetMealApplication
import com.kxsxlxv.jetmeal.MainActivity
import io.github.jan.supabase.auth.auth
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only the person's existing in-app session; never receives or prints credentials. */
@RunWith(AndroidJUnit4::class)
class HostedSavedSessionTest {
    @Test fun existingSignedInAccountCanReadDiaryTargetsAndCatalogue() {
        assumeTrue(BuildConfig.SUPABASE_URL.startsWith("https://"))
        ActivityScenario.launch(MainActivity::class.java).use {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as JetMealApplication
            val repository = requireNotNull(app.repository)
            runBlocking(Dispatchers.IO) {
                withTimeout(30_000) { repository.client.auth.awaitInitialization() }
            }
            assumeTrue("Sign into the app with the existing account before this test.",
                repository.client.auth.currentUserOrNull() != null)
            runBlocking(Dispatchers.IO) {
                var stage = "profile"
                try {
                    val zone = repository.timezone()
                    stage = "targets"
                    assertNotNull(repository.targets())
                    stage = "diary"
                    val day = LocalDate.now(zone)
                    repository.entries(day.minusDays(40), day.plusDays(1), zone)
                    stage = "catalogue"
                    repository.catalogue()
                    stage = "usage"
                    repository.usage()
                } catch (error: Exception) {
                    val classes = generateSequence<Throwable>(error) { it.cause }.take(8)
                        .joinToString(" -> ") { it.javaClass.simpleName }
                    throw AssertionError("Authenticated hosted read failed at $stage: $classes")
                }
            }
        }
    }
}
