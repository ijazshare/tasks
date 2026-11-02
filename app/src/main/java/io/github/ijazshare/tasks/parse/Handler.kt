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
    "LoopWithTooManyJumpStatements", "MagicNumber", "ComplexCondition",
    "ktlint:standard:max-line-length",
    "MagicNumber", "TooManyFunctions", "LongMethod", "CyclomaticComplexMethod", "ReturnCount", "NestedBlockDepth",
    "LongParameterList", "ComplexCondition", "LargeClass", "MaxLineLength", "ComplexMethod", "ThrowsCount",
)

package io.github.ijazshare.tasks.parse

import io.github.ijazshare.tasks.time.DueSpec
import io.github.ijazshare.tasks.time.RecurrenceRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

// Spec section 4 without the transport: the command handler, task matching (6.1 step 5), follow-up
// conversations (4.7) and the speech rules (4.5.4). Conversations and referents live in memory here;
// the IPC layer (ipc/Commands.kt) persists them in Room by replaying the answers.

data class Snapshot(val projects: List<ProjectInfo>, val labels: List<String>, val openTasks: List<OpenTask>)

data class NewTask(
    val title: String,
    val description: String,
    val project: ProjectRef,
    val priority: Int,
    val due: DueSpec?,
    val recurrence: RecurrenceRule?,
    val completionAnchor: Boolean,
    val recurrenceText: String?,
    val deadline: LocalDate?,
    val durationMin: Int?,
    val labels: List<LabelRef>,
    val source: String,
    val utterance: String,
)

/** What the handler needs from storage; [RoomStore] is the real one, the golden test has a fake. */
interface TaskStore {
    suspend fun snapshot(): Snapshot

    suspend fun create(task: NewTask): String

    suspend fun complete(id: String)

    suspend fun delete(id: String)

    suspend fun reschedule(id: String, due: DueSpec)

    // ponytail: priority stays in the contract for Assistant but is ignored (priorities removed from v1).
    suspend fun change(id: String, priority: Int?, project: String?, label: LabelRef?)

    suspend fun addProject(name: String): Boolean

    suspend fun addLabel(name: String): Boolean

    /** The task as the wire layer reports it (4.5.1), or null if it does not exist. */
    suspend fun view(id: String): TaskView?
}

/** A task as an effect reports it. [reminderAt] is the next fire time, if any. */
data class TaskView(
    val id: String,
    val title: String,
    val project: String,
    val due: DueSpec?,
    val recurrence: RecurrenceRule?,
    val anchor: String?,
    val recurrenceText: String?,
    val reminderAt: Instant?,
)

data class Request(
    val type: String = "utterance",
    val utterance: String? = null,
    val conversationId: String? = null,
    val optionId: String? = null,
    val client: String = "console",
    val now: ZonedDateTime,
    val speechEnd: Instant = now.toInstant(),
    val typed: Boolean = false,
    val plain: Set<String> = emptySet(),
)

data class ConversationInfo(
    val id: String,
    val expects: String,
    val options: List<Pair<String, String>>,
    val roundsLeft: Int,
)

/** [status] is done, needs_input, declined or not_mine. */
data class Response(
    val status: String,
    val speech: String,
    val intent: Intent? = null,
    val conversation: ConversationInfo? = null,
    val taskId: String? = null,
    val effect: String? = null,
    val parse: ParseResult? = null,
    /** For a query: everything that matched (the wire layer caps it). */
    val items: List<OpenTask>? = null,
    /** For a created project or label: its name. */
    val name: String? = null,
)

private class Conv(
    val id: String,
    val client: String,
    val q: Question,
    val ctx: ParseContext,
    val round: Int,
    val expires: Instant,
)

private const val TTL_SECONDS = 90L
private const val MAX_QUESTIONS = 4
private const val REFERENT_MINUTES = 10L
private const val SPOKEN_LIMIT = 5
private const val ESCAPES = "cancel|never ?mind|stop|forget it|no thanks"
private val YES =
    setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "please", "correct", "do it", "yes please", "that's right", "go ahead")
private val NO = setOf("no", "nope", "nah")

class CommandHandler(private val store: TaskStore, private val settings: ParseSettings = ParseSettings()) {
    private val convs = mutableMapOf<String, Conv>()
    internal val referents = mutableMapOf<String, Pair<String, Instant>>()
    internal val listed = mutableMapOf<String, List<OpenTask>>()
    private var counter = 0

