package com.kxsxlxv.jetmeal.data

import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReadRetryTest {
    @Test fun retriesTimeoutExactlyOnceThenSucceeds() = runBlocking {
        var calls = 0
        val retries = mutableListOf<Triple<ConnectionStage, Int, ConnectionFailureKind>>()
        val value = retryTransientRead(ConnectionStage.Entries, backoffMillis = 0,
            onRetry = { stage, attempt, kind -> retries += Triple(stage, attempt, kind) }) {
            calls++
            if (calls == 1) throw SocketTimeoutException("private endpoint with token")
            "loaded"
        }
        assertEquals("loaded", value)
        assertEquals(2, calls)
        assertEquals(listOf(Triple(ConnectionStage.Entries, 2, ConnectionFailureKind.Timeout)), retries)
    }

    @Test fun neverRetriesPermanentFailure() = runBlocking {
        var calls = 0
        try {
            retryTransientRead(ConnectionStage.Profile, backoffMillis = 0) {
                calls++
                throw IllegalArgumentException("invalid server data")
            }
            fail("Expected permanent failure")
        } catch (_: IllegalArgumentException) {
            assertEquals(1, calls)
        }
    }

    @Test fun neverRetriesCancellation() = runBlocking {
        var calls = 0
        try {
            retryTransientRead(ConnectionStage.Targets, backoffMillis = 0) {
                calls++
                throw CancellationException("cancelled by UI")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test fun repeatedTransientFailureStopsAfterSecondAttempt() = runBlocking {
        var calls = 0
        try {
            retryTransientRead(ConnectionStage.ZeroDays, backoffMillis = 0) {
                calls++
                throw SocketTimeoutException("still unavailable")
            }
            fail("Expected final timeout")
        } catch (_: SocketTimeoutException) {
            assertEquals(2, calls)
        }
    }
}
