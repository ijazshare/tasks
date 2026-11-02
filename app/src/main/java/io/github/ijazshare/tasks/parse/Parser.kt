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
    "LongParameterList", "ComplexCondition", "LargeClass", "MaxLineLength", "ComplexMethod",
)

package io.github.ijazshare.tasks.parse

import io.github.ijazshare.tasks.time.DayRule
import io.github.ijazshare.tasks.time.Freq
import io.github.ijazshare.tasks.time.RecurrenceRule
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth

// Parsing stages 1 to 4 and 6 of spec 6.1: junk gate, normalizer, intent frames, slot grammars and title
// extraction. The working copy is lowercase and the same length as the utterance, so every span is an offset
// into the original text. (Number words are read by the grammars themselves instead of being rewritten.)

data class Cand<T>(val v: T, val span: IntRange)

sealed interface DateRaw {
    data class Fixed(val date: LocalDate) : DateRaw

    /** [q] is "", "this" or "next". */
    data class Weekday(val day: DayOfWeek, val q: String) : DateRaw

    data class MonthDay(val day: Int, val month: Int?, val year: Int?) : DateRaw

    data object Weekend : DateRaw

    data object NextWeek : DateRaw
}

enum class TK { BARE, AM, PM, H24, NOON, MIDNIGHT, NAMED }

/** [hour] is the 24-hour hour except for BARE, where it is the clock-face hour. [name] is morning, afternoon, evening or tonight. */
data class TimeRaw(val hour: Int, val minute: Int, val kind: TK, val name: String? = null, val tonight: Boolean = false)

data class RecSlot(
    val rule: RecurrenceRule?,
    val completion: Boolean = false,
    val named: String? = null,
    val needsDay: Boolean = false,
    val decline: String? = null,
)

data class Draft(
    val text: String,
    val intent: Intent,
    val remindMe: Boolean = false,
    val title: String? = null,
    val titleSpans: List<IntRange> = emptyList(),
    val dates: List<Cand<DateRaw>> = emptyList(),
    val times: List<Cand<TimeRaw>> = emptyList(),
    val rel: Cand<Duration>? = null,
    val rec: Cand<RecSlot>? = null,
    val deadline: Cand<LocalDate>? = null,
    val priority: Cand<Int>? = null,
    val project: Cand<String>? = null,
    val section: String? = null,
    val labels: List<Cand<String>> = emptyList(),
    val tags: List<Cand<String>> = emptyList(),
    val description: String? = null,
    val durationMin: Int? = null,
    val decline: String? = null,
    val unresolved: List<IntRange> = emptyList(),
    val cue: IntRange? = null,
    val target: Target? = null,
    val scope: String? = null,
    val snooze: Duration? = null,
    val rambleBody: String? = null,
    val newName: String? = null,
    val recText: String? = null,
    val chips: List<Chip> = emptyList(),
    // Answers gathered by follow-ups.
    val noAlert: Boolean = false,
    val confirmed: Set<String> = emptySet(),
    val conflictResolved: Boolean = false,
    val titleOverride: String? = null,
    val taskId: String? = null,
)

internal const val WD = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
internal const val MON = "january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"
private const val ONES = "one|two|three|four|five|six|seven|eight|nine"
private const val TEENS = "ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen"
private const val TENS = "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety"
internal const val NUMW = "(?:(?:$TENS)(?:[- ](?:$ONES))?|$TEENS|$ONES|zero)"
internal const val NUM = "(?:\\d+|$NUMW)"
private const val ORDW = "first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|thirtieth|(?:twenty|thirty)[- ]?(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth)"
internal const val ORD = "(?:\\d{1,2}(?:st|nd|rd|th)|$ORDW)"
private const val PREP = "(?:(?:on|by|until|before|for|starting|from) )?"
private const val QUAL = "(?:\\s+(?:in the (morning|afternoon|evening)|(tonight)|this (morning|afternoon|evening)|at night))?"

private val ordWords = listOf(
    "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "eleventh",
    "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth",
    "twentieth",
)
private val numWords = (
    listOf("zero") + ONES.split("|") + TEENS.split("|")
    ).mapIndexed { i, s -> s to i }.toMap() + TENS.split("|").mapIndexed { i, s -> s to (i + 2) * 10 }.toMap()

internal fun numVal(s: String): Int? {
    s.toIntOrNull()?.let { return it }
    val parts = s.trim().split('-', ' ').filter { it.isNotEmpty() }
    if (parts.isEmpty() || parts.size > 2) return null
    val vals = parts.map { numWords[it] ?: return null }
    return vals.sum()
}

internal fun ordVal(s: String): Int? {
    Regex("(\\d{1,2})(?:st|nd|rd|th)").matchEntire(s)?.let { return it.groupValues[1].toInt() }
    ordWords.indexOf(s).takeIf { it >= 0 }?.let { return it + 1 }
    if (s == "thirtieth") return 30
    val m = Regex("(twenty|thirty)[- ]?(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth)").matchEntire(s)
        ?: return null
    return (numWords.getValue(m.groupValues[1])) + ordWords.indexOf(m.groupValues[2]) + 1
}

private val weekdays = WD.split("|").mapIndexed { i, s -> s to DayOfWeek.of(i + 1) }.toMap()

internal fun monthVal(s: String): Int {
    val full =
        listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    return full.indexOfFirst { it.startsWith(s.take(3)) } + 1
}

// ---- Stage 1: junk gate ----------------------------------------------------------------------------------

private val fillerOnly = setOf(
    "thank you", "thanks", "thank you very much", "you", "bye", "goodbye", "okay", "ok", "yeah", "yes", "no",
    "hello", "hi", "uh", "um", "hmm", "thanks for watching", "thank you for watching", "the end", "so", "right",
)

fun isJunk(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.none { it.isLetterOrDigit() }) return true
    if (Regex("^[\\[(].*[\\])]$").matches(t)) return true
    return t.lowercase().replace(Regex("[^a-z ]"), "").trim().replace(Regex("\\s+"), " ") in fillerOnly
}

/** Same length as [text]: lowercased, unicode dashes and quotes made plain. */
internal fun work(text: String): String = buildString(text.length) {
    for (c in text) {
        append(
            when (c) {
                '–', '—', '−' -> '-'
                '‘', '’' -> '\''
                '“', '”' -> '"'
                else -> Character.toLowerCase(c).let { if (it.isWhitespace()) ' ' else it }
            },
        )
    }
}.let { if (it.length == text.length) it else text.map { ch -> ch }.joinToString("") }

