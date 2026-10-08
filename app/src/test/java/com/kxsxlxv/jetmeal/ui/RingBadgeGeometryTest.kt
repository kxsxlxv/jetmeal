package com.kxsxlxv.jetmeal.ui

import kotlin.math.PI
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RingBadgeGeometryTest {
    @Test
    fun lowProgressTextFitKeepsCapsuleCenterlineInsideTheActualArc() {
        val radius = 69.5f
        val progress = .03f
        val height = 14f
        val padding = 4f
        val width = curvedBadgeTextWidthLimit(progress, radius, height, padding)
        val end = -90f + 360f * progress
        val geometry = curvedBadgeGeometry(end, radius, height, 11.5f, width, padding, 4.5f)
        assertEquals(-90f, geometry.badgeStartAngle, .001f)
        assertEquals(end, geometry.badgeStartAngle + geometry.badgeSweepAngle, .001f)
        assertTrue(width < 26f)
    }

    @Test
    fun completedLapDoesNotConstrainLongDeltaToTheShortOverflowSegment() {
        assertTrue(curvedBadgeTextWidthLimit(1.01f, 69.5f, 14f, 4f).isInfinite())
    }

    @Test
    fun visibleBadgeFrontReachesParentCapFrontWithInset() {
        val radius = 72f
        val badgeHeight = 14f
        val parentCapRadius = 11.5f
        val frontInset = (parentCapRadius * 2f - badgeHeight) / 2f
        val endAngle = 32f
        val geometry = curvedBadgeGeometry(
            endAngle = endAngle,
            radius = radius,
            badgeHeight = badgeHeight,
            parentCapRadius = parentCapRadius,
            textWidth = 28f,
            horizontalPadding = 4f,
            frontInset = frontInset,
        )

        val centerlineEnd = geometry.badgeStartAngle + geometry.badgeSweepAngle
        val badgeCapExtension =
            (badgeHeight / 2f / radius * 180f / PI.toFloat())
        val parentCapExtension =
            (parentCapRadius / radius * 180f / PI.toFloat())
        val insetAngle =
            (frontInset / radius * 180f / PI.toFloat())
        val visibleFront = centerlineEnd + badgeCapExtension
        val expectedFront = endAngle + parentCapExtension - insetAngle

        assertTrue(kotlin.math.abs(visibleFront - expectedFront) < 0.001f)
    }

    @Test
    fun textReversesOnLowerHalfWhereClockwiseTangentIsUpsideDown() {
        val parentCapRadius = 11.5f
        val lowerRight = curvedBadgeGeometry(
            endAngle = 32f,
            radius = 72f,
            badgeHeight = 14f,
            parentCapRadius = parentCapRadius,
            textWidth = 28f,
            horizontalPadding = 4f,
            frontInset = (parentCapRadius * 2f - 14f) / 2f,
        )
        val upperRight = curvedBadgeGeometry(
            endAngle = -65f,
            radius = 72f,
            badgeHeight = 14f,
            parentCapRadius = parentCapRadius,
            textWidth = 28f,
            horizontalPadding = 4f,
            frontInset = (parentCapRadius * 2f - 14f) / 2f,
        )

        assertTrue(lowerRight.textSweepAngle < 0f)
        assertTrue(upperRight.textSweepAngle > 0f)
    }

    @Test
    fun longerDeltaGetsLongerCurvedCapsule() {
        val parentCapRadius = 11.5f
        val short = curvedBadgeGeometry(
            endAngle = 45f,
            radius = 72f,
            badgeHeight = 14f,
            parentCapRadius = parentCapRadius,
            textWidth = 14f,
            horizontalPadding = 4f,
            frontInset = (parentCapRadius * 2f - 14f) / 2f,
        )
        val long = curvedBadgeGeometry(
            endAngle = 45f,
            radius = 72f,
            badgeHeight = 14f,
            parentCapRadius = parentCapRadius,
            textWidth = 42f,
            horizontalPadding = 4f,
            frontInset = (parentCapRadius * 2f - 14f) / 2f,
        )

        assertTrue(long.badgeSweepAngle > short.badgeSweepAngle)
    }
}
