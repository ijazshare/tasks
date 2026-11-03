package io.github.ijazshare.tasks.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavStackTest {
    @Test
    fun drillInAndBackPopOneLevel() {
        val s = NavStack().go(Route.Menu).go(Route.Setup)
        assertThat(s.routes).containsExactly(Route.Today, Route.Menu, Route.Setup).inOrder()
        assertThat(s.pop().top).isEqualTo(Route.Menu)
        assertThat(s.pop().pop().pop().top).isEqualTo(Route.Today)
    }

    @Test
    fun tabsReplaceTheStackAndNeverStack() {
        val s = NavStack().go(Route.Menu).go(Route.Upcoming)
        assertThat(s.routes).containsExactly(Route.Upcoming)
        assertThat(s.canPop).isFalse()
    }

    @Test
    fun missedFromNotificationSitsOnToday() {
        assertThat(NavStack().go(Route.Menu).missed().routes).containsExactly(Route.Today, Route.Missed).inOrder()
    }

    @Test
    fun sameScreenIsNotPushedTwice() {
        assertThat(NavStack().go(Route.Menu).go(Route.Menu).routes).hasSize(2)
    }

    @Test
    fun encodeRoundTripsAndSurvivesGarbage() {
        val s = NavStack().go(Route.Menu).go(Route.Completed)
        assertThat(NavStack.decode(s.encode()).routes).isEqualTo(s.routes)
        assertThat(NavStack.decode("nope").routes).containsExactly(Route.Today)
    }
}
