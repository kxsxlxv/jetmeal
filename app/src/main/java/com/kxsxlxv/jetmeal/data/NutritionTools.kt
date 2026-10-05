package com.kxsxlxv.jetmeal.data

import com.kxsxlxv.jetmeal.domain.*
import io.github.jan.supabase.auth.auth
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

data class ToolResult<T>(val ok: Boolean, val actionId: String?, val data: T,
    val warnings: List<String> = emptyList(), val undoable: Boolean = false)
data class DayData(val date: LocalDate, val entries: List<DiaryEntry>, val targets: Targets?, val week: WeekState?) {
    val totals: Nutrition get() = entries.fold(Nutrition.Zero) { total, entry -> total + entry.nutrition }
    val effectiveCalorieTarget: Double? get() = week?.effectiveTarget
}
data class FoodDraft(val name: String, val kind: String, val brand: String?, val source: String?,
    val servingAmount: Double, val servingUnit: String, val nutrition: Nutrition, val estimated: Boolean = false)
data class LogFood(val variantId: String? = null, val quantity: Double, val consumedAt: Instant,
    val meal: MealPeriod? = null, val estimateName: String? = null, val unit: String? = null,
    val estimateNutrition: Nutrition? = null, val confidence: Double? = null, val actionId: String? = null)
data class LogCorrection(val entryId: String, val quantity: Double? = null,
    val consumedAt: Instant? = null, val meal: MealPeriod? = null)
data class CatalogConstraints(val maxCalories: Double? = null, val minProtein: Double? = null,
    val maxFat: Double? = null, val maxCarbs: Double? = null)

/** Opaque capability, created by the human confirmation path and never accepted from a model payload. */
class TargetConfirmation internal constructor(internal val targets: Targets, internal val owner: String) {
    internal var used = false
}

/** Shared typed application operations. External transport/user linking remains a separate adapter. */
class NutritionTools(private val repository: SupabaseRepository) {
    suspend fun searchFood(query: String, limit: Int = 20): ToolResult<List<FoodCandidate>> {
        require(limit in 1..100)
        return ToolResult(true, null, ranked(query).take(limit))
    }
    private suspend fun ranked(query: String): List<FoodCandidate> {
        val words = normalize(query).split(' ').filter(String::isNotBlank)
        return repository.rankedCatalogue().filter { candidate ->
            val text = normalize("${candidate.name} ${candidate.brand.orEmpty()} ${candidate.source.orEmpty()}")
            words.all { text.contains(it) }
        }.map { candidate ->
            val name = normalize(candidate.name)
            val confidence = when { query.isBlank() -> 0.5; name == normalize(query) -> 0.99; name.startsWith(normalize(query)) -> 0.9; else -> 0.75 }
            candidate.copy(matchConfidence = confidence, matchReason = when {
                query.isBlank() -> "Frequently used personal food"
                confidence >= 0.99 -> "Exact normalized name"
                confidence >= 0.9 -> "Name prefix"
                else -> "Name, brand or source match"
            })
        }.sortedWith(compareByDescending<FoodCandidate> { candidate ->
            val name = normalize(candidate.name)
            when { query.isBlank() -> 0; name == normalize(query) -> 3; name.startsWith(normalize(query)) -> 2; else -> 1 }
        }.thenByDescending { it.usageCount }.thenByDescending { it.lastUsed }.thenBy { it.name })
    }

    suspend fun searchCatalog(query: String, constraints: CatalogConstraints): ToolResult<List<FoodCandidate>> {
        listOfNotNull(constraints.maxCalories, constraints.minProtein, constraints.maxFat, constraints.maxCarbs)
            .forEach { require(it.isFinite() && it >= 0) }
        return ToolResult(true, null, ranked(query).filter {
            (constraints.maxCalories == null || it.nutrition.calories <= constraints.maxCalories) &&
                (constraints.minProtein == null || it.nutrition.protein >= constraints.minProtein) &&
                (constraints.maxFat == null || it.nutrition.fat <= constraints.maxFat) &&
                (constraints.maxCarbs == null || it.nutrition.carbs <= constraints.maxCarbs)
        }.take(100))
    }

    suspend fun createFood(food: FoodDraft): ToolResult<JsonElement> {
        require(food.name.isNotBlank() && food.servingUnit.isNotBlank())
        validQuantity(food.servingAmount)
        validNutrition(food.nutrition)
        require(food.kind in setOf("generic", "packaged", "restaurant", "canteen", "ai_estimate"))
        return repository.mutate("create_food", buildJsonObject {
            put("name", food.name.trim()); put("normalized_name", normalize(food.name)); put("kind", food.kind)
            food.brand?.let { put("brand", it) }; food.source?.let { put("source", it) }
            put("serving_amount", food.servingAmount); put("serving_unit", food.servingUnit)
            putNutrition(food.nutrition); put("is_estimated", food.estimated)
        })
    }