    suspend fun handle(req: Request): Response {
        val snap = store.snapshot()
        fun ctxOf() = ParseContext(req.now, req.speechEnd, req.typed, true, snap.projects, snap.labels, snap.openTasks, settings, req.plain)
        return when (req.type) {
            "cancel" -> {
                convs.remove(req.client)
                Response("done", "")
            }

            "follow_up" -> followUp(req, ::ctxOf)

            else -> {
                convs.remove(req.client)
                val text = req.utterance.orEmpty()
                val ctx = ctxOf()
                scan(text, ctx).let { dispatch(req, ctx, it, Decider.decide(it, ctx), 0) }
            }
        }
    }

    private suspend fun followUp(req: Request, ctxOf: () -> ParseContext): Response {
        val conv = convs[req.client]?.takeIf { it.id == req.conversationId }
        if (conv == null || conv.expires < req.now.toInstant()) {
            convs.remove(req.client)
            return Response("declined", "That took too long, so I've left it.")
        }
        val q = conv.q
        val d = q.draft!!
        val text = req.utterance.orEmpty().trim()
        val lower = work(text).trim(',', '.', '!', '?', ' ')
        val picked = req.optionId?.let { id -> q.options.firstOrNull { it.id == id } }
        if (picked == null && Regex("^(?:$ESCAPES)$").matches(lower)) {
            convs.remove(req.client)
            return Response("declined", "Okay, I've left it.")
        }
        val ans = if (picked != null) Ans.Next(picked.next) else answer(d, q, text, lower, conv.ctx)
        return when (ans) {
            is Ans.Next -> {
                val r = Decider.decide(ans.draft, conv.ctx)
                val again = (r.verdict as? Verdict.Ask)?.question
                if (again != null && again.expects == q.expects && again.text == q.text) {
                    reask(req, conv, q)
                } else {
                    convs.remove(req.client)
                    dispatch(req, conv.ctx, ans.draft, r, conv.round)
                }
            }

            Ans.No -> {
                convs.remove(req.client)
                Response("declined", "Okay, I've left it.")
            }

            Ans.Reask -> reask(req, conv, q)

            Ans.Unrelated -> {
                convs.remove(req.client)
                val c = ctxOf()
                val fd = scan(text, c)
                val fresh = Decider.decide(fd, c)
                if (fresh.verdict == Verdict.NotMine) Response("not_mine", "") else dispatch(req, c, fd, fresh, 0)
            }
        }
    }

    private fun reask(req: Request, conv: Conv, q: Question): Response {
        val narrower = when (q.expects) {
            "date" -> "Say a day, like Friday or the 6th."
            "time" -> "Say a time, like 9 am or half past two."
            else -> q.text
        }
        return ask(req, conv.ctx, Question(q.expects, narrower, q.options, q.draft), conv.round, null)
    }

    private fun ask(req: Request, ctx: ParseContext, q: Question, round: Int, r: ParseResult?): Response {
        if (round >= MAX_QUESTIONS) {
            convs.remove(req.client)
            val what = when (q.expects) {
                "date" -> "a day"
                "time" -> "a time"
                "date_time" -> "a day and time"
                "title" -> "a title"
                else -> "an answer"
            }
            return Response(
                "declined",
                "I still didn't get $what, so I haven't saved it. You can add it in Tasks.",
                r?.intent,
                parse = r,
            )
        }
        val id = "c_${++counter}"
        val expires = req.now.toInstant().plusSeconds(TTL_SECONDS)
        convs[req.client] = Conv(id, req.client, q, ctx, round + 1, expires)
        return Response(
            "needs_input",
            q.text,
            r?.intent,
            ConversationInfo(id, q.expects, q.options.map { it.id to it.label }, MAX_QUESTIONS - round - 1),
            parse = r,
        )
    }

    private suspend fun dispatch(req: Request, ctx: ParseContext, d: Draft, r: ParseResult, round: Int): Response = when (val v = r.verdict) {
        Verdict.NotMine -> Response("not_mine", "", parse = r)
        is Verdict.Decline -> Response("declined", v.speech, r.intent, parse = r)
        is Verdict.Ask -> ask(req, ctx, v.question, round, r)
        Verdict.Done -> execute(req, ctx, d, r)
    }

