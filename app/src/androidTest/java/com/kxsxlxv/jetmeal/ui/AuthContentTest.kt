package com.kxsxlxv.jetmeal.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kxsxlxv.jetmeal.ui.theme.JetmealTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Component tests verify input behavior; real Auth is covered separately by JetMealEndToEndTest. */
@RunWith(AndroidJUnit4::class)
class AuthContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun typingDoesNotSignInAndExplicitSubmitPreservesPasswordWhitespace() {
        var submitted: Pair<String, String>? = null
        compose.setContent { JetmealTheme { AuthContent(AppState(authLoading = false)) { email, password -> submitted = email to password } } }
        compose.onNodeWithText("Войти").assertIsNotEnabled()
        compose.onNodeWithText("Электронная почта").performTextReplacement(" user@example.test ")
        compose.onNodeWithText("Пароль").performTextReplacement("   ")
        compose.onNodeWithText("Войти").assertIsNotEnabled()
        compose.onNodeWithText("Пароль").performTextReplacement(" password with spaces ")
        compose.onNodeWithText("Войти").assertIsEnabled()
        compose.runOnIdle { assertNull(submitted) }
        compose.onNodeWithText("Войти").performClick()
        compose.runOnIdle {
            assertEquals("user@example.test", submitted?.first)
            assertEquals(" password with spaces ", submitted?.second)
        }
    }

    @Test fun busyStateDisablesButtonAndPasswordInput() {
        val state = mutableStateOf(AppState(authLoading = false))
        var submissions = 0
        compose.setContent { JetmealTheme { AuthContent(state.value) { _, _ -> submissions++ } } }
        compose.onNodeWithText("Электронная почта").performTextReplacement("user@example.test")
        compose.onNodeWithText("Пароль").performTextReplacement("password")
        compose.runOnIdle { state.value = state.value.copy(busy = true) }
        compose.onNodeWithText("Войти").assertIsNotEnabled()
        compose.onNodeWithText("Пароль").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, submissions) }
    }
}
