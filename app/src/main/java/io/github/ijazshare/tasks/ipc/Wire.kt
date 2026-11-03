/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength", "TooManyFunctions", "LongParameterList")

package io.github.ijazshare.tasks.ipc

import io.github.ijazshare.tasks.parse.OpenTask
import io.github.ijazshare.tasks.parse.Request
import io.github.ijazshare.tasks.parse.Response
import io.github.ijazshare.tasks.parse.SlotValue
import io.github.ijazshare.tasks.parse.TaskView
import io.github.ijazshare.tasks.parse.clockSay
import io.github.ijazshare.tasks.parse.dayWord
import io.github.ijazshare.tasks.time.DueSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

// The contract of spec section 4, with no Android in it: JSON in, handler `Request` out, and the
// other way round. Fixed by the fixtures in contract/v1 (13.4).

const val CONTRACT_VERSION = 1
const val MAX_REQUEST_CHARS = 256 * 1024
const val MAX_RESPONSE_CHARS = 64 * 1024
const val MAX_LISTED = 50
const val QUESTION_TTL_SECONDS = 90L

/** The request as JSON gave it. */
class Wire(val raw: JsonObject) {
    private fun str(key: String) = (raw[key] as? JsonPrimitive)?.contentOrNull
    private val context = raw["context"] as? JsonObject

