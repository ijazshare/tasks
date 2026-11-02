/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength", "LongMethod")

package io.github.ijazshare.tasks.ipc

import com.google.common.truth.Truth.assertThat
import io.github.ijazshare.tasks.parse.CommandHandler
import io.github.ijazshare.tasks.parse.FakeStore
import io.github.ijazshare.tasks.parse.Intent
import io.github.ijazshare.tasks.parse.OpenTask
import io.github.ijazshare.tasks.parse.ParseContext
import io.github.ijazshare.tasks.parse.ProjectInfo
import io.github.ijazshare.tasks.parse.Response
import io.github.ijazshare.tasks.parse.TaskView
import io.github.ijazshare.tasks.parse.parse
import io.github.ijazshare.tasks.time.DueSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Spec 13.4. `contract/v1/` holds request/response pairs. This test produces each response from its
 * request with a fixed clock (Tue 27 Oct 2026 14:03 Europe/London) and compares it with the file;
 * `CONTRACT_WRITE=1` rewrites the files (and MANIFEST.sha256) instead, then run
 * `contract/sync-to-assistant.sh`. Pairs for undo, migrate, status and the error codes are built from
 * fixed numbers here; the Room paths behind them are covered on a device (CommandsTest).
 */
class ContractTest {
    private val dir = File("../contract/v1")
    private val zone = ZoneId.of("Europe/London")
    private val now = ZonedDateTime.of(LocalDateTime.of(2026, 10, 27, 14, 3), zone)
    private val pretty = Json { prettyPrint = true }
    private val open = listOf(
        OpenTask("t_Amira00001", "Call Amira", project = "Inbox"),
        OpenTask("t_Dentist001", "Call Amira's dentist", project = "Inbox"),
        OpenTask(
            "t_Gym0000001",
            "Gym at 6",
            DueSpec(LocalDate.of(2026, 10, 27), LocalTime.of(18, 0), null),
            project = "Home",
        ),
        OpenTask("t_GasBill001", "Pay the gas bill", project = "Inbox"),
    )

    private fun request(
        index: Int,
        type: String,
        utterance: String? = null,
        extra: (kotlinx.serialization.json.JsonObjectBuilder) -> Unit = {
        },
    ) = buildJsonObject {
        put("v", 1)
        put("request_id", "00000000-0000-4000-8000-%012d".format(index))
        put("type", type)
        if (utterance != null) put("utterance", utterance)
        extra(this)
        putJsonObject("context") {
            put("now_epoch_ms", now.toInstant().toEpochMilli())
            put("speech_end_epoch_ms", now.toInstant().toEpochMilli() - 600)
            put("zone", "Europe/London")
            put("locale", "en-GB")
            put("hour_cycle", "h12")
            put("source", "voice")
            put("round", 0)
            put("routed_by", if (type == "follow_up") "follow_up" else "keyword")
        }
        putJsonObject("client") {
            put("package", "io.github.ijazshare.assistant")
            put("version", "0.1.0")
        }
    }

    /** One fixture: the utterances said in a fresh session (the last is the fixture, earlier ones are its questions). */
    private class Case(val name: String, val said: List<String>, val option: String? = null)

    private val cases = listOf(
        Case("created", listOf("remind me to call Amira at 5")),
        Case("created-recurring", listOf("remind me to take my vitamins every day at 9am")),
        Case("needs-input-date", listOf("remind me next Friday to renew the parking permit")),
        Case("needs-input-time", listOf("remind me next Friday to renew the parking permit", "the sixth")),
        Case(
            "created-after-follow-up",
            listOf("remind me next Friday to renew the parking permit", "the sixth", "9 am"),
        ),
        Case("needs-input-date-time", listOf("remind me to water the plants")),
        Case("needs-input-title", listOf("remind me in an hour and a half")),
        Case("needs-input-choice", listOf("add prune the roses to the garden list")),
        Case("needs-input-confirm", listOf("remind me at 1pm to ring the school")),
        Case("needs-input-task", listOf("mark Amira as done")),
        Case("follow-up-option", listOf("remind me next Friday to renew the parking permit"), "o1"),
        Case("declined", listOf("remind me on the 31st of November to file the claim")),
        Case("declined-cancel", listOf("remind me to water the plants", "never mind")),
        Case("not-mine", listOf("Thank you.")),
        Case("completed", listOf("mark call Amira as done")),
        Case("deleted", listOf("delete the gym task")),
        Case("rescheduled", listOf("move the gym task to Thursday")),
        Case("updated", listOf("move the gas bill task to the Home list")),
        Case("listed", listOf("what's on my list today")),
        Case("project-created", listOf("create a list called Garden")),
        Case("label-created", listOf("create a label called errands")),
    )

