/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.parse

/** Where a place reminder points: a saved place's [name] and whether it fires on leaving. */
data class PlaceTarget(val name: String, val onLeave: Boolean)

/**
 * "remind me to X when I get home / get to <place> / arrive at <place> / get back to <place> / leave
 * <place>", with the clause first or last. [target] is null when the place is not a saved one (the
 * handler then declines: no place is ever guessed). [said] is the place as spoken, article included.
 */
data class PlaceClause(val title: String, val target: PlaceTarget?, val said: String, val onLeave: Boolean) {
    /** "when you get to the shop", "when you leave work", "when you get home". */
    fun whenSay(): String = when {
        onLeave -> "when you leave $said"
        said.equals("home", ignoreCase = true) -> "when you get home"
        else -> "when you get to $said"
    }

    companion object {
        private fun place(group: String) = """(?<$group>(?:the\s+|my\s+)?\p{L}[\p{L}'’ -]*?)"""
        private val CLAUSE = Regex(
            """\b(?:when(?:ever)?|once|as soon as)\s+i\s+(?:(?:get|arrive|come)(?:\s+back)?""" +
                """(?:\s+(?:to|at|in)\s+${place("at")}|\s+(?<home>home)\b)|(?:leave|left)\s+${place("leave")})""" +
                """(?=\s*(?:[,.;!?]|$)|\s+(?:and|then|please|remind)\b)""",
            RegexOption.IGNORE_CASE,
        )
        private val REMIND = Regex("""^(?:please\s+)?remind me\s+(?:to\s+|that\s+)?(\S.*)$""", RegexOption.IGNORE_CASE)
        private val NOT_PLACES = setOf("it", "them", "that", "this", "there", "here", "one")

        private fun key(s: String) = s.trim().lowercase().removePrefix("the ").removePrefix("my ")

        /** Null if [text] has no place clause (or no "remind me ..." around it): the normal parser takes it. */
        fun read(text: String, saved: List<String>): PlaceClause? {
            val m = CLAUSE.find(text) ?: return null
            val onLeave = m.groups["leave"] != null
            val said = (m.groups["at"] ?: m.groups["home"] ?: m.groups["leave"])!!.value.trim()
            if (key(said) in NOT_PLACES) return null
            val rest = text.removeRange(m.range).replace(Regex("""\s+"""), " ").trim(' ', ',', '.', ';', '!', '?')
            val title = REMIND.find(rest)?.groupValues?.get(1)?.trim() ?: return null
            val name = saved.firstOrNull { key(it) == key(said) }
            return PlaceClause(title, name?.let { PlaceTarget(it, onLeave) }, said, onLeave)
        }
    }
}
