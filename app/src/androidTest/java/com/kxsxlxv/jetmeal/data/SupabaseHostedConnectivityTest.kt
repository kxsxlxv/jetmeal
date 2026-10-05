package com.kxsxlxv.jetmeal.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kxsxlxv.jetmeal.BuildConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.okhttp.OkHttp

/** Real cloud TLS/SDK and anonymous isolation; does not send mail or create users/data. */
@RunWith(AndroidJUnit4::class)
class SupabaseHostedConnectivityTest {
    @Test fun cloudSdkCannotReadOwnedDataOrWriteWithoutSignIn() {
        val uri = URI(BuildConfig.SUPABASE_URL)
        assumeTrue("Requires configured hosted Supabase.", uri.scheme == "https" &&
            uri.host.endsWith(".supabase.co") && BuildConfig.SUPABASE_KEY.isNotBlank())
        val settings = request("/auth/v1/settings")
        assertEquals(200, settings.first)
        assertTrue(Json.parseToJsonElement(settings.second).jsonObject
            .getValue("external").jsonObject.getValue("email").jsonPrimitive.boolean)
        runBlocking {
        val client = createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) {
            httpEngine = OkHttp.create()
            requestTimeout = 15.seconds
            install(Auth) {
                sessionManager = MemorySessionManager()
                // This SDK-only test has no Activity to emit ProcessLifecycle ON_START.
                enableLifecycleCallbacks = false
            }
            install(Postgrest) { timeout = 15.seconds; maxRetries = 0 }
        }
        try {
            client.auth.awaitInitialization()
            for (table in listOf("profiles", "foods", "food_variants", "diary_entries", "nutrition_targets", "audit_events")) {
                try {
                    assertTrue("Unauthenticated data must be isolated: $table",
                        client.from(table).select().decodeList<JsonObject>().isEmpty())
                } catch (denied: RestException) {
                    assertTrue("Only authorization denial is acceptable.", denied.statusCode in setOf(401, 403))
                }
            }
            val denied = request("/rest/v1/rpc/jetmeal_create_food", "{\"p_input\":{}}")
            assertTrue("Unauthenticated mutation must be denied.", denied.first in setOf(401, 403))
            assertEquals("42501", Json.parseToJsonElement(denied.second).jsonObject.getValue("code").jsonPrimitive.content)
        } finally { client.close() }
        }
    }

    private fun request(path: String, body: String? = null): Pair<Int, String> {
        val connection = URI(BuildConfig.SUPABASE_URL + path).toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
            if (body != null) {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            status to stream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
}
