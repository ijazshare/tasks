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

import androidx.compose.runtime.Composable
import io.github.ijazshare.tasks.parse.TaskStore

// The contract console exists only in debug builds (src/debug); this stub keeps release builds compiling.
const val HAS_CONSOLE = false

@Composable
@Suppress("UNUSED_PARAMETER")
fun ConsoleScreen(store: TaskStore) = Unit
