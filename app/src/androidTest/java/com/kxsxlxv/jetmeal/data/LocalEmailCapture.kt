package com.kxsxlxv.jetmeal.data

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads only an explicitly addressed test account's genuine captured local Auth email. */
internal object LocalEmailCapture {
    suspend fun code(mailpit: String, email: String): String = withTimeout(60_000) {
        var found: String? = null
        while (found == null) {
            val messages = fetch("$mailpit/api/v1/messages").getValue("messages").jsonArray
            for (message in messages) {
                val summary = message.jsonObject
                if (summary.getValue("To").jsonArray.none {
                        it.jsonObject.getValue("Address").jsonPrimitive.content == email
                    }) continue
                val id = summary.getValue("ID").jsonPrimitive.content
                val captured = fetch("$mailpit/api/v1/message/$id")
                val text = captured["Text"]?.jsonPrimitive?.content.orEmpty() +
                    captured["HTML"]?.jsonPrimitive?.content.orEmpty()
                found = Regex("\\b([0-9]{6})\\b").find(text)?.groupValues?.get(1)
                if (found != null) break
            }
            if (found == null) delay(200)
        }
        requireNotNull(found)
    }

    private suspend fun fetch(url: String): JsonObject = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            check(connection.responseCode == 200) { "Local email-capture API did not respond successfully." }
            connection.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        } finally {
            connection.disconnect()
        }
    }
}
