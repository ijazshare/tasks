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

import io.github.ijazshare.tasks.ui.sharedToQuickAdd
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class QueryTest {
    private val today = LocalDate.of(2026, 11, 2)
    private val now = Instant.EPOCH

    private fun task(title: String, due: LocalDate?, p: Int = 4, project: String = "inbox", desc: String = "") =
        Task(
            title, title, desc, project, null, null, 0, p, due, null, null, null, null, null,
            null, null, null, now, now, null, "typed", null, null,
        )

    private val tasks = listOf(
        task("Water Amira's ferns", today, 1, "home"),
        task("Pay the gas bill", today.minusDays(2), 2, "home", "account 4471"),
        task("Plan Zaid's trip", today.plusDays(1), 4, "travel"),
        task("Sort the shed", null, 3, "home"),
    )
    private val tags = mapOf("Water Amira's ferns" to setOf("errands"), "Sort the shed" to setOf("errands", "slow"))
    private val env = QueryEnv(today, { tags[it.id].orEmpty() }, { it.projectId })

    private fun run(q: String) = tasks.filter { taskMatcher(q)(it, env) }.map { it.title }

    @Test fun dates() {
        assertEquals(listOf("Water Amira's ferns"), run("today"))
        assertEquals(listOf("Pay the gas bill"), run("overdue"))
        assertEquals(listOf("Sort the shed"), run("no date"))
    }

    @Test fun operators() {
        assertEquals(listOf("Water Amira's ferns", "Pay the gas bill"), run("#Home & (today | overdue)"))
        assertEquals(listOf("Pay the gas bill", "Sort the shed"), run("#home & !today"))
        assertEquals(listOf("Water Amira's ferns", "Sort the shed"), run("%errands"))
        assertEquals(listOf("Sort the shed"), run("@slow & !today"))
        assertEquals(listOf("Plan Zaid's trip"), run("tomorrow | ##travel"))
    }

    @Test fun plainWords() {
        assertEquals(listOf("Pay the gas bill"), run("gas bill"))
        assertEquals(listOf("Pay the gas bill"), run("4471"))
        assertEquals(listOf("Plan Zaid's trip"), run("zaid"))
    }

    @Test fun malformedFallsBackToPhrase() {
        assertEquals(emptyList<String>(), run("(today"))
        assertEquals(emptyList<String>(), run("& &"))
    }

    @Test fun shareText() {
        assertEquals("Book dentist // Reply by Friday", sharedToQuickAdd("Book dentist", "Reply by Friday"))
        assertEquals("Call Amira tomorrow 5pm", sharedToQuickAdd(null, "  Call Amira tomorrow 5pm \n"))
        assertEquals("Trip // line two line three", sharedToQuickAdd(null, "Trip\nline two\n\nline three"))
        assertEquals("Same", sharedToQuickAdd("Same", "Same"))
        assertEquals(500, sharedToQuickAdd(null, "x".repeat(900)).length)
        assertEquals("", sharedToQuickAdd(null, null))
    }
}
