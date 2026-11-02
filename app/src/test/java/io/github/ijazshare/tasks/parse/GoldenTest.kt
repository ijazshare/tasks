/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength", "CyclomaticComplexMethod")

package io.github.ijazshare.tasks.parse

import com.google.common.truth.Truth.assertThat
import io.github.ijazshare.tasks.time.DueSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** In-memory stand-in for Room (spec 13.1 asks for Room; the handler only sees [TaskStore]). */
class FakeStore(
    private val projects: List<ProjectInfo>,
    private val labels: List<String>,
    private var open: List<OpenTask>,
) : TaskStore {
    var created: NewTask? = null
    var completed: String? = null
    var deleted: String? = null
    var moved: DueSpec? = null
    var changed: String? = null
    private val addedProjects = mutableListOf<String>()
    private val addedLabels = mutableListOf<String>()

    override suspend fun snapshot() = Snapshot(
        projects + addedProjects.map {
            ProjectInfo(it)
        },
        labels + addedLabels,
        open,
    )

    override suspend fun create(task: NewTask): String {
        created = task
        open = open + OpenTask("new${open.size}", task.title, task.due, task.priority, task.project.name)
        return open.last().id
    }

    override suspend fun complete(id: String) {
        completed = open.first { it.id == id }.title
        open = open.filter { it.id != id }
    }

    override suspend fun delete(id: String) {
        deleted = open.first { it.id == id }.title
        open = open.filter { it.id != id }
    }

    override suspend fun reschedule(id: String, due: DueSpec) {
        moved = due
        open = open.map { if (it.id == id) it.copy(due = due) else it }
    }

    override suspend fun change(id: String, priority: Int?, project: String?, label: LabelRef?) {
        changed = listOfNotNull(priority?.let { "p$it" }, project, label?.name).joinToString()
    }

    override suspend fun addProject(name: String) =
        (projects.map { it.name } + addedProjects).none { it.equals(name, true) } && addedProjects.add(name)

    override suspend fun addLabel(name: String) =
        (labels + addedLabels).none { it.equals(name, true) } && addedLabels.add(name)

    override suspend fun view(id: String): TaskView? = open.firstOrNull { it.id == id }?.let {
        val made = created?.takeIf { c -> c.title == it.title }
        TaskView(
            it.id,
            it.title,
            it.project,
            it.due,
            made?.recurrence,
            made?.recurrence?.let {
                "schedule"
            },
            made?.recurrenceText,
            it.due?.takeIf { d -> d.time != null }?.instant(java.time.ZoneId.of("Europe/London")),
        )
    }
}

private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun dueText(d: DueSpec?) = d?.let {
    it.date.toString() +
        (it.time?.let { t -> "T" + t.withSecond(0).withNano(0) } ?: "")
}

