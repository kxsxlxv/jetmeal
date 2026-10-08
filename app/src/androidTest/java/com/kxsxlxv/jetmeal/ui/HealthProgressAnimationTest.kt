package com.kxsxlxv.jetmeal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

/** Tests the real Compose animation/draw phases, with deterministic frame time. */
class HealthProgressAnimationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dayEntranceHasOneUniformClockAndDoesNotRecomposeOrResizeAtItsTail() {
        compose.mainClock.autoAdvance = false
        val target = HealthProgressFrame(1.07f, .91f, .18f, .74f)
        val samples = mutableListOf<HealthProgressFrame>()
        var compositions = 0
        var sizes = 0
        compose.setContent {
            val frame = animatedHealthProgressFrame(LocalDate.of(2020, 10, 6), target)
            SideEffect { compositions++ }
            Canvas(Modifier.size(160.dp).onSizeChanged { sizes++ }) {
                samples += frame.value
            }
        }
        compose.waitForIdle()
        val initialCompositions = compositions
        val initialSizes = sizes
        repeat(47) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.runOnIdle {
            assertEquals("Animation must invalidate drawing, not the Hero composition", initialCompositions, compositions)
            assertEquals("Hero bounds must remain stable through the final frames", initialSizes, sizes)
            assertEquals(target, samples.last())
            assertTrue(samples.size > 30)
            samples.zipWithNext().forEach { (before, after) ->
                assertTrue(after.calories >= before.calories)
                assertTrue(after.calories - before.calories <= target.calories * .03f)
            }
            samples.filter { it.calories > 0f }.forEach {
                val fraction = it.calories / target.calories
                assertEquals(fraction, it.protein / target.protein, .00001f)
                assertEquals(fraction, it.fat / target.fat, .00001f)
                assertEquals(fraction, it.carbs / target.carbs, .00001f)
            }
        }
    }

    @Test fun sameDayRetargetStartsAtDisplayedFrameAndNewDayStartsAtZero() {
        compose.mainClock.autoAdvance = false
        var day by mutableStateOf(LocalDate.of(2020, 10, 6))
        var target by mutableStateOf(HealthProgressFrame(.34f, .9f, .15f, .7f))
        val samples = mutableListOf<HealthProgressFrame>()
        compose.setContent {
            val frame = animatedHealthProgressFrame(day, target)
            Canvas(Modifier.size(160.dp)) { samples += frame.value }
        }
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
        val displayed = samples.last()
        compose.runOnUiThread { target = HealthProgressFrame(.95f, .2f, .8f, .4f) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(abs(samples.last().calories - displayed.calories) < .03f)
        }
        compose.mainClock.advanceTimeBy(750)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(target, samples.last()) }
        compose.runOnUiThread { day = day.minusDays(1) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(samples.last().calories < .04f) }
        compose.mainClock.advanceTimeBy(750)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(target, samples.last()) }
    }
}
