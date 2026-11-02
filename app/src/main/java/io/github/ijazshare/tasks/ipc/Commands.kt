/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
@file:Suppress(
    "ktlint:standard:max-line-length",
    "MaxLineLength",
    "TooManyFunctions",
    "TooGenericExceptionCaught",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "ReturnCount",
)

package io.github.ijazshare.tasks.ipc

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import androidx.room.withTransaction
import io.github.ijazshare.tasks.data.AssistantMarkdownImporter
import io.github.ijazshare.tasks.data.Conversation
import io.github.ijazshare.tasks.data.Journal
import io.github.ijazshare.tasks.data.Referent
import io.github.ijazshare.tasks.data.Repository
import io.github.ijazshare.tasks.data.RequestLog
import io.github.ijazshare.tasks.data.SOURCE_MD
import io.github.ijazshare.tasks.data.TasksDb
import io.github.ijazshare.tasks.data.newId
import io.github.ijazshare.tasks.parse.CommandHandler
import io.github.ijazshare.tasks.parse.LabelRef
import io.github.ijazshare.tasks.parse.NewTask
import io.github.ijazshare.tasks.parse.Request
import io.github.ijazshare.tasks.parse.Response
import io.github.ijazshare.tasks.parse.RoomStore
import io.github.ijazshare.tasks.parse.TaskStore
import io.github.ijazshare.tasks.reminders.ReminderScheduler
import io.github.ijazshare.tasks.time.DueSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val DEADLINE_MS = 600L
private val DEDUPE_TTL: Duration = Duration.ofMinutes(10)
private val REFERENT_TTL: Duration = Duration.ofMinutes(10)
private val STORED = setOf("done", "needs_input", "declined")

/** A store that refuses writes while a conversation is replayed: only the real last step may change data. */
private class Guarded(val real: TaskStore) : TaskStore by real {
    var frozen = true

    private fun ok() = check(!frozen) { "replay diverged" }

    override suspend fun create(task: NewTask): String = ok().let { real.create(task) }

    override suspend fun complete(id: String) = ok().let { real.complete(id) }

    override suspend fun delete(id: String) = ok().let { real.delete(id) }

    override suspend fun reschedule(id: String, due: DueSpec) = ok().let { real.reschedule(id, due) }

    override suspend fun change(id: String, priority: Int?, project: String?, label: LabelRef?) =
        ok().let { real.change(id, priority, project, label) }

    override suspend fun addProject(name: String): Boolean = ok().let { real.addProject(name) }

    override suspend fun addLabel(name: String): Boolean = ok().let { real.addLabel(name) }
}

/**
 * Spec 4.3 to 4.8 on Room: dedupe by request id, the 600 ms deadline, one serialised writer,
 * persisted conversations and referents, the undo journal, `status`, migration. The wire format
 * is in Wire.kt; the language is in the handler. [handle] never throws.
 */
class Commands(private val context: Context, private val clock: () -> Instant = Instant::now) {
    private val db get() = TasksDb.get(context)
    private val dao get() = db.dao()

    fun handle(json: String, caller: String): String = runBlocking { handleAsync(json, caller) }

    suspend fun handleAsync(json: String, caller: String): String {
        val t0 = System.nanoTime()
        if (json.length > MAX_REQUEST_CHARS) return errorResponse(null, "bad_request", "too large")
        val w = Wire.parse(json) ?: return errorResponse(null, "bad_request", "not json")
        val id = w.requestId ?: return errorResponse(null, "bad_request", "no request_id")
        val v = w.version ?: return errorResponse(id, "bad_request", "no v")
        if (v > CONTRACT_VERSION) return errorResponse(id, "unsupported_version", "v=$v")
        val mine = CompletableDeferred<String>()
        inFlight.putIfAbsent(id, mine)?.let { return it.await() }
        val out = try {
            withContext(writer) { run(w, id, caller, t0) }
        } catch (e: Exception) {
            errorResponse(id, "internal", e.javaClass.simpleName)
        }
        mine.complete(out)
        inFlight.remove(id)
        return out
    }