    // ---- executing ------------------------------------------------------------------------------------

    private fun remember(client: String, id: String, now: Instant) {
        referents[client] = id to now.plus(Duration.ofMinutes(REFERENT_MINUTES))
        listed.remove(client)
    }

    private suspend fun execute(req: Request, ctx: ParseContext, d: Draft, r: ParseResult): Response {
        val client = req.client
        val now = ctx.now.toInstant()
        when (r.intent) {
            Intent.CREATE -> {
                val project = (r.project as? SlotValue.Parsed)?.v ?: ProjectRef("Inbox")
                if (project.create) store.addProject(project.name)
                r.labels.filter { it.create }.forEach { store.addLabel(it.name) }
                val due = (r.due as? SlotValue.Parsed)?.v
                val rule = (r.recurrence as? SlotValue.Parsed)?.v
                val id = store.create(
                    NewTask(
                        r.title.orEmpty(), r.description.orEmpty(), project, (r.priority as? SlotValue.Parsed)?.v ?: 4, due, rule,
                        r.completionAnchor, r.recurrenceText, (r.deadline as? SlotValue.Parsed)?.v, r.durationMin, r.labels,
                        if (req.typed) "typed" else "voice", d.text,
                    ),
                )
                remember(client, id, now)
                return Response(
                    "done",
                    createSpeech(r, ctx, d.text, d.remindMe, project),
                    Intent.CREATE,
                    taskId = id,
                    effect = "created",
                    parse = r,
                )
            }

            Intent.QUERY -> return query(req, ctx, r)

            Intent.CREATE_PROJECT -> {
                val name = r.title.orEmpty()
                return if (store.addProject(name)) {
                    Response(
                        "done",
                        "Okay. I made ${article(name)} $name list.",
                        r.intent,
                        effect = "project_created",
                        parse = r,
                        name = name,
                    )
                } else {
                    Response("declined", "You already have ${article(name)} $name list.", r.intent, parse = r)
                }
            }

            Intent.CREATE_LABEL -> {
                val name = r.title.orEmpty()
                return if (store.addLabel(name.lowercase())) {
                    Response(
                        "done",
                        "Okay. I made ${article(name)} $name label.",
                        r.intent,
                        effect = "label_created",
                        parse = r,
                        name = name.lowercase(),
                    )
                } else {
                    Response("declined", "You already have ${article(name)} $name label.", r.intent, parse = r)
                }
            }

            else -> Unit
        }
        // complete, delete, reschedule, change: find the task first.
        val task = when (val t = resolveTarget(req, ctx, d, r)) {
            is Found -> t.task
            is Needs -> return t.response
        }
        return when (r.intent) {
            Intent.COMPLETE -> {
                store.complete(task.id)
                remember(client, task.id, now)
                Response(
                    "done",
                    "Okay. Marked '${task.title}' as done.",
                    r.intent,
                    taskId = task.id,
                    effect = "completed",
                    parse = r,
                )
            }

            Intent.DELETE -> {
                store.delete(task.id)
                referents.remove(client)
                Response(
                    "done",
                    "Deleted '${task.title}'. Say undo to bring it back.",
                    r.intent,
                    taskId = task.id,
                    effect = "deleted",
                    parse = r,
                )
            }

            Intent.RESCHEDULE -> {
                val nd = (r.due as? SlotValue.Parsed)?.v
                    ?: return Response("declined", "I need a day or a time to move it to.", r.intent, parse = r)
                val instant = (r.reminder as? SlotValue.Parsed)?.v?.kind == "AT_INSTANT"
                val due = if (!instant && r.keepTime) DueSpec(nd.date, task.due?.time, task.due?.zone) else nd
                store.reschedule(task.id, due)
                remember(client, task.id, now)
                Response(
                    "done",
                    "Okay. Moved '${task.title}' to ${whenSay(due, ctx).removePrefix("on ")}.",
                    r.intent,
                    taskId = task.id,
                    effect = "rescheduled",
                    parse = r,
                )
            }

            Intent.CHANGE -> {
                val proj = (r.project as? SlotValue.Parsed)?.v?.name
                val label = r.labels.firstOrNull()
                store.change(task.id, null, proj, label)
                remember(client, task.id, now)
                val what = when {
                    proj != null -> "the $proj list"
                    else -> "the ${label?.name} label"
                }
                Response(
                    "done",
                    if (label != null && proj == null) {
                        "Okay. Added the ${label.name} label to '${task.title}'."
                    } else {
                        "Okay. ${if (proj != null) "Moved" else "Set"} '${task.title}' to $what."
                    },
                    r.intent,
                    taskId = task.id,
                    effect = "changed",
                    parse = r,
                )
            }

            else -> Response("not_mine", "", parse = r)
        }
    }

