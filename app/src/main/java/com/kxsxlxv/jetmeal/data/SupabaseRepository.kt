package com.kxsxlxv.jetmeal.data

import com.kxsxlxv.jetmeal.domain.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** All reads use the persisted authenticated session. Mutations are atomic RLS-backed RPCs. */
class SupabaseRepository(val client: SupabaseClient) {
    internal var diagnostics: ConnectionDiagnostics? = null
    internal var authFailures = AuthFailureCapture()
    private val searchMutex = Mutex()
    private val searchCacheLock = Any()
    private var searchCache: Pair<String, List<FoodCandidate>>? = null
    private var searchEpoch = 0
    fun invalidateSearch() = synchronized(searchCacheLock) { searchEpoch++; searchCache = null }

    suspend fun rankedCatalogue(): List<FoodCandidate> = searchMutex.withLock {
        val owner = requireNotNull(client.auth.currentUserOrNull()).id
        val (cached, epoch) = synchronized(searchCacheLock) {
            searchCache?.takeIf { it.first == owner } to searchEpoch
        }
        cached?.let { return@withLock it.second }
        val history = usage().groupBy { it.optional("food_variant_id") }
        val candidates = catalogue().map { candidate ->
            val rows = history[candidate.id].orEmpty()
            candidate.copy(usageCount = rows.size, lastUsed = rows.maxOfOrNull { Instant.parse(it.string("consumed_at")) })
        }
        synchronized(searchCacheLock) {
            if (epoch == searchEpoch && client.auth.currentUserOrNull()?.id == owner) searchCache = owner to candidates
        }
        candidates
    }
    suspend fun signIn(email: String, password: String) {
        val normalized = SignInValidation.normalizedEmail(email, password)
        client.auth.signInWith(Email) {
            this.email = normalized
            this.password = password
        }
    }

    suspend fun syncTimezone(zone: ZoneId) {
        val userId = requireNotNull(client.auth.currentUserOrNull()).id
        val profile = client.from("profiles").select { filter { eq("id", userId) } }
            .decodeList<JsonObject>().single()
        if (profile.string("timezone") != zone.id) {
            client.from("profiles").update(buildJsonObject { put("timezone", zone.id) }) {
                filter { eq("id", userId) }
            }
        }
    }

    suspend fun timezone(): ZoneId = ZoneId.of(client.from("profiles").select()
        .decodeList<JsonObject>().single().string("timezone"))

    suspend fun targets(): Targets? = client.from("nutrition_targets").select()
        .decodeList<JsonObject>().singleOrNull()?.let {
            Targets(it.number("daily_calories_kcal"), it.number("daily_protein_g"),
                it.number("daily_fat_g"), it.number("daily_carbs_g"), it.number("adjustment_limit_ratio"))
        }

    suspend fun targetHistory(): List<TargetVersion> =
        client.from("nutrition_target_history").select {
            order("effective_date", Order.ASCENDING)
        }.decodeList<JsonObject>().map {
            TargetVersion(LocalDate.parse(it.string("effective_date")),
                Targets(it.number("daily_calories_kcal"), it.number("daily_protein_g"),
                    it.number("daily_fat_g"), it.number("daily_carbs_g"),
                    it.number("adjustment_limit_ratio")))
        }

    suspend fun weightGoal(): WeightGoal? =
        client.from("weight_goals").select().decodeList<JsonObject>().singleOrNull()?.let {
            WeightGoal(LocalDate.parse(it.string("start_date")),
                LocalDate.parse(it.string("target_date")),
                it.number("start_weight_kg"),it.number("target_weight_kg"))
        }

    suspend fun setWeightGoal(startKg: Double,targetKg: Double,deadline: LocalDate) {
        require(startKg.isFinite() && startKg in 20.0..500.0)
        require(targetKg.isFinite() && targetKg in 20.0..500.0)
        require(deadline>LocalDate.now())
        client.postgrest.rpc("jetmeal_set_weight_goal",buildJsonObject {
            put("p_start_weight_kg",startKg)
            put("p_target_weight_kg",targetKg)
            put("p_target_date",deadline.toString())
        })
    }

