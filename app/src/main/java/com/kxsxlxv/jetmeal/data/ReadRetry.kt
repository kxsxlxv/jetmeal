package com.kxsxlxv.jetmeal.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * One bounded retry for idempotent Supabase SELECT requests only. Mutations and Auth
 * token refresh must never pass through this helper: a lost response can follow a
 * successful server-side write or rotation.
 */
internal suspend fun <T> retryTransientRead(
    stage: ConnectionStage,
    backoffMillis: Long = 350L,
    onRetry: (stage: ConnectionStage, attempt: Int, kind: ConnectionFailureKind) -> Unit = { _, _, _ -> },
    block: suspend () -> T,
): T {
    require(backoffMillis >= 0)
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val kind = ConnectionFailure.from(error).kind
            // Unknown or permanent HTTP/serialization/auth errors must fail immediately.
            if (attempt >= 2 || kind !in setOf(
                    ConnectionFailureKind.Dns,
                    ConnectionFailureKind.Timeout,
                    ConnectionFailureKind.Transport,
                    ConnectionFailureKind.RateLimit,
                    ConnectionFailureKind.Service,
                )) throw error
            attempt++
            onRetry(stage, attempt, kind)
            if (backoffMillis > 0) delay(backoffMillis)
        }
    }
}