    private sealed interface Res

    private class Found(val task: OpenTask) : Res

    private class Needs(val response: Response) : Res

    private fun resolveTarget(req: Request, ctx: ParseContext, d: Draft, r: ParseResult): Res {
        val client = req.client
        val now = ctx.now.toInstant()
        val open = ctx.openTasks
        r.taskId?.let { id -> open.firstOrNull { it.id == id }?.let { return Found(it) } }
        val none = Needs(Response("declined", "I couldn't find a task like that.", r.intent, parse = r))
        return when (val t = r.target) {
            Target.Referent -> {
                val task = referents[client]?.takeIf {
                    it.second > now
                }?.let { p -> open.firstOrNull { it.id == p.first } }
                if (task != null) {
                    Found(task)
                } else {
                    Needs(
                        needTask(
                            req,
                            ctx,
                            d,
                            r,
                            "Which task?",
                            listed[client].orEmpty().take(3).map {
                                it to it.title
                            },
                        ),
                    )
                }
            }

            is Target.Words -> when (val m = matchTask(t.text, open)) {
                is Match.One -> Found(m.task)

                is Match.Many -> Needs(needTask(req, ctx, d, r, m.question, m.options.map { it to it.title }))

                is Match.Fuzzy -> Needs(
                    needTask(req, ctx, d, r, "Do you mean '${m.task.title}'?", listOf(m.task to "Yes"), confirm = true),
                )

                Match.None -> none
            }

            null -> none
        }
    }

    private fun needTask(req: Request, ctx: ParseContext, d: Draft, r: ParseResult, text: String, opts: List<Pair<OpenTask, String>>, confirm: Boolean = false): Response {
        val options = opts.mapIndexed { i, (task, label) -> Option("o${i + 1}", label, d.copy(taskId = task.id)) }
        return ask(req, ctx, Question(if (confirm) "confirm" else "task", text, options, d), 0, r)
    }

    // ---- query ----------------------------------------------------------------------------------------

    private fun query(req: Request, ctx: ParseContext, r: ParseResult): Response {
        val today = ctx.today
        val scope = r.scope ?: "all"
        if (scope == "find") return find(req, ctx, r)
        val project = (r.project as? SlotValue.Parsed)?.v?.name
        var items = ctx.openTasks.filter { project == null || it.project.equals(project, true) }
        items = when {
            scope == "overdue" -> items.filter { (it.due?.date ?: today) < today }

            scope == "today" -> items.filter { it.due != null && it.due.date <= today }

            scope.startsWith("date:") -> LocalDate.parse(scope.removePrefix("date:")).let { day ->
                items.filter {
                    it.due?.date ==
                        day
                }
            }

            else -> items
        }.sortedWith(compareBy({ it.due?.date ?: LocalDate.MAX }, { it.due?.time }, { it.title }))
        val label = when {
            scope == "overdue" -> "overdue"
            scope == "today" -> "today"
            scope.startsWith("date:") -> dayWord(LocalDate.parse(scope.removePrefix("date:")), today)
            else -> ""
        }
        val speech = when {
            items.isEmpty() -> if (scope ==
                "overdue"
            ) {
                "Nothing is overdue."
            } else if (label.isEmpty()) {
                "Your list is empty."
            } else {
                "Nothing due $label."
            }

            else -> {
                val shown = items.take(SPOKEN_LIMIT).joinToString(", ") { it.title }
                val more = items.size - SPOKEN_LIMIT
                val head =
                    "You have ${items.size} ${if (items.size == 1) "task" else "tasks"}" +
                        if (label.isEmpty()) {
                            ""
                        } else if (scope == "overdue") {
                            " overdue"
                        } else {
                            " $label"
                        }
                "$head: $shown" + if (more > 0) ", and $more more." else "."
            }
        }
        if (items.size == 1) {
            remember(req.client, items.single().id, ctx.now.toInstant())
        } else {
            referents.remove(req.client)
            listed[req.client] = items
        }
        return Response("done", speech, Intent.QUERY, effect = "listed", parse = r, items = items)
    }

