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
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.ijazshare.tasks.R

/**
 * Saira (SIL OFL 1.1, licence in assets/licenses) for the Halo-flavoured display text only: titles, section
 * headers, big numerals, buttons and tab labels. It is one variable font; a weight is a variation setting.
 * Saira has the "tnum" feature (checked in its GSUB table), so numerals in display text stay tabular too.
 * Anything read in a long list stays on the device sans: squared faces tire the eye over many rows.
 */
@OptIn(ExperimentalTextApi::class)
private fun saira(weight: FontWeight) = FontFamily(
    Font(R.font.saira, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))),
)

private val SairaSemiBold = saira(FontWeight.SemiBold)
private val SairaMedium = saira(FontWeight.Medium)

/**
 * Every style asks for tabular figures ("tnum") so times and counts do not jitter as they change. The scale
 * follows the design brief (8.2): one big title, one row title, quiet meta.
 */
private fun style(
    size: Int,
    line: Int,
    weight: FontWeight,
    tracking: TextUnit = 0.sp,
    family: FontFamily = FontFamily.Default,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking,
    fontFeatureSettings = "tnum",
)

/** Big readout and empty-state title. */
val TasksDisplay = style(34, 40, FontWeight.SemiBold, family = SairaSemiBold)

/** Screen title. */
val TasksTitle = style(24, 30, FontWeight.SemiBold, family = SairaSemiBold)

/** List section headers: shown in caps by the caller. */
val TasksSection = style(12, 16, FontWeight.Medium, 0.8.sp, SairaMedium)

/** Task title, menu row: the thing that is tapped. */
val TasksRowTitle = style(17, 22, FontWeight.Medium)

val TasksBody = style(15, 21, FontWeight.Normal)

/** Times, counts, muted detail. The smallest text in the app. */
val TasksMeta = style(13, 18, FontWeight.Normal)

val TasksLabel = style(12, 16, FontWeight.Medium, 0.4.sp, SairaMedium)

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
