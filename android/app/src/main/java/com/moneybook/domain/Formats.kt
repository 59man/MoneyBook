package com.moneybook.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Date strings exactly as Realbyte shows them in English. */
object Formats {
    private val en = Locale.ENGLISH
    private val monthTitle = DateTimeFormatter.ofPattern("MMM yyyy", en)
    private val monthName = DateTimeFormatter.ofPattern("MMM", en)
    private val weekday = DateTimeFormatter.ofPattern("EEE", en)
    private val dayHeaderDate = DateTimeFormatter.ofPattern("MM.yyyy", en)
    private val editDate = DateTimeFormatter.ofPattern("M/d/yy", en)
    private val time = DateTimeFormatter.ofPattern("HH:mm", en)
    private val monthDay = DateTimeFormatter.ofPattern("MM.dd", en)
    private val monthDayShort = DateTimeFormatter.ofPattern("M.d", en)
    private val rangeDate = DateTimeFormatter.ofPattern("M.d.yy", en)
    private val syncTimeFormat = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm", en)

    fun monthTitle(ym: YearMonth): String = ym.format(monthTitle)
    fun monthName(ym: YearMonth): String = ym.format(monthName)
    fun weekday(date: LocalDate): String = date.format(weekday)
    fun weekdayShort(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, en)
    fun dayHeaderDate(date: LocalDate): String = date.format(dayHeaderDate)
    fun editDate(date: LocalDate): String = "${date.format(editDate)} (${weekday(date)})"
    fun time(t: LocalTime): String = t.format(time)
    fun calendarDay(date: LocalDate): String = if (date.dayOfMonth == 1) "${date.monthValue}.1" else date.dayOfMonth.toString()
    fun rangeShort(range: DateRange): String = "${range.start.format(monthDay)}  ~  ${range.lastDay.format(monthDay)}"
    fun weekdays(weekStart: DayOfWeek): List<DayOfWeek> = (0L..6L).map { weekStart.plus(it) }

    /** "1/1/26" (Stats period chips). */
    fun shortDate(date: LocalDate): String = date.format(editDate)
    /** "9.20" (weekly chart labels). */
    fun monthDayShort(date: LocalDate): String = date.format(monthDayShort)
    /** "9.20 ~ 9.26" for the week starting on [start]. */
    fun weekTitle(start: LocalDate): String = "${start.format(monthDayShort)} ~ ${start.plusDays(6).format(monthDayShort)}"

    /** A memo's date chip and row: "9.26 (Sat)". */
    fun memoDate(date: LocalDate): String = "${date.format(monthDayShort)} (${weekday(date)})"

    /** Search rows: "2026-09-26". */
    fun isoDate(date: LocalDate): String = date.toString()
    /** "9.1.26  ~  9.30.26" (Trans → Total). */
    fun rangeWithYear(range: DateRange): String = "${range.start.format(rangeDate)}  ~  ${range.lastDay.format(rangeDate)}"

    /** The Sync screen's "Last sync": "Oct 14, 2026 10:32". */
    fun syncTime(epochMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(epochMs).atZone(zone).format(syncTimeFormat)
}
