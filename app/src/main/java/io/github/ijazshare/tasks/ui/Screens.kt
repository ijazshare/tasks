/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ijazshare.tasks.data.Completion
import io.github.ijazshare.tasks.data.Label
import io.github.ijazshare.tasks.data.Project
import io.github.ijazshare.tasks.data.QueryEnv
import io.github.ijazshare.tasks.data.Reminder
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.data.pending
import io.github.ijazshare.tasks.data.taskMatcher
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM")
private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun dateText(date: LocalDate): String = date.format(dateFormat)

/** The next alert: a clock time today, else the date too. */
private fun alertText(at: Instant): String {
    val z = at.atZone(ZoneId.systemDefault())
    return if (z.toLocalDate() ==
        LocalDate.now()
    ) {
        timeText(z.toLocalTime())
    } else {
        "${dateText(z.toLocalDate())} ${timeText(z.toLocalTime())}"
    }
}

/** The earliest pending alert of each task (a snooze counts), for the rows' "alert" text. */
fun nextAlerts(reminders: List<Reminder>): Map<String, Instant> = reminders.filter { it.pending() }
    .mapNotNull { r -> (r.snoozedUntil ?: r.atInstant)?.let { r.taskId to it } }
    .groupBy({ it.first }, { it.second }).mapValues { (_, at) -> at.min() }

/** Tag filter chips (spec 8.6 rule 7): several chosen = a task with any of them. */
@Composable
fun TagFilter(labels: List<Label>, selected: List<String>, onToggle: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach {
            FilterChip(selected = it.id in selected, onClick = { onToggle(it.id) }, label = { Text(it.name) })
        }
    }
}

fun timeText(time: java.time.LocalTime): String = time.format(timeFormat)

@Composable
fun TaskRow(task: Task, withDate: Boolean, next: Instant?, onOpen: () -> Unit, onComplete: () -> Unit) {
    val detail = listOfNotNull(
        task.dueDate?.takeIf { withDate }?.let(::dateText),
        task.dueTime?.let(::timeText),
        task.recurrenceText?.let { "repeats: $it" },
        next?.let { "alert ${alertText(it)}" },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = "Open task", onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = false,
            onCheckedChange = { onComplete() },
            modifier = Modifier.semantics { contentDescription = "Complete ${task.title}" },
        )
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Header(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 12.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    )
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyListScope.rows(
    tasks: List<Task>,
    withDate: Boolean,
    alerts: Map<String, Instant>,
    open: (Task) -> Unit,
    done: (Task) -> Unit,
) = items(tasks, key = { it.id }) { TaskRow(it, withDate, alerts[it.id], { open(it) }, { done(it) }) }

/**
 * Overdue (collapsible), then today: timed tasks, then all-day ones, then tasks with no date.
 * [tasks] is every open dated task.
 */
@Composable
fun TodayScreen(
    tasks: List<Task>,
    undated: List<Task>,
    today: LocalDate,
    alerts: Map<String, Instant>,
    open: (Task) -> Unit,
    done: (Task) -> Unit,
) {
    var showOverdue by rememberSaveable { mutableStateOf(true) }
    val overdue = tasks.filter { it.dueDate!! < today }
    val now = tasks.filter { it.dueDate == today }
    if (overdue.isEmpty() && now.isEmpty() && undated.isEmpty()) return Empty("Nothing due today.")
    LazyColumn(Modifier.fillMaxSize()) {
        if (overdue.isNotEmpty()) {
            item { Header("Overdue (${overdue.size}) ${if (showOverdue) "▾" else "▸"}") { showOverdue = !showOverdue } }
            if (showOverdue) rows(overdue, true, alerts, open, done)
        }
        val timed = now.filter { it.dueTime != null }
        val allDay = now.filter { it.dueTime == null }
        if (timed.isNotEmpty()) {
            item { Header("Today") }
            rows(timed, false, alerts, open, done)
        }
        if (allDay.isNotEmpty()) {
            item { Header("All day") }
            rows(allDay, false, alerts, open, done)
        }
        if (undated.isNotEmpty()) {
            item { Header("No date") }
            rows(undated, false, alerts, open, done)
        }
    }
}

/** Everything due after tomorrow, by day. [tasks] is every open dated task. */
@Composable
fun UpcomingScreen(
    tasks: List<Task>,
    today: LocalDate,
    alerts: Map<String, Instant>,
    open: (Task) -> Unit,
    done: (Task) -> Unit,
) {
    val coming = tasks.filter { it.dueDate!! >= today.plusDays(2) }
    if (coming.isEmpty()) return Empty("Nothing after tomorrow.")
    LazyColumn(Modifier.fillMaxSize()) {
        coming.groupBy { it.dueDate!! }.forEach { (date, group) ->
            item { Header(dateText(date)) }
            rows(group, false, alerts, open, done)
        }
    }
}

@Composable
fun TaskListScreen(
    tasks: List<Task>,
    empty: String,
    alerts: Map<String, Instant>,
    open: (Task) -> Unit,
    done: (Task) -> Unit,
    withDate: Boolean = true,
) {
    if (tasks.isEmpty()) return Empty(empty)
    LazyColumn(Modifier.fillMaxSize()) { rows(tasks, withDate, alerts, open, done) }
}

/** Search and filters (see [taskMatcher]) over every open task. */
@Composable
fun SearchScreen(
    tasks: List<Task>,
    env: QueryEnv,
    alerts: Map<String, Instant>,
    open: (Task) -> Unit,
    done: (Task) -> Unit,
) {
    var q by rememberSaveable { mutableStateOf("") }
    val found = remember(q, tasks, env) { if (q.isBlank()) emptyList() else tasks.filter { taskMatcher(q)(it, env) } }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            q,
            { q = it },
            Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search, or filter: today & overdue | !no date, %tag, #project") },
        )
        if (q.isNotBlank() && found.isEmpty()) Empty("No match.") else TaskListScreen(found, "", alerts, open, done)
    }
}

/** What was ticked off, newest first (the last 200). */
@Composable
fun CompletedScreen(items: List<Completion>) {
    if (items.isEmpty()) return Empty("Nothing completed yet.")
    LazyColumn(Modifier.fillMaxSize()) {
        items(items) {
            val at = it.at.atZone(ZoneId.systemDefault())
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp)) {
                Text(it.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${dateText(at.toLocalDate())} ${timeText(at.toLocalTime())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ProjectsScreen(projects: List<Project>, onProject: (Project) -> Unit, onNew: (String) -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize()) {
        items(projects, key = { it.id }) {
            Text(
                it.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                    onProject(it)
                }.padding(vertical = 16.dp),
            )
        }
        item { TextButton(onClick = { naming = true }) { Text("New project") } }
    }
    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("New project") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = {
                    onNew(name)
                    name = ""
                    naming = false
                }, enabled = name.isNotBlank()) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}
