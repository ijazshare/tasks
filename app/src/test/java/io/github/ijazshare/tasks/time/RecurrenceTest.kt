/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.time

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Spec 8.2 by example, then the 13.2 properties that concern the rule itself, over seeded random cases. */
class RecurrenceTest {
    private val london = ZoneId.of("Europe/London")
    private val cases = 400

    private fun rule(text: String) = checkNotNull(RecurrenceRule.parse(text)) { text }

    @Test
    fun monthEndClampsToTheLastDay() {
        val dates = rule("FREQ=MONTHLY;BYMONTHDAY=31").dates(LocalDate.of(2027, 1, 31)).take(4).toList()
        assertThat(dates).containsExactly(
            LocalDate.of(2027, 1, 31),
            LocalDate.of(2027, 2, 28),
            LocalDate.of(2027, 3, 31),
            LocalDate.of(2027, 4, 30),
        ).inOrder()
        val leap = rule("FREQ=MONTHLY;BYMONTHDAY=30").dates(LocalDate.of(2028, 1, 30)).take(2).toList()
        assertThat(leap[1]).isEqualTo(LocalDate.of(2028, 2, 29))
    }

    @Test
    fun lastDayAndOrdinalWeekdays() {
        assertThat(rule("FREQ=MONTHLY;BYMONTHDAY=-1").dates(LocalDate.of(2027, 1, 1)).take(2).last())
            .isEqualTo(LocalDate.of(2027, 2, 28))
        // First Monday of the month, then the last Friday.
        assertThat(rule("FREQ=MONTHLY;BYDAY=1MO").dates(LocalDate.of(2026, 11, 1)).first())
            .isEqualTo(LocalDate.of(2026, 11, 2))
        assertThat(rule("FREQ=MONTHLY;BYDAY=-1FR").dates(LocalDate.of(2026, 11, 1)).first())
            .isEqualTo(LocalDate.of(2026, 11, 27))
    }

    @Test
    fun everyOtherWeekKeepsItsPhase() {
        val dates = rule("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH").dates(LocalDate.of(2026, 11, 2)).take(4).toList()
        assertThat(dates).containsExactly(
            LocalDate.of(2026, 11, 2),
            LocalDate.of(2026, 11, 5),
            LocalDate.of(2026, 11, 16),
            LocalDate.of(2026, 11, 19),
        ).inOrder()
    }

    @Test
    fun completingAdvancesOneOccurrenceAndAnOverdueTaskIsNotLeftOverdue() {
        val due = DueSpec(LocalDate.of(2026, 11, 2), LocalTime.of(9, 0), null)
        val daily = rule("FREQ=DAILY")
        val now = Instant.parse("2026-11-05T12:00:00Z")
        // Overdue by three days: the next due is the first 09:00 after now, not 3 Nov.
        assertThat(daily.advance(due, now, london).date).isEqualTo(LocalDate.of(2026, 11, 6))
        // Ticked early, before it is due: exactly one occurrence further on.
        assertThat(daily.advance(due, Instant.parse("2026-11-01T12:00:00Z"), london).date)
            .isEqualTo(LocalDate.of(2026, 11, 3))
    }

    @Test
    fun completionAnchorCountsFromCompletion() {
        val due = DueSpec(LocalDate.of(2026, 11, 2), LocalTime.of(9, 0), null)
        val next = rule("FREQ=DAILY;INTERVAL=3").afterCompletion(due, Instant.parse("2026-11-10T20:00:00Z"), london)
        assertThat(next).isEqualTo(due.copy(date = LocalDate.of(2026, 11, 13)))
    }

