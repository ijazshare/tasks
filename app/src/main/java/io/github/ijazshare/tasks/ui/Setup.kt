/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
@file:Suppress("MatchingDeclarationName")

package io.github.ijazshare.tasks.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri

/** Live state of the three things a reminder needs (spec 8.4, 9). */
data class SetupState(val notifications: Boolean, val exactAlarms: Boolean, val batteryUnrestricted: Boolean) {
    val complete get() = notifications && exactAlarms && batteryUnrestricted

    companion object {
        fun read(context: Context) = SetupState(
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
            context.getSystemService<AlarmManager>()?.canScheduleExactAlarms() == true,
            context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) == true,
        )
    }
}

/** [tick] changes whenever the user comes back from a system settings screen. */
@Composable
fun SetupScreen(tick: Int) {
    val context = LocalContext.current
    val state = remember(tick) { SetupState.read(context) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // A denial the system will not ask about again: send the user to the switch instead.
        if (!granted) context.startActivity(notificationSettings(context))
    }
    Column(Modifier.padding(top = 8.dp)) {
        Text("Reminders only fire on time when all three are on.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Item("Notifications", state.notifications, "Allow") { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
        Item("Exact alarms", state.exactAlarms, "Open settings") {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()),
            )
        }
        Item("Battery: Unrestricted", state.batteryUnrestricted, "Open settings") {
            // Find Tasks in the list and choose Unrestricted; an optimised app may be put to sleep.
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

private fun notificationSettings(context: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

@Composable
private fun Item(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (ok) "OK" else "Needs attention",
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
        }
        if (!ok) OutlinedButton(onClick = onClick) { Text(action) }
    }
}
