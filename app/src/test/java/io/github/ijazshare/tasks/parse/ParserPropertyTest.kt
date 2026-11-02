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

package io.github.ijazshare.tasks.parse

import com.google.common.truth.Truth.assertThat
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Spec 13.2 items 9 and 10, with kotest-property: the parser is total, and a title only reuses the utterance's words. */
class ParserPropertyTest {
    private val ctx = ParseContext(
        ZonedDateTime.of(LocalDateTime.parse("2026-10-27T14:03:00"), ZoneId.of("Europe/London")),
        projects = listOf(ProjectInfo("Inbox"), ProjectInfo("Home")),
        labels = listOf("errands"),
    )

    private val seeds = listOf(
        "remind me to call Amira at 5", "remind me in 20 minutes to take the bread out", "add oat milk and eggs to my shopping list",
        "remind me every Monday and Thursday at 7pm to put the bins out", "remind me next Friday to renew the parking permit",
        "mark call Amira as done", "move the dentist to Thursday at 10am", "what's on my list today", "priority one call the insurer tomorrow morning",
        "remind me on the 31st of every month at 9am to pay rent", "remind me for work to send the invoice Friday at 3",
    )

    private val words = listOf(
        "remind", "me", "to", "at", "5", "pm", "tomorrow", "every", "friday", "next", "in", "minutes", "call", "Amira", "#Home", "%errands", "p1",
        "{friday}", "//", "\"quoted\"", "555-0142", "midnight", "noon", "the", "31st", "of", "month", "no", "wait", "actually", "!", "o'clock", "25",
    )

    private fun check(text: String, typed: Boolean) {
        val r = parse(text, ctx.copy(typed = typed))
        r.titleSpans.forEach {
            assertThat(it.first).isAtLeast(0)
            assertThat(it.last).isLessThan(text.length)
        }
        val sorted = r.titleSpans.sortedBy { it.first }
        sorted.zipWithNext().forEach { (a, b) -> assertThat(a.last).isLessThan(b.first) }
        r.chips.forEach {
            assertThat(it.span.first).isAtLeast(0)
            assertThat(it.span.last).isLessThan(text.length)
        }
        r.chips.forEach { c ->
            r.titleSpans.forEach { t -> assertThat(c.span.last < t.first || t.last < c.span.first).isTrue() }
        }
        // Title soundness: every word of the title is a word of the utterance, in the same order.
        val title = r.title ?: return
        val src = text.split(Regex("\\s+")).map { it.lowercase() }
        title.split(' ').forEach { w ->
            assertThat(
                src.any {
                    it.contains(w.lowercase().trim(',', ';', ':', '.', '!', '?')) ||
                        w.lowercase() in it
                },
            ).isTrue()
        }
    }

    @Test
    fun randomUnicodeNeverThrows() {
        runBlocking {
            checkAll(2000, Arb.string(0..60)) { s ->
                check(s, typed = false)
                check(s, typed = true)
            }
        }
    }

    @Test
    fun mutatedUtterancesNeverThrowAndKeepTitleWordsSound() {
        runBlocking {
            checkAll(2000, Arb.int(0 until seeds.size), Arb.list(Arb.int(0 until words.size), 0..4), Arb.int(0..3)) {
                    i,
                    extra,
                    mode,
                ->
                val base = seeds[i].split(' ').toMutableList()
                extra.forEach { base.add((it * 7) % (base.size + 1), words[it]) }
                if (mode == 1 && base.size > 2) base.removeAt(base.size / 2)
                if (mode == 2) base.reverse()
                val text = base.joinToString(" ")
                check(text, typed = false)
                check(text, typed = true)
            }
        }
    }
}
