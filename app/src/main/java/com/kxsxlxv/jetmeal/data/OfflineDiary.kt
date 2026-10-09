package com.kxsxlxv.jetmeal.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.jan.supabase.exceptions.HttpRequestException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kxsxlxv.jetmeal.JetMealApplication
import com.kxsxlxv.jetmeal.domain.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class OfflineSnapshot(
    val start: LocalDate,
    val end: LocalDate,
    val entries: List<DiaryEntry>,
    val targets: Targets?,
    val history: List<TargetVersion>,
    val zeroDays: Set<LocalDate>,
)

data class PendingDiaryMutation(
    val id: String,
    val kind: String,
    val input: JsonObject,
    val expectedUpdatedAt: Instant? = null,
    val preview: DiaryEntry? = null,
    val blocked: Boolean = false,
    val attempted: Boolean = false,
)

data class OfflineSyncStatus(val remaining: Int, val blocked: Int, val synced: Int = 0)

private data class OfflineState(
    val snapshot: OfflineSnapshot? = null,
    val foods: List<FoodCandidate> = emptyList(),
    val pending: List<PendingDiaryMutation> = emptyList(),
)

/** Private, non-backed-up, AES-GCM-encrypted, crash-atomic user-scoped diary cache and outbox. */
internal class OfflineVault(private val context: Context) {
    private val alias = "com.kxsxlxv.jetmeal.offline.v1"
    private val mutex = Mutex()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private fun file(owner: String): AtomicFile {
        val safe = UUID.fromString(owner).toString()
        return AtomicFile(java.io.File(context.noBackupFilesDir, "nutrition-cache-$safe.aes"))
    }
    private fun read(owner: String): OfflineState {
        val file = file(owner)
        if (!file.baseFile.exists()) return OfflineState()
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > 28) { "Damaged encrypted diary cache" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        val root = Json.parseToJsonElement(
            String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
        ).jsonObject
        return OfflineState(
            root["snapshot"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::decodeSnapshot),
            root["foods"]?.jsonArray?.map { decodeFood(it.jsonObject) } ?: emptyList(),
            root["pending"]?.jsonArray?.map { decodePending(it.jsonObject) } ?: emptyList(),
        )
    }
    private fun write(owner: String, data: OfflineState) {
        val content = buildJsonObject {
            data.snapshot?.let { put("snapshot", encodeSnapshot(it)) }
            put("foods", JsonArray(data.foods.map(::encodeFood)))
            put("pending", JsonArray(data.pending.map(::encodePending)))
        }.toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val bytes = cipher.iv + cipher.doFinal(content.toByteArray(Charsets.UTF_8))
        val file = file(owner)
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }
    suspend fun readState(owner: String): Triple<OfflineSnapshot?,List<FoodCandidate>,List<PendingDiaryMutation>> =
        mutex.withLock { withContext(Dispatchers.IO) {
            val state=read(owner)
            Triple(state.snapshot,state.foods,state.pending)
        } }
    suspend fun change(owner: String, transform: (OfflineSnapshot?,List<FoodCandidate>,List<PendingDiaryMutation>) -> Triple<OfflineSnapshot?,List<FoodCandidate>,List<PendingDiaryMutation>>) {
        mutex.withLock { withContext(Dispatchers.IO) {
            val current=read(owner)
            val next=transform(current.snapshot,current.foods,current.pending)
            write(owner,OfflineState(next.first,next.second,next.third))
        } }
    }
}

class OfflineDiary(private val context: Context,private val repository: SupabaseRepository?) {
    private val vault=OfflineVault(context)
    private val syncMutex=Mutex()
    private fun owner(): String = requireNotNull(repository?.client?.auth?.currentUserOrNull()).id
    suspend fun status(): OfflineSyncStatus {
        val queue=vault.readState(owner()).third
        return OfflineSyncStatus(queue.size,queue.count { it.blocked })
    }
    suspend fun cachedSnapshot(): OfflineSnapshot?=vault.readState(owner()).first

