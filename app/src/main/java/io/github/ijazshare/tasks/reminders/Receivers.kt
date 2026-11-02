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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.ijazshare.tasks.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.TimeZone

private const val TAG = "Tasks"
private const val SNOOZE_SHORT_MIN = 15L
private const val SNOOZE_LONG_MIN = 60L

/** A receiver is torn down when onReceive returns unless it holds goAsync; nothing here may throw. */
private fun BroadcastReceiver.async(work: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            work()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // a receiver must never crash the process
            Log.e(TAG, "receiver work failed", e)
        } finally {
            pending.finish()
        }
    }
}

/** The alarm itself, and the notification buttons. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_TASK)
        val reminder = intent.getStringExtra(EXTRA_REMINDER)
        async {
            val repo = Repository(context)
            when (intent.action) {
                ACTION_DONE -> id?.let { repo.complete(it) }
                ACTION_SNOOZE_15 -> reminder?.let { repo.snooze(it, Duration.ofMinutes(SNOOZE_SHORT_MIN)) }
                ACTION_SNOOZE_60 -> reminder?.let { repo.snooze(it, Duration.ofMinutes(SNOOZE_LONG_MIN)) }
                ACTION_TOMORROW -> id?.let { repo.tomorrow(it) }
                ACTION_DISMISS -> id?.let { repo.markSeen(it) }
                else -> ReminderScheduler(context).rearm()
            }
            if (id != null && intent.action != ACTION_DISMISS) Notifications.cancel(context, id)
        }
    }

    companion object {
        const val EXTRA_TASK = "task"
        const val EXTRA_REMINDER = "reminder"
        const val ACTION_FIRE = "io.github.ijazshare.tasks.REMINDER_FIRE"
        const val ACTION_DONE = "io.github.ijazshare.tasks.REMINDER_DONE"
        const val ACTION_SNOOZE_15 = "io.github.ijazshare.tasks.REMINDER_SNOOZE_15"
        const val ACTION_SNOOZE_60 = "io.github.ijazshare.tasks.REMINDER_SNOOZE_60"
        const val ACTION_TOMORROW = "io.github.ijazshare.tasks.REMINDER_TOMORROW"
        const val ACTION_DISMISS = "io.github.ijazshare.tasks.REMINDER_DISMISS"
    }
}

/**
 * Boot, time change, zone change, package replace and a changed exact-alarm permission all do
 * the same thing: rebuild the alarm from the database. After boot a delivered reminder nobody
 * saw is shown once more, and anything that passed while the phone was off is delivered as Missed.
 */
class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The process caches its default zone; without this a zone change re-resolves in the old zone.
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) TimeZone.setDefault(null)
        async {
            // A zone or clock change moves floating dues; rows are derived again before the alarm is.
            Repository(context).rederiveAll()
            ReminderScheduler(context).rearm(afterBoot = intent.action == Intent.ACTION_BOOT_COMPLETED)
        }
    }
}