    private suspend fun run(w: Wire, id: String, caller: String, t0: Long): String {
        val now = clock()
        dao.purgeRequests(now.minus(DEDUPE_TTL))
        dao.requestLog(id)?.let { return it.responseJson }
        if ((System.nanoTime() - t0) / NANOS_PER_MS > DEADLINE_MS) return errorResponse(id, "busy")
        var mutated = false
        val out = try {
            db.withTransaction {
                val (json, changed) = dispatch(w, id, caller, now)
                mutated = changed
                if (Json.parseToJsonElement(json).jsonObject["status"]?.jsonPrimitive?.content in STORED) {
                    dao.insert(RequestLog(id, now, json))
                }
                json
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            return errorResponse(id, "storage", e.javaClass.simpleName)
        }
        if (mutated) ReminderScheduler(context).rearm()
        return out
    }

    /** Returns the response and whether data changed. */
    private suspend fun dispatch(
        w: Wire,
        id: String,
        caller: String,
        now: Instant,
    ): Pair<String, Boolean> = when (w.type) {
        "utterance", "follow_up" -> converse(w, id, caller, now)

        "cancel" -> {
            dao.dropConversation(caller)
            response(id, "done", "") to false
        }

        "undo" -> undo(w, id, caller, now)

        "migrate" -> migrate(w, id, now)

        "complete_by_legacy_id" -> legacy(w, id)

        "status" -> status(id, now) to false

        "ramble" -> response(id, "declined", "Brain dumps aren't ready in the task app yet.") to false

        else -> errorResponse(id, "bad_request", "type") to false
    }

    // ---- utterance and follow_up ----------------------------------------------------------------

    private suspend fun converse(w: Wire, id: String, caller: String, now: Instant): Pair<String, Boolean> {
        val req = w.request(caller) ?: return errorResponse(id, "bad_request", "context") to false
        dao.purgeConversations(now)
        val store = RoomStore(context)
        val guarded = Guarded(store)
        val handler = CommandHandler(guarded)
        loadMemory(handler, store, caller, req)
        var steps = JsonArray(emptyList())
        var convId: String? = null
        if (req.type == "follow_up") {
            val row = dao.conversationOf(caller)
            if (row == null || row.id != w.conversationId || row.expiresAt < now) {
                dao.dropConversation(caller)
                return errorResponse(id, "conversation_expired") to false
            }
            steps = Json.parseToJsonElement(row.draftJson).jsonArray
            try {
                for (s in steps) {
                    convId =
                        handler.handle(stepRequest(s.jsonObject, caller, convId)).also {
                            check(it.status == "needs_input")
                        }.conversation?.id
                }
            } catch (e: IllegalStateException) {
                dao.dropConversation(caller)
                return errorResponse(id, "conversation_expired", "replay: ${e.message}") to false
            }
        } else {
            dao.dropConversation(caller)
        }
        guarded.frozen = false
        val r = handler.handle(req.copy(conversationId = convId))
        saveMemory(handler, caller, req)
        val zone = req.now.zone
        val reply = if (r.status == "needs_input") {
            val cid = newId("c_")
            val step = buildJsonObject {
                put("type", req.type)
                put("utterance", req.utterance)
                put("option_id", req.optionId)
                put("now", req.now.toInstant().toEpochMilli())
                put("end", req.speechEnd.toEpochMilli())
                put("zone", zone.id)
                put("typed", req.typed)
            }
            val all = JsonArray(steps + step)
            val c = checkNotNull(r.conversation)
            dao.upsert(
                Conversation(
                    cid,
                    caller,
                    now,
                    req.now.toInstant().plusSeconds(QUESTION_TTL_SECONDS),
                    MAX_ROUND - c.roundsLeft,
                    c.expects,
                    all.toString(),
                    all.first().jsonObject["utterance"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ),
            )
            r.copy(conversation = c.copy(id = cid))
        } else {
            dao.dropConversation(caller)
            r
        }
        val mutated = isMutation(reply)
        val view = reply.taskId?.let { store.view(it) }
        val token = if (mutated) store.record(caller, now, reply.effect!!, summary(reply, view?.title)) else null
        val previous = reply.taskId?.let {
            store.previous(it)?.let { t -> t.dueDate?.let { d -> DueSpec(d, t.dueTime, t.dueZone) } }
        }
        return fromHandler(id, reply, req.now.toInstant(), zone, view, previous, token) to mutated
    }

    private fun stepRequest(s: JsonObject, caller: String, convId: String?) = Request(
        type = s["type"]!!.jsonPrimitive.content,
        utterance = s["utterance"]?.jsonPrimitive?.contentOrNull,
        conversationId = convId,
        optionId = s["option_id"]?.jsonPrimitive?.contentOrNull,
        client = caller,
        now = Instant.ofEpochMilli(
            s["now"]!!.jsonPrimitive.content.toLong(),
        ).atZone(ZoneId.of(s["zone"]!!.jsonPrimitive.content)),
        speechEnd = Instant.ofEpochMilli(s["end"]!!.jsonPrimitive.content.toLong()),
        typed = s["typed"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    private fun summary(r: Response, title: String?): String = when (r.effect) {
        "created" -> "I've removed '$title'."
        "completed" -> "I've reopened '$title'."
        "deleted" -> "I've brought back '$title'."
        "rescheduled" -> "I've put '$title' back."
        "changed" -> "I've changed '$title' back."
        "project_created" -> "I've removed the ${r.name} list."
        else -> "I've removed the ${r.name} label."
    }

    /** The referent and the last list live in Room (4.11), so a process death forgets nothing. */
    private suspend fun loadMemory(handler: CommandHandler, store: RoomStore, caller: String, req: Request) {
        val row = dao.referentOf(caller)?.takeIf { it.expiresAt > req.now.toInstant() } ?: return
        if (row.taskId.isNotEmpty()) handler.referents[caller] = row.taskId to row.expiresAt
        row.listedJson?.let { j ->
            val ids = Json.parseToJsonElement(j).jsonArray.map { it.jsonPrimitive.content }
            val open = store.snapshot().openTasks.associateBy { it.id }
            handler.listed[caller] = ids.mapNotNull { open[it] }
        }
    }

    private suspend fun saveMemory(handler: CommandHandler, caller: String, req: Request) {
        val ref = handler.referents[caller]
        val list = handler.listed[caller]
        if (ref == null && list == null) return dao.dropReferent(caller)
        dao.upsert(
            Referent(
                caller,
                ref?.first.orEmpty(),
                ref?.second ?: req.now.toInstant().plus(REFERENT_TTL),
                list?.let { l -> buildJsonArray { l.forEach { add(JsonPrimitive(it.id)) } }.toString() },
            ),
        )
    }

    // ---- undo, status, migration -----------------------------------------------------------------

    private suspend fun undo(w: Wire, id: String, caller: String, now: Instant): Pair<String, Boolean> {
        val token = w.undoToken ?: return errorResponse(id, "bad_request", "undo_token") to false
        val r = Journal(db).undo(token, caller, now)
        if (!r.ok) return response(id, "declined", r.speech) to false
        val effect = buildJsonObject {
            put("op", "undone")
            put("undone_op", r.op)
            putJsonArray("task_ids") { r.taskIds.forEach { add(JsonPrimitive(it)) } }
        }
        return response(id, "done", r.speech, effects = listOf(effect)) to true
    }

    private suspend fun status(id: String, now: Instant): String {
        val open = dao.openTasks()
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val am = context.getSystemService(AlarmManager::class.java)
        val migrated = dao.finishedRuns(SOURCE_MD) > 0
        val health = buildJsonObject {
            put("contract_version", CONTRACT_VERSION)
            put(
                "counts",
                buildJsonObject {
                    put("open", open.size)
                    put("overdue", open.count { (it.dueDate ?: today) < today })
                    put("due_today", open.count { it.dueDate == today })
                },
            )
            put(
                "permissions",
                buildJsonObject {
                    put(
                        "notifications",
                        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
                    )
                    put("exact_alarm", am.canScheduleExactAlarms())
                    put(
                        "battery_unrestricted",
                        context.getSystemService(
                            PowerManager::class.java,
                        ).isIgnoringBatteryOptimizations(context.packageName),
                    )
                },
            )
            putJsonArray("migrated") { if (migrated) add(JsonPrimitive(SOURCE_MD)) }
        }
        return response(id, "done", "", extra = mapOf("health" to health))
    }

    private suspend fun migrate(w: Wire, id: String, now: Instant): Pair<String, Boolean> {
        val lines = w.lines
        val c = w.chunk
        val index = (c?.get("index") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        val count = (c?.get("count") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        val sha = (c?.get("file_sha256") as? JsonPrimitive)?.contentOrNull
        if (lines == null || index == null || count == null ||
            sha == null
        ) {
            return errorResponse(id, "bad_request", "migrate") to false
        }
        val r = AssistantMarkdownImporter(
            Repository(context),
            db,
        ).import(lines, index, count, sha, now, ZoneId.systemDefault())
        val effect = buildJsonObject {
            put("op", "migrated")
            put("chunk_index", index)
            put("imported", r.imported)
            put("already_imported", r.already)
            put("needs_review", r.needsReview)
            put("non_task_lines", r.nonTask)
            put("done_all", r.doneAll)
        }
        val speech = "Imported ${r.imported} ${if (r.imported == 1) "task" else "tasks"} from your old list."
        return response(id, "done", speech, effects = listOf(effect)) to true
    }

    private suspend fun legacy(w: Wire, id: String): Pair<String, Boolean> {
        val local = w.legacyId?.let { dao.imported(SOURCE_MD, it) }
        val view = local?.let { RoomStore(context).view(it) } ?: return response(id, "declined", "") to false
        Repository(context).complete(local)
        return response(id, "done", "", effects = listOf(taskEffect("completed", view))) to true
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val MAX_ROUND = 4
        val writer = Executors.newSingleThreadExecutor { Thread(it, "tasks-commands") }.asCoroutineDispatcher()
        val inFlight = ConcurrentHashMap<String, CompletableDeferred<String>>()
    }
}
