package com.kxsxlxv.jetmeal.ui

import com.kxsxlxv.jetmeal.data.ConnectionFailure
import com.kxsxlxv.jetmeal.data.ConnectionFailureKind
import com.kxsxlxv.jetmeal.data.RetryableSessionRefreshException
import com.kxsxlxv.jetmeal.data.SessionRefreshPolicy
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
        causes.filterIsInstance<RetryableSessionRefreshException>().firstOrNull()?.let {
            return sessionRecoveryMessage(it)
        }
        causes.filterIsInstance<AuthRestException>().firstOrNull()?.let {
            return authMessage(it.errorCode?.value ?: it.error, it.statusCode, operation)
        }
        if (causes.any { it is SessionRequiredException }) return SIGN_IN
        if (causes.any { it is TokenExpiredException }) return "Срок токена доступа истёк. Подождите обновления сессии и повторите действие."
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

    const val SESSION_RECOVERING = "Восстанавливаем связь с сервисом. Сессия сохранена; повторяем автоматически."

    fun sessionRecoveryMessage(error: Throwable): String = sessionRecoveryMessage(ConnectionFailure.from(error))

    fun sessionRecoveryMessage(issue: ConnectionFailure): String {
        val reason = when (issue.kind) {
            ConnectionFailureKind.Dns -> "Не удалось найти адрес сервиса (DNS)."
            ConnectionFailureKind.Tls -> "Не удалось установить защищённое соединение (TLS)."
            ConnectionFailureKind.Timeout -> "Сервис не ответил вовремя."
            ConnectionFailureKind.RateLimit -> "Сервис временно ограничил запросы (HTTP 429)."
            ConnectionFailureKind.Service -> "Сервис временно недоступен (HTTP ${issue.status})."
            ConnectionFailureKind.Transport -> "Соединение с сервисом прервалось."
            else -> "Не удалось восстановить связь с сервисом."
        }
        return "$reason Сессия сохранена; повторяем автоматически."
    }

    fun sessionEndedMessage(issue: ConnectionFailure): String? {
        if (issue.status == null || SessionRefreshPolicy.isRetryable(issue.status, issue.code)) return null
        val detail = issue.code ?: "HTTP ${issue.status}"
        return "Сервис отклонил обновление сессии ($detail). Войдите снова."
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
        "session_not_found", "session_expired", "refresh_token_not_found", "refresh_token_already_used" -> SIGN_IN
        "bad_jwt", "no_authorization" -> "Сервис не подтвердил токен доступа. Обновите данные; если ошибка повторится, войдите снова."
        "request_timeout" -> "Сервис входа не ответил вовремя. Повторите попытку немного позже."
        else -> responseMessage(status)
    }

    private fun responseMessage(status: Int): String = when (status) {
        401 -> "Сервис не подтвердил доступ (HTTP 401). Обновите данные и повторите попытку."
        403 -> "У аккаунта нет прав на это действие. Войдите в нужный аккаунт."
        429 -> "Слишком много запросов. Немного подождите и попробуйте снова."
        in 500..599 -> "Сервис временно недоступен. Повторите попытку немного позже."
        else -> "Не удалось выполнить действие. Повторите попытку."
    }

    private const val SIGN_IN = "Сессия завершилась. Войдите снова и повторите действие."
}
