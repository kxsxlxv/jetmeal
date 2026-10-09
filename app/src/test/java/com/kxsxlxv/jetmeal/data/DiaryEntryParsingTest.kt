package com.kxsxlxv.jetmeal.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DiaryEntryParsingTest {
    private fun row(flag: JsonElement?) = buildJsonObject {
        put("id", "test")
        put("snapshot_name", "Food")
        put("quantity", 100)
        put("quantity_unit", "g")
        put("basis_amount_snapshot", 100)
        put("basis_calories_kcal_snapshot", 110)
        put("basis_protein_g_snapshot", 10)
        put("basis_fat_g_snapshot", 5)
        put("basis_carbs_g_snapshot", 7)
        put("calories_kcal_snapshot", 110)
        put("protein_g_snapshot", 10)
        put("fat_g_snapshot", 5)
        put("carbs_g_snapshot", 7)
        put("consumed_at", "2026-10-09T08:00:00Z")
        put("meal_type", "morning")
        put("is_estimated_snapshot", false)
        flag?.let { put("entered_measure_approximate", it) }
    }

    @Test fun nullAndMissingFlagsDefaultToFalse() {
        assertFalse(SupabaseRepository.diaryEntry(row(JsonNull)).enteredMeasureApproximate)
        assertFalse(SupabaseRepository.diaryEntry(row(null)).enteredMeasureApproximate)
    }

    @Test fun explicitFlagsKeepTheirMeaning() {
        assertFalse(SupabaseRepository.diaryEntry(row(JsonPrimitive(false))).enteredMeasureApproximate)
        assertTrue(SupabaseRepository.diaryEntry(row(JsonPrimitive(true))).enteredMeasureApproximate)
    }
}
