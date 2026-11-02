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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import io.github.ijazshare.tasks.MainActivity
import io.github.ijazshare.tasks.R
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.time.Scheduling
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The reminder notification (spec 8.4): Done, Snooze 10 min, Snooze 1 h, Tomorrow; tap opens the task. */
object Notifications {
    const val CHANNEL = "reminders"

    /** The task a tapped notification opens. */
    const val EXTRA_TASK = "task"

    private const val TAG = "Tasks"
    private val clock: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun id(taskId: String) = taskId.hashCode()

    fun cancel(context: Context, taskId: String) = NotificationManagerCompat.from(context).cancel(id(taskId))

    @Suppress("MissingPermission") // areNotificationsEnabled() is checked first; a denial is logged.
    fun post(context: Context, task: Task, kind: Scheduling.Kind, at: Instant, zone: ZoneId) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "reminder due but notifications are off: ${task.title}")
            return
        }
        context.getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires when a task with a time comes due."
            },
        )
        val title = task.title.ifEmpty { "Task" }
        val time = clock.format(at.atZone(zone))
        val open = PendingIntent.getActivity(
            context,
            id(task.id),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TASK, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (kind == Scheduling.Kind.MISSED) "Missed: $title" else title)
            .setContentText(if (kind == Scheduling.Kind.MISSED) "was due $time" else time)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            // Swiped away counts as seen; one left unanswered is shown once more after a reboot.
            .setDeleteIntent(action(context, task.id, ReminderReceiver.ACTION_DISMISS))
        listOf(
            "Done" to ReminderReceiver.ACTION_DONE,
            "Snooze 10 min" to ReminderReceiver.ACTION_SNOOZE_10,
            "Snooze 1 h" to ReminderReceiver.ACTION_SNOOZE_60,
            "Tomorrow" to ReminderReceiver.ACTION_TOMORROW,
        ).forEach { (label, act) -> builder.addAction(0, label, action(context, task.id, act)) }
        manager.notify(id(task.id), builder.build())
    }

    /** A distinct PendingIntent per button: same receiver, different action and data. */
    private fun action(context: Context, taskId: String, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id(taskId),
        Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            data = "tasks://task/$taskId/$action".toUri()
            putExtra(ReminderReceiver.EXTRA_TASK, taskId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
