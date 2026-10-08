package com.kxsxlxv.jetmeal.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kxsxlxv.jetmeal.JetMealApplication
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Unofficial PICOOC cloud protocol, based on the MIT-licensed SmartScaleConnect
 * implementation (AlexxIT/SmartScaleConnect). No vendor partnership or SLA.
 * The account is authenticated directly over TLS on the user's phone.
 */
internal class PicoocCloud {
    data class Reading(val externalId: String, val measuredAt: Instant,
                       val kilograms: Double, val bodyFatPercent: Double?)
    private val api = "https://api2.picooc-int.com/v1/api/"
    private val appVersion = "i4.1.11.0"

    suspend fun fetch(email: String, password: String, deviceId: String): List<Reading> =
        withContext(Dispatchers.IO) {
            val loginParameters = parameters("user_login_new", deviceId)
            val loginJson = JSONObject().apply {
                put("appver", loginParameters.getValue("appver"))
                put("timestamp", loginParameters.getValue("timestamp"))
                put("lang", loginParameters.getValue("lang"))
                put("method", loginParameters.getValue("method"))
                put("timezone", "")
                put("sign", loginParameters.getValue("sign"))
                put("push_token", loginParameters.getValue("push_token"))
                put("device_id", deviceId)
                put("req", JSONObject().apply {
                    put("app_version", appVersion)
                    put("email", email)
                    put("password", password)
                })
            }
            val authResponse = request(
                "account/login",
                loginParameters + ("reqData" to loginJson.toString()),
                post = true,
            )
            if (authResponse.optInt("code", -1) != 0) {
                throw IllegalStateException("PICOOC не принял логин или пароль либо изменил API.")
            }
            val auth = authResponse.optJSONObject("resp")
                ?: throw IllegalStateException("PICOOC не вернул данные пользователя.")
            val userId = auth.optString("user_id").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Не получен идентификатор PICOOC.")
            val roleId = auth.optString("role_id").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Не получен профиль пользователя PICOOC.")
            val weights = mutableMapOf<String, Reading>()
            val seenPages = mutableSetOf<String>()
            var cursor: String? = null
            repeat(100) {
                val params = parameters("bodyIndexList", deviceId).toMutableMap()
                params["pageSize"] = "1000"
                params["time"] = cursor ?: params.getValue("timestamp")
                params["userId"] = userId
                params["roleId"] = roleId
                val response = request("bodyIndex/bodyIndexList", params, post = false)
                val page = response.optJSONObject("resp")
                    ?: throw IllegalStateException("PICOOC не вернул журнал измерений.")
                val records = page.optJSONArray("records")
                    ?: throw IllegalStateException("PICOOC вернул неизвестный формат измерений.")
                for (i in 0 until records.length()) {
                    val record = records.optJSONObject(i) ?: continue
                    if (record.optInt("is_del") != 0 || record.optInt("abnormal_flag") != 0) continue
                    val id = record.optString("body_index_id").takeIf { it.isNotBlank() && it != "0" }
                        ?: continue
                    val epoch = record.optLong("bodyTime",0)
                    val kg = record.optDouble("weight",Double.NaN)
                    if (!kg.isFinite() || kg !in 20.0..500.0 ||
                        epoch !in 946684800L..Instant.now().plusSeconds(86400).epochSecond) continue
                    val fat = record.optDouble("body_fat",Double.NaN).takeIf {
                        it.isFinite() && it in 0.0..100.0
                    }
                    weights[id] = Reading("$userId:$roleId:$id",Instant.ofEpochSecond(epoch),kg,fat)
                }
                if (!page.optBoolean("continue",false)) return@withContext weights.values.toList()
                val next = page.optString("lastTime")
                if (next.isBlank() || !seenPages.add(next)) {
                    throw IllegalStateException("Не удалось завершить загрузку истории PICOOC.")
                }
                cursor = next
            }
            throw IllegalStateException("Слишком много страниц измерений PICOOC.")
        }

