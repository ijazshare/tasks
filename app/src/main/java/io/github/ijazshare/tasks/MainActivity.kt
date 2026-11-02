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
import io.github.ijazshare.tasks.reminders.Notifications.EXTRA_TASK
import io.github.ijazshare.tasks.ui.NEW_TASK
import io.github.ijazshare.tasks.ui.ProjectsScreen
import io.github.ijazshare.tasks.ui.SetupScreen
import io.github.ijazshare.tasks.ui.SetupState
import io.github.ijazshare.tasks.ui.TaskListScreen
import io.github.ijazshare.tasks.ui.TaskSheet
import io.github.ijazshare.tasks.ui.TodayScreen
import io.github.ijazshare.tasks.ui.UpcomingScreen
import io.github.ijazshare.tasks.ui.theme.TasksTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val openTask = mutableStateOf<String?>(null)

    /** Bumps whenever the user comes back, so permission state and "today" are read again. */
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openTask.value = intent.getStringExtra(EXTRA_TASK)
        setContent { TasksTheme { TasksApp(openTask, resumes.intValue) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTask.value = intent.getStringExtra(EXTRA_TASK)
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }
}

private val tabs = listOf("Today", "Upcoming", "Inbox", "Projects")
private const val UPCOMING_DAYS = 14L
private const val CLOCK_POLL_MS = 60_000L

@Composable
private fun TasksApp(openTask: androidx.compose.runtime.MutableState<String?>, resumes: Int) {
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
    val dated by remember(today) { repo.dated(today.plusDays(UPCOMING_DAYS)) }.collectAsState(emptyList())
    val inbox by remember { repo.inProject(INBOX_ID) }.collectAsState(emptyList())
    val inProject by remember(projectId) { projectId?.let(repo::inProject) ?: emptyFlow() }.collectAsState(emptyList())
    val needsSetup = remember(resumes) { !SetupState.read(context).complete }

    // Tapped notification: open that task, and count the reminder as seen.
    LaunchedEffect(openTask.value) {
        openTask.value?.let {
            sheet = it
            openTask.value = null
            repo.markSeen(it)
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
    val open: (io.github.ijazshare.tasks.data.Task) -> Unit = { sheet = it.id }
    val done: (io.github.ijazshare.tasks.data.Task) -> Unit = { scope.launch { repo.complete(it.id) } }
    val project = projects.firstOrNull { it.id == projectId }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i && !setup,
                        onClick = {
                            tab = i
                            setup = false
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
                    if (setup) "Setup" else project?.name ?: tabs[tab],
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
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
            Box(Modifier.fillMaxSize()) {
                when {
                    setup -> SetupScreen(resumes)

                    tab == 0 -> TodayScreen(dated, today, open, done)

                    tab == 1 -> UpcomingScreen(dated, today, open, done)

                    tab == 2 -> TaskListScreen(inbox, "Your inbox is empty.", open, done)

                    project != null -> TaskListScreen(inProject, "No tasks in this project.", open, done)

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
