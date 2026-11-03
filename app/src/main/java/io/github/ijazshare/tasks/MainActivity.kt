/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.ijazshare.tasks.data.Export
import io.github.ijazshare.tasks.data.INBOX_ID
import io.github.ijazshare.tasks.data.QueryEnv
import io.github.ijazshare.tasks.data.Repository
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.data.TasksDb
import io.github.ijazshare.tasks.parse.CommandHandler
import io.github.ijazshare.tasks.parse.RoomStore
import io.github.ijazshare.tasks.reminders.Notifications.EXTRA_MISSED
import io.github.ijazshare.tasks.reminders.Notifications.EXTRA_TASK
import io.github.ijazshare.tasks.ui.CompletedScreen
import io.github.ijazshare.tasks.ui.Composer
import io.github.ijazshare.tasks.ui.ConsoleScreen
import io.github.ijazshare.tasks.ui.HAS_CONSOLE
import io.github.ijazshare.tasks.ui.HaloFab
import io.github.ijazshare.tasks.ui.MenuScreen
import io.github.ijazshare.tasks.ui.NEW_TASK
import io.github.ijazshare.tasks.ui.NavStack
import io.github.ijazshare.tasks.ui.RootBar
import io.github.ijazshare.tasks.ui.RootHeader
import io.github.ijazshare.tasks.ui.Route
import io.github.ijazshare.tasks.ui.ScreenBar
import io.github.ijazshare.tasks.ui.SearchScreen
import io.github.ijazshare.tasks.ui.SetupScreen
import io.github.ijazshare.tasks.ui.SetupState
import io.github.ijazshare.tasks.ui.TagChips
import io.github.ijazshare.tasks.ui.TaskListScreen
import io.github.ijazshare.tasks.ui.TaskSheet
import io.github.ijazshare.tasks.ui.TodayScreen
import io.github.ijazshare.tasks.ui.UpcomingScreen
import io.github.ijazshare.tasks.ui.nextAlerts
import io.github.ijazshare.tasks.ui.rememberNavStack
import io.github.ijazshare.tasks.ui.sharedToQuickAdd
import io.github.ijazshare.tasks.ui.theme.TasksColors
import io.github.ijazshare.tasks.ui.theme.TasksTheme
import io.github.ijazshare.tasks.ui.theme.rememberReduceMotion
import io.github.ijazshare.tasks.ui.todaySubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val openTask = mutableStateOf<Launch?>(null)
    private val prefill = mutableStateOf<String?>(null)
    private val focusTick = mutableIntStateOf(0)

    /** A share fills the composer (the user confirms with Add); the tile and the shortcut open it empty. */
    private fun Intent.quickAdd() {
        when {
            action == Intent.ACTION_SEND && type == "text/plain" ->
                prefill.value =
                    sharedToQuickAdd(getStringExtra(Intent.EXTRA_SUBJECT), getStringExtra(Intent.EXTRA_TEXT))

            action == ACTION_QUICK_ADD -> focusTick.intValue++
        }
    }

    private fun Intent.launch() = getStringExtra(EXTRA_TASK)?.let { Launch(it, getBooleanExtra(EXTRA_MISSED, false)) }

    /** Bumps whenever the user comes back, so permission state and "today" are read again. */
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openTask.value = intent.launch()
        if (savedInstanceState == null) intent.quickAdd()
        setContent { TasksTheme { TasksApp(openTask, resumes.intValue, prefill, focusTick.intValue) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTask.value = intent.launch()
        intent.quickAdd()
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }
}

/** A tapped notification: its task, and whether it was a Missed one (which opens the Missed list instead). */
private data class Launch(val taskId: String, val missed: Boolean)

const val ACTION_QUICK_ADD = "io.github.ijazshare.tasks.QUICK_ADD"
private const val CLOCK_POLL_MS = 60_000L

/** A screen plus its depth in the stack: depth going up is a push, going down a pop, equal is a tab switch. */
private data class Page(val route: Route, val depth: Int)

