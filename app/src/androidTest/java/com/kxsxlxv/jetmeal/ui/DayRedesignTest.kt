package com.kxsxlxv.jetmeal.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.kxsxlxv.jetmeal.domain.DiaryEntry
import com.kxsxlxv.jetmeal.domain.MealPeriod
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.domain.Targets
import com.kxsxlxv.jetmeal.domain.WeekBudget
import com.kxsxlxv.jetmeal.ui.theme.JetmealTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Presentation fixtures only. Real Supabase reads/writes remain covered by integration tests. */
class DayRedesignTest {
    @get:Rule val compose = createComposeRule()
    private val date = LocalDate.of(2020, 10, 6)
    private fun entry(id: String, name: String, meal: MealPeriod, time: String, kcal: Double) = DiaryEntry(
        id, name, null, 100.0, "g", 100.0, Nutrition(kcal, 10.0, 5.0, 20.0),
        Nutrition(kcal, 10.0, 5.0, 20.0), Instant.parse(time), meal, null, null, null,
    )

    @Test fun mealAccordionShowsOneGroupAndKeepsContextualAddAndQuantityEdit() {
        val breakfast = entry("morning", "Творог", MealPeriod.Morning, "2020-10-06T06:00:00Z", 180.0)
        val dinner = entry("evening", "Кефир", MealPeriod.Evening, "2020-10-06T18:00:00Z", 100.0)
        var added: MealPeriod? = null
        var edited: DiaryEntry? = null
        compose.setContent {
            JetmealTheme {
                Surface {
                    DayContent(date, listOf(breakfast, dinner), Targets(2000.0, 100.0, 60.0, 200.0), null,
                        { added = it }, { edited = it }, {})
                }
            }
        }
        compose.onNodeWithTag("day-diary").performScrollToNode(hasText("Кефир"))
        compose.onNodeWithText("Кефир").assertIsDisplayed()
        compose.onNodeWithText("Творог").assertDoesNotExist()
        compose.onNodeWithTag("day-diary").performScrollToNode(hasText("Утро"))
        compose.onNodeWithText("Утро").performClick()
        compose.onNodeWithTag("day-diary").performScrollToNode(hasText("Творог"))
        compose.onNodeWithText("Творог").assertIsDisplayed()
        compose.onNodeWithText("Кефир").assertDoesNotExist()
        compose.onNodeWithText("Творог").performClick()
        compose.runOnIdle { assertEquals(breakfast, edited) }
        compose.onNodeWithTag("day-diary").performScrollToNode(hasContentDescription("Добавить еду: Перекус"))
        compose.onNodeWithContentDescription("Добавить еду: Перекус").performClick()
        compose.runOnIdle { assertEquals(MealPeriod.Snack, added) }
    }

