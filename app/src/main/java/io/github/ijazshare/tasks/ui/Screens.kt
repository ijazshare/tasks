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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ijazshare.tasks.data.Completion
import io.github.ijazshare.tasks.data.Label
import io.github.ijazshare.tasks.data.QueryEnv
import io.github.ijazshare.tasks.data.Reminder
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.data.pending
import io.github.ijazshare.tasks.data.taskMatcher
import io.github.ijazshare.tasks.ui.theme.HaloShape
import io.github.ijazshare.tasks.ui.theme.TasksColors
import io.github.ijazshare.tasks.ui.theme.rememberReduceMotion
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM")
private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun dateText(date: LocalDate): String = date.format(dateFormat)

fun timeText(time: java.time.LocalTime): String = time.format(timeFormat)

/** The next alert: a clock time today, else the date too. */
private fun alertText(at: Instant): String {
    val z = at.atZone(ZoneId.systemDefault())
    return if (z.toLocalDate() == LocalDate.now()) {
        timeText(z.toLocalTime())
    } else {
        "${dateText(z.toLocalDate())} ${timeText(z.toLocalTime())}"
    }
}

/** The earliest pending alert of each task (a snooze counts), for the rows' "alert" text. */
fun nextAlerts(reminders: List<Reminder>): Map<String, Instant> = reminders.filter { it.pending() }
    .mapNotNull { r -> (r.snoozedUntil ?: r.atInstant)?.let { r.taskId to it } }
    .groupBy({ it.first }, { it.second }).mapValues { (_, at) -> at.min() }

/** "Tue 3 Nov · 5 open": the count changes as tasks are done, so it earns its place under the title. */
fun todaySubtitle(today: LocalDate, dated: List<Task>, undated: List<Task>): String {
    val open = dated.count { it.dueDate!! <= today } + undated.size
    return if (open == 0) dateText(today) else "${dateText(today)} · $open open"
}

/** Top of Today and Upcoming: title, the filter and menu icons, and the active-filter chip when one is set. */
@Composable
fun RootHeader(
    title: String,
    subtitle: String?,
    hasLabels: Boolean,
    activeFilter: List<String>,
    setupNeedsAttention: Boolean,
    onFilter: () -> Unit,
    onClearFilter: () -> Unit,
    onMenu: () -> Unit,
) {
    Column {
        ScreenBar(title, subtitle) {
            if (hasLabels) {
                BarIcon(
                    TasksIcons.Filter,
                    "Filter by tag",
                    dot = activeFilter.isNotEmpty(),
                    onClick = onFilter,
                )
            }
            BarIcon(
                TasksIcons.Menu,
                if (setupNeedsAttention) "Menu, setup needs attention" else "Menu",
                setupNeedsAttention,
                onMenu,
            )
        }
        if (activeFilter.isNotEmpty()) {
            Row(
                Modifier.padding(start = 16.dp, bottom = 8.dp).clip(HaloShape.chip)
                    .background(
                        TasksColors.PanelActive,
                    ).clickable(onClickLabel = "Clear filter", onClick = onClearFilter)
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Tag: ${activeFilter.joinToString(", ")}", style = MaterialTheme.typography.labelLarge)
                Icon(TasksIcons.Close, "Clear filter", Modifier.size(16.dp), tint = TasksColors.Dim)
            }
        }
    }
}

/** A ring that fills green with a tick when done; the whole 56 dp leading column is the hit area. */
@Composable
private fun CompleteCircle(done: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier.width(56.dp).fillMaxHeight().clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val w = 1.5.dp.toPx()
            if (done) {
                drawCircle(TasksColors.Asset)
            } else {
                drawCircle(TasksColors.Dim, radius = (size.minDimension - w) / 2, style = Stroke(w))
            }
        }
        if (done) Icon(TasksIcons.Check, null, Modifier.size(16.dp), tint = TasksColors.Void)
    }
}

/**
 * One flat row (no card): ring, title, time on the right, a muted second line. Ticking the ring strikes the
 * title through and holds 400 ms before the task is really completed, so a mis-tap is visible and the row
 * then collapses out of the list.
 */
