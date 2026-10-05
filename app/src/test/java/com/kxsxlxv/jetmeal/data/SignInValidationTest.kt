package com.kxsxlxv.jetmeal.data

import org.junit.Assert.*
import org.junit.Test

class SignInValidationTest {
    @Test fun blankAndMalformedCredentialsCannotBeSubmitted() {
        listOf("" to "password", "not-an-email" to "password", "user@example.test" to "",
            "user@example.test" to " \t\n ").forEach { (email, password) ->
            assertFalse(SignInValidation.canSubmit(email, password))
            assertTrue(runCatching { SignInValidation.normalizedEmail(email, password) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun emailSurroundingWhitespaceIsNormalizedAndPasswordWhitespaceIsAccepted() {
        assertTrue(SignInValidation.canSubmit(" user@example.test ", " password with spaces "))
        assertEquals("user@example.test", SignInValidation.normalizedEmail(" user@example.test ", " password with spaces "))
    }
}
