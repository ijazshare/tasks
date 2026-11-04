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
import io.github.ijazshare.tasks.data.Reminder
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.time.AlertType
import io.github.ijazshare.tasks.time.Reminders
import io.github.ijazshare.tasks.time.Schedule
import io.github.ijazshare.tasks.time.Scheduling
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The reminder notification (spec 8.4, 8.6): Done, plus snooze buttons on the last alerts; tap opens the task. */
object Notifications {
    const val CHANNEL = "reminders"

    /** The task a tapped notification opens. */
    const val EXTRA_TASK = "task"

    /** Set on a Missed notification: its tap opens the Missed list. */
    const val EXTRA_MISSED = "missed"

    private val SNOOZE_BUTTONS = mapOf(
        "15m" to ("Snooze 15 min" to ReminderReceiver.ACTION_SNOOZE_15),
        "1h" to ("Snooze 1 h" to ReminderReceiver.ACTION_SNOOZE_60),
        "next_day" to ("Next day" to ReminderReceiver.ACTION_TOMORROW),
    )

    private const val TAG = "Tasks"
    private val clock: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun id(taskId: String) = taskId.hashCode()

    fun cancel(context: Context, taskId: String) = NotificationManagerCompat.from(context).cancel(id(taskId))

    @Suppress("MissingPermission") // areNotificationsEnabled() is checked first; a denial is logged.
    fun post(context: Context, task: Task, kind: Scheduling.Kind, row: Reminder, schedule: Schedule) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "reminder due but notifications are off: ${task.title}")
            return
        }
        createChannel(context)
        val title = task.title.ifEmpty { "Task" }
        val type = AlertType.valueOf(row.kind)
        val missed = kind == Scheduling.Kind.MISSED
        val said = Reminders.phrase(type, row.offsetMin)
        val dueTime = task.dueTime?.let { " · due ${clock.format(it)}" }.orEmpty()
        // A Missed notification opens the Missed list; any other opens the task.
        val open = PendingIntent.getActivity(
            context,
            id(task.id),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TASK, task.id).putExtra(EXTRA_MISSED, missed),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (missed) "Missed: $title" else title)
            .setContentText(if (missed) "missed alert, $said$dueTime" else "$said$dueTime")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            // Swiped away counts as seen; one left unanswered is shown once more after a reboot.
            .setDeleteIntent(action(context, task.id, row.id, ReminderReceiver.ACTION_DISMISS))
        // Done always; snooze buttons only at the last moment (8.6 rule 5), and never on a Missed one.
        val buttons =
            listOf("Done" to ReminderReceiver.ACTION_DONE) + if (!missed && Reminders.hasSnooze(type, row.offsetMin)) {
                schedule.snooze.mapNotNull { SNOOZE_BUTTONS[it] }
            } else {
                emptyList()
            }
        buttons.forEach { (label, act) -> builder.addAction(0, label, action(context, task.id, row.id, act)) }
        manager.notify(id(task.id), builder.build())
    }

    private fun createChannel(context: Context) {
        context.getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires when a task with a time comes due."
            },
        )
    }

    /** A place reminder: [said] is "You arrived at X" or "You left X". Done completes the task. */
    @Suppress("MissingPermission") // areNotificationsEnabled() is checked first; a denial is logged.
    fun postPlace(context: Context, task: Task, row: Reminder, said: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "place reminder due but notifications are off: ${task.title}")
            return
        }
        createChannel(context)
        val open = PendingIntent.getActivity(
            context,
            id(task.id),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TASK, task.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            id(task.id),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(task.title.ifEmpty { "Task" })
                .setContentText(said)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, "Done", action(context, task.id, row.id, ReminderReceiver.ACTION_DONE))
                .build(),
        )
    }

    /** A distinct PendingIntent per button: same receiver, different action and data. */
    private fun action(context: Context, taskId: String, reminderId: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id(taskId),
            Intent(context, ReminderReceiver::class.java).apply {
                this.action = action
                data = "tasks://task/$taskId/$action".toUri()
                putExtra(ReminderReceiver.EXTRA_TASK, taskId)
                putExtra(ReminderReceiver.EXTRA_REMINDER, reminderId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
