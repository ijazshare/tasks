/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.reminders

import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.ijazshare.tasks.data.INBOX_ID
import io.github.ijazshare.tasks.data.Reminder
import io.github.ijazshare.tasks.data.Repository
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.data.TasksDb
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Drives the real AlarmManager, receivers and notifications on a device, in the app's own process
 * and against its real database. Tasks are titled "[test] ..." and hard-deleted afterwards; the
 * owner's tasks are only read (their reminders still count towards "the" single armed alarm).
 *
 * WARNING: `connectedDebugAndroidTest` UNINSTALLS the app afterwards, which deletes its data. On a
 * device with real tasks run it by hand instead: `./gradlew installDebug assembleDebugAndroidTest`,
 * `adb install -t` the test APK, then `adb shell am instrument -w
 * io.github.ijazshare.tasks.debug.test/androidx.test.runner.AndroidJUnitRunner`.
 */
class ReminderMachineryTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = Repository(ctx)
    private val db get() = TasksDb.get(ctx)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val zone = ZoneId.systemDefault()

    @Before fun setUp() = runBlocking {
        repo.ensureInbox()
        repo.ensureLabels()
        wipe()
    }

    @After fun tearDown() = runBlocking {
        wipe()
    }

    // ---- 1 ----
    @Test fun firesOnTimeExactlyOnce() = runBlocking {
        val due = in_(6)
        val t = create("fires", due)
        assertThat(pending(t)).isEqualTo(due)
        assertAlarm(t)
        val posted = awaitNotification(t, 15)
        assertThat(Duration.between(due, Instant.ofEpochMilli(posted.postTime)).abs().toMillis()).isLessThan(5_000)
        assertThat(posted.notification.extras.getString("android.title")).isEqualTo(t.title)
        assertThat(text(posted)).startsWith("now")
        assertThat(buttons(posted)).containsExactly("Done", "Snooze 15 min", "Next day").inOrder()
        assertThat(lastFired(t)).isNotNull()
        // No second post: the post time is not renewed, and once cleared nothing comes back.
        delay(3_000)
        assertThat(find(t)!!.postTime).isEqualTo(posted.postTime)
        nm.cancel(Notifications.id(t.id))
        delay(4_000)
        assertThat(find(t)).isNull()
        assertNoPendingOf(t)
    }

    // ---- 2 ----
    @Test fun snoozeCancelsReArmsAndFiresAgain() = runBlocking {
        val t = create("snooze", in_(4))
        awaitNotification(t, 15)
        val before = Instant.now()
        press(t, "Snooze 15 min")
        awaitGone(t)
        val snoozed = reminder(t).snoozedUntil!!
        assertThat(snoozed).isAtLeast(before.plus(Duration.ofMinutes(15)).minusMillis(50))
        assertThat(snoozed).isAtMost(Instant.now().plus(Duration.ofMinutes(15)))
        assertAlarm(t)
        // Same code path with a short duration so it can be watched firing.
        val before2 = Instant.now()
        repo.snooze(reminder(t).id, Duration.ofSeconds(15))
        val s2 = reminder(t).snoozedUntil!!
        assertThat(Duration.between(before2.plusSeconds(15), s2).abs().toMillis()).isLessThan(2_000)
        assertThat(pending(t)).isEqualTo(s2)
        assertAlarm(t)
        assertThat(find(t)).isNull()
        val again = awaitNotification(t, 30)
        assertThat(Duration.between(s2, Instant.ofEpochMilli(again.postTime)).abs().toMillis()).isLessThan(5_000)
        assertThat(reminder(t).snoozedUntil).isNull()
    }

    // ---- 3 ----
    @Test fun doneCompletesAndStopsAlarm() = runBlocking {
        val t = create("done", in_(4))
        awaitNotification(t, 15)
        press(t, "Done")
        awaitGone(t)
        waitFor("completed") { repo.task(t.id)?.completedAt != null }
        assertThat(reminder(t).deletedAt).isNotNull()
        assertAlarm(t)
        delay(2_000)
        assertThat(find(t)).isNull()
    }

    // ---- 4 ----
    @Test fun tomorrowMovesDueOneDay() = runBlocking {
        val due = in_(4)
        val t = create("tomorrow", due)
        awaitNotification(t, 15)
        press(t, "Next day")
        awaitGone(t)
        val expected = due.atZone(zone).plusDays(1).toInstant()
        waitFor("moved") { repo.task(t.id)?.dueDate == due.atZone(zone).toLocalDate().plusDays(1) }
        val moved = repo.task(t.id)!!
        assertThat(moved.dueTime).isEqualTo(due.atZone(zone).toLocalTime())
        // Tomorrow's due is a day off: its 1 d alert is now, so the 1 h alert is the next one.
        assertThat(reminders(t).map { it.offsetMin }).containsExactly(60, 10)
        assertThat(reminders(t).all { it.lastFiredAt == null }).isTrue()
        assertThat(pending(t)).isEqualTo(expected.minus(Duration.ofHours(1)))
        assertAlarm(t)
        assertThat(find(t)).isNull()
    }

    // ---- 5 ----
    @Test fun recurringDoneAdvancesAndLogs() = runBlocking {
        val due = in_(4)
        val t = create("daily", due, recurrence = "FREQ=DAILY")
        awaitNotification(t, 15)
        press(t, "Done")
        awaitGone(t)
        val next = due.atZone(zone).plusDays(1)
        waitFor("advanced") { repo.task(t.id)?.dueDate == next.toLocalDate() }
        val after = repo.task(t.id)!!
        assertThat(after.completedAt).isNull()
        assertThat(after.dueTime).isEqualTo(next.toLocalTime())
        assertThat(
            count("SELECT COUNT(*) FROM completion_log WHERE taskId = ? AND wasRecurring = 1", t.id),
        ).isEqualTo(1)
        assertThat(pending(t)).isEqualTo(next.toInstant().minus(Duration.ofHours(1)))
        assertAlarm(t)
    }

    // ---- 6 ----
    @Test fun swipeDismissMarksSeenNoRepost() = runBlocking {
        val t = create("swipe", in_(4))
        val n = awaitNotification(t, 15)
        assertThat(reminder(t).unseen).isTrue()
        n.notification.deleteIntent.send()
        waitFor("seen") { !reminder(t).unseen }
        nm.cancel(Notifications.id(t.id))
        delay(4_000)
        assertThat(find(t)).isNull()
        assertThat(reminder(t).lastFiredAt).isNotNull()
    }

    // ---- 7 ----
    @Test fun editedDueRefiresAndPastDoesNotFire() = runBlocking {
        val t = create("edit", in_(4))
        awaitNotification(t, 15)
        nm.cancel(Notifications.id(t.id))
        val later = in_(6)
        save(t, later)
        assertThat(reminder(t).lastFiredAt).isNull()
        assertThat(pending(t)).isEqualTo(later)
        assertAlarm(t)
        awaitNotification(t, 15)
        // Now edit into the past: no fire, overdue.
        nm.cancel(Notifications.id(t.id))
        val past = Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        save(t, past)
        assertThat(rows(t)).isEmpty()
        delay(4_000)
        assertThat(find(t)).isNull()
        assertThat(repo.dated().first().map { it.id }).contains(t.id)
    }

    // ---- 8 ----
    @Test fun rearmDeliversMissedOnceAndIsIdempotent() = runBlocking {
        val t = create("missed", in_(60))
        // Make it "due 5 minutes ago and never delivered", as after a phone off over its time.
        val past = Instant.now().minusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val z = past.atZone(zone)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE task SET dueDate = ?, dueTime = ? WHERE id = ?",
            arrayOf(z.toLocalDate().toString(), z.toLocalTime().toString(), t.id),
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE reminder SET lastFiredAt = NULL, snoozedUntil = NULL, unseen = 0, atInstant = ?, nextFireAt = ? " +
                "WHERE taskId = ?",
            arrayOf<Any>(past.toEpochMilli(), past.toEpochMilli(), t.id),
        )
        assertThat(find(t)).isNull()
        // The same code the boot and time-changed receivers run.
        ReminderScheduler(ctx).rearm(afterBoot = true)
        val first = awaitNotification(t, 5)
        assertThat(first.notification.extras.getString("android.title")).isEqualTo("Missed: ${t.title}")
        assertThat(repo.missed().first().map { it.id }).contains(t.id)
        ReminderScheduler(ctx).rearm(afterBoot = true)
        ReminderScheduler(ctx).rearm()
        assertThat(find(t)!!.postTime).isEqualTo(first.postTime)
        // Cleared, then re-armed: marked delivered, so it is not posted a second time.
        nm.cancel(Notifications.id(t.id))
        ReminderScheduler(ctx).rearm()
        delay(1_000)
        assertThat(find(t)).isNull()
    }

    // ---- 9 ----
    @Test fun deleteCancelsAlarm() = runBlocking {
        val due = in_(120)
        val t = create("delete", due)
        assertThat(pending(t)).isEqualTo(due.minus(Duration.ofMinutes(10)).takeIf { it > Instant.now() } ?: due)
        assertAlarm(t)
        repo.delete(t.id)
        assertAlarm(t)
        assertThat(reminder(t).deletedAt).isNotNull()
        assertThat(find(t)).isNull()
    }

    // ---- 10 ----
    @Test fun urgentNagsEveryTenMinutesUntilDoneAndSnoozeCannotSkipANag() = runBlocking {
        val t = create("nag", in_(3), urgent = true)
        val dueAt = repo.task(t.id)!!.let { Instant.from(it.dueDate!!.atTime(it.dueTime!!).atZone(zone)) }
        assertThat(rows(t, "NAG").single().atInstant).isEqualTo(dueAt.plusSeconds(600))
        val atDue = awaitNotification(t, 15)
        assertThat(text(atDue)).startsWith("now")
        assertThat(buttons(atDue)).containsExactly("Done", "Snooze 15 min", "Snooze 1 h").inOrder()
        nm.cancel(Notifications.id(t.id))
        // Ten minutes compressed to seconds: the nag row is moved to 4 s from now.
        setTime(t, "NAG", null, in_(4))
        val nag1 = awaitNotification(t, 15)
        assertThat(text(nag1)).startsWith("overdue")
        assertThat(buttons(nag1)).containsExactly("Done", "Snooze 15 min", "Snooze 1 h").inOrder()
        val armed = rows(t, "NAG").single()
        assertThat(armed.lastFiredAt).isNotNull()
        // The next nag is already armed, on the due + k * 10 min grid, at most ten minutes ahead.
        val next = armed.snoozedUntil!!
        assertThat(next).isGreaterThan(Instant.now())
        assertThat(next).isAtMost(Instant.now().plusSeconds(600))
        assertThat(Duration.between(dueAt, next).seconds % 600).isEqualTo(0)
        assertAlarm(t)
        // Snooze 1 h would land after that nag, so the nag stays as it is.
        press(t, "Snooze 1 h")
        awaitGone(t)
        waitFor("snooze handled") { !rows(t, "NAG").single().unseen }
        assertThat(rows(t, "NAG").single().snoozedUntil).isEqualTo(next)
        // And the nagging goes on until Done, whatever the hour.
        setTime(t, "NAG", null, in_(3))
        val nag2 = awaitNotification(t, 15)
        assertThat(nag2.postTime).isGreaterThan(nag1.postTime)
        press(t, "Done")
        awaitGone(t)
        waitFor("completed") { repo.task(t.id)?.completedAt != null }
        assertThat(rows(t).all { it.deletedAt != null }).isTrue()
        assertAlarm(t)
    }

    // ---- 11 ----
    @Test fun earlyAlertsShowOnlyDoneAndTheTenMinuteAlertGetsSnoozeButtons() = runBlocking {
        val t = create("early", in_(3 * 3600))
        setTime(t, "BEFORE_DUE", 60, in_(4))
        val hour = awaitNotification(t, 15)
        assertThat(text(hour)).startsWith("in 1 hour")
        assertThat(buttons(hour)).containsExactly("Done")
        nm.cancel(Notifications.id(t.id))
        setTime(t, "BEFORE_DUE", 10, in_(4))
        val ten = awaitNotification(t, 15)
        assertThat(text(ten)).startsWith("in 10 min")
        assertThat(buttons(ten)).containsExactly("Done", "Snooze 15 min", "Next day").inOrder()
    }

    // ---- 12 ----
    @Test fun aSnoozeThatWouldStackOnTheNextAlertIsReplacedByIt() = runBlocking {
        val t = create("stack", in_(3 * 3600), urgent = true)
        setTime(t, "BEFORE_DUE", 10, in_(4))
        setTime(t, "AT_DUE", null, in_(30 * 60))
        awaitNotification(t, 15)
        press(t, "Snooze 1 h")
        awaitGone(t)
        waitFor("snooze handled") { !rows(t, "BEFORE_DUE", 10).single().unseen }
        // Snooze 1 h would pass the at-due alert (in 30 min): no snooze row, the at-due alert comes first.
        assertThat(rows(t, "BEFORE_DUE", 10).single().snoozedUntil).isNull()
        assertThat(pending(t)).isEqualTo(rows(t, "AT_DUE").single().atInstant)
        assertAlarm(t)
        // Snooze 15 min short of the next alert does apply.
        setTime(t, "AT_DUE", null, in_(60 * 60))
        setTime(t, "BEFORE_DUE", 10, in_(4))
        awaitNotification(t, 15)
        press(t, "Snooze 15 min")
        awaitGone(t)
        waitFor("snoozed") { rows(t, "BEFORE_DUE", 10).single().snoozedUntil != null }
        assertAlarm(t)
    }

    // ---- 13 ----
    @Test fun tagsAndRecurrenceRederiveRowsAndKeepWhatStillMatches() = runBlocking {
        val t = create("derive", in_(10 * 86_400))
        assertThat(rows(t).map { it.offsetMin }).containsExactly(4320, 1440, 60, 10)
        val ids = rows(t).associate { it.offsetMin to it.id }
        repo.save(repo.task(t.id)!!, setOf("l_urgent"))
        assertThat(
            rows(t).map {
                it.kind
            },
        ).containsExactly("BEFORE_DUE", "BEFORE_DUE", "BEFORE_DUE", "BEFORE_DUE", "AT_DUE", "NAG")
        assertThat(rows(t).filter { it.kind == "BEFORE_DUE" }.associate { it.offsetMin to it.id }).isEqualTo(ids)
        repo.save(repo.task(t.id)!!, emptySet())
        assertThat(rows(t).map { it.kind }).containsExactly("BEFORE_DUE", "BEFORE_DUE", "BEFORE_DUE", "BEFORE_DUE")
        repo.save(
            repo.task(
                t.id,
            )!!.copy(recurrence = "FREQ=DAILY", recurrenceText = "Every day", recurrenceAnchor = "schedule"),
        )
        assertThat(rows(t).map { it.offsetMin }).containsExactly(60, 10)
        assertAlarm(t)
    }

    // ---- 14 ----
    @Test fun dateOnlyTaskAlertsAtNineAndItsNineAlertHasSnoozeButtons() = runBlocking {
        val day = ZonedDateTime.now(zone).toLocalDate().plusDays(2)
        val t = createDay("day", day)
        val nine = day.atTime(9, 0).atZone(zone).toInstant()
        assertThat(rows(t).map { it.kind to it.offsetMin }).containsExactly("BEFORE_DUE" to 1440, "AT_DUE" to null)
        assertThat(rows(t, "AT_DUE").single().atInstant).isEqualTo(nine)
        setTime(t, "AT_DUE", null, in_(4))
        val n = awaitNotification(t, 15)
        assertThat(text(n)).startsWith("now")
        assertThat(buttons(n)).containsExactly("Done", "Snooze 15 min", "Next day").inOrder()
    }

    // ---- 15 ----
    @Test fun missedAlertsCollapseToOneMissedAndTheMissedListClearsOnOpen() = runBlocking {
        val t = create("collapse", in_(3 * 3600), urgent = true)
        val now = Instant.now()
        setTime(t, "BEFORE_DUE", 60, now.minusSeconds(1800))
        setTime(t, "BEFORE_DUE", 10, now.minusSeconds(1200))
        setTime(t, "AT_DUE", null, now.minusSeconds(300))
        // The first of these rearms already delivered one; start from "phone was off".
        nm.cancel(Notifications.id(t.id))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE reminder SET lastFiredAt = NULL, snoozedUntil = NULL, unseen = 0 " +
                "WHERE taskId = ? AND kind != 'NAG'",
            arrayOf(t.id),
        )
        db.openHelper.writableDatabase.execSQL("UPDATE task SET missedAt = NULL WHERE id = ?", arrayOf(t.id))
        ReminderScheduler(ctx).rearm(afterBoot = true)
        val n = awaitNotification(t, 5)
        assertThat(n.notification.extras.getString("android.title")).isEqualTo("Missed: ${t.title}")
        assertThat(buttons(n)).containsExactly("Done")
        assertThat(nm.activeNotifications.count { it.id == Notifications.id(t.id) }).isEqualTo(1)
        assertThat(
            rows(t).filter {
                it.kind != "NAG"
            }.all { it.lastFiredAt != null && it.snoozedUntil == null },
        ).isTrue()
        assertThat(repo.missed().first().map { it.id }).contains(t.id)
        // Opening the task clears it from the Missed list.
        repo.opened(t.id)
        assertThat(repo.missed().first().map { it.id }).doesNotContain(t.id)
    }

    // ---- helpers ----

    private fun in_(seconds: Long): Instant = Instant.now().plusSeconds(
        seconds,
    ).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)

    private suspend fun create(name: String, due: Instant, recurrence: String? = null, urgent: Boolean = false): Task {
        val z = due.atZone(zone)
        val t = repo.blank(INBOX_ID).copy(
            title = "[test] $name",
            dueDate = z.toLocalDate(),
            dueTime = z.toLocalTime(),
            recurrence = recurrence,
        )
        repo.save(t, if (urgent) setOf("l_urgent") else emptySet())
        return repo.task(t.id)!!
    }

    private suspend fun createDay(name: String, day: java.time.LocalDate): Task {
        val t = repo.blank(INBOX_ID).copy(title = "[test] $name", dueDate = day)
        repo.save(t, emptySet())
        return repo.task(t.id)!!
    }

    private suspend fun rows(t: Task): List<Reminder> = db.dao().remindersOf(t.id).filter { it.deletedAt == null }

    private suspend fun rows(t: Task, kind: String, offset: Int? = null): List<Reminder> =
        rows(t).filter { it.kind == kind && it.offsetMin == offset }

    private suspend fun pendingRows(t: Task) = rows(t).filter { it.lastFiredAt == null || it.snoozedUntil != null }

    /** The earliest pending alert of [t]. */
    private suspend fun pending(t: Task): Instant? = pendingRows(t).minOfOrNull { it.snoozedUntil ?: it.atInstant!! }

    /** Moves one alert row to [at] as if never fired, then runs the same re-arm every trigger runs. */
    private suspend fun setTime(t: Task, kind: String, offset: Int?, at: Instant) {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE reminder SET atInstant = ?, nextFireAt = ?, lastFiredAt = NULL, snoozedUntil = NULL, unseen = 0 " +
                "WHERE taskId = ? AND kind = ? AND COALESCE(offsetMin, -1) = ?",
            arrayOf<Any>(at.toEpochMilli(), at.toEpochMilli(), t.id, kind, offset ?: -1),
        )
        ReminderScheduler(ctx).rearm()
    }

    private fun text(n: StatusBarNotification) = n.notification.extras.getCharSequence("android.text").toString()

    private fun buttons(n: StatusBarNotification) = n.notification.actions.orEmpty().map { it.title.toString() }

    private suspend fun save(t: Task, due: Instant) {
        val z = due.atZone(zone)
        repo.save(repo.task(t.id)!!.copy(dueDate = z.toLocalDate(), dueTime = z.toLocalTime()))
    }

    private suspend fun reminder(t: Task): Reminder = db.dao().remindersOf(t.id).first()

    private suspend fun reminders(t: Task): List<Reminder> = rows(t)

    private suspend fun lastFired(t: Task) = reminder(t).lastFiredAt

    private fun find(t: Task): StatusBarNotification? =
        nm.activeNotifications.firstOrNull { it.id == Notifications.id(t.id) }

    private suspend fun awaitNotification(t: Task, seconds: Int): StatusBarNotification {
        waitFor("notification for ${t.title}", seconds) { find(t) != null }
        return find(t)!!
    }

    private suspend fun awaitGone(t: Task) = waitFor("notification of ${t.title} gone", 10) { find(t) == null }

    private suspend fun waitFor(what: String, seconds: Int = 10, cond: suspend () -> Boolean) {
        val end = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < end) {
            if (cond()) return
            delay(200)
        }
        throw AssertionError("timed out after ${seconds}s waiting for $what")
    }

    /** Fires the real action PendingIntent of the notification, so the receiver path runs. */
    private fun press(t: Task, label: String) {
        val action = find(t)!!.notification.actions.first { it.title == label }
        action.actionIntent.send()
    }

    private suspend fun assertNoPendingOf(t: Task) {
        val r = reminder(t)
        assertThat(r.lastFiredAt).isNotNull()
        assertThat(r.snoozedUntil).isNull()
    }

    private fun count(sql: String, arg: String): Int =
        db.openHelper.readableDatabase.query(sql, arrayOf(arg)).use {
            it.moveToFirst()
            it.getInt(0)
        }

    /**
     * The one app alarm, as dumpsys alarm shows it, must be the earliest pending reminder of all
     * tasks: mine ([mine], read from the database) or the owner's, whichever is first. None
     * pending at all means no alarm.
     */
    private suspend fun assertAlarm(vararg mine: Task) {
        val ids = mine.map { it.id }.toSet()
        val pending = db.dao().liveReminders()
            .filter { it.lastFiredAt == null || it.snoozedUntil != null }
            .filter { r ->
                r.taskId in ids ||
                    count("SELECT COUNT(*) FROM task WHERE id = ? AND title LIKE '[test] %'", r.taskId) == 0
            }
            .filter { r ->
                count(
                    "SELECT COUNT(*) FROM task WHERE id = ? AND completedAt IS NULL AND deletedAt IS NULL",
                    r.taskId,
                ) ==
                    1
            }
            .mapNotNull { it.snoozedUntil ?: it.atInstant ?: it.nextFireAt }
        val expected = pending.minOrNull()
        val actual = alarms()
        if (expected == null) {
            assertThat(actual).isEmpty()
        } else {
            assertThat(actual).containsExactly(expected.toEpochMilli())
        }
    }

    private fun alarms(): List<Long> {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys alarm").let { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
            }
        val re = Regex("""Alarm\{\w+ type \d+ origWhen (\d+) [^}]*io\.github\.ijazshare\.tasks\.debug\}""")
        return re.findAll(out).map { it.groupValues[1].toLong() }.toList()
    }

    private suspend fun wipe() {
        nm.activeNotifications.forEach { n ->
            if (n.packageName == ctx.packageName && n.notification.extras.getString("android.title")
                    ?.contains("[test] ") == true
            ) {
                nm.cancel(n.id)
            }
        }
        val w = db.openHelper.writableDatabase
        w.execSQL("DELETE FROM task_label WHERE taskId IN (SELECT id FROM task WHERE title LIKE '[test] %')")
        val ids = mutableListOf<String>()
        w.query("SELECT id FROM task WHERE title LIKE '[test] %'").use {
            while (it.moveToNext()) ids += it.getString(0)
        }
        for (id in ids) {
            w.execSQL("DELETE FROM reminder WHERE taskId = ?", arrayOf(id))
            w.execSQL("DELETE FROM completion_log WHERE taskId = ?", arrayOf(id))
            w.execSQL("DELETE FROM task_fts WHERE taskId = ?", arrayOf(id))
            w.execSQL("DELETE FROM task WHERE id = ?", arrayOf(id))
        }
        ReminderScheduler(ctx).rearm()
    }
}