    suspend fun catalogueCount(): Int = vault.readState(owner()).second.size
    suspend fun remember(snapshot: OfflineSnapshot) = vault.change(owner()) { _,foods,queue ->
        Triple(snapshot,foods,queue)
    }
    suspend fun rememberFoods(foods: List<FoodCandidate>) = vault.change(owner()) { snapshot,_,queue ->
        Triple(snapshot,foods,queue)
    }
    /** Full personal catalogue is cached during normal authenticated startup. */
    suspend fun cachedFoods(query: String): List<FoodCandidate> =
        withContext(Dispatchers.Default) {
            rankFoodCandidates(vault.readState(owner()).second,query)
        }
    suspend fun pending(): List<PendingDiaryMutation> = vault.readState(owner()).third

    suspend fun enqueueLog(candidate: FoodCandidate, amount: Double, at: Instant, meal: MealPeriod): Int {
        require(amount.isFinite() && amount > 0)
        val id=UUID.randomUUID().toString()
        val input=buildJsonObject {
            put("food_variant_id",candidate.id);put("quantity",amount)
            put("consumed_at",at.toString());put("meal_type",meal.wireValue)
        }
        val preview=DiaryEntry(id,candidate.name,candidate.brand,amount,candidate.unit,
            candidate.amount,candidate.nutrition,candidate.nutrition*(amount/candidate.amount),
            at,meal,candidate.id,candidate.foodId,null,candidate.estimated)
        return add(PendingDiaryMutation(id,"log_food",input,preview=preview))
    }
    suspend fun enqueueEdit(entry: DiaryEntry, amount: Double): Int {
        require(amount.isFinite() && amount>0)
        val expected=requireNotNull(entry.updatedAt) { "Record must be loaded before editing offline." }
        val id=UUID.randomUUID().toString()
        return add(PendingDiaryMutation(id,"update_log",buildJsonObject {
            put("entry_id",entry.id);put("quantity",amount)
        },expectedUpdatedAt=expected))
    }
    suspend fun enqueueDelete(entry: DiaryEntry): Int {
        val expected=requireNotNull(entry.updatedAt) { "Record must be loaded before deleting offline." }
        val id=UUID.randomUUID().toString()
        return add(PendingDiaryMutation(id,"delete_log",buildJsonObject {
            put("entry_id",entry.id)
        },expectedUpdatedAt=expected))
    }
    private suspend fun add(action: PendingDiaryMutation): Int {
        val user=owner()
        vault.change(user) { snapshot,foods,queue ->
            // A sequence of writes to one row must wait for the first acknowledgement;
            // otherwise every operation would reference a stale server revision.
            val entryId=action.input["entry_id"]?.jsonPrimitive?.contentOrNull
            require(entryId==null || queue.none {
                it.input["entry_id"]?.jsonPrimitive?.contentOrNull==entryId
            }) { "This food record is already awaiting synchronization." }
            Triple(snapshot,foods,queue+action)
        }
        schedule()
        return vault.readState(user).third.size
    }

    /** Overlay unacknowledged actions over the last server-confirmed snapshot. */
    suspend fun overlay(base: List<DiaryEntry>): List<DiaryEntry> =
        applyPending(base,pending())