    @Test
    fun firstOccurrenceIsTheEarliestOneAtOrAfterNow() {
        val due = DueSpec(LocalDate.of(2026, 11, 7), LocalTime.of(9, 0), null) // a Saturday
        val weekdays = rule("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
        assertThat(weekdays.first(due, Instant.parse("2026-11-01T00:00:00Z"), london).date)
            .isEqualTo(LocalDate.of(2026, 11, 9))
    }

    @Test
    fun unsupportedRulesAreRefused() {
        listOf(
            "",
            "FREQ=HOURLY",
            "FREQ=DAILY;BYDAY=MO",
            "FREQ=WEEKLY;BYDAY=1MO",
            "FREQ=MONTHLY;BYDAY=MO",
            "FREQ=DAILY;X=1",
        )
            .forEach { assertThat(RecurrenceRule.parse(it)).isNull() }
    }

    @Test
    fun property8_ruleStringRoundTrips() {
        val rnd = java.util.Random(8)
        repeat(cases) {
            val r = randomRule(rnd)
            assertThat(RecurrenceRule.parse(r.format())).isEqualTo(r)
        }
    }

    @Test
    fun property2_everyOccurrenceMatchesTheRuleInLocalDates() {
        val rnd = java.util.Random(2)
        repeat(cases) {
            val r = randomRule(rnd)
            val anchor = LocalDate.of(2000, 1, 1).plusDays(rnd.nextInt(DAYS_40Y).toLong())
            val dates = r.dates(anchor).take(30).toList()
            assertThat(dates.first()).isAtLeast(anchor)
            dates.zipWithNext().forEach { (a, b) -> assertThat(b).isGreaterThan(a) }
            dates.forEach { assertThat(matches(r, anchor, it)).isTrue() }
        }
    }

    @Test
    fun property11_weekdayRuleNeverLandsOnTheWeekend() {
        val dates = rule("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR").dates(LocalDate.of(2026, 1, 1)).take(500)
        dates.forEach { assertThat(it.dayOfWeek).isNoneOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
    }

    @Test
    fun property1_nextIsAfterNowAndMonotone() {
        val rnd = java.util.Random(1)
        repeat(cases) {
            val zone = zones.random(rnd)
            val r = randomRule(rnd)
            val now = randomInstant(rnd)
            val due = randomDue(rnd, zone, now)
            val a = r.advance(due, now, zone)
            assertThat(a.instant(zone)).isGreaterThan(now)
            assertThat(a.instant(zone)).isGreaterThan(due.instant(zone))
            // Completing the next one at its own time moves strictly forward again.
            val b = r.advance(a, a.instant(zone), zone)
            assertThat(b.instant(zone)).isGreaterThan(a.instant(zone))
        }
    }

    @Test
    fun property3_localTimeIsTheDueTimeExceptShiftedInsideAGap() {
        val rnd = java.util.Random(3)
        repeat(cases) {
            val zone = zones.random(rnd)
            val r = randomRule(rnd)
            val due = randomDue(rnd, zone, randomInstant(rnd)).let {
                it.copy(time = it.time ?: LocalTime.NOON, zone = null)
            }
            r.dates(due.date).take(10).forEach { d ->
                val local = d.atTime(due.time!!)
                val at = DueSpec(d, due.time, null).instant(zone).atZone(zone)
                val gap = zone.rules.getTransition(local)?.takeIf { it.isGap }
                if (gap == null) {
                    assertThat(at.toLocalDateTime()).isEqualTo(local)
                } else {
                    assertThat(at.toLocalDateTime()).isEqualTo(local.plus(gap.duration))
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // one branch per rule shape, mirroring the rule table
    private fun matches(r: RecurrenceRule, anchor: LocalDate, d: LocalDate): Boolean = when (r.freq) {
        Freq.DAILY -> java.time.temporal.ChronoUnit.DAYS.between(anchor, d) % r.interval == 0L

        Freq.WEEKLY -> {
            val days = r.byDay.map { it.day }.ifEmpty { listOf(anchor.dayOfWeek) }
            val monday = { x: LocalDate -> x.minusDays(x.dayOfWeek.value - 1L) }
            d.dayOfWeek in days &&
                java.time.temporal.ChronoUnit.WEEKS.between(monday(anchor), monday(d)) % r.interval == 0L
        }

        Freq.MONTHLY -> {
            val months = java.time.temporal.ChronoUnit.MONTHS.between(anchor.withDayOfMonth(1), d.withDayOfMonth(1))
            months % r.interval == 0L && if (r.byDay.isEmpty()) {
                d.dayOfMonth == dayOf(r.byMonthDay ?: anchor.dayOfMonth, d)
            } else {
                r.byDay.any { x ->
                    d.dayOfWeek == x.day &&
                        if (x.ordinal!! > 0) {
                            (d.dayOfMonth - 1) / DAYS_PER_WEEK + 1 == x.ordinal
                        } else {
                            d.plusDays(DAYS_PER_WEEK.toLong()).month != d.month
                        }
                }
            }
        }

        Freq.YEARLY ->
            (d.year - anchor.year) % r.interval == 0 && d.monthValue == (r.byMonth ?: anchor.monthValue) &&
                d.dayOfMonth == dayOf(r.byMonthDay ?: anchor.dayOfMonth, d)
    }

    private fun dayOf(wanted: Int, d: LocalDate) = if (wanted ==
        -1
    ) {
        d.lengthOfMonth()
    } else {
        minOf(wanted, d.lengthOfMonth())
    }

    private companion object {
        const val DAYS_40Y = 14_600
        const val DAYS_PER_WEEK = 7
    }
}