private val fillerStart = Regex("^\\s*(?:(?:okay|ok|hey|so|right|please|um|uh)\\b[,.]?\\s*)+")

private val protectedRes = listOf(
    Regex("\"[^\"]+\"|(?<![\\p{L}\\p{N}])'[^']+'(?![\\p{L}\\p{N}])"),
    Regex("\\+\\d[\\d ]{6,}\\d|\\b\\d{3}-\\d{4}\\b|\\b\\d{3,5}[ -]\\d{3}[ -]\\d{4}\\b"),
    Regex("[£$€]\\s?\\d+(?:\\.\\d+)?"),
    Regex("\\bv?\\d+\\.\\d+(?:\\.\\d+)+\\b"),
)

// ---- Stage 4: slot grammars ------------------------------------------------------------------------------

private val DURPIECE = "(?:(?:\\d+(?:\\.\\d+)?|$NUMW|an?|half an?|a quarter of an?|one and a half)\\s*(?:hours?|hrs?|minutes?|mins?|seconds?|secs?))"
private val DURBODY = "$DURPIECE(?:(?:\\s*,)?(?:\\s+and)?\\s+(?:$DURPIECE|a half))*"
private val durPiece =
    Regex("(\\d+(?:\\.\\d+)?|$NUMW|an?|half an?|a quarter of an?|one and a half)\\s*(hour|hr|minute|min|second|sec)")

internal fun parseDuration(body: String): Duration? {
    var secs = 0.0
    var last = ""
    for (m in durPiece.findAll(body)) {
        val q = m.groupValues[1]
        val n = when {
            q == "a" || q == "an" -> 1.0
            q.startsWith("half") -> 0.5
            q.startsWith("a quarter") -> 0.25
            q.startsWith("one and") -> 1.5
            else -> q.toDoubleOrNull() ?: numVal(q)?.toDouble() ?: return null
        }
        val unit = m.groupValues[2]
        last = unit
        secs += n * when (unit) {
            "hour", "hr" -> 3600
            "minute", "min" -> 60
            else -> 1
        }
    }
    if (last.isEmpty()) return null
    if (Regex("and a half\\b").containsMatchIn(body.substringAfterLast(last))) {
        secs += if (last == "hour" || last == "hr") {
            1800
        } else if (last.startsWith("min")) {
            30
        } else {
            0
        }
    }
    return Duration.ofMillis((secs * 1000).toLong())
}

private val reVague =
    Regex(
        "\\bin (?:a few|a couple(?: of)?|several|a bit|a little(?: while)?|a while)(?: (?:of )?(?:minutes|mins|hours|hrs|days|weeks))?\\b|\\b(?:later|soon|sometime|some time|shortly)\\b",
    )
private val reZone =
    Regex(
        "\\b(?:london|new york|paris|eastern|pacific|central|mountain)(?: time)?\\b(?= time|\\b)(?<=eastern|pacific|time)|\\b(?:gmt|utc|est|edt|pst|pdt|cet|bst)\\b",
    )

private val reRelDur = Regex("\\bin\\s+($DURBODY)\\b|\\b($DURBODY)\\s+from now\\b")
private val reDurFor =
    Regex("\\bfor\\s+((?:\\d+(?:\\.\\d+)?|$NUMW|an?|half an?)\\s*(?:hours?|hrs?|minutes?|mins?)|\\d+[hm])\\b")

private val reAfterTomorrow = Regex("\\bthe day after tomorrow\\b")
private val reRelDays = Regex("\\bin ($NUM|an?) (day|days|week|weeks)\\b")
private val reToday = Regex("\\b$PREP(today|tomorrow)\\b")
private val reThisPart = Regex("\\bthis (morning|afternoon|evening)\\b")
private val reTonight = Regex("\\btonight\\b")
private val reWeekday = Regex("\\b$PREP(?:(this|next|coming)\\s+)?($WD)\\b(?!['’]s)")
private val reOrdA = Regex("\\b(?:on|by|for|until|before) the ($ORD)\\b")
private val reOrdB = Regex("\\bthe (\\d{1,2}(?:st|nd|rd|th))\\b")
private val reOrdC = Regex("\\b$PREP(?:the )?($ORD)\\s+of\\s+($MON)\\b(?:,? (20\\d\\d))?")
private val reOrdD = Regex("\\b$PREP($NUM|$ORD) ($MON)\\b(?:,? (20\\d\\d))?")
private val reOrdE = Regex("\\b$PREP($MON) ($ORD|\\d{1,2})\\b(?:,? (20\\d\\d))?")
private val reEndMonth = Regex("\\bend of (?:the )?month\\b")
private val reNextWeek = Regex("\\bnext week\\b")
private val reWeekend = Regex("\\b(?:this|next|at the|on the|over the) weekend\\b")
private val pods = Regex("^\\s+(morning|afternoon|evening)\\b")

private val reNoon = Regex("\\b(?:(?:at|by|around) )?(noon|midday|midnight)\\b(?: (tonight|today))?")
private val reFrac = Regex("\\b(?:(?:at|by) )?(?:a )?(half|quarter) (past|to) ($NUM)\\b(?: o'?clock)?$QUAL")
private val reColon =
    Regex("\\b(?:(?:at|by|around|about) )?(\\d{1,3}):(\\d{2})\\b(?:\\s?(am|pm|a\\.m\\.|p\\.m\\.))?$QUAL")
private val reAmPm =
    Regex("\\b(?:(?:at|by|around|about) )?(\\d{1,2}|$NUMW)(?::(\\d{2}))?\\s?(am|pm|a\\.m\\.|p\\.m\\.)(?![a-z])")
private val reOclock = Regex("\\b(?:at )?($NUM) o'?clock\\b$QUAL")
private val reAtBare = Regex("\\bat (\\d{1,2}|$NUMW)(?:[ .](\\d{2}))?\\b(?![:.]\\d)(?!\\s?(?:st|nd|rd|th)\\b)$QUAL")
private val reInPart = Regex("\\bin the (morning|afternoon|evening)\\b")

