/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
@file:UseContextualSerialization(Instant::class, LocalDate::class, LocalTime::class)

package io.github.ijazshare.tasks.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// Schema v4, spec section 5. v2 added `label.scheduleJson` and `task.missedAt` (MIGRATION_1_2); v3 adds
// `referent.listedJson`, the ids a query listed, so "which task?" survives a process death (MIGRATION_2_3); v4 adds
// `place` and `reminder.placeId`/`onLeave` for location reminders (MIGRATION_3_4).
// Ids are random strings, timestamps are epoch ms, dates and times are ISO strings. Columns carry
// the property name (camelCase); `order` is reserved in SQL, so the property is `sortOrder`.
// Everything soft-deletes through `deletedAt`.

@Entity(tableName = "project", indices = [Index("parentId", "nameKey")])
data class Project(
    @PrimaryKey val id: String,
    val name: String,
    val nameKey: String,
    val color: String?,
    val parentId: String?,
    val sortOrder: Int,
    val isInbox: Boolean,
    val isArchived: Boolean,
    val view: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

@Entity(tableName = "section", indices = [Index("projectId", "nameKey")])
data class Section(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val nameKey: String,
    val sortOrder: Int,
    val isArchived: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)

@Entity(tableName = "label", indices = [Index("nameKey")])
data class Label(
    @PrimaryKey val id: String,
    val name: String,
    val nameKey: String,
    val color: String?,
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    /** The reminder schedule this tag gives its tasks (spec 8.6); null = Regular. Added in v2. */
    val scheduleJson: String?,
)

@Serializable
@Entity(tableName = "task", indices = [Index("projectId", "completedAt", "deletedAt"), Index("dueDate")])
data class Task(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val projectId: String,
    val sectionId: String?,
    val parentId: String?,
    val sortOrder: Int,
    /** 1 = highest ... 4 = none. */
    val priority: Int,
    val dueDate: LocalDate?,
    /** Null = all-day. */
    val dueTime: LocalTime?,
    /** IANA id; null = floating. */
    val dueZone: String?,
    val recurrence: String?,
    val recurrenceAnchor: String?,
    val recurrenceText: String?,
    val deadline: LocalDate?,
    val durationMin: Int?,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val source: String,
    val sourceUtterance: String?,
    /** When an alert of this task was missed and the task not opened since (8.6 rule 8). Added in v2. */
    val missedAt: Instant?,
)

/**
 * Full-text index over task titles. Not an external-content table: `task` has a text primary
 * key, so an FTS rowid link to it is not stable (VACUUM may renumber rowids). Each row carries
 * the task id instead, and the Repository keeps the rows in step with the titles.
 */
@Fts4(notIndexed = ["taskId"])
@Entity(tableName = "task_fts")
data class TaskFts(val taskId: String, val title: String)

@Entity(tableName = "task_label", primaryKeys = ["taskId", "labelId"])
data class TaskLabel(val taskId: String, val labelId: String)

@Serializable
@Entity(tableName = "reminder", indices = [Index("nextFireAt")])
data class Reminder(
    @PrimaryKey val id: String,
    val taskId: String,
    /** AT_DUE, BEFORE_DUE, AT_LOCAL, AT_INSTANT or AT_PLACE (v4: no time, see [placeId]). */
    val kind: String,
    val offsetMin: Int?,
    val atDate: LocalDate?,
    val atTime: LocalTime?,
    val atZone: String?,
    val atInstant: Instant?,
    val nextFireAt: Instant?,
    val lastFiredAt: Instant?,
    val snoozedUntil: Instant?,
    /** Delivered and not yet swiped, opened or answered; shown once more after a reboot (8.4). */
    val unseen: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    /** AT_PLACE only: the saved place, and whether it fires on leaving rather than arriving. Added in v4. */
    val placeId: String? = null,
    @ColumnInfo(defaultValue = "0") val onLeave: Boolean = false,
)

/** A spot the owner saved from where they stood (no maps, no geocoding). Added in v4. */
@Entity(tableName = "place", indices = [Index("nameKey")])
data class Place(
    @PrimaryKey val id: String,
    val name: String,
    val nameKey: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val createdAt: Instant,
    val deletedAt: Instant?,
)

@Entity(tableName = "completion_log")
data class CompletionLog(
    @PrimaryKey val id: String,
    val taskId: String,
    val completedAt: Instant,
    val dueDateAtCompletion: LocalDate?,
    val dueTimeAtCompletion: LocalTime?,
    val dueZoneAtCompletion: String?,
    val wasRecurring: Boolean,
    val imported: Boolean,
)

/** One line of the Completed screen. */
data class Completion(val taskId: String, val title: String, val at: Instant)

@Entity(tableName = "undo_journal")
data class UndoJournal(
    @PrimaryKey val token: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val used: Boolean,
    val client: String,
    val summary: String,
    val inverseOpsJson: String,
    val touchedJson: String,
)

@Entity(tableName = "conversation")
data class Conversation(
    @PrimaryKey val id: String,
    val client: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val round: Int,
    val expects: String,
    val draftJson: String,
    val originalUtterance: String,
)

@Entity(tableName = "referent")
data class Referent(
    @PrimaryKey val client: String,
    /** Empty when the last thing was a list of several tasks (then [listedJson] has the ids). */
    val taskId: String,
    val expiresAt: Instant,
    /** JSON array of the task ids the last query listed. Added in v3. */
    val listedJson: String?,
)

@Entity(tableName = "request_log")
data class RequestLog(@PrimaryKey val requestId: String, val receivedAt: Instant, val responseJson: String)

@Entity(tableName = "import_map", primaryKeys = ["source", "externalId"])
data class ImportMap(
    val source: String,
    val externalId: String,
    val localId: String,
    val kind: String,
    val importRunId: String,
    val importedAt: Instant,
)

@Entity(tableName = "import_run")
data class ImportRun(
    @PrimaryKey val id: String,
    val source: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val summaryJson: String,
    val removedAt: Instant?,
)

@Entity(tableName = "ramble")
data class Ramble(
    @PrimaryKey val id: String,
    val createdAt: Instant,
    val source: String,
    val transcript: String,
    /** open, saved or discarded. */
    val status: String,
    val closedAt: Instant?,
)

@Entity(tableName = "ramble_draft")
data class RambleDraft(
    @PrimaryKey val id: String,
    val rambleId: String,
    val sortOrder: Int,
    val spanStart: Int,
    val spanEnd: Int,
    val parsedJson: String,
    val flagsJson: String,
    val description: String?,
    /** keep, edit or drop. */
    val state: String,
    val confirmed: Boolean,
)

@Entity(tableName = "utterance_log")
data class UtteranceLog(
    @PrimaryKey val id: String,
    val at: Instant,
    val utterance: String,
    val responseStatus: String,
    val intent: String?,
    val parseJson: String,
)

class Converters {
    @TypeConverter fun instant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter fun instant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter fun date(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter fun date(value: LocalDate?): String? = value?.toString()

    @TypeConverter fun time(value: String?): LocalTime? = value?.let(LocalTime::parse)

    @TypeConverter fun time(value: LocalTime?): String? = value?.toString()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE label ADD COLUMN scheduleJson TEXT")
        db.execSQL("ALTER TABLE task ADD COLUMN missedAt INTEGER")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE referent ADD COLUMN listedJson TEXT")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `place` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `nameKey` TEXT NOT NULL, " +
                "`lat` REAL NOT NULL, `lon` REAL NOT NULL, `radiusM` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`deletedAt` INTEGER, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_place_nameKey` ON `place` (`nameKey`)")
        db.execSQL("ALTER TABLE reminder ADD COLUMN placeId TEXT")
        db.execSQL("ALTER TABLE reminder ADD COLUMN onLeave INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        Project::class, Section::class, Label::class, Task::class, TaskFts::class, TaskLabel::class,
        Reminder::class, CompletionLog::class, UndoJournal::class, Conversation::class, Referent::class,
        RequestLog::class, ImportMap::class, ImportRun::class, Ramble::class, RambleDraft::class,
        UtteranceLog::class, Place::class,
    ],
    version = 4,
)
@TypeConverters(Converters::class)
abstract class TasksDb : RoomDatabase() {
    abstract fun dao(): TasksDao

    abstract fun places(): PlaceDao

    companion object {
        @Volatile private var instance: TasksDb? = null

        fun get(context: Context): TasksDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TasksDb::class.java, "tasks.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
