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

import com.google.common.truth.Truth.assertThat
import io.github.ijazshare.tasks.time.AlertType.AT_DUE
import io.github.ijazshare.tasks.time.AlertType.BEFORE_DUE
import io.github.ijazshare.tasks.time.AlertType.NAG
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Random

private val zone = ZoneId.of("Europe/London")
private val due = DueSpec(LocalDate.of(2026, 11, 10), LocalTime.of(15, 0), null)
private val dueAt = due.instant(zone)

private fun minutesBefore(m: Long) = dueAt.minus(Duration.ofMinutes(m))

/** Tag reminder schedules, spec 8.6 rules 1 to 5. */
class RemindersTest {
    private fun upcoming(
        d: DueSpec = due,
        s: Schedule = Schedule.REGULAR,
        now: Instant,
        repeating: Boolean = false,
    ) = Reminders.upcoming(d, repeating, s, now, zone).map { Triple(it.type, it.offsetMin, it.at) }

    @Test fun regularGetsFourAlertsBeforeDueAndNothingAtOrAfter() {
        val got = upcoming(now = dueAt.minus(Duration.ofDays(10)))
        assertThat(got).containsExactly(
            Triple(BEFORE_DUE, 4320, minutesBefore(3 * 1440)),
            Triple(BEFORE_DUE, 1440, minutesBefore(1440)),
            Triple(BEFORE_DUE, 60, minutesBefore(60)),
            Triple(BEFORE_DUE, 10, minutesBefore(10)),
        )
    }

    @Test fun urgentAddsAtDueAndTheFirstNagTenMinutesLater() {
        val got = upcoming(s = Schedule.URGENT, now = dueAt.minus(Duration.ofDays(10)))
        assertThat(
            got.map {
                it.first
            },
        ).containsExactly(BEFORE_DUE, BEFORE_DUE, BEFORE_DUE, BEFORE_DUE, AT_DUE, NAG).inOrder()
        assertThat(got.last().third).isEqualTo(dueAt.plusSeconds(600))
        assertThat(got[4].third).isEqualTo(dueAt)
    }

    @Test fun shortNoticeSkipsPastAlertsSilently() {
        assertThat(upcoming(now = minutesBefore(30)).map { it.second }).containsExactly(10)
        assertThat(upcoming(now = minutesBefore(90)).map { it.second }).containsExactly(60, 10).inOrder()
        assertThat(
            upcoming(now = minutesBefore(2 * 1440 - 1)).map {
                it.second
            },
        ).containsExactly(1440, 60, 10).inOrder()
    }

    @Test fun ifNoAlertIsLeftItFiresOnceAtDue() {
        assertThat(upcoming(now = minutesBefore(5))).containsExactly(Triple(AT_DUE, null, dueAt))
        assertThat(upcoming(now = minutesBefore(5), repeating = true)).containsExactly(Triple(AT_DUE, null, dueAt))
        // The 10 min alert is still ahead at 12 min: that one fires, and due does not.
        assertThat(upcoming(now = minutesBefore(12)).map { it.first }).containsExactly(BEFORE_DUE)
    }

    @Test fun anAlreadyPastDueFiresNothingUnlessUrgent() {
        val late = dueAt.plusSeconds(60)
        assertThat(upcoming(now = late)).isEmpty()
        val urgent = upcoming(s = Schedule.URGENT, now = late)
        assertThat(urgent.map { it.first }).containsExactly(NAG)
        assertThat(urgent.single().third).isEqualTo(dueAt.plusSeconds(600))
    }

    @Test fun dateOnlyAlertsAtNineThreeDaysOneDayAndOnTheDay() {
        val day = DueSpec(LocalDate.of(2026, 11, 10), null, null)
        val nine = { d: Int -> LocalDate.of(2026, 11, d).atTime(9, 0).atZone(zone).toInstant() }
        val got = upcoming(d = day, now = nine(1))
        assertThat(got).containsExactly(
            Triple(BEFORE_DUE, 4320, nine(7)),
            Triple(BEFORE_DUE, 1440, nine(9)),
            Triple(AT_DUE, null, nine(10)),
        ).inOrder()
        // Created after 09:00 on the day: nothing is left, and its "due" (09:00) has passed.
        assertThat(upcoming(d = day, now = nine(10).plusSeconds(1))).isEmpty()
        // Created at 20:00 the day before: only the day-of alert remains.
        assertThat(upcoming(d = day, now = nine(9).plusSeconds(11 * 3600)).map { it.first }).containsExactly(AT_DUE)
        // Urgent: the 09:00 alert is its at-due alert, and the nag follows it.
        val urgent = upcoming(d = day, s = Schedule.URGENT, now = nine(1))
        assertThat(urgent.map { it.first }).containsExactly(BEFORE_DUE, BEFORE_DUE, AT_DUE, NAG).inOrder()
        assertThat(urgent.last().third).isEqualTo(nine(10).plusSeconds(600))
    }

    @Test fun repeatingTasksGetOnlyOneHourAndTenMinutesPlusUrgentDueAndNag() {
        val far = dueAt.minus(Duration.ofDays(10))
        assertThat(upcoming(now = far, repeating = true).map { it.second }).containsExactly(60, 10).inOrder()
        val urgent = upcoming(s = Schedule.URGENT, now = far, repeating = true)
        assertThat(urgent.map { it.first }).containsExactly(BEFORE_DUE, BEFORE_DUE, AT_DUE, NAG).inOrder()
        val day = DueSpec(due.date, null, null)
        assertThat(upcoming(d = day, now = far, repeating = true).map { it.first }).containsExactly(AT_DUE)
    }

