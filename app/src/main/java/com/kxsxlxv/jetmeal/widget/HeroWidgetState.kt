package com.kxsxlxv.jetmeal.widget

import android.content.Context
import com.kxsxlxv.jetmeal.domain.DiaryEntry
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.domain.Targets
import com.kxsxlxv.jetmeal.domain.WeekBudget
import java.time.LocalDate
import java.time.ZoneId

internal sealed interface HeroWidgetState {
    data object Empty : HeroWidgetState
    data object SignedOut : HeroWidgetState
    data object MissingTargets : HeroWidgetState

    data class Ready(
        val date: LocalDate,
        val total: Nutrition,
        val calorieTarget: Double,
        val targets: Targets,
        val updatedAtMillis: Long,
    ) : HeroWidgetState
}

internal object HeroWidgetSnapshot {
    fun calculate(
        date: LocalDate,
        entries: List<DiaryEntry>,
        targets: Targets,
        zone: ZoneId,
        updatedAtMillis: Long = System.currentTimeMillis(),
        confirmedZeroDays: Set<LocalDate> = emptySet(),
        dailyTargets: Map<LocalDate, Targets> = emptyMap(),
    ): HeroWidgetState.Ready {
        val weekStart = date.minusDays(date.dayOfWeek.value - 1L)
        val relevant = entries.filter {
            val localDate = it.consumedAt.atZone(zone).toLocalDate()
            localDate in weekStart..date
        }
        val actualByDate = relevant
            .groupBy { it.consumedAt.atZone(zone).toLocalDate() }
            .mapValues { (_, rows) -> rows.sumOf { it.nutrition.calories } }
        val today = relevant
            .filter { it.consumedAt.atZone(zone).toLocalDate() == date }
            .fold(Nutrition.Zero) { sum, row -> sum + row.nutrition }

        return HeroWidgetState.Ready(
            date = date,
            total = today,
            calorieTarget = WeekBudget.calculate(date, targets, actualByDate, confirmedZeroDays,
                dailyTargets = dailyTargets).effectiveTarget,
            targets = targets,
            updatedAtMillis = updatedAtMillis,
        )
    }
}

internal class HeroWidgetStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun read(): HeroWidgetState = when (preferences.getString(KEY_STATUS, null)) {
        STATUS_READY -> runCatching {
            val date = preferences.getString(KEY_DATE, null)
                ?.let(LocalDate::parse)
                ?: return@runCatching HeroWidgetState.Empty
            HeroWidgetState.Ready(
                date = date,
                total = Nutrition(
                    preferences.getFloat(KEY_CALORIES, 0f).toDouble(),
                    preferences.getFloat(KEY_PROTEIN, 0f).toDouble(),
                    preferences.getFloat(KEY_FAT, 0f).toDouble(),
                    preferences.getFloat(KEY_CARBS, 0f).toDouble(),
                ),
                calorieTarget = preferences.getFloat(KEY_CALORIE_TARGET, 0f).toDouble(),
                targets = Targets(
                    preferences.getFloat(KEY_BASE_CALORIE_TARGET, 0f).toDouble(),
                    preferences.getFloat(KEY_PROTEIN_TARGET, 0f).toDouble(),
                    preferences.getFloat(KEY_FAT_TARGET, 0f).toDouble(),
                    preferences.getFloat(KEY_CARBS_TARGET, 0f).toDouble(),
                    preferences.getFloat(KEY_LIMIT_RATIO, .1f).toDouble(),
                ),
                updatedAtMillis = preferences.getLong(KEY_UPDATED_AT, 0L),
            )
        }.getOrDefault(HeroWidgetState.Empty)

        STATUS_SIGNED_OUT -> HeroWidgetState.SignedOut
        STATUS_MISSING_TARGETS -> HeroWidgetState.MissingTargets
        else -> HeroWidgetState.Empty
    }

    fun write(state: HeroWidgetState) {
        val editor = preferences.edit().clear()
        when (state) {
            HeroWidgetState.Empty -> editor.putString(KEY_STATUS, STATUS_EMPTY)
            HeroWidgetState.SignedOut -> editor.putString(KEY_STATUS, STATUS_SIGNED_OUT)
            HeroWidgetState.MissingTargets -> editor.putString(KEY_STATUS, STATUS_MISSING_TARGETS)
            is HeroWidgetState.Ready -> editor
                .putString(KEY_STATUS, STATUS_READY)
                .putString(KEY_DATE, state.date.toString())
                .putFloat(KEY_CALORIES, state.total.calories.toFloat())
                .putFloat(KEY_PROTEIN, state.total.protein.toFloat())
                .putFloat(KEY_FAT, state.total.fat.toFloat())
                .putFloat(KEY_CARBS, state.total.carbs.toFloat())
                .putFloat(KEY_CALORIE_TARGET, state.calorieTarget.toFloat())
                .putFloat(KEY_BASE_CALORIE_TARGET, state.targets.calories.toFloat())
                .putFloat(KEY_PROTEIN_TARGET, state.targets.protein.toFloat())
                .putFloat(KEY_FAT_TARGET, state.targets.fat.toFloat())
                .putFloat(KEY_CARBS_TARGET, state.targets.carbs.toFloat())
                .putFloat(KEY_LIMIT_RATIO, state.targets.limitRatio.toFloat())
                .putLong(KEY_UPDATED_AT, state.updatedAtMillis)
        }
        editor.apply()
    }

    private companion object {
        const val NAME = "hero_widget_state_v1"
        const val KEY_STATUS = "status"
        const val KEY_DATE = "date"
        const val KEY_CALORIES = "calories"
        const val KEY_PROTEIN = "protein"
        const val KEY_FAT = "fat"
        const val KEY_CARBS = "carbs"
        const val KEY_CALORIE_TARGET = "calorie_target"
        const val KEY_BASE_CALORIE_TARGET = "base_calorie_target"
        const val KEY_PROTEIN_TARGET = "protein_target"
        const val KEY_FAT_TARGET = "fat_target"
        const val KEY_CARBS_TARGET = "carbs_target"
        const val KEY_LIMIT_RATIO = "limit_ratio"
        const val KEY_UPDATED_AT = "updated_at"

        const val STATUS_EMPTY = "empty"
        const val STATUS_READY = "ready"
        const val STATUS_SIGNED_OUT = "signed_out"
        const val STATUS_MISSING_TARGETS = "missing_targets"
    }
}
