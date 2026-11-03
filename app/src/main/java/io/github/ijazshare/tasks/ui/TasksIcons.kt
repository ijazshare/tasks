/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The few icons the app needs, as 2 dp round-capped outlines on a 24 grid: thin hairline strokes suit the
 * Halo look, and hand-drawing seven shapes is far smaller than pulling in material-icons-extended.
 * Tinted by the caller through `Icon`, so the colour here is only a placeholder.
 */
object TasksIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    ).build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        val d = r * 2
        arcToRelative(r, r, 0f, true, true, d, 0f)
        arcToRelative(r, r, 0f, true, true, -d, 0f)
    }

    val Today = icon("Today") {
        circle(12f, 12f, 4f)
        for ((x, y) in listOf(12f to 3f, 12f to 19f, 3f to 12f, 19f to 12f)) {
            moveTo(x, y)
            lineToRelative(if (x == 12f) 0f else 2f, if (x == 12f) 2f else 0f)
        }
        moveTo(5.6f, 5.6f)
        lineToRelative(1.4f, 1.4f)
        moveTo(17f, 17f)
        lineToRelative(1.4f, 1.4f)
        moveTo(5.6f, 18.4f)
        lineToRelative(1.4f, -1.4f)
        moveTo(17f, 7f)
        lineToRelative(1.4f, -1.4f)
    }

    val Calendar = icon("Calendar") {
        moveTo(5f, 5f)
        horizontalLineTo(19f)
        verticalLineTo(20f)
        horizontalLineTo(5f)
        close()
        moveTo(5f, 10f)
        horizontalLineTo(19f)
        moveTo(8f, 3f)
        verticalLineTo(7f)
        moveTo(16f, 3f)
        verticalLineTo(7f)
    }

    val Search = icon("Search") {
        circle(10.5f, 10.5f, 6.5f)
        moveTo(15.5f, 15.5f)
        lineTo(20f, 20f)
    }

    val Menu = icon("Menu") {
        moveTo(4f, 7f)
        horizontalLineTo(20f)
        moveTo(4f, 12f)
        horizontalLineTo(20f)
        moveTo(4f, 17f)
        horizontalLineTo(20f)
    }

    val Filter = icon("Filter") {
        moveTo(4f, 5f)
        horizontalLineTo(20f)
        lineTo(14f, 12f)
        verticalLineTo(19f)
        lineTo(10f, 21f)
        verticalLineTo(12f)
        close()
    }

    val Add = icon("Add") {
        moveTo(12f, 5f)
        verticalLineTo(19f)
        moveTo(5f, 12f)
        horizontalLineTo(19f)
    }

    val Back = icon("Back") {
        moveTo(19f, 12f)
        horizontalLineTo(5f)
        moveTo(11f, 6f)
        lineTo(5f, 12f)
        lineTo(11f, 18f)
    }

    val Chevron = icon("Chevron") {
        moveTo(9f, 6f)
        lineTo(15f, 12f)
        lineTo(9f, 18f)
    }

    val Check = icon("Check") {
        moveTo(5f, 12.5f)
        lineTo(9.5f, 17f)
        lineTo(19f, 7f)
    }

    val Close = icon("Close") {
        moveTo(6f, 6f)
        lineTo(18f, 18f)
        moveTo(18f, 6f)
        lineTo(6f, 18f)
    }

    val Warning = icon("Warning") {
        moveTo(12f, 4f)
        lineTo(21f, 20f)
        horizontalLineTo(3f)
        close()
        moveTo(12f, 10f)
        verticalLineTo(14f)
        moveTo(12f, 17f)
        lineToRelative(0.01f, 0f)
    }

    val Export = icon("Export") {
        moveTo(12f, 16f)
        verticalLineTo(4f)
        moveTo(7f, 9f)
        lineTo(12f, 4f)
        lineTo(17f, 9f)
        moveTo(5f, 20f)
        horizontalLineTo(19f)
    }

    val ExpandDown = icon("ExpandDown") {
        moveTo(6f, 9f)
        lineTo(12f, 15f)
        lineTo(18f, 9f)
    }
}
