package com.yvora.app

enum class PinchAction { NONE, PENDING, TAP, START_DRAG, MOVE_DRAG, DROP, CANCEL }

internal class PinchDragEngine(
    private val holdMs: Long = 350L,
    private val closeThreshold: Float = 0.06f,
    private val openThreshold: Float = 0.075f
) {
    private var startedAt = -1L
    private var lastAt = -1L
    private var dragging = false
    private var blockedUntilOpen = true
    val ownsPinch: Boolean get() = startedAt >= 0

    fun reset() {
        startedAt = -1L
        lastAt = -1L
        dragging = false
        blockedUntilOpen = true
    }

    fun update(distance: Float, now: Long, canStart: Boolean, supportsDrag: Boolean): PinchAction {
        if (!distance.isFinite() || (ownsPinch && (now < lastAt || now - lastAt > 650L))) {
            reset()
            return PinchAction.CANCEL
        }
        val closed = distance < (if (ownsPinch) openThreshold else closeThreshold)
        if (!closed) {
            val result = when {
                dragging -> PinchAction.DROP
                ownsPinch && (now - startedAt < holdMs || !supportsDrag) -> PinchAction.TAP
                ownsPinch -> PinchAction.CANCEL
                else -> PinchAction.NONE
            }
            startedAt = -1L
            dragging = false
            blockedUntilOpen = false
            return result
        }
        if (!ownsPinch) {
            if (blockedUntilOpen || !canStart) {
                blockedUntilOpen = true
                return PinchAction.NONE
            }
            startedAt = now
            lastAt = now
            return PinchAction.PENDING
        }
        lastAt = now
        if (dragging) return PinchAction.MOVE_DRAG
        if (supportsDrag && now - startedAt >= holdMs) {
            dragging = true
            return PinchAction.START_DRAG
        }
        return PinchAction.PENDING
    }
}
