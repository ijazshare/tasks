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
    "LoopWithTooManyJumpStatements", "UnusedParameter", "MagicNumber", "ComplexCondition",
    "ktlint:standard:max-line-length",
    "MagicNumber", "TooManyFunctions", "LongMethod", "CyclomaticComplexMethod", "ReturnCount", "NestedBlockDepth",
    "LongParameterList", "ComplexCondition", "LargeClass", "MaxLineLength", "ComplexMethod", "ThrowsCount",
)

package io.github.ijazshare.tasks.parse

import io.github.ijazshare.tasks.time.DueSpec
import io.github.ijazshare.tasks.time.Freq
import io.github.ijazshare.tasks.time.RecurrenceRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

// Stages 5 and 7 of spec 6.1: entity resolution and the decider (8.3, 8.3.1).

private val SEEDS = setOf("urgent", "work", "personal", "family", "client")
private val INBOX_WORDS = setOf("", "to-do", "todo", "tasks", "task", "inbox", "my", "to do")

// ---- say helpers (4.5.4) ---------------------------------------------------------------------------------

internal fun clockSay(t: LocalTime, h12: Boolean = true): String {
    if (!h12) return t.toString().take(5)
    if (t == LocalTime.MIDNIGHT) return "midnight"
    if (t == LocalTime.NOON) return "noon"
    val h = t.hour % 12
    val hh = if (h == 0) 12 else h
    val ap = if (t.hour < 12) "am" else "pm"
    return if (t.minute == 0) "$hh $ap" else "$hh:${"%02d".format(t.minute)} $ap"
}

private fun dayName(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

private fun monthName(m: Int) = java.time.Month.of(m).getDisplayName(TextStyle.FULL, Locale.ENGLISH)

private fun ordinal(n: Int) = n.toString() + if (n in 11..13) {
    "th"
} else {
    when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
}

/** "today", "tomorrow", "Friday" (within a week), else "Friday 6 November". */
internal fun dayWord(date: LocalDate, today: LocalDate): String {
    val diff = java.time.temporal.ChronoUnit.DAYS.between(today, date)
    return when {
        diff == 0L -> "today"
        diff == 1L -> "tomorrow"
        diff in 2..6 -> dayName(date)
        else -> "${dayName(date)} ${date.dayOfMonth} ${monthName(date.monthValue)}"
    }
}

/** Question wording: "Friday the 30th" within a week, else "Friday 6 November". */
internal fun askDay(date: LocalDate, today: LocalDate): String {
    val diff = java.time.temporal.ChronoUnit.DAYS.between(today, date)
    return if (diff ==
        0L
    ) {
        "today"
    } else if (diff ==
        1L
    ) {
        "tomorrow"
    } else if (diff in
        2..7
    ) {
        "${dayName(date)} the ${ordinal(date.dayOfMonth)}"
    } else {
        "${dayName(date)} ${date.dayOfMonth} ${monthName(date.monthValue)}"
    }
}

internal fun whenSay(due: DueSpec, ctx: ParseContext): String {
    val t = due.time
    val day = dayWord(due.date, ctx.today)
    val onDay = if (day == "today" || day == "tomorrow") day else "on $day"
    return when {
        t == null -> onDay

        t == LocalTime.MIDNIGHT -> "at midnight${if (due.date == ctx.today.plusDays(
                1,
            )
        ) {
            " tonight"
        } else {
            ""
        }}, the start of ${dayName(due.date)}"

        else -> "$onDay at ${clockSay(t.withSecond(0).withNano(0), ctx.h12)}"
    }
}

