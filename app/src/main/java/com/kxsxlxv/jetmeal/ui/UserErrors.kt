package com.kxsxlxv.jetmeal.ui

import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.SessionRequiredException
import io.github.jan.supabase.auth.exception.TokenExpiredException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import javax.net.ssl.SSLException

internal enum class ErrorOperation { Data, SignIn }

/** Only known codes and local validation messages become UI text; server payloads stay private. */
internal object UserErrors {
    fun message(error: Throwable, operation: ErrorOperation = ErrorOperation.Data): String {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        causes.filterIsInstance<AuthRestException>().firstOrNull()?.let {
            return authMessage(it.errorCode?.value ?: it.error, it.statusCode, operation)
        }
        if (causes.any { it is SessionRequiredException || it is TokenExpiredException }) return SIGN_IN
        causes.filterIsInstance<RestException>().firstOrNull()?.let { return responseMessage(it.statusCode) }
        if (causes.any { it is SSLException }) {
            return "A secure connection could not be established. Check your connection and device date, then try again."
        }
        if (causes.any { it is IOException || it is HttpRequestTimeoutException }) {
            return if (operation == ErrorOperation.Data)
                "Connection interrupted. Check your connection and refresh your diary before trying again."
            else "Could not connect to the sign-in service. Check your connection and try again."
        }
        if (error is IllegalArgumentException) {
            return when (error.message) {
                "Enter a valid email address." -> "Enter a valid email address."
                "Enter your password." -> "Enter your password."
                "Quantity must be finite and positive." -> "Enter a quantity greater than zero."
                "Daily calories must be positive." -> "Enter a daily calorie target greater than zero."
                else -> "Check the entered values and try again."
            }
        }
        return "The request could not be completed. Try again."
    }

    internal fun authMessage(code: String, status: Int, operation: ErrorOperation): String = when (code) {
        "over_request_rate_limit" -> "Too many sign-in requests. Wait a little and try again."
        "invalid_credentials" -> "The email or password was not accepted. Check both and try again."
        "email_not_confirmed" ->
            "This account's email is not confirmed. Ask the project owner to confirm the existing account."
        "email_address_invalid", "validation_failed" -> if (operation == ErrorOperation.SignIn)
            "Enter a valid email address and password."
            else "Check the entered values and try again."
        "email_provider_disabled" ->
            "Email/password sign-in is disabled for this project. Ask the project owner to enable it."
        "user_banned" -> "This account cannot sign in. Contact the project owner."
        "session_not_found", "session_expired", "refresh_token_not_found", "refresh_token_already_used",
        "bad_jwt", "no_authorization" -> SIGN_IN
        "request_timeout" -> "The sign-in service took too long to respond. Try again shortly."
        else -> responseMessage(status)
    }

    private fun responseMessage(status: Int): String = when (status) {
        401 -> SIGN_IN
        403 -> "Your account does not have permission for this action. Sign in with the correct account."
        429 -> "Too many requests. Wait a little and try again."
        in 500..599 -> "The service is temporarily unavailable. Try again shortly."
        else -> "The request could not be completed. Try again."
    }

    private const val SIGN_IN = "Your sign-in session is no longer valid. Sign in again and retry."
}
