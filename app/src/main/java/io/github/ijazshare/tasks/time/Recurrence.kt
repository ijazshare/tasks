/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

// The RRULE subset of spec section 8.2. Pure JVM: nothing here reads a clock or a default zone.

enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** One BYDAY entry; [ordinal] is 1..4 or -1 (last) and only for MONTHLY. */
data class DayRule(val ordinal: Int?, val day: DayOfWeek) {
    override fun toString() = "${ordinal ?: ""}${CODES[day.value - 1]}"

    companion object {
        val CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        private val SHAPE = Regex("(-1|[1-4])?(MO|TU|WE|TH|FR|SA|SU)")

        fun parse(text: String): DayRule? = SHAPE.matchEntire(text)?.let { m ->
            DayRule(m.groupValues[1].ifEmpty { null }?.toInt(), DayOfWeek.of(CODES.indexOf(m.groupValues[2]) + 1))
        }
    }
}

data class RecurrenceRule(
    val freq: Freq,
    val interval: Int = 1,
    val byDay: List<DayRule> = emptyList(),
    val byMonthDay: Int? = null,
    val byMonth: Int? = null,
) {
    init {
        require(interval >= 1)
        require(byMonthDay == null || byMonthDay == -1 || byMonthDay in 1..MAX_DAY)
        require(byMonth == null || byMonth in 1..MONTHS)
        when (freq) {
            Freq.DAILY -> require(byDay.isEmpty() && byMonthDay == null && byMonth == null)

            Freq.WEEKLY -> require(byDay.all { it.ordinal == null } && byMonthDay == null && byMonth == null)

            Freq.MONTHLY -> require(
                byMonth == null && byDay.all { it.ordinal != null } && (byDay.isEmpty() || byMonthDay == null),
            )

            Freq.YEARLY -> require(byDay.isEmpty())
        }
    }

    fun format(): String = buildList {
        add("FREQ=$freq")
        if (interval > 1) add("INTERVAL=$interval")
        if (byDay.isNotEmpty()) add("BYDAY=" + byDay.joinToString(","))
        byMonth?.let { add("BYMONTH=$it") }
        byMonthDay?.let { add("BYMONTHDAY=$it") }
    }.joinToString(";")

    /** Every occurrence date on or after [anchor], ascending. Weeks start on Monday (Q12: proposed, unanswered). */
    fun dates(anchor: LocalDate): Sequence<LocalDate> {
        val step = interval.toLong()
        val counter = generateSequence(0L) { it + 1 }
        return when (freq) {
            Freq.DAILY -> generateSequence(anchor) { it.plusDays(step) }

            Freq.WEEKLY -> {
                val days = byDay.map { it.day }.ifEmpty { listOf(anchor.dayOfWeek) }.distinct().sorted()
                val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                counter.flatMap { w ->
                    days.asSequence().map { monday.plusWeeks(w * step).plusDays(it.value - 1L) }
                }.filter { !it.isBefore(anchor) }
            }

            Freq.MONTHLY -> counter.flatMap { m -> monthDates(YearMonth.from(anchor).plusMonths(m * step), anchor) }
                .filter { !it.isBefore(anchor) }

            Freq.YEARLY -> counter.map { y ->
                clamp(YearMonth.of(anchor.year, byMonth ?: anchor.monthValue).plusYears(y * step), anchor)
            }.filter { !it.isBefore(anchor) }
        }
    }

    private fun monthDates(month: YearMonth, anchor: LocalDate): Sequence<LocalDate> =
        if (byDay.isEmpty()) {
            sequenceOf(clamp(month, anchor))
        } else {
            byDay.map { month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(it.ordinal!!, it.day)) }
                .distinct().sorted().asSequence()
        }

    /** Q5: proposed, unanswered. The 29th to 31st clamp to the last day of a shorter month; -1 is the last day. */
    private fun clamp(month: YearMonth, anchor: LocalDate): LocalDate {
        val day = byMonthDay ?: anchor.dayOfMonth
        return month.atDay(if (day == -1) month.lengthOfMonth() else minOf(day, month.lengthOfMonth()))
    }

    private fun pick(due: DueSpec, ok: (LocalDate) -> Boolean) = due.copy(date = dates(due.date).first(ok))

    /** The first due of a new or re-dated recurring task: the earliest occurrence at or after [now] (8.2). */
    fun first(due: DueSpec, now: Instant, device: ZoneId): DueSpec {
        val today = now.atZone(due.zoneId(device)).toLocalDate()
        return pick(due) { d ->
            if (due.time == null) d >= today else due.copy(date = d).instant(device) >= now
        }
    }

    /** Schedule anchor: the first occurrence strictly after max(current due, now), so overdue stays fixed. */
    fun advance(due: DueSpec, now: Instant, device: ZoneId): DueSpec {
        val today = now.atZone(due.zoneId(device)).toLocalDate()
        val floor = maxOf(due.instant(device), now)
        return pick(due) { d ->
            if (due.time == null) d > maxOf(due.date, today) else due.copy(date = d).instant(device) > floor
        }
    }

    /** Completion anchor: the completion date plus the interval, keeping the time of day (8.2). */
    fun afterCompletion(due: DueSpec, completedAt: Instant, device: ZoneId): DueSpec {
        val done = completedAt.atZone(due.zoneId(device)).toLocalDate()
        val n = interval.toLong()
        return due.copy(
            date = when (freq) {
                Freq.DAILY -> done.plusDays(n)
                Freq.WEEKLY -> done.plusWeeks(n)
                Freq.MONTHLY -> done.plusMonths(n)
                Freq.YEARLY -> done.plusYears(n)
            },
        )
    }

    companion object {
        private const val MAX_DAY = 31
        private const val MONTHS = 12

        /** Null for anything outside the supported subset. */
        fun parse(text: String): RecurrenceRule? = runCatching {
            val parts = text.split(";").map { it.split("=", limit = 2) }
            require(parts.all { it.size == 2 } && parts.map { it[0] }.toSet().size == parts.size)
            val values = parts.associate { it[0] to it[1] }
            require(values.keys.all { it in setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYMONTH") })
            RecurrenceRule(
                freq = Freq.valueOf(values.getValue("FREQ")),
                interval = values["INTERVAL"]?.toInt() ?: 1,
                byDay = values["BYDAY"]?.split(",")?.map { requireNotNull(DayRule.parse(it)) } ?: emptyList(),
                byMonthDay = values["BYMONTHDAY"]?.toInt(),
                byMonth = values["BYMONTH"]?.toInt(),
            )
        }.getOrNull()
    }
}