    suspend fun logFood(log: LogFood): ToolResult<JsonElement> {
        validQuantity(log.quantity)
        require(log.confidence == null || (log.confidence.isFinite() && log.confidence in 0.0..1.0))
        log.actionId?.let { UUID.fromString(it) }
        if (log.variantId == null) {
            require(!log.estimateName.isNullOrBlank() && !log.unit.isNullOrBlank())
            validNutrition(requireNotNull(log.estimateNutrition))
        }
        return repository.mutate("log_food", buildJsonObject {
            log.variantId?.let { put("food_variant_id", it) }
            put("quantity", log.quantity); put("consumed_at", log.consumedAt.toString())
            log.meal?.let { put("meal_type", it.wireValue) }
            log.estimateName?.let { put("snapshot_name", it) }; log.unit?.let { put("quantity_unit", it) }
            log.estimateNutrition?.let { putNutrition(it, "calories") }
            log.confidence?.let { put("confidence", it) }; log.actionId?.let { put("action_id", it) }
        })
    }

    suspend fun updateLog(correction: LogCorrection): ToolResult<JsonElement> {
        correction.quantity?.let(::validQuantity)
        require(correction.quantity != null || correction.consumedAt != null || correction.meal != null)
        return repository.mutate("update_log", buildJsonObject {
            put("entry_id", correction.entryId); correction.quantity?.let { put("quantity", it) }
            correction.consumedAt?.let { put("consumed_at", it.toString()) }
            correction.meal?.let { put("meal_type", it.wireValue) }
        })
    }

    suspend fun deleteLog(entryId: String) = repository.mutate("delete_log", buildJsonObject { put("entry_id", entryId) })
    suspend fun repeatMeal(entryIds: List<String>, consumedAt: Instant, meal: MealPeriod? = null): ToolResult<JsonElement> {
        require(entryIds.isNotEmpty() && entryIds.size <= 100)
        return repository.mutate("repeat_meal", buildJsonObject {
            put("entry_ids", JsonArray(entryIds.map(::JsonPrimitive))); put("consumed_at", consumedAt.toString())
            meal?.let { put("meal_type", it.wireValue) }
        })
    }

    suspend fun getTargets() = ToolResult(true, null, repository.targets())
    suspend fun getWeek(date: LocalDate): ToolResult<WeekState?> {
        val targets = repository.targets() ?: return ToolResult(true, null, null, listOf("Set nutrition targets in Settings."))
        val zone = repository.timezone()
        val start = date.minusDays((date.dayOfWeek.value - 1).toLong())
        val actual = repository.entries(start, start.plusDays(7), zone)
            .groupBy { it.consumedAt.atZone(zone).toLocalDate() }
            .mapValues { (_, rows) -> rows.sumOf { it.nutrition.calories } }
        return ToolResult(true, null, WeekBudget.calculate(date, targets, actual))
    }

    suspend fun getDay(date: LocalDate): ToolResult<DayData> {
        val zone = repository.timezone()
        return ToolResult(true, null, DayData(date, repository.entries(date, date.plusDays(1), zone),
            repository.targets(), getWeek(date).data))
    }

    internal fun confirmedByUser(targets: Targets): TargetConfirmation {
        validTargets(targets)
        return TargetConfirmation(targets, requireNotNull(repository.client.auth.currentUserOrNull()).id)
    }

    suspend fun updateTargets(targets: Targets, confirmation: TargetConfirmation?): ToolResult<JsonElement> {
        validTargets(targets)
        require(confirmation != null && !confirmation.used && confirmation.targets == targets &&
            confirmation.owner == repository.client.auth.currentUserOrNull()?.id) { "Target changes require confirmation in JetMeal." }
        confirmation.used = true
        val values = targetInput(targets)
        val challenge = repository.mutate("prepare_targets", values).data.jsonObject.string("confirmation")
        return repository.mutate("update_targets", JsonObject(values + ("confirmation" to JsonPrimitive(challenge))))
    }

    suspend fun undoLastAction() = repository.mutate("undo_last_action", buildJsonObject {})

    private fun targetInput(targets: Targets) = buildJsonObject {
        put("daily_calories_kcal", targets.calories); put("daily_protein_g", targets.protein)
        put("daily_fat_g", targets.fat); put("daily_carbs_g", targets.carbs)
        put("adjustment_limit_ratio", targets.limitRatio)
    }
    private fun validTargets(targets: Targets) {
        validNutrition(Nutrition(targets.calories, targets.protein, targets.fat, targets.carbs))
        require(targets.calories > 0 && targets.limitRatio.isFinite() && targets.limitRatio in 0.0..1.0)
    }
    private fun validQuantity(value: Double) { require(value.isFinite() && value > 0 && value <= 1_000_000) }
    private fun validNutrition(n: Nutrition) {
        listOf(n.calories, n.protein, n.fat, n.carbs).forEach { require(it.isFinite() && it in 0.0..1_000_000.0) }
    }
    private fun normalize(text: String) = text.trim().lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("\\s+"), " ")
    private fun JsonObjectBuilder.putNutrition(n: Nutrition, caloriesKey: String = "calories_kcal") {
        put(caloriesKey, n.calories); put("protein_g", n.protein); put("fat_g", n.fat); put("carbs_g", n.carbs)
    }
}