internal fun recSay(rule: RecurrenceRule): String = when (rule.freq) {
    Freq.DAILY -> if (rule.interval == 1) "every day" else "every ${rule.interval} days"

    Freq.WEEKLY -> {
        val codes = rule.byDay.map { it.day }
        val names = codes.map { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH) }
        val prefix = if (rule.interval ==
            2
        ) {
            "every other week"
        } else if (rule.interval > 2) {
            "every ${rule.interval} weeks"
        } else {
            "every week"
        }
        when {
            rule.interval == 1 &&
                codes.toSet() ==
                setOf(
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY,
                ) -> "every weekday"

            rule.interval == 1 && codes.toSet() == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "every weekend"

            names.isEmpty() -> prefix

            rule.interval == 1 -> "every " + names.joinList()

            else -> "$prefix on " + names.joinList()
        }
    }

    Freq.MONTHLY -> when {
        rule.byDay.isNotEmpty() -> rule.byDay.joinToString(" and ") { dr ->
            val o = when (dr.ordinal) {
                -1 -> "last"
                1 -> "first"
                2 -> "second"
                3 -> "third"
                else -> "fourth"
            }
            "the $o ${dr.day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}"
        } + " of every month"

        rule.byMonthDay == -1 -> "every month on the last day"

        rule.byMonthDay != null -> "every month on the ${ordinal(rule.byMonthDay)}"

        else -> "every month"
    }

    Freq.YEARLY -> if (rule.byMonth != null && rule.byMonthDay != null) {
        "every year on ${rule.byMonthDay} ${monthName(rule.byMonth)}"
    } else {
        "every year"
    }
}

/** Month-end clamp (8.2, Q5), said after the time. */
internal fun clampNote(rule: RecurrenceRule): String =
    if (rule.freq == Freq.MONTHLY && (rule.byMonthDay ?: 0) > 28) ", or the last day in shorter months" else ""

private fun List<String>.joinList() = if (size <=
    1
) {
    joinToString("")
} else {
    dropLast(1).joinToString(", ") + " and " + last()
}

internal fun article(name: String) = if (name.trim().firstOrNull()?.lowercaseChar() in
    setOf('a', 'e', 'i', 'o', 'u')
) {
    "an"
} else {
    "a"
}

private fun titleCase(s: String) = s.trim().split(Regex("\\s+")).joinToString(" ") { w ->
    w.replaceFirstChar { it.uppercase() }
}

// ---- entity resolution (6.1 step 5) ----------------------------------------------------------------------

/** Damerau-Levenshtein (optimal string alignment). */
internal fun dl(a: String, b: String): Int {
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val c = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] &&
                a[i - 2] == b[j - 1]
            ) {
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
    }
    return d[a.length][b.length]
}

/** Exact (case-insensitive), then plural/singular, then edit distance 1 for names of 5+ characters with a margin of 2. */
internal fun resolveName(name: String, names: List<String>): String? {
    val k = name.trim().lowercase()
    names.firstOrNull { it.lowercase() == k }?.let { return it }
    names.firstOrNull { it.lowercase().trimEnd('s') == k.trimEnd('s') }?.let { return it }
    if (k.length < 5) return null
    val scored = names.map { it to dl(it.lowercase(), k) }.sortedBy { it.second }
    val best = scored.firstOrNull() ?: return null
    return if (best.second <= 1 && (scored.getOrNull(1)?.second ?: 99) >= 2) best.first else null
}

// ---- the decider -----------------------------------------------------------------------------------------

private class Stop(val result: ParseResult) : RuntimeException(null, null, false, false)

internal object Decider {
    fun decide(d: Draft, ctx: ParseContext): ParseResult = try {
        run(d, ctx)
    } catch (s: Stop) {
        s.result
    }

    private fun base(d: Draft, verdict: Verdict, ctx: ParseContext) = ParseResult(
        intent = d.intent, title = d.titleOverride ?: d.title, titleSpans = d.titleSpans,
        due = SlotValue.Absent, recurrence = SlotValue.Absent, reminder = SlotValue.Absent,
        deadline =
        d.deadline?.let { SlotValue.Parsed(it.v, it.span) } ?: SlotValue.Absent,
        priority = d.priority?.let { SlotValue.Parsed(it.v, it.span) } ?: SlotValue.Absent, project = SlotValue.Absent,
        labels = emptyList(), unresolved = d.unresolved, selfCorrection = d.cue != null, appliedDefaults = emptyList(),
        verdict = verdict, chips = d.chips, description = d.description, durationMin = d.durationMin, target = d.target,
        scope = d.scope, snooze = d.snooze, rambleBody = d.rambleBody, taskId = d.taskId,
    )

