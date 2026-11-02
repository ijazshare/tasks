/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.data

import java.time.LocalDate

/** What a query needs besides the task: today, its tag names and its project name (both lower case). */
class QueryEnv(val today: LocalDate, val tags: (Task) -> Set<String>, val project: (Task) -> String)

/**
 * Search and the filter subset: `today`, `tomorrow`, `overdue`, `no date`, `%tag` / `@tag`,
 * `#project` / `##project`, `&`, `|`, `!` and parentheses. Any other word must appear in the title or description,
 * so a plain search is a filter too. Two terms side by side mean "and"; `!` binds tightest, then `&`, then `|`.
 * Text that is not a well-formed query is matched as one plain phrase.
 */
fun taskMatcher(query: String): (Task, QueryEnv) -> Boolean {
    val phrase = query.trim().lowercase()
    val plain: (Task, QueryEnv) -> Boolean = { t, _ -> "${t.title} ${t.description}".lowercase().contains(phrase) }
    return runCatching { QueryParser(tokens(query)).run() }.getOrNull() ?: plain
}

private fun tokens(q: String): List<String> = Regex("[()&|!]|[^\\s()&|!]+").findAll(q).map { it.value }.toList()

private typealias Test = (Task, QueryEnv) -> Boolean

private class QueryParser(private val toks: List<String>) {
    private var i = 0

    fun run(): Test {
        val t = or()
        check(i == toks.size)
        return t
    }

    private fun peek() = toks.getOrNull(i)

    private fun or(): Test {
        var l = and()
        while (peek() == "|") {
            i++
            val a = l
            val b = and()
            l = { t, e -> a(t, e) || b(t, e) }
        }
        return l
    }

    private fun and(): Test {
        var l = not()
        while (peek() == "&" || peek()?.let { it != "|" && it != ")" } == true) {
            if (peek() == "&") i++
            val a = l
            val b = not()
            l = { t, e -> a(t, e) && b(t, e) }
        }
        return l
    }

    private fun not(): Test {
        if (peek() == "!") {
            i++
            val a = not()
            return { t, e -> !a(t, e) }
        }
        return atom()
    }

    private fun atom(): Test {
        val w = checkNotNull(toks.getOrNull(i++))
        if (w == "(") {
            val t = or()
            check(toks.getOrNull(i++) == ")")
            return t
        }
        check(w !in setOf(")", "&", "|"))
        val lw = w.lowercase()
        return when {
            lw == "today" -> { t, e -> t.dueDate == e.today }

            lw == "tomorrow" -> { t, e -> t.dueDate == e.today.plusDays(1) }

            lw == "overdue" -> { t, e -> t.dueDate?.isBefore(e.today) == true }

            lw == "no" && peek()?.lowercase() == "date" -> {
                i++
                { t, _ -> t.dueDate == null }
            }

            (lw[0] == '%' || lw[0] == '@') && lw.length > 1 -> { t, e -> lw.drop(1) in e.tags(t) }

            lw[0] == '#' && lw.trimStart('#').isNotEmpty() -> { t, e -> e.project(t) == lw.trimStart('#') }

            else -> { t, _ -> "${t.title} ${t.description}".lowercase().contains(lw) }
        }
    }
}
