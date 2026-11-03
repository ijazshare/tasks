/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.ui

import android.text.format.DateFormat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ijazshare.tasks.data.Label
import io.github.ijazshare.tasks.data.Repository
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.time.DayRule
import io.github.ijazshare.tasks.time.Freq
import io.github.ijazshare.tasks.time.RecurrenceRule
import io.github.ijazshare.tasks.ui.theme.HaloShape
import io.github.ijazshare.tasks.ui.theme.TasksColors
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

const val NEW_TASK = "new"
private const val MINUTES_PER_HOUR = 60
private const val LAST_HOUR = 23
private const val WORKDAYS = 5
private const val MILLIS_PER_DAY = 86_400_000L

/** The recurrence choices, as rule strings for a start date. Typed rules arrive with the parser (M2). */
private fun presets(date: LocalDate?): List<Pair<String, String?>> {
    val d = date ?: LocalDate.now()
    fun rule(freq: Freq, vararg days: java.time.DayOfWeek, monthDay: Int? = null, month: Int? = null) =
        RecurrenceRule(freq, byDay = days.map { DayRule(null, it) }, byMonthDay = monthDay, byMonth = month).format()
    return listOf(
        "None" to null,
        "Every day" to rule(Freq.DAILY),
        "Every weekday" to rule(Freq.WEEKLY, *java.time.DayOfWeek.entries.take(WORKDAYS).toTypedArray()),
        "Every week" to rule(Freq.WEEKLY, d.dayOfWeek),
        "Every month" to rule(Freq.MONTHLY, monthDay = d.dayOfMonth),
        "Every year" to rule(Freq.YEARLY, monthDay = d.dayOfMonth, month = d.monthValue),
    )
}

/** Task detail and new-task sheet: plain title plus manual date and time pickers (parsing is M2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSheet(
    taskId: String,
    defaultProject: String,
    repo: Repository,
    onClose: () -> Unit,
    prefillTitle: String = "",
) {
    val loaded by produceState<Pair<Task, Set<String>>?>(null, taskId) {
        value = if (taskId == NEW_TASK) {
            repo.blank(defaultProject).copy(title = prefillTitle) to emptySet()
        } else {
            repo.task(taskId)?.let { it to repo.tagsOf(taskId) }
        }
    }
    val labels by repo.labels().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val (task, tags) = loaded ?: return
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        TaskEditor(
            task,
            tags,
            labels,
            taskId == NEW_TASK,
            onClose,
            onSave = { saved, tagSet ->
                scope.launch {
                    repo.save(saved, tagSet)
                    onClose()
                }
            },
            onDelete = {
                scope.launch {
                    repo.delete(task.id)
                    onClose()
                }
            },
            onCompleteForever = {
                scope.launch {
                    repo.complete(task.id, forever = true)
                    onClose()
                }
            },
        )
    }
}

/** The editor itself, free of the repository so a screenshot test can render it. Save is bottom-right. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditor(
    task: Task,
    initialTags: Set<String>,
    labels: List<Label>,
    isNew: Boolean,
    onClose: () -> Unit,
    onSave: (Task, Set<String>) -> Unit,
    onDelete: () -> Unit,
    onCompleteForever: () -> Unit,
) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf(task.title) }
    var description by rememberSaveable { mutableStateOf(task.description) }
    var epochDay by rememberSaveable { mutableStateOf(task.dueDate?.toEpochDay()) }
    var minutes by rememberSaveable { mutableStateOf(task.dueTime?.let { it.hour * MINUTES_PER_HOUR + it.minute }) }
    // Q3: proposed, unanswered. Floating is the default; this switch makes the time fixed to a zone.
    var keepTime by rememberSaveable { mutableStateOf(task.dueZone != null) }
    var tags by rememberSaveable { mutableStateOf(initialTags.toList()) }
    val date = epochDay?.let(LocalDate::ofEpochDay)
    var preset by rememberSaveable {
        mutableStateOf(presets(date).indexOfFirst { it.second == task.recurrence }.coerceAtLeast(0))
    }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Close top-left, Save bottom-right of the bar: the same place every time, never beside Delete.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(TasksIcons.Close, "Close") }
            Text(
                if (isNew) "New task" else "Task",
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
            )
            Button(enabled = title.isNotBlank(), shape = HaloShape.chip, onClick = {
                val rule = presets(date)[preset].second
                onSave(
                    task.copy(
                        title = title,
                        description = description,
                        dueDate = date,
                        dueTime = minutes?.let { LocalTime.of(it / MINUTES_PER_HOUR, it % MINUTES_PER_HOUR) },
                        dueZone = if (keepTime) task.dueZone ?: ZoneId.systemDefault().id else null,
                        recurrence = rule,
                        recurrenceText = presets(date)[preset].first.takeIf { rule != null },
                        recurrenceAnchor = "schedule".takeIf { rule != null },
                    ),
                    tags.toSet(),
                )
            }) { Text("Save") }
        }
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") })
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                pickDate = true
            }, shape = HaloShape.chip) { Text(date?.let(::dateText) ?: "Set date") }
            OutlinedButton(onClick = { pickTime = true }, enabled = date != null, shape = HaloShape.chip) {
                Text(
                    minutes?.let {
                        timeText(LocalTime.of(it / MINUTES_PER_HOUR, it % MINUTES_PER_HOUR))
                    } ?: "Set time",
                )
            }
            if (date != null) {
                TextButton(onClick = {
                    epochDay = null
                    minutes = null
                    preset = 0
                }) { Text("Clear") }
            }
        }
        if (minutes != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep this time when travelling", Modifier.weight(1f))
                Switch(keepTime, { keepTime = it })
            }
            Text(
                "Alerts come from the task's tags (urgent nags until Done).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Repeat", style = MaterialTheme.typography.labelLarge)
        ChipRow(presets(date).map { it.first }, preset, enabled = date != null) { preset = it }
        Text("Tags", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEach { l ->
                FilterChip(
                    selected = l.id in tags,
                    onClick = { tags = if (l.id in tags) tags - l.id else tags + l.id },
                    label = { Text(l.name) },
                )
            }
        }
        if (!isNew) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Q16: proposed, unanswered. Delete removes the whole series.
                TextButton(onClick = onDelete) { Text("Delete", color = TasksColors.Relevant) }
                if (task.recurrence != null) TextButton(onClick = onCompleteForever) { Text("Complete forever") }
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).toEpochDay() * MILLIS_PER_DAY,
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { epochDay = it / MILLIS_PER_DAY }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val start = minutes ?: ((LocalTime.now().hour + 1).coerceAtMost(LAST_HOUR) * MINUTES_PER_HOUR)
        val state = rememberTimePickerState(
            start / MINUTES_PER_HOUR,
            start % MINUTES_PER_HOUR,
            DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    minutes = state.hour * MINUTES_PER_HOUR + state.minute
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChipRow(labels: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, label ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(label) }, enabled = enabled)
        }
    }
}