    @Test fun overTargetReportsMagnitudeInsideHeroWithoutDuplicatedVisibleCopy() {
        val food = entry("over", "Ужин", MealPeriod.Evening, "2020-10-06T18:00:00Z", 3500.0)
        compose.setContent {
            JetmealTheme {
                Surface { DayContent(date, listOf(food), Targets(2000.0, 100.0, 60.0, 200.0), null, {}, {}, {}) }
            }
        }
        compose.onNodeWithContentDescription("Калории:", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("превышение", substring = true).assertIsDisplayed()
        compose.onNodeWithText("% нормы", substring = true).assertDoesNotExist()
        compose.onNodeWithText("ккал сверх нормы", substring = true).assertDoesNotExist()
    }

    @Test fun exhaustedZeroAllowanceIsRepresentedWithoutPercentageOrInvalidNumbers() {
        val targets = Targets(2000.0, 100.0, 60.0, 200.0, limitRatio = 1.0)
        val week = WeekBudget.calculate(date, targets, mapOf(date.minusDays(1) to 20000.0))
        assertEquals(0.0, week.effectiveTarget, 0.0)
        compose.setContent {
            JetmealTheme {
                Surface { DayContent(date, emptyList(), targets, week, {}, {}, {}, confirmedZero = true) }
            }
        }
        compose.onNodeWithContentDescription("Калории: 0 из 0 ккал", substring = true).assertIsDisplayed()
        compose.onNodeWithText("% нормы", substring = true).assertDoesNotExist()
        compose.onNodeWithText("NaN", substring = true).assertDoesNotExist()
        compose.onNodeWithText("∞", substring = true).assertDoesNotExist()
    }

    @Test fun missingHistoricalDayIsNotDisplayedAsZeroConsumption() {
        var confirmed = false
        compose.setContent {
            JetmealTheme {
                Surface {
                    DayContent(date, emptyList(), Targets(2000.0, 100.0, 60.0, 200.0), null,
                        {}, {}, {}, onZeroDay = { confirmed = it })
                }
            }
        }
        compose.onNodeWithText("Нет данных за этот день").assertIsDisplayed()
        compose.onNodeWithContentDescription("Калории:", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("day-diary").performScrollToNode(hasText("Подтвердить 0 ккал"))
        compose.onNodeWithText("Подтвердить 0 ккал").performClick()
        compose.runOnIdle { assertEquals(true, confirmed) }
    }

    @Test fun consumptionWithZeroAllowanceKeepsActualAndExhaustedStateInHero() {
        val food = entry("zero-allowance", "Обед", MealPeriod.Day, "2020-10-06T13:00:00Z", 123.0)
        val targets = Targets(2000.0, 100.0, 60.0, 200.0, limitRatio = 1.0)
        val week = WeekBudget.calculate(date, targets,
            mapOf(date.minusDays(1) to 20000.0, date to food.nutrition.calories))
        assertEquals(0.0, week.effectiveTarget, 0.0)
        compose.setContent {
            JetmealTheme {
                Surface { DayContent(date, listOf(food), targets, week, {}, {}, {}) }
            }
        }
        compose.onNodeWithContentDescription("Калории: 123 из 0 ккал", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("норма исчерпана", substring = true).assertIsDisplayed()
        compose.onNodeWithText("% нормы", substring = true).assertDoesNotExist()
        compose.onNodeWithText("NaN", substring = true).assertDoesNotExist()
        compose.onNodeWithText("∞", substring = true).assertDoesNotExist()
    }

    @Test fun macroTargetsRoundToWholeGramsInHeroAndAccessibility() {
        val food = entry("macro-rounding", "Йогурт", MealPeriod.Morning,
            "2020-10-06T09:00:00Z", 220.0)
        compose.setContent {
            JetmealTheme {
                Surface {
                    DayContent(date, listOf(food),
                        Targets(2000.0, 190.0, 65.0, 163.8), null, {}, {}, {})
                }
            }
        }
        // The underlying 163.8g target is unchanged; only the Hero label rounds.
        compose.onNodeWithContentDescription("Углеводы: 20 из 164 г",
            substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("163,8", substring = true).assertDoesNotExist()
    }

    @Test fun rightHeroTogglesBetweenGoalBarsAndDistributionWithoutChangingLeftRing() {
        val food = entry("macro", "Йогурт", MealPeriod.Morning, "2020-10-06T09:00:00Z", 220.0)
        compose.setContent {
            JetmealTheme {
                Surface { DayContent(date, listOf(food), Targets(2000.0, 100.0, 60.0, 200.0),
                    null, {}, {}, {}) }
            }
        }
        val toggle = compose.onNodeWithTag("macro-hero-toggle")
        toggle.assertIsDisplayed()
        toggle.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Прогресс к целям БЖУ"))
        compose.onNodeWithContentDescription("Калории:", substring = true).assertIsDisplayed()
        toggle.performClick()
        toggle.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Распределение БЖУ"))
        compose.onNodeWithContentDescription("Калории:", substring = true).assertIsDisplayed()
        toggle.performClick()
        toggle.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Прогресс к целям БЖУ"))
    }


}
