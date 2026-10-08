package com.kxsxlxv.jetmeal.data

import io.github.jan.supabase.SupabaseClientBuilder
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** A received transient Auth response, not a revoked refresh token. Contains no server payload. */
internal class RetryableSessionRefreshException(
    val status: Int,
    val code: String?,
    val requestId: String?,
) : IOException("Temporary session refresh failure (HTTP $status)")

/**
 * supabase-kt 3.8.0 clears storage on any refresh RestException outside its small 5xx list,
 * including 408/429. Keep these responses on its existing retry path. Do not retry writes
 * or suppress actual Auth rejections. Remove this guard when the SDK fixes that policy.
 */
@OptIn(SupabaseInternal::class)
internal fun SupabaseClientBuilder.protectSessionRefresh(onFailure: (ConnectionFailure) -> Unit = {}) {
    httpConfig {
        HttpResponseValidator {
            validateResponse { response ->
                val url = response.request.url
                if (!url.encodedPath.endsWith("/auth/v1/token") ||
                    url.parameters["grant_type"] != "refresh_token" || response.status.value < 400) return@validateResponse
                val code = runCatching {
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    // Auth may include numeric HTTP "code" alongside string "error_code".
                    listOf("error_code", "code", "error").firstNotNullOfOrNull { name ->
                        body[name]?.jsonPrimitive?.contentOrNull?.let(AuthErrorCode::fromValue)?.value
                    }
                }.getOrNull()
                currentCoroutineContext().ensureActive()
                val failure = ConnectionFailure.fromResponse(response.status.value, code, response.headers["sb-request-id"])
                onFailure(failure)
                if (SessionRefreshPolicy.isRetryable(response.status.value, code)) {
                    throw RetryableSessionRefreshException(response.status.value, code,
                        ConnectionFailure.safeRequestId(response.headers["sb-request-id"]))
                }
            }
            handleResponseExceptionWithRequest { error, request ->
                currentCoroutineContext().ensureActive()
                if (request.url.encodedPath.endsWith("/auth/v1/token") &&
                    request.url.parameters["grant_type"] == "refresh_token") {
                    onFailure(ConnectionFailure.from(error))
                }
            }
        }
    }
}

internal object SessionRefreshPolicy {
    fun isRetryable(status: Int, code: String?): Boolean = status in setOf(408, 425, 429) ||
        status in 500..599 || code in setOf("request_timeout", "over_request_rate_limit")
}
