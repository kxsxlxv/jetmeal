package com.kxsxlxv.jetmeal.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.kxsxlxv.jetmeal.R
import com.kxsxlxv.jetmeal.ui.curvedBadgeGeometry
import com.kxsxlxv.jetmeal.ui.number
import com.kxsxlxv.jetmeal.ui.signed
import com.kxsxlxv.jetmeal.ui.theme.DarkNutritionColors
import com.kxsxlxv.jetmeal.ui.theme.LightNutritionColors
import com.kxsxlxv.jetmeal.ui.theme.MealDarkColors
import com.kxsxlxv.jetmeal.ui.theme.MealLightColors
import com.kxsxlxv.jetmeal.ui.theme.NutritionColors
import com.kxsxlxv.jetmeal.ui.theme.RingColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Canvas twin of DayContent.DailyRings.
 *
 * App widgets are RemoteViews, so the normal Compose Hero cannot be hosted directly.
 * Keep every layout constant here paired with DailyRings/CalorieHealthDial/MacroHealthCard.
 */
internal object HeroWidgetRenderer {
    private const val HERO_GAP_DP = 6f
    private const val MACRO_GAP_DP = 5f
    private const val MAX_DIAL_DP = 168f
    private const val ROW_WEIGHT = 2.08f

    fun render(
        context: Context,
        state: HeroWidgetState.Ready,
        widthDp: Float,
        heightDp: Float,
    ): Bitmap {
        val metrics = context.resources.displayMetrics
        val screenPixels = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        val requestedPixels = widthDp.coerceAtLeast(1f) * heightDp.coerceAtLeast(1f)
        // RemoteViews bitmap/icon memory is capped at 1.5 screens. One screen gives us
        // comfortable headroom for host-side metadata and future widget additions.
        val memorySafeScale = sqrt(screenPixels / requestedPixels).toFloat()
        val pxPerDp = minOf(metrics.density, memorySafeScale).coerceAtLeast(1f)
        val widthPx = (widthDp * pxPerDp).roundToInt().coerceAtLeast(1)
        val heightPx = (heightDp * pxPerDp).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val scheme = if (dark) MealDarkColors else MealLightColors
        val nutrition = if (dark) DarkNutritionColors else LightNutritionColors
        val fontScale = context.resources.configuration.fontScale

        val slotWidthDp = ((widthDp - HERO_GAP_DP).coerceAtLeast(0f) / ROW_WEIGHT)
        val rightWidthDp = (widthDp - HERO_GAP_DP - slotWidthDp).coerceAtLeast(0f)
        val dialDp = minOf(slotWidthDp, MAX_DIAL_DP, heightDp).coerceAtLeast(1f)
        val rowHeightDp = dialDp
        val topDp = ((heightDp - rowHeightDp) / 2f).coerceAtLeast(0f)
        val leftSlot = RectF(
            0f,
            dp(topDp, pxPerDp),
            dp(slotWidthDp, pxPerDp),
            dp(topDp + rowHeightDp, pxPerDp),
        )
        val dialSizePx = dp(dialDp, pxPerDp)
        val dialBounds = RectF(
            leftSlot.centerX() - dialSizePx / 2f,
            leftSlot.centerY() - dialSizePx / 2f,
            leftSlot.centerX() + dialSizePx / 2f,
            leftSlot.centerY() + dialSizePx / 2f,
        )
        drawCalorieDial(
            canvas = canvas,
            bounds = dialBounds,
            actual = state.total.calories,
            target = state.calorieTarget,
            colors = nutrition.calories,
            track = scheme.surfaceContainerHighest,
            innerSurface = scheme.surfaceContainerLow,
            onSurface = scheme.onSurface,
            onSurfaceVariant = scheme.onSurfaceVariant,
            error = scheme.error,
            pxPerDp = pxPerDp,
            fontScale = fontScale,
        )

        val rightLeftDp = slotWidthDp + HERO_GAP_DP
        val cardHeightDp = ((rowHeightDp - MACRO_GAP_DP * 2f) / 3f).coerceAtLeast(1f)
        drawMacroCard(
            context, canvas,
            RectF(
                dp(rightLeftDp, pxPerDp),
                dp(topDp, pxPerDp),
                dp(rightLeftDp + rightWidthDp, pxPerDp),
                dp(topDp + cardHeightDp, pxPerDp),
            ),
            "Белки", state.total.protein, state.targets.protein,
            nutrition.protein, R.drawable.symbol_egg_alt,
            scheme.surfaceContainerLow, scheme.surface, scheme.onSurface,
            pxPerDp, fontScale,
        )
        drawMacroCard(
            context, canvas,
            RectF(
                dp(rightLeftDp, pxPerDp),
                dp(topDp + cardHeightDp + MACRO_GAP_DP, pxPerDp),
                dp(rightLeftDp + rightWidthDp, pxPerDp),
                dp(topDp + cardHeightDp * 2f + MACRO_GAP_DP, pxPerDp),
            ),
            "Жиры", state.total.fat, state.targets.fat,
            nutrition.fat, R.drawable.symbol_water_drop,
            scheme.surfaceContainerLow, scheme.surface, scheme.onSurface,
            pxPerDp, fontScale,
        )
        drawMacroCard(
            context, canvas,
            RectF(
                dp(rightLeftDp, pxPerDp),
                dp(topDp + (cardHeightDp + MACRO_GAP_DP) * 2f, pxPerDp),
                dp(rightLeftDp + rightWidthDp, pxPerDp),
                dp(topDp + cardHeightDp * 3f + MACRO_GAP_DP * 2f, pxPerDp),
            ),
            "Углеводы", state.total.carbs, state.targets.carbs,
            nutrition.carbs, R.drawable.symbol_bakery_dining,
            scheme.surfaceContainerLow, scheme.surface, scheme.onSurface,
            pxPerDp, fontScale,
        )
        return bitmap
    }

