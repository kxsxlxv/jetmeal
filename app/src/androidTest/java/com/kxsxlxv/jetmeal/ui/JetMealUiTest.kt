package com.kxsxlxv.jetmeal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.domain.*
import com.kxsxlxv.jetmeal.ui.theme.JetmealTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Fixtures exist only in this test source set; real Auth/HTTP/RLS is verified separately. */
class JetMealUiTest {
    @get:Rule val compose = createComposeRule()
    private val food = FoodCandidate("variant", "food", "Cottage cheese", "Personal brand", "Label",
        300.0, "g", Nutrition(432.0, 36.0, 12.0, 45.0), false)
    private val entry = DiaryEntry("entry", food.name, food.brand, 125.0, "g", 300.0,
        food.nutrition, Nutrition(180.0, 15.0, 5.0, 18.75), Instant.parse("2026-10-06T06:00:00Z"),
        MealPeriod.Morning, food.id, food.foodId, null)

    @Test fun manualSelectionAndQuantityAreNotWritesUntilConfirmed() {
        var selected by mutableStateOf<FoodCandidate?>(null)
        val writes = mutableListOf<Double>()
        compose.setContent {
            JetmealTheme(dynamicColor = false) {
                Surface {
                    if (selected == null) FoodSearchContent(listOf(food), false, {}) { selected = it }
                    else AmountContent(food.name, food.unit, food.amount, food.amount, food.nutrition,
                        false, false, null, { writes.add(it) }, null, {}, null)
                }
            }
        }
        compose.onNodeWithText(food.name).performClick()
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        compose.onNodeWithText("Quantity (g)").performTextReplacement("125")
        compose.onNodeWithText("180 kcal").assertIsDisplayed()
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        compose.onNodeWithText("Confirm food").performClick()
        compose.runOnIdle { assertEquals(listOf(125.0), writes) }
    }

    @Test fun diaryTapExposesQuantityCorrectionAndSoftDeleteAction() {
        var editing by mutableStateOf(false)
        var corrected: Double? = null
        var deletes = 0
        compose.setContent {
            JetmealTheme(dynamicColor = false) {
                Surface {
                    if (!editing) DayContent(LocalDate.of(2026, 10, 6), listOf(entry),
                        Targets(2000.0, 100.0, 60.0, 250.0), null, {}, { editing = true }, {})
                    else AmountContent(entry.name, entry.unit, entry.quantity, entry.basisAmount,
                        entry.basisNutrition, false, false, null, { corrected = it }, { deletes++ }, {}, null)
                }
            }
        }
        compose.onNodeWithText("${food.name} · ${food.brand}").performClick()
        compose.onNodeWithText("Quantity (g)").performTextReplacement("150")
        compose.onNodeWithText("216 kcal").assertIsDisplayed()
        compose.runOnIdle { assertNull(corrected); assertEquals(0, deletes) }
        compose.onNodeWithText("Save quantity").performClick()
        compose.runOnIdle { assertEquals(150.0, corrected!!, 0.0) }
        compose.onNodeWithText("Delete entry").performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun failedQuantityWriteKeepsEditorVisibleAndAllowsRetry() {
        var error by mutableStateOf<String?>(null)
        var successes = 0
        var requests = 0
        compose.setContent {
            JetmealTheme(dynamicColor = false) {
                AmountContent(entry.name, entry.unit, entry.quantity, entry.basisAmount,
                    entry.basisNutrition, false, false, error,
                    { requests++; error = "Connection failed" }, null, { successes++ }, null)
            }
        }
        compose.onNodeWithText("Confirm food").performClick()
        compose.onNodeWithText("Connection failed").assertIsDisplayed()
        compose.onNodeWithText("Quantity (g)").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, successes); assertEquals(1, requests) }
    }

    @Test fun targetsCommitOnlyAfterConcreteReviewConfirmation() {
        var saved: Targets? = null
        compose.setContent {
            JetmealTheme(dynamicColor = false) {
                SettingsContent(null, "owner@example.test", false, { saved = it }, {})
            }
        }
        compose.onNodeWithText("Base daily calories (kcal)").performTextReplacement("2000")
        compose.onNodeWithText("Protein (g)").performTextReplacement("110")
        compose.onNodeWithText("Fat (g)").performTextReplacement("65")
        compose.onNodeWithText("Carbohydrates (g)").performTextReplacement("240")
        compose.onNodeWithText("Review target changes").performScrollTo().performClick()
        compose.onNodeWithText("Confirm your targets").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Save targets").performClick()
        compose.runOnIdle { assertEquals(Targets(2000.0, 110.0, 65.0, 240.0, 0.1), saved) }
    }

    @Test fun calendarNavigatesWithAccessibleCaloriesAndStatusAtLargeFont() {
        val date = LocalDate.of(2026, 10, 6)
        var opened: LocalDate? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                JetmealTheme(dynamicColor = false) {
                    Box(Modifier.width(320.dp).fillMaxSize()) {
                        CalendarContent(YearMonth.from(date), mapOf(date to 2500.0), mapOf(date to 2000.0), {}, { opened = it })
                    }
                }
            }
        }
        val description = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")) +
            ", 2500 kilocalories, Near allowance"
        compose.onNodeWithContentDescription(description).assertIsDisplayed().assertWidthIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(date, opened) }
    }

    @Test fun daySummarySupportsExpandedWidthAndLargeFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                JetmealTheme(dynamicColor = false) {
                    Box(Modifier.width(840.dp)) {
                        DayContent(LocalDate.of(2026, 10, 6), listOf(entry),
                            Targets(2000.0, 100.0, 60.0, 250.0), null, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("180 / 2000 kcal").assertIsDisplayed()
        compose.onNodeWithText("1820 kcal remaining").assertIsDisplayed()
        compose.onNodeWithText("Protein").assertIsDisplayed()
    }
}
