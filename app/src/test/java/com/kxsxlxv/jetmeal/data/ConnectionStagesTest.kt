package com.kxsxlxv.jetmeal.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConnectionStagesTest {
    @Test fun wrapsOriginalFailureWithStageWithoutChangingItsNetworkClassification() = runBlocking {
        val original = IOException("private URL and access_token")
        val wrapped = try {
            atConnectionStage(ConnectionStage.Entries) { throw original }
            fail("Expected stage failure")
            throw AssertionError("Unreachable")
        } catch (error: ConnectionStageException) { error }
        assertEquals(ConnectionStage.Entries, wrapped.stage)
        assertSame(original, wrapped.cause)
        assertEquals(ConnectionFailureKind.Transport, ConnectionFailure.from(wrapped).kind)
        assertFalse(wrapped.message.orEmpty().contains("access_token"))
    }

    @Test fun neverWrapsCoroutineCancellation() = runBlocking {
        val original = CancellationException("cancelled")
        val observed = try {
            atConnectionStage(ConnectionStage.Profile) { throw original }
            fail("Expected cancellation")
            throw AssertionError("Unreachable")
        } catch (error: CancellationException) { error }
        assertSame(original, observed)
    }

    @Test fun reportsOnlyAllowlistedCodeSymbolsWithoutSecretExceptionText() {
        val issue = IllegalStateException("email=private@example.test token=secret")
        issue.stackTrace = arrayOf(
            StackTraceElement("untrusted.private.UserData", "tokenSecret", "tokenSecret.kt", 42),
            StackTraceElement("com.kxsxlxv.jetmeal.data.SupabaseRepository", "entries", "SupabaseRepository.kt", 123)
        )
        assertEquals("com.kxsxlxv.jetmeal.data.SupabaseRepository.entries", safeExceptionOrigin(issue))
        assertFalse(safeExceptionOrigin(issue).orEmpty().contains("private@example.test"))
        assertNull(safeExceptionOrigin(IllegalStateException("token").apply { stackTrace = emptyArray() }))
    }

    @Test fun rootCauseWinsOverStageWrapperForOrigin() {
        val root = IllegalStateException("secret")
        root.stackTrace = arrayOf(StackTraceElement("io.github.jan.supabase.SomeClient", "decode", "Client.kt", 55))
        val wrapped = ConnectionStageException(ConnectionStage.Targets, root)
        assertEquals("io.github.jan.supabase.SomeClient.decode", safeExceptionOrigin(wrapped))
    }
}
