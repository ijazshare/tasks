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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Every screen. The first three are bottom-bar roots; the rest are drilled into from Menu or a banner. */
enum class Route(val root: Boolean = false) {
    Today(root = true),
    Upcoming(root = true),
    Search(root = true),
    Menu,
    Missed,
    Completed,
    Setup,
    Places,
    Console,
}

/**
 * The back stack: a plain list, root first. Choosing a bottom tab replaces the whole stack (roots never stack);
 * a drill-in pushes; system back pops. One list replaces five booleans, so a new screen touches one place.
 */
class NavStack(val routes: List<Route> = listOf(Route.Today)) {
    val top get() = routes.last()
    val canPop get() = routes.size > 1

    /** Open [route]: a root replaces the stack, anything else goes on top (never twice in a row). */
    fun go(route: Route) = when {
        route.root -> NavStack(listOf(route))
        route == top -> this
        else -> NavStack(routes + route)
    }

    fun pop() = if (canPop) NavStack(routes.dropLast(1)) else this

    /** Missed opened from a notification or the banner while elsewhere: Today, then Missed on top. */
    fun missed() = NavStack(listOf(Route.Today, Route.Missed))

    /** Saved as "Today,Menu,Setup": a string survives the bundle without a custom Saver. */
    fun encode() = routes.joinToString(",") { it.name }

    companion object {
        fun decode(s: String) = NavStack(
            s.split(",").mapNotNull { n -> Route.entries.find { it.name == n } }.ifEmpty { listOf(Route.Today) },
        )
    }
}

@Composable
fun rememberNavStack(): Pair<NavStack, (NavStack) -> Unit> {
    var saved by rememberSaveable { mutableStateOf(NavStack().encode()) }
    return NavStack.decode(saved) to { saved = it.encode() }
}
