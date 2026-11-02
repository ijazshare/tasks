/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.data

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// Schema v1, spec section 5 (rewritten in place after M0: FTS fix, `reminder.unseen`).
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
)

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

@Entity(tableName = "reminder", indices = [Index("nextFireAt")])
data class Reminder(
    @PrimaryKey val id: String,
    val taskId: String,
    /** AT_DUE, BEFORE_DUE, AT_LOCAL or AT_INSTANT. */
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
data class Referent(@PrimaryKey val client: String, val taskId: String, val expiresAt: Instant)

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

@Database(
    entities = [
        Project::class, Section::class, Label::class, Task::class, TaskFts::class, TaskLabel::class,
        Reminder::class, CompletionLog::class, UndoJournal::class, Conversation::class, Referent::class,
        RequestLog::class, ImportMap::class, ImportRun::class, Ramble::class, RambleDraft::class,
        UtteranceLog::class,
    ],
    version = 1,
)
@TypeConverters(Converters::class)
abstract class TasksDb : RoomDatabase() {
    abstract fun dao(): TasksDao

    companion object {
        @Volatile private var instance: TasksDb? = null

        fun get(context: Context): TasksDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TasksDb::class.java, "tasks.db")
                .build()
                .also { instance = it }
        }
    }
}