// Brief 8.8: decelerate in, accelerate out, short.
private val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val Accelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
private const val PUSH_MS = 260
private const val POP_MS = 200
private const val TAB_MS = 160
private const val SLIDE_IN_DP = 24
private const val SLIDE_OUT_DP = 8

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun TasksApp(
    openTask: MutableState<Launch?>,
    resumes: Int,
    prefill: MutableState<String?>,
    focusTick: Int,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { Repository(context) }
    val (nav, setNav) = rememberNavStack()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetTitle by rememberSaveable { mutableStateOf("") }
    var composer by rememberSaveable { mutableStateOf(false) }
    var filterSheet by rememberSaveable { mutableStateOf(false) }
    val store = remember { RoomStore(context, repo) }
    val handler = remember { CommandHandler(store) }
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
    val completed by remember { repo.completed() }.collectAsState(emptyList())
    val undated by remember { repo.undated() }.collectAsState(emptyList())
    val env = remember(today, tagLinks, labels, projects) {
        val tagNames = labels.associate { it.id to it.nameKey }
        val byTask = tagLinks.groupBy({ it.taskId }, { tagNames[it.labelId] ?: "" }).mapValues { it.value.toSet() }
        val projectNames = projects.associate { it.id to it.nameKey }
        QueryEnv(today, { byTask[it.id].orEmpty() }, { projectNames[it.projectId].orEmpty() })
    }
    val next = remember(alerts) { nextAlerts(alerts) }
    fun List<Task>.filtered(): List<Task> {
        if (filter.isEmpty()) return this
        val tagged = tagLinks.filter { it.labelId in filter }.map { it.taskId }.toSet()
        return filter { it.id in tagged }
    }
    val shown = remember(dated, tagLinks, filter) { dated.filtered() }
    val shownUndated = remember(undated, tagLinks, filter) { undated.filtered() }
    val setupOk = remember(resumes) { SetupState.read(context).complete }

    // Share, tile or shortcut: open the composer over whatever is showing.
    LaunchedEffect(prefill.value, focusTick) {
        if (prefill.value != null || focusTick > 0) composer = true
    }

    // Tapped notification: a Missed one opens the Missed list, any other opens its task.
    LaunchedEffect(openTask.value) {
        openTask.value?.let {
            if (it.missed) {
                setNav(nav.missed())
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
    val open: (Task) -> Unit = {
        sheet = it.id
        scope.launch { repo.opened(it.id) }
    }
    val done: (Task) -> Unit = { scope.launch { repo.complete(it.id) } }
    val back = { setNav(nav.pop()) }
    val toToday = { setNav(NavStack().go(Route.Today)) }

    // One handler for system back: pops a drilled-in screen (Console included), and Search returns to Today.
    // Today itself is not handled, so back leaves the app.
    BackHandler(enabled = nav.canPop || nav.top == Route.Search) { if (nav.canPop) back() else toToday() }

    val reduce = rememberReduceMotion()
    val density = LocalDensity.current
    val top = nav.top
    Scaffold(
        containerColor = TasksColors.Void,
        bottomBar = { if (top.root) RootBar(top) { setNav(nav.go(it)) } },
        floatingActionButton = {
            if (top == Route.Today || top == Route.Upcoming) HaloFab { composer = true }
        },
    ) { padding ->
        AnimatedContent(
            targetState = Page(top, nav.routes.size),
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = {
                val push = targetState.depth > initialState.depth
                val pop = targetState.depth < initialState.depth
                when {
                    reduce -> fadeIn(tween(0)) togetherWith fadeOut(tween(0))

                    push -> (
                        slideInHorizontally(tween(PUSH_MS, easing = Decelerate)) {
                            with(density) { SLIDE_IN_DP.dp.roundToPx() }
                        } +
                            fadeIn(tween(PUSH_MS, easing = Decelerate))
                        ) togetherWith (
                        slideOutHorizontally(tween(PUSH_MS, easing = Decelerate)) {
                            -with(density) { SLIDE_OUT_DP.dp.roundToPx() }
                        } +
                            fadeOut(tween(PUSH_MS, easing = Decelerate), targetAlpha = 0f)
                        )

                    pop -> (
                        slideInHorizontally(tween(POP_MS, easing = Accelerate)) {
                            -with(density) { SLIDE_OUT_DP.dp.roundToPx() }
                        } +
                            fadeIn(tween(POP_MS, easing = Accelerate))
                        ) togetherWith (
                        slideOutHorizontally(tween(POP_MS, easing = Accelerate)) {
                            with(density) { SLIDE_IN_DP.dp.roundToPx() }
                        } +
                            fadeOut(tween(POP_MS, easing = Accelerate))
                        )

                    else -> fadeIn(tween(TAB_MS, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(tween(TAB_MS, easing = FastOutSlowInEasing))
                }.using(SizeTransform(clip = false))
            },
            label = "route",
        ) { page ->
            Column(Modifier.fillMaxSize().background(TasksColors.Void)) {
                when (page.route) {
                    Route.Today -> {
                        RootHeader(
                            "Today",
                            todaySubtitle(today, shown, shownUndated),
                            labels.isNotEmpty(),
                            labels.filter { it.id in filter }.map { it.name },
                            !setupOk,
                            { filterSheet = true },
                            { filter = emptyList() },
                            { setNav(nav.go(Route.Menu)) },
                        )
                        TodayScreen(shown, shownUndated, today, next, missed.size, {
                            setNav(nav.go(Route.Missed))
                        }, open, done)
                    }

                    Route.Upcoming -> {
                        RootHeader(
                            "Upcoming",
                            null,
                            labels.isNotEmpty(),
                            labels.filter { it.id in filter }.map { it.name },
                            !setupOk,
                            { filterSheet = true },
                            { filter = emptyList() },
                            { setNav(nav.go(Route.Menu)) },
                        )
                        UpcomingScreen(shown, today, next, open, done)
                    }

                    Route.Search -> SearchScreen(dated + undated, env, next, toToday, open, done)

                    Route.Menu -> MenuScreen(
                        missed.size,
                        setupOk,
                        HAS_CONSOLE,
                        back,
                        { setNav(nav.go(it)) },
                    ) { save.launch("tasks-export-${LocalDate.now()}.json") }

                    Route.Missed -> {
                        ScreenBar("Missed", onBack = back)
                        TaskListScreen(missed, "Nothing missed.", next, open, done)
                    }

                    Route.Completed -> {
                        ScreenBar("Completed", onBack = back)
                        CompletedScreen(completed)
                    }

                    Route.Setup -> {
                        ScreenBar("Setup", onBack = back)
                        SetupScreen(resumes)
                    }

                    Route.Console -> {
                        ScreenBar("Console", onBack = back)
                        ConsoleScreen(store)
                    }
                }
            }
        }
    }
    if (composer) {
        ModalBottomSheet(
            onDismissRequest = { composer = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Composer(
                handle = handler::handle,
                projects = projects.map { it.name },
                labels = labels.map { it.name },
                onDone = {
                    composer = false
                    if (it.speech.isNotEmpty()) Toast.makeText(context, it.speech, Toast.LENGTH_SHORT).show()
                },
                onMore = {
                    composer = false
                    sheetTitle = it
                    sheet = NEW_TASK
                },
                prefill = prefill.value,
            ) { prefill.value = null }
        }
    }
    if (filterSheet) {
        ModalBottomSheet(onDismissRequest = { filterSheet = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("Filter by tag", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                TagChips(labels, filter) { id -> filter = if (id in filter) filter - id else filter + id }
                TextButton(onClick = { filterSheet = false }, Modifier.padding(horizontal = 8.dp)) { Text("Done") }
            }
        }
    }
    sheet?.let {
        TaskSheet(it, INBOX_ID, repo, onClose = {
            sheet = null
            sheetTitle = ""
        }, prefillTitle = sheetTitle)
    }
}
