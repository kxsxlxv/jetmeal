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
/** Human-sized decimal input. Never serialize a binary floating-point tail
 * into a text field after a grams/portion conversion.
 * The formatted value is the value shown and saved, not a hidden full-precision copy. */
internal fun decimalInput(value: Double): String {
    require(value.isFinite())
    return java.math.BigDecimal.valueOf(value)
        .setScale(2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
        .replace('.', ',')
}
/** Previously recorded explicit input must not lose precision on opening edit. */
internal fun recordedDecimalInput(value: Double): String {
    require(value.isFinite())
    return java.math.BigDecimal.valueOf(value)
        .stripTrailingZeros().toPlainString().replace('.', ',')
}
internal fun unitLabel(unit: String): String = when(unit) {
    "g" -> "г"; "ml" -> "мл"; "piece", "pcs" -> "шт."; "serving", "portion" -> "порц."; "kg" -> "кг"; "l" -> "л"; else -> unit
}
internal fun MealPeriod.label(): String = when(this) {
    MealPeriod.Morning -> "Утро"; MealPeriod.Day -> "День"; MealPeriod.Evening -> "Вечер"; MealPeriod.Snack -> "Перекус"
}
