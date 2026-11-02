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
import androidx.room.withTransaction
import io.github.ijazshare.tasks.reminders.ReminderScheduler
import io.github.ijazshare.tasks.time.DueSpec
import io.github.ijazshare.tasks.time.RecurrenceRule
import io.github.ijazshare.tasks.time.Scheduling
import kotlinx.coroutines.flow.Flow
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val INBOX_ID = "p_inbox00000"

private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
private const val ID_LENGTH = 10
private val random = SecureRandom()

/** Spec section 5: a prefix plus 10 base62 characters. */
fun newId(prefix: String) = prefix + String(CharArray(ID_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

fun Task.due(): DueSpec? = dueDate?.let { DueSpec(it, dueTime, dueZone) }

/**
 * Every write goes through here, inside one Room transaction, and ends by re-arming the alarm.
 * (The undo-journal row each mutation will also write arrives with M3.)
 */
class Repository(private val context: Context) {
    private val db get() = TasksDb.get(context)
    private val dao get() = db.dao()
    private val scheduler get() = ReminderScheduler(context)

    fun dated(until: LocalDate): Flow<List<Task>> = dao.dated(until)

    fun inProject(projectId: String): Flow<List<Task>> = dao.inProject(projectId)

    fun projects(): Flow<List<Project>> = dao.projects()

    suspend fun task(id: String): Task? = dao.task(id)

    /** Idempotent: a fixed id and INSERT-or-ignore, so a second run or a race adds nothing. */
    suspend fun ensureInbox() {
        val now = Instant.now()
        dao.insert(
            Project(INBOX_ID, "Inbox", "inbox", null, null, 0, true, false, "list", now, now, null),
        )
    }

    /** A blank task in [projectId]; [save] gives it its first write. */
    fun blank(projectId: String): Task {
        val now = Instant.now()
        return Task(
            newId("t_"), "", "", projectId, null, null, 0, PRIORITY_NONE, null, null, null, null, null, null,
            null, null, null, now, now, null, "typed", null,
        )
    }

    /** Returns the project's id; an existing project with the same name is reused. */
    suspend fun addProject(name: String): String? = db.withTransaction {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return@withTransaction null
        dao.projectByKey(key)?.let { return@withTransaction it.id }
        val now = Instant.now()
        val id = newId("p_")
        dao.insert(
            Project(id, name.trim(), key, null, null, dao.nextProjectOrder(), false, false, "list", now, now, null),
        )
        id
    }

    /**
     * Creates or updates a task. A recurring task whose date, time or rule changed gets its first
     * occurrence at or after now (8.2). A timed task has one at-due reminder (D3), reset when its
     * due changes. A reminder born in the past counts as fired: it is overdue, not "missed".
     */
    suspend fun save(task: Task) {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        db.withTransaction {
            val old = dao.task(task.id)
            var t = task.copy(title = task.title.trim(), updatedAt = now)
            if (t.dueDate == null || t.dueTime == null) t = t.copy(dueTime = null, dueZone = null)
            if (t.dueDate == null) t = t.copy(recurrence = null)
            if (t.recurrence == null) t = t.copy(recurrenceText = null, recurrenceAnchor = null)
            val changed = old == null || old.dueDate != t.dueDate || old.dueTime != t.dueTime ||
                old.dueZone != t.dueZone || old.recurrence != t.recurrence
            val rule = t.recurrence?.let(RecurrenceRule::parse)
            if (changed && rule != null) {
                val first = rule.first(checkNotNull(t.due()), now, zone)
                t = t.copy(dueDate = first.date)
            }
            dao.upsert(t)
            dao.deleteFts(t.id)
            dao.insertFts(t.id, t.title)
            if (changed) resetReminder(t, now, zone)
        }
        scheduler.rearm()
    }

    /**
     * Completing a recurring task advances it by one occurrence (8.2) and logs the completion;
     * anything else is completed and its reminder stops.
     */
    suspend fun complete(id: String, forever: Boolean = false) {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        db.withTransaction {
            val t = dao.task(id) ?: return@withTransaction
            val due = t.due()
            val rule = t.recurrence?.let(RecurrenceRule::parse)
            if (rule == null || due == null || forever) {
                dao.upsert(t.copy(completedAt = now, updatedAt = now))
                softDeleteReminders(id, now)
                return@withTransaction
            }
            dao.insert(CompletionLog(newId("c_"), id, now, due.date, due.time, due.zone, true, false))
            val next = if (t.recurrenceAnchor == "completion") {
                rule.afterCompletion(due, now, zone)
            } else {
                rule.advance(due, now, zone)
            }
            val moved = t.copy(dueDate = next.date, updatedAt = now)
            dao.upsert(moved)
            resetReminder(moved, now, zone)
        }
        scheduler.rearm()
    }

    /** Q16: proposed, unanswered. Deleting a recurring task deletes the whole series. */
    suspend fun delete(id: String) {
        val now = Instant.now()
        db.withTransaction {
            val t = dao.task(id) ?: return@withTransaction
            dao.upsert(t.copy(deletedAt = now, updatedAt = now))
            dao.deleteFts(id)
            softDeleteReminders(id, now)
        }
        scheduler.rearm()
    }

    suspend fun snooze(id: String, duration: Duration) {
        val now = Instant.now()
        db.withTransaction {
            dao.remindersOf(id).filter { it.deletedAt == null }.forEach {
                dao.upsert(listOf(it.copy(snoozedUntil = now.plus(duration), unseen = false, updatedAt = now)))
            }
        }
        scheduler.rearm()
    }

    /** Same time tomorrow, by the floating or fixed rule of the task (8.4). */
    suspend fun tomorrow(id: String) {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        db.withTransaction {
            val t = dao.task(id) ?: return@withTransaction
            val due = t.due() ?: return@withTransaction
            val moved = t.copy(dueDate = Scheduling.tomorrow(now, due.zoneId(zone)), updatedAt = now)
            dao.upsert(moved)
            resetReminder(moved, now, zone)
        }
        scheduler.rearm()
    }

    suspend fun markSeen(id: String) = dao.markSeen(id)

    private suspend fun softDeleteReminders(taskId: String, now: Instant) {
        dao.upsert(dao.remindersOf(taskId).map { it.copy(deletedAt = now, unseen = false, updatedAt = now) })
    }

    private suspend fun resetReminder(t: Task, now: Instant, zone: ZoneId) {
        val due = t.due()
        if (due == null || due.time == null) return softDeleteReminders(t.id, now)
        val fireAt = due.instant(zone)
        val old = dao.remindersOf(t.id).firstOrNull()
        dao.upsert(
            listOf(
                Reminder(
                    id = old?.id ?: newId("r_"),
                    taskId = t.id,
                    kind = "AT_DUE",
                    offsetMin = null,
                    atDate = null,
                    atTime = null,
                    atZone = null,
                    atInstant = null,
                    nextFireAt = fireAt,
                    lastFiredAt = if (fireAt <= now) fireAt else null,
                    snoozedUntil = null,
                    unseen = false,
                    createdAt = old?.createdAt ?: now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            ),
        )
    }

    private companion object {
        const val PRIORITY_NONE = 4
    }
}
