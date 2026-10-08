package com.kxsxlxv.jetmeal.ui

import android.graphics.Bitmap
import android.content.res.Configuration
import android.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import com.kxsxlxv.jetmeal.domain.DiaryEntry
import com.kxsxlxv.jetmeal.domain.MealPeriod
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.domain.Targets
import com.kxsxlxv.jetmeal.ui.theme.JetmealTheme
import com.kxsxlxv.jetmeal.widget.HeroWidgetRenderer
import com.kxsxlxv.jetmeal.widget.HeroWidgetState
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the same renderer on the hardware Compose canvas, including the curved pill. */
class CalorieRingComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureRequiredProgressDeltasAndBothColorSchemes() {
        val values = mutableStateOf(60.0 to 2000.0)
        val dark = mutableStateOf(false)
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "hero-ring-verification").apply { mkdirs() }
        compose.setContent {
            val (actual, target) = values.value
            val total = Nutrition(actual, 37.0, 49.0, 218.0)
            val entry = DiaryEntry("ring-fixture", "Fixture", null, 100.0, "g", 100.0, total, total,
                Instant.parse("2026-10-08T12:00:00Z"), MealPeriod.Day, null, null, null)
            JetmealTheme(darkTheme = dark.value) {
                Surface {
                    DayContent(LocalDate.of(2026, 10, 8), listOf(entry),
                        Targets(target, 100.0, 60.0, 200.0), null, {}, {}, {})
                }
            }
        }
        val scenarios = listOf(
            "003" to (60.0 to 2000.0), "034" to (680.0 to 2000.0),
            "095" to (1900.0 to 2000.0), "099" to (1980.0 to 2000.0),
            "100" to (2000.0 to 2000.0), "101" to (1966.0 to 1946.0),
            "105" to (2100.0 to 2000.0), "107-positive-141" to (2141.0 to 2000.0),
            "long-negative-2137" to (863.0 to 3000.0),
        )
        listOf(false, true).forEach { night ->
            scenarios.forEach { (name, scenario) ->
                compose.runOnIdle { dark.value = night; values.value = scenario }
                compose.waitForIdle()
                val bitmap = compose.onNodeWithContentDescription("Калории:", substring = true)
                    .captureToImage().asAndroidBitmap()
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                File(output, "compose-${if (night) "dark" else "light"}-$name.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val configuration = Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val widgetContext = context.createConfigurationContext(configuration)
                val (actual, target) = scenario
                val state = HeroWidgetState.Ready(LocalDate.of(2026, 10, 8),
                    Nutrition(actual, 37.0, 49.0, 218.0), target,
                    Targets(target, 100.0, 60.0, 200.0), 0)
                val widget = HeroWidgetRenderer.renderRing(widgetContext, state, 168f, 168f)
                val scale = bitmap.width / 168f
                val stroke = 23f * scale
                val radius = bitmap.width / 2f - stroke / 2f - 3f * scale
                val fraction = (actual / target).toFloat().coerceIn(0f, 2f)
                val activeLap = if (fraction > 1f) fraction - 1f else fraction
                val endpointAngle = -Math.PI / 2.0 + activeLap * 2.0 * Math.PI
                val endX = bitmap.width / 2f + cos(endpointAngle) * radius
                val endY = bitmap.height / 2f + sin(endpointAngle) * radius
                // Sample either side of the pill band, avoiding all text. The same native
                // shader and silhouette must agree on GPU and software Canvas in both themes.
                for (degrees in 0 until 360 step 3) for (side in listOf(-1f, 1f)) {
                    val angle = Math.toRadians(degrees.toDouble())
                    val sampleRadius = radius + side * stroke * .4f
                    val x = (bitmap.width / 2f + cos(angle) * sampleRadius).toInt()
                    val y = (bitmap.height / 2f + sin(angle) * sampleRadius).toInt()
                    if (activeLap != 1f) {
                        // CPU and GPU use different edge coverage rasterizers. Compare
                        // actual fill pixels, not their one-pixel antialiasing fringe.
                        val fromEnd = kotlin.math.hypot(x + .5 - endX, y + .5 - endY)
                        val fromStart = kotlin.math.hypot(x + .5 - bitmap.width / 2f,
                            y + .5 - (bitmap.height / 2f - radius))
                        if (abs(fromEnd - stroke / 2f) < 1.5 ||
                            abs(fromStart - stroke / 2f) < 1.5) continue
                    }
                    val a = bitmap.getPixel(x, y)
                    val b = widget.getPixel(x, y)
                    val difference = abs(Color.red(a) - Color.red(b)) +
                        abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
                    assertTrue("$night/$name at $degrees/$side GPU and widget differ by $difference",
                        difference <= 9)
                }
            }
        }
    }
}
