package com.kxsxlxv.jetmeal.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class UserErrorsTest {
    @Test fun invalidCredentialsDoNotRevealWhichCredentialWasWrong() {
        val message = auth("invalid_credentials", 400)
        assertTrue(message.contains("email or password"))
        assertFalse(message.contains("account exists"))
        assertFalse(message.contains("code"))
    }

    @Test fun unconfirmedExistingAccountHasActionableGuidance() {
        val message = auth("email_not_confirmed", 400)
        assertTrue(message.contains("not confirmed"))
        assertTrue(message.contains("project owner"))
        assertFalse(message.contains("connection"))
    }

    @Test fun requestLimitDoesNotInventAnExactCooldown() {
        assertEquals("Too many sign-in requests. Wait a little and try again.", auth("over_request_rate_limit", 429))
    }

    @Test fun disabledPasswordSignInAndInvalidSessionAreNotNetworkFailures() {
        assertTrue(auth("email_provider_disabled", 400).contains("Email/password sign-in is disabled"))
        assertTrue(auth("session_expired", 401).contains("Sign in again"))
        assertTrue(auth("refresh_token_already_used", 400).contains("Sign in again"))
    }

    @Test fun wrappedNetworkErrorsAdviseRefreshingBeforeRepeatingPossibleWrite() {
        val error = IllegalStateException("private response", UnknownHostException("private-host"))
        val data = UserErrors.message(error)
        assertTrue(data.contains("refresh your diary"))
        val signIn = UserErrors.message(error, ErrorOperation.SignIn)
        assertTrue(signIn.contains("sign-in service"))
        assertFalse(signIn.contains("diary"))
        assertEquals(data, UserErrors.message(IOException("private-token")))
    }

    @Test fun insecureConnectionHasSeparateGuidance() {
        assertTrue(UserErrors.message(SSLHandshakeException("private certificate payload")).contains("device date"))
    }

    @Test fun unknownServerCodesAndExceptionMessagesCannotReachUi() {
        val secret = "private@example.test password=secret access_token=secret"
        assertFalse(auth(secret, 400).contains(secret))
        assertFalse(UserErrors.message(IllegalArgumentException(secret)).contains(secret))
        assertFalse(UserErrors.message(IllegalStateException(secret)).contains(secret))
        assertTrue(auth(secret, 503).contains("temporarily unavailable"))
    }

    @Test fun onlyKnownLocalValidationTextIsRetained() {
        assertEquals("Enter a valid email address.", UserErrors.message(IllegalArgumentException("Enter a valid email address.")))
        assertEquals("Enter your password.", UserErrors.message(IllegalArgumentException("Enter your password.")))
        assertTrue(UserErrors.message(IllegalArgumentException("Quantity must be finite and positive.")).contains("greater than zero"))
        assertEquals("Check the entered values and try again.", UserErrors.message(IllegalArgumentException("Invalid UUID: private input")))
    }

    private fun auth(code: String, status: Int, operation: ErrorOperation = ErrorOperation.SignIn) =
        UserErrors.authMessage(code, status, operation)
}
