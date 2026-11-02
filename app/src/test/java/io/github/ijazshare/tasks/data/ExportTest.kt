/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 Hasan Ismail
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExportTest {
    @Test
    fun emptyDatabaseExportsEveryKeptTableAsEmpty() {
        val json = Export.toJson(
            1,
            mapOf(
                "project" to emptyList(),
                "task" to emptyList(),
                "utterance_log" to emptyList(),
            ),
        )

        assertThat(json).contains("\"schemaVersion\": 1")
        assertThat(json).contains("\"includeVoiceHistory\": false")
        assertThat(json).contains("\"project\": []")
        assertThat(json).contains("\"task\": []")
        assertThat(json).doesNotContain("utterance_log")
    }

    @Test
    fun voiceHistoryIsLeftOutUnlessAsked() {
        val tables = mapOf(
            "task" to
                listOf(mapOf("id" to "t_0000000001", "title" to "Call Amira Khan", "sourceUtterance" to "call Amira")),
        )

        assertThat(Export.toJson(1, tables)).doesNotContain("sourceUtterance")
        assertThat(Export.toJson(1, tables, includeVoiceHistory = true)).contains("\"sourceUtterance\": \"call Amira\"")
    }
}
