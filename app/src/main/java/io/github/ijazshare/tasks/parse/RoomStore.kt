/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength")

package io.github.ijazshare.tasks.parse

import android.content.Context
import io.github.ijazshare.tasks.data.INBOX_ID
import io.github.ijazshare.tasks.data.Journal
import io.github.ijazshare.tasks.data.Label
import io.github.ijazshare.tasks.data.Repository
import io.github.ijazshare.tasks.data.Snap
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.data.TasksDb
import io.github.ijazshare.tasks.data.due
import io.github.ijazshare.tasks.data.newId
import io.github.ijazshare.tasks.data.pending
import io.github.ijazshare.tasks.time.DueSpec
import io.github.ijazshare.tasks.time.RecurrenceRule
import io.github.ijazshare.tasks.time.Schedule
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate

/** The real [TaskStore]: every write goes through [Repository], so reminders derive from tags as for a hand-made task. */
class RoomStore(context: Context, private val repo: Repository = Repository(context)) : TaskStore {
    private val db = TasksDb.get(context)
    private val dao = db.dao()
    private val journal = Journal(db)

    // What this request touched, for the undo journal (4.8): each task as it was before, or null if it is new.
    private val before = LinkedHashMap<String, Snap?>()
    private val madeProjects = mutableListOf<String>()
    private val madeLabels = mutableListOf<String>()
    private val started = Instant.now()

    private suspend fun touch(id: String) {
        if (id !in before) before[id] = journal.snap(id)
    }

    /** Writes the undo row for what this request changed (inside its transaction); returns the token, or null. */
    suspend fun record(client: String, now: Instant, op: String, summary: String): String? =
        journal.record(client, started, now, op, summary, before, madeProjects, madeLabels)

    /** The tasks this request changed, as they were before (null: newly created). */
    fun previous(id: String): Task? = before[id]?.task

    override suspend fun view(id: String): TaskView? {
        val t = dao.taskAny(id) ?: return null
        val next = dao.remindersOf(id).filter { it.deletedAt == null && it.pending() }
            .mapNotNull { it.snoozedUntil ?: it.atInstant ?: it.nextFireAt }.minOrNull()
        val project = dao.projectById(t.projectId)?.name ?: "Inbox"
        return TaskView(
            t.id,
            t.title,
            project,
            t.due(),
            t.recurrence?.let(RecurrenceRule::parse),
            t.recurrenceAnchor,
            t.recurrenceText,
            next,
        )
    }

    override suspend fun snapshot(): Snapshot {
        val projects = dao.projects().first()
        val sections = dao.sections().groupBy { it.projectId }
        val names = projects.associate { it.id to it.name }
        val today = LocalDate.now()
        return Snapshot(
            projects.map { p -> ProjectInfo(p.name, sections[p.id].orEmpty().map { it.name }) },
            dao.labels().first().map { it.name },
            dao.openTasks().map {
                OpenTask(
                    it.id,
                    it.title,
                    it.due(),
                    it.priority,
                    names[it.projectId] ?: "Inbox",
                    (it.dueDate ?: today) < today,
                )
            },
        )
    }

    private suspend fun projectId(name: String) = if (name.equals("Inbox", true)) {
        INBOX_ID
    } else {
        repo.addProject(name)
            ?: INBOX_ID
    }

    override suspend fun create(task: NewTask): String {
        val now = Instant.now()
        val pid = projectId(task.project.name)
        val section = task.project.section?.let { s ->
            dao.sections().firstOrNull { it.projectId == pid && it.name.equals(s, true) }?.id
        }
        val t = repo.blank(pid).copy(
            title = task.title, description = task.description, sectionId = section, priority = task.priority,
            dueDate = task.due?.date, dueTime = task.due?.time, dueZone = task.due?.zone,
            recurrence = task.recurrence?.format(),
            recurrenceAnchor = task.recurrence?.let {
                if (task.completionAnchor) "completion" else "schedule"
            },
            recurrenceText = task.recurrenceText.takeIf { task.recurrence != null }, deadline = task.deadline,
            durationMin = task.durationMin, createdAt = now, updatedAt = now, source = task.source, sourceUtterance = task.utterance,
        )
        val tags = task.labels.mapNotNull { dao.labelByKey(it.name.lowercase())?.id }.toSet()
        repo.save(t, tags)
        before[t.id] = null
        return t.id
    }

    override suspend fun complete(id: String) {
        touch(id)
        repo.complete(id)
    }

    override suspend fun delete(id: String) {
        touch(id)
        repo.delete(id)
    }

    override suspend fun reschedule(id: String, due: DueSpec) {
        val t = repo.task(id) ?: return
        touch(id)
        repo.save(t.copy(dueDate = due.date, dueTime = due.time, dueZone = due.zone))
    }

    override suspend fun change(id: String, priority: Int?, project: String?, label: LabelRef?) {
        val t: Task = repo.task(id) ?: return
        touch(id)
        val tags = label?.let { l -> dao.labelByKey(l.name.lowercase())?.id?.let { repo.tagsOf(id) + it } }
        repo.save(
            t.copy(
                priority = t.priority,
                projectId = project?.let {
                    projectId(it)
                } ?: t.projectId,
                sectionId = if (project !=
                    null
                ) {
                    null
                } else {
                    t.sectionId
                },
            ),
            tags,
        )
    }

    override suspend fun addProject(name: String): Boolean {
        val existed = dao.projects().first().any { it.name.equals(name, true) }
        val id = if (existed) null else repo.addProject(name)
        id?.let { madeProjects += it }
        return id != null
    }

    override suspend fun addLabel(name: String): Boolean {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return false
        val now = Instant.now()
        val label = Label(newId("l_"), key, key, null, dao.nextLabelOrder(), now, now, null, Schedule.REGULAR.toJson())
        return (dao.insert(label) != -1L).also { if (it) madeLabels += label.id }
    }
}