    private fun produce(): Map<String, Pair<JsonObject, String>> = runBlocking {
        val out = linkedMapOf<String, Pair<JsonObject, String>>()
        var n = 0
        for (c in cases) {
            val store =
                FakeStore(
                    listOf(ProjectInfo("Inbox"), ProjectInfo("Home"), ProjectInfo("Shopping")),
                    listOf("calls"),
                    open,
                )
            val handler = CommandHandler(store)
            var last: Response? = null
            var req = JsonObject(emptyMap())
            var body = ""
            for ((i, said) in c.said.withIndex()) {
                val follow = i > 0
                val type = if (follow) "follow_up" else "utterance"
                req = request(++n, type, said) { b -> if (follow) b.put("conversation_id", last!!.conversation!!.id) }
                val before = store.snapshot().openTasks
                store.created = null
                val r = handler.handle(Wire(req).request("io.github.ijazshare.assistant")!!)
                val prior = before.firstOrNull { it.id == r.taskId }
                val view = r.taskId?.let {
                    store.view(it)
                        ?: prior?.let { p -> TaskView(p.id, p.title, p.project, p.due, null, null, null, null) }
                }
                val prev = prior?.due
                body =
                    fromHandler(
                        "00000000-0000-4000-8000-%012d".format(n),
                        r,
                        now.toInstant(),
                        zone,
                        view,
                        prev,
                        if (isMutation(r)) "u_41fe" else null,
                    )
                last = r
            }
            if (c.option != null) {
                req = request(++n, "follow_up", null) { b ->
                    b.put("conversation_id", last!!.conversation!!.id)
                    b.put("option_id", c.option)
                }
                val r = handler.handle(Wire(req).request("io.github.ijazshare.assistant")!!)
                val view = r.taskId?.let { store.view(it) }
                body =
                    fromHandler(
                        "00000000-0000-4000-8000-%012d".format(n),
                        r,
                        now.toInstant(),
                        zone,
                        view,
                        null,
                        if (isMutation(r)) "u_41fe" else null,
                    )
            }
            out[c.name] = req to body
        }
        out += pure(n)
        out
    }

