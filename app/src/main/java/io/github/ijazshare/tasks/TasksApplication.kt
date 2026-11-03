/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks

import android.app.Application
import android.util.Log
import io.github.ijazshare.tasks.data.Repository
import io.github.ijazshare.tasks.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TasksApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Every process start re-arms (spec 8.4): force-stop clears alarms, and this is what restores them.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = Repository(this@TasksApplication)
                repo.ensureInbox()
                repo.ensureLabels()
                repo.rederiveAll()
                ReminderScheduler(this@TasksApplication).rearm()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // startup must survive anything
                Log.e("Tasks", "startup re-arm failed", e)
            }
        }
    }
}
