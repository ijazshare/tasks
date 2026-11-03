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
import io.github.ijazshare.tasks.time.Scheduling.Armed
import io.github.ijazshare.tasks.time.Scheduling.Kind
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Random

private val london = ZoneId.of("Europe/London")
private val newYork = ZoneId.of("America/New_York")
private val sydney = ZoneId.of("Australia/Sydney")

/** Reboot re-arm, zone change, DST gap and overlap, fire-exactly-once and "Missed" (spec 8.4, 8.5, 13.2). */
class SchedulingTest {
    private val cases = 300

    private fun armed(fireAt: String, last: String? = null, snoozed: String? = null, unseen: Boolean = false) =
        Armed("r", Instant.parse(fireAt), last?.let(Instant::parse), snoozed?.let(Instant::parse), unseen)

    @Test
    fun anAlarmThatFiresOnTimeDeliversOnceAndIsThenFinished() {
        val a = armed("2026-11-02T09:00:00Z")
        val plan = Scheduling.plan(listOf(a), Instant.parse("2026-11-02T09:00:02Z"))
        assertThat(plan.deliver).containsExactly(Scheduling.Delivery("r", Kind.ON_TIME))
        val after = a.afterDelivery(Kind.ON_TIME)
        val again = Scheduling.plan(listOf(after), Instant.parse("2026-11-02T09:00:03Z"), afterBoot = false)
        assertThat(again.deliver).isEmpty()
        assertThat(again.nextAlarm).isNull()
    }

    @Test
    fun nothingIsDeliveredBeforeItsTimeAndTheAlarmIsSetForIt() {
        val plan = Scheduling.plan(listOf(armed("2026-11-02T09:00:00Z")), Instant.parse("2026-11-02T08:59:00Z"))
        assertThat(plan.deliver).isEmpty()
        assertThat(plan.nextAlarm).isEqualTo(Instant.parse("2026-11-02T09:00:00Z"))
    }

    @Test
    fun rebootDeliversWhatPassedOnceAsMissedAndRearmsTheRest() {
        val passed = armed("2026-11-02T09:00:00Z").copy(id = "passed")
        val future = armed("2026-11-02T18:00:00Z").copy(id = "future")
        val boot = Instant.parse("2026-11-02T12:00:00Z")
        val first = Scheduling.plan(listOf(passed, future), boot, afterBoot = true)
        assertThat(first.deliver).containsExactly(Scheduling.Delivery("passed", Kind.MISSED))
        assertThat(first.nextAlarm).isEqualTo(future.fireAt)
        // A second re-arm (the process start that races the boot receiver) finds nothing new.
        val second = Scheduling.plan(listOf(passed.afterDelivery(Kind.MISSED), future), boot, afterBoot = true)
        assertThat(second.deliver.map { it.kind }).doesNotContain(Kind.MISSED)
    }

    @Test
    fun lateByAMinuteOrLessIsNotMissed() {
        val a = armed("2026-11-02T09:00:00Z")
        assertThat(Scheduling.plan(listOf(a), Instant.parse("2026-11-02T09:00:59Z")).deliver.single().kind)
            .isEqualTo(Kind.ON_TIME)
        assertThat(Scheduling.plan(listOf(a), Instant.parse("2026-11-02T09:01:01Z")).deliver.single().kind)
            .isEqualTo(Kind.MISSED)
    }

    @Test
    fun anUnansweredNotificationIsShownOnceMoreAfterBootOnly() {
        val shown = armed("2026-11-02T09:00:00Z", last = "2026-11-02T09:00:00Z", unseen = true)
        val now = Instant.parse("2026-11-02T10:00:00Z")
        assertThat(Scheduling.plan(listOf(shown), now).deliver).isEmpty()
        val boot = Scheduling.plan(listOf(shown), now, afterBoot = true)
        assertThat(boot.deliver).containsExactly(Scheduling.Delivery("r", Kind.AGAIN))
        // Showing it drops the flag, so the next boot leaves it alone; a seen one is never shown again.
        val dropped = shown.afterDelivery(Kind.AGAIN)
        assertThat(Scheduling.plan(listOf(dropped), now, afterBoot = true).deliver).isEmpty()
    }