    private fun find(req: Request, ctx: ParseContext, r: ParseResult): Response {
        val q = tokens((r.target as? Target.Words)?.text.orEmpty())
        val hits = if (q.isEmpty()) emptyList() else ctx.openTasks.filter { contains(tokens(it.title), q) }
        val speech = if (hits.isEmpty()) {
            "No task like that."
        } else {
            "Yes: " + hits.take(SPOKEN_LIMIT).joinToString("; ") { t ->
                t.title + (t.due?.let { ", ${whenSay(it, ctx)}" } ?: "")
            } + (if (hits.size > SPOKEN_LIMIT) ", and ${hits.size - SPOKEN_LIMIT} more." else ".")
        }
        if (hits.size == 1) remember(req.client, hits.single().id, ctx.now.toInstant())
        return Response("done", speech, Intent.QUERY, effect = "listed", parse = r, items = hits)
    }

    // ---- speech ---------------------------------------------------------------------------------------

    private fun lcFirst(title: String, text: String, r: ParseResult): String {
        val start = r.titleSpans.firstOrNull()?.first
        val originalUpper = start != null && start < text.length && text[start].isUpperCase()
        val word = title.substringBefore(' ')
        return if (!originalUpper &&
            word.drop(1).all { it.isLowerCase() || it == '\'' }
        ) {
            title.replaceFirstChar { it.lowercase() }
        } else {
            title
        }
    }

    private fun createSpeech(r: ParseResult, ctx: ParseContext, text: String, remind: Boolean, project: ProjectRef): String {
        val title = r.title.orEmpty()
        val due = (r.due as? SlotValue.Parsed)?.v
        val rule = (r.recurrence as? SlotValue.Parsed)?.v
        val noAlert = remind && due != null && r.reminder is SlotValue.Absent
        val word = title.substringBefore(' ').lowercase()
        val lead = if (word in setOf("the", "a", "an", "my", "our", "your")) "about" else "to"
        val said = if (lead == "about") title.replaceFirstChar { it.lowercase() } else lcFirst(title, text, r)
        if (rule != null && due != null) {
            val start = dayWord(due.date, ctx.today).let { if (it == "today" || it == "tomorrow") it else "on $it" }
            val sched = recSay(rule) + (due.time?.let { " at ${clockSay(it, ctx.h12)}" } ?: "") + clampNote(rule) +
                (if (r.completionAnchor) ", counted from when you finish it" else "") + ", starting $start"
            return if (remind) "Okay. I'll remind you $lead $said $sched." else "Okay. Added '$title' to ${project.name}, $sched."
        }
        return when {
            noAlert -> "Okay. Saved '$title' for ${dayWord(due!!.date, ctx.today)}, all day, no time alert."
            remind -> "Okay. I'll remind you $lead $said ${whenSay(due!!, ctx)}."
            due == null -> "Okay. Added '$title' to ${project.name}."
            else -> "Okay. Added '$title' to ${project.name} for ${whenSay(due, ctx).removePrefix("on ")}."
        }
    }
}

// ---- task matching (6.1 step 5) ----------------------------------------------------------------------

private val STOP =
    setOf("a", "an", "the", "my", "to", "at", "on", "in", "for", "of", "about", "with", "task", "reminder", "list")

internal fun tokens(s: String): Set<String> = work(s).replace(Regex("['’]s\\b"), "").split(Regex("[^\\p{L}\\p{N}]+"))
    .filter { it.isNotEmpty() && it !in STOP }.map(::tokenStem).toSet()

private fun same(a: String, b: String) = a == b || (a.length >= 5 && b.length >= 5 && dl(a, b) <= 1)

private fun contains(t: Set<String>, q: Set<String>) = q.all { x -> t.any { same(it, x) } }

private fun dice(q: Set<String>, t: Set<String>): Double {
    val common = q.count { x -> t.any { same(it, x) } }
    return if (q.isEmpty() || t.isEmpty()) 0.0 else 2.0 * common / (q.size + t.size)
}

