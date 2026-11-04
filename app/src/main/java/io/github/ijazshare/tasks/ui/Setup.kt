/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
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
import androidx.compose.material3.HorizontalDivider
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
import io.github.ijazshare.tasks.ui.theme.HaloShape
import io.github.ijazshare.tasks.ui.theme.TasksColors

/** Live state of the three things a reminder needs (spec 8.4, 9). */
private const val NEEDS = "Needs attention"

data class SetupState(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val batteryUnrestricted: Boolean,
    /** Optional, for place reminders: not part of [complete]. */
    val location: Boolean = false,
    val locationAlways: Boolean = false,
) {
    val complete get() = notifications && exactAlarms && batteryUnrestricted

    companion object {
        fun read(context: Context) = SetupState(
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
            context.getSystemService<AlarmManager>()?.canScheduleExactAlarms() == true,
            context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) == true,
            hasFineLocation(context),
            hasBackgroundLocation(context),
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
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // "Allow all the time" is only offered in the app's settings (Android 11+), once location is allowed.
        if (hasFineLocation(context)) context.startActivity(appSettings(context))
    }
    SetupContent(
        state,
        onNotifications = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) },
        onExactAlarms = {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()),
            )
        },
        // Find Tasks in the list and choose Unrestricted; an optimised app may be put to sleep.
        onBattery = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        onLocation = {
            if (state.location) {
                context.startActivity(appSettings(context))
            } else {
                askLocation.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        },
    )
}

/** The screen without the system calls, so a screenshot test can show each state. */
@Composable
fun SetupContent(
    state: SetupState,
    onNotifications: () -> Unit,
    onExactAlarms: () -> Unit,
    onBattery: () -> Unit,
    onLocation: () -> Unit = {},
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "Reminders only fire on time when all three are on.",
            Modifier.padding(bottom = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Item("Notifications", state.notifications, "Allow", onNotifications)
        Item("Exact alarms", state.exactAlarms, "Open settings", onExactAlarms)
        Item("Battery: Unrestricted", state.batteryUnrestricted, "Open settings", onBattery)
        // Optional: only place reminders need it, so it never makes the setup incomplete.
        val allTheTime = state.location && state.locationAlways
        Item(
            "Location: Allow all the time",
            allTheTime,
            if (state.location) "Open settings" else "Allow",
            onLocation,
            if (state.location) "Optional: place reminders need \"all the time\"" else "Optional: for place reminders",
        )
    }
}

private fun appSettings(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

private fun notificationSettings(context: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

@Composable
private fun Item(label: String, ok: Boolean, action: String, onClick: () -> Unit, off: String = NEEDS) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (ok) "OK" else off,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) {
                        TasksColors.Asset
                    } else if (off == NEEDS) {
                        TasksColors.Relevant
                    } else {
                        TasksColors.Dim
                    },
                )
            }
            if (!ok) OutlinedButton(onClick = onClick, shape = HaloShape.chip) { Text(action) }
        }
        HorizontalDivider(color = TasksColors.Rule)
    }
}
