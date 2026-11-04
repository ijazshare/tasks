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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import io.github.ijazshare.tasks.data.Repository
import io.github.ijazshare.tasks.ui.theme.HaloShape
import io.github.ijazshare.tasks.ui.theme.TasksColors
import kotlinx.coroutines.launch

fun hasFineLocation(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun hasBackgroundLocation(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Where the phone is now: a fresh fix if a provider gives one, else the newest last-known one, else null. */
@Suppress("MissingPermission") // callers check hasFineLocation()
private fun whereAmI(context: Context, onResult: (Pair<Double, Double>?) -> Unit) {
    val lm = context.getSystemService<LocationManager>() ?: return onResult(null)
    val last = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    val provider = listOf(
        LocationManager.FUSED_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    )
        .firstOrNull { lm.isProviderEnabled(it) }
    if (provider == null) return onResult(last?.let { it.latitude to it.longitude })
    lm.getCurrentLocation(provider, null, context.mainExecutor) {
        (it ?: last)?.let { l -> onResult(l.latitude to l.longitude) } ?: onResult(null)
    }
}

/** Saved places for location reminders: "Save current location as…" from where the owner stands, and delete. */
@Composable
fun PlacesScreen(repo: Repository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val places by repo.places().collectAsState(emptyList())
    var name by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    fun save() {
        status = "Finding where you are…"
        whereAmI(context) { here ->
            scope.launch {
                status = when {
                    here == null -> "No location yet. Check that location is on and try again."
                    repo.addPlace(name, here.first, here.second) -> "Saved ${name.trim()}.".also { name = "" }
                    else -> "A place called ${name.trim()} already exists. Delete it first."
                }
            }
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasFineLocation(context)) save() else status = "Location is needed to save a place. See Setup."
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            name,
            { name = it },
            Modifier.fillMaxWidth(),
            label = { Text("Name, like home or work") },
            singleLine = true,
        )
        Button(
            onClick = {
                if (hasFineLocation(context)) {
                    save()
                } else {
                    ask.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                }
            },
            Modifier.padding(vertical = 8.dp),
            enabled = name.isNotBlank(),
            shape = HaloShape.chip,
        ) { Text("Save current location as this name") }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TasksColors.Dim) }
        if (places.isEmpty()) {
            Text(
                "No places yet. Stand where you want the reminder to fire, name it, and save.",
                Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            items(places, key = { it.id }) { p ->
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { scope.launch { repo.deletePlace(p.id) } }) {
                            Text("Delete", color = TasksColors.Relevant)
                        }
                    }
                    HorizontalDivider(color = TasksColors.Rule)
                }
            }
        }
    }
}