private val reDeadline = Regex("\\bdeadline\\s+(?:is |on |by )?")
private val rePriority = Regex("\\bpriority (one|two|three|four|[1-4])\\b|\\bp([1-4])\\b|\\bhigh priority\\b")
private val reLabelWord = Regex("\\b(?:with the |with )?(?:label|tag|tagged)\\s+(?:as )?([\\p{L}\\p{N}_-]+)")
private val reListPhrase = Regex("\\b(?:to|on|in|into|onto) (?:my|the) ((?:[\\p{L}\\p{N}'-]+ ){0,3}?)(list|project)\\b")
private val reCue = Regex("\\b(no wait|no make that|actually|i mean|scratch that|sorry)\\b")

private val WDLIST = "(?:$WD)(?:(?:,|,? and|&) (?:$WD))*"

private fun days(s: String) = Regex("($WD)").findAll(s).map { DayRule(null, weekdays.getValue(it.value)) }.toList()

private val priNames = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4)

internal class Scan(val text: String, val ctx: ParseContext) {
    val w = work(text)
    val n = text.length
    val used = BooleanArray(n)
    val prot = BooleanArray(n)
    val chips = mutableListOf<Chip>()

    fun free(r: IntRange) = r.all { it in 0 until n && !used[it] && !prot[it] }

    fun take(r: IntRange, kind: String? = null) {
        for (i in r) if (i in 0 until n) used[i] = true
        if (kind != null) chips += Chip(kind, r)
    }

    /** First free match of [re]; marks it used. */
    fun find(re: Regex, kind: String?): MatchResult? {
        for (m in re.findAll(w)) {
            if (m.range.isEmpty() || !free(m.range)) continue
            take(m.range, kind)
            return m
        }
        return null
    }

    fun findAllFree(re: Regex, kind: String?): List<MatchResult> {
        val out = mutableListOf<MatchResult>()
        for (m in re.findAll(w)) {
            if (m.range.isEmpty() || !free(m.range)) continue
            take(m.range, kind)
            out += m
        }
        return out
    }
}

private fun qualOf(m: MatchResult, from: Int): String? =
    (from until minOf(from + 3, m.groups.size)).firstNotNullOfOrNull { i ->
        m.groups[i]?.value?.takeIf { it.isNotEmpty() }
    }
        ?.let { if (it == "at night") "tonight" else it }

private fun withQual(raw: TimeRaw, q: String?): TimeRaw = when {
    q == null -> raw
    raw.kind != TK.BARE -> raw
    q == "morning" -> TimeRaw(raw.hour % 12, raw.minute, TK.AM)
    else -> TimeRaw(raw.hour % 12 + 12, raw.minute, TK.PM)
}

private fun clock(h: Int, min: Int, ap: String?): TimeRaw? {
    if (min > 59) return null
    val a = ap?.replace(".", "")
    return when {
        a == "am" -> if (h in 1..12) TimeRaw(h % 12, min, TK.AM) else null
        a == "pm" -> if (h in 1..12) TimeRaw(h % 12 + 12, min, TK.PM) else null
        h in 13..23 || h == 0 -> TimeRaw(h, min, TK.H24)
        h in 1..12 -> TimeRaw(h, min, TK.BARE)
        else -> null
    }
}

private fun leadingZero(m: MatchResult, group: Int) =
    m.groupValues[group].length == 2 && m.groupValues[group].startsWith("0")