    @Test
    fun aSnoozeFiresLaterEvenThoughTheReminderAlreadyFired() {
        val snoozed = armed("2026-11-02T09:10:00Z", last = "2026-11-02T09:00:00Z", snoozed = "2026-11-02T09:10:00Z")
        assertThat(Scheduling.plan(listOf(snoozed), Instant.parse("2026-11-02T09:05:00Z")).nextAlarm)
            .isEqualTo(Instant.parse("2026-11-02T09:10:00Z"))
        val fired = Scheduling.plan(listOf(snoozed), Instant.parse("2026-11-02T09:10:01Z")).deliver
        assertThat(fired).hasSize(1)
        val after = snoozed.afterDelivery(Kind.ON_TIME)
        assertThat(after.snoozedUntil).isNull()
        assertThat(Scheduling.plan(listOf(after), Instant.parse("2026-11-02T09:30:00Z")).deliver).isEmpty()
    }

    // ---- Zone change (8.5, property 7)

    @Test
    fun floatingKeepsItsWallTimeAndFixedKeepsItsInstantWhenTheZoneChanges() {
        val floating = DueSpec(LocalDate.of(2026, 11, 2), LocalTime.of(9, 0), null)
        val fixed = floating.copy(zone = "Europe/London")
        assertThat(floating.instant(london)).isEqualTo(Instant.parse("2026-11-02T09:00:00Z"))
        assertThat(floating.instant(newYork)).isEqualTo(Instant.parse("2026-11-02T14:00:00Z"))
        assertThat(fixed.instant(newYork)).isEqualTo(fixed.instant(london))
    }

    @Test
    fun aFloatingReminderThatHasPassedInTheNewZoneIsDeliveredOnceAsMissed() {
        val due = DueSpec(LocalDate.of(2026, 11, 2), LocalTime.of(9, 0), null)
        // Armed for 09:00 London; the phone lands in Sydney at 12:00Z, where 09:00 was last night.
        val inLondon = Armed("r", Scheduling.fireAt(due, null, london), null, null, false)
        val landed = Instant.parse("2026-11-02T12:00:00Z")
        assertThat(Scheduling.plan(listOf(inLondon), Instant.parse("2026-11-02T08:00:00Z")).nextAlarm)
            .isEqualTo(Instant.parse("2026-11-02T09:00:00Z"))
        val inSydney = inLondon.copy(fireAt = Scheduling.fireAt(due, null, sydney))
        val plan = Scheduling.plan(listOf(inSydney), landed)
        assertThat(plan.deliver).containsExactly(Scheduling.Delivery("r", Kind.MISSED))
        assertThat(Scheduling.plan(listOf(inSydney.afterDelivery(Kind.MISSED)), landed).deliver).isEmpty()
    }

    @Test
    fun aFloatingReminderThatAlreadyFiredDoesNotFireAgainInTheNewZone() {
        val due = DueSpec(LocalDate.of(2026, 11, 2), LocalTime.of(9, 0), null)
        val fired =
            Armed("r", Scheduling.fireAt(due, null, newYork), Instant.parse("2026-11-02T09:00:00Z"), null, false)
        // New York 09:00 is 14:00Z, later than the 09:00Z it already fired for.
        assertThat(Scheduling.plan(listOf(fired), Instant.parse("2026-11-02T15:00:00Z")).deliver).isEmpty()
    }

    // ---- DST (8.5)

    @Test
    fun overlapFiresOnceAtTheEarlierOffset() {
        val due = DueSpec(LocalDate.of(2026, 10, 25), LocalTime.of(1, 30), null)
        assertThat(due.instant(london)).isEqualTo(Instant.parse("2026-10-25T00:30:00Z")) // 01:30 BST
        val a = Armed("r", due.instant(london), null, null, false)
        val first = Scheduling.plan(listOf(a), Instant.parse("2026-10-25T00:30:00Z"))
        assertThat(first.deliver).hasSize(1)
        // 01:30 comes round again as GMT, an hour later: nothing.
        val second = Scheduling.plan(listOf(a.afterDelivery(Kind.ON_TIME)), Instant.parse("2026-10-25T01:30:00Z"))
        assertThat(second.deliver).isEmpty()
        assertThat(second.nextAlarm).isNull()
    }

    @Test
    fun gapFiresAtTheFirstValidInstantAfterTheGap() {
        val due = DueSpec(LocalDate.of(2027, 3, 28), LocalTime.of(1, 30), null)
        assertThat(due.instant(london).atZone(london).toLocalTime()).isEqualTo(LocalTime.of(2, 30))
        val next = RecurrenceRule(Freq.DAILY).advance(due, due.instant(london), london)
        assertThat(next.date).isEqualTo(LocalDate.of(2027, 3, 29))
        assertThat(next.instant(london).atZone(london).toLocalTime()).isEqualTo(LocalTime.of(1, 30))
    }