    fun schedule() {
        val manager=WorkManager.getInstance(context)
        val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        manager.enqueueUniqueWork("jetmeal-diary-outbox",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OfflineSyncWorker>().setConstraints(constraints).build())
        manager.enqueueUniquePeriodicWork("jetmeal-diary-outbox-periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OfflineSyncWorker>(2,TimeUnit.HOURS)
                .setConstraints(constraints).build())
    }
    fun onlineNow(): Boolean {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val capabilities=manager.getNetworkCapabilities(manager.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Observe validated transport transitions even if the activity never goes through onResume. */
    fun networkChanges(): Flow<Boolean> = callbackFlow {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val callback=object : ConnectivityManager.NetworkCallback() {
            // Android delivers capabilities *after* onAvailable; querying the
            // manager synchronously inside callbacks can return stale results.
            override fun onLost(network: Network) { trySend(false) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        trySend(onlineNow())
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    suspend fun sync(): OfflineSyncStatus = syncMutex.withLock {
        if(!onlineNow()) return@withLock status()
        val user=owner()
        var count=0
        while (true) {
            val next=vault.readState(user).third.firstOrNull() ?: break
            if (next.blocked) break
            // Persist "attempted" before sending; the server may commit while the
            // response is lost. An attempted request may ONLY be resolved by replay.
            vault.change(user) {snapshot,foods,queue ->
                Triple(snapshot,foods,queue.map {
                    if (it.id==next.id) it.copy(attempted=true) else it
                })
            }
            try {
                requireNotNull(repository).syncMutation(next)
                // Never erase the local write until the server returns a receipt.
                vault.change(user) { snapshot,foods,queue ->
                    Triple(snapshot,foods,queue.filterNot { it.id==next.id })
                }
                count++
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (!isTransient(error)) {
                    vault.change(user) { snapshot,foods,queue ->
                        Triple(snapshot,foods,queue.map {
                            if(it.id==next.id) it.copy(blocked=true) else it
                        })
                    }
                }
                break
            }
        }
        val q=vault.readState(user).third
        OfflineSyncStatus(q.size,q.count { it.blocked },count)
    }
    suspend fun discardBlocked(): Int = syncMutex.withLock {
        val user=owner()
        vault.change(user) { snapshot,foods,queue ->
            Triple(snapshot,foods,queue.filterNot { it.blocked })
        }
        vault.readState(user).third.size
    }
    suspend fun cancelLast(): Boolean = syncMutex.withLock {
        val user=owner()
        val before=vault.readState(user).third
        if (before.isEmpty() || before.last().attempted || before.last().blocked) false
        else {
            vault.change(user) { snapshot,foods,queue ->
                Triple(snapshot,foods,queue.dropLast(1))
            }
            true
        }
    }
    private fun isTransient(error: Exception): Boolean =
        error is IOException || error is HttpRequestTimeoutException ||
            error is HttpRequestException ||
            error is RestException && (error.statusCode>=500 ||
                error.statusCode==408 || error.statusCode==429)
}

fun applyPending(base: List<DiaryEntry>, pending: List<PendingDiaryMutation>): List<DiaryEntry> {
    val byId=LinkedHashMap(base.associateBy { it.id })
    for (pendingAction in pending) {
        if (pendingAction.blocked) continue
        when(pendingAction.kind) {
        "log_food" -> pendingAction.preview?.let {
            if(byId.values.none { row -> row.mealGroupId == pendingAction.id })
                byId[it.id]=it
        }
        "update_log" -> {
            val id=pendingAction.input["entry_id"]?.jsonPrimitive?.contentOrNull
            val entry=byId[id]
            val amount=pendingAction.input["quantity"]?.jsonPrimitive?.doubleOrNull
            if (entry!=null && amount!=null && amount>0)
                byId[entry.id]=entry.copy(quantity=amount,
                    nutrition=entry.basisNutrition*(amount/entry.basisAmount))
        }
        "delete_log" -> {
            val id=pendingAction.input["entry_id"]?.jsonPrimitive?.contentOrNull
            byId.remove(id)
        }
        }
    }
    return byId.values.sortedBy { it.consumedAt }
}

class OfflineSyncWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as JetMealApplication
        val repo=app.repository ?: return Result.success()
        return try {
            repo.client.auth.awaitInitialization()
            if(repo.client.auth.sessionStatus.value !is SessionStatus.Authenticated)
                return Result.retry()
            val result=app.offlineDiary.sync()
            if(result.blocked>0 || result.remaining==0) Result.success()
            else Result.retry()
        } catch (cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_:Exception) {
            if(runAttemptCount<5) Result.retry() else Result.success()
        }
    }
}

private fun encodeNutrition(v:Nutrition)=buildJsonObject {
    put("calories",v.calories);put("protein",v.protein);put("fat",v.fat);put("carbs",v.carbs)
}
private fun decodeNutrition(v:JsonObject)=Nutrition(
    v.number("calories"),v.number("protein"),v.number("fat"),v.number("carbs"))
private fun encodeTargets(v:Targets)=buildJsonObject {
    put("calories",v.calories);put("protein",v.protein);put("fat",v.fat);put("carbs",v.carbs);put("limit",v.limitRatio)
}
private fun decodeTargets(v:JsonObject)=Targets(
    v.number("calories"),v.number("protein"),v.number("fat"),v.number("carbs"),v.number("limit"))
private fun encodeEntry(v:DiaryEntry)=buildJsonObject {
    put("id",v.id);put("name",v.name);v.brand?.let {put("brand",it)}
    put("quantity",v.quantity);put("unit",v.unit);put("basis",v.basisAmount)
    put("basisNutrition",encodeNutrition(v.basisNutrition))
    put("nutrition",encodeNutrition(v.nutrition));put("at",v.consumedAt.toString())
    put("meal",v.meal.wireValue);v.variantId?.let{put("variant",it)}
    v.foodId?.let{put("food",it)};v.confidence?.let{put("confidence",it)}
    put("estimated",v.estimated)
    v.updatedAt?.let{put("updatedAt",it.toString())}
    v.mealGroupId?.let{put("mealGroupId",it)}
}
private fun decodeEntry(v:JsonObject)=DiaryEntry(
    v.string("id"),v.string("name"),v.optional("brand"),v.number("quantity"),
    v.string("unit"),v.number("basis"),decodeNutrition(v.getValue("basisNutrition").jsonObject),
    decodeNutrition(v.getValue("nutrition").jsonObject),Instant.parse(v.string("at")),
    MealPeriod.fromWire(v.string("meal")),v.optional("variant"),v.optional("food"),
    v["confidence"]?.jsonPrimitive?.doubleOrNull,
    v["estimated"]?.jsonPrimitive?.boolean ?: false,
    v.optional("updatedAt")?.let(Instant::parse),
    v.optional("mealGroupId"),
)
private fun encodeFood(v:FoodCandidate)=buildJsonObject {
    put("id",v.id);put("foodId",v.foodId);put("name",v.name)
    v.brand?.let{put("brand",it)};v.source?.let{put("source",it)}
    put("amount",v.amount);put("unit",v.unit);put("nutrition",encodeNutrition(v.nutrition))
    put("estimated",v.estimated);put("usage",v.usageCount)
    v.lastUsed?.let{put("last",it.toString())}
}
private fun decodeFood(v:JsonObject)=FoodCandidate(
    v.string("id"),v.string("foodId"),v.string("name"),v.optional("brand"),
    v.optional("source"),v.number("amount"),v.string("unit"),
    decodeNutrition(v.getValue("nutrition").jsonObject),
    v["estimated"]?.jsonPrimitive?.boolean ?: false,
    v["usage"]?.jsonPrimitive?.intOrNull ?: 0,
    v.optional("last")?.let(Instant::parse),
)
private fun encodePending(v:PendingDiaryMutation)=buildJsonObject {
    put("id",v.id);put("kind",v.kind);put("input",v.input)
    v.expectedUpdatedAt?.let{put("expected",it.toString())}
    v.preview?.let{put("preview",encodeEntry(it))}
    put("blocked",v.blocked)
    put("attempted",v.attempted)
}
private fun decodePending(v:JsonObject)=PendingDiaryMutation(
    v.string("id"),v.string("kind"),v.getValue("input").jsonObject,
    v.optional("expected")?.let(Instant::parse),
    v["preview"]?.jsonObject?.let(::decodeEntry),
    v["blocked"]?.jsonPrimitive?.boolean ?: false,
    v["attempted"]?.jsonPrimitive?.boolean ?: false,
)
private fun encodeSnapshot(v:OfflineSnapshot)=buildJsonObject {
    put("start",v.start.toString());put("end",v.end.toString())
    put("entries",JsonArray(v.entries.map(::encodeEntry)))
    v.targets?.let{put("targets",encodeTargets(it))}
    put("history",JsonArray(v.history.map {
        buildJsonObject {put("date",it.date.toString());put("targets",encodeTargets(it.targets))}
    }))
    put("zero",JsonArray(v.zeroDays.map {JsonPrimitive(it.toString())}))
}
private fun decodeSnapshot(v:JsonObject)=OfflineSnapshot(
    LocalDate.parse(v.string("start")),LocalDate.parse(v.string("end")),
    v.getValue("entries").jsonArray.map {decodeEntry(it.jsonObject)},
    v["targets"]?.jsonObject?.let(::decodeTargets),
    v.getValue("history").jsonArray.map {
        TargetVersion(LocalDate.parse(it.jsonObject.string("date")),
            decodeTargets(it.jsonObject.getValue("targets").jsonObject))
    },
    v.getValue("zero").jsonArray.map {LocalDate.parse(it.jsonPrimitive.content)}.toSet(),
)
