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
    "LongParameterList",
    "CyclomaticComplexMethod",
    "ComplexCondition",
)

package io.github.ijazshare.tasks.data

import io.github.ijazshare.tasks.parse.ParseContext
import io.github.ijazshare.tasks.parse.SlotValue
import io.github.ijazshare.tasks.parse.parse
import io.github.ijazshare.tasks.time.DueSpec
import io.github.ijazshare.tasks.time.Reminders
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Migration of Assistant's tasks.md (spec 4.13). Assistant sends raw lines; everything is read here.

const val SOURCE_MD = "assistant_md"
const val REVIEW_LIST = "Import needs review"

/** One task line of tasks.md. [key] is the comment's `id:`, else a hash of the raw line. */
class MdLine(
    val raw: String,
    val key: String,
    val legacyId: String?,
    val done: Boolean,
    val title: String,
    val dueText: String?,
    val due: LocalDateTime?,
    val delivered: Boolean,
    val added: LocalDateTime?,
)

private val TASK = Regex("""^\s*[-*]\s+\[([ xX])]\s+(.*)$""")
private val COMMENT = Regex("""\s*<!--(.*?)-->\s*$""")
private val DUE_TEXT = Regex("""\s+—\s+due\s+(.+)$""")
private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
    "%02x".format(it)
}

/** Null for a line that is not a task (a heading, a note, a blank line). */
fun parseMdLine(raw: String): MdLine? {
    val m = TASK.matchEntire(raw) ?: return null
    var body = m.groupValues[2]
    val comment = COMMENT.find(body)?.groupValues?.get(1).orEmpty()
    body = COMMENT.replace(body, "")
    val dueText = DUE_TEXT.find(body)?.groupValues?.get(1)?.trim()
    val title = DUE_TEXT.replace(body, "").trim().ifEmpty { "Reminder" }
    val id = Regex("""\bid:(\S+)""").find(comment)?.groupValues?.get(1)
    return MdLine(
        raw,
        id ?: ("md:" + sha256(raw)),
        id,
        m.groupValues[1] != " ",
        title,
        dueText,
        Regex("""\bdue:(\S+)""").find(comment)?.groupValues?.get(1)?.let {
            runCatching { LocalDateTime.parse(it) }.getOrNull()
        },
        Regex("""\bdelivered\b""").containsMatchIn(comment),
        Regex("""\badded (\d{4}-\d\d-\d\d \d\d:\d\d)""").find(comment)?.groupValues?.get(1)?.let {
            LocalDateTime.parse(it, STAMP)
        },
    )
}

/** The visible "due" words of a hand-written line, read by Tasks' own grammar against [at]; null unless exactly one date comes out. */
fun dueFromText(text: String, at: ZonedDateTime): DueSpec? {
    val r = parse("remind me to x $text", ParseContext(at))
    return (r.due as? SlotValue.Parsed)?.v?.takeIf { r.verdict == io.github.ijazshare.tasks.parse.Verdict.Done }
}

class ChunkResult(val imported: Int, val already: Int, val needsReview: Int, val nonTask: Int, val doneAll: Boolean)

/** Imports one chunk. Call inside a transaction; the caller re-arms the alarm afterwards. */
class AssistantMarkdownImporter(private val repo: Repository, private val db: TasksDb) {
    private val dao get() = db.dao()

    suspend fun import(lines: List<String>, index: Int, count: Int, fileSha: String, now: Instant, device: ZoneId): ChunkResult {
        val runId = "$SOURCE_MD:$fileSha"
        val run = dao.importRun(runId) ?: ImportRun(runId, SOURCE_MD, now, null, "[]", null)
        var imported = 0
        var already = 0
        var review = 0
        var nonTask = 0
        for (raw in lines) {
            val line = parseMdLine(raw)
            when {
                line == null -> if (raw.isNotBlank()) nonTask++

                dao.imported(SOURCE_MD, line.key) != null -> already++

                else -> {
                    val inReview = importLine(line, runId, now, device)
                    imported++
                    if (inReview) review++
                }
            }
        }
        val chunks = (
            run.summaryJson.removeSurrounding("[", "]").split(',').mapNotNull { it.trim().toIntOrNull() } +
                index
            ).toSortedSet()
        val all = chunks.size >= count
        dao.upsert(run.copy(summaryJson = chunks.joinToString(",", "[", "]"), finishedAt = if (all) now else null))
        return ChunkResult(imported, already, review, nonTask, all)
    }

    /** Returns true when the task went to the needs-review list. */
    private suspend fun importLine(l: MdLine, runId: String, now: Instant, device: ZoneId): Boolean {
        val stamp = l.added?.atZone(device) ?: now.atZone(device)
        val parsed =
            l.due?.let { DueSpec(it.toLocalDate(), it.toLocalTime(), null) }
                ?: l.dueText?.let { dueFromText(it, stamp) }
        val review = l.due == null && l.dueText != null && parsed == null
        val pid = if (review) repo.addProject(REVIEW_LIST) ?: INBOX_ID else INBOX_ID
        val task = repo.blank(pid).copy(
            title = l.title, description = if (review) "Due: ${l.dueText}" else "", dueDate = parsed?.date, dueTime = parsed?.time,
            completedAt = now.takeIf {
                l.done
            },
            createdAt = now, updatedAt = now, source = SOURCE_MD, sourceUtterance = l.raw,
        )
        // The mapping goes in first: a duplicate key (a re-sent chunk) adds nothing.
        if (dao.insert(ImportMap(SOURCE_MD, l.key, task.id, "task", runId, now)) == -1L) return false
        if (l.done) {
            dao.upsert(task)
            dao.insertFts(task.id, task.title)
            dao.insert(
                CompletionLog(
                    newId("c_"),
                    task.id,
                    now,
                    parsed?.date,
                    parsed?.time,
                    null,
                    wasRecurring = false,
                    imported = true,
                ),
            )
            return false
        }
        repo.save(task)
        if (l.due == null && !l.delivered) dao.clearReminders(task.id)
        // A reminder that never fired, or fired and was never answered, is shown once now as "Missed" (8.4).
        val at = parsed?.let { Reminders.dueInstant(it, device) }
        if (parsed != null && at != null && at <= now && (l.due != null || l.delivered)) {
            dao.upsert(
                listOf(
                    Reminder(
                        newId(
                            "r_",
                        ),
                        task.id, "AT_DUE", null, null, null, null, at, at, null, null, false, now, now, null,
                    ),
                ),
            )
        }
        return review
    }
}
