package com.yvora.app

import kotlin.math.abs

/**
 * Horizontal swipe uses the raw mirrored camera centroid, independent of
 * pointer sensitivity and handedness. An explicit pose gate arms it.
 * Fuzzy memberships combine horizontal travel, average speed and dominance.
 */
internal class HorizontalSwipeEngine {
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorTime = -1L
    private var lastSampleTime = -1L
    private var latched = false
    private var releaseSamples = 0
    var blocksOtherGestures = false
        private set

    fun reset() {
        anchorTime = -1L
        lastSampleTime = -1L
        latched = false
        releaseSamples = 0
        blocksOtherGestures = false
    }

    fun trackingLost() {
        anchorTime = -1L
        lastSampleTime = -1L
        releaseSamples = 0
        // A short tracking loss must not rearm a completed swipe.
    }

    private fun membership(value: Float, low: Float, high: Float): Float =
        ((value - low) / (high - low)).coerceIn(0f, 1f)

    // Legacy count-based entry retained only for existing motion regression tests.
    fun update(fingers: Int, x: Float, y: Float, nowMs: Long, palmWidth: Float = 0.20f, aspect: Float = 1f): String? =
        updatePose(fingers == 3, x, y, nowMs, palmWidth, aspect)

    fun updatePose(armed: Boolean, x: Float, y: Float, nowMs: Long, palmWidth: Float = 0.20f, aspect: Float = 1f): String? {
        if (!x.isFinite() || !y.isFinite()) {
            trackingLost()
            return null
        }
        if (!armed) {
            if (blocksOtherGestures) {
                releaseSamples++
                if (releaseSamples >= 2) {
                    anchorTime = -1L
                    lastSampleTime = -1L
                    latched = false
                    blocksOtherGestures = false
                }
            }
            return null
        }
        blocksOtherGestures = true
        releaseSamples = 0
        if (latched) return null

        if (anchorTime < 0 || nowMs <= lastSampleTime ||
            nowMs - lastSampleTime > 450L || nowMs - anchorTime > 1400L) {
            anchorX = x
            anchorY = y
            anchorTime = nowMs
            lastSampleTime = nowMs
            return null
        }
        lastSampleTime = nowMs
        val elapsed = nowMs - anchorTime
        if (elapsed < 55L) return null

        val dx = x - anchorX
        val dy = (y - anchorY) * aspect.coerceIn(0.4f, 2.5f)
        val travel = abs(dx)
        val scale = palmWidth.coerceIn(0.07f, 0.5f)
        val relativeTravel = travel / scale
        val speed = relativeTravel * 1000f / elapsed
        val horizontal = travel / (travel + abs(dy) + 0.0001f)
        val confidence = minOf(
            membership(relativeTravel, 0.12f, 0.37f),
            membership(speed, 0.18f, 0.85f),
            membership(horizontal, 0.52f, 0.77f)
        )
        if (travel < 0.018f || confidence < 0.55f) return null
        latched = true
        anchorTime = -1L
        return if (dx > 0f) "RIGHT" else "LEFT"
    }
}
