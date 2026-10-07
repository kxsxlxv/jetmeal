package com.kxsxlxv.jetmeal.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.kxsxlxv.jetmeal.ui.number
import com.kxsxlxv.jetmeal.ui.theme.DarkNutritionColors
import com.kxsxlxv.jetmeal.ui.theme.LightNutritionColors
import com.kxsxlxv.jetmeal.ui.theme.MealDarkColors
import com.kxsxlxv.jetmeal.ui.theme.MealLightColors
import kotlin.math.abs

internal object HeroRingRenderer {
    private const val SIZE = 320

    fun render(context: Context, state: HeroWidgetState.Ready): Bitmap {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val scheme = if (dark) MealDarkColors else MealLightColors
        val colors = if (dark) DarkNutritionColors.calories else LightNutritionColors.calories

        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = SIZE / 2f
        val stroke = 42f
        val radius = center - stroke / 2f - 8f
        val bounds = RectF(center - radius, center - radius, center + radius, center + radius)
        val fraction = when {
            state.calorieTarget > 0.0 -> (state.total.calories / state.calorieTarget).toFloat()
            state.total.calories > 0.0 -> 2f
            else -> 0f
        }.coerceIn(0f, 2f)
        val firstLap = fraction.coerceAtMost(1f)
        val overflowLap = (fraction - 1f).coerceIn(0f, 1f)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = scheme.surfaceContainerHighest.toArgb()
        }
        canvas.drawCircle(center, center, radius, trackPaint)

        val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            shader = SweepGradient(
                center,
                center,
                intArrayOf(colors.start.toArgb(), colors.end.toArgb(), colors.start.toArgb()),
                floatArrayOf(0f, .5f, 1f),
            )
        }

        canvas.save()
        canvas.rotate(-90f, center, center)
        if (firstLap > 0f) {
            if (firstLap >= .9995f) {
                progressPaint.strokeCap = Paint.Cap.BUTT
                canvas.drawCircle(center, center, radius, progressPaint)
            } else {
                progressPaint.strokeCap = Paint.Cap.ROUND
                canvas.drawArc(bounds, 0f, 360f * firstLap, false, progressPaint)
            }
        }

        if (overflowLap > 0f) {
            progressPaint.shader = SweepGradient(
                center,
                center,
                intArrayOf(
                    colors.start.toArgb(),
                    0xFFFFC34D.toInt(),
                    scheme.error.toArgb(),
                    scheme.error.toArgb(),
                    0xFFFFC34D.toInt(),
                    colors.start.toArgb(),
                ),
                floatArrayOf(0f, .04f, .10f, .90f, .96f, 1f),
            )
            if (overflowLap >= .9995f) {
                progressPaint.strokeCap = Paint.Cap.BUTT
                canvas.drawCircle(center, center, radius, progressPaint)
            } else {
                progressPaint.strokeCap = Paint.Cap.ROUND
                canvas.drawArc(bounds, 0f, 360f * overflowLap, false, progressPaint)
            }
        }
        canvas.restore()

        val percent = if (state.calorieTarget > 0.0) {
            number(state.total.calories / state.calorieTarget * 100.0) + "%"
        } else "—"
        val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = scheme.onSurface.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = 68f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val percentY = center - (percentPaint.ascent() + percentPaint.descent()) / 2f - 5f
        canvas.drawText(percent, center, percentY, percentPaint)

        val bottomText = "${number(state.total.calories)} / ${number(state.calorieTarget)} ккал"
        val bottomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = scheme.onSurfaceVariant.toArgb()
            textSize = 23f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val textRadius = radius - stroke / 2f - 14f
        val textBounds = RectF(
            center - textRadius,
            center - textRadius,
            center + textRadius,
            center + textRadius,
        )
        val sweep = -150f
        val path = Path().apply { addArc(textBounds, 165f, sweep) }
        val pathLength = (textRadius * Math.PI * abs(sweep) / 180.0).toFloat()
        val width = bottomPaint.measureText(bottomText)
        if (width > pathLength * .94f && width > 0f) {
            bottomPaint.textSize *= pathLength * .94f / width
        }
        val fitted = bottomPaint.measureText(bottomText)
        canvas.drawTextOnPath(
            bottomText,
            path,
            ((pathLength - fitted) / 2f).coerceAtLeast(0f),
            0f,
            bottomPaint,
        )

        return bitmap
    }
}