    private fun ask(d: Draft, ctx: ParseContext, expects: String, text: String, options: List<Option> = emptyList(), due: SlotValue<DueSpec> = SlotValue.Absent): Nothing =
        throw Stop(base(d, Verdict.Ask(Question(expects, text, options, d)), ctx).copy(due = due))

    private fun decline(d: Draft, ctx: ParseContext, speech: String): Nothing = throw Stop(
        base(d, Verdict.Decline(speech), ctx),
    )

    private fun run(d: Draft, ctx: ParseContext): ParseResult {
        if (d.intent == Intent.NOT_MINE) return base(d, Verdict.NotMine, ctx)
        d.decline?.let { decline(d, ctx, it) }
        return when (d.intent) {
            Intent.RAMBLE -> if (d.rambleBody.isNullOrBlank()) {
                ask(d, ctx, "ramble", "Go ahead, I'm listening.")
            } else {
                decline(d, ctx, "Brain dumps aren't ready yet. You can add the tasks one at a time.")
            }

            Intent.CREATE_PROJECT, Intent.CREATE_LABEL -> base(
                d,
                Verdict.Done,
                ctx,
            ).copy(title = d.newName?.let(::titleCase))

            Intent.QUERY -> query(d, ctx)

            Intent.COMPLETE, Intent.DELETE -> base(d, Verdict.Done, ctx)

            Intent.CHANGE -> change(d, ctx)

            else -> timed(d, ctx)
        }
    }

    private fun query(d: Draft, ctx: ParseContext): ParseResult {
        val r = base(d, Verdict.Done, ctx)
        val p = d.project?.v?.takeIf { it.isNotBlank() && it !in INBOX_WORDS }
        if (p == null) return r
        val found =
            resolveName(p, ctx.projects.map { it.name })
                ?: decline(d, ctx, "I don't have ${article(p)} ${titleCase(p)} list.")
        return r.copy(project = SlotValue.Parsed(ProjectRef(found), d.project.span))
    }

    private fun change(d: Draft, ctx: ParseContext): ParseResult {
        var r = base(d, Verdict.Done, ctx)
        d.project?.let { p ->
            val found = if (p.v.lowercase() in INBOX_WORDS) {
                "Inbox"
            } else {
                resolveName(p.v, ctx.projects.map { it.name })
                    ?: decline(d, ctx, "I don't have ${article(p.v)} ${titleCase(p.v)} list.")
            }
            r = r.copy(project = SlotValue.Parsed(ProjectRef(found), p.span))
        }
        val names = (ctx.labels + SEEDS).distinct()
        r = r.copy(
            labels = d.labels.map { l ->
                LabelRef(resolveName(l.v, names) ?: decline(d, ctx, "I don't have a '${l.v}' label."))
            },
        )
        return r
    }

    private fun timeQuestion(date: LocalDate?, today: LocalDate): String {
        val day = date?.let { askDay(it, today) } ?: "today"
        return "What time " + (if (day == "today" || day == "tomorrow") day else "on $day") + "?"
    }

    private fun optionOf(i: Int, label: String, next: Draft) = Option("o$i", label, next)

