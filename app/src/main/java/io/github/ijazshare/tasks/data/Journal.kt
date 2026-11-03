/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
@file:Suppress("LongParameterList", "ComplexCondition")
@file:UseContextualSerialization(Instant::class, LocalDate::class, LocalTime::class)

package io.github.ijazshare.tasks.data

import androidx.room.withTransaction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// The undo journal (spec 4.8). A mutation records the rows it touched as they were before, and
// the `updatedAt` each has afterwards; undo puts the old rows back, unless something changed them since.

private class Text<T : Any>(name: String, val to: (T) -> String, val from: (String) -> T) : KSerializer<T> {
    override val descriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(to(value))

    override fun deserialize(decoder: Decoder): T = from(decoder.decodeString())
}

internal val journalJson = Json {
    serializersModule = SerializersModule {
        contextual(
            Instant::class,
            Text("Instant", {
                it.toEpochMilli().toString()
            }, { Instant.ofEpochMilli(it.toLong()) }),
        )
        contextual(LocalDate::class, Text("LocalDate", LocalDate::toString, LocalDate::parse))
        contextual(LocalTime::class, Text("LocalTime", LocalTime::toString, LocalTime::parse))
    }
}

/** A task with its reminder rows and tags, as it was. */
@Serializable
class Snap(val task: Task, val reminders: List<Reminder>, val tags: List<String>)

/** What undo does: put [restore] back, remove what was [created] (tasks, projects, labels). */
@Serializable
class Inverse(
    val op: String,
    val restore: List<Snap>,
    val created: List<String>,
    val projects: List<String>,
    val labels: List<String>,
)

@Serializable
class Touched(val table: String, val id: String, val updatedAt: Long)

class UndoResult(val ok: Boolean, val speech: String, val op: String? = null, val taskIds: List<String> = emptyList())

class Journal(private val db: TasksDb) {
    private val dao get() = db.dao()

    suspend fun snap(id: String): Snap? = dao.taskAny(id)?.let { Snap(it, dao.remindersOf(id), dao.labelIdsOf(id)) }

    /** Writes the row for one mutation; null when it touched nothing. Call inside the mutation's transaction. */
    suspend fun record(
        client: String,
        started: Instant,
        now: Instant,
        op: String,
        summary: String,
        before: Map<String, Snap?>,
        projects: List<String>,
        labels: List<String>,
    ): String? {
        if (before.isEmpty() && projects.isEmpty() && labels.isEmpty()) return null
        val inverse =
            Inverse(
                op,
                before.values.filterNotNull(),
                before.filterValues {
                    it == null
                }.keys.toList(),
                projects,
                labels,
            )
        val touched = before.keys.mapNotNull { id ->
            dao.taskAny(id)?.let { Touched("task", id, it.updatedAt.toEpochMilli()) }
        }
        val token = newId("u_")
        dao.insert(
            UndoJournal(
                token,
                started,
                now.plus(TTL),
                false,
                client,
                summary,
                journalJson.encodeToString(Inverse.serializer(), inverse),
                journalJson.encodeToString(ListSerializer(Touched.serializer()), touched),
            ),
        )
        dao.purgeJournal(now)
        return token
    }

    /** Applies the inverse in one transaction; the caller re-arms the alarm afterwards. */
    suspend fun undo(token: String, client: String, now: Instant): UndoResult = db.withTransaction {
        val j = dao.journal(token)
        if (j == null || j.used || j.client != client || j.expiresAt < now) return@withTransaction refused(GONE)
        val touched = journalJson.decodeFromString(ListSerializer(Touched.serializer()), j.touchedJson)
        if (touched.any {
                dao.taskAny(it.id)?.updatedAt?.toEpochMilli() != it.updatedAt
            }
        ) {
            return@withTransaction refused(CHANGED)
        }
        val inv = journalJson.decodeFromString(Inverse.serializer(), j.inverseOpsJson)
        inv.restore.forEach { s ->
            val id = s.task.id
            dao.upsert(s.task)
            dao.deleteFts(id)
            if (s.task.deletedAt == null) dao.insertFts(id, s.task.title)
            dao.clearReminders(id)
            dao.upsert(s.reminders)
            dao.clearTags(id)
            dao.insertTags(s.tags.map { TaskLabel(id, it) })
            dao.deleteLogsSince(id, j.createdAt)
        }
        inv.created.forEach { id ->
            dao.taskAny(id)?.let { dao.upsert(it.copy(deletedAt = now, updatedAt = now)) }
            dao.deleteFts(id)
            dao.clearReminders(id)
        }
        inv.projects.forEach { dao.dropProject(it) }
        inv.labels.forEach {
            dao.dropTagsOf(it)
            dao.dropLabel(it)
        }
        dao.markUsed(token)
        UndoResult(true, "Undone. ${j.summary}", inv.op, inv.restore.map { it.task.id } + inv.created)
    }

    private fun refused(speech: String) = UndoResult(false, speech)

    private companion object {
        val TTL: Duration = Duration.ofMinutes(10)
        const val GONE = "That can't be undone any more. You can change it in Tasks."
        const val CHANGED = "That task has changed since, so I've left it. You can change it in Tasks."
    }
}
