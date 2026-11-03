/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The app has one look, dark, and does not follow the system theme or dynamic
 * colour. That is a deliberate departure from Material You: the palette encodes
 * meaning — red is live capture, amber is the system talking about itself, green is
 * a completed action — and letting the wallpaper recolour it would destroy that.
 * A surveillance readout that changes colour with the user's wallpaper is also just
 * the wrong idea.
 */
private val TasksScheme = darkColorScheme(
    primary = TasksColors.Bone,
    onPrimary = TasksColors.Void,
    secondary = TasksColors.Irrelevant,
    onSecondary = TasksColors.Void,
    tertiary = TasksColors.Admin,
    onTertiary = TasksColors.Void,
    background = TasksColors.Void,
    onBackground = TasksColors.Bone,
    surface = TasksColors.Panel,
    onSurface = TasksColors.Bone,
    surfaceVariant = TasksColors.PanelActive,
    onSurfaceVariant = TasksColors.Dim,
    outline = TasksColors.Rule,
    outlineVariant = TasksColors.Rule,
    error = TasksColors.Relevant,
    onError = TasksColors.Bone,
    errorContainer = TasksColors.Panel,
    onErrorContainer = TasksColors.Relevant,
)

@Composable
fun TasksTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TasksScheme,
        typography = TasksTypography,
        content = content,
    )
}
