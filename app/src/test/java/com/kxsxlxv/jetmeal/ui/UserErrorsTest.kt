package com.kxsxlxv.jetmeal.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class UserErrorsTest {
    @Test fun invalidCredentialsDoNotRevealWhichCredentialWasWrong() {
        val message = auth("invalid_credentials", 400)
        assertTrue(message.contains("почта или пароль"))
        assertFalse(message.contains("аккаунт существует"))
        assertFalse(message.contains("код"))
    }

    @Test fun unconfirmedExistingAccountHasActionableGuidance() {
        val message = auth("email_not_confirmed", 400)
        assertTrue(message.contains("не подтверждена"))
        assertTrue(message.contains("владельца проекта"))
        assertFalse(message.contains("интернет"))
    }

    @Test fun requestLimitDoesNotInventAnExactCooldown() {
        assertEquals("Слишком много попыток входа. Немного подождите и попробуйте снова.", auth("over_request_rate_limit", 429))
    }

    @Test fun disabledPasswordSignInAndInvalidSessionAreNotNetworkFailures() {
        assertTrue(auth("email_provider_disabled", 400).contains("Вход по почте и паролю отключён"))
        assertTrue(auth("session_expired", 401).contains("Войдите снова"))
        assertTrue(auth("refresh_token_already_used", 400).contains("Войдите снова"))
    }

    @Test fun wrappedNetworkErrorsAdviseRefreshingBeforeRepeatingPossibleWrite() {
        val error = IllegalStateException("private response", UnknownHostException("private-host"))
        val data = UserErrors.message(error)
        assertTrue(data.contains("обновите дневник"))
        val signIn = UserErrors.message(error, ErrorOperation.SignIn)
        assertTrue(signIn.contains("подключиться для входа"))
        assertFalse(signIn.contains("дневник"))
        assertEquals(data, UserErrors.message(IOException("private-token")))
    }

    @Test fun insecureConnectionHasSeparateGuidance() {
        assertTrue(UserErrors.message(SSLHandshakeException("private certificate payload")).contains("дату на устройстве"))
    }

    @Test fun unknownServerCodesAndExceptionMessagesCannotReachUi() {
        val secret = "private@example.test password=secret access_token=secret"
        assertFalse(auth(secret, 400).contains(secret))
        assertFalse(UserErrors.message(IllegalArgumentException(secret)).contains(secret))
        assertFalse(UserErrors.message(IllegalStateException(secret)).contains(secret))
        assertTrue(auth(secret, 503).contains("временно недоступен"))
    }

    @Test fun onlyKnownLocalValidationTextIsRetained() {
        assertEquals("Введите корректную электронную почту.", UserErrors.message(IllegalArgumentException("Enter a valid email address.")))
        assertEquals("Введите пароль.", UserErrors.message(IllegalArgumentException("Enter your password.")))
        assertTrue(UserErrors.message(IllegalArgumentException("Quantity must be finite and positive.")).contains("больше нуля"))
        assertEquals("Проверьте введённые значения и повторите попытку.", UserErrors.message(IllegalArgumentException("Invalid UUID: private input")))
    }

    private fun auth(code: String, status: Int, operation: ErrorOperation = ErrorOperation.SignIn) =
        UserErrors.authMessage(code, status, operation)
}
