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
import io.github.ijazshare.tasks.time.Alert
import io.github.ijazshare.tasks.time.AlertType
import io.github.ijazshare.tasks.time.DueSpec
import io.github.ijazshare.tasks.time.RecurrenceRule
import io.github.ijazshare.tasks.time.Reminders
import io.github.ijazshare.tasks.time.Schedule
import io.github.ijazshare.tasks.time.Scheduling
import kotlinx.coroutines.flow.Flow
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

const val INBOX_ID = "p_inbox00000"

/** The seeded tags (8.6); only the first has the Urgent schedule. Their ids are fixed: `l_<name>`. */
val SEED_LABELS = listOf("urgent", "work", "personal", "family", "client")

fun Reminder.pending() = lastFiredAt == null || snoozedUntil != null

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
@Suppress("TooManyFunctions") // one repository for one small schema
class Repository(private val context: Context) {
    private val db get() = TasksDb.get(context)
    private val dao get() = db.dao()
    private val scheduler get() = ReminderScheduler(context)

    fun dated(): Flow<List<Task>> = dao.dated()

    fun undated(): Flow<List<Task>> = dao.undated()

    fun missed(): Flow<List<Task>> = dao.missed()

    fun labels(): Flow<List<Label>> = dao.labels()

    fun taskLabels(): Flow<List<TaskLabel>> = dao.taskLabels()

    fun reminders(): Flow<List<Reminder>> = dao.reminders()

    suspend fun tagsOf(taskId: String): Set<String> = dao.labelIdsOf(taskId).toSet()

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

    /** Idempotent, like [ensureInbox]: fixed ids, insert-or-ignore. */
    suspend fun ensureLabels() {
        val now = Instant.now()
        SEED_LABELS.forEachIndexed { i, name ->
            val schedule = if (name == "urgent") Schedule.URGENT else Schedule.REGULAR
            dao.insert(Label("l_$name", name, name, null, i, now, now, null, schedule.toJson()))
        }
    }

    /** Re-derives every open task's reminder rows; matching rows keep their state. Run at start and on zone changes. */
    suspend fun rederiveAll() {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        db.withTransaction { dao.datedOpenTasks().forEach { rederive(it, now, zone, reset = false) } }
    }