    @Test
    fun anInstantIsImmuneToTheClocksChanging() {
        // In 30 minutes at 01:45 BST on 2026-10-25 is 01:15 GMT.
        val now = Instant.parse("2026-10-25T00:45:00Z")
        val at = now.plus(Duration.ofMinutes(30))
        assertThat(at.atZone(london).toLocalTime()).isEqualTo(LocalTime.of(1, 15))
        assertThat(Duration.between(now, at)).isEqualTo(Duration.ofSeconds(30 * 60L))
    }

    @Test
    fun property6_aSnoozeIsExactlyNMinutesOfElapsedTime() {
        val rnd = Random(6)
        repeat(cases) {
            val now = randomInstant(rnd)
            val minutes = 1L + rnd.nextInt(MAX_SNOOZE_MIN)
            assertThat(
                Duration.between(now, now.plus(Duration.ofMinutes(minutes))).seconds,
            ).isEqualTo(minutes * SECONDS)
        }
    }

    @Test
    fun property5_tomorrowIsTheNextLocalDateEvenAcrossDstAndApiasSkippedDay() {
        val rnd = Random(5)
        val apia = ZoneId.of("Pacific/Apia")
        val skipped = Instant.parse("2011-12-30T09:00:00Z") // the night Samoa lost 30 December
        val specials = listOf(skipped, Instant.parse("2026-10-25T00:30:00Z"), Instant.parse("2027-03-28T00:30:00Z"))
        val nows = specials + List(cases) { randomInstant(rnd) }
        for (now in nows) {
            for (zone in zones + apia) {
                val tomorrow = Scheduling.tomorrow(now, zone)
                assertThat(tomorrow).isEqualTo(now.atZone(zone).toLocalDate().plusDays(1))
                // And a time on that date is still in the future, whichever way the zone's calendar folds.
                assertThat(DueSpec(tomorrow, LocalTime.of(9, 0), null).instant(zone)).isGreaterThan(now)
            }
        }
    }

    // ---- Fire exactly once (property 4) and never early (property 12), with a fake clock

    /**
     * Runs the real planner against a fake clock: wake at the alarm plus a random delivery delay,
     * deliver, complete (which advances the due, as the Repository does) and re-arm.
     * Returns what was delivered as (local due date, due instant, time of delivery).
     */
    @Suppress("LongParameterList")
    private fun simulate(
        rule: RecurrenceRule,
        start: DueSpec,
        zone: ZoneId,
        from: Instant,
        until: Instant,
        rnd: Random,
    ) =
        buildList {
            var due = start
            var now = from
            var last: Instant? = null
            while (size < MAX_FIRES) {
                val a = Armed("r", due.instant(zone), last, null, false)
                val plan = Scheduling.plan(listOf(a), now)
                if (plan.deliver.isNotEmpty()) {
                    add(Triple(due.date, a.fireAt, now))
                    due = rule.advance(due, now, zone)
                    last = null
                } else {
                    val wake = plan.nextAlarm?.takeIf { it <= until } ?: break
                    now = wake.plusMillis(rnd.nextInt(MAX_DELAY_MS).toLong())
                }
            }
        }

    @Test
    fun property4_aDailyReminderFiresExactlyOncePerLocalDateAroundEveryTransition() {
        val rnd = Random(4)
        val daily = RecurrenceRule(Freq.DAILY)
        val windows = listOf(
            Triple(london, LocalDate.of(2026, 10, 24), 3), // fall back on the 25th
            Triple(london, LocalDate.of(2027, 3, 27), 3), // spring forward on the 28th
            Triple(newYork, LocalDate.of(2026, 11, 1), 2),
            Triple(newYork, LocalDate.of(2027, 3, 14), 2),
            Triple(ZoneId.of("Australia/Lord_Howe"), LocalDate.of(2026, 10, 4), 2),
            Triple(ZoneId.of("America/Sao_Paulo"), LocalDate.of(2018, 11, 3), 2),
        )
        for ((zone, first, days) in windows) {
            repeat(cases / 10) {
                val time = LocalTime.of(rnd.nextInt(HOURS), rnd.nextInt(MINUTES))
                val start = DueSpec(first, time, null)
                val from = start.instant(zone).minusSeconds(SECONDS)
                val until = from.plus(Duration.ofDays(days.toLong())).plus(Duration.ofHours(HOURS.toLong()))
                val fired = simulate(daily, start, zone, from, until, rnd)
                val dates = fired.map { it.first }
                assertThat(dates).containsNoDuplicates()
                assertThat(dates).containsAtLeast(first, first.plusDays(1), first.plusDays(2)).inOrder()
                fired.forEach { (_, fireAt, delivered) -> assertThat(delivered).isAtLeast(fireAt) }
                fired.zipWithNext().forEach { (a, b) -> assertThat(b.second).isGreaterThan(a.second) }
            }
        }
    }