private fun names(a: JsonElement?) =
    (a as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

class GoldenTest {
    private val cases: Map<String, JsonObject> by lazy {
        val dir = File(javaClass.classLoader!!.getResource("golden")!!.toURI())
        dir.listFiles { f -> f.name.endsWith(".jsonl") }!!.sortedBy { it.name }.flatMap { f ->
            f.readLines().filter {
                it.isNotBlank() && !it.startsWith("#")
            }.map { Json.parseToJsonElement(it).jsonObject }
        }.associateBy { it["id"].str()!! }
    }

    private fun chain(c: JsonObject): List<JsonObject> =
        (c["after"].str()?.let { chain(cases.getValue(it)) } ?: emptyList()) + c

    /** Runs a case (and the ones it follows) in one session; returns the problems found. */
    private fun run(c: JsonObject): List<String> = runBlocking {
        val steps = chain(c)
        val first = steps.first()
        val projects = (first["projects"] as? JsonArray)?.map { p ->
            if (p is JsonObject) {
                ProjectInfo(
                    p["name"].str()!!,
                    names(p["sections"]),
                )
            } else {
                ProjectInfo(p.jsonPrimitive.content)
            }
        } ?: listOf(ProjectInfo("Inbox"))
        val open = (first["open_tasks"] as? JsonArray)?.mapIndexed { i, t ->
            if (t is JsonObject) {
                val due = (t["due"] as? JsonObject)?.let {
                    DueSpec(LocalDate.parse(it["date"].str()), it["time"].str()?.let(LocalTime::parse), null)
                }
                OpenTask("t$i", t["title"].str()!!, due, project = t["project"].str() ?: "Inbox")
            } else {
                OpenTask("t$i", t.jsonPrimitive.content)
            }
        } ?: emptyList()
        val store = FakeStore(projects, names(first["labels"]), open)
        val handler = CommandHandler(store)
        var last: Response? = null
        val problems = mutableListOf<String>()
        for ((i, step) in steps.withIndex()) {
            val zone = ZoneId.of(step["zone"].str() ?: first["zone"].str() ?: "Europe/London")
            val now = ZonedDateTime.of(
                LocalDateTime.parse(step["now"].str() ?: first["now"].str() ?: "2026-10-27T14:03:00"),
                zone,
            )
            val offset =
                step["speech_end_offset_ms"]?.jsonPrimitive?.int ?: first["speech_end_offset_ms"]?.jsonPrimitive?.int
                    ?: 0
            store.created = null
            store.completed = null
            store.deleted = null
            store.moved = null
            store.changed = null
            val conv = last?.conversation
            val req = Request(
                type = if (conv != null && i > 0 && step["after"] != null) "follow_up" else "utterance",
                utterance = step["utterance"].str(),
                conversationId = conv?.id,
                now = now,
                speechEnd = now.toInstant().plus(Duration.ofMillis(offset.toLong())),
                typed = step["typed"]?.jsonPrimitive?.boolean ?: false,
            )
            last = handler.handle(req)
            if (step === c) problems += compare(c, last, store)
        }
        problems
    }

    private fun compare(c: JsonObject, r: Response, store: FakeStore): List<String> {
        val e = c["expect"]!!.jsonObject
        val out = mutableListOf<String>()
        fun check(key: String, actual: Any?) {
            if (key !in e) return
            val want = e[key].let { if (it is JsonNull) null else it }
            val got = when (actual) {
                null -> null
                is List<*> -> JsonArray(actual.map { JsonPrimitive(it.toString()) })
                is Boolean -> JsonPrimitive(actual)
                is Number -> JsonPrimitive(actual)
                else -> JsonPrimitive(actual.toString())
            }
            if (want != got) out += "${c["id"].str()} [${c["utterance"].str()}] $key: expected $want but was $got"
        }
        val t = store.created
        check("status", r.status)
        check("intent", r.intent?.name?.lowercase())
        check("title", t?.title)
        check("due", dueText(t?.due))
        check("due_zone", t?.due?.zone)
        check("recurrence", t?.recurrence?.format())
        check("completion", t?.completionAnchor)
        check("reminder", (r.parse?.reminder as? SlotValue.Parsed)?.v?.kind)
        check("reminder_at", (r.parse?.reminder as? SlotValue.Parsed)?.v?.at)
        check("project", t?.project?.name)
        check("section", t?.project?.section)
        check("labels", t?.labels?.map { it.name }?.sorted())
        check("priority", t?.priority)
        check("deadline", t?.deadline)
        check("description", t?.description?.ifEmpty { null })
        check("duration_min", t?.durationMin)
        check("expects", r.conversation?.expects)
        check("options", r.conversation?.options?.map { it.second })
        check("completed", store.completed)
        check("deleted", store.deleted)
        check("moved", dueText(store.moved))
        check("changed", store.changed)
        check("effect", r.effect)
        check("speech", r.speech)
        if ("speech" !in e && r.status != "not_mine" &&
            System.getenv("GOLDEN_PRINT") == null
        ) {
            out += "${c["id"].str()} has no speech expectation"
        }
        if (System.getProperty("golden.print") != null || System.getenv("GOLDEN_PRINT") != null) {
            println("GOLDEN\t${c["id"].str()}\t${r.status}\t${r.speech}")
        }
        return out
    }

    @Test
    fun corpus() {
        val problems = cases.values.flatMap { run(it) }
        assertThat(problems).isEmpty()
    }

    @Test
    fun corpusIsBigEnough() {
        val status = cases.values.map { it["expect"]!!.jsonObject["status"].str() }
        assertThat(cases.size).isAtLeast(300)
        assertThat(status.count { it == "needs_input" || it == "declined" }).isAtLeast(60)
        assertThat(status.toSet()).containsAtLeast("done", "needs_input", "declined", "not_mine")
    }
}
