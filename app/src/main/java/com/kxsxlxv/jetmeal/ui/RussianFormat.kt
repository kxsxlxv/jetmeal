package com.kxsxlxv.jetmeal.ui

import com.kxsxlxv.jetmeal.domain.MealPeriod
import java.text.NumberFormat
import java.util.Locale

internal val RussianLocale: Locale = Locale.forLanguageTag("ru-RU")
internal fun number(value: Double, decimals: Int = 0): String = NumberFormat.getNumberInstance(RussianLocale).apply {
    maximumFractionDigits = decimals
    minimumFractionDigits = 0
}.format(value)
internal fun signed(value: Double): String = (if (value > 0) "+" else "") + number(value)
internal fun decimalInput(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString().replace('.', ',')
internal fun unitLabel(unit: String): String = when(unit) {
    "g" -> "г"; "ml" -> "мл"; "piece", "pcs" -> "шт."; "serving", "portion" -> "порц."; "kg" -> "кг"; "l" -> "л"; else -> unit
}
internal fun MealPeriod.label(): String = when(this) {
    MealPeriod.Morning -> "Утро"; MealPeriod.Day -> "День"; MealPeriod.Evening -> "Вечер"; MealPeriod.Snack -> "Перекус"
}
