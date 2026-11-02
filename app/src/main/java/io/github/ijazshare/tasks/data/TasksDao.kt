/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength")

package io.github.ijazshare.tasks.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Suppress("TooManyFunctions") // one DAO for one small schema; splitting it adds nothing
@Dao
interface TasksDao {
    @Query("SELECT * FROM task WHERE id = :id AND deletedAt IS NULL")
    suspend fun task(id: String): Task?

    @Upsert
    suspend fun upsert(task: Task)

    @Upsert
    suspend fun upsert(reminders: List<Reminder>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(project: Project): Long

    @Insert
    suspend fun insert(log: CompletionLog)

    @Query("DELETE FROM task_fts WHERE taskId = :id")
    suspend fun deleteFts(id: String)

    @Query("INSERT INTO task_fts(taskId, title) VALUES (:id, :title)")
    suspend fun insertFts(id: String, title: String)

    /** Open tasks with a due, overdue ones included, by due date. */
    @Query(
        "SELECT * FROM task WHERE completedAt IS NULL AND deletedAt IS NULL AND dueDate IS NOT NULL " +
            "ORDER BY dueDate, dueTime IS NULL, dueTime, sortOrder, createdAt",
    )
    fun dated(): Flow<List<Task>>

    /** Open tasks with no due date (the "No date" section of Upcoming). */
    @Query(
        "SELECT * FROM task WHERE completedAt IS NULL AND deletedAt IS NULL AND dueDate IS NULL " +
            "ORDER BY sortOrder, createdAt",
    )
    fun undated(): Flow<List<Task>>

    @Query(
        "SELECT * FROM task WHERE completedAt IS NULL AND deletedAt IS NULL AND missedAt IS NOT NULL " +
            "ORDER BY missedAt DESC",
    )
    fun missed(): Flow<List<Task>>

    /** Every open task, for the command handler's matching. */
    @Query(
        "SELECT * FROM task WHERE completedAt IS NULL AND deletedAt IS NULL ORDER BY dueDate IS NULL, dueDate, dueTime, createdAt",
    )
    suspend fun openTasks(): List<Task>

    @Query("SELECT * FROM section WHERE deletedAt IS NULL")
    suspend fun sections(): List<Section>

    @Query("SELECT * FROM label WHERE nameKey = :key AND deletedAt IS NULL")
    suspend fun labelByKey(key: String): Label?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM label")
    suspend fun nextLabelOrder(): Int

    @Query("SELECT * FROM label WHERE deletedAt IS NULL ORDER BY sortOrder, nameKey")
    fun labels(): Flow<List<Label>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(label: Label): Long

    @Query("SELECT * FROM task_label")
    fun taskLabels(): Flow<List<TaskLabel>>

    @Query("SELECT labelId FROM task_label WHERE taskId = :taskId")
    suspend fun labelIdsOf(taskId: String): List<String>

    @Query("DELETE FROM task_label WHERE taskId = :taskId")
    suspend fun clearTags(taskId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<TaskLabel>)

    /** The schedules of the tags of [taskId], or of every task if null. */
    @Query(
        "SELECT tl.taskId AS taskId, l.scheduleJson AS scheduleJson FROM task_label tl " +
            "JOIN label l ON l.id = tl.labelId WHERE l.deletedAt IS NULL AND (:taskId IS NULL OR tl.taskId = :taskId)",
    )
    suspend fun schedules(taskId: String?): List<TaskSchedule>

    @Query("SELECT * FROM reminder WHERE deletedAt IS NULL")
    fun reminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun reminder(id: String): Reminder?

    @Query("DELETE FROM reminder WHERE id IN (:ids)")
    suspend fun deleteReminders(ids: List<String>)

    @Query("UPDATE task SET missedAt = :at WHERE id IN (:ids)")
    suspend fun markMissed(ids: List<String>, at: Instant)

    @Query(
        "SELECT * FROM task WHERE projectId = :projectId AND completedAt IS NULL AND deletedAt IS NULL " +
            "ORDER BY dueDate IS NULL, dueDate, dueTime IS NULL, dueTime, sortOrder, createdAt",
    )
    fun inProject(projectId: String): Flow<List<Task>>

    @Query("SELECT * FROM project WHERE deletedAt IS NULL AND isArchived = 0 ORDER BY isInbox DESC, sortOrder, nameKey")
    fun projects(): Flow<List<Project>>

    @Query("SELECT * FROM project WHERE nameKey = :key AND parentId IS NULL AND deletedAt IS NULL")
    suspend fun projectByKey(key: String): Project?

    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM project")
    suspend fun nextProjectOrder(): Int

    @Query("SELECT * FROM task WHERE completedAt IS NULL AND deletedAt IS NULL AND dueDate IS NOT NULL")
    suspend fun datedOpenTasks(): List<Task>

    @Query("SELECT * FROM reminder WHERE deletedAt IS NULL")
    suspend fun liveReminders(): List<Reminder>

    @Query("SELECT * FROM reminder WHERE taskId = :taskId")
    suspend fun remindersOf(taskId: String): List<Reminder>

    @Query("UPDATE reminder SET unseen = 0 WHERE taskId = :taskId")
    suspend fun markSeen(taskId: String)

    // ---- M3: undo journal, conversations, referents, dedupe, import ----------------------------------

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun taskAny(id: String): Task?

    @Query("DELETE FROM reminder WHERE taskId = :taskId")
    suspend fun clearReminders(taskId: String)

    @Query("DELETE FROM completion_log WHERE taskId = :taskId AND completedAt >= :since")
    suspend fun deleteLogsSince(taskId: String, since: Instant)

    @Query(
        "DELETE FROM project WHERE id = :id AND NOT EXISTS (SELECT 1 FROM task WHERE projectId = :id AND deletedAt IS NULL)",
    )
    suspend fun dropProject(id: String)

    @Query("DELETE FROM task_label WHERE labelId = :id")
    suspend fun dropTagsOf(id: String)

    @Query("DELETE FROM label WHERE id = :id")
    suspend fun dropLabel(id: String)

    @Insert
    suspend fun insert(entry: UndoJournal)

    @Query("SELECT * FROM undo_journal WHERE token = :token")
    suspend fun journal(token: String): UndoJournal?

    @Query("UPDATE undo_journal SET used = 1 WHERE token = :token")
    suspend fun markUsed(token: String)

    @Query("DELETE FROM undo_journal WHERE expiresAt < :now")
    suspend fun purgeJournal(now: Instant)

    @Upsert
    suspend fun upsert(conversation: Conversation)

    @Query("SELECT * FROM conversation WHERE client = :client")
    suspend fun conversationOf(client: String): Conversation?

    @Query("DELETE FROM conversation WHERE client = :client")
    suspend fun dropConversation(client: String)

    @Query("DELETE FROM conversation WHERE expiresAt < :now")
    suspend fun purgeConversations(now: Instant)

    @Upsert
    suspend fun upsert(referent: Referent)

    @Query("SELECT * FROM referent WHERE client = :client")
    suspend fun referentOf(client: String): Referent?

    @Query("DELETE FROM referent WHERE client = :client")
    suspend fun dropReferent(client: String)

    @Query("SELECT * FROM request_log WHERE requestId = :id")
    suspend fun requestLog(id: String): RequestLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: RequestLog)

    @Query("DELETE FROM request_log WHERE receivedAt < :before")
    suspend fun purgeRequests(before: Instant)

    @Query("SELECT localId FROM import_map WHERE source = :source AND externalId = :externalId")
    suspend fun imported(source: String, externalId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ImportMap): Long

    @Query("SELECT * FROM import_run WHERE id = :id")
    suspend fun importRun(id: String): ImportRun?

    @Upsert
    suspend fun upsert(run: ImportRun)

    @Query("SELECT COUNT(*) FROM import_run WHERE source = :source AND finishedAt IS NOT NULL AND removedAt IS NULL")
    suspend fun finishedRuns(source: String): Int

    @Insert
    suspend fun insertAll(logs: List<CompletionLog>)

    @Query("SELECT * FROM project WHERE id = :id")
    suspend fun projectById(id: String): Project?

    /** Opening a task clears it from the Missed list. */
    @Query("UPDATE task SET missedAt = NULL WHERE id = :taskId")
    suspend fun clearMissed(taskId: String)
}

/** One tag's schedule for one task. */
data class TaskSchedule(val taskId: String, val scheduleJson: String?)