    suspend fun weights(start: LocalDate, endExclusive: LocalDate, zone: ZoneId): List<WeightMeasurement> {
        val collected = mutableListOf<WeightMeasurement>()
        var offset = 0L
        do {
            val page = client.from("weight_measurements").select {
                filter { and {
                    gte("measured_at", start.atStartOfDay(zone).toInstant().toString())
                    lt("measured_at", endExclusive.atStartOfDay(zone).toInstant().toString())
                } }
                order("measured_at", Order.ASCENDING)
                range(offset, offset + PAGE_SIZE - 1)
            }.decodeList<JsonObject>()
            collected += page.map {
                WeightMeasurement(it.string("id"), Instant.parse(it.string("measured_at")),
                    it.number("weight_kg"), it["body_fat_percent"]?.jsonPrimitive?.doubleOrNull,
                    it.string("source"))
            }
            offset += page.size
        } while (page.size == PAGE_SIZE)
        return collected
    }

    suspend fun logWeight(kilograms: Double, measuredAt: Instant, source: String = "manual",
                          externalId: String? = null, bodyFatPercent: Double? = null) {
        require(kilograms.isFinite() && kilograms in 20.0..500.0)
        require(source in setOf("manual", "picooc", "health_connect"))
        client.postgrest.rpc("jetmeal_log_weight", buildJsonObject {
            put("p_weight_kg", kilograms)
            put("p_measured_at", measuredAt.toString())
            put("p_source", source)
            externalId?.let { put("p_external_id", it) }
            bodyFatPercent?.let { put("p_body_fat_percent", it) }
        })
    }

    suspend fun confirmedZeroDays(start: LocalDate, endExclusive: LocalDate): Set<LocalDate> {
        val rows = client.from("zero_calorie_days").select(columns = Columns.list("local_date")) {
            filter {
                and {
                    gte("local_date", start.toString())
                    lt("local_date", endExclusive.toString())
                }
            }
        }.decodeList<JsonObject>()
        return rows.mapTo(mutableSetOf()) { LocalDate.parse(it.string("local_date")) }
    }

    suspend fun confirmZeroDay(date: LocalDate, confirmed: Boolean) {
        client.postgrest.rpc("jetmeal_confirm_zero_day", buildJsonObject {
            put("p_date", date.toString())
            put("p_confirmed", confirmed)
        })
    }

    suspend fun entries(start: LocalDate, endExclusive: LocalDate, zone: ZoneId): List<DiaryEntry> {
        val entries = mutableListOf<DiaryEntry>()
        var offset = 0L
        do {
            val page = client.from("diary_entries").select {
                filter {
                    exact("deleted_at", null)
                    // Supabase Kotlin 3.8 serializes only the first top-level value for a
                    // repeated column; a single logical AND preserves both date bounds.
                    and {
                        gte("consumed_at", start.atStartOfDay(zone).toInstant().toString())
                        lt("consumed_at", endExclusive.atStartOfDay(zone).toInstant().toString())
                    }
                }
                order("consumed_at", Order.ASCENDING)
                order("id", Order.ASCENDING)
                range(offset, offset + PAGE_SIZE - 1)
            }.decodeList<JsonObject>()
            entries += page.map(::diaryEntry)
            offset += page.size
        } while (page.size == PAGE_SIZE)
        return entries
    }

    /** Paginate the personal catalogue; never silently truncate search or usage ranking. */
    suspend fun catalogue(): List<FoodCandidate> {
        val foods = activeRows("foods").associateBy { it.string("id") }
        val measures = activeRows("food_measures").groupBy { it.string("food_variant_id") }
        return activeRows("food_variants").mapNotNull { variant ->
            val food = foods[variant.string("food_id")] ?: return@mapNotNull null
            FoodCandidate(variant.string("id"), food.string("id"), food.string("name"),
                food.optional("brand"), food.optional("source"), variant.number("serving_amount"),
                variant.string("serving_unit"), nutrition(variant),
                variant["is_estimated"]?.jsonPrimitive?.boolean ?: false,
                measures=measures[variant.string("id")].orEmpty().map { measure ->
                    FoodMeasure(measure.string("id"), measure.string("food_variant_id"),
                        measure.string("measure_key"),measure.string("label"),
                        measure.number("base_amount"),
                        measure["is_approximate"]?.jsonPrimitive?.boolean ?: false,
                        measure["is_default"]?.jsonPrimitive?.boolean ?: false)
                })
        }
    }

