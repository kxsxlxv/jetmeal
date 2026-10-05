package com.kxsxlxv.jetmeal.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.kxsxlxv.jetmeal.BuildConfig
import com.kxsxlxv.jetmeal.JetMealApplication
import com.kxsxlxv.jetmeal.MainActivity
import com.kxsxlxv.jetmeal.data.FoodDraft
import com.kxsxlxv.jetmeal.data.LocalEmailCapture
import com.kxsxlxv.jetmeal.data.LogFood
import com.kxsxlxv.jetmeal.data.NutritionTools
import com.kxsxlxv.jetmeal.data.SupabaseRepository
import com.kxsxlxv.jetmeal.domain.MealPeriod
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.domain.Targets
import com.kxsxlxv.jetmeal.domain.WeekBudget
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.builtin.Email
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MainActivity, existing-account password sign-in, application repository and atomic database operations. */
@RunWith(AndroidJUnit4::class)
class JetMealEndToEndTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun authenticateSetTargetsLogEditUndoNavigateAndCaptureActualApp() {
        val uri = runCatching { URI(BuildConfig.SUPABASE_URL) }.getOrNull()
        assumeTrue("This test requires a configured real local Supabase stack and captured email.",
            uri != null && uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2") &&
                BuildConfig.SUPABASE_KEY.isNotBlank())
        val mailpit = URI("http", null, requireNotNull(uri).host, 54324, null, null, null).toString()
        val repository = requireNotNull((compose.activity.application as JetMealApplication).repository)
        runBlocking(Dispatchers.IO) {
            repository.client.auth.awaitInitialization()
            if (repository.client.auth.currentSessionOrNull() != null) repository.client.auth.signOut()
        }
        val email = "jetmeal-ui-${UUID.randomUUID()}@example.test"
        val password = " Local-${UUID.randomUUID()}-aA1! "
        // Test-only account creation/confirmation uses the real local Auth service, never runtime signup UI.
        runBlocking(Dispatchers.IO) {
            repository.client.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            if (repository.client.auth.currentSessionOrNull() == null) {
                val code = LocalEmailCapture.code(mailpit, email)
                repository.client.auth.verifyEmailOtp(OtpType.Email.SIGNUP, email, code)
            }
            repository.client.auth.signOut()
        }
        await(hasText("Email address") and hasSetTextAction())
        compose.onNodeWithText("Email address").performTextReplacement(email)
        compose.onNodeWithText("Password").performTextReplacement(password)
        assertEquals(null, repository.client.auth.currentSessionOrNull())
        click("Sign in")
        ready()
        val userId = requireNotNull(repository.client.auth.currentUserOrNull()).id
        assertEquals(email, repository.client.auth.currentUserOrNull()?.email)
        val persisted = runBlocking(Dispatchers.IO) { repository.client.auth.sessionManager.loadSession() }
        assertEquals(userId, persisted.user?.id)

        // Fixtures are created through the app's real typed operations for this local test account.
        val tools = NutritionTools(repository)
        val foodName = "Cottage cheese 5%"
        runBlocking(Dispatchers.IO) {
            tools.createFood(FoodDraft(foodName, "packaged", "Local label", "Nutrition label",
                300.0, "g", Nutrition(435.0, 51.0, 15.0, 9.6)))
        }

        navigate("Settings")
        listOf("Base daily calories (kcal)" to "2000", "Protein (g)" to "170", "Fat (g)" to "70",
            "Carbohydrates (g)" to "180", "Weekly adjustment limit (±%)" to "10").forEach { (label, value) ->
            compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
        }
        hideKeyboard()
        compose.onNodeWithText("Review target changes").performScrollTo().performClick()
        await(hasText("Confirm your targets"))
        assertEquals(null, runBlocking(Dispatchers.IO) { repository.targets() })
        click("Save targets")
        await(hasText("Targets saved."))
        ready()
        val targets = Targets(2000.0, 170.0, 70.0, 180.0)
        assertEquals(targets, runBlocking(Dispatchers.IO) { repository.targets() })

