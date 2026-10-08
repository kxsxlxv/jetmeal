package com.kxsxlxv.jetmeal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kxsxlxv.jetmeal.domain.WeightGoal
import com.kxsxlxv.jetmeal.domain.WeightMeasurement
import com.kxsxlxv.jetmeal.domain.WeightProgress
import com.kxsxlxv.jetmeal.ui.theme.JetmealTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

/** UI-only checks; sync/auth live in the regular integration tests. */
class WeightProgressUiTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val goal = WeightGoal(today.minusDays(1),today.plusDays(60),105.9,100.0)
    private val readings = listOf(
        WeightMeasurement("1",today.minusDays(4).atStartOfDay(zone).toInstant(),106.9,null,"picooc"),
        WeightMeasurement("2",today.minusDays(1).atStartOfDay(zone).toInstant(),105.9,null,"picooc"),
    )

    @Test fun chartHasDistinctMeasurementAndGoalViewsAndDatedSelectedValue() {
        val data = WeightProgress.build(readings,goal,today,zone)
        compose.setContent {
            JetmealTheme {
                Surface { WeightProgressChart(data) }
            }
        }
        compose.onNodeWithText("Динамика веса").assertIsDisplayed()
        compose.onNodeWithText("Измерения").assertIsDisplayed()
        compose.onNodeWithText("До цели").performClick()
        compose.onNodeWithContentDescription("График веса",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Промежутки",substring=true).assertIsDisplayed()
    }

    @Test fun weightGoalDateOpensNativeMaterialCalendar() {
        compose.setContent {
            JetmealTheme {
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        WeightSection(readings,goal,connected=true,loading=false,busy=false,
                            onAddWeight={},onSetGoal={_,_->},onConnect={_,_,_->},
                            onSync={},onDisconnect={})
                    }
                }
            }
        }
        compose.onNodeWithText("Дата достижения",substring=true).performScrollTo().performClick()
        compose.onNodeWithText("Отмена").assertIsDisplayed()
        compose.onNodeWithText("Выбрать").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Дата достижения",substring=true).assertIsDisplayed()
    }
}
