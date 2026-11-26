package com.yvora.app

import kotlin.math.abs

/**
 * Horizontal swipe uses the raw mirrored camera centroid, independent of
 * pointer sensitivity and handedness. Exactly three extended fingers arm it.
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

    fun update(fingers: Int, x: Float, y: Float, nowMs: Long): String? {
        if (!x.isFinite() || !y.isFinite()) {
            trackingLost()
            return null
        }
        if (fingers != 3) {
            anchorTime = -1L
            lastSampleTime = -1L
            if (blocksOtherGestures) {
                releaseSamples++
                if (releaseSamples >= 2) {
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
            nowMs - lastSampleTime > 350L || nowMs - anchorTime > 850L) {
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
        val dy = y - anchorY
        val travel = abs(dx)
        val speed = travel * 1000f / elapsed
        val horizontal = travel / (travel + abs(dy) + 0.0001f)
        val confidence = minOf(
            membership(travel, 0.035f, 0.11f),
            membership(speed, 0.12f, 0.48f),
            membership(horizontal, 0.55f, 0.85f)
        )
        if (confidence < 0.65f) return null
        latched = true
        anchorTime = -1L
        return if (dx > 0f) "RIGHT" else "LEFT"
    }
}
