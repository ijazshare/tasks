/*
 * Tasks — an offline task app for Android.
 * Copyright (C) 2026 ijazshare
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for the full text.
 */
package io.github.ijazshare.tasks.parse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The place clause only matches a literal saved name; every place here is made up. */
class PlaceClauseTest {
    private val saved = listOf("Home", "Work", "Shop", "Dentist")

    private fun read(text: String) = PlaceClause.read(text, saved)

    @Test
    fun arrivingAtASavedPlace() {
        for (said in listOf(
            "get home",
            "get to the shop",
            "arrive at the shop",
            "get back to the shop",
            "get to shop",
        )) {
            val c = read("remind me to buy milk when I $said")
            assertThat(c?.title).isEqualTo("buy milk")
            assertThat(c?.onLeave).isFalse()
        }
        assertThat(read("remind me to buy milk when I get to the shop")?.target).isEqualTo(PlaceTarget("Shop", false))
        assertThat(read("remind me to x when I get back home")?.target).isEqualTo(PlaceTarget("Home", false))
    }

    @Test
    fun leavingComesFirstOrLast() {
        val last = read("remind me to lock up when I leave work")
        val first = read("When I leave work, remind me to lock up.")
        assertThat(last?.title).isEqualTo("lock up")
        assertThat(first?.title).isEqualTo("lock up")
        assertThat(first?.target).isEqualTo(PlaceTarget("Work", true))
        assertThat(first?.whenSay()).isEqualTo("when you leave work")
    }

    @Test
    fun nameMatchIgnoresCaseAndArticle() {
        assertThat(read("remind me to a when I get to THE SHOP")?.target?.name).isEqualTo("Shop")
        assertThat(read("remind me to a when I get to my work")?.target?.name).isEqualTo("Work")
    }

    @Test
    fun anUnsavedPlaceIsKnownAsUnsavedNotGuessed() {
        val c = read("remind me to buy milk when I get to the library")
        assertThat(c?.target).isNull()
        assertThat(c?.said).isEqualTo("the library")
    }

    @Test
    fun otherSentencesAreLeftToTheParser() {
        assertThat(read("remind me to call Dad at 5")).isNull()
        assertThat(read("remind me to call Dad when I get a chance")).isNull()
        assertThat(read("remind me to tidy when I leave it")).isNull()
        assertThat(read("buy milk when I get home")).isNull()
    }
}
