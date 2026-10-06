package com.kxsxlxv.jetmeal.ui

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

enum class TimeScale(val label: String) { Day("День"), Week("Неделя"), Month("Месяц"), Quarter("3 месяца") }

/** Presentation calendar navigation only; all nutrition arithmetic stays in the existing domain. */
object TimelinePeriods {
    fun start(scale: TimeScale, date: LocalDate): LocalDate = when(scale) {
        TimeScale.Day -> date
        TimeScale.Week -> date.minusDays(date.dayOfWeek.value - 1L)
        TimeScale.Month -> date.withDayOfMonth(1)
        TimeScale.Quarter -> date.withDayOfMonth(1).minusMonths(((date.monthValue - 1) % 3).toLong())
    }
    fun endExclusive(scale: TimeScale, date: LocalDate): LocalDate = start(scale,date).let {
        when(scale) { TimeScale.Day -> it.plusDays(1); TimeScale.Week -> it.plusDays(7); TimeScale.Month -> it.plusMonths(1); TimeScale.Quarter -> it.plusMonths(3) }
    }
    fun move(scale: TimeScale, date: LocalDate, offset: Int): LocalDate = start(scale,date).let {
        when(scale) { TimeScale.Day -> it.plusDays(offset.toLong()); TimeScale.Week -> it.plusWeeks(offset.toLong()); TimeScale.Month -> it.plusMonths(offset.toLong()); TimeScale.Quarter -> it.plusMonths(offset * 3L) }
    }
    fun title(scale: TimeScale, date: LocalDate, today: LocalDate = LocalDate.now()): String {
        val start = start(scale,date)
        val end = endExclusive(scale,date).minusDays(1)
        val dayFormat = DateTimeFormatter.ofPattern(if(date.year==today.year) "d MMMM" else "d MMMM yyyy",RussianLocale)
        val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy",RussianLocale)
        val text = when(scale) {
            TimeScale.Day -> when(date) { today -> "Сегодня"; today.minusDays(1) -> "Вчера, ${date.format(dayFormat)}"; else -> date.format(dayFormat) }
            TimeScale.Month -> start.format(monthFormat)
            TimeScale.Quarter -> "${start.format(DateTimeFormatter.ofPattern("LLLL",RussianLocale))}–${end.format(monthFormat)}"
            TimeScale.Week -> when {
                start.year != end.year -> {
                    val withYear = DateTimeFormatter.ofPattern("d MMMM yyyy", RussianLocale)
                    "${start.format(withYear)} – ${end.format(withYear)}"
                }
                YearMonth.from(start) == YearMonth.from(end) -> "${start.dayOfMonth}–${end.format(dayFormat)}"
                else -> "${start.format(dayFormat)} – ${end.format(dayFormat)}"
            }
        }
        return text.replaceFirstChar { it.titlecase(RussianLocale) }
    }
}
