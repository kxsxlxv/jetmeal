package com.kxsxlxv.jetmeal.data

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Low-level request telemetry before supabase-kt drops the IOException cause.
 * Strictly allowlisted: no exception messages, host, port, DNS answers, IPs,
 * URLs, query parameters, headers (other than validated sb-request-id), bodies,
 * cookies, credentials or tokens, even in debug builds.
 */
internal enum class NetworkRoute { Auth, Diary, Catalogue, Mutation, Other }
internal enum class NetworkPhase { Queued, Dns, Tcp, Tls, Request, Waiting, Response }
internal enum class NetworkCause {
    UnknownHost, ConnectionRefused, NoRoute, TlsHandshake, TlsCertificate,
    Tls, Timeout, PrematureClose, Protocol, Io, Unknown
}

internal fun routeForPath(path: String): NetworkRoute = when {
    path.startsWith("/auth/v1/") -> NetworkRoute.Auth
    path == "/rest/v1/diary_entries" || path == "/rest/v1/zero_calorie_days" ||
        path == "/rest/v1/nutrition_targets" || path == "/rest/v1/nutrition_target_history" ->
        NetworkRoute.Diary
    path == "/rest/v1/food_variants" || path == "/rest/v1/foods" ||
        path == "/rest/v1/rpc/jetmeal_search_catalog" -> NetworkRoute.Catalogue
    path.startsWith("/rest/v1/rpc/") -> NetworkRoute.Mutation
    else -> NetworkRoute.Other
}

internal fun networkCause(error: Throwable): NetworkCause {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        chain.any { it is UnknownHostException } -> NetworkCause.UnknownHost
        chain.any { it is SSLPeerUnverifiedException } -> NetworkCause.TlsCertificate
        chain.any { it is SSLHandshakeException } -> NetworkCause.TlsHandshake
        chain.any { it is SSLException } -> NetworkCause.Tls
        chain.any { it is SocketTimeoutException || it is InterruptedIOException } -> NetworkCause.Timeout
        chain.any { it is ConnectException } -> NetworkCause.ConnectionRefused
        chain.any { it is NoRouteToHostException } -> NetworkCause.NoRoute
        chain.any { it is EOFException } -> NetworkCause.PrematureClose
        chain.any { it is ProtocolException } -> NetworkCause.Protocol
        chain.any { it is IOException } -> NetworkCause.Io
        else -> NetworkCause.Unknown
    }
}

internal data class NetworkTrace(
    val route: NetworkRoute,
    val phase: NetworkPhase,
    val elapsedMs: Long,
    val cause: NetworkCause? = null,
    val status: Int? = null,
    val dnsMs: Long? = null,
    val tcpMs: Long? = null,
    val tlsMs: Long? = null,
    val requestId: String? = null,
    val connectionReused: Boolean = false,
) {
    /** Allowlisted fields only. No free-form exception text is accepted. */
    fun safeLine(): String = buildString {
        append("layer=OkHttp route=").append(route)
        append(" phase=").append(phase)
        append(" result=").append(when {
            cause != null -> "failed"
            status != null && status >= 400 -> "http_error"
            else -> "recovered"
        })
        cause?.let { append(" cause=").append(it) }
        status?.let { append(" http=").append(it) }
        append(" elapsedMs=").append(elapsedMs.coerceAtLeast(0))
        dnsMs?.let { append(" dnsMs=").append(it.coerceAtLeast(0)) }
        tcpMs?.let { append(" tcpMs=").append(it.coerceAtLeast(0)) }
        tlsMs?.let { append(" tlsMs=").append(it.coerceAtLeast(0)) }
        if (connectionReused) append(" reused=true")
        ConnectionFailure.safeRequestId(requestId)?.let { append(" request=").append(it) }
    }
}