    suspend fun usage(): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var offset = 0L
        do {
            val page = client.from("diary_entries").select(columns = Columns.list("id", "food_variant_id", "consumed_at")) {
                filter { exact("deleted_at", null) }
                order("id", Order.ASCENDING)
                range(offset, offset + PAGE_SIZE - 1)
            }.decodeList<JsonObject>()
            result += page
            offset += page.size
        } while (page.size == PAGE_SIZE)
        return result
    }

    private suspend fun activeRows(table: String): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var offset = 0L
        do {
            val page = client.from(table).select {
                filter { exact("archived_at", null) }
                order("id", Order.ASCENDING)
                range(offset, offset + PAGE_SIZE - 1)
            }.decodeList<JsonObject>()
            result += page
            offset += page.size
        } while (page.size == PAGE_SIZE)
        return result
    }

    suspend fun syncMutation(request: PendingDiaryMutation): ToolResult<JsonElement> {
        val response=Json.parseToJsonElement(client.postgrest.rpc("jetmeal_sync_mutation",
            buildJsonObject {
                put("p_request_id",request.id)
                put("p_operation",request.kind)
                put("p_input",request.input)
                request.expectedUpdatedAt?.let{put("p_expected_updated_at",it.toString())}
            }).data).jsonObject
        check(response["ok"]?.jsonPrimitive?.boolean == true) { "Mutation was not applied." }
        invalidateSearch()
        return ToolResult(true,response.optional("action_id"),response["data"] ?: JsonNull,
            response["warnings"]?.jsonArray?.map{it.jsonPrimitive.content} ?: emptyList(),
            response["undoable"]?.jsonPrimitive?.boolean ?: false)
    }

    internal suspend fun mutate(operation: String, input: JsonObject): ToolResult<JsonElement> {
        val response = Json.parseToJsonElement(client.postgrest.rpc("jetmeal_$operation",
            buildJsonObject { put("p_input", input) }).data).jsonObject
        check(response["ok"]?.jsonPrimitive?.boolean == true) { "The operation could not be completed." }
        invalidateSearch()
        return ToolResult(true, response.optional("action_id"), response["data"] ?: JsonNull,
            response["warnings"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            response["undoable"]?.jsonPrimitive?.boolean ?: false)
    }

    companion object {
        private const val PAGE_SIZE = 500
        internal fun diaryEntry(row: JsonObject): DiaryEntry = DiaryEntry(
            id = row.string("id"), name = row.string("snapshot_name"), brand = row.optional("snapshot_brand"),
            quantity = row.number("quantity"), unit = row.string("quantity_unit"),
            basisAmount = row.number("basis_amount_snapshot"),
            basisNutrition = Nutrition(row.number("basis_calories_kcal_snapshot"),
                row.number("basis_protein_g_snapshot"), row.number("basis_fat_g_snapshot"), row.number("basis_carbs_g_snapshot")),
            nutrition = Nutrition(row.number("calories_kcal_snapshot"), row.number("protein_g_snapshot"),
                row.number("fat_g_snapshot"), row.number("carbs_g_snapshot")),
            consumedAt = Instant.parse(row.string("consumed_at")),
            meal = MealPeriod.entries.single { it.wireValue == row.string("meal_type") },
            variantId = row.optional("food_variant_id"), foodId = row.optional("food_id"),
            confidence = row["confidence"]?.jsonPrimitive?.doubleOrNull,
            estimated = row["is_estimated_snapshot"]?.jsonPrimitive?.boolean ?: false,
            updatedAt = row.optional("updated_at")?.let(Instant::parse),
            mealGroupId = row.optional("meal_group_id"),
            enteredMeasureLabel = row.optional("entered_measure_label"),
            enteredMeasureKey = row.optional("entered_measure_key"),
            enteredMeasureQuantity = row["entered_measure_quantity"]?.jsonPrimitive?.doubleOrNull,
            enteredMeasureBaseAmount = row["entered_measure_base_amount"]?.jsonPrimitive?.doubleOrNull,
            enteredMeasureApproximate = row["entered_measure_approximate"]?.jsonPrimitive?.boolean ?: false
        )

        private fun nutrition(row: JsonObject) = Nutrition(row.number("calories_kcal"),
            row.number("protein_g"), row.number("fat_g"), row.number("carbs_g"))
    }
}

internal fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
internal fun JsonObject.optional(name: String) = get(name)?.jsonPrimitive?.contentOrNull
internal fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.double