    /** A blank task in [projectId]; [save] gives it its first write. */
    fun blank(projectId: String): Task {
        val now = Instant.now()
        return Task(
            newId("t_"), "", "", projectId, null, null, 0, PRIORITY_NONE, null, null, null, null, null, null,
            null, null, null, now, now, null, "typed", null, null,
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
     * occurrence at or after now (8.2). Reminder rows are re-derived (8.6) when the due, the rule
     * or the [tags] (label ids; null = unchanged) change. An alert already past counts as skipped.
     */
    suspend fun save(task: Task, tags: Set<String>? = null) {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        db.withTransaction {
            val old = dao.task(task.id)
            var t = task.copy(title = task.title.trim(), updatedAt = now)
            if (t.dueDate == null || t.dueTime == null) t = t.copy(dueTime = null, dueZone = null)
            if (t.dueDate == null) t = t.copy(recurrence = null)
            if (t.recurrence == null) t = t.copy(recurrenceText = null, recurrenceAnchor = null)
            val tagsChanged = setTags(t.id, tags)
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
            if (changed || tagsChanged) rederive(t, now, zone, reset = changed)
        }
        scheduler.rearm()
    }

    /** Stores [tags] (null = leave alone); true if they differ from what was there. */
    private suspend fun setTags(taskId: String, tags: Set<String>?): Boolean {
        if (tags == null || tags == dao.labelIdsOf(taskId).toSet()) return false
        dao.clearTags(taskId)
        dao.insertTags(tags.map { TaskLabel(taskId, it) })
        return true
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
            rederive(moved, now, zone, reset = true)
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

    /** Snoozes the alert [reminderId], unless that would stack on the task's next alert (8.6 rule 5). */
    suspend fun snooze(reminderId: String, length: Duration) {
        val now = Instant.now()
        db.withTransaction {
            val r = dao.reminder(reminderId) ?: return@withTransaction
            val others = dao.remindersOf(r.taskId).filter {
                it.deletedAt == null && it.pending() && (it.id != r.id || it.kind == AlertType.NAG.name)
            }.mapNotNull { it.snoozedUntil ?: it.atInstant ?: it.nextFireAt }
            val type = AlertType.valueOf(r.kind)
            val until = Reminders.snoozeUntil(type, now, length, others) ?: r.snoozedUntil
            val rows = mutableListOf(r.copy(snoozedUntil = until, unseen = false, updatedAt = now))
            // Snoozing an at-due alert pauses the nagging too: it resumes after the snooze alert.
            if (type == AlertType.AT_DUE && until != null) {
                val nagMin = Schedule.pick(
                    dao.schedules(r.taskId).mapNotNull {
                        Schedule.parse(it.scheduleJson)
                    },
                ).nagMin
                dao.remindersOf(r.taskId).filter { it.deletedAt == null && it.kind == AlertType.NAG.name }.forEach {
                    val at = Reminders.nagResume(until, nagMin ?: Schedule.URGENT.nagMin!!)
                    rows +=
                        it.copy(
                            atInstant = at,
                            nextFireAt = at,
                            lastFiredAt = null,
                            snoozedUntil = null,
                            updatedAt = now,
                        )
                }
            }
            dao.upsert(rows)
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
            rederive(moved, now, zone, reset = true)
        }
        scheduler.rearm()
    }

    suspend fun markSeen(id: String) = dao.markSeen(id)

    /** The task was opened: its alerts are seen and it leaves the Missed list. */
    suspend fun opened(id: String) {
        dao.markSeen(id)
        dao.clearMissed(id)
    }

    private fun Alert.matches(r: Reminder) = type.name == r.kind && offsetMin == r.offsetMin

    private suspend fun softDeleteReminders(taskId: String, now: Instant) {
        dao.upsert(dao.remindersOf(taskId).map { it.copy(deletedAt = now, unseen = false, updatedAt = now) })
    }

    /**
     * Brings [t]'s reminder rows in line with its due, tags and recurrence (8.6). Rows that still
     * match keep their state (fired, snoozed, unseen) unless [reset]; the rest are replaced.
     */
    private suspend fun rederive(t: Task, now: Instant, zone: ZoneId, reset: Boolean) {
        val due = t.due() ?: return softDeleteReminders(t.id, now)
        val schedule = Schedule.pick(dao.schedules(t.id).mapNotNull { Schedule.parse(it.scheduleJson) })
        val repeating = t.recurrence != null
        val all = Reminders.candidates(due, repeating, schedule, zone)
        val rows = dao.remindersOf(t.id)
        val upcoming = Reminders.upcoming(due, repeating, schedule, now, zone)
        // A row stays if it is still one of this task's alerts and either is history, is overdue
        // for delivery (a Missed one), or is still wanted; a pending alert the tags dropped goes.
        val keep = rows.filter { r ->
            !reset && r.deletedAt == null && all.any {
                it.type.name == r.kind && it.offsetMin == r.offsetMin &&
                    (it.type == AlertType.NAG || it.at == r.atInstant)
            } && (!r.pending() || (r.snoozedUntil ?: r.atInstant)!! <= now || upcoming.any { it.matches(r) })
        }
        dao.deleteReminders((rows - keep.toSet()).map { it.id })
        val fresh = upcoming.filter { a -> keep.none { a.matches(it) } }
        dao.upsert(
            fresh.map {
                Reminder(
                    newId("r_"), t.id, it.type.name, it.offsetMin, null, null, null, it.at, it.at, null, null,
                    false, now, now, null,
                )
            },
        )
    }

    private companion object {
        const val PRIORITY_NONE = 4
    }
}
