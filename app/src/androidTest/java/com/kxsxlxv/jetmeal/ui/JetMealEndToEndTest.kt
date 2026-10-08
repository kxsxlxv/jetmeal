package com.kxsxlxv.jetmeal.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import com.kxsxlxv.jetmeal.BuildConfig
import com.kxsxlxv.jetmeal.JetMealApplication
import com.kxsxlxv.jetmeal.MainActivity
import com.kxsxlxv.jetmeal.data.*
import com.kxsxlxv.jetmeal.domain.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.builtin.Email
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MainActivity, local Auth/Postgres, real typed operations, actual rendered screenshots. */
@RunWith(AndroidJUnit4::class)
class JetMealEndToEndTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: JetMealViewModel
    private val ru = Locale.forLanguageTag("ru-RU")

    @Test fun authenticateSetTargetsLogEditUndoNavigateAndCaptureActualApp() {
        val uri = runCatching { URI(BuildConfig.SUPABASE_URL) }.getOrNull()
        assumeTrue("Requires real local Supabase Auth and captured confirmation email.",
            uri != null && uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2") &&
                BuildConfig.SUPABASE_KEY.isNotBlank())
        val mailpit = URI("http", null, requireNotNull(uri).host, 54324, null, null, null).toString()
        val repository = requireNotNull((compose.activity.application as JetMealApplication).repository)
        runBlocking(Dispatchers.IO) {
            repository.client.auth.awaitInitialization()
            if (repository.client.auth.currentSessionOrNull() != null) repository.client.auth.signOut()
        }
        val email = "jetmeal-redesign-${UUID.randomUUID()}@example.test"
        val password = " Local-${UUID.randomUUID()}-aA1! "
        // Test setup alone provisions and confirms an account against real local Auth.
        runBlocking(Dispatchers.IO) {
            repository.client.auth.signUpWith(Email) { this.email = email; this.password = password }
            if (repository.client.auth.currentSessionOrNull() == null) {
                repository.client.auth.verifyEmailOtp(OtpType.Email.SIGNUP, email, LocalEmailCapture.code(mailpit, email))
            }
            repository.client.auth.signOut()
        }
        await(hasText("Электронная почта") and hasSetTextAction())
        compose.onNodeWithText("Электронная почта").performTextReplacement(email)
        compose.onNodeWithText("Пароль").performTextReplacement(password)
        assertNull(repository.client.auth.currentSessionOrNull())
        hideKeyboard()
        click("Войти")
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[JetMealViewModel::class.java] }
        ready()
        val userId = requireNotNull(repository.client.auth.currentUserOrNull()).id
        assertEquals(email, repository.client.auth.currentUserOrNull()?.email)
        assertEquals(userId, runBlocking(Dispatchers.IO) { repository.client.auth.sessionManager.loadSession() }.user?.id)
        val tools = NutritionTools(repository)
        val foodName = "Творог 5%"
        runBlocking(Dispatchers.IO) {
            tools.createFood(FoodDraft(foodName, "packaged", "Личный каталог", "Этикетка",
                300.0, "g", Nutrition(435.0, 51.0, 15.0, 9.6)))
        }

        icon("Настройки")
        listOf("Базовая цель (ккал)" to "2000", "Белки (г)" to "170", "Жиры (г)" to "70",
            "Углеводы (г)" to "180", "Точное значение (±%)" to "10").forEach { (label, value) ->
            compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
        }
        hideKeyboard()
        compose.onNodeWithText("Проверить изменения").performScrollTo().performClick()
        await(hasText("Сохранить новые цели?"))
        assertNull(runBlocking(Dispatchers.IO) { repository.targets() })
        capture("Settings-confirmation")
        click("Сохранить цели")
        ready()
        val targets = Targets(2000.0, 170.0, 70.0, 180.0)
        assertEquals(targets, runBlocking(Dispatchers.IO) { repository.targets() })
        icon("Назад")
        ready()

        // Repeatedly cancel actual HTTP work; cancellation must never appear as a connection error.
        compose.runOnUiThread {
            repeat(6) {
                model.refresh()
                model.setTimeScale(TimeScale.Week)
                model.setTimeScale(TimeScale.Month)
                model.setTimeScale(TimeScale.Day)
                model.search("Т")
                model.search("Творог")
                model.search("")
            }
        }
        ready()
        compose.runOnUiThread {
            assertNull("Superseded real reads must not show network errors", model.state.value.error)
            assertEquals(TimeScale.Day, model.state.value.scale)
            assertTrue(model.state.value.foods.any { it.name == foodName })
        }
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now(zone)
        dayScroll(hasContentDescription("Добавить еду: Утро"))
        icon("Добавить еду: Утро")
        await(hasText("Найти еду в каталоге") and hasSetTextAction())
        compose.onNodeWithText("Найти еду в каталоге").performTextReplacement("Творог")
        hideKeyboard()
        await(hasText(foodName) and hasClickAction())
        capture("Add-search")
        compose.onNode(hasText(foodName) and hasClickAction()).performClick()
        await(hasText("Количество (г)") and hasSetTextAction())
        compose.onNodeWithText("Количество (г)").performTextReplacement("125")
        hideKeyboard()
        assertTrue(readDay(repository, date).isEmpty())
        capture("Add-quantity-preview")
        click("Добавить еду")
        ready()
        awaitAbsent(hasText("Количество (г)") and hasSetTextAction())
        var entry = readDay(repository, date).single()
        assertEquals(125.0, entry.quantity, 0.0)
        assertEquals(181.25, entry.nutrition.calories, .001)
        assertEquals(MealPeriod.Morning, entry.meal)

        val rowLabel = "$foodName · Личный каталог"
        expandMeal("Утро")
        dayScroll(hasText(rowLabel))
        compose.onNodeWithText(rowLabel).performClick()
        await(hasText("Количество (г)") and hasSetTextAction())
        compose.onNodeWithText("Количество (г)").performTextReplacement("150")
        hideKeyboard()
        capture("Quantity-edit")
        click("Сохранить количество")
        ready()
        awaitAbsent(hasText("Количество (г)") and hasSetTextAction())
        entry = readDay(repository, date).single()
        assertEquals(150.0, entry.quantity, 0.0)
        assertEquals(217.5, entry.nutrition.calories, .001)
        assertEquals(300.0, entry.basisAmount, 0.0)
        assertEquals(435.0, entry.basisNutrition.calories, 0.0)
        dayScroll(hasText(rowLabel))
        compose.onNodeWithText(rowLabel).performClick()
        click("Удалить запись")
        ready()
        awaitAbsent(hasText("Количество (г)") and hasSetTextAction())
        assertTrue(readDay(repository, date).isEmpty())
        click("Отменить")
        ready()
        assertEquals(entry.id, readDay(repository, date).single().id)

        // Rich visual fixtures belong only to this isolated real local account. No runtime sample data.
        val previous = date.minusDays(1)
        runBlocking(Dispatchers.IO) {
            tools.logFood(LogFood(quantity = 1.0, consumedAt = previous.atTime(13, 0).atZone(zone).toInstant(),
                meal = MealPeriod.Day, estimateName = "Обед с пастой", unit = "serving",
                estimateNutrition = Nutrition(2500.0, 130.0, 90.0, 220.0), confidence = .8))
            val dishes = listOf(
                FoodDraft("Йогурт натуральный", "packaged", null, "Этикетка", 100.0, "g", Nutrition(110.0, 9.0, 5.0, 7.0)),
                FoodDraft("Рис с овощами", "generic", null, "Личный рецепт", 200.0, "g", Nutrition(260.0, 6.0, 4.0, 50.0)),
                FoodDraft("Филе куриное", "generic", null, "Личный рецепт", 150.0, "g", Nutrition(248.0, 46.0, 5.0, 0.0)),
                FoodDraft("Салат с томатами", "generic", null, "Личный рецепт", 120.0, "g", Nutrition(90.0, 3.0, 6.0, 7.0)),
                FoodDraft("Лосось запечённый", "generic", null, "Личный рецепт", 180.0, "g", Nutrition(360.0, 36.0, 23.0, 0.0)),
                FoodDraft("Яблоко", "generic", null, "Пищевая ценность", 160.0, "g", Nutrition(83.0, .4, .3, 22.0)),
            )
            dishes.forEach { tools.createFood(it) }
            val catalogue = tools.searchFood("").data
            dishes.forEachIndexed { index, dish ->
                val candidate = catalogue.single { it.name == dish.name }
                val meal = when(index) { 0 -> MealPeriod.Morning; 1,2,3 -> MealPeriod.Day; 4 -> MealPeriod.Evening; else -> MealPeriod.Snack }
                tools.logFood(LogFood(candidate.id, dish.servingAmount,
                    date.atTime(when(meal) {MealPeriod.Morning->8;MealPeriod.Day->13;MealPeriod.Evening->19;MealPeriod.Snack->15}, index)
                        .atZone(zone).toInstant(), meal))
            }
            val currentMonthStart = date.withDayOfMonth(1)
            val quarter = TimelinePeriods.start(TimeScale.Quarter, date)
            val pastQuarter = quarter.minusMonths(3)
            val history = buildList {
                var day = currentMonthStart
                while(day < previous) { add(day); day = day.plusDays(1) }
                (0..2).forEach { month ->
                    listOf(3L, 8L, 12L, 19L, 25L).forEach { offset -> add(pastQuarter.plusMonths(month.toLong()).plusDays(offset)) }
                }
            }.distinct()
            history.forEachIndexed { index, day ->
                tools.logFood(LogFood(quantity = 1.0, consumedAt = day.atTime(19, 0).atZone(zone).toInstant(),
                    meal = MealPeriod.Evening, estimateName = "Рацион за день", unit = "serving",
                    estimateNutrition = Nutrition(listOf(1950.0, 2210.0, 1780.0, 2010.0)[index % 4], 150.0, 65.0, 190.0), confidence = .85))
            }
        }
        assertEquals(7, readDay(repository, date).size)
        assertEquals("Обед с пастой", readDay(repository, previous).single().name)
        dayTop()
        compose.onNodeWithTag("day-diary").performTouchInput { swipeDown() }
        ready(); clearNotice()
        dayTop(); capture("Day-populated")
        expandMeal("День")
        dayScroll(hasText("Рис с овощами")); capture("Day-accordion-lunch")
        expandMeal("Вечер")
        dayScroll(hasText("Лосось запечённый")); capture("Day-accordion-evening")
        // An actually empty future date exercises the accordion's empty state without erasing data.
        compose.runOnUiThread { model.openDate(date.plusDays(1)) }
        ready(); expandMeal("Перекус"); dayScroll(hasText("Добавьте первое блюдо")); capture("Day-empty-meal")
        icon("Текущий период"); ready()
        assertEquals(date, model.state.value.day)
        runBlocking(Dispatchers.IO) {
            tools.logFood(LogFood(quantity = 1.0, consumedAt = date.atTime(16, 0).atZone(zone).toInstant(),
                meal = MealPeriod.Snack, estimateName = "Десерт и кофе", unit = "serving",
                estimateNutrition = Nutrition(1200.0, 16.0, 65.0, 139.0), confidence = .75))
        }
        dayTop()
        compose.onNodeWithTag("day-diary").performTouchInput { swipeDown() }
        compose.waitUntil(60_000) {
            !model.state.value.busy && model.state.value.entries.any { it.name == "Десерт и кофе" }
        }
        ready(); clearNotice(); dayTop()
        val overage = "превышение ${number(model.state.value.entries.sumOf { it.nutrition.calories } - requireNotNull(model.state.value.week).effectiveTarget)} ккал"
        compose.onNodeWithContentDescription(overage, substring = true).assertIsDisplayed()
        capture("Day-over-target")

        // All scales use the same arrows, native pager and reset behavior, backed by real reads.
        listOf(TimeScale.Day, TimeScale.Week, TimeScale.Month, TimeScale.Quarter).forEach { scale ->
            navigateScale(scale)
            icon("Текущий период"); ready()
            val current = TimelinePeriods.start(scale, date)
            assertEquals(current, TimelinePeriods.start(scale, model.state.value.day))
            icon("Предыдущий период"); ready()
            assertEquals(TimelinePeriods.move(scale, current, -1), TimelinePeriods.start(scale, model.state.value.day))
            val beforeRecreation=model.state.value.day
            compose.activityRule.scenario.recreate()
            compose.runOnUiThread { model=ViewModelProvider(compose.activity)[JetMealViewModel::class.java] }
            ready()
            assertEquals("Recreating a paged timeline must keep the selected date",beforeRecreation,model.state.value.day)
            compose.onNodeWithContentDescription("Шкала питания. Листайте периоды влево или вправо")
                .performTouchInput { swipeLeft() }
            ready()
            compose.waitUntil(60_000) { TimelinePeriods.start(scale, model.state.value.day) == current }
            icon("Следующий период"); ready()
            assertEquals(TimelinePeriods.move(scale, current, 1), TimelinePeriods.start(scale, model.state.value.day))
            icon("Текущий период"); ready()
            assertEquals(current, TimelinePeriods.start(scale, model.state.value.day))
        }
        navigateScale(TimeScale.Week)
        val actualWeek = runBlocking(Dispatchers.IO) { requireNotNull(tools.getWeek(date).data) }
        assertEquals(actualWeek.totalConsumed, requireNotNull(model.state.value.week).totalConsumed, .001)
        compose.onNodeWithText("Ритм недели").assertIsDisplayed()
        capture("Week")
        navigateScale(TimeScale.Month); capture("Month")
        val cell = hasContentDescription(previous.format(DateTimeFormatter.ofPattern("d MMMM yyyy", ru)), substring = true) and hasText(previous.dayOfMonth.toString()) and hasClickAction()
        await(cell); compose.onNode(cell).performScrollTo().performClick(); ready()
        assertEquals(TimeScale.Day, model.state.value.scale)
        assertEquals(previous, model.state.value.day)
        dayTop(); capture("Day-historical")
        dayScroll(hasText("Обед с пастой")); capture("Day-historical-food")
        navigateScale(TimeScale.Quarter); capture("Quarter-current")
        icon("Предыдущий период"); ready(); capture("Quarter-history")
        val quarterDate=model.state.value.monthCalories.keys.minOrNull()!!
        val quarterLabel="${quarterDate.dayOfMonth} · ${number(model.state.value.monthCalories.getValue(quarterDate))} ккал"
        compose.onAllNodesWithText("Выбрать день").onFirst().performClick()
        click(quarterLabel); ready()
        assertEquals(TimeScale.Day,model.state.value.scale)
        assertEquals(quarterDate,model.state.value.day)
        dayTop(); capture("Quarter-selected-day")
        icon("Настройки"); compose.onNodeWithText("Ваши цели").performScrollTo(); capture("Settings")
        compose.onNodeWithText("Корректировка калорий").performScrollTo(); capture("Settings-adjustment")
        // Activity recreation retains the real authenticated account and secondary navigation.
        compose.activityRule.scenario.recreate()
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[JetMealViewModel::class.java] }
        await(hasText("Ваши цели"))
        assertEquals(userId, repository.client.auth.currentUserOrNull()?.id)
        icon("Назад"); ready(); navigateScale(TimeScale.Day); icon("Текущий период"); ready()
        assertEquals(date, model.state.value.day)
        assertNotNull(repository.client.auth.currentSessionOrNull())
        dayTop(); capture("Day-final")
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
        compose.waitUntil(timeoutMillis = 60_000) {
            ::model.isInitialized && !model.state.value.authLoading && model.state.value.email != null &&
                !model.state.value.busy && !model.state.value.searching
        }
        compose.waitForIdle()
        assertNull("Actual local request failed", model.state.value.error)
    }
    private fun click(label: String) {
        val matcher = hasText(label) and hasClickAction() and isEnabled()
        await(matcher); compose.onNode(matcher).performClick()
    }
    private fun icon(description: String) {
        val matcher = hasContentDescription(description) and hasClickAction() and isEnabled()
        await(matcher); compose.onNode(matcher).performClick(); compose.waitForIdle()
    }
    private fun navigateScale(scale: TimeScale) {
        val matcher = hasText(scale.label) and hasClickAction() and isToggleable()
        await(matcher); compose.onNode(matcher).performClick(); ready()
        assertEquals(scale, model.state.value.scale)
    }
    private fun dayScroll(matcher: SemanticsMatcher) {
        compose.onNodeWithTag("day-diary").performScrollToNode(matcher)
    }
    private fun dayTop() { compose.onNodeWithTag("day-diary").performScrollToIndex(0) }
    private fun expandMeal(label: String) {
        val matcher = hasText(label) and hasClickAction() and !isToggleable()
        dayScroll(matcher)
        compose.onNode(matcher).performClick(); compose.waitForIdle()
    }
    private fun clearNotice() {
        model.state.value.notice?.let { notice -> awaitAbsent(hasText(notice)) }
    }
    private fun hideKeyboard() {
        compose.runOnUiThread {
            (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }
    private fun screenshotDirectory(): File = File(
        requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
        "redesign-verification",
    ).also { check(it.mkdirs() || it.isDirectory) }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // A Compose-idle assertion can precede the corresponding native screenshot frame.
        instrumentation.uiAutomation.waitForIdle(250, 5_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        FileOutputStream(File(screenshotDirectory(), "$name.png")).use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