    fun renderRing(
        context: Context,
        state: HeroWidgetState.Ready,
        widthDp: Float,
        heightDp: Float,
    ): Bitmap {
        val metrics = context.resources.displayMetrics
        val screenPixels = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        val requestedPixels = widthDp.coerceAtLeast(1f) * heightDp.coerceAtLeast(1f)
        val memorySafeScale = sqrt(screenPixels / requestedPixels).toFloat()
        val pxPerDp = minOf(metrics.density, memorySafeScale).coerceAtLeast(1f)
        val widthPx = (widthDp * pxPerDp).roundToInt().coerceAtLeast(1)
        val heightPx = (heightDp * pxPerDp).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val scheme = if (dark) MealDarkColors else MealLightColors
        val nutrition = if (dark) DarkNutritionColors else LightNutritionColors
        val fontScale = context.resources.configuration.fontScale
        val dialDp = minOf(widthDp, heightDp).coerceAtLeast(1f)
        val dialSizePx = dp(dialDp, pxPerDp)
        val bounds = RectF(
            (widthPx - dialSizePx) / 2f,
            (heightPx - dialSizePx) / 2f,
            (widthPx + dialSizePx) / 2f,
            (heightPx + dialSizePx) / 2f,
        )

        drawCalorieDial(
            canvas = canvas,
            bounds = bounds,
            actual = state.total.calories,
            target = state.calorieTarget,
            colors = nutrition.calories,
            track = scheme.surfaceContainerHighest,
            innerSurface = scheme.surfaceContainerLow,
            onSurface = scheme.onSurface,
            onSurfaceVariant = scheme.onSurfaceVariant,
            error = scheme.error,
            pxPerDp = pxPerDp,
            fontScale = fontScale,
        )
        return bitmap
    }

