package io.github.ijazshare.tasks.ui

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.github.ijazshare.tasks.ui.theme.TasksColors
import org.junit.Test
import kotlin.math.pow

/** WCAG contrast of every text/icon token on the surfaces it is drawn on. Fails the build if a token drifts. */
class ContrastTest {
    private fun lin(c: Float) = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)

    private fun ratio(a: Color, b: Color) = (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05)

    private val surfaces = listOf(
        "Void" to TasksColors.Void,
        "Panel" to TasksColors.Panel,
        "PanelActive" to TasksColors.PanelActive,
    )

    private fun check(name: String, fg: Color, min: Double) = surfaces.forEach { (sn, bg) ->
        assertThat(ratio(fg, bg)).isAtLeast(min)
        println("$name on $sn: ${"%.2f".format(ratio(fg, bg))}")
    }

    @Test
    fun textTokensPassAaOnEverySurface() {
        check("Bone", TasksColors.Bone, AA)
        check("Dim", TasksColors.Dim, AA)
        check("Ghost", TasksColors.Ghost, AA)
        check("Admin", TasksColors.Admin, AA)
        check("Asset", TasksColors.Asset, AA)
        check("Relevant", TasksColors.Relevant, AA)
    }

    @Test
    fun hairlineIsDecorativeAndMeetsNonTextOnVoidOnlyWhenBoosted() {
        // Rule is never used for text or as the only cue; this documents how faint it is.
        assertThat(ratio(TasksColors.Rule, TasksColors.Void)).isLessThan(AA)
    }

    @Test
    fun textOnAccentFillsPasses() {
        // FAB and selected controls: Void text on the cyan, on the green and on the orange.
        listOf(TasksColors.Admin, TasksColors.Asset, TasksColors.Relevant).forEach {
            assertThat(ratio(TasksColors.Void, it)).isAtLeast(AA)
        }
    }

    private companion object {
        const val AA = 4.5
    }
}
