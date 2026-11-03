/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import androidx.room.withTransaction
import io.github.ijazshare.tasks.data.Reminder
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.data.TasksDb
import io.github.ijazshare.tasks.time.AlertType
import io.github.ijazshare.tasks.time.Reminders
import io.github.ijazshare.tasks.time.Schedule
import io.github.ijazshare.tasks.time.Scheduling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * One exact "next due" alarm (spec 8.4). [rearm] is the only place that delivers or arms: it
 * marks what is due as fired inside one transaction, before anything is posted (the guard
 * against a refire loop), then posts, then arms the next wake-up. Every trigger calls it: the
 * alarm itself, boot, time and zone changes, package replace, process start and each mutation.
 * Alarms live in memory and vanish on reboot; the database is the record.
 */
class ReminderScheduler(private val context: Context) {

    suspend fun rearm(afterBoot: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            val db = TasksDb.get(context)
            val dao = db.dao()
            val now = Instant.now()
            var next: Instant? = null
            val toShow = mutableListOf<Shown>()
            db.withTransaction {
                val tasks = dao.datedOpenTasks().associateBy { it.id }
                val schedules = dao.schedules(null).groupBy({ it.taskId }, { Schedule.parse(it.scheduleJson) })
                val rows = dao.liveReminders().filter {
                    it.taskId in tasks &&
                        (it.snoozedUntil ?: it.atInstant ?: it.nextFireAt) != null
                }
                val armed = rows.map {
                    Scheduling.Armed(it.id, fireAt(it), it.lastFiredAt, it.snoozedUntil, it.unseen, it.taskId)
                }
                val kinds = Scheduling.plan(armed, now, afterBoot).deliver.associate { it.id to it.kind }
                val after = rows.zip(armed).map { (r, a) ->
                    val kind = kinds[r.id] ?: return@map r to a
                    val task = tasks.getValue(r.taskId)
                    val schedule = Schedule.pick(schedules[task.id].orEmpty().filterNotNull())
                    if (kind != Scheduling.Kind.SKIPPED) toShow += Shown(task, kind, r, schedule)
                    var done = a.afterDelivery(kind)
                    // A nag is never done: after each one the next slot is armed (8.6 rule 4).
                    if (kind != Scheduling.Kind.AGAIN && r.kind == AlertType.NAG.name) {
                        val slot = resume(schedule, now)
                        done = done.copy(snoozedUntil = slot, fireAt = slot)
                    }
                    r.copy(
                        nextFireAt = done.snoozedUntil ?: a.fireAt,
                        lastFiredAt = done.lastFiredAt,
                        snoozedUntil = done.snoozedUntil,
                        unseen = done.unseen,
                    ) to done
                }
                dao.upsert(after.map { it.first }.filter { it !in rows })
                dao.markMissed(toShow.filter { it.kind == Scheduling.Kind.MISSED }.map { it.task.id }, now)
                next = Scheduling.plan(after.map { it.second }, now).nextAlarm
            }
            val showing = context.getSystemService<NotificationManager>()?.activeNotifications.orEmpty().map { it.id }
            // One notification per task: the latest alert shown.
            for (shown in toShow.groupBy {
                it.task.id
            }.values.map { g -> g.maxBy { it.row.atInstant ?: Instant.MIN } }) {
                // Already on screen (a cold start beat the alarm to it): not shown a second time.
                if (shown.kind == Scheduling.Kind.AGAIN && Notifications.id(shown.task.id) in showing) continue
                Notifications.post(context, shown.task, shown.kind, shown.row, shown.schedule)
            }
            arm(next)
        }
    }

    private fun resume(schedule: Schedule, now: Instant) = Reminders.nagResume(now, schedule.nagMin ?: NAG_MIN)

    private fun fireAt(r: Reminder) = checkNotNull(r.snoozedUntil ?: r.atInstant ?: r.nextFireAt)

    private class Shown(val task: Task, val kind: Scheduling.Kind, val row: Reminder, val schedule: Schedule)

    /** Exact if allowed, else the inexact fallback: late is recoverable, silent is not. */
    private fun arm(at: Instant?) {
        val alarms = context.getSystemService<AlarmManager>() ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (at == null) {
            alarms.cancel(pending)
            return
        }
        val millis = at.toEpochMilli()
        try {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused, falling back", e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        }
    }

    private companion object {
        const val TAG = "Tasks"
        const val NAG_MIN = 10
        val lock = Mutex()
    }
}