@Composable
fun TaskRow(
    task: Task,
    withDate: Boolean,
    next: Instant?,
    overdue: Boolean,
    onOpen: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var finishing by remember { mutableStateOf(false) }
    val reduce = rememberReduceMotion()
    LaunchedEffect(finishing) {
        if (finishing) {
            delay(if (reduce) 0 else HOLD_MS)
            onComplete()
        }
    }
    val complete = { finishing = true }
    val trailing = listOfNotNull(task.dueDate?.takeIf { withDate }?.let(::dateText), task.dueTime?.let(::timeText))
        .joinToString(" ")
    val detail = listOfNotNull(
        task.recurrenceText?.let { "repeats: $it" },
        next?.let { "alert ${alertText(it)}" },
    ).joinToString(" · ")
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().haloClickable("Open task", onOpen).heightIn(min = 56.dp)
                .semantics(mergeDescendants = true) {
                    if (overdue) stateDescription = "overdue"
                    customActions = listOf(
                        CustomAccessibilityAction("Complete") {
                            complete()
                            true
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompleteCircle(finishing, "Complete ${task.title}") { complete() }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (finishing) TasksColors.Dim else TasksColors.Bone,
                    textDecoration = TextDecoration.LineThrough.takeIf { finishing },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = TasksColors.Dim)
                }
            }
            if (trailing.isNotEmpty()) {
                Text(
                    trailing,
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) TasksColors.Relevant else TasksColors.Dim,
                )
            }
        }
        HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), color = TasksColors.Rule)
    }
}

private const val HOLD_MS = 400L
private const val COLLAPSED_DEG = -90f

/** Sticky, muted, small: the section label stays under the title while its rows scroll. */
private fun LazyListScope.header(text: String, onClick: (() -> Unit)? = null, collapsed: Boolean? = null) {
    stickyHeader {
        Row(
            Modifier.fillMaxWidth().background(TasksColors.Void)
                .let { if (onClick != null) it.clickable(onClickLabel = "Show or hide", onClick = onClick) else it }
                .heightIn(min = 40.dp).padding(horizontal = 16.dp).semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text.uppercase(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = TasksColors.Dim,
            )
            if (collapsed != null) {
                Icon(
                    TasksIcons.ExpandDown,
                    null,
                    Modifier.size(18.dp).rotate(if (collapsed) COLLAPSED_DEG else 0f),
                    tint = TasksColors.Dim,
                )
            }
        }
    }
}

@Suppress("LongParameterList")
private fun LazyListScope.rows(
    tasks: List<Task>,
    withDate: Boolean,
    alerts: Map<String, Instant>,
    overdue: Boolean,
    open: (Task) -> Unit,
    done: (Task) -> Unit,
) = items(tasks, key = { it.id }) { RowItem(it, withDate, alerts[it.id], overdue, open, done) }

@Composable
private fun LazyItemScope.RowItem(
    task: Task,
    withDate: Boolean,
    next: Instant?,
    overdue: Boolean,
    open: (Task) -> Unit,
    done: (Task) -> Unit,
) {
    // Rows slide up to fill the gap when one is completed; with animations off they just jump.
    val move = if (rememberReduceMotion()) Modifier else Modifier.animateItem()
    TaskRow(task, withDate, next, overdue, { open(task) }, { done(task) }, move)
}

private val FAB_CLEARANCE = 96.dp

/**
 * Overdue (collapsible), then today, then tasks with no date. [tasks] is every open dated task. The Missed
 * banner sits on top while [missedCount] is above zero.
 */
@Composable
fun TodayScreen(
    tasks: List<Task>,
    undated: List<Task>,
    today: LocalDate,
    alerts: Map<String, Instant>,
    missedCount: Int,
    onMissed: () -> Unit,
    open: (Task) -> Unit,
    done: (Task) -> Unit,
) {
    var showOverdue by rememberSaveable { mutableStateOf(true) }
    val overdue = tasks.filter { it.dueDate!! < today }
    val now = tasks.filter { it.dueDate == today }.sortedBy { it.dueTime == null }
    Column(Modifier.fillMaxSize()) {
        if (missedCount > 0) MissedBanner(missedCount, onMissed)
        if (overdue.isEmpty() && now.isEmpty() && undated.isEmpty()) {
            EmptyState("All clear.", "Nothing due. Tap + to add.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FAB_CLEARANCE)) {
                if (overdue.isNotEmpty()) {
                    header("Overdue (${overdue.size})", { showOverdue = !showOverdue }, !showOverdue)
                    if (showOverdue) rows(overdue, true, alerts, true, open, done)
                }
                if (now.isNotEmpty()) {
                    header("Today")
                    rows(now, false, alerts, false, open, done)
                }
                if (undated.isNotEmpty()) {
                    header("No date")
                    rows(undated, false, alerts, false, open, done)
                }
            }
        }
    }
}