    private fun drawCalorieDial(
        canvas: Canvas,
        bounds: RectF,
        actual: Double,
        target: Double,
        colors: RingColors,
        track: Color,
        innerSurface: Color,
        onSurface: Color,
        onSurfaceVariant: Color,
        error: Color,
        pxPerDp: Float,
        fontScale: Float,
    ) {
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val stroke = dp(23f, pxPerDp)
        val radius = bounds.width().coerceAtMost(bounds.height()) / 2f - stroke / 2f - dp(3f, pxPerDp)
        val ringBounds = RectF(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius,
        )
        val fraction = when {
            target > 0.0 -> (actual / target).toFloat()
            actual > 0.0 -> 2f
            else -> 0f
        }.coerceIn(0f, 2f)
        val firstLap = fraction.coerceAtMost(1f)
        val overflowLap = (fraction - 1f).coerceIn(0f, 1f)

        val innerRadius = (radius - stroke / 2f).coerceAtLeast(0f)
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = innerSurface.toArgb()
        }
        canvas.drawCircle(centerX, centerY, innerRadius, innerPaint)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = track.toArgb()
        }
        canvas.drawCircle(centerX, centerY, radius, trackPaint)

        val baseGradient = SweepGradient(
            centerX,
            centerY,
            intArrayOf(colors.start.toArgb(), colors.end.toArgb(), colors.start.toArgb()),
            floatArrayOf(0f, .5f, 1f),
        )
        val overflowGradient = SweepGradient(
            centerX,
            centerY,
            intArrayOf(
                colors.start.toArgb(),
                0xFFFFC34D.toInt(),
                error.toArgb(),
                error.toArgb(),
                0xFFFFC34D.toInt(),
                colors.start.toArgb(),
            ),
            floatArrayOf(0f, .04f, .10f, .90f, .96f, 1f),
        )
        val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.BUTT
            shader = baseGradient
        }
        val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = baseGradient
        }

        canvas.save()
        canvas.rotate(-90f, centerX, centerY)
        if (firstLap > 0f) {
            progressPaint.shader = baseGradient
            canvas.drawArc(
                ringBounds,
                0f,
                (360f * firstLap).coerceAtMost(359.999f),
                false,
                progressPaint,
            )
            val capOverlapFraction =
                (stroke / radius / (2f * Math.PI.toFloat())).coerceIn(0f, .25f)
            if (overflowLap <= 0f && firstLap < 1f - capOverlapFraction) {
                capPaint.shader = baseGradient
                canvas.drawCircle(centerX + radius, centerY, stroke / 2f, capPaint)
            }
        }
        if (overflowLap > 0f) {
            progressPaint.shader = overflowGradient
            canvas.drawArc(
                ringBounds,
                0f,
                (360f * overflowLap).coerceAtMost(359.999f),
                false,
                progressPaint,
            )
        }

        val movingCapLap = if (overflowLap > 0f) overflowLap else firstLap
        if (movingCapLap > .001f) {
            val angle = Math.toRadians((360f * movingCapLap).toDouble())
            val activeX = centerX + Math.cos(angle).toFloat() * radius
            val activeY = centerY + Math.sin(angle).toFloat() * radius
            capPaint.shader = if (overflowLap > 0f) overflowGradient else baseGradient
            canvas.drawCircle(activeX, activeY, stroke / 2f, capPaint)
        }
        canvas.restore()

        val activeLap = if (overflowLap > 0f) overflowLap else firstLap
        if (activeLap > .001f) {
            val endAngle = -90f + 360f * activeLap
            val deltaText = signed(actual - target)
            val badgeHeight = minOf(dp(14f, pxPerDp), stroke - dp(4f, pxPerDp))
            val horizontalPadding = dp(4f, pxPerDp)
            val frontInset = (stroke - badgeHeight) / 2f
            val badgeTextPaint = textPaint(
                color = Color(0xFF102018).toArgb(),
                sizePx = sp(9f, pxPerDp, fontScale),
                weight = 500,
            )
            val badgeTextWidth = badgeTextPaint.measureText(deltaText)
            val geometry = curvedBadgeGeometry(
                endAngle = endAngle,
                radius = radius,
                badgeHeight = badgeHeight,
                parentCapRadius = stroke / 2f,
                textWidth = badgeTextWidth,
                horizontalPadding = horizontalPadding,
                frontInset = frontInset,
            )
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.Black.copy(alpha = .10f).toArgb()
                style = Paint.Style.STROKE
                strokeWidth = badgeHeight
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawArc(
                ringBounds,
                geometry.badgeStartAngle,
                geometry.badgeSweepAngle,
                false,
                fillPaint,
            )

            val textPath = Path().apply {
                addArc(ringBounds, geometry.textStartAngle, geometry.textSweepAngle)
            }
            val metrics = badgeTextPaint.fontMetrics
            val verticalOffset = -(metrics.ascent + metrics.descent) / 2f
            canvas.drawTextOnPath(
                deltaText,
                textPath,
                0f,
                verticalOffset,
                badgeTextPaint,
            )
        }

        val percent = if (target > 0.0) number(actual / target * 100.0) + "%" else "—"
        val percentPaint = textPaint(
            color = onSurface.toArgb(),
            sizePx = sp(36f, pxPerDp, fontScale),
            weight = 700,
            align = Paint.Align.CENTER,
        )
        drawTextInLineBox(
            canvas,
            percent,
            centerX,
            centerY - sp(22f, pxPerDp, fontScale),
            sp(44f, pxPerDp, fontScale),
            percentPaint,
        )

        val textRadius = radius - stroke / 2f - dp(8f, pxPerDp)
        val textBounds = RectF(
            centerX - textRadius,
            centerY - textRadius,
            centerX + textRadius,
            centerY + textRadius,
        )
        val bottomText = "${number(actual)} / ${number(target)} ккал"
        val bottomPaint = textPaint(
            color = onSurfaceVariant.toArgb(),
            sizePx = sp(10.5f, pxPerDp, fontScale),
            weight = 500,
        )
        val bottomSweep = -150f
        val path = Path().apply { addArc(textBounds, 165f, bottomSweep) }
        val pathLength = (textRadius * Math.PI * abs(bottomSweep) / 180.0).toFloat()
        val originalSize = bottomPaint.textSize
        val measured = bottomPaint.measureText(bottomText)
        if (measured > pathLength * .94f && measured > 0f) {
            bottomPaint.textSize = originalSize * (pathLength * .94f / measured)
        }
        val fitted = bottomPaint.measureText(bottomText)
        canvas.drawTextOnPath(
            bottomText,
            path,
            ((pathLength - fitted) / 2f).coerceAtLeast(0f),
            0f,
            bottomPaint,
        )
    }

    private fun drawMacroCard(
        context: Context,
        canvas: Canvas,
        bounds: RectF,
        label: String,
        actual: Double,
        target: Double,
        colors: RingColors,
        iconRes: Int,
        baseSurface: Color,
        chipSurface: Color,
        onSurface: Color,
        pxPerDp: Float,
        fontScale: Float,
    ) {
        val radius = dp(20f, pxPerDp)
        val cardPath = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(cardPath)
        canvas.drawColor(baseSurface.toArgb())

        val progress = when {
            target > 0.0 -> (actual / target).toFloat()
            actual > 0.0 -> 1f
            else -> 0f
        }.coerceIn(0f, 1f)
        if (progress > 0f) {
            val gradient = LinearGradient(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.top,
                intArrayOf(colors.container.toArgb(), colors.start.toArgb(), colors.end.toArgb()),
                floatArrayOf(0f, .42f, 1f),
                Shader.TileMode.CLAMP,
            )
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
            canvas.save()
            canvas.clipRect(bounds.left, bounds.top, bounds.left + bounds.width() * progress, bounds.bottom)
            canvas.drawRect(bounds, fillPaint)
            canvas.restore()
        }
        canvas.restore()

        val horizontalPadding = dp(7f, pxPerDp)
        val chipSize = dp(35f, pxPerDp)
        val chipRadius = dp(13f, pxPerDp)
        val chipLeft = bounds.left + horizontalPadding
        val chipTop = bounds.centerY() - chipSize / 2f
        val chipBounds = RectF(chipLeft, chipTop, chipLeft + chipSize, chipTop + chipSize)
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = chipSurface.copy(alpha = .72f).toArgb()
        }
        canvas.drawRoundRect(chipBounds, chipRadius, chipRadius, chipPaint)

        val iconSize = dp(23f, pxPerDp)
        context.getDrawable(iconRes)?.mutate()?.apply {
            setTint(onSurface.toArgb())
            val left = (chipBounds.centerX() - iconSize / 2f).roundToInt()
            val top = (chipBounds.centerY() - iconSize / 2f).roundToInt()
            setBounds(left, top, (left + iconSize).roundToInt(), (top + iconSize).roundToInt())
            draw(canvas)
        }

        val textLeft = chipBounds.right + dp(7f, pxPerDp)
        val totalLineHeight = sp(36f, pxPerDp, fontScale)
        val firstTop = bounds.centerY() - totalLineHeight / 2f
        val labelPaint = textPaint(
            color = onSurface.toArgb(),
            sizePx = sp(11f, pxPerDp, fontScale),
            weight = 500,
        )
        drawTextInLineBox(
            canvas,
            label,
            textLeft,
            firstTop,
            sp(16f, pxPerDp, fontScale),
            labelPaint,
        )

        val value = "${number(actual, 1)} / ${number(target, 1)} г"
        val valuePaint = textPaint(
            color = onSurface.toArgb(),
            sizePx = sp(14f, pxPerDp, fontScale),
            weight = 600,
        )
        val available = (bounds.right - horizontalPadding - textLeft).coerceAtLeast(1f)
        val measured = valuePaint.measureText(value)
        if (measured > available) valuePaint.textSize *= available / measured
        drawTextInLineBox(
            canvas,
            value,
            textLeft,
            firstTop + sp(16f, pxPerDp, fontScale),
            sp(20f, pxPerDp, fontScale),
            valuePaint,
        )
    }

    private fun textPaint(
        color: Int,
        sizePx: Float,
        weight: Int,
        align: Paint.Align = Paint.Align.LEFT,
    ) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.color = color
        textSize = sizePx
        textAlign = align
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false)
    }

    private fun drawTextInLineBox(
        canvas: Canvas,
        text: String,
        x: Float,
        boxTop: Float,
        boxHeight: Float,
        paint: Paint,
    ) {
        val metrics = paint.fontMetrics
        val glyphHeight = metrics.descent - metrics.ascent
        val baseline = boxTop + (boxHeight - glyphHeight) / 2f - metrics.ascent
        canvas.drawText(text, x, baseline, paint)
    }

    private fun dp(value: Float, pxPerDp: Float): Float = value * pxPerDp
    private fun sp(value: Float, pxPerDp: Float, fontScale: Float): Float =
        value * pxPerDp * fontScale
}