    /** Requests the handler does not answer: built from fixed numbers. */
    private fun pure(start: Int): Map<String, Pair<JsonObject, String>> {
        var n = start
        fun id() = "00000000-0000-4000-8000-%012d".format(++n)
        val m = linkedMapOf<String, Pair<JsonObject, String>>()
        fun add(
            name: String,
            type: String,
            extra: (kotlinx.serialization.json.JsonObjectBuilder) -> Unit,
            build: (String) -> String,
        ) {
            val req = request(n + 1, type, null, extra)
            m[name] = req to build(id())
        }
        add("cancel", "cancel", { it.put("conversation_id", "c_19ab") }) { response(it, "done", "") }
        add("undo", "undo", { it.put("undo_token", "u_41fe") }) {
            response(
                it,
                "done",
                "Undone. I've removed 'Call Amira'.",
                effects = listOf(
                    buildJsonObject {
                        put("op", "undone")
                        put("undone_op", "created")
                        putJsonArray("task_ids") { add(JsonPrimitive("t_8Q2c")) }
                    },
                ),
            )
        }
        add("undo-declined", "undo", {
            it.put("undo_token", "u_used")
        }) { response(it, "declined", "That can't be undone any more. You can change it in Tasks.") }
        add("ramble-declined", "ramble", {
            it.put("utterance", "call the garage and buy printer paper")
        }) { response(it, "declined", "Brain dumps aren't ready in the task app yet.") }
        add("migrate", "migrate", {
            it.putJsonArray("lines") {
                add(
                    JsonPrimitive(
                        "- [ ] Pay rent — due Tue 3 Nov, 9:00 AM  <!-- id:ab12cd34 due:2026-11-03T09:00 added 2026-10-27 09:04 -->",
                    ),
                )
            }
            it.putJsonObject("chunk") {
                put("index", 0)
                put("count", 1)
                put("file_sha256", "0".repeat(64))
            }
        }) {
            response(
                it,
                "done",
                "Imported 1 task from your old list.",
                effects = listOf(
                    buildJsonObject {
                        put("op", "migrated")
                        put("chunk_index", 0)
                        put("imported", 1)
                        put("already_imported", 0)
                        put("needs_review", 0)
                        put("non_task_lines", 0)
                        put("done_all", true)
                    },
                ),
            )
        }
        add("complete-by-legacy-id", "complete_by_legacy_id", { it.put("legacy_id", "ab12cd34") }) {
            response(
                it,
                "done",
                "",
                effects = listOf(
                    taskEffect(
                        "completed",
                        io.github.ijazshare.tasks.parse.TaskView(
                            "t_8Q2c",
                            "Pay rent",
                            "Inbox",
                            null,
                            null,
                            null,
                            null,
                            null,
                        ),
                    ),
                ),
            )
        }
        add("complete-by-legacy-id-declined", "complete_by_legacy_id", {
            it.put("legacy_id", "ffffffff")
        }) { response(it, "declined", "") }
        add("status", "status", {}) {
            response(
                it,
                "done",
                "",
                extra = mapOf(
                    "health" to buildJsonObject {
                        put("contract_version", 1)
                        putJsonObject("counts") {
                            put("open", 4)
                            put("overdue", 0)
                            put("due_today", 1)
                        }
                        putJsonObject("permissions") {
                            put("notifications", true)
                            put("exact_alarm", true)
                            put("battery_unrestricted", false)
                        }
                        putJsonArray("migrated") { add(JsonPrimitive("assistant_md")) }
                    },
                ),
            )
        }
        for (code in listOf(
            "bad_request",
            "unsupported_version",
            "caller_not_trusted",
            "busy",
            "storage",
            "conversation_expired",
            "internal",
        )) {
            add("error-$code", "utterance", {
                it.put("utterance", "remind me to call Amira at 5")
            }) { errorResponse(it, code, "fixture") }
        }
        return m
    }

    private fun text(e: JsonElement) = pretty.encodeToString(JsonElement.serializer(), e) + "\n"

    private fun manifest(): String = dir.listFiles { f -> f.name != "MANIFEST.sha256" }!!.sortedBy { it.name }
        .joinToString("") { "${sha256(it.readText())}  ${it.name}\n" }

    private fun sha256(t: String) = io.github.ijazshare.tasks.data.sha256(t)

    @Test
    fun fixturesMatchTheHandler() {
        val produced = produce()
        val write = System.getenv("CONTRACT_WRITE") != null
        val problems = mutableListOf<String>()
        produced.entries.forEachIndexed { i, (name, pair) ->
            val label = "%02d-%s".format(i + 1, name)
            val (req, resp) = pair
            val rf = File(dir, "request-$label.json")
            val sf = File(dir, "response-$label.json")
            val reqText = text(req)
            val respText = text(Json.parseToJsonElement(resp))
            if (write) {
                rf.writeText(reqText)
                sf.writeText(respText)
            } else {
                if (!rf.exists() || Json.parseToJsonElement(rf.readText()) != req) problems += "request-$label differs"
                if (!sf.exists() ||
                    Json.parseToJsonElement(sf.readText()) != Json.parseToJsonElement(resp)
                ) {
                    problems +=
                        "response-$label differs"
                }
            }
        }
        if (write) {
            File(
                "src/main/aidl/io/github/ijazshare/tasks/ipc/ITaskCommand.aidl",
            ).copyTo(File(dir, "ITaskCommand.aidl"), true)
            File(dir, "MANIFEST.sha256").writeText(manifest())
        }
        val files = dir.listFiles { f ->
            f.name.startsWith("request-") || f.name.startsWith("response-")
        }!!.map { it.name }.toSet()
        val expected = produced.keys.indices.flatMap {
            listOf("request-%02d-", "response-%02d-").map { p -> p.format(it + 1) }
        }
        assertThat(problems).isEmpty()
        assertThat(files.size).isEqualTo(expected.size)
    }

