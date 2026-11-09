package com.sgexpense.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** Singapore time is used for all date bucketing. */
val SG_ZONE: ZoneId = ZoneId.of("Asia/Singapore")

fun Long.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), SG_ZONE)
fun Long.toLocalDate(): LocalDate = toLocalDateTime().toLocalDate()
fun LocalDate.startMillis(): Long = atStartOfDay(SG_ZONE).toInstant().toEpochMilli()
fun LocalDate.atMillis(time: LocalTime?): Long =
    atTime(time ?: LocalTime.NOON).atZone(SG_ZONE).toInstant().toEpochMilli()
fun LocalDateTime.toMillis(): Long = atZone(SG_ZONE).toInstant().toEpochMilli()
fun YearMonth.startMillis(): Long = atDay(1).startMillis()
fun YearMonth.endMillis(): Long = plusMonths(1).atDay(1).startMillis()
fun todaySg(): LocalDate = LocalDate.now(SG_ZONE)