/** Slot grammars 4.1 to 4.7 over the free text, plus the typed symbols of 6.3. */
internal fun scanSlots(s: Scan, intent: Intent, d0: Draft): Draft {
    var d = d0
    val ctx = s.ctx
    val today = ctx.today
    val create = intent == Intent.CREATE

    // Typed symbols and escapes first: they are unambiguous.
    if (ctx.typed) {
        Regex("(?:^|\\s)(//\\s*)(.*)$", RegexOption.DOT_MATCHES_ALL).find(s.w)?.let { m ->
            val r = m.groups[1]!!.range.first..s.n - 1
            if (s.free(r)) {
                d = d.copy(description = s.text.substring(m.groups[2]!!.range.first).trim())
                s.take(r, "description")
            }
        }
    }
    Regex("(?<![\\p{L}\\p{N}])#([^\\s/]+)(?:\\s+/([^\\s/]+))?").find(s.w)?.let { m ->
        if (s.free(m.range)) {
            s.take(m.range, "project")
            d = d.copy(
                project = Cand(s.text.substring(m.groups[1]!!.range), m.range),
                section = m.groups[2]?.let { s.text.substring(it.range) },
            )
        }
    }
    for (m in Regex("(?<![\\p{L}\\p{N}])[%@]([\\p{L}\\p{N}_-]+)").findAll(s.w)) {
        if (!s.free(m.range)) continue
        s.take(m.range, "label")
        d = d.copy(labels = d.labels + Cand(s.text.substring(m.groups[1]!!.range), m.range))
    }
    Regex("\\{([^}]*)\\}").find(s.w)?.let { m ->
        if (s.free(m.range)) {
            s.take(m.range, "deadline")
            val inner = Scan(s.text.substring(m.groups[1]!!.range), ctx)
            val dr = dateAt(inner, 0)
            val date = dr?.let { resolveSimple(it.first, ctx) }
            d =
                if (date !=
                    null
                ) {
                    d.copy(deadline = Cand(date, m.range))
                } else {
                    d.copy(unresolved = d.unresolved + listOf(m.range))
                }
        }
    }
    Regex("(?<![\\p{L}\\p{N}])!(\\S+)(?: (?:min|minutes?|hours?)(?: before)?)?").findAll(s.w).forEach { m ->
        if (s.free(m.range)) s.take(m.range) // extra reminders are parsed away but not stored (a "should")
    }

    // 4.1 recurrence
    var rec: Cand<RecSlot>? = null
    findRecurrence(s)?.let { rec = it }
    // zones and vague quantities decline.
    s.find(reZone, null)?.let { return d.copy(decline = "I can't set times in other time zones yet.") }
    s.find(reVague, null)?.let { return d.copy(decline = "I need an exact time, like in 20 minutes.") }

    // duration "for 30 minutes" (create only)
    if (create) {
        s.find(reDurFor, "duration")?.let { m ->
            val body = m.groupValues[1]
            val mins =
                Regex("^(\\d+)([hm])$").find(body)?.let {
                    it.groupValues[1].toInt() *
                        if (it.groupValues[2] == "h") 60 else 1
                }
                    ?: parseDuration(body)?.toMinutes()?.toInt()
            d = d.copy(durationMin = mins)
        }
    }

    // 4.2 relative instants
    var rel: Cand<Duration>? = null
    s.find(reRelDur, "time")?.let { m ->
        val body = m.groups[1]?.value ?: m.groups[2]!!.value
        val dur = parseDuration(body)
        if (dur == null) d = d.copy(unresolved = d.unresolved + listOf(m.range)) else rel = Cand(dur, m.range)
    }

    val dates = mutableListOf<Cand<DateRaw>>()
    val times = mutableListOf<Cand<TimeRaw>>()
    // 4.5 deadline
    for (m in reDeadline.findAll(s.w)) {
        if (!s.free(m.range)) continue
        val got = dateAt(s, m.range.last + 1) ?: continue
        val date = resolveSimple(got.first, ctx) ?: continue
        val r = m.range.first..got.second
        if (!s.free(r.first..m.range.last)) continue
        s.take(r, "deadline")
        d = d.copy(deadline = Cand(date, r))
    }

    // noon and midnight go first so that "midnight tonight" is one span.
    for (m in s.findAllFree(reNoon, "time")) {
        val w = m.groupValues[1]
        val kind = if (w == "midnight") TK.MIDNIGHT else TK.NOON
        times +=
            Cand(TimeRaw(if (kind == TK.NOON) 12 else 0, 0, kind, tonight = m.groupValues[2] == "tonight"), m.range)
    }
    // 4.3 dates
    s.find(reAfterTomorrow, "date")?.let { dates += Cand(DateRaw.Fixed(today.plusDays(2)), it.range) }
    for (m in s.findAllFree(reRelDays, "date")) {
        val n = m.groupValues[1].let { if (it == "a" || it == "an") 1 else numVal(it) } ?: continue
        val unit = if (m.groupValues[2].startsWith("week")) 7 else 1
        dates += Cand(DateRaw.Fixed(today.plusDays((n * unit).toLong())), m.range)
    }
    for (m in s.findAllFree(reToday, "date")) {
        var r = m.range
        val pod = pods.find(s.w.substring(r.last + 1))
        if (pod != null) {
            val name = pod.groupValues[1]
            r = r.first..(r.last + pod.range.last + 1)
            s.take(r)
            times += Cand(TimeRaw(0, 0, TK.NAMED, name), r)
        }
        val which = m.groupValues[1]
        dates += Cand(DateRaw.Fixed(if (which == "today") today else today.plusDays(1)), r)
    }
    for (m in s.findAllFree(reThisPart, "date")) {
        dates += Cand(DateRaw.Fixed(today), m.range)
        times += Cand(TimeRaw(0, 0, TK.NAMED, m.groupValues[1]), m.range)
    }
    for (m in s.findAllFree(reTonight, "date")) {
        dates += Cand(DateRaw.Fixed(today), m.range)
        times += Cand(TimeRaw(0, 0, TK.NAMED, "tonight"), m.range)
    }
    for (m in s.findAllFree(reWeekend, "date")) dates += Cand(DateRaw.Weekend, m.range)
    for (m in s.findAllFree(reNextWeek, "date")) dates += Cand(DateRaw.NextWeek, m.range)
    for (m in s.findAllFree(reEndMonth, "date")) {
        dates +=
            Cand(DateRaw.Fixed(YearMonth.from(today).atEndOfMonth()), m.range)
    }
    // 4.3 month names and ordinals, then weekdays (which would otherwise grab "on Friday" first).
    for (re in listOf(reOrdC, reOrdE, reOrdD, reOrdA, reOrdB)) {
        for (m in re.findAll(s.w)) {
            if (!s.free(m.range)) continue
            val raw = when (re) {
                reOrdC -> mdOf(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                reOrdD -> mdOf(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                reOrdE -> mdOf(m.groupValues[2], m.groupValues[1], m.groupValues[3])
                else -> mdOf(m.groupValues[1], "", "")
            } ?: continue
            s.take(m.range, "date")
            dates += Cand(raw, m.range)
        }
    }
    for (m in s.findAllFree(reWeekday, "date")) {
        var r = m.range
        val pod = pods.find(s.w.substring(r.last + 1))
        if (pod != null) {
            r = r.first..(r.last + pod.range.last + 1)
            s.take(r)
            times += Cand(TimeRaw(0, 0, TK.NAMED, pod.groupValues[1]), r)
        }
        dates +=
            Cand(
                DateRaw.Weekday(
                    weekdays.getValue(m.groupValues[2]),
                    m.groupValues[1].let {
                        if (it ==
                            "coming"
                        ) {
                            "this"
                        } else {
                            it
                        }
                    },
                ),
                r,
            )
    }

    // 4.4 times (continued)
    for (m in s.findAllFree(reFrac, "time")) {
        val h = numVal(m.groupValues[3]) ?: continue
        val base = clockOr(
            if (m.groupValues[2] == "to") (h + 10) % 12 + 1 else h,
            when {
                m.groupValues[1] == "half" -> 30
                m.groupValues[2] == "to" -> 45
                else -> 15
            },
            null,
            qualOf(m, 4),
        ) ?: continue
        times += Cand(base, m.range)
    }
    for (m in s.findAllFree(reColon, "time")) {
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 24 || min > 59 || (h == 24 && min > 0)) {
            return d.copy(decline = "That isn't a time I can use.")
        }
        var t = clock(if (h == 24) 0 else h, min, m.groupValues[3].ifEmpty { null }) ?: continue
        if (t.kind == TK.BARE && leadingZero(m, 1)) t = TimeRaw(h, min, TK.H24)
        times += Cand(withQual(t, qualOf(m, 4)), m.range)
    }
    for (m in s.findAllFree(reAmPm, "time")) {
        val h = numVal(m.groupValues[1]) ?: continue
        times += Cand(clock(h, m.groupValues[2].ifEmpty { "0" }.toInt(), m.groupValues[3]) ?: continue, m.range)
    }
    for (m in s.findAllFree(reOclock, "time")) {
        val h = numVal(m.groupValues[1]) ?: continue
        if (h > 24) return d.copy(decline = "That isn't a time I can use.")
        times += Cand(withQual(clock(h % 24, 0, null) ?: continue, qualOf(m, 2)), m.range)
    }
    for (m in s.findAllFree(reAtBare, "time")) {
        val h = numVal(m.groupValues[1]) ?: continue
        if (h > 23) continue
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val t = clock(h, min, null) ?: continue
        times += Cand(withQual(t, qualOf(m, 3)), m.range)
    }
    for (m in s.findAllFree(reInPart, "time")) times += Cand(TimeRaw(0, 0, TK.NAMED, m.groupValues[1]), m.range)

    // 4.6 priority
    s.find(rePriority, "priority")?.let { m ->
        val p = when {
            m.value == "high priority" -> 2
            m.groupValues[1].isNotEmpty() -> priNames[m.groupValues[1]] ?: m.groupValues[1].toInt()
            else -> m.groupValues[2].toInt()
        }
        d = d.copy(priority = Cand(p, m.range))
    }

    // 4.7 project, section and label
    if (d.project == null) {
        s.find(reListPhrase, "project")?.let { m ->
            val name = m.groupValues[1].trim()
            d = d.copy(project = Cand(name, m.range))
        }
    }
    for (m in s.findAllFree(reLabelWord, "label")) {
        d = d.copy(labels = d.labels + Cand(s.text.substring(m.groups[1]!!.range), m.range))
    }

    return d.copy(
        dates = dates.sortedBy { it.span.first },
        times = times.sortedBy { it.span.first },
        rel = rel,
        rec = rec,
    )
}

private fun clockOr(h: Int, min: Int, ap: String?, q: String?): TimeRaw? = clock(h, min, ap)?.let { withQual(it, q) }

private fun mdOf(dayWord: String, monthWord: String, year: String): DateRaw? {
    val day = if (dayWord.any { it.isDigit() } && !dayWord.endsWith("st") && !dayWord.endsWith("nd") &&
        !dayWord.endsWith("rd") && !dayWord.endsWith("th")
    ) {
        dayWord.toIntOrNull()
    } else {
        ordVal(dayWord) ?: numVal(dayWord)
    } ?: return null
    return DateRaw.MonthDay(day, monthWord.ifEmpty { null }?.let(::monthVal), year.ifEmpty { null }?.toInt())
}

/** A date expression starting exactly at [from] (used by "deadline" and `{...}`): date and end offset. */
internal fun dateAt(s: Scan, from: Int): Pair<DateRaw, Int>? {
    val w = s.w
    val today = s.ctx.today
    fun at(re: Regex) = re.matchAt(w, from)?.takeIf { s.free(from..it.range.last) }
    at(Regex("(today|tomorrow)\\b"))?.let {
        return DateRaw.Fixed(
            today.plusDays(
                if (it.groupValues[1] ==
                    "today"
                ) {
                    0
                } else {
                    1
                },
            ),
        ) to
            it.range.last
    }
    at(Regex("(?:(this|next)\\s+)?($WD)\\b"))?.let {
        return DateRaw.Weekday(weekdays.getValue(it.groupValues[2]), it.groupValues[1]) to it.range.last
    }
    at(Regex("($ORD)(?: of)? ($MON)\\b(?:,? (20\\d\\d))?|($MON) ($ORD|\\d{1,2})\\b(?:,? (20\\d\\d))?"))?.let { m ->
        val raw = if (m.groupValues[1].isNotEmpty()) {
            mdOf(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        } else {
            mdOf(m.groupValues[5], m.groupValues[4], m.groupValues[6])
        }
        return raw?.let { it to m.range.last }
    }
    at(Regex("the ($ORD)\\b"))?.let { m -> return mdOf(m.groupValues[1], "", "")?.let { it to m.range.last } }
    return null
}

/** For deadlines, `{...}` and queries: no questions, so an ambiguous date takes the plain reading. */
internal fun resolveSimple(raw: DateRaw, ctx: ParseContext): LocalDate? {
    val today = ctx.today
    return when (raw) {
        is DateRaw.Fixed -> raw.date

        is DateRaw.Weekday -> {
            val diff = (raw.day.value - today.dayOfWeek.value + 7) % 7
            today.plusDays(
                (
                    if (diff == 0 &&
                        raw.q != "this"
                    ) if (raw.q ==
                        "next"
                    ) {
                        7
                    } else {
                        0
                    } else {
                        diff + if (raw.q == "next" && diff > 0) 0 else 0
                    }
                    ).toLong(),
            )
        }

        is DateRaw.MonthDay -> monthDayDate(raw, today)

        else -> null
    }
}

/** The date a month-day names; null if it does not exist. With no month: the soonest such day after today. */
internal fun monthDayDate(r: DateRaw.MonthDay, today: LocalDate): LocalDate? {
    if (r.day !in 1..31) return null
    if (r.month != null) {
        val year = r.year ?: today.year
        val ym = YearMonth.of(year, r.month)
        return if (r.day <= ym.lengthOfMonth()) ym.atDay(r.day) else null
    }
    var ym = YearMonth.from(today)
    repeat(14) {
        if (r.day <= ym.lengthOfMonth() && ym.atDay(r.day) > today) return ym.atDay(r.day)
        ym = ym.plusMonths(1)
    }
    return null
}

// ---- recurrence ------------------------------------------------------------------------------------------

private val reRecStart = Regex("\\b(every!?|each|daily|weekly|monthly|yearly|annually|fortnightly)(?=\\s|$|[,.])")
private val reRecMonthDay = Regex("\\b(?:on )?the ($ORD) of (?:every|each) month\\b")
private val reRecNth = Regex("\\b(?:on )?(?:the )?(first|second|third|fourth|last) ($WD) of (?:every|each) month\\b")
private val reCompletion = Regex("^\\s+after (?:i )?(?:finish|complete|do|done|tick)(?:ed)?(?: it| that)?(?: off)?\\b")
private val reAfterN = Regex("\\bafter ($NUM) (day|week|month|year)s?\\b")

private fun unitFreq(u: String) = when (u) {
    "day" -> Freq.DAILY
    "week" -> Freq.WEEKLY
    "month" -> Freq.MONTHLY
    else -> Freq.YEARLY
}

private fun safeRule(f: () -> RecurrenceRule): RecurrenceRule? = runCatching(f).getOrNull()

private fun findRecurrence(s: Scan): Cand<RecSlot>? {
    s.find(reRecMonthDay, "recurrence")?.let { m ->
        val day = ordVal(m.groupValues[1])
        return Cand(RecSlot(day?.let { safeRule { RecurrenceRule(Freq.MONTHLY, byMonthDay = it) } }), m.range)
    }
    s.find(reRecNth, "recurrence")?.let { m ->
        val ord = if (m.groupValues[1] ==
            "last"
        ) {
            -1
        } else {
            listOf("first", "second", "third", "fourth").indexOf(m.groupValues[1]) + 1
        }
        return Cand(
            RecSlot(
                safeRule {
                    RecurrenceRule(Freq.MONTHLY, byDay = listOf(DayRule(ord, weekdays.getValue(m.groupValues[2]))))
                },
            ),
            m.range,
        )
    }
    s.find(reAfterN, "recurrence")?.takeIf { s.ctx.typed }?.let { m ->
        val n = numVal(m.groupValues[1]) ?: 1
        return Cand(RecSlot(safeRule { RecurrenceRule(unitFreq(m.groupValues[2]), n) }, completion = true), m.range)
    }
    for (m in reRecStart.findAll(s.w)) {
        if (!s.free(m.range)) continue
        val word = m.groupValues[1]
        val bang = word.endsWith("!")
        val key = word.trimEnd('!')
        var end = m.range.last
        var slot = parseRecTail(s, key, end + 1)
        if (slot == null) continue
        end = slot.second
        var rec = slot.first
        reCompletion.find(s.w.substring(end + 1))?.let { c ->
            end += c.range.last + 1
            rec = rec.copy(completion = true)
        }
        if (bang) rec = rec.copy(completion = true)
        val r = m.range.first..end
        if (!s.free(r)) continue
        s.take(r, "recurrence")
        return Cand(rec, r)
    }
    return null
}

/** What follows "every"/"daily"/...: a recurrence and the offset of its last character. */
private fun parseRecTail(s: Scan, key: String, from: Int): Pair<RecSlot, Int>? {
    val w = s.w
    fun at(re: Regex) = re.matchAt(w, from)
    fun rule(f: () -> RecurrenceRule) = RecSlot(safeRule(f))
    when (key) {
        "daily" -> return rule { RecurrenceRule(Freq.DAILY) } to from - 1

        "yearly", "annually" -> return rule { RecurrenceRule(Freq.YEARLY) } to from - 1

        "fortnightly" -> return rule { RecurrenceRule(Freq.WEEKLY, 2) } to from - 1

        "weekly" -> {
            at(Regex("\\s+on ($WDLIST)"))?.let { m ->
                return rule { RecurrenceRule(Freq.WEEKLY, byDay = days(m.groupValues[1])) } to m.range.last
            }
            return rule { RecurrenceRule(Freq.WEEKLY) } to from - 1
        }

        "monthly" -> {
            at(Regex("\\s+on the ($ORD|last day)"))?.let { m ->
                val day = if (m.groupValues[1] == "last day") -1 else ordVal(m.groupValues[1])
                return RecSlot(day?.let { safeRule { RecurrenceRule(Freq.MONTHLY, byMonthDay = it) } }) to m.range.last
            }
            return rule { RecurrenceRule(Freq.MONTHLY) } to from - 1
        }
    }
    // every / each
    at(Regex("\\s+(other\\s+)?($WDLIST)\\b"))?.let { m ->
        val iv = if (m.groupValues[1].isNotEmpty()) 2 else 1
        return rule { RecurrenceRule(Freq.WEEKLY, iv, days(m.groupValues[2])) } to m.range.last
    }
    at(Regex("\\s+(weekday|weekend)s?\\b"))?.let { m ->
        val set = if (m.groupValues[1] == "weekday") listOf("MO", "TU", "WE", "TH", "FR") else listOf("SA", "SU")
        return rule { RecurrenceRule(Freq.WEEKLY, byDay = set.map { DayRule.parse(it)!! }) } to m.range.last
    }
    at(Regex("\\s+(first|second|third|fourth|last) ($WD)\\b(?: of (?:the|every|each) month)?"))?.let { m ->
        val ord = if (m.groupValues[1] ==
            "last"
        ) {
            -1
        } else {
            listOf("first", "second", "third", "fourth").indexOf(m.groupValues[1]) + 1
        }
        return rule {
            RecurrenceRule(Freq.MONTHLY, byDay = listOf(DayRule(ord, weekdays.getValue(m.groupValues[2]))))
        } to
            m.range.last
    }
    at(Regex("\\s+(morning|afternoon|evening|night)\\b"))?.let { m ->
        return RecSlot(
            safeRule { RecurrenceRule(Freq.DAILY) },
            named = m.groupValues[1].let {
                if (it ==
                    "night"
                ) {
                    "tonight"
                } else {
                    it
                }
            },
        ) to
            m.range.last
    }
    at(Regex("\\s+($MON) ($ORD|\\d{1,2})\\b|\\s+($ORD|\\d{1,2}) ($MON)\\b"))?.let { m ->
        val (mo, dd) = if (m.groupValues[1].isNotEmpty()) {
            m.groupValues[1] to m.groupValues[2]
        } else {
            m.groupValues[4] to
                m.groupValues[3]
        }
        val day = ordVal(dd) ?: dd.toIntOrNull()
        return RecSlot(
            day?.let {
                safeRule { RecurrenceRule(Freq.YEARLY, byMonth = monthVal(mo), byMonthDay = it) }
            },
        ) to
            m.range.last
    }
    at(Regex("\\s+(other\\s+|$NUM\\s+)?(day|week|month|year|hour|minute|second)s?\\b"))?.let { m ->
        val unit = m.groupValues[2]
        if (unit in listOf("hour", "minute", "second")) {
            return RecSlot(null, decline = "I can only repeat daily or less often.") to m.range.last
        }
        val q = m.groupValues[1].trim()
        val iv = if (q == "other") {
            2
        } else if (q.isEmpty()) {
            1
        } else {
            numVal(q) ?: 1
        }
        var end = m.range.last
        val f = unitFreq(unit)
        var days: List<DayRule> = emptyList()
        var monthDay: Int? = null
        var month: Int? = null
        if (f == Freq.WEEKLY) {
            Regex("\\s+on ($WDLIST)").matchAt(w, end + 1)?.let { dm ->
                days = days(dm.groupValues[1])
                end = dm.range.last
            }
        } else if (f == Freq.MONTHLY) {
            Regex("\\s+on the ($ORD|last day)").matchAt(w, end + 1)?.let { dm ->
                monthDay = if (dm.groupValues[1] == "last day") -1 else ordVal(dm.groupValues[1])
                end = dm.range.last
            }
        } else if (f == Freq.YEARLY) {
            Regex("\\s+on (?:the )?($ORD|\\d{1,2}) (?:of )?($MON)\\b|\\s+on ($MON) ($ORD|\\d{1,2})\\b").matchAt(
                w,
                end + 1,
            )?.let { dm ->
                val (mo, dd) = if (dm.groupValues[2].isNotEmpty()) {
                    dm.groupValues[2] to dm.groupValues[1]
                } else {
                    dm.groupValues[3] to
                        dm.groupValues[4]
                }
                month = monthVal(mo)
                monthDay = ordVal(dd) ?: dd.toIntOrNull()
                end = dm.range.last
            }
        }
        val slot = RecSlot(
            safeRule { RecurrenceRule(f, iv, days, monthDay, month) },
            needsDay = f == Freq.WEEKLY && iv > 1 && days.isEmpty(),
        )
        return slot to end
    }
    return null
}

// ---- Stage 3: intent frames and the whole scan -----------------------------------------------------------

private const val PRI_WORDS = "one|two|three|four|[1-4]"
private val reRamble = Regex("^(?:start a |okay |let's do a |do a )?(?:brain ?dump|ramble)\\b[,.:]?\\s*")
private val reCreateNamed = Regex("^create (?:a |an |the )?(?:new )?(list|project|label|tag)(?: called| named)? (.+)$")
private val reQuery = Regex(
    "^(?:what(?:'s| is| are)? (?:on|due|left|overdue)|what do i have|what have i got|what (?:tasks|reminders) do i have|" +
        "anything (?:due|overdue|on)|any (?:tasks|reminders)|(?:read|show|list)(?: me)? (?:my|the) (?:tasks|list|to-?do|reminders?)|" +
        "my tasks|my to-?do list|to-?do list|what(?:'s| is) overdue|is anything overdue)\\b",
)
private val completeRes = listOf(
    Regex("^mark (.+?) (?:as )?(?:done|complete|completed|finished)$"),
    Regex("^(?:tick|cross|check) off (.+)$"),
    Regex("^(?:tick|cross|check) (.+?) off$"),
    Regex("^(?:complete|finish) (.+)$"),
    Regex("^(.+?) (?:is|are) (?:done|finished|complete|completed)$"),
    Regex("^i(?:'ve| have)? (?:done|finished|paid|sent|called|booked|bought|posted|emailed) (.+)$"),
)
private val reDelete = Regex("^(?:delete|remove|scrap) (.+?)(?: from my (?:list|tasks|to-?do list))?$")
private val reSnooze = Regex("^snooze (.+?)(?: for)? ($DURBODY)$")
private val reMove =
    Regex("^(?:move|push|reschedule|postpone|put off|shift) (.+?)(?: back)? (?:to|until|till|for|by) (.+)$")
private val reMoveList = Regex("^(?:the |my )?(.+?) (?:list|project)$")
private val reChangePri = Regex("^(?:make|set) (.+?)(?: to| as)? (?:priority|p) ?($PRI_WORDS)$")
private val reAddLabel = Regex("^(?:add|put) (?:the )?(?:label|tag) (\\S+) (?:to|on) (.+)$")
private val reRemind = Regex("^(?:(urgent(?:ly)?)[, ]+)?remind me\\b")
private val reAdd =
    Regex(
        "^(?:add|put) (?:(?:a |an )?(?:new )?task(?: to| that| called| for)? )?|^(?:new|create (?:a )?) ?task(?: to| that| called| for)? |^make a task(?: to| that| for)? ",
    )
private val reLeadPri = Regex("^(?:priority ($PRI_WORDS)|p([1-4]))\\b")
private val referents = setOf("it", "that", "this", "that one", "this one", "the last one", "that task", "it again")

private fun tgt(s: String): Target = if (s.trim() in referents) Target.Referent else Target.Words(s.trim())

private val stripEnds =
    setOf("to", "about", "that", "at", "on", "by", "for", "in", "of", "and", "then", "from", "until", "till", "or")

fun parse(text: String, ctx: ParseContext): ParseResult = Decider.decide(scan(text, ctx), ctx)

/** Stages 1 to 4 and 6. Never throws. */
fun scan(text: String, ctx: ParseContext): Draft {
    if (!ctx.typed && isJunk(text)) return Draft(text, Intent.NOT_MINE)
    val s = Scan(text, ctx)
    protectedRes.forEach { re -> re.findAll(s.w).forEach { m -> for (i in m.range) s.prot[i] = true } }
    for (word in ctx.plain) {
        val w = work(word)
        var i = s.w.indexOf(w)
        while (w.isNotEmpty() && i >= 0) {
            for (k in i until i + w.length) s.prot[k] = true
            i = s.w.indexOf(w, i + w.length)
        }
    }
    Regex("\\bliterally\\b").find(s.w)?.let { m ->
        for (i in m.range.last + 1 until s.n) s.prot[i] = true
        for (i in m.range) s.used[i] = true
    }
    val fill = fillerStart.find(s.w)
    val start = fill?.range?.last?.plus(1) ?: 0
    if (fill != null) for (i in fill.range) s.used[i] = true
    // Work on the text after the fillers, minus trailing punctuation, as one anchored string.
    val end = s.w.trimEnd(' ', '.', '!', '?', ',').length.coerceAtLeast(start)
    val body = s.w.substring(start, end)
    fun off(r: IntRange) = (r.first + start)..(r.last + start)

    var d = Draft(text, Intent.NOT_MINE)
    var remind = false

    if (!ctx.typed) {
        reRamble.find(body)?.let { m ->
            s.take(off(m.range))
            return d.copy(
                intent = Intent.RAMBLE,
                rambleBody = text.substring((m.range.last + 1 + start).coerceAtMost(s.n)).trim(),
            )
        }
        reCreateNamed.find(body)?.let { m ->
            val kind = m.groupValues[1]
            return d.copy(
                intent = if (kind == "list" || kind == "project") Intent.CREATE_PROJECT else Intent.CREATE_LABEL,
                newName = text.substring(
                    m.groups[2]!!.range.first + start,
                    m.groups[2]!!.range.last + 1 + start,
                ).trim(),
            )
        }
        reQuery.find(body)?.let { m ->
            s.take(off(m.range))
            d = d.copy(intent = Intent.QUERY)
            val dd = scanSlots(s, Intent.QUERY, d)
            val scope = when {
                Regex("\\boverdue\\b").containsMatchIn(s.w) -> "overdue"

                dd.dates.isNotEmpty() -> resolveSimple(dd.dates.first().v, ctx)?.let { "date:$it" } ?: "today"

                Regex("\\b(?:left|today)\\b").containsMatchIn(s.w) ||
                    (dd.project == null && Regex("what(?:'s| is) on").containsMatchIn(body)) -> "today"

                else -> "all"
            }
            return dd.copy(scope = scope, chips = s.chips)
        }
        for (re in completeRes) {
            re.find(body)?.let { m ->
                val g = m.groups[1]!!
                return d.copy(
                    intent = Intent.COMPLETE,
                    target = tgt(
                        text.substring(
                            g.range.first + start,
                            g.range.last + 1 + start,
                        ),
                    ),
                )
            }
        }
        reDelete.find(body)?.let { m ->
            val g = m.groups[1]!!
            return d.copy(
                intent = Intent.DELETE,
                target = tgt(
                    text.substring(
                        g.range.first + start,
                        g.range.last + 1 + start,
                    ),
                ),
            )
        }
        reSnooze.find(body)?.let { m ->
            val g = m.groups[1]!!
            return d.copy(
                intent = Intent.RESCHEDULE,
                target = tgt(text.substring(g.range.first + start, g.range.last + 1 + start)),
                snooze = parseDuration(m.groupValues[2]),
            )
        }
        reAddLabel.find(body)?.let { m ->
            val g = m.groups[2]!!
            val l = m.groups[1]!!
            return d.copy(
                intent = Intent.CHANGE,
                target = tgt(text.substring(g.range.first + start, g.range.last + 1 + start)),
                labels = listOf(Cand(text.substring(l.range.first + start, l.range.last + 1 + start), off(l.range))),
            )
        }
        reChangePri.find(body)?.let { m ->
            val g = m.groups[1]!!
            val p = priNames[m.groupValues[2]] ?: m.groupValues[2].toInt()
            return d.copy(
                intent = Intent.CHANGE,
                target = tgt(text.substring(g.range.first + start, g.range.last + 1 + start)),
                priority = Cand(p, off(m.range)),
            )
        }
        reMove.find(body)?.let { m ->
            val g = m.groups[1]!!
            val whenG = m.groups[2]!!
            val ref = tgt(text.substring(g.range.first + start, g.range.last + 1 + start))
            reMoveList.find(whenG.value)?.let { lm ->
                return d.copy(
                    intent = Intent.CHANGE,
                    target = ref,
                    project = Cand(lm.groupValues[1].trim(), off(whenG.range)),
                )
            }
            for (i in off(m.range.first..g.range.first - 1)) s.used[i] = true
            for (i in off(g.range)) s.prot[i] = true
            d = d.copy(intent = Intent.RESCHEDULE, target = ref)
            val dd = scanSlots(s, Intent.RESCHEDULE, d)
            return finishFrameless(dd, s).copy(chips = s.chips)
        }
    }

    // create frames
    var isCreate = ctx.typed
    var tagNames = emptyList<Cand<String>>()
    val rm = reRemind.find(body)
    if (rm != null) {
        isCreate = true
        remind = true
        var e = rm.range.last
        val urgentBefore = rm.groupValues[1].isNotEmpty()
        if (urgentBefore) tagNames = tagNames + Cand("urgent", off(rm.range))
        Regex("^\\s+urgently\\b").find(body.substring(e + 1))?.let {
            tagNames = tagNames + Cand("urgent", off((e + 1)..(e + it.range.last + 1)))
            e += it.range.last + 1
        }
        Regex("^\\s+for\\s+([\\p{L}\\p{N}_-]+)").find(body.substring(e + 1))?.let { fm ->
            val name = fm.groupValues[1]
            if (ctx.labels.any { it.equals(name, true) } || name in SEED_TAGS) {
                tagNames = tagNames + Cand(name, off((e + 1)..(e + fm.range.last + 1)))
                e += fm.range.last + 1
            }
        }
        Regex("^\\s+(?:to|about|that)\\b").find(body.substring(e + 1))?.let { e += it.range.last + 1 }
        s.take(off(rm.range.first..e))
    } else {
        reAdd.find(body)?.let { m ->
            if (!m.value.startsWith("put") ||
                Regex("\\b(?:on|to|in) (?:my|the)\\b.*\\blist\\b").containsMatchIn(body)
            ) {
                isCreate = true
                s.take(off(m.range))
            }
        }
        reLeadPri.find(body)?.let { isCreate = true }
    }
    if (!isCreate) return d
    d = d.copy(intent = Intent.CREATE, remindMe = remind, tags = tagNames)
    d = scanSlots(s, Intent.CREATE, d)
    return finishFrameless(d, s).copy(chips = s.chips)
}

private val SEED_TAGS = setOf("urgent", "work", "personal", "family", "client")

/** Self-correction cue (6.1 step 7) and title extraction (step 6). */
private fun finishFrameless(d0: Draft, s: Scan): Draft {
    var d = d0
    if (d.decline != null) return d
    // A cue counts only with a value of one type before it and the same type after it.
    val typeSpans = listOf(d.dates.map { it.span }, d.times.map { it.span })
    for (m in reCue.findAll(s.w)) {
        if (!s.free(m.range)) continue
        if (typeSpans.any { sp -> sp.any { it.last < m.range.first } && sp.any { it.first > m.range.last } }) {
            d = d.copy(cue = m.range)
            s.take(m.range)
            break
        }
    }
    // Title: what is left, in order, with words as in the utterance.
    val segs = mutableListOf<IntRange>()
    var i = 0
    while (i < s.n) {
        if (s.used[i] || s.text[i].isWhitespace()) {
            i++
            continue
        }
        var j = i
        while (j < s.n && !s.used[j]) j++
        segs += i until j
        i = j
    }
    val words = mutableListOf<Pair<String, IntRange>>()
    for (seg in segs) {
        Regex("\\S+").findAll(s.text.substring(seg.first, seg.last + 1)).forEach {
            words += it.value to (it.range.first + seg.first)..(it.range.last + seg.first)
        }
    }
    words.removeAll { it.first.none { ch -> ch.isLetterOrDigit() } }
    var lo = 0
    var hi = words.size
    fun bare(x: String) = x.trim(',', '.', ';', ':').lowercase()
    while (lo < hi && bare(words[lo].first) in stripEnds) lo++
    while (hi > lo && bare(words[hi - 1].first) in stripEnds) hi--
    val kept = words.subList(lo, hi)
    val title = kept.joinToString(" ") { it.first }.trim().trimEnd(',', ';', ':', '.', '!', '?').let { t ->
        if (t.isEmpty()) null else t.replaceFirstChar { it.uppercase() }
    }
    return d.copy(title = title, titleSpans = kept.map { it.second })
}
