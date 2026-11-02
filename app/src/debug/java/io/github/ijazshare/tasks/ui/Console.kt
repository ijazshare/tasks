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

package io.github.ijazshare.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.ijazshare.tasks.parse.CommandHandler
import io.github.ijazshare.tasks.parse.LabelRef
import io.github.ijazshare.tasks.parse.NewTask
import io.github.ijazshare.tasks.parse.ParseContext
import io.github.ijazshare.tasks.parse.Request
import io.github.ijazshare.tasks.parse.Response
import io.github.ijazshare.tasks.parse.Snapshot
import io.github.ijazshare.tasks.parse.TaskStore
import io.github.ijazshare.tasks.parse.parse
import io.github.ijazshare.tasks.time.DueSpec
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

const val HAS_CONSOLE = true

/** Reads from the real store, records writes instead of making them. */
private class DryStore(private val real: TaskStore, val log: MutableList<String>) : TaskStore {
    override suspend fun snapshot(): Snapshot = real.snapshot()

    override suspend fun create(task: NewTask): String = "dry".also { log += "create $task" }

    override suspend fun complete(id: String) {
        log += "complete $id"
    }

    override suspend fun delete(id: String) {
        log += "delete $id"
    }

    override suspend fun reschedule(id: String, due: DueSpec) {
        log += "reschedule $id $due"
    }

    override suspend fun change(id: String, priority: Int?, project: String?, label: LabelRef?) {
        log += "change $id p=$priority project=$project label=$label"
    }

    override suspend fun addProject(name: String): Boolean = true.also { log += "addProject $name" }

    override suspend fun addLabel(name: String): Boolean = true.also { log += "addLabel $name" }

    override suspend fun view(id: String) = real.view(id)
}

/**
 * Debug-only contract console (spec 14, M2): type an utterance and see the ParseResult and the handler's
 * answer. "Dry run" (the default) reads your real tasks but writes nothing. It bypasses the IPC layer (no undo, no
 * persisted conversations); the real path is `ipc/Commands`.
 */
@Composable
fun ConsoleScreen(store: TaskStore) {
    val scope = rememberCoroutineScope()
    val writes = remember { mutableListOf<String>() }
    var dry by remember { mutableStateOf(true) }
    var typed by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<Response?>(null) }
    val handlers = remember(dry) { CommandHandler(if (dry) DryStore(store, writes) else store) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(dry, { dry = it })
            Text(if (dry) " Dry run (writes nothing)" else " LIVE: writes your real tasks")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(typed, { typed = it })
            Text(" Typed (quick-add rules)")
        }
        OutlinedTextField(text, {
            text = it
        }, Modifier.fillMaxWidth(), label = { Text("Utterance, or the answer to a question") })
        Button(
            enabled = text.isNotBlank(),
            onClick = {
                scope.launch {
                    val now = ZonedDateTime.now()
                    val conv = last?.conversation
                    writes.clear()
                    val ctx = store.snapshot().let {
                        ParseContext(
                            now,
                            typed = typed,
                            projects = it.projects,
                            labels = it.labels,
                            openTasks = it.openTasks,
                        )
                    }
                    val parsed = if (conv == null) "ParseResult:\n${parse(text, ctx)}\n\n" else ""
                    val r = handlers.handle(
                        Request(
                            if (conv !=
                                null
                            ) {
                                "follow_up"
                            } else {
                                "utterance"
                            },
                            text,
                            conv?.id,
                            now = now,
                            typed = typed,
                        ),
                    )
                    last = r
                    out =
                        parsed +
                        "Response: ${r.copy(parse = null)}\n\nWrites: ${writes.ifEmpty {
                            listOf(if (dry) "none" else "see Today")
                        }}"
                    text = ""
                }
            },
        ) { Text(if (last?.conversation != null) "Answer" else "Send") }
        Text(out, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
