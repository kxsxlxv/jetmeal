package com.kxsxlxv.jetmeal.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kxsxlxv.jetmeal.BuildConfig
import com.kxsxlxv.jetmeal.JetMealApplication
import com.kxsxlxv.jetmeal.MainActivity
import io.github.jan.supabase.auth.auth
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Captures real MainActivity data after the local E2E has established a persisted session.
 * System night mode, font scale and window dimensions are supplied externally by the runner.
 * This test creates no account and performs no food, diary or target write.
 */
@RunWith(AndroidJUnit4::class)
class DesignViewportTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: JetMealViewModel
    private val viewport = InstrumentationRegistry.getArguments().getString("viewport", "default").orEmpty()
        .replace(Regex("[^A-Za-z0-9_-]"), "_")

    @Test fun captureExistingRealSessionAcrossTimelineAndSettings() {
        val uri = runCatching { URI(BuildConfig.SUPABASE_URL) }.getOrNull()
        assumeTrue("Viewport verification requires the real persisted local integration account.",
            uri != null && uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))
        val repository = requireNotNull((compose.activity.application as JetMealApplication).repository)
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[JetMealViewModel::class.java] }
        ready()
        val userId = requireNotNull(repository.client.auth.currentUserOrNull()).id
        assertNotNull(repository.client.auth.currentSessionOrNull())
        assertEquals("ru", compose.activity.resources.configuration.locales[0].language)

        val today = LocalDate.now()
        compose.runOnUiThread { model.closeSettings(); model.openDate(today) }
        ready()
        compose.onNodeWithTag("day-diary").performScrollToIndex(0)
        assertScaleSelectorIsReadableAndTouchable()
        capture("Day-top")
        compose.onNodeWithTag("day-diary").performScrollToNode(hasText("Приёмы пищи"))
        capture("Day-meals")

        // Search and preview use the real personal catalogue and stop before confirmation.
        if (viewport !in setOf("large", "narrow") && model.state.value.foods.isNotEmpty()) {
            compose.onNodeWithTag("day-diary").performScrollToNode(hasContentDescription("Добавить еду: Утро"))
            icon("Добавить еду: Утро")
            await(hasText("Найти еду в каталоге") and hasSetTextAction())
            ready()
            capture("Add-search")
            val food = model.state.value.foods.firstOrNull()
            if (food != null) {
                val matcher = hasText(food.name) and hasClickAction()
                await(matcher)
                val candidates = compose.onAllNodes(matcher)
                candidates[candidates.fetchSemanticsNodes().lastIndex].performClick()
                await(hasText("Количество", substring = true) and hasSetTextAction())
                capture("Add-quantity-preview")
            }
            Espresso.pressBack()
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasText("Количество", substring = true) and hasSetTextAction())
                    .fetchSemanticsNodes().isEmpty()
            }
            compose.waitForIdle()
        }

        switchScale(TimeScale.Week)
        capture("Week")
        switchScale(TimeScale.Month)
        capture("Month")
        switchScale(TimeScale.Quarter)
        compose.runOnUiThread { model.showPeriod(TimelinePeriods.start(TimeScale.Quarter, today).minusMonths(3)) }
        ready()
        capture("Quarter-history")

        icon("Настройки")
        await(hasText("Ваши цели"))
        compose.onNodeWithText("Ваши цели").performScrollTo()
        capture("Settings-top")
        compose.onNodeWithText("Корректировка калорий").performScrollTo()
        capture("Settings-adjustment")

        compose.activityRule.scenario.recreate()
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[JetMealViewModel::class.java] }
        ready()
        await(hasText("Ваши цели"))
        assertEquals(userId, repository.client.auth.currentUserOrNull()?.id)
        assertEquals(Destination.Settings, model.state.value.destination)
        assertNotNull(repository.client.auth.currentSessionOrNull())
        icon("Назад")
        compose.runOnUiThread { model.openDate(today) }
        ready()
        compose.onNodeWithTag("day-diary").performScrollToIndex(0)
        capture("Day-restored")
        println("JETMEAL_VIEWPORT_SCREENSHOTS=" + screenshotDirectory().absolutePath)
    }

    private fun switchScale(scale: TimeScale) {
        compose.runOnUiThread { model.setTimeScale(scale); model.resetPeriod() }
        ready()
        assertEquals(scale, model.state.value.scale)
    }

    private fun assertScaleSelectorIsReadableAndTouchable() {
        val minimumTargetPx = 48f * compose.activity.resources.displayMetrics.density
        TimeScale.entries.forEach { scale ->
            val button = compose.onNode(hasText(scale.label) and isToggleable())
            button.assertIsDisplayed().assertIsEnabled()
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue("${scale.label}: touch target width ${bounds.width}px is below 48dp",
                bounds.width + .5f >= minimumTargetPx)
            assertTrue("${scale.label}: touch target height ${bounds.height}px is below 48dp",
                bounds.height + .5f >= minimumTargetPx)

            // Inspect the actual rendered Text node; merged button semantics cannot detect a
            // clipped label. The ancestor condition distinguishes the scale День from a meal.
            val label = compose.onNode(
                hasText(scale.label) and hasAnyAncestor(isToggleable()), useUnmergedTree = true,
            )
            label.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertTrue("${scale.label}: rendered text layout is unavailable", action(layouts))
            }
            assertTrue("${scale.label}: expected a rendered text layout", layouts.isNotEmpty())
            layouts.forEach { layout ->
                val text = layout.layoutInput.text.text
                val lastGlyph = if (text.isNotEmpty()) layout.getBoundingBox(text.lastIndex) else null
                val textBounds = label.fetchSemanticsNode().boundsInRoot
                val diagnostic = "${scale.label} in $viewport: result.size=${layout.size}, " +
                    "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                    "lastGlyph=$lastGlyph, buttonBounds=$bounds, textBounds=$textBounds, " +
                    "lines=${layout.lineCount}, fontScale=${compose.activity.resources.configuration.fontScale}"
                assertEquals("Scale label must remain one line. $diagnostic", 1, layout.lineCount)
                assertFalse("Scale label must not be ellipsized. $diagnostic", layout.isLineEllipsized(0))
                // Paragraph width can retain the parent's available width while the Text layout
                // adopts the word's intrinsic width. Check actual glyphs instead of treating that
                // difference as clipping; allow only one pixel for raster/layout rounding.
                text.indices.forEach { index ->
                    val glyph = layout.getBoundingBox(index)
                    val detail = "glyph[$index]=$glyph. $diagnostic"
                    assertTrue("Glyph falls outside its text width. $detail",
                        glyph.left >= -1f && glyph.right <= layout.size.width + 1f)
                    assertTrue("Glyph falls outside its text height. $detail",
                        glyph.top >= -1f && glyph.bottom <= layout.size.height + 1f)
                    assertTrue("Glyph falls outside its button horizontally. $detail",
                        textBounds.left + glyph.left >= bounds.left - 1f &&
                            textBounds.left + glyph.right <= bounds.right + 1f)
                    assertTrue("Glyph falls outside its button vertically. $detail",
                        textBounds.top + glyph.top >= bounds.top - 1f &&
                            textBounds.top + glyph.bottom <= bounds.bottom + 1f)
                }
            }
        }
    }

    private fun ready() {
        compose.waitUntil(60_000) {
            ::model.isInitialized && !model.state.value.authLoading && model.state.value.email != null &&
                !model.state.value.busy && !model.state.value.searching
        }
        compose.waitForIdle()
        assertNull("Real local viewport request failed", model.state.value.error)
    }

    private fun await(matcher: SemanticsMatcher) {
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun icon(description: String) {
        val matcher = hasContentDescription(description) and hasClickAction() and isEnabled()
        await(matcher)
        compose.onNode(matcher).performClick()
        compose.waitForIdle()
    }

    private fun screenshotDirectory(): File = File(
        requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
        "redesign-viewport/$viewport",
    ).also { check(it.mkdirs() || it.isDirectory) }

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        FileOutputStream(File(screenshotDirectory(), "$name.png")).use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }
}
