package com.moneybook.data

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

class TimeSource(private val clock: Clock = Clock.systemDefaultZone()) {
    fun millis(): Long = clock.millis()
    fun now(): LocalDateTime = LocalDateTime.now(clock)
    fun today(): LocalDate = LocalDate.now(clock)
}
