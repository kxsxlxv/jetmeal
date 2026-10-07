package com.kxsxlxv.jetmeal.ui

import kotlin.math.PI
import org.junit.Assert.assertTrue
import org.junit.Test

class RingBadgeGeometryTest {
    @Test
    fun visibleBadgeFrontStaysBehindRingEndpoint() {
        val radius = 72f
        val badgeHeight = 14f
        val frontInset = 2f
        val endAngle = 32f
        val geometry = curvedBadgeGeometry(
            endAngle = endAngle,
            radius = radius,
            badgeHeight = badgeHeight,
            textWidth = 28f,
            horizontalPadding = 4f,
            frontInset = frontInset,
        )

        val centerlineEnd = geometry.badgeStartAngle + geometry.badgeSweepAngle
        val roundCapExtension =
            (badgeHeight / 2f / radius * 180f / PI.toFloat())
        val visibleFront = centerlineEnd + roundCapExtension

        assertTrue(visibleFront < endAngle)
    }

    @Test
    fun textReversesOnLowerHalfWhereClockwiseTangentIsUpsideDown() {
        val lowerRight = curvedBadgeGeometry(
            endAngle = 32f,
            radius = 72f,
            badgeHeight = 14f,
            textWidth = 28f,
            horizontalPadding = 4f,
            frontInset = 2f,
        )
        val upperRight = curvedBadgeGeometry(
            endAngle = -65f,
            radius = 72f,
            badgeHeight = 14f,
            textWidth = 28f,
            horizontalPadding = 4f,
            frontInset = 2f,
        )

        assertTrue(lowerRight.textSweepAngle < 0f)
        assertTrue(upperRight.textSweepAngle > 0f)
    }

    @Test
    fun longerDeltaGetsLongerCurvedCapsule() {
        val short = curvedBadgeGeometry(
            endAngle = 45f,
            radius = 72f,
            badgeHeight = 14f,
            textWidth = 14f,
            horizontalPadding = 4f,
            frontInset = 2f,
        )
        val long = curvedBadgeGeometry(
            endAngle = 45f,
            radius = 72f,
            badgeHeight = 14f,
            textWidth = 42f,
            horizontalPadding = 4f,
            frontInset = 2f,
        )

        assertTrue(long.badgeSweepAngle > short.badgeSweepAngle)
    }
}
