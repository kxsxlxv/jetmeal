package com.kxsxlxv.jetmeal.data

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ConnectionFailureTest {
    @Test fun transportKindsDoNotClaimAnAuthRejection() {
        assertEquals(ConnectionFailureKind.Dns, ConnectionFailure.from(UnknownHostException("private-host")).kind)
        assertEquals(ConnectionFailureKind.Tls, ConnectionFailure.from(SSLHandshakeException("private-cert")).kind)
        assertEquals(ConnectionFailureKind.Timeout, ConnectionFailure.from(SocketTimeoutException("private-response")).kind)
    }

    @Test fun transientResponseCarriesSafeStatusAndRequestReference() {
        val id = "01234567-89ab-cdef-0123-456789abcdef"
        val rate = ConnectionFailure.from(RetryableSessionRefreshException(429, "over_request_rate_limit", id))
        assertEquals(ConnectionFailureKind.RateLimit, rate.kind)
        assertEquals(429, rate.status)
        assertEquals(id, rate.requestId)
        assertEquals(ConnectionFailureKind.Service, ConnectionFailure.fromResponse(503, "unexpected_failure", null).kind)
        assertEquals(ConnectionFailureKind.AuthRejected,
            ConnectionFailure.fromResponse(400, "refresh_token_already_used", id).kind)
    }

    @Test fun unknownServerPayloadCannotBecomeDiagnosticFields() {
        val private = "private@example.test access_token=secret password=secret"
        val record = ConnectionFailure.fromResponse(400, private, private)
        assertNull(record.code)
        assertNull(record.requestId)
        assertFalse(record.toString().contains(private))
    }

    @Test fun rejectionAndTransientPoliciesAreSeparate() {
        assertTrue(SessionRefreshPolicy.isRetryable(429, null))
        assertTrue(SessionRefreshPolicy.isRetryable(408, null))
        assertTrue(SessionRefreshPolicy.isRetryable(400, "request_timeout"))
        assertFalse(SessionRefreshPolicy.isRetryable(400, "refresh_token_already_used"))
        assertFalse(SessionRefreshPolicy.isRetryable(401, "session_not_found"))
        assertFalse(SessionRefreshPolicy.isRetryable(403, "user_banned"))
    }
}
