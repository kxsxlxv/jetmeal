package com.kxsxlxv.jetmeal.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.domain.Targets
import com.kxsxlxv.jetmeal.ui.theme.JetmealTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Presentation fixtures only: real Auth/RLS/diary paths remain separate integration tests. */
@RunWith(AndroidJUnit4::class)
class EditorContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun quantityPreviewUsesImmutableBasisAndNeedsExplicitConfirmation() {
        var saved: Double? = null
        compose.setContent {
            JetmealTheme {
                AmountContent("Блюдо", "g", 125.0, 300.0, Nutrition(600.0, 30.0, 12.0, 90.0),
                    false, false, null, { saved = it }, null, {}, null)
            }
        }
        compose.onNodeWithText("250 ккал").assertIsDisplayed()
        compose.onNodeWithText("Количество (г)").performTextReplacement("100")
        compose.onNodeWithText("200 ккал").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Добавить еду").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(100.0, saved!!, 0.0) }
    }

    @Test fun amountSheetClosesOnlyAfterRealSuccessNotice() {
        val busy = mutableStateOf(false)
        val notice = mutableStateOf<String?>(null)
        var closed = false
        compose.setContent {
            JetmealTheme {
                AmountContent("Блюдо", "g", 100.0, 100.0, Nutrition(100.0, 10.0, 1.0, 10.0),
                    false, busy.value, null, { busy.value = true }, null, { closed = true }, null, notice.value)
            }
        }
        compose.onNodeWithText("Добавить еду").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(closed); busy.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(closed); notice.value = "Еда добавлена. Можно отменить." }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun offlineAcknowledgementClosesEditorAfterDurableLocalWrite() {
        val busy=mutableStateOf(false)
        val notice=mutableStateOf<String?>(null)
        var closed=false
        compose.setContent {
            JetmealTheme {
                AmountContent("Гречневая каша","g",100.0,100.0,
                    Nutrition(112.0,4.0,1.0,23.0),false,busy.value,null,
                    { busy.value=true },null,{ closed=true },null,notice.value)
            }
        }
        compose.onNodeWithText("Добавить еду").performScrollTo().performClick()
        compose.runOnIdle {
            busy.value=false
            notice.value="Сохранено на телефоне: 1 действий ожидают синхронизации."
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Editor should close after local durable commit",closed) }
    }

    @Test fun targetEditsRequireSeparateReviewAndConfirmation() {
        var saved: Targets? = null
        compose.setContent {
            JetmealTheme { SettingsContent(Targets(2000.0, 100.0, 60.0, 250.0), "test@example.test", false,
                { saved = it }, {}) }
        }
        compose.onNodeWithText("Базовая цель (ккал)").performTextReplacement("2100")
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Проверить изменения").performScrollTo().performClick()
        compose.onNodeWithText("Сохранить новые цели?").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Сохранить цели").performClick()
        compose.runOnIdle {
            val confirmed = requireNotNull(saved)
            assertEquals(2100.0, confirmed.calories, 0.0)
            assertEquals(0.10, confirmed.limitRatio, 0.0)
        }
    }
}
