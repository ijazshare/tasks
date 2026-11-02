/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ijazshare.tasks.data.Export
import io.github.ijazshare.tasks.data.INBOX_ID
import io.github.ijazshare.tasks.data.Repository
import io.github.ijazshare.tasks.data.TasksDb
import io.github.ijazshare.tasks.reminders.Notifications.EXTRA_MISSED
import io.github.ijazshare.tasks.reminders.Notifications.EXTRA_TASK
import io.github.ijazshare.tasks.ui.NEW_TASK
import io.github.ijazshare.tasks.ui.ProjectsScreen
import io.github.ijazshare.tasks.ui.SetupScreen
import io.github.ijazshare.tasks.ui.SetupState
import io.github.ijazshare.tasks.ui.TagFilter
import io.github.ijazshare.tasks.ui.TaskListScreen
import io.github.ijazshare.tasks.ui.TaskSheet
import io.github.ijazshare.tasks.ui.TodayScreen
import io.github.ijazshare.tasks.ui.UpcomingScreen
import io.github.ijazshare.tasks.ui.nextAlerts
import io.github.ijazshare.tasks.ui.theme.TasksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val openTask = mutableStateOf<Launch?>(null)

    private fun Intent.launch() = getStringExtra(EXTRA_TASK)?.let { Launch(it, getBooleanExtra(EXTRA_MISSED, false)) }

    /** Bumps whenever the user comes back, so permission state and "today" are read again. */
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openTask.value = intent.launch()
        setContent { TasksTheme { TasksApp(openTask, resumes.intValue) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTask.value = intent.launch()
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }
}

/** A tapped notification: its task, and whether it was a Missed one (which opens the Missed list instead). */
private data class Launch(val taskId: String, val missed: Boolean)

private val tabs = listOf("Today", "Tomorrow", "Upcoming", "Inbox", "Projects")
private const val MISSED_TITLE = "Missed"
private const val CLOCK_POLL_MS = 60_000L

@Composable
private fun TasksApp(openTask: androidx.compose.runtime.MutableState<Launch?>, resumes: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { Repository(context) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var setup by rememberSaveable { mutableStateOf(false) }
    val today by produceState(LocalDate.now(), resumes) {
        while (true) {
            value = LocalDate.now()
            delay(CLOCK_POLL_MS)
        }
    }
    val projects by repo.projects().collectAsState(emptyList())
    val dated by remember { repo.dated() }.collectAsState(emptyList())
    val labels by remember { repo.labels() }.collectAsState(emptyList())
    val tagLinks by remember { repo.taskLabels() }.collectAsState(emptyList())
    val alerts by remember { repo.reminders() }.collectAsState(emptyList())
    val missed by remember { repo.missed() }.collectAsState(emptyList())
    var filter by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showMissed by rememberSaveable { mutableStateOf(false) }
    val next = remember(alerts) { nextAlerts(alerts) }
    val shown = remember(dated, tagLinks, filter) {
        if (filter.isEmpty()) {
            dated
        } else {
            val tagged = tagLinks.filter { it.labelId in filter }.map { it.taskId }.toSet()
            dated.filter { it.id in tagged }
        }
    }
    val inbox by remember { repo.inProject(INBOX_ID) }.collectAsState(emptyList())
    val inProject by remember(projectId) { projectId?.let(repo::inProject) ?: emptyFlow() }.collectAsState(emptyList())
    val needsSetup = remember(resumes) { !SetupState.read(context).complete }

    // Tapped notification: a Missed one opens the Missed list, any other opens its task.
    LaunchedEffect(openTask.value) {
        openTask.value?.let {
            if (it.missed) {
                showMissed = true
                setup = false
                repo.markSeen(it.taskId)
            } else {
                sheet = it.taskId
                repo.opened(it.taskId)
            }
            openTask.value = null
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = Export.toJson(TasksDb.get(context).openHelper.writableDatabase)
                        context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                    }.isSuccess
                }
                Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val open: (io.github.ijazshare.tasks.data.Task) -> Unit = {
        sheet = it.id
        scope.launch { repo.opened(it.id) }
    }
    val done: (io.github.ijazshare.tasks.data.Task) -> Unit = { scope.launch { repo.complete(it.id) } }
    val project = projects.firstOrNull { it.id == projectId }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i && !setup && !showMissed,
                        onClick = {
                            tab = i
                            setup = false
                            showMissed = false
                            projectId = null
                        },
                        icon = {},
                        label = { Text(label) },
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { sheet = NEW_TASK },
                modifier = Modifier.semantics { contentDescription = "Add task" },
            ) { Text("+") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        setup -> "Setup"
                        showMissed -> MISSED_TITLE
                        else -> project?.name ?: tabs[tab]
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (missed.isNotEmpty() && !showMissed) {
                    TextButton(onClick = { showMissed = true }) { Text("$MISSED_TITLE (${missed.size})") }
                }
                TextButton(onClick = { setup = !setup }) { Text(if (setup) "Back" else "Setup") }
                // Settings will own the export in a later milestone; until then it lives in the header.
                TextButton(onClick = { save.launch("tasks-export-${LocalDate.now()}.json") }) { Text("Export") }
            }
            if (needsSetup && !setup) {
                Text(
                    "Reminders need setup. Tap to finish.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().clickable { setup = true }.padding(vertical = 12.dp),
                )
            }
            if (!setup && !showMissed && tab <= 2) {
                TagFilter(labels, filter) { id -> filter = if (id in filter) filter - id else filter + id }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    setup -> SetupScreen(resumes)

                    showMissed -> TaskListScreen(missed, "Nothing missed.", next, open, done)

                    tab == 0 -> TodayScreen(shown, today, next, open, done)

                    tab == 1 -> TaskListScreen(
                        shown.filter { it.dueDate == today.plusDays(1) },
                        "Nothing due tomorrow.",
                        next,
                        open,
                        done,
                        withDate = false,
                    )

                    tab == 2 -> UpcomingScreen(shown, today, next, open, done)

                    tab == 3 -> TaskListScreen(inbox, "Your inbox is empty.", next, open, done)

                    project != null -> TaskListScreen(inProject, "No tasks in this project.", next, open, done)

                    else -> ProjectsScreen(
                        projects,
                        onProject = { projectId = it.id },
                        onNew = { name -> scope.launch { repo.addProject(name) } },
                    )
                }
            }
        }
    }
    sheet?.let {
        TaskSheet(it, projectId ?: INBOX_ID, projects, repo, onClose = { sheet = null })
    }
}
