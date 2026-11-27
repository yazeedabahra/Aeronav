package com.yvora.app

import kotlin.math.hypot

/** Single-worker, monotonic-clock pacing. No allocations in the hot path. */
internal class FramePacer(private val minimumIntervalMs: Long) {
    private var lastStart = -1L
    private var averageCostMs = 0.0
    val intervalMs: Long
        get() = maxOf(minimumIntervalMs, (averageCostMs * 1.35).toLong().coerceAtMost(300L))

    fun canStart(nowMs: Long): Boolean = lastStart < 0L || nowMs - lastStart >= intervalMs

    // Keep a processing rest period even when results are arriving quickly.
    fun canStart(nowMs: Long, requestedMs: Long): Boolean {
        val interval = maxOf(requestedMs, (averageCostMs * 2.0).toLong().coerceAtMost(320L))
        return lastStart < 0L || nowMs - lastStart >= interval
    }

    fun started(nowMs: Long) { lastStart = nowMs }

    fun finished(costMs: Long) {
        val sample = costMs.coerceAtLeast(1L).toDouble()
        averageCostMs = if (averageCostMs == 0.0) sample else 0.8 * averageCostMs + 0.2 * sample
    }
}

/** One adaptive low-pass filter; fast motion has less smoothing than slow motion. */
internal class AdaptivePointerFilter {
    var x = 0.5f
        private set
    var y = 0.5f
        private set
    private var rawX = 0.5f
    private var rawY = 0.5f
    private var previousTime = -1L
    var tauSeconds=.095f
        private set

    fun reset() { previousTime = -1L; x = 0.5f; y = 0.5f; tauSeconds=.095f }

    fun update(inputX: Float, inputY: Float, timeMs: Long) {
        if (!inputX.isFinite() || !inputY.isFinite()) return
        val nx = inputX.coerceIn(0f, 1f)
        val ny = inputY.coerceIn(0f, 1f)
        if (previousTime < 0 || timeMs - previousTime > 350L) {
            x = nx; y = ny; rawX = nx; rawY = ny; previousTime = timeMs
            return
        }
        val dt = ((timeMs - previousTime).coerceIn(1L, 250L)) / 1000f
        val speed = hypot(nx - rawX, ny - rawY) / dt
        // Linear fuzzy membership for rapid motion (0 = slow, 1 = fast).
        val tau=FuzzyControllers.cursorTau(speed)
        tauSeconds=tau
        val alpha = dt / (tau + dt)
        x += alpha * (nx - x)
        y += alpha * (ny - y)
        rawX = nx; rawY = ny; previousTime = timeMs
    }
}
