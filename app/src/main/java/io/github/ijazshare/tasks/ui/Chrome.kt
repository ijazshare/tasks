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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ijazshare.tasks.ui.theme.HaloShape
import io.github.ijazshare.tasks.ui.theme.TasksColors
import io.github.ijazshare.tasks.ui.theme.rememberReduceMotion

/**
 * Press and keyboard focus highlight from the classic menus: a lifted fill and a 3 dp cyan bar on the left
 * edge. Nothing scales, so the layout never shifts. 120 ms linear, or instant when animations are off.
 */
@Composable
fun Modifier.haloClickable(onClickLabel: String? = null, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val reduce = rememberReduceMotion()
    val fill by animateColorAsState(
        if (pressed || focused) TasksColors.PanelActive else Color.Transparent,
        tween(if (reduce) 0 else HIGHLIGHT_MS, easing = LinearEasing),
        label = "row-fill",
    )
    return drawBehind {
        drawRect(fill)
        if (pressed || focused) drawRect(TasksColors.Admin, size = Size(BAR_DP.dp.toPx(), size.height))
    }.clickable(source, indication = null, onClickLabel = onClickLabel, onClick = onClick)
}

private const val HIGHLIGHT_MS = 120
private const val BAR_DP = 3

/** The one top bar. A back arrow shows only when [onBack] is given, so a screen can never have two. */
@Composable
fun ScreenBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, Modifier.padding(start = 4.dp)) { Icon(TasksIcons.Back, "Back") }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        actions()
        Spacer(Modifier.width(4.dp))
    }
}

/** A top-bar icon button; [dot] marks something that needs attention (setup incomplete). */
@Composable
fun BarIcon(icon: ImageVector, description: String, dot: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick) {
        Box {
            Icon(icon, description)
            if (dot) {
                Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(TasksColors.Relevant, CircleShape))
            }
        }
    }
}

private fun tabIcon(route: Route) = when (route) {
    Route.Today -> TasksIcons.Today
    Route.Upcoming -> TasksIcons.Calendar
    else -> TasksIcons.Search
}

/** The three roots, each with an icon, so "where am I" is always marked. */
@Composable
fun RootBar(selected: Route, onSelect: (Route) -> Unit) {
    NavigationBar(
        containerColor = TasksColors.Void,
        modifier = Modifier.drawBehind {
            drawRect(TasksColors.Rule, size = Size(size.width, 1.dp.toPx()))
        },
    ) {
        Route.entries.filter { it.root }.forEach { r ->
            NavigationBarItem(
                selected = r == selected,
                onClick = { onSelect(r) },
                icon = { Icon(tabIcon(r), null) },
                label = { Text(r.name, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = TasksColors.Admin,
                    selectedTextColor = TasksColors.Admin,
                    indicatorColor = TasksColors.PanelActive,
                    unselectedIconColor = TasksColors.Dim,
                    unselectedTextColor = TasksColors.Dim,
                ),
            )
        }
    }
}

/** The only add control, bottom-right on every list screen. */
@Composable
fun HaloFab(onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        shape = HaloShape.fab,
        containerColor = TasksColors.Admin,
        contentColor = TasksColors.Void,
        icon = { Icon(TasksIcons.Add, null) },
        text = { Text("Add task", style = MaterialTheme.typography.labelLarge) },
    )
}

/** Shown on Today only while something slipped: orange edge, whole banner is the button. */
@Composable
fun MissedBanner(count: Int, onClick: () -> Unit) {
    val text = "$count missed. Review"
    Surface(
        shape = HaloShape.panel,
        color = TasksColors.Panel,
        border = BorderStroke(1.dp, TasksColors.Relevant),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            // Polite live region so TalkBack announces the count when it changes.
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = text
            },
    ) {
        Row(
            Modifier.haloClickable("Review missed tasks", onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(TasksIcons.Warning, null, tint = TasksColors.Relevant)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(TasksIcons.Chevron, null, tint = TasksColors.Dim)
        }
    }
}

/** Centred hairline glyph, one title, one muted line saying what to do next. Never an error colour. */
@Composable
fun EmptyState(title: String, line: String, icon: ImageVector = TasksIcons.Check) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(48.dp), tint = TasksColors.Admin)
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MenuRow(
    title: String,
    value: String? = null,
    valueColor: Color = TasksColors.Dim,
    icon: ImageVector = TasksIcons.Chevron,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().haloClickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor)
            Icon(icon, null, tint = TasksColors.Dim)
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = TasksColors.Rule)
    }
}

/** Rare things live here, not in the header. Export is a row that acts; the rest drill in. */
@Composable
fun MenuScreen(
    missed: Int,
    setupOk: Boolean,
    hasConsole: Boolean,
    onBack: () -> Unit,
    onOpen: (Route) -> Unit,
    onExport: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenBar("Menu", onBack = onBack)
        MenuRow("Missed", missed.takeIf { it > 0 }?.toString(), TasksColors.Relevant) { onOpen(Route.Missed) }
        MenuRow("Completed") { onOpen(Route.Completed) }
        MenuRow("Places") { onOpen(Route.Places) }
        MenuRow(
            "Setup",
            if (setupOk) "All set" else "Needs attention",
            if (setupOk) TasksColors.Asset else TasksColors.Relevant,
        ) { onOpen(Route.Setup) }
        MenuRow("Export", icon = TasksIcons.Export, onClick = onExport)
        if (hasConsole) MenuRow("Console", "Debug") { onOpen(Route.Console) }
    }
}