    private fun timed(d0: Draft, ctx: ParseContext): ParseResult {
        var d = d0
        val today = ctx.today
        val rec = d.rec?.v
        rec?.decline?.let { decline(d, ctx, it) }
        if (d.rec != null && rec?.rule == null) decline(d, ctx, "I couldn't understand that repeat.")
        // A named time next to a clock time only qualifies it.
        var times = d.times
        val clocks = times.filter { it.v.kind != TK.NAMED }
        val named = times.firstOrNull { it.v.kind == TK.NAMED }
        if (clocks.isNotEmpty() && named != null) {
            times = clocks.map { c -> Cand(applyQual(c.v, named.v.name), c.span) }
        }
        d = d.copy(times = times)
        val dates = d.dates

        // Conflicting or corrected slots: ask, offering both readings (6.1 step 7, 8.3).
        if (!d.conflictResolved) {
            for ((kind, list) in listOf("date" to dates, "time" to times)) {
                val distinct = list.distinctBy { it.v }
                if (distinct.size < 2) continue
                val (a, b) = if (d.cue != null) {
                    (list.lastOrNull { it.span.last < d.cue!!.first } ?: list.first()) to
                        (list.firstOrNull { it.span.first > d.cue!!.last } ?: list.last())
                } else {
                    distinct[0] to distinct[1]
                }

                @Suppress("UNCHECKED_CAST")
                fun pick(c: Cand<out Any>) = if (kind == "date") {
                    d.copy(dates = listOf(c as Cand<DateRaw>), conflictResolved = true)
                } else {
                    d.copy(times = listOf(c as Cand<TimeRaw>), conflictResolved = true)
                }
                val da = pick(a)
                val db = pick(b)
                val ra = decide(da, ctx)
                val rb = decide(db, ctx)
                fun label(r: ParseResult, c: Cand<*>): String =
                    (r.due as? SlotValue.Parsed)?.takeIf { r.verdict == Verdict.Done }
                        ?.let { whenSay(it.v, ctx).removePrefix("on ").replaceFirstChar { ch -> ch.uppercase() } }
                        ?: d.text.substring(c.span).trim().replaceFirstChar { ch -> ch.uppercase() }
                val la = label(ra, a)
                val lb = label(rb, b)
                ask(d, ctx, "choice", "$la, or $lb?", listOf(optionOf(1, la, da), optionOf(2, lb, db)))
            }
        }

        entities(d, ctx).let {
            d = it.first
            return finish(d, ctx, it.second)
        }
    }

    private class Ents(val project: ProjectRef?, val labels: List<LabelRef>, val projectSpan: IntRange?)

