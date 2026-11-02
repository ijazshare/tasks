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

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.getSystemService
import androidx.room.withTransaction
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.data.TasksDb
import io.github.ijazshare.tasks.data.due
import io.github.ijazshare.tasks.time.Scheduling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

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
            val zone = ZoneId.systemDefault()
            var next: Instant? = null
            val toShow = mutableListOf<Triple<Task, Scheduling.Kind, Instant>>()
            db.withTransaction {
                val tasks = dao.timedOpenTasks().associateBy { it.id }
                val armed = dao.liveReminders().mapNotNull { r ->
                    val task = tasks[r.taskId] ?: return@mapNotNull null
                    val at = Scheduling.fireAt(checkNotNull(task.due()), r.snoozedUntil, zone)
                    Triple(r, task, Scheduling.Armed(r.id, at, r.lastFiredAt, r.snoozedUntil, r.unseen))
                }
                val plan = Scheduling.plan(armed.map { it.third }, now, afterBoot)
                val kinds = plan.deliver.associate { it.id to it.kind }
                val rows = armed.mapNotNull { (r, task, a) ->
                    val after = kinds[r.id]?.let { kind ->
                        toShow += Triple(task, kind, a.fireAt)
                        a.afterDelivery(kind)
                    } ?: a
                    r.copy(
                        nextFireAt = a.fireAt,
                        lastFiredAt = after.lastFiredAt,
                        snoozedUntil = after.snoozedUntil,
                        unseen = after.unseen,
                    ).takeIf { it != r }
                }
                dao.upsert(rows)
                next = plan.nextAlarm
            }
            val showing = context.getSystemService<NotificationManager>()?.activeNotifications.orEmpty().map { it.id }
            for ((task, kind, at) in toShow) {
                // Already on screen (a cold start beat the alarm to it): not shown a second time.
                if (Notifications.id(task.id) !in showing) Notifications.post(context, task, kind, at, zone)
            }
            arm(next)
        }
    }

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
        val lock = Mutex()
    }
}
