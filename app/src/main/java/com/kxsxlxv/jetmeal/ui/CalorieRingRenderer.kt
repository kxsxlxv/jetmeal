package com.kxsxlxv.jetmeal.ui

import android.graphics.Canvas
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Shared by the hardware Compose canvas and the software App Widget bitmap canvas. */
internal class CalorieRingRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Path()
    private val cap = Path()
    private val outer = RectF()
    private val inner = RectF()
    private val gradientRotation = Matrix()

    fun draw(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        stroke: Float,
        progress: Float,
        track: Int,
        startColor: Int,
        endColor: Int,
        warningColor: Int,
        errorColor: Int,
    ) {
        if (radius <= stroke / 2f || stroke <= 0f) return
        val fraction = progress.coerceIn(0f, 2f)
        val halfStroke = stroke / 2f
        outer.set(centerX - radius - halfStroke, centerY - radius - halfStroke,
            centerX + radius + halfStroke, centerY + radius + halfStroke)
        inner.set(centerX - radius + halfStroke, centerY - radius + halfStroke,
            centerX + radius - halfStroke, centerY + radius - halfStroke)

        paint.shader = null
        paint.color = track
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.style = Paint.Style.FILL
        // A transparent track must not modulate the alpha of the progress shader.
        paint.color = Color.WHITE
        gradientRotation.setRotate(-90f, centerX, centerY)

        fun drawLap(lap: Float, overflow: Boolean) {
            if (lap <= 0f) return
            val stops = if (overflow) listOf(
                0f to startColor, .04f to warningColor, .10f to errorColor,
                .90f to errorColor, .96f to warningColor, 1f to startColor,
            ) else listOf(0f to startColor, .5f to endColor, 1f to startColor)
            val sweep = lap * 360f
            outline.rewind()
            if (lap == 1f) {
                // A completed lap is a closed annulus. It has neither a cap nor a seam.
                outline.addOval(outer, Path.Direction.CW)
                outline.addOval(inner, Path.Direction.CCW)
                paint.shader = sweepShader(centerX, centerY, stops)
            } else {
                outline.arcTo(outer, -90f, sweep, true)
                outline.arcTo(inner, -90f + sweep, -sweep, false)
                outline.close()
                if (!overflow) addCap(centerX, centerY - radius, halfStroke)
                val angle = Math.toRadians(sweep.toDouble())
                val tangentX = cos(angle).toFloat()
                val tangentY = sin(angle).toFloat()
                val endX = centerX + tangentY * radius
                val endY = centerY - tangentX * radius
                addCap(endX, endY, halfStroke)

                val endpoint = colorAt(stops, lap)
                // Outside the bounded arc, use the nearest endpoint. The cut between
                // those two constant colors lies opposite the middle of the arc.
                val cut = (lap + 1f) / 2f
                val boundedStops = stops.filter { it.first < lap } + listOf(
                    lap to endpoint, cut to endpoint, cut to startColor, 1f to startColor,
                )
                val boundedGradient = sweepShader(centerX, centerY, boundedStops)
                // The forward half of the moving cap belongs to the endpoint even when
                // it crosses 12 o'clock and overlaps the beginning of a nearly full lap.
                val capDisk = RadialGradient(endX, endY, halfStroke,
                    intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                    floatArrayOf(0f, 1f, 1f), Shader.TileMode.CLAMP)
                val capFront = LinearGradient(
                    endX - tangentX * halfStroke, endY - tangentY * halfStroke,
                    endX + tangentX * halfStroke, endY + tangentY * halfStroke,
                    intArrayOf(Color.TRANSPARENT, endpoint), floatArrayOf(.5f, .5f),
                    Shader.TileMode.CLAMP,
                )
                val endpointMask = ComposeShader(capFront, capDisk, BlendMode.DST_IN)
                paint.shader = ComposeShader(boundedGradient, endpointMask, BlendMode.SRC_OVER)
            }
            // Fill the union once: no separately antialiased arc/circle boundaries.
            canvas.drawPath(outline, paint)
        }

        drawLap(fraction.coerceAtMost(1f), overflow = false)
        drawLap((fraction - 1f).coerceIn(0f, 1f), overflow = true)
        paint.shader = null
    }

    private fun sweepShader(x: Float, y: Float, stops: List<Pair<Float, Int>>) =
        SweepGradient(x, y, stops.map { it.second }.toIntArray(),
            stops.map { it.first }.toFloatArray()).apply { setLocalMatrix(gradientRotation) }

    private fun addCap(x: Float, y: Float, radius: Float) {
        cap.rewind()
        cap.addCircle(x, y, radius, Path.Direction.CW)
        outline.op(cap, Path.Op.UNION)
    }

    private fun colorAt(stops: List<Pair<Float, Int>>, position: Float): Int {
        val end = stops.indexOfFirst { it.first >= position }.coerceAtLeast(1)
        val (left, a) = stops[end - 1]
        val (right, b) = stops[end]
        val t = ((position - left) / (right - left)).coerceIn(0f, 1f)
        fun lerp(start: Int, finish: Int) = (start + (finish - start) * t).roundToInt()
        return Color.argb(lerp(Color.alpha(a), Color.alpha(b)), lerp(Color.red(a), Color.red(b)),
            lerp(Color.green(a), Color.green(b)), lerp(Color.blue(a), Color.blue(b)))
    }
}