/**
 * Each OkHttp call gets an independent listener. Event timing is monotonic;
 * TCP/TLS callbacks can be skipped for a pooled connection, not missing data.
 * A failed route attempt may be retried by OkHttp before the final callFailed.
 */
internal class SafeNetworkEventListener(
    private val diagnostics: ConnectionDiagnostics,
    private val route: NetworkRoute,
    private val nanoTime: () -> Long = System::nanoTime,
) : EventListener() {
    private val startedAt = nanoTime()
    private var phase = NetworkPhase.Queued
    private var dnsStart: Long? = null
    private var tcpStart: Long? = null
    private var tlsStart: Long? = null
    private var dnsMs: Long? = null
    private var tcpMs: Long? = null
    private var tlsMs: Long? = null
    private var status: Int? = null
    private var requestId: String? = null
    private var reused = false
    private var connected = false

    private fun duration(start: Long?): Long? = start?.let { ((nanoTime() - it) / 1_000_000L).coerceAtLeast(0L) }

    @Synchronized override fun dnsStart(call: Call, domainName: String) {
        phase = NetworkPhase.Dns
        dnsStart = nanoTime()
    }
    @Synchronized override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsMs = duration(dnsStart)
    }
    @Synchronized override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        phase = NetworkPhase.Tcp
        tcpStart = nanoTime()
    }
    @Synchronized override fun secureConnectStart(call: Call) {
        phase = NetworkPhase.Tls
        tlsStart = nanoTime()
    }
    @Synchronized override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsMs = duration(tlsStart)
    }
    @Synchronized override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress,
                                           proxy: Proxy, protocol: Protocol?) {
        tcpMs = duration(tcpStart)
        connected = true
    }
    @Synchronized override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress,
                                              proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        if (phase == NetworkPhase.Tls) tlsMs = duration(tlsStart)
        else tcpMs = duration(tcpStart)
    }
    @Synchronized override fun connectionAcquired(call: Call, connection: okhttp3.Connection) {
        reused = !connected
    }
    @Synchronized override fun requestHeadersStart(call: Call) { phase = NetworkPhase.Request }
    @Synchronized override fun requestBodyStart(call: Call) { phase = NetworkPhase.Request }
    @Synchronized override fun responseHeadersStart(call: Call) { phase = NetworkPhase.Waiting }
    @Synchronized override fun responseHeadersEnd(call: Call, response: Response) {
        phase = NetworkPhase.Response
        status = response.code
        requestId = ConnectionFailure.safeRequestId(response.header("sb-request-id"))
    }
    @Synchronized override fun responseBodyStart(call: Call) { phase = NetworkPhase.Response }

    @Synchronized override fun callFailed(call: Call, ioe: IOException) {
        if (phase == NetworkPhase.Dns) dnsMs = duration(dnsStart)
        if (phase == NetworkPhase.Tcp) tcpMs = duration(tcpStart)
        if (phase == NetworkPhase.Tls) tlsMs = duration(tlsStart)
        runCatching { diagnostics.networkTrace(snapshot(networkCause(ioe))) }
        // Telemetry must never change the outcome of the user's original request.
    }

    @Synchronized override fun callEnd(call: Call) {
        runCatching { diagnostics.networkTrace(snapshot(null)) }
    }

    private fun snapshot(cause: NetworkCause?): NetworkTrace = NetworkTrace(
        route = route,
        phase = phase,
        elapsedMs = duration(startedAt) ?: 0L,
        cause = cause,
        status = status,
        dnsMs = dnsMs,
        tcpMs = tcpMs,
        tlsMs = tlsMs,
        requestId = requestId,
        connectionReused = reused,
    )
}

/** Factory does not inspect request headers, query strings or body. */
internal class SafeNetworkListenerFactory(
    private val diagnostics: ConnectionDiagnostics,
) : EventListener.Factory {
    override fun create(call: Call): EventListener =
        SafeNetworkEventListener(diagnostics, routeForPath(call.request().url.encodedPath))
}
