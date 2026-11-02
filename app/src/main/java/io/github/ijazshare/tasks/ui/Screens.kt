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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ijazshare.tasks.data.Project
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.ui.theme.TasksColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM")
private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private const val UPCOMING_DAYS = 14L

fun dateText(date: LocalDate): String = date.format(dateFormat)

fun timeText(time: java.time.LocalTime): String = time.format(timeFormat)

@Composable
fun TaskRow(task: Task, withDate: Boolean, onOpen: () -> Unit, onComplete: () -> Unit) {
    val color = when (task.priority) {
        1 -> TasksColors.Relevant
        2 -> TasksColors.Asset
        3 -> TasksColors.Admin
        else -> TasksColors.Ghost
    }
    val detail = listOfNotNull(
        task.dueDate?.takeIf { withDate }?.let(::dateText),
        task.dueTime?.let(::timeText),
        task.recurrenceText?.let { "repeats: $it" },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = "Open task", onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
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

private fun LazyListScope.rows(tasks: List<Task>, withDate: Boolean, open: (Task) -> Unit, done: (Task) -> Unit) =
    items(tasks, key = { it.id }) { TaskRow(it, withDate, { open(it) }, { done(it) }) }

/** Overdue (collapsible), then today: timed tasks, then all-day ones. [tasks] is every open task due up to today. */
@Composable
fun TodayScreen(tasks: List<Task>, today: LocalDate, open: (Task) -> Unit, done: (Task) -> Unit) {
    var showOverdue by rememberSaveable { mutableStateOf(true) }
    val overdue = tasks.filter { it.dueDate!! < today }
    val now = tasks.filter { it.dueDate == today }
    if (overdue.isEmpty() && now.isEmpty()) return Empty("Nothing due today.")
    LazyColumn(Modifier.fillMaxSize()) {
        if (overdue.isNotEmpty()) {
            item { Header("Overdue (${overdue.size}) ${if (showOverdue) "▾" else "▸"}") { showOverdue = !showOverdue } }
            if (showOverdue) rows(overdue, true, open, done)
        }
        val timed = now.filter { it.dueTime != null }
        val allDay = now.filter { it.dueTime == null }
        if (timed.isNotEmpty()) {
            item { Header("Today") }
            rows(timed, false, open, done)
        }
        if (allDay.isNotEmpty()) {
            item { Header("All day") }
            rows(allDay, false, open, done)
        }
    }
}

/** The next 14 days, grouped by day. [tasks] is every open task due before today + 14. */
@Composable
fun UpcomingScreen(tasks: List<Task>, today: LocalDate, open: (Task) -> Unit, done: (Task) -> Unit) {
    val coming = tasks.filter { it.dueDate!! >= today && it.dueDate < today.plusDays(UPCOMING_DAYS) }
    if (coming.isEmpty()) return Empty("Nothing in the next 14 days.")
    LazyColumn(Modifier.fillMaxSize()) {
        coming.groupBy { it.dueDate!! }.forEach { (date, group) ->
            item { Header(if (date == today) "Today" else dateText(date)) }
            rows(group, false, open, done)
        }
    }
}

@Composable
fun TaskListScreen(tasks: List<Task>, empty: String, open: (Task) -> Unit, done: (Task) -> Unit) {
    if (tasks.isEmpty()) return Empty(empty)
    LazyColumn(Modifier.fillMaxSize()) { rows(tasks, true, open, done) }
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
