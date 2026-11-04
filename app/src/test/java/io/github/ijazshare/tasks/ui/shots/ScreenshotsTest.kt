@file:Suppress("ktlint:standard:max-line-length", "MaxLineLength")

package io.github.ijazshare.tasks.ui.shots

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ijazshare.tasks.data.Completion
import io.github.ijazshare.tasks.data.Label
import io.github.ijazshare.tasks.data.QueryEnv
import io.github.ijazshare.tasks.data.Task
import io.github.ijazshare.tasks.parse.Response
import io.github.ijazshare.tasks.ui.CompletedScreen
import io.github.ijazshare.tasks.ui.Composer
import io.github.ijazshare.tasks.ui.HaloFab
import io.github.ijazshare.tasks.ui.MenuScreen
import io.github.ijazshare.tasks.ui.RootBar
import io.github.ijazshare.tasks.ui.RootHeader
import io.github.ijazshare.tasks.ui.Route
import io.github.ijazshare.tasks.ui.ScreenBar
import io.github.ijazshare.tasks.ui.SearchScreen
import io.github.ijazshare.tasks.ui.SetupContent
import io.github.ijazshare.tasks.ui.SetupState
import io.github.ijazshare.tasks.ui.TaskEditor
import io.github.ijazshare.tasks.ui.TaskListScreen
import io.github.ijazshare.tasks.ui.TodayScreen
import io.github.ijazshare.tasks.ui.UpcomingScreen
import io.github.ijazshare.tasks.ui.theme.HaloShape
import io.github.ijazshare.tasks.ui.theme.TasksColors
import io.github.ijazshare.tasks.ui.theme.TasksTheme
import io.github.ijazshare.tasks.ui.todaySubtitle
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Draws every screen on a 1080 x 2340 dark phone with made-up data. The PNGs only appear when Gradle is run
 * with -PrecordScreens (see app/build.gradle.kts); otherwise this just proves each screen composes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-port-night-xxhdpi")
class ScreenshotsTest {
    @get:Rule(order = 1)
    val rule = createComposeRule()

    // ui-test-manifest would put this activity into the debug APK; registering it here keeps the app clean.
    // It must run before the compose rule launches the activity, hence the explicit order.
    @get:Rule(order = 0)
    val hostActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    private val today = LocalDate.of(2026, 11, 3)
    private val stamp = Instant.parse("2026-11-01T08:00:00Z")

    private fun task(id: String, title: String, day: LocalDate?, time: String? = null, repeat: String? = null) = Task(
        id = id, title = title, description = "", projectId = "inbox", sectionId = null, parentId = null, sortOrder = 0,
        priority = 4, dueDate = day, dueTime = time?.let(LocalTime::parse), dueZone = null, recurrence = null,
        recurrenceAnchor = null, recurrenceText = repeat, deadline = null, durationMin = null, completedAt = null,
        createdAt = stamp, updatedAt = stamp, deletedAt = null, source = "typed", sourceUtterance = null, missedAt = null,
    )

    private fun label(id: String, name: String) = Label(id, name, name.lowercase(), null, 0, stamp, stamp, null, null)

    private val overdue = listOf(
        task("o1", "Pay electricity bill", today.minusDays(1), "09:00"),
        task("o2", "Send school trip form", today.minusDays(2)),
    )
    private val now = listOf(
        task("t1", "Team stand-up", today, "09:30", "Every weekday"),
        task("t2", "Call dentist", today, "15:30"),
        task("t3", "Buy milk and bread", today),
    )
    private val undated = listOf(task("u1", "Fix bike brake", null), task("u2", "Plan weekend trip", null))
    private val coming = listOf(
        task("c1", "Team call", today.plusDays(1), "10:00"),
        task("c2", "Return library books", today.plusDays(1)),
        task("c3", "Renew passport", today.plusDays(2)),
        task("c4", "Water the plants", today.plusDays(4), null, "Every week"),
        task("c5", "Dentist check-up", today.plusDays(9), "11:15"),
    )
    private val labels = listOf(label("l1", "Home"), label("l2", "Errands"))
    private val none = Unit.let { { _: Task -> } }

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { TasksTheme { Box(Modifier.fillMaxSize().background(TasksColors.Void)) { content() } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("${System.getProperty("screens.dir", "build/screens")}/$name.png")
    }

    /** The real app's frame for a root screen: status-bar gap, bottom bar, and the add button where it applies. */
    @Composable
    private fun Root(route: Route, fab: Boolean = true, body: @Composable () -> Unit) {
        Scaffold(
            containerColor = TasksColors.Void,
            bottomBar = { RootBar(route) {} },
            floatingActionButton = { if (fab) HaloFab {} },
        ) { pad -> Column(Modifier.fillMaxSize().padding(pad).padding(top = 28.dp)) { body() } }
    }

    @Composable
    private fun Sub(title: String, body: @Composable () -> Unit) =
        Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
            ScreenBar(title, onBack = {})
            body()
        }