private sealed interface Match {
    data class One(val task: OpenTask) : Match

    data class Many(val question: String, val options: List<OpenTask>) : Match

    data class Fuzzy(val task: OpenTask) : Match

    data object None : Match
}

private fun lcFirstTitle(t: String): String = if (t.substringBefore(' ').drop(1).all {
        it.isLowerCase() || it == '\''
    }
) {
    t.replaceFirstChar { it.lowercase() }
} else {
    t
}

private fun tokenStem(w: String) = when {
    w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
    w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
    w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
    else -> w
}

private fun matchTask(ref: String, open: List<OpenTask>): Match {
    val q = tokens(ref)
    if (q.isEmpty()) return Match.None
    val ts = open.map { it to tokens(it.title) }
    val exact = ts.filter { (_, t) -> contains(t, q) && contains(q, t) }
    if (exact.size == 1) return Match.One(exact.single().first)
    val cont = ts.filter { (_, t) -> contains(t, q) }.sortedByDescending { dice(q, it.second) }
    when {
        cont.size == 1 -> return Match.One(cont.single().first)

        cont.size in 2..3 -> return Match.Many(
            "Which one: " + cont.joinToString(", or ") {
                lcFirstTitle(it.first.title)
            } + "?",
            cont.map { it.first },
        )

        cont.size >= 4 -> return Match.Many(
            "I found ${cont.size} tasks with ${ref.trim()}. Which one?",
            cont.take(3).map {
                it.first
            },
        )
    }
    val best = ts.maxByOrNull { dice(q, it.second) }
    return if (best != null && dice(q, best.second) >= 0.5) Match.Fuzzy(best.first) else Match.None
}

// ---- answers to questions (4.7) ----------------------------------------------------------------------

private sealed interface Ans {
    data class Next(val draft: Draft) : Ans

    data object No : Ans

    data object Reask : Ans

    data object Unrelated : Ans
}

private fun words(s: String): List<String> = work(s).split(Regex("[^\\p{L}\\p{N}]+")).filter {
    it.isNotEmpty()
}.map { w ->
    ordVal(w)?.toString() ?: numVal(w)?.toString() ?: w
}

private fun optionMatch(lower: String, options: List<Option>): Option? {
    if (options.isEmpty()) return null
    options.firstOrNull { it.id == lower }?.let { return it }
    val ws = words(lower)
    val stop = setOf("the", "a", "in", "on", "of", "it", "please", "i", "to", "at", "one")
    val scored = options.map { o -> o to words(o.label).count { it !in stop && it in ws } }
    val best = scored.maxByOrNull { it.second }
    if (best != null && best.second > 0 && scored.count { it.second == best.second } == 1) return best.first
    if (lower in
        setOf("the first one", "first one", "the first", "first", "one", "this one", "this", "the one")
    ) {
        return options.first()
    }
    if (lower in
        setOf("the second one", "second one", "the second", "second", "two", "the other one", "the other", "next one")
    ) {
        return options.getOrNull(1)
    }
    if (lower in setOf("the third one", "third one", "the third", "third", "three")) return options.getOrNull(2)
    if (lower == "the last one") return options.last()
    if ("am" in ws && options.size == 2) return options[0]
    if ("pm" in ws && options.size == 2) return options[1]
    return null
}

