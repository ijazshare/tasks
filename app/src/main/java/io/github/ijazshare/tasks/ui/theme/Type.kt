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

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The device's own sans-serif: clean, small APK, no font licence to account for. Every style asks for
 * tabular figures ("tnum") so times and counts do not jitter as they change. The scale follows the design
 * brief (8.2): one big title, one row title, quiet meta.
 */
private fun style(size: Int, line: Int, weight: FontWeight, tracking: TextUnit = 0.sp) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking,
    fontFeatureSettings = "tnum",
)

/** Big readout and empty-state title. */
val TasksDisplay = style(34, 40, FontWeight.SemiBold)

/** Screen title. */
val TasksTitle = style(24, 30, FontWeight.SemiBold)

/** List section headers: shown in caps by the caller. */
val TasksSection = style(12, 16, FontWeight.Medium, 0.8.sp)

/** Task title, menu row: the thing that is tapped. */
val TasksRowTitle = style(17, 22, FontWeight.Medium)

val TasksBody = style(15, 21, FontWeight.Normal)

/** Times, counts, muted detail. The smallest text in the app. */
val TasksMeta = style(13, 18, FontWeight.Normal)

val TasksLabel = style(12, 16, FontWeight.Medium, 0.4.sp)

val TasksTypography = Typography(
    displayLarge = TasksDisplay,
    headlineMedium = TasksDisplay.copy(fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TasksTitle,
    titleLarge = TasksTitle,
    titleMedium = TasksRowTitle,
    titleSmall = TasksSection,
    bodyLarge = TasksRowTitle,
    bodyMedium = TasksBody,
    bodySmall = TasksMeta,
    labelLarge = TasksLabel.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TasksLabel,
    labelSmall = TasksLabel.copy(fontSize = 11.sp),
)
