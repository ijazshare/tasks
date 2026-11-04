/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** The reminder kind of a location reminder; it has no time, only [Reminder.placeId]. */
const val AT_PLACE = "AT_PLACE"

/** One place reminder of a task: the saved place and whether it fires on leaving rather than arriving. */
data class PlaceRef(val placeId: String, val onLeave: Boolean)

@Dao
interface PlaceDao {
    @Query("SELECT * FROM place WHERE deletedAt IS NULL ORDER BY nameKey")
    fun live(): Flow<List<Place>>

    @Query("SELECT * FROM place WHERE deletedAt IS NULL ORDER BY nameKey")
    suspend fun all(): List<Place>

    @Query("SELECT * FROM place")
    suspend fun everyPlace(): List<Place>

    @Query("SELECT * FROM place WHERE nameKey = :key AND deletedAt IS NULL")
    suspend fun byKey(key: String): Place?

    @Insert
    suspend fun insert(place: Place)

    @Query("UPDATE place SET deletedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Instant)

    @Query("UPDATE reminder SET deletedAt = :at, updatedAt = :at WHERE placeId = :id AND deletedAt IS NULL")
    suspend fun dropReminders(id: String, at: Instant)

    @Query("SELECT * FROM reminder WHERE kind = 'AT_PLACE'")
    suspend fun placeReminders(): List<Reminder>
}
