package io.github.cragcoffee.memoripple.domain.diary

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

interface TimeProvider {
    fun nowMillis(): Long
    fun currentLocalDate(): LocalDate
    fun currentZoneId(): ZoneId

    fun toEpochMillis(localDateTime: LocalDateTime): Long =
        localDateTime.atZone(currentZoneId()).toInstant().toEpochMilli()
}

class SystemTimeProvider : TimeProvider {
    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun currentLocalDate(): LocalDate = LocalDate.now()

    override fun currentZoneId(): ZoneId = ZoneId.systemDefault()
}