    val version get() = (raw["v"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    val requestId get() = str("request_id")
    val type get() = str("type")
    val utterance get() = str("utterance")
    val conversationId get() = str("conversation_id")
    val optionId get() = str("option_id")
    val undoToken get() = str("undo_token")
    val legacyId get() = str("legacy_id")
    val lines get() = (raw["lines"] as? JsonArray)?.map { it.jsonPrimitive.content }
    val chunk get() = raw["chunk"] as? JsonObject
    val source get() = (context?.get("source") as? JsonPrimitive)?.contentOrNull ?: "voice"

    /** The handler's request, or null when the context is missing or malformed (a `bad_request`). */
    fun request(client: String): Request? = runCatching {
        val c = context!!
        val now = Instant.ofEpochMilli(c["now_epoch_ms"]!!.jsonPrimitive.long)
        val end =
            (c["speech_end_epoch_ms"] as? JsonPrimitive)?.contentOrNull?.toLong()?.let(Instant::ofEpochMilli) ?: now
        Request(
            type = type!!,
            utterance = utterance,
            conversationId = conversationId,
            optionId = optionId,
            client = client,
            now = ZonedDateTime.ofInstant(now, ZoneId.of(c["zone"]!!.jsonPrimitive.content)),
            speechEnd = end,
            typed =
            source == "typed",
        )
    }.getOrNull()

    companion object {
        fun parse(json: String): Wire? = runCatching { Wire(Json.parseToJsonElement(json).jsonObject) }.getOrNull()
    }
}

/** Fixed sentences for each error code (4.9). */
private val ERRORS = mapOf(
    "bad_request" to "Something went wrong talking to the task app.",
    "unsupported_version" to "Please update Assistant.",
    "caller_not_trusted" to "The task app is signed with a different key.",
    "busy" to "The task app took too long. Nothing was saved.",
    "storage" to "I couldn't save that; storage failed.",
    "conversation_expired" to "I lost track of that. Please say it again.",
    "internal" to "Something went wrong in the task app.",
)

fun errorResponse(requestId: String?, code: String, detail: String = ""): String = response(
    requestId,
    "error",
    ERRORS.getValue(code),
    error = buildJsonObject {
        put("code", code)
        put("detail", detail)
    },
)

/** An effect that carries a task (4.5.1). */
fun taskEffect(
    op: String,
    t: TaskView,
    build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
): JsonObject = buildJsonObject {
    put("op", op)
    put("task_id", t.id)
    put("title", t.title)
    put("project", t.project)
    put("due", dueJson(t.due))
    put(
        "recurrence",
        t.recurrence?.let {
            buildJsonObject {
                put("rrule", it.format())
                put("anchor", t.anchor ?: "schedule")
                put("text", t.recurrenceText)
            }
        } ?: JsonNull,
    )
    put("reminder_at_epoch_ms", t.reminderAt?.toEpochMilli())
    build()
}

fun dueJson(d: DueSpec?): JsonElement = d?.let {
    buildJsonObject {
        put("date", it.date.toString())
        put("time", it.time?.withSecond(0)?.withNano(0)?.toString())
        put("zone", it.zone)
    }
} ?: JsonNull

fun response(
    requestId: String?,
    status: String,
    speech: String,
    display: JsonObject? = null,
    effects: List<JsonObject> = emptyList(),
    conversation: JsonObject? = null,
    undo: JsonObject? = null,
    openUri: String? = null,
    error: JsonObject? = null,
    extra: Map<String, JsonElement> = emptyMap(),
): String = JsonObject(
    buildMap {
        put("v", JsonPrimitive(CONTRACT_VERSION))
        put("request_id", JsonPrimitive(requestId))
        put("status", JsonPrimitive(status))
        put("speech", JsonPrimitive(speech))
        put("display", display ?: JsonNull)
        put("effects", JsonArray(effects))
        put("warnings", JsonArray(emptyList()))
        put("conversation", conversation ?: JsonNull)
        put("undo", undo ?: JsonNull)
        put("open_uri", JsonPrimitive(openUri))
        put("error", error ?: JsonNull)
        putAll(extra)
    },
).toString()

private fun String.cap() = replaceFirstChar { it.uppercase() }

/**
 * The response to a handled utterance or follow-up. [view] is the task the effect names (after the
 * change), [previous] the same task before it; [undoToken] is set for mutations.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
fun fromHandler(
    requestId: String,
    r: Response,
    now: Instant,
    zone: ZoneId,
    view: TaskView?,
    previous: DueSpec?,
    undoToken: String?,
): String {
    val today = now.atZone(zone).toLocalDate()
    val effect: JsonObject? = when (r.effect) {
        "created" -> view?.let { taskEffect("created", it) }

        "completed" -> view?.let { v ->
            taskEffect("completed", v) {
                if (v.recurrence !=
                    null
                ) {
                    put("completed_occurrence", dueJson(previous))
                }
            }
        }

        "deleted" -> view?.let { taskEffect("deleted", it) }

        "rescheduled" -> view?.let { v -> taskEffect("rescheduled", v) { put("previous_due", dueJson(previous)) } }

        "changed" -> view?.let { v ->
            taskEffect("updated", v) {
                putJsonArray("changed") {
                    r.parse?.let { p ->
                        if (p.priority is SlotValue.Parsed) add(JsonPrimitive("priority"))
                        if (p.project is SlotValue.Parsed) add(JsonPrimitive("project"))
                        if (p.labels.isNotEmpty()) add(JsonPrimitive("labels"))
                    }
                }
            }
        }

        "listed" -> listed(r, today)

        "project_created", "label_created" -> buildJsonObject {
            put("op", r.effect)
            put("id", r.name.orEmpty())
            put("name", r.name.orEmpty())
        }

        else -> null
    }
    val done = r.status == "done"
    val display = if (done && view != null && r.effect != "deleted") display(view, today) else null
    val uri = when {
        !done -> null
        r.effect == "listed" -> if (queryDate(r)?.isAfter(today) == true) "tasks://upcoming" else "tasks://today"
        view != null && r.effect != "deleted" -> "tasks://task/${view.id}"
        else -> null
    }
    val conversation = r.conversation?.let { c ->
        buildJsonObject {
            put("id", c.id)
            put("expects", c.expects)
            put("expires_epoch_ms", now.plusSeconds(QUESTION_TTL_SECONDS).toEpochMilli())
            put("rounds_left", c.roundsLeft)
            putJsonArray("options") {
                c.options.forEach { (id, label) ->
                    add(
                        buildJsonObject {
                            put("id", id)
                            put("label", label)
                        },
                    )
                }
            }
        }
    }
    return response(
        requestId,
        r.status,
        r.speech,
        display,
        listOfNotNull(effect.takeIf { done }),
        conversation,
        undoToken?.let {
            buildJsonObject {
                put("token", it)
                put("expires_epoch_ms", now.plusSeconds(UNDO_TTL_SECONDS).toEpochMilli())
            }
        },
        uri,
    )
}

const val UNDO_TTL_SECONDS = 600L

private fun display(t: TaskView, today: LocalDate): JsonObject = buildJsonObject {
    put("title", t.title)
    putJsonArray("lines") {
        val due = t.due?.let { d -> dayWord(d.date, today).cap() + (d.time?.let { " " + clockSay(it) } ?: "") }
        add(
            JsonPrimitive(
                listOfNotNull(
                    due,
                    t.project,
                    if (t.reminderAt !=
                        null
                    ) {
                        "reminder on"
                    } else {
                        null
                    },
                ).joinToString(" · "),
            ),
        )
    }
}

private fun listed(r: Response, today: LocalDate): JsonObject {
    val all = r.items.orEmpty()
    val scope = r.parse?.scope ?: "all"
    val project = (r.parse?.project as? SlotValue.Parsed)?.v?.name
    val day = queryDate(r)
    val query = when {
        project != null -> "project:$project"
        day == today -> "today"
        day == today.plusDays(1) -> "tomorrow"
        else -> scope
    }
    return buildJsonObject {
        put("op", "listed")
        put("query", query)
        put("items", buildJsonArray { all.take(MAX_LISTED).forEach { add(item(it)) } })
        put("total", all.size)
        put("truncated", all.size > MAX_LISTED)
    }
}

private fun queryDate(r: Response): LocalDate? = r.parse?.scope?.takeIf {
    it.startsWith("date:")
}?.let { LocalDate.parse(it.removePrefix("date:")) }

private fun item(t: OpenTask) = buildJsonObject {
    put("task_id", t.id)
    put("title", t.title)
    put("due", dueJson(t.due))
    put("project", t.project)
    put("priority", t.priority)
    put("overdue", t.overdue)
}

/** True for a handler answer that changed data, so it gets an undo token. */
fun isMutation(r: Response) = r.status == "done" && r.effect != null && r.effect != "listed"
