package com.kxsxlxv.jetmeal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.domain.Targets
import com.kxsxlxv.jetmeal.widget.HeroWidgetRenderer
import com.kxsxlxv.jetmeal.widget.HeroWidgetState
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native Skia pixel tests; no screenshot golden, network, or production fixture data. */
@RunWith(AndroidJUnit4::class)
class CalorieRingRendererTest {
    private val start = 0xFF1EAE50.toInt()
    private val end = 0xFF4FF08A.toInt()
    private val warning = 0xFFFFC34D.toInt()
    private val error = 0xFFFFB4AA.toInt()
    private val output by lazy {
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "hero-ring-verification").apply { mkdirs() }
    }

    @Test fun oldSweepGradientReproducesWrongBackOfLapInsideOnePercentCap() {
        val bitmap = Bitmap.createBitmap(504, 504, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = SweepGradient(252f, 252f,
                intArrayOf(start, warning, error, error, warning, start),
                floatArrayOf(0f, .04f, .10f, .90f, .96f, 1f))
        }
        canvas.rotate(-90f, 252f, 252f)
        val angle = Math.toRadians(3.6)
        canvas.drawCircle(252f + cos(angle).toFloat() * 204f,
            252f + sin(angle).toFloat() * 204f, 34.5f, paint)
        // This is behind 12 o'clock in a 1% lap. It must not sample the 99% yellow tail.
        assertTrue(channelDistance(start, bitmap.getPixel(240, 48)) > 35)
        save(bitmap, "legacy-101-cap-only")
    }

    @Test fun onePercentCapClampsBothEndsInsteadOfWrappingTheGradient() {
        val bitmap = render(1.01f)
        assertTrue("The back of the cap must stay at the start of the overflow gradient",
            channelDistance(start, bitmap.getPixel(240, 48)) <= 3)
        val endpointColor = Color.rgb(
            (Color.red(start) * .75f + Color.red(warning) * .25f).toInt(),
            (Color.green(start) * .75f + Color.green(warning) * .25f).toInt(),
            (Color.blue(start) * .75f + Color.blue(warning) * .25f).toInt(),
        )
        assertTrue("The forward cap must use 1% rather than sample beyond the actual endpoint",
            channelDistance(endpointColor, bitmap.getPixel(285, 50)) <= 4)
    }

    @Test fun completeAndOverflowLapsStayWithinExactlyOneAnnulus() {
        listOf(1f, 1.0001f, 1.01f, 1.05f, 1.07f, 1.99f, 2f).forEach { progress ->
            val bitmap = render(progress)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val distance = kotlin.math.hypot(x + .5f - 252f, y + .5f - 252f)
                if (distance < 168f || distance > 240f) {
                    assertEquals("$progress at ($x,$y) escapes the annulus", 0,
                        Color.alpha(bitmap.getPixel(x, y)))
                } else if (distance in 171f..237f) {
                    assertEquals("$progress at ($x,$y) leaves a hole", 255,
                        Color.alpha(bitmap.getPixel(x, y)))
                }
            }
        }
    }

    @Test fun crossingOneHundredPercentHasNoDiscretePixelChangeAtTheSeam() {
        val before = render(.99999f)
        val after = render(1.00001f)
        save(before, "boundary-before-100")
        save(after, "boundary-after-100")
        var difference = 0L
        var samples = 0
        for (y in 10..90) for (x in 205..300) {
            val a = before.getPixel(x, y)
            val b = after.getPixel(x, y)
            val radius = kotlin.math.hypot(x + .5f - 252f, y + .5f - 252f)
            if (radius in 171f..237f) {
                // Standard ComposeShader introduces up to four 8-bit rounding levels.
                // Check filled pixels separately from CPU path antialiasing coverage.
                assertTrue("Interior seam at ($x,$y)",
                    abs(Color.red(a) - Color.red(b)) <= 4 &&
                    abs(Color.green(a) - Color.green(b)) <= 4 &&
                    abs(Color.blue(a) - Color.blue(b)) <= 4)
            }
            difference += channelDistance(a, b) + abs(Color.alpha(a) - Color.alpha(b))
            samples++
        }
        assertTrue("Crossing 100% changes $difference / $samples channel values",
            difference.toDouble() / samples < 2.0)
    }

    @Test fun curvedDeltaPillStaysInsideProgressIncludingThreePercentAndLongDelta() {
        listOf(.03f to "-1 940", .03f to "-2 137", .34f to "-1 320",
            .95f to "-100", .99f to "-20", 1f to "0", 1.01f to "+20",
            1.07f to "+141", .2876667f to "-2 137").forEach { (progress, text) ->
            val parent = Bitmap.createBitmap(504, 504, Bitmap.Config.ARGB_8888)
            CalorieRingRenderer().draw(Canvas(parent), 252f, 252f, 204f, 69f,
                progress, Color.TRANSPARENT, start, end, warning, error)
            val pill = Bitmap.createBitmap(504, 504, Bitmap.Config.ARGB_8888)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 27f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val limit = curvedBadgeTextWidthLimit(progress, 204f, 42f, 12f)
            val measured = textPaint.measureText(text)
            if (measured > limit) textPaint.textSize *= limit / measured
            val active = if (progress > 1f) progress - 1f else progress
            val geometry = curvedBadgeGeometry(-90f + 360f * active, 204f, 42f, 34.5f,
                textPaint.measureText(text), 12f, 13.5f)
            Canvas(pill).drawArc(RectF(48f, 48f, 456f, 456f),
                geometry.badgeStartAngle, geometry.badgeSweepAngle, false,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = 42f
                })
            for (y in 0 until 504) for (x in 0 until 504) {
                if (Color.alpha(pill.getPixel(x, y)) > 32) {
                    assertTrue("$progress / $text pill escapes progress at ($x,$y)",
                        Color.alpha(parent.getPixel(x, y)) > 0)
                    val distance = kotlin.math.hypot(x + .5f - 252f, y + .5f - 252f)
                    assertTrue("Pill loses radial inset", distance in 181f..227f)
                }
            }
        }
    }

    @Test fun captureRequiredProgressAndDeltaScenariosInBothWidgetVariants() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scenarios = listOf(
            "003" to (60.0 to 2000.0), "034" to (680.0 to 2000.0),
            "095" to (1900.0 to 2000.0), "099" to (1980.0 to 2000.0),
            "100" to (2000.0 to 2000.0), "101" to (1966.0 to 1946.0),
            "105" to (2100.0 to 2000.0), "107-positive-141" to (2141.0 to 2000.0),
            "long-negative-2137" to (863.0 to 3000.0),
        )
        scenarios.forEach { (name, values) ->
            val (actual, target) = values
            val state = HeroWidgetState.Ready(LocalDate.of(2026, 10, 8),
                Nutrition(actual, 37.0, 49.0, 218.0), target,
                Targets(target, 100.0, 60.0, 200.0), 0)
            save(render((actual / target).toFloat()), "ring-only-$name")
            save(HeroWidgetRenderer.renderRing(context, state, 168f, 168f), "widget-ring-$name")
            save(HeroWidgetRenderer.render(context, state, 355f, 168f), "widget-hero-$name")
        }
        assertEquals(27, output.listFiles()?.count {
            it.name.startsWith("ring-only-") || it.name.startsWith("widget-")
        })
    }

    @Test fun ringOnlyKeepsCircularBoundsInRectangularLauncherCells() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = HeroWidgetState.Ready(LocalDate.of(2026, 10, 8),
            Nutrition(1966.0, 37.0, 49.0, 218.0), 1946.0,
            Targets(1946.0, 100.0, 60.0, 200.0), 0)
        listOf(168f to 110f, 110f to 168f, 168f to 168f).forEach { (width, height) ->
            val bitmap = HeroWidgetRenderer.renderRing(context, state, width, height)
            var left = bitmap.width
            var top = bitmap.height
            var right = -1
            var bottom = -1
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 128) {
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            assertTrue("Ring must remain circular in $width × $height", abs((right - left) - (bottom - top)) <= 1)
            assertTrue("Ring must remain horizontally centered", abs(left - (bitmap.width - 1 - right)) <= 1)
            assertTrue("Ring must remain vertically centered", abs(top - (bitmap.height - 1 - bottom)) <= 1)
        }
    }

    private fun render(progress: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(504, 504, Bitmap.Config.ARGB_8888)
        CalorieRingRenderer().draw(Canvas(bitmap), 252f, 252f, 204f, 69f, progress,
            0xFF323C33.toInt(), start, end, warning, error)
        return bitmap
    }

    private fun channelDistance(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) +
            abs(Color.blue(a) - Color.blue(b))

    private fun save(bitmap: Bitmap, name: String) {
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
