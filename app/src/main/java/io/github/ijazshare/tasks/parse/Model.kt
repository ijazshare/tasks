/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength", "MagicNumber")

package io.github.ijazshare.tasks.parse

import io.github.ijazshare.tasks.time.DueSpec
import io.github.ijazshare.tasks.time.RecurrenceRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Shapes shared by the parser and the command handler (spec 4 and 6.2).

enum class Intent { CREATE, QUERY, COMPLETE, RESCHEDULE, DELETE, CHANGE, CREATE_PROJECT, CREATE_LABEL, RAMBLE, NOT_MINE }

/** Policy constants of spec 8.3.1. The two `ask` switches are the owner's "always ask" alternatives (Q4). */
data class ParseSettings(
    val morning: LocalTime = LocalTime.of(9, 0),
    val afternoon: LocalTime = LocalTime.of(14, 0),
    val evening: LocalTime = LocalTime.of(19, 0),
    val tonight: LocalTime = LocalTime.of(20, 0),
    /** D1 alternative: ask am or pm every time. */
    val askBareHour: Boolean = false,
    /** D2 alternative: ask "What time?" with the named time as an option. */
    val askNamedTime: Boolean = false,
)

data class ProjectInfo(val name: String, val sections: List<String> = emptyList())

data class OpenTask(
    val id: String,
    val title: String,
    val due: DueSpec? = null,
    val priority: Int = 4,
    val project: String = "Inbox",
    val overdue: Boolean = false,
)

data class ParseContext(
    val now: ZonedDateTime,
    val speechEnd: Instant = now.toInstant(),
    val typed: Boolean = false,
    val h12: Boolean = true,
    val projects: List<ProjectInfo> = listOf(ProjectInfo("Inbox")),
    val labels: List<String> = emptyList(),
    val openTasks: List<OpenTask> = emptyList(),
    val settings: ParseSettings = ParseSettings(),
    /** Words the user tapped back into plain text (6.3): never parsed as a slot. */
    val plain: Set<String> = emptySet(),
) {
    val zone: ZoneId get() = now.zone
    val today: LocalDate get() = now.toLocalDate()
}

sealed interface SlotValue<out T> {
    data class Parsed<T>(val v: T, val span: IntRange) : SlotValue<T>

    data class Ambiguous<T>(val options: List<T>, val span: IntRange) : SlotValue<T>

    data object Absent : SlotValue<Nothing>
}

/** SCHEDULE: the alerts come from the task's tags (8.6). AT_INSTANT: a relative time, exact to the second. */
data class ReminderSpec(val kind: String, val at: Instant? = null)

data class ProjectRef(val name: String, val section: String? = null, val create: Boolean = false)

data class LabelRef(val name: String, val create: Boolean = false)

data class DefaultUsed(val id: String, val said: String)

/** A highlighted span of the typed text; [kind] is date, time, recurrence, priority, project, label, deadline, duration. */
data class Chip(val kind: String, val span: IntRange)

sealed interface Target {
    data object Referent : Target

    data class Words(val text: String) : Target
}

data class Option(val id: String, val label: String, val next: Draft)

data class Question(
    val expects: String,
    val text: String,
    val options: List<Option> = emptyList(),
    val draft: Draft? = null,
)

sealed interface Verdict {
    data object Done : Verdict

    data class Ask(val question: Question) : Verdict

    data class Decline(val speech: String) : Verdict

    data object NotMine : Verdict
}

data class ParseResult(
    val intent: Intent,
    val title: String?,
    val titleSpans: List<IntRange>,
    val due: SlotValue<DueSpec>,
    val recurrence: SlotValue<RecurrenceRule>,
    val reminder: SlotValue<ReminderSpec>,
    val deadline: SlotValue<LocalDate>,
    val priority: SlotValue<Int>,
    val project: SlotValue<ProjectRef>,
    val labels: List<LabelRef>,
    val unresolved: List<IntRange>,
    val selfCorrection: Boolean,
    val appliedDefaults: List<DefaultUsed>,
    // Beyond the spec's list: what the handler and the quick-add bar need.
    val verdict: Verdict,
    val chips: List<Chip> = emptyList(),
    val completionAnchor: Boolean = false,
    val recurrenceText: String? = null,
    val description: String? = null,
    val durationMin: Int? = null,
    val target: Target? = null,
    val scope: String? = null,
    val snooze: Duration? = null,
    val keepTime: Boolean = false,
    val allDayDue: Boolean = false,
    val rambleBody: String? = null,
    val taskId: String? = null,
)
