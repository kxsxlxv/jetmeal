package com.kxsxlxv.jetmeal.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kxsxlxv.jetmeal.domain.Targets
import com.kxsxlxv.jetmeal.domain.WeekBudget
import com.kxsxlxv.jetmeal.ui.theme.JetmealTheme
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class CompletedWeekExplanationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sundayIsListedAndFinalSurplusReplacesStaleSundayCorrection() {
        val monday = LocalDate.of(2026, 10, 5)
        val sunday = monday.plusDays(6)
        val targets = Targets(2000.0, 190.0, 65.0, 163.8)
        val actual = (0L..5L).associate { monday.plusDays(it) to
            (if (it == 0L) 2960.0 else 2000.0) } + (sunday to 1624.6)
        val history = (0L..6L).associate { monday.plusDays(it) to targets }
        val week = WeekBudget.calculate(sunday, targets, actual,
            asOfDayCompleted = true, dailyTargets = history)
        compose.setContent {
            JetmealTheme {
                Surface {
                    WeekBudgetExplanation(week, history)
                }
            }
        }
        compose.onNodeWithText("Откуда взялся итог").assertExists()
        compose.onNodeWithText("+585 ккал", substring = true).assertExists()
        compose.onNodeWithText("Показать расчёт по дням").performClick()
        // Previously the as-of-day filter silently omitted this Sunday.
        compose.onNodeWithText("вс, 11 окт", substring = true).assertExists()
        compose.onNodeWithText("375", substring = true).assertExists()
    }
}
