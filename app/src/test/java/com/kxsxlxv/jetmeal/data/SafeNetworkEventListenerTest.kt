package com.kxsxlxv.jetmeal.data

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.*
import org.junit.Test

class SafeNetworkEventListenerTest {
    @Test fun classifiesLowLevelNetworkFailuresBeforeSupabaseRemovesCause() {
        assertEquals(NetworkCause.UnknownHost,
            networkCause(IOException("opaque wrapper",UnknownHostException("secret.example"))))
        assertEquals(NetworkCause.ConnectionRefused,
            networkCause(ConnectException("private IP leaked in message")))
        assertEquals(NetworkCause.NoRoute,
            networkCause(NoRouteToHostException("private router")))
        assertEquals(NetworkCause.TlsCertificate,
            networkCause(SSLPeerUnverifiedException("certificate for private host")))
        assertEquals(NetworkCause.TlsHandshake,
            networkCause(SSLHandshakeException("secret hostname")))
        assertEquals(NetworkCause.Timeout,networkCause(SocketTimeoutException("url")))
        assertEquals(NetworkCause.PrematureClose,networkCause(EOFException("url")))
        assertEquals(NetworkCause.Io,networkCause(IOException("url with token")))
    }

    @Test fun onlyAllowlistedRoutesAppearEvenWhenPathIncludesSensitiveTokens() {
        assertEquals(NetworkRoute.Auth,routeForPath("/auth/v1/token"))
        assertEquals(NetworkRoute.Catalogue,routeForPath("/rest/v1/food_variants"))
        assertEquals(NetworkRoute.Diary,routeForPath("/rest/v1/diary_entries"))
        assertEquals(NetworkRoute.Mutation,routeForPath("/rest/v1/rpc/jetmeal_log_food"))
        assertEquals(NetworkRoute.Other,routeForPath("/secret/example/private-user-name"))
    }

    @Test fun reportNeverContainsHostHeadersQueryPersonalDetailsOrRawExceptionMessage() {
        val secret = "my-private-token"
        val trace = NetworkTrace(NetworkRoute.Auth, NetworkPhase.Tls, 1700,
            NetworkCause.TlsHandshake,dnsMs=32,tcpMs=510,tlsMs=1141,
            requestId="https://private.example?authorization=$secret",
            connectionReused=false)
        val formatted = trace.safeLine()
        assertTrue(formatted.contains("route=Auth"))
        assertTrue(formatted.contains("phase=Tls"))
        assertTrue(formatted.contains("cause=TlsHandshake"))
        assertTrue(formatted.contains("tlsMs=1141"))
        assertFalse(formatted.contains(secret))
        assertFalse(formatted.contains("private.example"))
        assertFalse(formatted.contains("request="))
    }

    @Test fun httpErrorsAndValidatedRequestIdsAreDiagnosticButNeverResponseBodies() {
        val uuid="2c2c2c2c-3333-4444-8888-999999999999"
        val formatted = NetworkTrace(NetworkRoute.Diary, NetworkPhase.Response,450,
            status=503,requestId=uuid,connectionReused=true).safeLine()
        assertTrue(formatted.contains("result=http_error"))
        assertTrue(formatted.contains("http=503"))
        assertTrue(formatted.contains("request=$uuid"))
        assertTrue(formatted.contains("reused=true"))
    }

    @Test fun noHttpStatusAndEarlyDnsFailureAreExplicit() {
        val formatted=NetworkTrace(NetworkRoute.Catalogue,NetworkPhase.Dns,180,
            NetworkCause.UnknownHost,dnsMs=180).safeLine()
        assertTrue(formatted.contains("result=failed"))
        assertTrue(formatted.contains("dnsMs=180"))
        assertFalse(formatted.contains("http="))
        assertFalse(formatted.contains("tcpMs"))
    }

    @Test fun aRecoveredCallHasExplicitStatusAndNoCause() {
        val formatted=NetworkTrace(NetworkRoute.Diary,NetworkPhase.Response,220,
            status=200).safeLine()
        assertTrue(formatted.contains("result=recovered"))
        assertTrue(formatted.contains("http=200"))
        assertFalse(formatted.contains("cause="))
    }
}
