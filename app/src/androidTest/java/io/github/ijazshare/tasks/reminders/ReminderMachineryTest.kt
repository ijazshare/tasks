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
        wipe()
    }

    @After fun tearDown() = runBlocking {
        wipe()
    }

    // ---- 1 ----
    @Test fun firesOnTimeExactlyOnce() = runBlocking {
        val due = in_(6)
        val t = create("fires", due)
        assertAlarm(due)
        val posted = awaitNotification(t, 15)
        assertThat(Duration.between(due, Instant.ofEpochMilli(posted.postTime)).abs().toMillis()).isLessThan(5_000)
        assertThat(posted.notification.extras.getString("android.title")).isEqualTo(t.title)
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
        press(t, "Snooze 10 min")
        awaitGone(t)
        val snoozed = reminder(t).snoozedUntil!!
        assertThat(snoozed).isAtLeast(before.plus(Duration.ofMinutes(10)).minusMillis(50))
        assertThat(snoozed).isAtMost(Instant.now().plus(Duration.ofMinutes(10)))
        assertAlarm(snoozed)
        // Same code path with a short duration so it can be watched firing.
        val before2 = Instant.now()
        repo.snooze(t.id, Duration.ofSeconds(15))
        val s2 = reminder(t).snoozedUntil!!
        assertThat(Duration.between(before2.plusSeconds(15), s2).abs().toMillis()).isLessThan(2_000)
        assertAlarm(s2)
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
        assertAlarm(null)
        delay(2_000)
        assertThat(find(t)).isNull()
    }

    // ---- 4 ----
    @Test fun tomorrowMovesDueOneDay() = runBlocking {
        val due = in_(4)
        val t = create("tomorrow", due)
        awaitNotification(t, 15)
        press(t, "Tomorrow")
        awaitGone(t)
        val expected = due.atZone(zone).plusDays(1).toInstant()
        waitFor("moved") { repo.task(t.id)?.dueDate == due.atZone(zone).toLocalDate().plusDays(1) }
        val moved = repo.task(t.id)!!
        assertThat(moved.dueTime).isEqualTo(due.atZone(zone).toLocalTime())
        assertThat(reminder(t).nextFireAt).isEqualTo(expected)
        assertThat(reminder(t).lastFiredAt).isNull()
        assertAlarm(expected)
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
        assertThat(count("SELECT COUNT(*) FROM completion_log WHERE taskId = ? AND wasRecurring = 1", t.id)).isEqualTo(1)
        assertThat(reminder(t).nextFireAt).isEqualTo(next.toInstant())
        assertAlarm(next.toInstant())
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
        assertAlarm(later)
        awaitNotification(t, 15)
        // Now edit into the past: no fire, overdue.
        nm.cancel(Notifications.id(t.id))
        val past = Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        save(t, past)
        assertThat(reminder(t).lastFiredAt).isNotNull()
        delay(4_000)
        assertThat(find(t)).isNull()
        val tomorrow = ZonedDateTime.now(zone).toLocalDate().plusDays(2)
        assertThat(repo.dated(tomorrow).first().map { it.id }).contains(t.id)
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
            "UPDATE reminder SET lastFiredAt = NULL, snoozedUntil = NULL, unseen = 0 WHERE taskId = ?",
            arrayOf(t.id),
        )
        assertThat(find(t)).isNull()
        // The same code the boot and time-changed receivers run.
        ReminderScheduler(ctx).rearm(afterBoot = true)
        val first = awaitNotification(t, 5)
        assertThat(first.notification.extras.getString("android.title")).isEqualTo("Missed: ${t.title}")
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
        assertAlarm(due)
        repo.delete(t.id)
        assertAlarm(null)
        assertThat(reminder(t).deletedAt).isNotNull()
        assertThat(find(t)).isNull()
    }

    // ---- helpers ----

    private fun in_(seconds: Long): Instant = Instant.now().plusSeconds(seconds).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)

    private suspend fun create(name: String, due: Instant, recurrence: String? = null): Task {
        val z = due.atZone(zone)
        val t = repo.blank(INBOX_ID).copy(
            title = "[test] $name",
            dueDate = z.toLocalDate(),
            dueTime = z.toLocalTime(),
            recurrence = recurrence,
        )
        repo.save(t)
        return repo.task(t.id)!!
    }

    private suspend fun save(t: Task, due: Instant) {
        val z = due.atZone(zone)
        repo.save(repo.task(t.id)!!.copy(dueDate = z.toLocalDate(), dueTime = z.toLocalTime()))
    }

    private suspend fun reminder(t: Task): Reminder =
        db.dao().remindersOf(t.id).first()

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
        db.openHelper.readableDatabase.query(sql, arrayOf(arg)).use { it.moveToFirst(); it.getInt(0) }

    /**
     * The one app alarm, as dumpsys alarm shows it, must be the earliest pending reminder. The
     * owner's own pending reminders compete, so the expectation is min(mine, theirs); [mine] null
     * means "none of mine is pending".
     */
    private suspend fun assertAlarm(mine: Instant?) {
        val others = db.dao().liveReminders()
            .filter { r -> count("SELECT COUNT(*) FROM task WHERE id = ? AND title LIKE '[test] %'", r.taskId) == 0 }
            .filter { it.lastFiredAt == null || it.snoozedUntil != null }
            .mapNotNull { it.snoozedUntil ?: it.nextFireAt }
        val expected = (others + listOfNotNull(mine)).minOrNull()
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
        val ids = mutableListOf<String>()
        w.query("SELECT id FROM task WHERE title LIKE '[test] %'").use { while (it.moveToNext()) ids += it.getString(0) }
        for (id in ids) {
            w.execSQL("DELETE FROM reminder WHERE taskId = ?", arrayOf(id))
            w.execSQL("DELETE FROM completion_log WHERE taskId = ?", arrayOf(id))
            w.execSQL("DELETE FROM task_fts WHERE taskId = ?", arrayOf(id))
            w.execSQL("DELETE FROM task WHERE id = ?", arrayOf(id))
        }
        ReminderScheduler(ctx).rearm()
    }
}
