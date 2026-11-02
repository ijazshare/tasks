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

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Time model and delivery rules, spec sections 8.1, 8.4 and 8.5. Pure JVM: callers pass "now"
// and the device zone in, so the property tests drive it with a fake clock.

/**
 * A task's due: local date, optional time (null = all-day) and optional zone. Q3: proposed,
 * unanswered. A null zone is floating, read in the device's current zone; a zone id is fixed
 * and keeps its instant when the phone travels.
 */
data class DueSpec(val date: LocalDate, val time: LocalTime?, val zone: String?) {
    fun zoneId(device: ZoneId): ZoneId = zone?.let(ZoneId::of) ?: device

    /**
     * The instant, computed last from the local date-time (8.5). ZonedDateTime.of puts a wall
     * time in a spring-forward gap after the gap and one in a fall-back overlap at the earlier
     * offset. An all-day due counts from the start of its day.
     */
    fun instant(device: ZoneId): Instant =
        ZonedDateTime.of(date, time ?: LocalTime.MIN, zoneId(device)).toInstant()
}

object Scheduling {
    /** Absorbs clock reads within one wake-up. AlarmManager never fires an exact alarm early. */
    val TOLERANCE: Duration = Duration.ofSeconds(1)

    /** A reminder delivered later than this after its time is shown as "Missed". */
    val MISSED_AFTER: Duration = Duration.ofMinutes(1)

    /** A live reminder as the planner sees it. [fireAt] is the snooze time if snoozed, else the due instant. */
    data class Armed(
        val id: String,
        val fireAt: Instant,
        val lastFiredAt: Instant?,
        val snoozedUntil: Instant?,
        val unseen: Boolean,
    ) {
        /** Never fired for this due, or snoozed since. Fired once means done until the due changes. */
        val pending get() = lastFiredAt == null || snoozedUntil != null

        fun afterDelivery(kind: Kind) = when (kind) {
            Kind.AGAIN -> copy(unseen = false)
            else -> copy(lastFiredAt = fireAt, snoozedUntil = null, unseen = true)
        }
    }

    enum class Kind { ON_TIME, MISSED, AGAIN }

    data class Delivery(val id: String, val kind: Kind)

    data class Plan(val deliver: List<Delivery>, val nextAlarm: Instant?)

    fun fireAt(due: DueSpec, snoozedUntil: Instant?, device: ZoneId): Instant = snoozedUntil ?: due.instant(device)

    /**
     * What to show now and when to wake next. A pending reminder whose time has come is delivered
     * once (and as Missed if it is late); [afterBoot] also shows once more any delivered reminder
     * nobody has seen, because a reboot cleared its notification.
     */
    fun plan(reminders: List<Armed>, now: Instant, afterBoot: Boolean = false): Plan {
        val horizon = now.plus(TOLERANCE)
        val deliver = reminders.mapNotNull { r ->
            when {
                r.pending && r.fireAt <= horizon ->
                    Delivery(r.id, if (Duration.between(r.fireAt, now) > MISSED_AFTER) Kind.MISSED else Kind.ON_TIME)

                afterBoot && !r.pending && r.unseen -> Delivery(r.id, Kind.AGAIN)

                else -> null
            }
        }
        return Plan(deliver, reminders.filter { it.pending && it.fireAt > horizon }.minOfOrNull { it.fireAt })
    }

    /** "Tomorrow" is the next calendar date, never +24h (8.1). */
    fun tomorrow(now: Instant, zone: ZoneId): LocalDate = now.atZone(zone).toLocalDate().plusDays(1)
}
