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

package io.github.ijazshare.tasks.parse

import android.content.Context
import io.github.ijazshare.tasks.data.INBOX_ID
import io.github.ijazshare.tasks.data.Label
import io.github.ijazshare.tasks.data.Repository
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.data.TasksDb
import io.github.ijazshare.tasks.data.due
import io.github.ijazshare.tasks.data.newId
import io.github.ijazshare.tasks.time.DueSpec
import io.github.ijazshare.tasks.time.Schedule
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate

/** The real [TaskStore]: every write goes through [Repository], so reminders derive from tags as for a hand-made task. */
class RoomStore(context: Context, private val repo: Repository = Repository(context)) : TaskStore {
    private val dao = TasksDb.get(context).dao()

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
        return t.id
    }

    override suspend fun complete(id: String) = repo.complete(id)

    override suspend fun delete(id: String) = repo.delete(id)

    override suspend fun reschedule(id: String, due: DueSpec) {
        val t = repo.task(id) ?: return
        repo.save(t.copy(dueDate = due.date, dueTime = due.time, dueZone = due.zone))
    }

    override suspend fun change(id: String, priority: Int?, project: String?, label: LabelRef?) {
        val t: Task = repo.task(id) ?: return
        val tags = label?.let { l -> dao.labelByKey(l.name.lowercase())?.id?.let { repo.tagsOf(id) + it } }
        repo.save(
            t.copy(
                priority = priority ?: t.priority,
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
        return !existed && repo.addProject(name) != null
    }

    override suspend fun addLabel(name: String): Boolean {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return false
        val now = Instant.now()
        return dao.insert(
            Label(newId("l_"), key, key, null, dao.nextLabelOrder(), now, now, null, Schedule.REGULAR.toJson()),
        ) !=
            -1L
    }
}
