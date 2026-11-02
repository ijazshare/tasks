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
import java.time.LocalTime
import java.time.ZoneId
import java.util.Random

// Seeded generators for the 13.2 property tests (spec asks for kotest-property; a seeded
// java.util.Random keeps the dependency list empty and the cases reproducible).

val zones: List<ZoneId> = listOf(
    "Europe/London",
    "America/New_York",
    "Australia/Lord_Howe",
    "Asia/Kolkata",
    "Pacific/Apia",
    "America/Sao_Paulo",
).map(ZoneId::of)

private const val FROM = 946_684_800L // 2000-01-01
private const val TO = 2_208_988_800L // 2040-01-01
private const val TWO_YEARS = 730

fun <T> List<T>.random(rnd: Random): T = this[rnd.nextInt(size)]

fun randomInstant(rnd: Random): Instant = Instant.ofEpochSecond(FROM + (rnd.nextDouble() * (TO - FROM)).toLong())

/** A due near [around], half the time within three hours of a real transition so gaps and overlaps are common. */
fun randomDue(rnd: Random, zone: ZoneId, around: Instant): DueSpec {
    val transition = zone.rules.nextTransition(around)
    val base = if (transition != null && rnd.nextBoolean()) {
        transition.dateTimeBefore.plusMinutes((rnd.nextInt(360) - 180).toLong())
    } else {
        around.atZone(zone).toLocalDateTime().plusDays((rnd.nextInt(2 * TWO_YEARS) - TWO_YEARS).toLong())
    }
    val time = if (rnd.nextInt(10) == 0) null else LocalTime.of(base.hour, base.minute)
    return DueSpec(base.toLocalDate(), time, if (rnd.nextBoolean()) null else zone.id)
}

fun randomRule(rnd: Random): RecurrenceRule {
    val interval = 1 + rnd.nextInt(3)
    val days = DayOfWeek.entries.shuffled(rnd).take(1 + rnd.nextInt(3))
    return when (rnd.nextInt(4)) {
        0 -> RecurrenceRule(Freq.DAILY, interval)

        1 -> RecurrenceRule(
            Freq.WEEKLY,
            interval,
            if (rnd.nextBoolean()) {
                days.map {
                    DayRule(null, it)
                }
            } else {
                emptyList()
            },
        )

        2 -> if (rnd.nextBoolean()) {
            RecurrenceRule(Freq.MONTHLY, interval, byMonthDay = if (rnd.nextInt(5) == 0) -1 else 1 + rnd.nextInt(31))
        } else {
            val ordinals = listOf(-1, 1, 2, 3, 4)
            RecurrenceRule(Freq.MONTHLY, interval, days.take(2).map { DayRule(ordinals.random(rnd), it) }.distinct())
        }

        else -> RecurrenceRule(Freq.YEARLY, interval, byMonth = 1 + rnd.nextInt(12), byMonthDay = 1 + rnd.nextInt(31))
    }
}

fun LocalDate.isWeekend() = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
