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
    private val food = FoodCandidate("variant", "food", "Творог", "Мой продукт", "Этикетка",
        300.0, "g", Nutrition(432.0, 36.0, 12.0, 45.0), false)
    private val entry = DiaryEntry("entry", food.name, food.brand, 125.0, "g", 300.0,
        food.nutrition, Nutrition(180.0, 15.0, 5.0, 18.75), Instant.parse("2026-10-06T06:00:00Z"),
        MealPeriod.Morning, food.id, food.foodId, null)

    @Test fun manualSelectionAndQuantityAreNotWritesUntilConfirmed() {
        var selected by mutableStateOf<FoodCandidate?>(null)
        val writes = mutableListOf<Double>()
        compose.setContent {
            JetmealTheme {
                Surface {
                    if (selected == null) FoodSearchContent(listOf(food), false, {}) { selected = it.single() }
                    else AmountContent(food.name, food.unit, food.amount, food.amount, food.nutrition,
                        false, false, null, { writes.add(it) }, null, {}, null)
                }
            }
        }
        compose.onNodeWithText(food.name).performClick()
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        compose.onNodeWithText("Количество (г)").performTextReplacement("125")
        compose.onNodeWithText("180 ккал").assertIsDisplayed()
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        compose.onNodeWithText("Добавить еду").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(125.0), writes) }
    }

    @Test fun diaryTapExposesQuantityCorrectionAndSoftDeleteAction() {
        var editing by mutableStateOf(false)
        var corrected: Double? = null
        var deletes = 0
        var notice by mutableStateOf<String?>(null)
        compose.setContent {
            JetmealTheme {
                Surface {
                    if (!editing) DayContent(LocalDate.of(2026, 10, 6), listOf(entry),
                        Targets(2000.0, 100.0, 60.0, 250.0), null, {}, { editing = true }, {})
                    else AmountContent(entry.name, entry.unit, entry.quantity, entry.basisAmount,
                        entry.basisNutrition, false, false, null,
                        { corrected = it; notice = "Количество изменено. Можно отменить." },
                        { deletes++; notice = "Запись удалена. Можно отменить." },
                        { editing = false; notice = null }, null, notice)
                }
            }
        }
        compose.onNodeWithText("Утро").performScrollTo().performClick()
        compose.onNodeWithText("${food.name} · ${food.brand}").performScrollTo().performClick()
        compose.onNodeWithText("Количество (г)").performTextReplacement("150")
        compose.onNodeWithText("216 ккал").assertIsDisplayed()
        compose.runOnIdle { assertNull(corrected); assertEquals(0, deletes) }
        compose.onNodeWithText("Сохранить количество").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(150.0, corrected!!, 0.0) }
        compose.onNodeWithText("Утро").performScrollTo().performClick()
        compose.onNodeWithText("${food.name} · ${food.brand}").performScrollTo().performClick()
        compose.onNodeWithText("Удалить запись").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun failedQuantityWriteKeepsEditorVisibleAndAllowsRetry() {
        var error by mutableStateOf<String?>(null)
        var successes = 0
        var requests = 0
        compose.setContent {
            JetmealTheme {
                AmountContent(entry.name, entry.unit, entry.quantity, entry.basisAmount,
                    entry.basisNutrition, false, false, error,
                    { requests++; error = "Нет соединения" }, null, { successes++ }, null)
            }
        }
        compose.onNodeWithText("Добавить еду").performScrollTo().performClick()
        compose.onNodeWithText("Нет соединения").assertIsDisplayed()
        compose.onNodeWithText("Количество (г)").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, successes); assertEquals(1, requests) }
        compose.onNodeWithText("Добавить еду").assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, successes); assertEquals(2, requests) }
    }

    @Test fun targetsCommitOnlyAfterConcreteReviewConfirmation() {
        var saved: Targets? = null
        compose.setContent {
            JetmealTheme {
                SettingsContent(null, "owner@example.test", false, { saved = it }, {})
            }
        }
        compose.onNodeWithText("Базовая цель (ккал)").performTextReplacement("2000")
        compose.onNodeWithText("Белки (г)").performTextReplacement("110")
        compose.onNodeWithText("Жиры (г)").performTextReplacement("65")
        compose.onNodeWithText("Углеводы (г)").performScrollTo().performTextReplacement("240")
        compose.onNodeWithText("Проверить изменения").performScrollTo().performClick()
        compose.onNodeWithText("Сохранить новые цели?").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Сохранить цели").performClick()
        compose.runOnIdle { assertEquals(Targets(2000.0, 110.0, 65.0, 240.0, 0.1), saved) }
    }

    @Test fun calendarNavigatesWithAccessibleCaloriesAndStatusAtLargeFont() {
        val date = LocalDate.of(2026, 10, 6)
        var opened: LocalDate? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                JetmealTheme {
                    Box(Modifier.width(320.dp).fillMaxSize()) {
                        CalendarContent(YearMonth.from(date), mapOf(date to 2500.0), mapOf(date to 2000.0), {}, { opened = it })
                    }
                }
            }
        }
        val description = date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", RussianLocale)) +
            ", ${number(2500.0)} ккал, Выше нормы" + (if (date == LocalDate.now()) ", сегодня" else "") + ". Открыть день"
        compose.onNodeWithContentDescription(description).assertIsDisplayed()
            // Seven squares must fit even at 320dp; Compose expands the physical
            // touch slop beyond the visual cell where space permits.
            .assertWidthIsAtLeast(36.dp).performClick()
        compose.runOnIdle { assertEquals(date, opened) }
    }

    @Test fun monthShowsSevenWeekdaysAndLastDateWithoutVerticalScrolling() {
        val month=YearMonth.of(2026,10)
        compose.setContent {
            JetmealTheme {
                Box(Modifier.width(320.dp).fillMaxSize()) {
                    CalendarContent(month,emptyMap(),emptyMap(),{}, {})
                }
            }
        }
        compose.onNodeWithText("ПН").assertIsDisplayed()
        compose.onNodeWithText("ВС").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "31 октября 2026, Нет записей. Открыть день"
        ).assertIsDisplayed()
    }

    @Test fun weekChartBarsAreClickableOnCompactScreensAndInfoIsOnDemand() {
        val monday=LocalDate.of(2026,10,5)
        val days=(0L..6L).map { offset ->
            BudgetDay(monday.plusDays(offset),1200.0,1800.0,DayLoggingStatus.Recorded)
        }
        val week=WeekState(monday,monday.plusDays(6),days,12600.0,8400.0,-900.0,
            4,1800.0,0.0)
        var openedDay: LocalDate? = null
        compose.setContent {
            JetmealTheme {
                Box(Modifier.width(320.dp).fillMaxSize()) {
                    WeekContent(week,{},onDay={openedDay=it})
                }
            }
        }
        compose.onNodeWithContentDescription("5 октября",substring=true)
            .assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(monday,openedDay) }
        compose.onNodeWithContentDescription("Информация: Ритм недели")
            .performClick()
        compose.onNodeWithText("Горизонтальная черта",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Понятно").performClick()
        compose.onNodeWithText("Горизонтальная черта",substring=true).assertDoesNotExist()
        compose.onNodeWithText("ккал · норма на сегодня").assertDoesNotExist()
    }

    @Test fun monthSummaryFollowsCalendarAndExplanationIsOptional() {
        val month=YearMonth.of(2026,10)
        compose.setContent {
            JetmealTheme {
                Box(Modifier.width(320.dp).fillMaxSize()) {
                    CalendarContent(month,emptyMap(),emptyMap(),{}, {})
                }
            }
        }
        compose.onNodeWithText("Итоги месяца").assertIsDisplayed()
        compose.onNodeWithText("В норме").assertDoesNotExist()
        compose.onNodeWithContentDescription("Информация: Цвета календаря").performClick()
        compose.onNodeWithText("Зелёный — записано",substring=true).assertIsDisplayed()
    }

    @Test fun weeklyDetailRowsAreHiddenUntilRequested() {
        val monday=LocalDate.of(2026,10,5)
        val days=(0L..6L).map { offset ->
            BudgetDay(monday.plusDays(offset),if(offset<3L) 1200.0 else 0.0,
                1800.0,if(offset<3L) DayLoggingStatus.Recorded else DayLoggingStatus.Missing)
        }
        val week=WeekState(monday,monday.plusDays(6),days,12600.0,3600.0,-900.0,
            4,1800.0,0.0)
        compose.setContent {
            JetmealTheme {
                WeekContent(week,{})
            }
        }
        compose.onNodeWithText("Ритм недели").assertExists()
        compose.onNodeWithText("Съедено").assertExists()
        compose.onNodeWithText("Понедельник, 5 октября").assertDoesNotExist()
        compose.onNodeWithText("Показать по дням").performScrollTo().performClick()
        compose.onNodeWithText("Понедельник, 5 октября").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Скрыть подробности").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Понедельник, 5 октября").assertDoesNotExist()
    }

    @Test fun dayViewShowsMealNamesWithoutRedundantHeading() {
        compose.setContent {
            JetmealTheme {
                DayContent(LocalDate.of(2026,10,6),listOf(entry),
                    Targets(2000.0,100.0,60.0,250.0),null,{},{},{})
            }
        }
        compose.onNodeWithText("Приёмы пищи").assertDoesNotExist()
        compose.onNodeWithText("Утро").assertExists()
    }

    @Test fun daySummarySupportsExpandedWidthAndLargeFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                JetmealTheme {
                    Box(Modifier.width(840.dp)) {
                        DayContent(LocalDate.of(2026, 10, 6), listOf(entry),
                            Targets(2000.0, 100.0, 60.0, 250.0), null, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Калории: ${number(180.0)} из ${number(2000.0)} ккал").assertIsDisplayed()
        compose.onNodeWithContentDescription("Белки: 15 из 100 г").performScrollTo().assertIsDisplayed()
    }
}