    @Composable
    private fun TodayHeader(filter: List<String> = emptyList(), setupOk: Boolean = false, subtitle: String) =
        RootHeader("Today", subtitle, true, filter, !setupOk, {}, {}, {})

    @Test
    fun today() = shot("01-today") {
        Root(Route.Today) {
            TodayHeader(subtitle = todaySubtitle(today, overdue + now, undated))
            TodayScreen(overdue + now, undated, today, emptyMap(), 0, {}, none, none)
        }
    }

    @Test
    fun todayWithMissedBanner() = shot("02-today-missed") {
        Root(Route.Today) {
            TodayHeader(subtitle = todaySubtitle(today, overdue + now, undated))
            TodayScreen(overdue + now, undated, today, emptyMap(), 3, {}, none, none)
        }
    }

    @Test
    fun todayFiltered() = shot("03-today-filtered") {
        Root(Route.Today) {
            TodayHeader(listOf("Errands"), true, todaySubtitle(today, listOf(now[2]), emptyList()))
            TodayScreen(listOf(now[2]), emptyList(), today, emptyMap(), 0, {}, none, none)
        }
    }

    @Test
    fun todayEmpty() = shot("04-today-empty") {
        Root(Route.Today) {
            TodayHeader(setupOk = true, subtitle = todaySubtitle(today, emptyList(), emptyList()))
            TodayScreen(emptyList(), emptyList(), today, emptyMap(), 0, {}, none, none)
        }
    }

    @Test
    fun upcoming() = shot("05-upcoming") {
        Root(Route.Upcoming) {
            RootHeader("Upcoming", null, true, emptyList(), false, {}, {}, {})
            UpcomingScreen(coming, today, emptyMap(), none, none)
        }
    }

    @Test
    fun search() = shot("06-search") {
        Root(Route.Search, fab = false) {
            SearchScreen(
                overdue + now + coming + undated,
                QueryEnv(today, {
                    emptySet()
                }, { "" }),
                emptyMap(),
                {},
                none,
                none,
                "call",
            )
        }
    }

    @Test
    fun menu() = shot("07-menu") {
        Box(Modifier.padding(top = 28.dp)) { MenuScreen(3, false, true, {}, {}, {}) }
    }

    @Test
    fun missed() = shot("08-missed") {
        Sub("Missed") { TaskListScreen(overdue + now.take(1), "Nothing missed.", emptyMap(), none, none) }
    }

    @Test
    fun completed() = shot("09-completed") {
        val at = Instant.parse("2026-11-03T08:12:00Z")
        Sub("Completed") {
            CompletedScreen(
                listOf("Book car service", "Email landlord", "Order printer ink", "Pay water bill", "Call Omar back")
                    .mapIndexed { i, t -> Completion("d$i", t, at.minusSeconds(i * 7_200L)) },
            )
        }
    }

    @Test
    fun setup() = shot("10-setup") {
        Sub("Setup") {
            SetupContent(SetupState(notifications = true, exactAlarms = false, batteryUnrestricted = false), {}, {}, {})
        }
    }

    /** The bottom sheets are drawn inline over a dimmed Today: a real sheet lives in its own window, which the
     *  JVM renderer cannot capture. The sheet body is the same composable the app shows. */
    @Composable
    private fun OverSheet(sheet: @Composable () -> Unit) {
        Root(Route.Today) {
            TodayHeader(subtitle = todaySubtitle(today, overdue + now, undated))
            TodayScreen(overdue + now, undated, today, emptyMap(), 0, {}, none, none)
        }
        Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(shape = HaloShape.sheet, color = TasksColors.Panel) { sheet() }
        }
    }

    @Test
    fun composer() = shot("11-composer") {
        OverSheet {
            Composer(
                handle = { Response(status = "done", speech = "") },
                projects = emptyList(),
                labels = listOf("Home", "Errands"),
                onDone = {},
                onMore = {},
                prefill = "Call Layla tomorrow 5pm %errands",
            )
        }
    }

    @Test
    fun taskSheet() = shot("12-task-sheet") {
        OverSheet {
            TaskEditor(
                task("t2", "Call dentist", today, "15:30"),
                setOf("l2"),
                labels,
                false,
                {},
                { _, _, _ -> },
                {},
                {},
            )
        }
    }
}
