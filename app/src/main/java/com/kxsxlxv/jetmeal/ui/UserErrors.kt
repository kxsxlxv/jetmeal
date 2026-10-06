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
            return "Не удалось установить защищённое соединение. Проверьте интернет и дату на устройстве."
        }
        if (causes.any { it is IOException || it is HttpRequestTimeoutException }) {
            return if (operation == ErrorOperation.Data)
                "Связь прервалась. Проверьте интернет и обновите дневник перед повторной попыткой."
            else "Не удалось подключиться для входа. Проверьте интернет и повторите попытку."
        }
        if (error is IllegalArgumentException) {
            return when (error.message) {
                "Enter a valid email address." -> "Введите корректную электронную почту."
                "Enter your password." -> "Введите пароль."
                "Quantity must be finite and positive." -> "Введите количество больше нуля."
                "Daily calories must be positive." -> "Введите цель калорий больше нуля."
                else -> "Проверьте введённые значения и повторите попытку."
            }
        }
        return "Не удалось выполнить действие. Повторите попытку."
    }

    internal fun authMessage(code: String, status: Int, operation: ErrorOperation): String = when (code) {
        "over_request_rate_limit" -> "Слишком много попыток входа. Немного подождите и попробуйте снова."
        "invalid_credentials" -> "Неверная почта или пароль. Проверьте данные и попробуйте снова."
        "email_not_confirmed" ->
            "Почта аккаунта не подтверждена. Попросите владельца проекта подтвердить аккаунт."
        "email_address_invalid", "validation_failed" -> if (operation == ErrorOperation.SignIn)
            "Введите корректную почту и пароль."
            else "Проверьте введённые значения и повторите попытку."
        "email_provider_disabled" ->
            "Вход по почте и паролю отключён. Попросите владельца проекта включить его."
        "user_banned" -> "Вход в этот аккаунт недоступен. Обратитесь к владельцу проекта."
        "session_not_found", "session_expired", "refresh_token_not_found", "refresh_token_already_used",
        "bad_jwt", "no_authorization" -> SIGN_IN
        "request_timeout" -> "Сервис входа не ответил вовремя. Повторите попытку немного позже."
        else -> responseMessage(status)
    }

    private fun responseMessage(status: Int): String = when (status) {
        401 -> SIGN_IN
        403 -> "У аккаунта нет прав на это действие. Войдите в нужный аккаунт."
        429 -> "Слишком много запросов. Немного подождите и попробуйте снова."
        in 500..599 -> "Сервис временно недоступен. Повторите попытку немного позже."
        else -> "Не удалось выполнить действие. Повторите попытку."
    }

    private const val SIGN_IN = "Сессия завершилась. Войдите снова и повторите действие."
}