    @Test fun nagsRunEveryTenMinutesOnTheDueGridWithNoQuietHours() {
        val rnd = Random(8)
        repeat(300) {
            val now = dueAt.plusSeconds((rnd.nextInt(3 * 86_400) - 3600).toLong()) // includes 03:00
            val next = Reminders.nextNag(dueAt, 10, now)
            assertThat(next).isGreaterThan(now)
            assertThat(next).isAtMost(maxOf(now, dueAt).plusSeconds(600))
            assertThat(Duration.between(dueAt, next).seconds % 600).isEqualTo(0)
            assertThat(next).isAtLeast(dueAt.plusSeconds(600))
        }
        // Whatever the hour, an urgent task keeps one nag armed.
        val night = ZoneId.of("UTC").let {
            due.copy(date = LocalDate.of(2026, 11, 10)).instant(it).plusSeconds(12 * 3600)
        }
        assertThat(upcoming(s = Schedule.URGENT, now = night).map { it.first }).containsExactly(NAG)
    }

    @Test fun snoozeButtonsOnlyOnTenMinuteDueNagAndDateOnlyNineAlerts() {
        listOf(4320, 1440, 60).forEach { assertThat(Reminders.hasSnooze(BEFORE_DUE, it)).isFalse() }
        assertThat(Reminders.hasSnooze(BEFORE_DUE, 10)).isTrue()
        assertThat(Reminders.hasSnooze(AT_DUE, null)).isTrue()
        assertThat(Reminders.hasSnooze(NAG, null)).isTrue()
    }

    @Test fun aSnoozeLandingOnOrAfterTheNextAlertIsReplacedByIt() {
        val now = Instant.parse("2026-11-10T12:00:00Z")
        val hour = Duration.ofHours(1)
        // Urgent, 1 h alert: Snooze 1 h would be the at-due alert itself.
        assertThat(Reminders.snoozeUntil(now, hour, listOf(now.plus(hour)))).isNull()
        assertThat(Reminders.snoozeUntil(now, hour, listOf(now.plusSeconds(3599)))).isNull()
        assertThat(Reminders.snoozeUntil(now, hour, listOf(now.plusSeconds(3601)))).isEqualTo(now.plus(hour))
        assertThat(Reminders.snoozeUntil(now, hour, emptyList())).isEqualTo(now.plus(hour))
        assertThat(Reminders.snoozeUntil(now, hour, listOf(now.minusSeconds(5)))).isEqualTo(now.plus(hour))
        // A nag every ten minutes always wins over a 15 min or 1 h snooze.
        assertThat(Reminders.snoozeUntil(now, Duration.ofMinutes(15), listOf(now.plusSeconds(600)))).isNull()
    }

    @Test fun everyDerivedAlertIsAheadOfNowAndUniquePerKind() {
        val rnd = Random(86)
        repeat(500) {
            val z = zones.random(rnd)
            val now = randomInstant(rnd)
            val d = randomDue(rnd, z, now)
            val s = if (rnd.nextBoolean()) Schedule.URGENT else Schedule.REGULAR
            val repeating = rnd.nextBoolean()
            val got = Reminders.upcoming(d, repeating, s, now, z)
            assertThat(got.all { it.at > now }).isTrue()
            assertThat(got.map { it.type to it.offsetMin }.toSet()).hasSize(got.size)
            assertThat(got.count { it.type == NAG }).isEqualTo(if (s.nagMin != null) 1 else 0)
            assertThat(
                got.none {
                    it.type == AT_DUE
                } || s.nagMin != null || d.time == null || got.all { a -> a.type != BEFORE_DUE },
            )
                .isTrue()
            if (repeating) {
                assertThat(
                    got.filter {
                        it.type == BEFORE_DUE
                    }.all { it.offsetMin in listOf(60, 10) },
                ).isTrue()
            }
        }
    }

    @Test fun mostUrgentTagWinsAndUntaggedIsRegular() {
        assertThat(Schedule.pick(emptyList())).isEqualTo(Schedule.REGULAR)
        assertThat(
            Schedule.pick(listOf(Schedule.REGULAR, Schedule.URGENT, Schedule.REGULAR)),
        ).isEqualTo(Schedule.URGENT)
        assertThat(Schedule.pick(listOf(Schedule.URGENT, Schedule.REGULAR))).isEqualTo(Schedule.URGENT)
        assertThat(Schedule.parse(Schedule.URGENT.toJson())).isEqualTo(Schedule.URGENT)
        assertThat(Schedule.parse("not json")).isNull()
    }

    @Test fun notificationTextSaysWhichAlert() {
        assertThat(Reminders.phrase(BEFORE_DUE, 4320)).isEqualTo("in 3 days")
        assertThat(Reminders.phrase(BEFORE_DUE, 1440)).isEqualTo("in 1 day")
        assertThat(Reminders.phrase(BEFORE_DUE, 60)).isEqualTo("in 1 hour")
        assertThat(Reminders.phrase(BEFORE_DUE, 10)).isEqualTo("in 10 min")
        assertThat(Reminders.phrase(AT_DUE, null)).isEqualTo("now")
        assertThat(Reminders.phrase(NAG, null)).isEqualTo("overdue")
    }

    @Test fun candidatesAreStableSoRowsKeepTheirStateWhenOnlyTagsChange() {
        val a = Reminders.candidates(due, false, Schedule.REGULAR, zone)
        val b = Reminders.candidates(due, false, Schedule.URGENT, zone)
        assertThat(b.map { it.copy() }).containsAtLeastElementsIn(a)
    }
}
