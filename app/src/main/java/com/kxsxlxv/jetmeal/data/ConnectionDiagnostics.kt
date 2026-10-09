package com.kxsxlxv.jetmeal.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.logging.SupabaseLoggingProcessor
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLException

internal enum class ConnectionOperation { Auth, SignIn, Diary, Catalogue, Mutation, Widget }
internal enum class ConnectionAuthState { Authenticated, NotAuthenticated, Recovering, SignOutRequested }
internal enum class ConnectionFailureKind { Dns, Tls, Timeout, Transport, RateLimit, Service, AuthRejected, Permission, Unknown }

/** Allowlisted, non-personal metadata only. Never retain Throwable/message, URL, body or tokens. */
internal data class ConnectionFailure(
    val kind: ConnectionFailureKind,
    val status: Int? = null,
    val code: String? = null,
    val requestId: String? = null,
) {
    companion object {
        fun from(error: Throwable): ConnectionFailure {
            val causes = generateSequence(error) { it.cause }.take(12).toList()
            causes.filterIsInstance<RetryableSessionRefreshException>().firstOrNull()?.let {
                return fromResponse(it.status, it.code, it.requestId)
            }
            causes.filterIsInstance<RestException>().firstOrNull()?.let {
                val code = (it as? AuthRestException)?.errorCode?.value ?: AuthErrorCode.fromValue(it.error)?.value
                return fromResponse(it.statusCode, code, it.response.headers["sb-request-id"])
            }
            val kind = when {
                causes.any { it is UnknownHostException } -> ConnectionFailureKind.Dns
                causes.any { it is SSLException } -> ConnectionFailureKind.Tls
                causes.any { it is HttpRequestTimeoutException || it is SocketTimeoutException } -> ConnectionFailureKind.Timeout
                causes.any { it is IOException || it is HttpRequestException } -> ConnectionFailureKind.Transport
                else -> ConnectionFailureKind.Unknown
            }
            return ConnectionFailure(kind)
        }

        fun fromResponse(status: Int, code: String?, requestId: String?): ConnectionFailure {
            val safeCode = code?.let(AuthErrorCode::fromValue)?.value
            val kind = when {
                status == 429 || safeCode == "over_request_rate_limit" -> ConnectionFailureKind.RateLimit
                status == 408 || safeCode == "request_timeout" -> ConnectionFailureKind.Timeout
                status >= 500 -> ConnectionFailureKind.Service
                status == 403 -> ConnectionFailureKind.Permission
                status == 401 || safeCode in setOf("session_not_found", "session_expired", "refresh_token_not_found",
                    "refresh_token_already_used", "bad_jwt", "no_authorization", "invalid_credentials", "user_banned") -> ConnectionFailureKind.AuthRejected
                else -> ConnectionFailureKind.Unknown
            }
            return ConnectionFailure(kind, status, safeCode, safeRequestId(requestId))
        }

        fun safeRequestId(value: String?): String? = value?.takeIf {
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(it)
        }
    }
}

/**
 * KtorSupabaseHttpClient 3.8.0 replaces transport exceptions with HttpRequestException
 * without retaining their cause. Capture safe metadata at Ktor's boundary first, and
 * never replace that record with its less informative SDK wrapper.
 */
internal class AuthFailureCapture {
    @Volatile var latest: ConnectionFailure? = null
        private set

    fun capture(failure: ConnectionFailure) { latest = failure }
    fun clear() { latest = null }
    fun resolve(error: Throwable): ConnectionFailure {
        val wrapped = generateSequence(error) { it.cause }.take(12)
            .any { it is HttpRequestException && it.cause == null }
        return if (wrapped) latest ?: ConnectionFailure.from(error) else ConnectionFailure.from(error)
    }
}

/** Small private history survives a process restart and can be correlated with hosted Auth logs. */
internal class ConnectionDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("jetmeal_connection_diagnostics", Context.MODE_PRIVATE)
    val authFailures = AuthFailureCapture()

    fun recent(): String = preferences.getString("events", "").orEmpty()

    fun failure(operation: ConnectionOperation, error: Throwable) {
        val failure = if (operation == ConnectionOperation.Auth) authFailures.resolve(error) else ConnectionFailure.from(error)
        if (operation == ConnectionOperation.Auth) authFailures.capture(failure)
        record("operation=$operation kind=${failure.kind} http=${failure.status ?: "none"}" +
            " code=${failure.code ?: "none"} request=${failure.requestId ?: "none"}" +
            " exception=${error.javaClass.simpleName.takeIf { Regex("[A-Za-z0-9_]{1,64}").matches(it) } ?: "unknown"}")
    }

    fun captureAuthFailure(failure: ConnectionFailure) {
        authFailures.capture(failure)
        record("operation=Auth kind=${failure.kind} http=${failure.status ?: "none"}" +
            " code=${failure.code ?: "none"} request=${failure.requestId ?: "none"}")
    }

    fun authState(state: ConnectionAuthState) {
        // Never log SessionStatus.toString() (contains JWT).
        if (state == ConnectionAuthState.Authenticated || state == ConnectionAuthState.SignOutRequested) authFailures.clear()
        record("auth=$state")
    }

    @Synchronized private fun record(details: String) {
        val line = "${Instant.now()} $details network=${network()}"
        val recent = preferences.getString("events", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        preferences.edit().putString("events", (recent.takeLast(59) + line).joinToString("\n")).apply()
        Log.i("JetMealConnection", line)
    }

    private fun network(): String = runCatching {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching "none"
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        "$transport:${if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "unvalidated"}"
    }.getOrDefault("unknown")
}

/** The SDK's error Throwable embeds request headers/URLs. Record only the metadata above. */
internal class SafeSupabaseLogger(private val diagnostics: ConnectionDiagnostics) : SupabaseLoggingProcessor {
    override fun isEnabled(level: LogLevel): Boolean = level == LogLevel.ERROR
    override fun processLog(level: LogLevel, tag: String, throwable: Throwable?, message: String) {
        if (tag == Auth.LOGGING_TAG) throwable?.let { diagnostics.failure(ConnectionOperation.Auth, it) }
    }
}