        navigate("Today")
        // Real HTTP reads are repeatedly superseded while navigating and searching.
        // A canceled transport may report IOException; it must not become a network banner.
        lateinit var model: JetMealViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[JetMealViewModel::class.java]
            repeat(6) {
                model.refresh()
                model.selectDestination(Destination.Week)
                model.selectDestination(Destination.Calendar)
                model.selectDestination(Destination.Today)
                model.search("C")
                model.search("Cottage")
                model.search("")
            }
        }
        ready()
        compose.waitUntil(timeoutMillis = 60_000) {
            !model.state.value.busy && !model.state.value.searching
        }
        compose.runOnUiThread {
            assertNull("Superseded real reads must not create a connection error.", model.state.value.error)
            assertEquals(Destination.Today, model.state.value.destination)
            assertTrue(model.state.value.foods.any { it.name == foodName })
        }
        compose.onAllNodesWithText("+ Add").onFirst().performScrollTo().performClick()
        await(hasText("Search your foods") and hasSetTextAction())
        compose.onNodeWithText("Search your foods").performTextReplacement("Cottage")
        hideKeyboard()
        await(hasText(foodName) and hasClickAction())
        compose.onNode(hasText(foodName) and hasClickAction()).performClick()
        await(hasText("Quantity (g)") and hasSetTextAction())
        compose.onNodeWithText("Quantity (g)").performTextReplacement("125")
        hideKeyboard()
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now(zone)
        assertTrue(readDay(repository, date).isEmpty())
        click("Confirm food")
        await(hasText("Food logged. Undo is available."))
        ready()
        awaitAbsent(hasText("Quantity (g)") and hasSetTextAction())
        var entry = readDay(repository, date).single()
        assertEquals(125.0, entry.quantity, 0.0)
        assertEquals(181.25, entry.nutrition.calories, 0.001)
        assertEquals(MealPeriod.Morning, entry.meal)

        val rowLabel = "$foodName · Local label"
        compose.onNodeWithText(rowLabel).performScrollTo().performClick()
        await(hasText("Quantity (g)") and hasSetTextAction())
        compose.onNodeWithText("Quantity (g)").performTextReplacement("150")
        hideKeyboard()
        click("Save quantity")
        await(hasText("Quantity updated. Undo is available."))
        ready()
        awaitAbsent(hasText("Quantity (g)") and hasSetTextAction())
        entry = readDay(repository, date).single()
        assertEquals(150.0, entry.quantity, 0.0)
        assertEquals(217.5, entry.nutrition.calories, 0.001)
        assertEquals(300.0, entry.basisAmount, 0.0)
        assertEquals(435.0, entry.basisNutrition.calories, 0.0)

        compose.onNodeWithText(rowLabel).performScrollTo().performClick()
        await(hasText("Delete entry") and hasClickAction())
        click("Delete entry")
        await(hasText("Entry removed. Undo is available."))
        ready()
        awaitAbsent(hasText("Quantity (g)") and hasSetTextAction())
        assertTrue(readDay(repository, date).isEmpty())
        click("Undo")
        await(hasText("Action undone."))
        ready()
        assertEquals(entry.id, readDay(repository, date).single().id)

        val previous = date.minusDays(1)
        runBlocking(Dispatchers.IO) {
            tools.logFood(LogFood(quantity = 1.0, consumedAt = previous.atTime(13, 0).atZone(zone).toInstant(),
                meal = MealPeriod.Day, estimateName = "Yesterday's meals", unit = "serving",
                estimateNutrition = Nutrition(2500.0, 130.0, 90.0, 220.0), confidence = 0.8))
        }
        // Both boundaries must survive SDK query serialization once adjacent dates have data.
        assertEquals(entry.id, readDay(repository, date).single().id)
        assertEquals("Yesterday's meals", readDay(repository, previous).single().name)
        click("Refresh")
        ready()
        if (compose.onAllNodes(hasText("Dismiss") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) click("Dismiss")
        capture("Today")

        navigate("Week")
        val expectedWeek = WeekBudget.calculate(date, targets, mapOf(previous to 2500.0, date to 217.5))
        val actualWeek = runBlocking(Dispatchers.IO) { requireNotNull(tools.getWeek(date).data) }
        assertEquals(expectedWeek.effectiveTarget, actualWeek.effectiveTarget, 0.001)
        assertEquals(expectedWeek.totalConsumed, actualWeek.totalConsumed, 0.001)
        val deviation = (if (expectedWeek.deviation > 0) "+" else "") +
            String.format(Locale.getDefault(), "%.0f", expectedWeek.deviation)
        compose.onNodeWithText("Completed-day deviation: $deviation kcal").assertIsDisplayed()
        capture("Week")

        navigate("Calendar")
        if (YearMonth.from(previous) != YearMonth.from(date)) {
            click("Previous")
            ready()
        }
        capture("Calendar")
        val previousDescription = previous.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))
        val calendarCell = hasContentDescription(previousDescription, substring = true) and hasClickAction()
        await(calendarCell)
        compose.onNode(calendarCell).performScrollTo().performClick()
        ready()
        await(hasText("Yesterday's meals") and hasClickAction())
        assertEquals("Yesterday's meals", readDay(repository, previous).single().name)
        compose.onNodeWithText("Back").performClick()
        ready()

        navigate("Settings")
        compose.onNodeWithText("Daily targets").performScrollTo()
        capture("Settings")

        // Activity recreation verifies retained UI/account state; process death and APK updates are separate checks.
        compose.activityRule.scenario.recreate()
        ready()
        assertEquals(userId, repository.client.auth.currentUserOrNull()?.id)
        navigate("Today")
        await(hasText(rowLabel) and hasClickAction())
        assertNotNull(repository.client.auth.currentSessionOrNull())
        println("JETMEAL_SCREENSHOTS=" + screenshotDirectory().absolutePath)
    }

    private fun readDay(repository: SupabaseRepository, date: LocalDate) = runBlocking(Dispatchers.IO) {
        NutritionTools(repository).getDay(date).data.entries
    }

    private fun await(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 60_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitAbsent(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 60_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
    }

    private fun ready() {
        await(hasText("Refresh") and hasClickAction() and isEnabled())
        compose.waitForIdle()
    }

    private fun click(label: String) {
        val matcher = hasText(label) and hasClickAction() and isEnabled()
        await(matcher)
        compose.onNode(matcher).performClick()
    }

    private fun navigate(destination: String) {
        click(destination)
        ready()
    }

    private fun hideKeyboard() {
        compose.runOnUiThread {
            val manager = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }

    private fun screenshotDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(requireNotNull(context.getExternalFilesDir(null)), "verification").also { check(it.mkdirs() || it.isDirectory) }
    }

    private fun capture(destination: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(screenshotDirectory(), "$destination.png")).use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
