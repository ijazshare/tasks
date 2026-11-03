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
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app has one look, dark, and does not follow the system theme or dynamic
 * colour. That is a deliberate departure from Material You: the palette encodes
 * meaning — red is live capture, amber is the system talking about itself, green is
 * a completed action — and letting the wallpaper recolour it would destroy that.
 * A surveillance readout that changes colour with the user's wallpaper is also just
 * the wrong idea.
 */
private val TasksScheme = darkColorScheme(
    primary = TasksColors.Admin,
    onPrimary = TasksColors.Void,
    secondary = TasksColors.Irrelevant,
    onSecondary = TasksColors.Void,
    tertiary = TasksColors.Asset,
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
    onError = TasksColors.Void,
    // Not Panel: contentColorFor(Panel) would otherwise resolve to orange and tint every panel's text.
    errorContainer = Color(0xFF2A1710),
    onErrorContainer = TasksColors.Relevant,
)

@Composable
fun TasksTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TasksScheme,
        typography = TasksTypography,
        // A root Surface sets the default text colour, so a screen outside a Scaffold is never black on navy.
        content = { Surface(color = TasksColors.Void, contentColor = TasksColors.Bone, content = content) },
    )
}