/** Tomorrow first, then every later day. [tasks] is every open dated task. */
@Composable
fun UpcomingScreen(
    tasks: List<Task>,
    today: LocalDate,
    alerts: Map<String, Instant>,
    open: (Task) -> Unit,
    done: (Task) -> Unit,
) {
    val coming = tasks.filter { it.dueDate!! > today }
    if (coming.isEmpty()) {
        return EmptyState(
            "Nothing coming up.",
            "Tasks with a later date show here.",
            TasksIcons.Calendar,
        )
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FAB_CLEARANCE)) {
        coming.groupBy { it.dueDate!! }.forEach { (date, group) ->
            header(if (date == today.plusDays(1)) "Tomorrow · ${dateText(date)}" else dateText(date))
            rows(group, false, alerts, false, open, done)
        }
    }
}

/** A plain list of tasks (Missed). Rows show their date, since they are all in the past. */
@Composable
fun TaskListScreen(
    tasks: List<Task>,
    empty: String,
    alerts: Map<String, Instant>,
    open: (Task) -> Unit,
    done: (Task) -> Unit,
) {
    if (tasks.isEmpty()) return EmptyState(empty, "Nothing to review.")
    LazyColumn(Modifier.fillMaxSize()) { rows(tasks, true, alerts, true, open, done) }
}

/** Search and filters (see [taskMatcher]) over every open task; the field is focused on open. */
@Composable
fun SearchScreen(
    tasks: List<Task>,
    env: QueryEnv,
    alerts: Map<String, Instant>,
    onBack: () -> Unit,
    open: (Task) -> Unit,
    done: (Task) -> Unit,
    initial: String = "",
) {
    var q by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (initial.isEmpty()) focus.requestFocus() }
    val found = remember(q, tasks, env) { if (q.isBlank()) emptyList() else tasks.filter { taskMatcher(q)(it, env) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, Modifier.padding(start = 4.dp)) { Icon(TasksIcons.Back, "Back") }
            OutlinedTextField(
                q,
                { q = it },
                Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                placeholder = { Text("Search tasks") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = {
                    if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(TasksIcons.Close, "Clear search") }
                },
            )
        }
        when {
            q.isBlank() -> EmptyState("Search.", "Try a word, %tag, #project or: today & overdue.", TasksIcons.Search)
            found.isEmpty() -> EmptyState("No match.", "Nothing open matches \"$q\".", TasksIcons.Search)
            else -> LazyColumn(Modifier.fillMaxSize()) { rows(found, true, alerts, false, open, done) }
        }
    }
}

/** What was ticked off, newest first (the last 200). */
@Composable
fun CompletedScreen(items: List<Completion>) {
    if (items.isEmpty()) return EmptyState("Nothing completed yet.", "Done tasks collect here.")
    LazyColumn(Modifier.fillMaxSize()) {
        items(items) {
            val at = it.at.atZone(ZoneId.systemDefault())
            Column {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(TasksIcons.Check, null, tint = TasksColors.Asset)
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(it.title, style = MaterialTheme.typography.titleMedium, color = TasksColors.Dim)
                        Text(
                            "${dateText(at.toLocalDate())} ${timeText(at.toLocalTime())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TasksColors.Dim,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), color = TasksColors.Rule)
            }
        }
    }
}

/** Tag chips for the filter sheet (several chosen = a task with any of them). */
@Composable
fun TagChips(labels: List<Label>, selected: List<String>, onToggle: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach {
            FilterChip(
                selected = it.id in selected,
                onClick = { onToggle(it.id) },
                label = { Text(it.name) },
                shape = HaloShape.chip,
            )
        }
    }
}
