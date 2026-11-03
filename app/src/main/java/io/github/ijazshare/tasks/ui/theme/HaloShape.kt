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

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.unit.dp

/** The UNSC-panel chamfers, drawn in code so no asset is needed. Panels group info; rows stay flat. */
object HaloShape {
    /** Banner, empty state, composer: two opposite corners cut deep, two barely. */
    val panel = CutCornerShape(topStart = 14.dp, topEnd = 3.dp, bottomEnd = 14.dp, bottomStart = 3.dp)

    /** The add button: one diagonal cut. */
    val fab = CutCornerShape(topStart = 10.dp, topEnd = 0.dp, bottomEnd = 10.dp, bottomStart = 0.dp)

    /** Small controls such as chips. */
    val chip = CutCornerShape(topStart = 6.dp, topEnd = 2.dp, bottomEnd = 6.dp, bottomStart = 2.dp)

    /** Bottom sheets: top corners cut. */
    val sheet = CutCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 0.dp, bottomStart = 0.dp)
}