    private fun entities(d0: Draft, ctx: ParseContext): Pair<Draft, Ents> {
        var project: ProjectRef? = null
        var span: IntRange? = null
        val c = d0.project
        if (c != null && d0.intent == Intent.CREATE) {
            val name = c.v.trim()
            span = c.span
            if (name.lowercase() in INBOX_WORDS) {
                project = ProjectRef("Inbox")
            } else {
                val found = resolveName(name, ctx.projects.map { it.name })
                if (found != null) {
                    project = ProjectRef(found)
                } else if ("project:create" in d0.confirmed) {
                    project = ProjectRef(titleCase(name), create = true)
                } else if ("project:inbox" in d0.confirmed) {
                    project = ProjectRef("Inbox")
                } else {
                    val tc = titleCase(name)
                    ask(
                        d0,
                        ctx,
                        "choice",
                        "I don't have ${article(tc)} $tc list. Create it, or put this in Inbox?",
                        listOf(
                            optionOf(1, "Create it", d0.copy(confirmed = d0.confirmed + "project:create")),
                            optionOf(2, "Inbox", d0.copy(confirmed = d0.confirmed + "project:inbox")),
                        ),
                    )
                }
            }
            val sec = d0.section
            if (sec != null && project != null) {
                val sections = ctx.projects.firstOrNull { it.name.equals(project.name, true) }?.sections.orEmpty()
                val sf = resolveName(sec, sections)
                project = when {
                    sf != null -> project.copy(section = sf)

                    "section:drop" in d0.confirmed -> project

                    else -> ask(
                        d0,
                        ctx,
                        "confirm",
                        "${project.name} has no '${titleCase(
                            sec,
                        )}' section. Put it in ${project.name} without a section?",
                        listOf(optionOf(1, "Yes", d0.copy(confirmed = d0.confirmed + "section:drop"))),
                    )
                }
            }
        }
        val known = (ctx.labels + SEEDS).distinct()
        val labels = mutableListOf<LabelRef>()
        for (l in d0.tags + d0.labels) {
            val found = resolveName(l.v, known)
            val key = l.v.lowercase()
            when {
                found != null -> labels += LabelRef(found)

                "label:$key:create" in d0.confirmed -> labels += LabelRef(l.v.lowercase(), create = true)

                "label:$key:drop" in d0.confirmed -> Unit

                else -> ask(
                    d0,
                    ctx,
                    "choice",
                    "I don't have ${article(l.v)} '${l.v}' label. Create it, or leave it off?",
                    listOf(
                        optionOf(1, "Create it", d0.copy(confirmed = d0.confirmed + "label:$key:create")),
                        optionOf(2, "Leave it off", d0.copy(confirmed = d0.confirmed + "label:$key:drop")),
                    ),
                )
            }
        }
        return d0 to Ents(project, labels.distinctBy { it.name }, span)
    }

    private fun applyQual(t: TimeRaw, q: String?): TimeRaw = when {
        q == null || t.kind != TK.BARE -> t
        q == "morning" -> TimeRaw(t.hour % 12, t.minute, TK.AM)
        else -> TimeRaw(t.hour % 12 + 12, t.minute, TK.PM)
    }

    private const val RESCHEDULE_NEEDS = "I need a day or a time to move it to."

    private fun finish(d: Draft, ctx: ParseContext, e: Ents): ParseResult {
        val today = ctx.today
        val create = d.intent == Intent.CREATE
        val title = d.titleOverride ?: d.title
        val r0 = base(d, Verdict.Done, ctx).copy(
            project = e.project?.let { SlotValue.Parsed(it, e.projectSpan ?: 0..0) } ?: SlotValue.Absent,
            labels = e.labels,
        )
        if (create && title.isNullOrBlank()) {
            ask(d, ctx, "title", "What should I remind you about?")
        }
        val rec = d.rec?.v
        val applied = mutableListOf<DefaultUsed>()
        val dateC = d.dates.firstOrNull()
        val timeC = d.times.firstOrNull()
        val span = listOfNotNull(dateC?.span, timeC?.span, d.rel?.span, d.rec?.span).let { l ->
            if (l.isEmpty()) 0..0 else (l.minOf { it.first })..(l.maxOf { it.last })
        }

        // Relative instant: "in 20 minutes", "snooze for an hour".
        val relDur = d.rel?.v ?: d.snooze
        if (relDur != null) {
            if (dateC != null || timeC != null || rec != null) {
                decline(d, ctx, "I can't use a time and an \"in …\" together. Say one of them.")
            }
            val at = ctx.speechEnd.plus(relDur)
            val z = at.atZone(ctx.zone)
            val due = DueSpec(z.toLocalDate(), z.toLocalTime().withNano(z.nano / 1_000_000 * 1_000_000), ctx.zone.id)
            return r0.copy(
                due = SlotValue.Parsed(due, span),
                reminder = SlotValue.Parsed(ReminderSpec("AT_INSTANT", at), span),
            )
        }

        if (rec != null) return recurring(d, ctx, r0, rec, dateC, timeC, span)

        // One-off: date, then time.
        var date: LocalDate? = dateC?.let { resolveDate(d, ctx, it) }
        var time: LocalTime? = null
        var rolled = false
        if (timeC != null) {
            val t = timeC.v
            when (t.kind) {
                TK.MIDNIGHT -> {
                    val day = date ?: today
                    date = day.plusDays(1)
                    time = LocalTime.MIDNIGHT
                }

                TK.NOON -> time = LocalTime.NOON

                TK.NAMED -> {
                    val (ct, used) = named(t.name!!, ctx.settings)
                    if (ctx.settings.askNamedTime) {
                        ask(
                            d,
                            ctx,
                            "time",
                            timeQuestion(date, today),
                            listOf(
                                optionOf(
                                    1,
                                    clockSay(ct),
                                    d.copy(times = listOf(Cand(TimeRaw(ct.hour, ct.minute, TK.H24), timeC.span))),
                                ),
                                optionOf(2, "No alert", d.copy(noAlert = true, times = emptyList())),
                            ),
                        )
                    }
                    time = ct
                    if (date == null) {
                        date =
                            if (ZonedDateTime.of(today, ct, ctx.zone).toInstant() >
                                ctx.now.toInstant()
                            ) {
                                today
                            } else {
                                today.plusDays(1)
                            }
                        rolled = date != today
                    }
                    applied += DefaultUsed(used, clockSay(ct))
                }

                TK.BARE -> {
                    if (t.hour == 12) {
                        val m = t.minute
                        ask(
                            d,
                            ctx,
                            "time",
                            "Noon or midnight?",
                            listOf(
                                optionOf(1, "Noon", d.copy(times = listOf(Cand(TimeRaw(12, m, TK.H24), timeC.span)))),
                                optionOf(
                                    2,
                                    "Midnight",
                                    d.copy(times = listOf(Cand(TimeRaw(0, m, TK.MIDNIGHT), timeC.span))),
                                ),
                            ),
                        )
                    }
                    if (ctx.settings.askBareHour) amPmQuestion(d, ctx, t, timeC.span)
                    val (pt, pdate) = pickBare(t, ctx)
                    if (pt.hour in 0..6) amPmQuestion(d, ctx, t, timeC.span)
                    time = pt
                    if (date == null) {
                        date = pdate
                        rolled = pdate != today
                    }
                    applied += DefaultUsed("D1", clockSay(pt))
                }

                else -> time = LocalTime.of(t.hour, t.minute)
            }
            if (date == null) date = today
            // An explicit time today that has passed.
            val instant = ZonedDateTime.of(date, time, ctx.zone).toInstant()
            if (date == today && !rolled && "tomorrow" !in d.confirmed && instant <= ctx.now.toInstant() &&
                t0(timeC.v)
            ) {
                val tom = today.plusDays(1)
                ask(
                    d,
                    ctx,
                    "confirm",
                    "${clockSay(time)} today has passed. Tomorrow at ${clockSay(time)}?",
                    listOf(
                        optionOf(
                            1,
                            "Yes",
                            d.copy(
                                dates = listOf(Cand(DateRaw.Fixed(tom), dateC?.span ?: timeC.span)),
                                conflictResolved = true,
                            ),
                        ),
                    ),
                )
            }
        } else if (date != null) {
            if (create && d.remindMe && !d.noAlert) {
                ask(
                    d,
                    ctx,
                    "time",
                    timeQuestion(date, today),
                    listOf(
                        optionOf(
                            1,
                            clockSay(ctx.settings.morning),
                            d.copy(
                                times = listOf(
                                    Cand(
                                        TimeRaw(ctx.settings.morning.hour, ctx.settings.morning.minute, TK.H24),
                                        dateC!!.span,
                                    ),
                                ),
                            ),
                        ),
                        optionOf(2, "No alert", d.copy(noAlert = true)),
                    ),
                )
            }
        } else if (create) {
            if (d.remindMe) ask(d, ctx, "date_time", "When should I remind you?")
        } else if (d.intent == Intent.RESCHEDULE) {
            decline(d, ctx, RESCHEDULE_NEEDS)
        }
        val due = date?.let { DueSpec(it, time, null) }
        return r0.copy(
            due = due?.let { SlotValue.Parsed(it, span) } ?: SlotValue.Absent,
            reminder = if (due != null &&
                !d.noAlert
            ) {
                SlotValue.Parsed(ReminderSpec("SCHEDULE"), span)
            } else {
                SlotValue.Absent
            },
            appliedDefaults = applied,
            keepTime = !create && time == null,
            allDayDue = create && due != null && time == null,
        )
    }

    /** Only an explicit or defaulted clock time counts as "passed"; midnight never does. */
    private fun t0(t: TimeRaw) = t.kind != TK.MIDNIGHT

    private fun named(name: String, s: ParseSettings): Pair<LocalTime, String> = when (name) {
        "morning" -> s.morning to "D2"
        "afternoon" -> s.afternoon to "D2"
        "evening" -> s.evening to "D2"
        else -> s.tonight to "D2"
    }

    /** D1: the next occurrence of this wall time, am or pm, after now; and the date it falls on. */
    private fun pickBare(t: TimeRaw, ctx: ParseContext): Pair<LocalTime, LocalDate> {
        val am = LocalTime.of(t.hour % 12, t.minute)
        val pm = LocalTime.of(t.hour % 12 + 12, t.minute)
        val now = ctx.now.toInstant()
        return listOf(ctx.today, ctx.today.plusDays(1)).flatMap { dt ->
            listOf(am, pm).map { Triple(ZonedDateTime.of(dt, it, ctx.zone).toInstant(), it, dt) }
        }
            .filter { it.first > now }.minBy { it.first }.let { it.second to it.third }
    }

    private fun spokenHour(t: TimeRaw) = if (t.minute == 0) "${t.hour}" else "${t.hour}:${"%02d".format(t.minute)}"

    private fun amPmQuestion(d: Draft, ctx: ParseContext, t: TimeRaw, span: IntRange): Nothing {
        val pm = LocalTime.of(t.hour % 12 + 12, t.minute)
        val w = if (pm.hour < 17) "afternoon" else "evening"
        ask(
            d,
            ctx,
            "time",
            "${spokenHour(t)} in the morning or the $w?",
            listOf(
                optionOf(
                    1,
                    "In the morning",
                    d.copy(times = listOf(Cand(TimeRaw(t.hour % 12, t.minute, TK.AM), span))),
                ),
                optionOf(
                    2,
                    "In the $w",
                    d.copy(times = listOf(Cand(TimeRaw(t.hour % 12 + 12, t.minute, TK.PM), span))),
                ),
            ),
        )
    }

    private fun resolveDate(d: Draft, ctx: ParseContext, c: Cand<DateRaw>): LocalDate {
        val today = ctx.today
        fun dateOpt(i: Int, date: LocalDate, label: String? = null) = optionOf(
            i,
            label ?: "${dayName(date)} ${date.dayOfMonth} ${monthName(date.monthValue)}",
            d.copy(dates = listOf(Cand(DateRaw.Fixed(date), c.span)), conflictResolved = true),
        )
        return when (val raw = c.v) {
            is DateRaw.Fixed -> raw.date

            is DateRaw.Weekday -> {
                val diff = (raw.day.value - today.dayOfWeek.value + 7) % 7
                when {
                    raw.q == "next" -> {
                        val first = today.plusDays(if (diff == 0) 7L else diff.toLong())
                        ask(
                            d,
                            ctx,
                            "date",
                            "This ${askDay(first, today)}, or ${askDay(first.plusDays(7), today)}?",
                            listOf(dateOpt(1, first), dateOpt(2, first.plusDays(7))),
                        )
                    }

                    diff == 0 -> ask(
                        d,
                        ctx,
                        "date",
                        "Today, or ${dayName(
                            today,
                        )} ${today.plusDays(7).dayOfMonth} ${monthName(today.plusDays(7).monthValue)}?",
                        listOf(dateOpt(1, today, "Today"), dateOpt(2, today.plusDays(7))),
                    )

                    else -> today.plusDays(diff.toLong())
                }
            }

            is DateRaw.MonthDay -> resolveMonthDay(d, ctx, c, raw)

            DateRaw.Weekend -> {
                val sat = today.plusDays(((DayOfWeek.SATURDAY.value - today.dayOfWeek.value + 7) % 7).toLong())
                ask(d, ctx, "date", "Which day?", listOf(dateOpt(1, sat), dateOpt(2, sat.plusDays(1))))
            }

            DateRaw.NextWeek -> {
                val mon = today.plusDays((8 - today.dayOfWeek.value).toLong())
                ask(d, ctx, "date", "Which day?", listOf(dateOpt(1, mon)))
            }
        }
    }

    private fun resolveMonthDay(d: Draft, ctx: ParseContext, c: Cand<DateRaw>, r: DateRaw.MonthDay): LocalDate {
        val today = ctx.today
        if (r.day !in 1..31) decline(d, ctx, "That isn't a date I can use.")
        fun opt(
            i: Int,
            date: LocalDate,
            label: String,
        ) = optionOf(i, label, d.copy(dates = listOf(Cand(DateRaw.Fixed(date), c.span)), conflictResolved = true))
        if (r.month == null) {
            if (r.day == today.dayOfMonth) {
                val next = monthDayDate(r, today) ?: decline(d, ctx, "That isn't a date I can use.")
                ask(
                    d,
                    ctx,
                    "date",
                    "Today, or ${next.dayOfMonth} ${monthName(next.monthValue)}?",
                    listOf(opt(1, today, "Today"), opt(2, next, "${next.dayOfMonth} ${monthName(next.monthValue)}")),
                )
            }
            return monthDayDate(r, today) ?: decline(d, ctx, "That isn't a date I can use.")
        }
        val year = r.year ?: today.year
        val ym = YearMonth.of(year, r.month)
        if (r.day > ym.lengthOfMonth()) decline(d, ctx, "${monthName(r.month)} has no ${ordinal(r.day)}.")
        val date = ym.atDay(r.day)
        if (date < today) {
            if (r.year != null) decline(d, ctx, "${r.day} ${monthName(r.month)} has passed.")
            val nextYear = YearMonth.of(year + 1, r.month)
            if (r.day > nextYear.lengthOfMonth()) decline(d, ctx, "${r.day} ${monthName(r.month)} has passed.")
            ask(
                d,
                ctx,
                "confirm",
                "${r.day} ${monthName(r.month)} has passed. Do you mean next year?",
                listOf(opt(1, nextYear.atDay(r.day), "Yes")),
            )
        }
        return date
    }

    private fun recurring(d: Draft, ctx: ParseContext, r0: ParseResult, rec: RecSlot, dateC: Cand<DateRaw>?, timeC: Cand<TimeRaw>?, span: IntRange): ParseResult {
        val today = ctx.today
        var rule = rec.rule!!
        if (rec.needsDay && dateC == null) {
            ask(d, ctx, "date", "Which day?")
        }
        val applied = mutableListOf<DefaultUsed>()
        var time: LocalTime? = null
        val t = timeC?.v ?: rec.named?.let { TimeRaw(0, 0, TK.NAMED, it) }
        val tspan = timeC?.span ?: d.rec!!.span
        if (t != null) {
            when (t.kind) {
                TK.MIDNIGHT -> time = LocalTime.MIDNIGHT

                TK.NOON -> time = LocalTime.NOON

                TK.NAMED -> {
                    val (ct, used) = named(t.name!!, ctx.settings)
                    time = ct
                    applied += DefaultUsed(used, clockSay(ct))
                }

                TK.BARE -> {
                    if (t.hour == 12) {
                        ask(
                            d,
                            ctx,
                            "time",
                            "Noon or midnight?",
                            listOf(
                                optionOf(1, "Noon", d.copy(times = listOf(Cand(TimeRaw(12, t.minute, TK.H24), tspan)))),
                                optionOf(
                                    2,
                                    "Midnight",
                                    d.copy(times = listOf(Cand(TimeRaw(0, t.minute, TK.H24), tspan))),
                                ),
                            ),
                        )
                    }
                    amPmQuestion(d, ctx, t, tspan)
                }

                else -> time = LocalTime.of(t.hour, t.minute)
            }
        }
        val start = dateC?.let { resolveDate(d, ctx, it) } ?: today
        val first = rule.first(DueSpec(start, time, null), ctx.now.toInstant(), ctx.zone)
        // "starting Monday": a given start is never moved earlier than itself.
        val due = if (dateC != null && first.date < start) first.copy(date = start) else first
        val text = d.text.substring(d.rec!!.span.first, d.rec.span.last + 1)
        return r0.copy(
            due = SlotValue.Parsed(due, span),
            recurrence = SlotValue.Parsed(rule, d.rec.span),
            reminder = SlotValue.Parsed(ReminderSpec("SCHEDULE"), span),
            completionAnchor = rec.completion,
            recurrenceText = text,
            appliedDefaults = applied,
            allDayDue = time == null,
        )
    }
}
