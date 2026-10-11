package com.kxsxlxv.jetmeal.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.ui.theme.JetmealTheme
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class WeeklyMacroSplitTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneCompactRailChangesMeaningOnTap() {
        compose.setContent {
            JetmealTheme {
                Surface {
                    WeeklyMacroSplit(
                        weekStart = LocalDate.of(2026, 10, 5),
                        actual = Nutrition(14584.6, 820.1, 635.8, 1424.6),
                        target = Nutrition(14000.0, 1330.0, 455.0, 1146.6),
                        fillFraction = { 1f },
                    )
                }
            }
        }
        val toggle = compose.onNodeWithContentDescription("Сменить вид недельных БЖУ", substring = true)
        toggle.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Доли энергии БЖУ"))
        toggle.performClick()
        toggle.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Выполнение недельной нормы БЖУ"))
        toggle.performClick()
        toggle.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Доли энергии БЖУ"))
    }
}