    @Test
    fun theLondonExamplesFromTheSpec() {
        val daily = RecurrenceRule(Freq.DAILY)
        val gap = simulate(
            daily,
            DueSpec(LocalDate.of(2027, 3, 27), LocalTime.of(1, 30), null),
            london,
            Instant.parse("2027-03-27T00:00:00Z"),
            Instant.parse("2027-03-30T00:00:00Z"),
            Random(1),
        )
        // 01:30 GMT on the 27th, 02:30 BST on the 28th (shifted by the gap), 01:30 BST on the 29th.
        assertThat(gap.take(3).map { it.second }).containsExactly(
            Instant.parse("2027-03-27T01:30:00Z"),
            Instant.parse("2027-03-28T01:30:00Z"),
            Instant.parse("2027-03-29T00:30:00Z"),
        ).inOrder()
        val overlap = simulate(
            daily,
            DueSpec(LocalDate.of(2026, 10, 24), LocalTime.of(1, 30), null),
            london,
            Instant.parse("2026-10-24T00:00:00Z"),
            Instant.parse("2026-10-27T00:00:00Z"),
            Random(2),
        )
        assertThat(overlap.take(3).map { it.second }).containsExactly(
            Instant.parse("2026-10-24T00:30:00Z"),
            Instant.parse("2026-10-25T00:30:00Z"), // 01:30 BST, once
            Instant.parse("2026-10-26T01:30:00Z"), // 01:30 GMT the next day
        ).inOrder()
    }

    @Test
    fun property12_noReminderFiresBeforeItsTimeUnderRandomDeliveryDelays() {
        val rnd = Random(12)
        repeat(cases) {
            val zone = zones.random(rnd)
            val rule = randomRule(rnd)
            val now = randomInstant(rnd)
            val start = rule.first(randomDue(rnd, zone, now), now, zone)
            val fired = simulate(rule, start, zone, now, now.plus(Duration.ofDays(FORTY)), rnd)
            fired.forEach { (_, fireAt, delivered) -> assertThat(delivered).isAtLeast(fireAt) }
        }
    }

    @Test
    fun aDeliveredReminderOfAWeekdayRuleNeverLandsOnTheWeekend() {
        val weekdays = RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")!!
        val start = DueSpec(LocalDate.of(2026, 10, 23), LocalTime.of(1, 30), null)
        val fired =
            simulate(
                weekdays,
                start,
                london,
                Instant.parse("2026-10-23T00:00:00Z"),
                Instant.parse("2026-11-20T00:00:00Z"),
                Random(3),
            )
        assertThat(fired).isNotEmpty()
        fired.forEach { assertThat(it.first.dayOfWeek).isNoneOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
    }

    private companion object {
        const val MAX_FIRES = 200
        const val MAX_DELAY_MS = 30_000
        const val MAX_SNOOZE_MIN = 24 * 60
        const val SECONDS = 60L
        const val HOURS = 24
        const val MINUTES = 60
        const val FORTY = 40L
    }

    @Test
    fun missedAlertsOfOneTaskCollapseToTheLatestOne() {
        val now = Instant.parse("2026-11-02T12:00:00Z")
        fun r(id: String, task: String, at: String) =
            Armed(id, Instant.parse(at), null, null, false, task)
        val plan = Scheduling.plan(
            listOf(
                r("a1", "A", "2026-11-02T09:00:00Z"),
                r("a2", "A", "2026-11-02T11:00:00Z"),
                r("a3", "A", "2026-11-02T10:00:00Z"),
                r("b1", "B", "2026-11-02T08:00:00Z"),
                r("a4", "A", "2026-11-02T13:00:00Z"),
            ),
            now,
        )
        assertThat(plan.deliver).containsExactly(
            Scheduling.Delivery("a1", Kind.SKIPPED),
            Scheduling.Delivery("a2", Kind.MISSED),
            Scheduling.Delivery("a3", Kind.SKIPPED),
            Scheduling.Delivery("b1", Kind.MISSED),
        )
        assertThat(plan.nextAlarm).isEqualTo(Instant.parse("2026-11-02T13:00:00Z"))
        val after = Armed(
            "a1",
            Instant.parse("2026-11-02T09:00:00Z"),
            null,
            null,
            false,
            "A",
        ).afterDelivery(Kind.SKIPPED)
        assertThat(after.pending).isFalse()
        assertThat(after.unseen).isFalse()
    }
}
