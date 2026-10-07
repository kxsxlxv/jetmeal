package com.kxsxlxv.jetmeal.ui

import kotlin.math.PI
import kotlin.math.max

internal data class CurvedBadgeGeometry(
    val badgeStartAngle: Float,
    val badgeSweepAngle: Float,
    val textStartAngle: Float,
    val textSweepAngle: Float,
)

/**
 * Geometry for a curved capsule that sits fully inside a circular ring end cap.
 *
 * Angles use Android Canvas conventions: 0° at 3 o'clock, positive clockwise.
 */
internal fun curvedBadgeGeometry(
    endAngle: Float,
    radius: Float,
    badgeHeight: Float,
    parentCapRadius: Float,
    textWidth: Float,
    horizontalPadding: Float,
    frontInset: Float,
): CurvedBadgeGeometry {
    if (radius <= 0f) {
        return CurvedBadgeGeometry(endAngle, .01f, endAngle, .01f)
    }

    fun lengthToDegrees(length: Float): Float =
        (length / radius * 180f / PI.toFloat())

    fun normalize(angle: Float): Float {
        val value = angle % 360f
        return if (value < 0f) value + 360f else value
    }

    // Let the smaller badge cap reach almost to the visible front of the parent
    // round cap. Its centerline therefore moves slightly *past* the ring path
    // endpoint; the badge's own round cap still remains inside the larger parent cap.
    val badgeCapRadius = badgeHeight / 2f
    val forwardShift =
        (parentCapRadius - badgeCapRadius - frontInset).coerceAtLeast(0f)
    val badgeCenterlineEnd =
        endAngle + lengthToDegrees(forwardShift)

    val visibleLength = max(badgeHeight, textWidth + horizontalPadding * 2f)
    val centerlineLength = max(.5f, visibleLength - badgeHeight)
    val badgeSweep = lengthToDegrees(centerlineLength).coerceAtLeast(.01f)
    val badgeStart = badgeCenterlineEnd - badgeSweep
    val badgeMid = badgeCenterlineEnd - badgeSweep / 2f

    val textSweepMagnitude = lengthToDegrees(textWidth).coerceAtLeast(.01f)

    // Clockwise text is upside-down on the lower half of the ring because its
    // tangent points from 90° through 270°. Reverse the text path there while
    // leaving the capsule itself untouched.
    val normalizedMid = normalize(badgeMid)
    val reverseText = normalizedMid in 0f..180f

    return if (reverseText) {
        CurvedBadgeGeometry(
            badgeStartAngle = badgeStart,
            badgeSweepAngle = badgeSweep,
            textStartAngle = badgeMid + textSweepMagnitude / 2f,
            textSweepAngle = -textSweepMagnitude,
        )
    } else {
        CurvedBadgeGeometry(
            badgeStartAngle = badgeStart,
            badgeSweepAngle = badgeSweep,
            textStartAngle = badgeMid - textSweepMagnitude / 2f,
            textSweepAngle = textSweepMagnitude,
        )
    }
}
