/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.data

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The backup file (spec section 5): schema version plus every table, minus the logs, the
 * conversations, the referents, the undo journal and the text of what was said. Voice
 * history (`task.sourceUtterance`, `ramble.transcript`) is included only on request.
 */
object Export {
    private val leftOut = setOf("utterance_log", "request_log", "conversation", "referent", "undo_journal")
    private val voiceColumns = mapOf("task" to "sourceUtterance", "ramble" to "transcript")
    private val json = Json { prettyPrint = true }

    /** `tables` maps table name to rows, each row a column-to-value map. */
    fun toJson(
        schemaVersion: Int,
        tables: Map<String, List<Map<String, Any?>>>,
        includeVoiceHistory: Boolean = false,
    ): String {
        val exported = tables.filterKeys { it !in leftOut }.mapValues { (table, rows) ->
            JsonArray(
                rows.map { row ->
                    JsonObject(
                        row.filterKeys { includeVoiceHistory || voiceColumns[table] != it }
                            .mapValues { (_, value) -> primitive(value) },
                    )
                },
            )
        }
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "format" to JsonPrimitive("tasks-export"),
                    "schemaVersion" to JsonPrimitive(schemaVersion),
                    "includeVoiceHistory" to JsonPrimitive(includeVoiceHistory),
                    "tables" to JsonObject(exported),
                ),
            ),
        )
    }

    fun toJson(db: SupportSQLiteDatabase, includeVoiceHistory: Boolean = false): String {
        val names = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'task_fts%' AND name NOT IN ('android_metadata', 'room_master_table')",
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        val tables = names.associateWith { name ->
            db.query(SimpleSQLiteQuery("SELECT * FROM `$name`")).use { c ->
                generateSequence { if (c.moveToNext()) c else null }.map {
                    (0 until c.columnCount).associate { i -> c.getColumnName(i) to cell(c, i) }
                }.toList()
            }
        }
        return toJson(db.version, tables, includeVoiceHistory)
    }

    private fun cell(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        else -> c.getString(i)
    }

    private fun primitive(value: Any?) = when (value) {
        null -> JsonNull
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
