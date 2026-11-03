/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength")

package io.github.ijazshare.tasks.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ijazshare.tasks.parse.CommandHandler
import io.github.ijazshare.tasks.parse.OpenTask
import io.github.ijazshare.tasks.parse.ParseContext
import io.github.ijazshare.tasks.parse.ProjectInfo
import io.github.ijazshare.tasks.parse.Request
import io.github.ijazshare.tasks.parse.Response
import io.github.ijazshare.tasks.parse.parse
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * The typed quick-add bar (spec 6.3): chips show what was understood as you type, and a tapped chip is turned
 * back into plain title text. Saving goes through the same [CommandHandler] as everything else; a question
 * (needs_input) shows its options under the field and the next send answers it.
 */
@Composable
fun QuickAddBar(
    handler: CommandHandler,
    projects: List<String>,
    labels: List<String>,
    prefill: String? = null,
    focusTick: Int = 0,
    onPrefilled: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    // A share fills the field and waits for the user to press Add; the tile and shortcut just focus it.
    LaunchedEffect(prefill) {
        if (prefill != null) {
            text = prefill
            onPrefilled()
        }
    }
    LaunchedEffect(focusTick) { if (focusTick > 0 || prefill != null) focus.requestFocus() }
    var plain by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var reply by remember { mutableStateOf<Response?>(null) }
    val live = remember(text, plain, projects, labels) {
        if (text.isBlank()) {
            emptyList()
        } else {
            val ctx = ParseContext(
                ZonedDateTime.now(),
                typed = true,
                projects = projects.map(::ProjectInfo),
                labels = labels,
                openTasks = emptyList<OpenTask>(),
                plain = plain.toSet(),
            )
            parse(text, ctx).chips.sortedBy { it.span.first }
        }
    }

    fun send(optionId: String? = null, answer: String? = null) {
        val conv = reply?.conversation
        val sent = answer ?: text
        scope.launch {
            val req = Request(
                type = if (conv != null) "follow_up" else "utterance",
                utterance = sent,
                conversationId = conv?.id,
                optionId = optionId,
                now = ZonedDateTime.now(),
                typed = true,
                plain = plain.toSet(),
            )
            val r = handler.handle(req)
            reply = r
            if (r.status != "needs_input") {
                text = ""
                plain = emptyList()
            } else if (conv != null) {
                text = ""
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            label = {
                Text(
                    reply?.takeIf { it.status == "needs_input" }?.speech
                        ?: "Quick add, like: call Amira tomorrow 5pm #Home %errands",
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) send() }),
            trailingIcon = { TextButton(onClick = { send() }, enabled = text.isNotBlank()) { Text("Add") } },
        )
        if (live.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                live.forEach { chip ->
                    val word = text.substring(
                        chip.span.first.coerceAtLeast(0),
                        (chip.span.last + 1).coerceAtMost(text.length),
                    )
                    AssistChip(
                        onClick = { plain = plain + word.trim() },
                        label = { Text("${chip.kind}: ${word.trim()}") },
                    )
                }
            }
        }
        val r = reply
        if (r != null && r.status == "needs_input") {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                r.conversation?.options?.forEach { (id, label) ->
                    TextButton(onClick = { send(optionId = id, answer = label) }) { Text(label) }
                }
                TextButton(onClick = {
                    scope.launch { handler.handle(Request("cancel", now = ZonedDateTime.now(), typed = true)) }
                    reply = null
                }) { Text("Cancel") }
            }
        } else if (r != null && r.speech.isNotEmpty()) {
            Text(
                r.speech,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The quick-add text for a share: the subject (or the first line) is the title and the rest becomes the description
 * with the parser's ` // ` marker, all on one line and capped so a pasted article cannot flood the field.
 */
fun sharedToQuickAdd(subject: String?, text: String?): String {
    val lines = (text ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
    val subj = subject?.trim().orEmpty()
    val title = subj.ifEmpty { lines.firstOrNull().orEmpty() }
    val rest = (if (subj.isEmpty()) lines.drop(1) else lines).joinToString(" ")
    val line = if (rest.isEmpty() || rest == title) title else "$title // $rest"
    return line.take(SHARE_MAX)
}

private const val SHARE_MAX = 500
