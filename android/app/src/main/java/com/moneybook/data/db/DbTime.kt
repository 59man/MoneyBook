package com.moneybook.data.db

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Local date-times are stored as fixed-width text so string order == time order. */
object DbTime {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    const val MAX = "9999-12-31T23:59:59.999"
    const val MIN = "0000-01-01T00:00:00.000"

    fun format(dateTime: LocalDateTime): String = dateTime.format(FORMAT)
    fun parse(text: String): LocalDateTime = LocalDateTime.parse(text, FORMAT)
    fun startOf(date: LocalDate): String = format(date.atStartOfDay())
}
