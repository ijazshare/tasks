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

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrations 1 to 2, 2 to 3 and 3 to 4 on throwaway database files ("migration-test..."), never `tasks.db`: a v1
 * database with a task, a tag and a reminder keeps every row and gains the two new columns.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TasksDb::class.java)

    @Test
    fun migrate1To2KeepsRowsAndAddsTheNewColumns() {
        helper.createDatabase(NAME, 1).apply {
            execSQL(
                "INSERT INTO task (id, title, description, projectId, sortOrder, priority, dueDate, dueTime, " +
                    "createdAt, updatedAt, source) VALUES ('t_1', 'Pay rent', '', 'p_inbox00000', 0, 4, " +
                    "'2026-11-10', '15:00', 1, 1, 'typed')",
            )
            execSQL(
                "INSERT INTO label (id, name, nameKey, sortOrder, createdAt, updatedAt) " +
                    "VALUES ('l_x', 'x', 'x', 0, 1, 1)",
            )
            execSQL("INSERT INTO task_label (taskId, labelId) VALUES ('t_1', 'l_x')")
            execSQL(
                "INSERT INTO reminder (id, taskId, kind, nextFireAt, unseen, createdAt, updatedAt) " +
                    "VALUES ('r_1', 't_1', 'AT_DUE', 5, 0, 1, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(NAME, 2, true, MIGRATION_1_2)

        fun one(sql: String): String? = db.query(sql).use {
            it.moveToFirst()
            if (it.isNull(0)) null else it.getString(0)
        }
        assertThat(one("SELECT title || '|' || dueTime FROM task WHERE id = 't_1'")).isEqualTo("Pay rent|15:00")
        assertThat(one("SELECT missedAt FROM task WHERE id = 't_1'")).isNull()
        assertThat(one("SELECT name FROM label WHERE id = 'l_x'")).isEqualTo("x")
        assertThat(one("SELECT scheduleJson FROM label WHERE id = 'l_x'")).isNull()
        assertThat(one("SELECT labelId FROM task_label WHERE taskId = 't_1'")).isEqualTo("l_x")
        assertThat(one("SELECT kind || nextFireAt FROM reminder WHERE id = 'r_1'")).isEqualTo("AT_DUE5")
    }

    @Test
    fun migrate2To3KeepsReferentsAndAddsListedJson() {
        helper.createDatabase(NAME_V3, 2).apply {
            execSQL("INSERT INTO referent (client, taskId, expiresAt) VALUES ('c.example', 't_1', 99)")
            execSQL(
                "INSERT INTO task (id, title, description, projectId, sortOrder, priority, createdAt, updatedAt, " +
                    "source) VALUES ('t_1', 'Pay rent', '', 'p_inbox00000', 0, 4, 1, 1, 'typed')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(NAME_V3, 3, true, MIGRATION_2_3)

        fun one(sql: String): String? = db.query(sql).use {
            it.moveToFirst()
            if (it.isNull(0)) null else it.getString(0)
        }
        assertThat(
            one("SELECT taskId || '|' || expiresAt FROM referent WHERE client = 'c.example'"),
        ).isEqualTo("t_1|99")
        assertThat(one("SELECT listedJson FROM referent WHERE client = 'c.example'")).isNull()
        assertThat(one("SELECT title FROM task WHERE id = 't_1'")).isEqualTo("Pay rent")
        db.execSQL("UPDATE referent SET listedJson = '[\"t_1\"]' WHERE client = 'c.example'")
        assertThat(one("SELECT listedJson FROM referent")).isEqualTo("[\"t_1\"]")
    }

    @Test
    fun migrate3To4KeepsRemindersAndAddsPlaces() {
        helper.createDatabase(NAME_V4, 3).apply {
            execSQL(
                "INSERT INTO task (id, title, description, projectId, sortOrder, priority, createdAt, updatedAt, " +
                    "source) VALUES ('t_1', 'Buy milk', '', 'p_inbox00000', 0, 4, 1, 1, 'typed')",
            )
            execSQL(
                "INSERT INTO reminder (id, taskId, kind, nextFireAt, unseen, createdAt, updatedAt) " +
                    "VALUES ('r_1', 't_1', 'AT_DUE', 5, 0, 1, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(NAME_V4, 4, true, MIGRATION_3_4)

        fun one(sql: String): String? = db.query(sql).use {
            it.moveToFirst()
            if (it.isNull(0)) null else it.getString(0)
        }
        assertThat(one("SELECT kind || nextFireAt FROM reminder WHERE id = 'r_1'")).isEqualTo("AT_DUE5")
        assertThat(one("SELECT placeId FROM reminder WHERE id = 'r_1'")).isNull()
        assertThat(one("SELECT onLeave FROM reminder WHERE id = 'r_1'")).isEqualTo("0")
        db.execSQL(
            "INSERT INTO place (id, name, nameKey, lat, lon, radiusM, createdAt) " +
                "VALUES ('pl_1', 'Shop', 'shop', 51.5, -0.12, 150, 1)",
        )
        db.execSQL(
            "INSERT INTO reminder (id, taskId, kind, placeId, onLeave, unseen, createdAt, updatedAt) " +
                "VALUES ('r_2', 't_1', 'AT_PLACE', 'pl_1', 1, 0, 1, 1)",
        )
        assertThat(one("SELECT name || radiusM FROM place WHERE id = 'pl_1'")).isEqualTo("Shop150")
        assertThat(one("SELECT placeId || onLeave FROM reminder WHERE id = 'r_2'")).isEqualTo("pl_11")
    }

    private companion object {
        const val NAME_V4 = "migration-test-v4"

        const val NAME_V3 = "migration-test-v3"

        const val NAME = "migration-test"
    }
}