    @Test
    fun manifestIsCurrentAndAidlIsACopy() {
        assertThat(File(dir, "MANIFEST.sha256").readText()).isEqualTo(manifest())
        assertThat(File(dir, "ITaskCommand.aidl").readText())
            .isEqualTo(File("src/main/aidl/io/github/ijazshare/tasks/ipc/ITaskCommand.aidl").readText())
    }

    @Test
    fun fixturesCoverEveryTypeStatusOpAndExpects() {
        val all = dir.listFiles { f -> f.name.startsWith("response-") }!!.joinToString("\n") { it.readText() }
        val reqs = dir.listFiles { f -> f.name.startsWith("request-") }!!.joinToString("\n") { it.readText() }
        listOf(
            "utterance",
            "follow_up",
            "cancel",
            "undo",
            "ramble",
            "migrate",
            "complete_by_legacy_id",
            "status",
        ).forEach {
            assertThat(reqs).contains("\"type\": \"$it\"")
        }
        listOf("done", "needs_input", "declined", "not_mine", "error").forEach {
            assertThat(all).contains("\"status\": \"$it\"")
        }
        listOf("created", "updated", "rescheduled", "completed", "deleted", "undone", "listed", "migrated", "project_created", "label_created").forEach {
            assertThat(all).contains("\"op\": \"$it\"")
        }
        // project, label and ramble are in the contract but nothing in the M3 handler asks them (4.7 uses choice; M4 adds ramble).
        listOf("date", "time", "date_time", "title", "choice", "confirm", "task").forEach {
            assertThat(all).contains("\"expects\": \"$it\"")
        }
    }

    @Test
    fun routingLinesAgreeWithTheParser() {
        val ctx = ParseContext(now, projects = listOf(ProjectInfo("Inbox"), ProjectInfo("Shopping")), openTasks = open)
        val bad = File(dir, "routing.jsonl").readLines().filter {
            it.isNotBlank()
        }.map { Json.parseToJsonElement(it).jsonObject }.filter {
            val claim = it["claim"]!!.jsonPrimitive.content.toBoolean()
            val intent = parse(it["utterance"]!!.jsonPrimitive.content, ctx).intent
            intent.name.lowercase() != it["intent"]!!.jsonPrimitive.content || (claim && intent == Intent.NOT_MINE)
        }
        assertThat(bad.map { it.toString() }).isEmpty()
    }

    @Test
    fun callerCheck() {
        val always = { _: String -> true }
        assertThat(
            trustedCaller(arrayOf("io.github.ijazshare.assistant.debug"), always),
        ).isEqualTo("io.github.ijazshare.assistant.debug")
        assertThat(trustedCaller(arrayOf("io.github.ijazshare.assistant"), always)).isNotNull()
        assertThat(trustedCaller(arrayOf("com.example.other"), always)).isNull()
        assertThat(trustedCaller(arrayOf("io.github.ijazshare.assistant", "com.example.other"), always)).isNull()
        assertThat(trustedCaller(arrayOf("io.github.ijazshare.assistant.debug"), { false })).isNull()
        assertThat(trustedCaller(null, always)).isNull()
        assertThat(trustedCaller(emptyArray(), always)).isNull()
    }

    @Test
    fun badRequestsAreRejectedBeforeAnyWork() {
        assertThat(Wire.parse("not json")).isNull()
        assertThat(Wire.parse("{\"v\":1,\"type\":\"utterance\"}")!!.request("x")).isNull()
        assertThat(Instant.EPOCH).isNotNull()
    }
}