private fun answer(d: Draft, q: Question, text: String, lower: String, ctx: ParseContext): Ans {
    val yes = lower in YES
    val no = lower in NO
    fun next(draft: Draft) = Ans.Next(draft)
    // 4.7 step 4: an answer that starts with an intent frame is a new command.
    if (!yes && !no && q.expects != "ramble" &&
        scan(text, ctx.copy(typed = false)).intent != Intent.NOT_MINE
    ) {
        return Ans.Unrelated
    }
    when (q.expects) {
        "confirm" -> return if (yes) {
            q.options.firstOrNull()?.let { next(it.next) } ?: Ans.Reask
        } else if (no) {
            Ans.No
        } else {
            Ans.Reask
        }

        "choice" -> {
            if ("inbox" in lower && q.text.contains("Create it")) return next(q.options.last().next)
            optionMatch(lower, q.options)?.let { return next(it.next) }
            if (yes) return next(q.options.first().next)
            if (no && q.text.contains("Create it")) return next(q.options.last().next)
            return if (scan(text, ctx).intent != Intent.NOT_MINE) Ans.Unrelated else Ans.Reask
        }

        "task" -> {
            optionMatch(lower, q.options)?.let { return next(it.next) }
            if (q.options.isEmpty() || tokens(lower).isNotEmpty()) {
                val pool = if (q.options.isEmpty()) {
                    ctx.openTasks
                } else {
                    ctx.openTasks.filter { t ->
                        q.options.any {
                            it.next.taskId ==
                                t.id
                        }
                    }
                }
                when (val m = matchTask(lower, pool)) {
                    is Match.One -> return next(d.copy(taskId = m.task.id))
                    is Match.Many -> return Ans.Reask
                    else -> Unit
                }
            }
            return if (scan(text, ctx).intent != Intent.NOT_MINE) Ans.Unrelated else Ans.Unrelated
        }

        "title" -> {
            if (scan(text, ctx.copy(typed = false)).intent !in setOf(Intent.NOT_MINE) ||
                text.isBlank()
            ) {
                return Ans.Unrelated
            }
            return next(d.copy(titleOverride = text.trim().replaceFirstChar { it.uppercase() }))
        }

        "ramble" -> return Ans.Unrelated
    }
    // date, time, date_time and the confirm-less choices below
    optionMatch(lower, q.options)?.let { return next(it.next) }
    val plain = ctx.copy(typed = false)
    val sc = Scan(text, plain)
    val got = scanSlots(sc, Intent.CREATE, Draft(text, Intent.CREATE))
    val weekWords = if (d.dates.any { it.v == DateRaw.NextWeek }) true else false
    when (q.expects) {
        "date" -> {
            if (d.rec?.v?.needsDay == true) {
                val wd = got.dates.firstOrNull()?.v as? DateRaw.Weekday ?: return Ans.Reask
                val rule = d.rec.v.rule!!
                return next(
                    d.copy(
                        rec = Cand(
                            d.rec.v.copy(
                                rule = rule.copy(byDay = listOf(io.github.ijazshare.tasks.time.DayRule(null, wd.day))),
                                needsDay = false,
                            ),
                            d.rec.span,
                        ),
                        conflictResolved = true,
                    ),
                )
            }
            val c = got.dates.firstOrNull()
            if (c != null) {
                var raw = c.v
                if (weekWords && raw is DateRaw.Weekday) {
                    val mon = ctx.today.plusDays((8 - ctx.today.dayOfWeek.value).toLong())
                    raw = DateRaw.Fixed(mon.plusDays((raw.day.value - 1).toLong()))
                }
                return next(d.copy(dates = listOf(Cand(raw, c.span)), conflictResolved = true))
            }
            return fillOther(d, got)
        }

        "time" -> {
            if (no &&
                q.options.any { it.label == "No alert" }
            ) {
                return next(q.options.first { it.label == "No alert" }.next)
            }
            var t = got.times.firstOrNull()
            if (t ==
                null
            ) {
                t =
                    scanSlots(Scan("at $text", plain), Intent.CREATE, Draft(text, Intent.CREATE)).times.firstOrNull()
            }
            if (t != null) return next(d.copy(times = listOf(t), conflictResolved = true))
            return fillOther(d, got)
        }

        "date_time" -> {
            if (got.dates.isEmpty() && got.times.isEmpty() && got.rel == null && got.rec == null) return Ans.Unrelated
            return next(
                d.copy(
                    dates = got.dates,
                    times = got.times,
                    rel = got.rel,
                    rec = got.rec ?: d.rec,
                    conflictResolved = true,
                ),
            )
        }
    }
    return Ans.Unrelated
}

/** 4.7 step 3: the answer fills another missing slot of the same draft. */
private fun fillOther(d: Draft, got: Draft): Ans = when {
    got.dates.isNotEmpty() && d.dates.isEmpty() -> Ans.Next(d.copy(dates = got.dates))
    got.times.isNotEmpty() && d.times.isEmpty() -> Ans.Next(d.copy(times = got.times))
    got.dates.isNotEmpty() || got.times.isNotEmpty() -> Ans.Reask
    else -> Ans.Unrelated
}
