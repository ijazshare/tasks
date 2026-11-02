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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Tag reminder schedules, spec section 8.6. Pure JVM: which alerts a task gets, and what they say.

/** A schedule as stored in `label.scheduleJson`. [snooze] holds "15m", "1h" or "next_day". */
@Serializable
data class Schedule(
    val offsets: List<Int>,
    @SerialName("at_due") val atDue: Boolean,
    @SerialName("nag_min") val nagMin: Int?,
    val snooze: List<String>,
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    private val rank get() = (if (nagMin != null) 2 else 0) + (if (atDue) 1 else 0)

    companion object {
        val REGULAR = Schedule(listOf(DAYS_3, DAYS_1, HOUR_1, MIN_10), false, null, listOf("15m", "next_day"))
        val URGENT = Schedule(listOf(DAYS_3, DAYS_1, HOUR_1, MIN_10), true, NAG_MIN, listOf("15m", "1h"))

        fun parse(json: String?): Schedule? = json?.let {
            runCatching { Json.decodeFromString(serializer(), it) }.getOrNull()
        }

        /** The most urgent of a task's tag schedules; none (an untagged task) means Regular. */
        fun pick(all: List<Schedule>): Schedule = all.maxByOrNull { it.rank } ?: REGULAR
    }
}

/** Stored in `reminder.kind`; the names are the spec's (section 5) plus NAG. */
enum class AlertType { BEFORE_DUE, AT_DUE, NAG }

data class Alert(val type: AlertType, val offsetMin: Int?, val at: Instant)

private const val DAY = 1440
private const val DAYS_3 = 3 * DAY
private const val DAYS_1 = DAY
private const val HOUR_1 = 60
private const val MIN_10 = 10
private const val NAG_MIN = 10
private const val MILLIS_PER_MIN = 60_000L

object Reminders {
    /** A date-only task's "task time" (8.6 rule 2). */
    val DATE_ONLY_TIME: LocalTime = LocalTime.of(9, 0)

    /** Only this close to the task do the snooze buttons appear (8.6 rule 5). */
    private const val SNOOZE_WITHIN_MIN = MIN_10

    /** The moment a task counts as due: its time, or 09:00 on its day. */
    fun dueInstant(due: DueSpec, device: ZoneId): Instant = ZonedDateTime.of(
        due.date,
        due.time ?: DATE_ONLY_TIME,
        due.zoneId(device),
    ).toInstant()

    /**
     * Every alert a task can ever show, past ones included; this is what existing rows are
     * matched against. AT_DUE is always listed (it is only armed where the schedule or a date-only
     * due wants it), and so is NAG's first slot.
     */
    fun candidates(due: DueSpec, repeating: Boolean, s: Schedule, device: ZoneId): List<Alert> {
        val dateOnly = due.time == null
        val zone = due.zoneId(device)
        val at = dueInstant(due, device)
        val offsets = s.offsets.filter {
            when {
                dateOnly -> it % DAY == 0 && !repeating
                else -> !repeating || it == HOUR_1 || it == MIN_10
            }
        }
        val before = offsets.map {
            val alertAt = if (it % DAY == 0) {
                ZonedDateTime.of(due.date, due.time ?: DATE_ONLY_TIME, zone).minusDays((it / DAY).toLong()).toInstant()
            } else {
                at.minus(Duration.ofMinutes(it.toLong()))
            }
            Alert(AlertType.BEFORE_DUE, it, alertAt)
        }
        val nag = s.nagMin?.let { Alert(AlertType.NAG, null, at.plus(Duration.ofMinutes(it.toLong()))) }
        return before + Alert(AlertType.AT_DUE, null, at) + listOfNotNull(nag)
    }

    /** What to arm for a task as of [now]: rules 1 and 2 on top of [candidates]. */
    fun upcoming(due: DueSpec, repeating: Boolean, s: Schedule, now: Instant, device: ZoneId): List<Alert> {
        val all = candidates(due, repeating, s, device)
        val atDue = all.first { it.type == AlertType.AT_DUE }
        val wanted = all.filter {
            it.type == AlertType.BEFORE_DUE || (it.type == AlertType.AT_DUE && (s.atDue || due.time == null))
        }.filter { it.at > now }
        // Rule 1: nothing left to warn with, so it fires once at due.
        val base = wanted.ifEmpty { listOf(atDue).filter { it.at > now } }
        val nag = s.nagMin?.let { Alert(AlertType.NAG, null, nextNag(atDue.at, it, now)) }
        return base + listOfNotNull(nag)
    }

    /** The first nag slot after [now]: due + k * [nagMin], k >= 1. */
    fun nextNag(dueAt: Instant, nagMin: Int, now: Instant): Instant {
        val step = nagMin * MILLIS_PER_MIN
        val k = maxOf(1L, Math.floorDiv(now.toEpochMilli() - dueAt.toEpochMilli(), step) + 1)
        return dueAt.plusMillis(k * step)
    }

    fun hasSnooze(type: AlertType, offsetMin: Int?): Boolean =
        type != AlertType.BEFORE_DUE || offsetMin!! <= SNOOZE_WITHIN_MIN

    /**
     * When a snooze of [length] ends, or null if it would land on or after the next pending alert
     * ([others]): that alert then simply comes first and two never stack (rule 5).
     */
    fun snoozeUntil(now: Instant, length: Duration, others: List<Instant>): Instant? {
        val target = now.plus(length)
        val next = others.filter { it > now }.minOrNull()
        return if (next != null && target >= next) null else target
    }

    fun phrase(type: AlertType, offsetMin: Int?): String = when (type) {
        AlertType.AT_DUE -> "now"
        AlertType.NAG -> "overdue"
        AlertType.BEFORE_DUE -> before(offsetMin!!)
    }

    private fun before(min: Int) = when {
        min % DAY == 0 -> "in ${min / DAY} ${if (min == DAY) "day" else "days"}"
        min == HOUR_1 -> "in 1 hour"
        min % HOUR_1 == 0 -> "in ${min / HOUR_1} hours"
        else -> "in $min min"
    }
}
