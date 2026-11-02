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

package io.github.ijazshare.tasks.ipc

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.github.ijazshare.tasks.data.AssistantMarkdownImporter
import io.github.ijazshare.tasks.data.Repository
import io.github.ijazshare.tasks.data.TasksDb
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The Room side of the contract on a device: undo, persisted conversations and referents, dedupe,
 * migration, `status` and the latency target (4.3, 4.7, 4.8, 4.13, 4.10). It runs in the app's process
 * against its real database, as ReminderMachineryTest does: every row it makes carries "zzqtest"
 * (or the client "test.client", or the file hash "zzqsha") and is hard-deleted afterwards; nothing of
 * the owner's is touched. WARNING: never run it with `connectedDebugAndroidTest` (that uninstalls the
 * app and its data); use `adb install -t` and `am instrument` as ReminderMachineryTest says.
 */
class CommandsTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = TasksDb.get(ctx)
    private val commands = Commands(ctx)
    private var n = 0

    @Before fun setUp() = runBlocking {
        Repository(ctx).apply {
            ensureInbox()
            ensureLabels()
        }
        wipe()
    }

    @After fun tearDown() = runBlocking {
        wipe()
        io.github.ijazshare.tasks.reminders.ReminderScheduler(ctx).rearm()
    }

    private fun wipe() {
        val w = db.openHelper.writableDatabase
        val mine = "(SELECT id FROM task WHERE title LIKE '%zzqtest%' OR sourceUtterance LIKE '%zzqtest%')"
        listOf(
            "DELETE FROM reminder WHERE taskId IN $mine",
            "DELETE FROM task_label WHERE taskId IN $mine",
            "DELETE FROM completion_log WHERE taskId IN $mine",
            "DELETE FROM task_fts WHERE taskId IN $mine",
            "DELETE FROM task WHERE id IN $mine",
            "DELETE FROM undo_journal WHERE client = '$CLIENT'",
            "DELETE FROM conversation WHERE client = '$CLIENT'",
            "DELETE FROM referent WHERE client = '$CLIENT'",
            "DELETE FROM request_log WHERE requestId LIKE 'zzq-%'",
            "DELETE FROM import_map WHERE importRunId LIKE '%zzqsha%'",
            "DELETE FROM import_run WHERE id LIKE '%zzqsha%'",
            "DELETE FROM project WHERE name = 'Import needs review' AND NOT EXISTS (SELECT 1 FROM task WHERE projectId = project.id)",
        ).forEach { w.execSQL(it) }
    }

    private fun request(
        type: String,
        utterance: String? = null,
        id: String = "zzq-${++n}-${System.nanoTime()}",
        extra: (kotlinx.serialization.json.JsonObjectBuilder) -> Unit = {
        },
    ): String =
        buildJsonObject {
            put("v", 1)
            put("request_id", id)
            put("type", type)
            if (utterance != null) put("utterance", utterance)
            extra(this)
            putJsonObject("context") {
                val now = System.currentTimeMillis()
                put("now_epoch_ms", now)
                put("speech_end_epoch_ms", now)
                put("zone", ZoneId.systemDefault().id)
                put("locale", "en-GB")
                put("hour_cycle", "h12")
                put("source", "voice")
                put("round", 0)
                put("routed_by", "keyword")
            }
        }.toString()

    private fun send(json: String): JsonObject = Json.parseToJsonElement(commands.handle(json, CLIENT)).jsonObject

    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content

    private fun JsonObject.undoToken() = this["undo"]!!.jsonObject.s("token")

    private fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use {
        it.moveToFirst()
        it.getInt(0)
    }

    private fun live(like: String) = count(
        "SELECT COUNT(*) FROM task WHERE title LIKE '%$like%' AND deletedAt IS NULL AND completedAt IS NULL",
    )

    // ---- undo (4.8) -------------------------------------------------------------------------------

    @Test fun createdThenUndoneThenCannotBeUndoneTwice() {
        val r = send(request("utterance", "remind me to zzqtest alpha tomorrow at 9 am"))
        assertThat(r.s("status")).isEqualTo("done")
        assertThat(live("zzqtest alpha")).isEqualTo(1)
        val token = r.undoToken()
        val u = send(request("undo") { it.put("undo_token", token) })
        assertThat(u.s("status")).isEqualTo("done")
        assertThat(u.s("speech")).startsWith("Undone. I've removed 'Zzqtest alpha'")
        assertThat(u["effects"]!!.jsonArray[0].jsonObject.s("op")).isEqualTo("undone")
        assertThat(live("zzqtest alpha")).isEqualTo(0)
        assertThat(
            count(
                "SELECT COUNT(*) FROM reminder WHERE taskId IN (SELECT id FROM task WHERE title LIKE '%zzqtest alpha%') AND deletedAt IS NULL",
            ),
        ).isEqualTo(0)
        val again = send(request("undo") { it.put("undo_token", token) })
        assertThat(again.s("status")).isEqualTo("declined")
        assertThat(again.s("speech")).startsWith("That can't be undone any more")
    }

    @Test fun deleteIsUndoneWithItsReminders() {
        send(request("utterance", "remind me to zzqtest bravo tomorrow at 9 am"))
        val remindersBefore =
            count(
                "SELECT COUNT(*) FROM reminder WHERE taskId IN (SELECT id FROM task WHERE title LIKE '%zzqtest bravo%') AND deletedAt IS NULL",
            )
        assertThat(remindersBefore).isGreaterThan(0)
        val d = send(request("utterance", "delete the zzqtest bravo task"))
        assertThat(d.s("status")).isEqualTo("done")
        assertThat(live("zzqtest bravo")).isEqualTo(0)
        assertThat(send(request("undo") { it.put("undo_token", d.undoToken()) }).s("status")).isEqualTo("done")
        assertThat(live("zzqtest bravo")).isEqualTo(1)
        assertThat(
            count(
                "SELECT COUNT(*) FROM reminder WHERE taskId IN (SELECT id FROM task WHERE title LIKE '%zzqtest bravo%') AND deletedAt IS NULL",
            ),
        ).isEqualTo(remindersBefore)
    }

    @Test fun undoIsRefusedWhenTheTaskChangedSince() = runBlocking {
        val r = send(request("utterance", "remind me to zzqtest charlie tomorrow at 9 am"))
        val id = r["effects"]!!.jsonArray[0].jsonObject.s("task_id")
        val repo = Repository(ctx)
        Thread.sleep(5)
        repo.save(repo.task(id)!!.copy(title = "Zzqtest charlie edited"))
        val u = send(request("undo") { it.put("undo_token", r.undoToken()) })
        assertThat(u.s("status")).isEqualTo("declined")
        assertThat(u.s("speech")).startsWith("That task has changed since")
        assertThat(live("zzqtest charlie edited")).isEqualTo(1)
    }

    @Test fun recurringCompletionUndoRestoresTheDueAndRemovesTheLog() {
        val c = send(request("utterance", "remind me to zzqtest delta every day at 9 am"))
        val effect = c["effects"]!!.jsonArray[0].jsonObject
        val dueBefore = effect["due"]!!.jsonObject.s("date")
        val done = send(request("utterance", "mark zzqtest delta as done"))
        assertThat(done.s("status")).isEqualTo("done")
        assertThat(count("SELECT COUNT(*) FROM completion_log WHERE taskId = '${effect.s("task_id")}'")).isEqualTo(1)
        assertThat(send(request("undo") { it.put("undo_token", done.undoToken()) }).s("status")).isEqualTo("done")
        assertThat(count("SELECT COUNT(*) FROM completion_log WHERE taskId = '${effect.s("task_id")}'")).isEqualTo(0)
        assertThat(
            db.openHelper.readableDatabase.query("SELECT dueDate FROM task WHERE id = '${effect.s("task_id")}'").use {
                it.moveToFirst()
                it.getString(0)
            },
        ).isEqualTo(dueBefore)
    }

    // ---- conversations, referents (4.7, 4.11) ------------------------------------------------------

    @Test fun aQuestionSurvivesAndIsAnsweredWithTheFrozenInstants() {
        val q = send(request("utterance", "remind me to zzqtest echo"))
        assertThat(q.s("status")).isEqualTo("needs_input")
        val conv = q["conversation"]!!.jsonObject
        assertThat(conv.s("expects")).isEqualTo("date_time")
        assertThat(live("zzqtest echo")).isEqualTo(0)
        assertThat(count("SELECT COUNT(*) FROM conversation WHERE client = '$CLIENT'")).isEqualTo(1)
        // A fresh Commands (as after a process death) still has the conversation, because it is in Room.
        val a = Commands(ctx).handle(
            request("follow_up", "tomorrow at 10 am") {
                it.put("conversation_id", conv.s("id"))
            },
            CLIENT,
        )
        val r = Json.parseToJsonElement(a).jsonObject
        assertThat(r.s("status")).isEqualTo("done")
        assertThat(live("zzqtest echo")).isEqualTo(1)
        assertThat(count("SELECT COUNT(*) FROM conversation WHERE client = '$CLIENT'")).isEqualTo(0)
    }

    @Test fun aFollowUpWithAnUnknownConversationIsExpired() {
        val r = send(request("follow_up", "tomorrow") { it.put("conversation_id", "c_nope") })
        assertThat(r.s("status")).isEqualTo("error")
        assertThat(r["error"]!!.jsonObject.s("code")).isEqualTo("conversation_expired")
    }

    @Test fun aNewUtteranceCancelsTheOpenConversation() {
        send(request("utterance", "remind me to zzqtest foxtrot"))
        assertThat(count("SELECT COUNT(*) FROM conversation WHERE client = '$CLIENT'")).isEqualTo(1)
        send(request("utterance", "Thank you."))
        assertThat(count("SELECT COUNT(*) FROM conversation WHERE client = '$CLIENT'")).isEqualTo(0)
    }

    @Test fun thatMeansTheLastTaskEvenAfterTheProcessIsGone() {
        send(request("utterance", "remind me to zzqtest golf tomorrow at 8 am"))
        val r = Json.parseToJsonElement(
            Commands(ctx).handle(request("utterance", "push that to Friday"), CLIENT),
        ).jsonObject
        assertThat(r.s("status")).isEqualTo("done")
        assertThat(r["effects"]!!.jsonArray[0].jsonObject.s("op")).isEqualTo("rescheduled")
        assertThat(r["effects"]!!.jsonArray[0].jsonObject.s("title")).isEqualTo("Zzqtest golf")
    }

    // ---- dedupe (4.3) ---------------------------------------------------------------------------

    @Test fun theSameRequestIdRunsOnceAndReturnsTheSameAnswer() = runBlocking {
        val json = request("utterance", "remind me to zzqtest hotel tomorrow at 9 am", id = "zzq-dup-1")
        val both = (1..2).map { async { commands.handleAsync(json, CLIENT) } }.awaitAll()
        assertThat(both[0]).isEqualTo(both[1])
        assertThat(live("zzqtest hotel")).isEqualTo(1)
        assertThat(commands.handle(json, CLIENT)).isEqualTo(both[0])
        assertThat(live("zzqtest hotel")).isEqualTo(1)
    }

    @Test fun errorsAreNotStoredAndHugeRequestsAreRejected() {
        val bad = send("""{"v":1,"request_id":"zzq-err-1","type":"follow_up","conversation_id":"x","utterance":"hi"}""")
        assertThat(bad["error"]!!.jsonObject.s("code")).isNotEmpty()
        assertThat(count("SELECT COUNT(*) FROM request_log WHERE requestId = 'zzq-err-1'")).isEqualTo(0)
        val huge = send(request("utterance", "x".repeat(MAX_REQUEST_CHARS)))
        assertThat(huge["error"]!!.jsonObject.s("code")).isEqualTo("bad_request")
        assertThat(send("{}")["error"]!!.jsonObject.s("code")).isEqualTo("bad_request")
        assertThat(
            send(request("utterance", "hi").replace("\"v\":1", "\"v\":9"))["error"]!!.jsonObject.s("code"),
        ).isEqualTo("unsupported_version")
    }

    // ---- migration (4.13) -------------------------------------------------------------------------

    private val lines = listOf(
        "# Tasks",
        "",
        "> The assistant appends to this file when you ask it to remember something.",
        "- [ ] zzqtest future  <!-- id:zzq00001 due:2099-01-05T09:00 added 2026-10-27 09:04 -->",
        "- [x] zzqtest finished  <!-- id:zzq00002 delivered added 2026-10-27 12:24 -->",
        "- [ ] zzqtest seen — due Tue 27 Oct, 7:00 PM  <!-- id:zzq00003 added 2026-10-27 12:52 -->",
        "- [ ] zzqtest by hand — due tomorrow at 5pm",
        "- [ ] zzqtest unreadable — due whenever it suits",
        "- [ ] zzqtest plain",
    )

    private fun migrateRequest(sha: String = "zzqsha1") = request("migrate") {
        it.putJsonArray("lines") { lines.forEach { l -> add(JsonPrimitive(l)) } }
        it.putJsonObject("chunk") {
            put("index", 0)
            put("count", 1)
            put("file_sha256", sha)
        }
    }

    @Test fun migrationImportsOnceAndAKnownIdCompletesByLegacyId() {
        val r = send(migrateRequest())
        val e = r["effects"]!!.jsonArray[0].jsonObject
        assertThat(e.s("imported")).isEqualTo("6")
        assertThat(e.s("non_task_lines")).isEqualTo("2")
        assertThat(e.s("needs_review")).isEqualTo("1")
        assertThat(e.s("done_all")).isEqualTo("true")
        // zzq00001: due in 2099, a reminder armed; zzq00002 done; zzq00003 seen: due shown, no reminder.
        assertThat(
            count(
                "SELECT COUNT(*) FROM task WHERE sourceUtterance LIKE '%zzq00001%' AND dueDate = '2099-01-05' AND dueTime = '09:00' AND dueZone IS NULL",
            ),
        ).isEqualTo(1)
        assertThat(
            count(
                "SELECT COUNT(*) FROM reminder WHERE deletedAt IS NULL AND taskId IN (SELECT id FROM task WHERE sourceUtterance LIKE '%zzq00001%')",
            ),
        ).isGreaterThan(0)
        assertThat(
            count("SELECT COUNT(*) FROM task WHERE sourceUtterance LIKE '%zzq00002%' AND completedAt IS NOT NULL"),
        ).isEqualTo(1)
        assertThat(
            count("SELECT COUNT(*) FROM task WHERE sourceUtterance LIKE '%zzq00003%' AND dueDate = '2026-10-27'"),
        ).isEqualTo(1)
        assertThat(
            count(
                "SELECT COUNT(*) FROM reminder WHERE taskId IN (SELECT id FROM task WHERE sourceUtterance LIKE '%zzq00003%')",
            ),
        ).isEqualTo(0)
        assertThat(
            count(
                "SELECT COUNT(*) FROM task t JOIN project p ON p.id = t.projectId WHERE t.sourceUtterance LIKE '%unreadable%' AND p.name = 'Import needs review' AND t.dueDate IS NULL",
            ),
        ).isEqualTo(1)
        // Sent again, nothing is added twice.
        val again = send(migrateRequest())["effects"]!!.jsonArray[0].jsonObject
        assertThat(again.s("imported")).isEqualTo("0")
        assertThat(again.s("already_imported")).isEqualTo("6")
        assertThat(count("SELECT COUNT(*) FROM task WHERE sourceUtterance LIKE '%zzqtest%'")).isEqualTo(6)
        val status = send(request("status"))
        assertThat(
            status["health"]!!.jsonObject["migrated"]!!.jsonArray.map {
                it.jsonPrimitive.content
            },
        ).contains("assistant_md")
        val c = send(request("complete_by_legacy_id") { it.put("legacy_id", "zzq00001") })
        assertThat(c.s("status")).isEqualTo("done")
        assertThat(
            count("SELECT COUNT(*) FROM task WHERE sourceUtterance LIKE '%zzq00001%' AND completedAt IS NOT NULL"),
        ).isEqualTo(1)
        assertThat(
            send(
                request("complete_by_legacy_id") {
                    it.put("legacy_id", "nosuchid")
                },
            ).s("status"),
        ).isEqualTo("declined")
    }

    /** A reminder that never fired, or fired unanswered, gets exactly one AT_DUE row, which the scheduler delivers once as "Missed" (it may already have, so lastFiredAt is not checked). */
    @Test fun aPastReminderBecomesExactlyOneRow() = runBlocking {
        val past = listOf(
            "- [ ] zzqtest never fired  <!-- id:zzq00011 due:2026-10-01T09:00 added 2026-09-30 09:00 -->",
            "- [ ] zzqtest unanswered — due Tue 27 Oct, 7:00 PM  <!-- id:zzq00012 delivered added 2026-10-27 12:52 -->",
        )
        db.withTransaction {
            AssistantMarkdownImporter(
                Repository(ctx),
                db,
            ).import(past, 0, 1, "zzqsha2", Instant.now(), ZoneId.systemDefault())
        }
        for (id in listOf("zzq00011", "zzq00012")) {
            assertThat(
                count(
                    "SELECT COUNT(*) FROM reminder WHERE kind = 'AT_DUE' AND deletedAt IS NULL AND taskId IN (SELECT id FROM task WHERE sourceUtterance LIKE '%$id%')",
                ),
            ).isEqualTo(1)
        }
    }

    // ---- latency (4.10) ---------------------------------------------------------------------------

    @Test fun handleStaysInsideTheLatencyTarget() {
        send(request("utterance", "remind me to zzqtest warm up tomorrow at 9 am"))
        val times = (1..40).map {
            val json = request("utterance", "remind me to zzqtest bench $it tomorrow at 9 am")
            val t0 = System.nanoTime()
            val r = send(json)
            assertThat(r.s("status")).isEqualTo("done")
            (System.nanoTime() - t0) / 1_000_000
        }.sorted()
        val p50 = times[times.size / 2]
        val p95 = times[(times.size * 95) / 100 - 1]
        Log.i("TasksBench", "handle() create: p50=${p50}ms p95=${p95}ms max=${times.last()}ms n=${times.size}")
        assertThat(p95).isAtMost(150L)
    }

    private companion object {
        const val CLIENT = "test.client"
    }
}
