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

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import io.github.ijazshare.tasks.data.Place
import io.github.ijazshare.tasks.data.Reminder
import io.github.ijazshare.tasks.data.TasksDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Place reminders on the platform's proximity alerts (no Play Services, no network). One alert per
 * reminder row, keyed by its PendingIntent; one-shot: [fire] marks the row fired and removes the alert.
 *
 * ponytail: Android throttles background location, so an arrival can be noticed minutes late, and a
 * proximity alert is lost on reboot until [rearm] runs. Upgrade path: a foreground service that holds
 * the location updates while a place reminder is armed.
 */
class PlaceAlerts(private val context: Context) {
    private val db get() = TasksDb.get(context)
    private val manager get() = context.getSystemService<LocationManager>()

    /** Arms every live place reminder not yet armed and disarms the rest. Run wherever time alarms are re-armed. */
    suspend fun rearm() = withContext(Dispatchers.IO) {
        val rows = db.places().placeReminders()
        if (rows.isEmpty()) return@withContext
        val places = db.places().everyPlace().associateBy { it.id }
        val open = db.dao().openTasks().map { it.id }.toSet()
        val canArm = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        for (r in rows) {
            val place = places[r.placeId]?.takeIf { it.deletedAt == null }
            if (!r.live() || r.taskId !in open || place == null) {
                disarm(r)
            } else if (canArm) {
                arm(r, place)
            } else {
                Log.w(TAG, "location permission missing: place reminder for ${place.name} not armed")
            }
        }
    }

    /** A proximity event for [reminderId]; [entering] is the platform's flag (true = arrived, false = left). */
    suspend fun fire(reminderId: String, entering: Boolean) = withContext(Dispatchers.IO) {
        val dao = db.dao()
        val raw = dao.reminder(reminderId)
        val row = raw?.takeIf { it.live() }
        val task = row?.let { dao.task(it.taskId) }?.takeIf { it.completedAt == null && it.deletedAt == null }
        val place = row?.placeId?.let { id -> db.places().everyPlace().firstOrNull { it.id == id } }
        if (row == null || task == null || place == null) {
            raw?.let { disarm(it) }
            return@withContext
        }
        if (entering == row.onLeave) return@withContext // the other edge: keep waiting
        val now = Instant.now()
        dao.upsert(listOf(row.copy(lastFiredAt = now, updatedAt = now)))
        disarm(row)
        Notifications.postPlace(context, task, row, (if (row.onLeave) "You left " else "You arrived at ") + place.name)
    }

    private fun Reminder.live() = deletedAt == null && lastFiredAt == null

    private fun pending(r: Reminder, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PlaceReceiver::class.java).setData("tasks://place/${r.id}".toUri())
            .putExtra(ReminderReceiver.EXTRA_REMINDER, r.id),
        // Mutable: the system adds the entering flag to the intent it sends.
        flags or PendingIntent.FLAG_MUTABLE,
    )

    /** Already registered (its PendingIntent exists) is left alone, so a re-arm does not restart the alert. */
    @Suppress("MissingPermission") // checked in rearm()
    private fun arm(r: Reminder, place: Place) {
        if (pending(r, PendingIntent.FLAG_NO_CREATE) != null) return
        val intent = pending(r, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            manager?.addProximityAlert(place.lat, place.lon, place.radiusM.toFloat(), -1L, intent)
        } catch (e: SecurityException) {
            Log.w(TAG, "proximity alert refused for ${place.name}", e)
            intent.cancel()
        }
    }

    private fun disarm(r: Reminder) {
        val intent = pending(r, PendingIntent.FLAG_NO_CREATE) ?: return
        manager?.removeProximityAlert(intent)
        intent.cancel()
    }

    private companion object {
        const val TAG = "Tasks"
    }
}