    private fun parameters(method: String, deviceId: String): Map<String,String> {
        val timestamp = (System.currentTimeMillis()/1000).toString()
        val sign = md5(deviceId + md5(timestamp + md5(method) + md5(appVersion)))
        return mapOf("appver" to appVersion,"timestamp" to timestamp,"lang" to "en",
            "method" to method,"timezone" to "","sign" to sign,
            "push_token" to "android::$deviceId","device_id" to deviceId)
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    private fun request(path: String, params: Map<String,String>, post: Boolean): JSONObject {
        val encoded = params.entries.joinToString("&") {
            URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8")
        }
        val url = URL(api+path+(if(post) "" else "?$encoded"))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout=15000
            readTimeout=20000
            instanceFollowRedirects=false
            requestMethod=if(post) "POST" else "GET"
            if(post) {
                doOutput=true
                setRequestProperty("Content-Type","application/x-www-form-urlencoded")
            }
        }
        try {
            if(post) connection.outputStream.use {
                it.write(encoded.toByteArray(StandardCharsets.UTF_8))
            }
            if(connection.responseCode !in 200..299) {
                throw IllegalStateException("PICOOC недоступен (HTTP ${connection.responseCode}).")
            }
            val data=connection.inputStream.bufferedReader().use { it.readText() }
            if (data.length>8_000_000) throw IllegalStateException("Слишком большой ответ PICOOC.")
            return JSONObject(data)
        } finally { connection.disconnect() }
    }
}

/** Credentials are scoped to the signed-in Supabase owner and encrypted with Android Keystore. */
internal class PicoocCredentialStore(private val context: Context) {
    private val pref=context.getSharedPreferences("picooc_account_v1",Context.MODE_PRIVATE)
    private val keyAlias="com.kxsxlxv.jetmeal.picooc.v1"
    data class Credentials(val owner: String,val email: String,val password: String,val deviceId: String)

    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun save(credentials: Credentials) {
        val plain=JSONObject().put("owner",credentials.owner).put("email",credentials.email)
            .put("password",credentials.password).put("deviceId",credentials.deviceId).toString()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val encrypted=cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        pref.edit().putString("data",Base64.encodeToString(cipher.iv+encrypted,Base64.NO_WRAP)).apply()
    }
    fun load(owner: String): Credentials? {
        val value=pref.getString("data",null) ?: return null
        return runCatching {
            val bytes=Base64.decode(value,Base64.NO_WRAP)
            require(bytes.size>28)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            val obj=JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),StandardCharsets.UTF_8))
            Credentials(obj.getString("owner"),obj.getString("email"),
                obj.getString("password"),obj.getString("deviceId"))
        }.getOrNull()?.takeIf { it.owner==owner }
    }
    fun remove() { pref.edit().remove("data").apply() }
}

class PicoocIntegration(private val context: Context,private val repository: SupabaseRepository?) {
    private val store=PicoocCredentialStore(context)
    fun connected(owner: String): Boolean=store.load(owner)!=null
    suspend fun connect(email: String,password: String): Int {
        require(email.isNotBlank() && password.isNotEmpty())
        val user=requireNotNull(repository?.client?.auth?.currentUserOrNull())
        val credentials=PicoocCredentialStore.Credentials(user.id,email.trim(),password,
            UUID.randomUUID().toString().uppercase())
        // Authenticate before storing credentials. No fake "connected" state.
        val readings=PicoocCloud().fetch(credentials.email,credentials.password,credentials.deviceId)
        store.save(credentials)
        enqueue()
        return persist(readings)
    }
    suspend fun sync(): Int {
        val user=requireNotNull(repository?.client?.auth?.currentUserOrNull())
        val account=store.load(user.id) ?: return 0
        return persist(PicoocCloud().fetch(account.email,account.password,account.deviceId))
    }
    private suspend fun persist(readings: List<PicoocCloud.Reading>): Int {
        val data=requireNotNull(repository)
        for(reading in readings) {
            data.logWeight(reading.kilograms,reading.measuredAt,"picooc",reading.externalId,reading.bodyFatPercent)
        }
        return readings.size
    }
    fun disconnect() {
        store.remove()
        WorkManager.getInstance(context).cancelUniqueWork("jetmeal-picooc-periodic")
    }
    fun enqueue() {
        val request=PeriodicWorkRequestBuilder<PicoocSyncWorker>(1,TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "jetmeal-picooc-periodic",ExistingPeriodicWorkPolicy.KEEP,request)
    }
}

class PicoocSyncWorker(context: Context,parameters: WorkerParameters):
    CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result {
        val app=applicationContext as JetMealApplication
        val repo=app.repository ?: return Result.success()
        return try {
            repo.client.auth.awaitInitialization()
            if (repo.client.auth.sessionStatus.value !is SessionStatus.Authenticated) return Result.retry()
            app.picoocIntegration.sync()
            Result.success()
        } catch (_: Exception) {
            if(runAttemptCount<3) Result.retry() else Result.failure()
        }
    }
}
