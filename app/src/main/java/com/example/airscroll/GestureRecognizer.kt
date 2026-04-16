package com.example.airscroll

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot

class GestureRecognizer {

    data class GestureState(
        val handDetected: Boolean = false,
        val pointerX: Float = 0.5f,
        val pointerY: Float = 0.5f,
        val fingerDirection: String = "UNCLEAR",
        val isHandOpen: Boolean = false,
        val isTapPinching: Boolean = false,
        val isHoldPinching: Boolean = false,
        val engineState: String = "IDLE",
        val armedFor: String? = null,
        val lastGesture: String = "None",
        val gestureCount: Int = 0,
        val triggeredGesture: String? = null,
        val triggeredTap: Boolean = false,
        val triggeredHoldStart: Boolean = false,
        val triggeredHoldMove: Boolean = false,
        val triggeredHoldEnd: Boolean = false,
        val holdX: Float = 0f,
        val holdY: Float = 0f
    )

    companion object {
        private const val TAP_PINCH_THRESHOLD  = 0.06f
        private const val TAP_COOLDOWN_MS      = 400L
        private const val HOLD_PINCH_THRESHOLD = 0.07f
        private const val HOLD_DRAG_MIN_PX     = 4f
        private const val FLICK_COOLDOWN_MS    = 1200L
        private const val POSE_HOLD_MS         = 200L
        private const val FLICK_TIMEOUT_MS     = 500L
        private const val FLICK_MIN_PIXELS     = 50f
        private const val DIR_HISTORY_SIZE     = 5
        private const val STABLE_COUNT_NEEDED  = 4
    }

    // Tap state
    private var wasTapPinching  = false
    private var lastTapTime     = 0L

    // Hold state
    // Hold state
    private var wasHoldPinching  = false
    private var isHoldActive     = false
    private var holdCurrentX     = 0f
    private var holdCurrentY     = 0f
    private var lastHoldMoveTime = 0L
    private val HOLD_MOVE_INTERVAL_MS = 100L
    // Flick state
    private var flickState              = "IDLE"
    private var armedFor: String?       = null
    private var poseStartTime           = 0L
    private var flickStartY: Float?     = null
    private var lastFlickTriggerTime    = 0L
    private var lastGesture             = "None"
    private var gestureCount            = 0

    // Smoothing
    private var smoothPointerX  = 0.5f
    private var smoothPointerY  = 0.5f
    private val pointerAlpha    = 0.20f
    private var smoothCenterY: Float? = null
    private val centerYAlpha    = 0.35f

    // Direction history
    private val dirHistory = ArrayDeque<String>()

    // -------------------------------------------------------
    // RESET
    // -------------------------------------------------------

    fun reset() {
        lastHoldMoveTime = 0L
        wasTapPinching  = false
        wasHoldPinching = false
        isHoldActive    = false
        flickState      = "IDLE"
        armedFor        = null
        poseStartTime   = 0L
        flickStartY     = null
        smoothCenterY   = null
        dirHistory.clear()
    }

    // -------------------------------------------------------
    // MAIN ENTRY POINT
    // -------------------------------------------------------

    fun processResult(
        result: HandLandmarkerResult,
        imageWidth: Int,
        imageHeight: Int
    ): GestureState {

        if (result.landmarks().isEmpty()) {
            val wasHolding = isHoldActive
            reset()
            return GestureState(
                handDetected     = false,
                lastGesture      = lastGesture,
                gestureCount     = gestureCount,
                triggeredHoldEnd = wasHolding
            )
        }

        val landmarks = result.landmarks()[0]
        val w   = imageWidth.toFloat()
        val h   = imageHeight.toFloat()
        val now = System.currentTimeMillis()

        // --- Pointer position ---
        val rawPointerX = listOf(0, 5, 9, 13, 17)
            .map { landmarks[it].x() }.average().toFloat()
        val rawPointerY = listOf(0, 5, 9, 13, 17)
            .map { landmarks[it].y() }.average().toFloat()

        smoothPointerX = pointerAlpha * rawPointerX + (1f - pointerAlpha) * smoothPointerX
        smoothPointerY = pointerAlpha * rawPointerY + (1f - pointerAlpha) * smoothPointerY

        // --- Key landmarks ---
        val thumbTip  = landmarks[4]   // thumb tip
        val indexTip  = landmarks[8]   // index finger tip
        val middleTip = landmarks[12]  // middle finger tip

        // -------------------------------------------------------
        // TAP — thumb + index instant on first touch frame
        // -------------------------------------------------------
        val tapDist     = hypot(thumbTip.x() - indexTip.x(), thumbTip.y() - indexTip.y())
        val tapPinching = tapDist < TAP_PINCH_THRESHOLD
        var triggeredTap = false

        if (tapPinching && !wasTapPinching) {
            if (now - lastTapTime >= TAP_COOLDOWN_MS) {
                triggeredTap = true
                lastTapTime  = now
                lastGesture  = "TAP"
                gestureCount++
            }
        }
        wasTapPinching = tapPinching

        // -------------------------------------------------------
        // HOLD — thumb + middle finger
        // -------------------------------------------------------
        val holdDist     = hypot(thumbTip.x() - middleTip.x(), thumbTip.y() - middleTip.y())
        val holdPinching = holdDist < HOLD_PINCH_THRESHOLD

        var triggeredHoldStart = false
        var triggeredHoldMove  = false
        var triggeredHoldEnd   = false
        var holdX = holdCurrentX
        var holdY = holdCurrentY

        when {
            holdPinching && !wasHoldPinching -> {
                isHoldActive    = true
                holdCurrentX    = smoothPointerX
                holdCurrentY    = smoothPointerY
                holdX           = holdCurrentX
                holdY           = holdCurrentY
                triggeredHoldStart = true
                lastGesture     = "HOLD"
                gestureCount++
            }
            holdPinching && wasHoldPinching && isHoldActive -> {
                val dxPx   = (smoothPointerX - holdCurrentX) * imageWidth
                val dyPx   = (smoothPointerY - holdCurrentY) * imageHeight
                val movePx = hypot(dxPx, dyPx)
                val canMove = now - lastHoldMoveTime >= HOLD_MOVE_INTERVAL_MS
                if (movePx > HOLD_DRAG_MIN_PX && canMove) {
                    holdCurrentX      = smoothPointerX
                    holdCurrentY      = smoothPointerY
                    holdX             = holdCurrentX
                    holdY             = holdCurrentY
                    triggeredHoldMove = true
                    lastHoldMoveTime  = now
                }
            }
            !holdPinching && wasHoldPinching && isHoldActive -> {
                isHoldActive    = false
                holdX           = holdCurrentX
                holdY           = holdCurrentY
                triggeredHoldEnd = true
                lastGesture     = "HOLD_END"
            }
        }
        wasHoldPinching = holdPinching

        // -------------------------------------------------------
        // FLICK — only when no pinch is active
        // -------------------------------------------------------
        val handOpen = isHandOpen(landmarks, w, h)
        val rawDir = when {
            tapPinching || holdPinching -> "PINCHING"
            handOpen -> getFingerDirection(landmarks)
            else -> "CLOSED"
        }

        if (rawDir == "FINGERS_UP" || rawDir == "FINGERS_DOWN") {
            dirHistory.addLast(rawDir)
            while (dirHistory.size > DIR_HISTORY_SIZE) dirHistory.removeFirst()
        }

        val stableDir = getStableDirection()

        val centerY = listOf(0, 5, 9, 13, 17)
            .map { landmarks[it].y() * h }.average().toFloat()
        smoothCenterY = if (smoothCenterY == null) centerY
        else centerYAlpha * centerY + (1f - centerYAlpha) * smoothCenterY!!

        var triggeredGesture: String? = null

        if (!tapPinching && !holdPinching && !isHoldActive) {
            if (now - lastFlickTriggerTime >= FLICK_COOLDOWN_MS) {
                triggeredGesture = runFlickStateMachine(stableDir, smoothCenterY!!, now)
            }
        } else {
            resetFlick()
        }

        return GestureState(
            handDetected       = true,
            pointerX           = smoothPointerX,
            pointerY           = smoothPointerY,
            fingerDirection    = stableDir,
            isHandOpen         = handOpen,
            isTapPinching      = tapPinching,
            isHoldPinching     = holdPinching,
            engineState        = flickState,
            armedFor           = armedFor,
            lastGesture        = lastGesture,
            gestureCount       = gestureCount,
            triggeredGesture   = triggeredGesture,
            triggeredTap       = triggeredTap,
            triggeredHoldStart = triggeredHoldStart,
            triggeredHoldMove  = triggeredHoldMove,
            triggeredHoldEnd   = triggeredHoldEnd,
            holdX              = holdX,
            holdY              = holdY
        )
    }

    // -------------------------------------------------------
    // FLICK STATE MACHINE
    // -------------------------------------------------------

    private fun runFlickStateMachine(stableDir: String, sy: Float, now: Long): String? {
        when (flickState) {
            "IDLE" -> {
                when (stableDir) {
                    "FINGERS_UP" -> {
                        if (armedFor != "DOWN") {
                            armedFor      = "DOWN"
                            poseStartTime = now
                            flickStartY   = sy
                        } else if (now - poseStartTime >= POSE_HOLD_MS) {
                            flickState = "ARMED"
                        }
                    }
                    "FINGERS_DOWN" -> {
                        if (armedFor != "UP") {
                            armedFor      = "UP"
                            poseStartTime = now
                            flickStartY   = sy
                        } else if (now - poseStartTime >= POSE_HOLD_MS) {
                            flickState = "ARMED"
                        }
                    }
                    else -> resetFlick()
                }
            }
            "ARMED" -> {
                if (now > poseStartTime + POSE_HOLD_MS + FLICK_TIMEOUT_MS) {
                    resetFlick()
                    return null
                }
                val startY = flickStartY ?: sy
                if (armedFor == "DOWN" && stableDir == "FINGERS_DOWN") {
                    return if (sy - startY > FLICK_MIN_PIXELS) {
                        lastGesture = "DOWN"
                        gestureCount++
                        lastFlickTriggerTime = now
                        resetFlick()
                        dirHistory.clear()
                        "DOWN"
                    } else { resetFlick(); null }
                }
                if (armedFor == "UP" && stableDir == "FINGERS_UP") {
                    return if (sy - startY < -FLICK_MIN_PIXELS) {
                        lastGesture = "UP"
                        gestureCount++
                        lastFlickTriggerTime = now
                        resetFlick()
                        dirHistory.clear()
                        "UP"
                    } else { resetFlick(); null }
                }
            }
        }
        return null
    }

    private fun resetFlick() {
        flickState    = "IDLE"
        armedFor      = null
        poseStartTime = 0L
        flickStartY   = null
    }

    // -------------------------------------------------------
    // HAND HELPERS
    // -------------------------------------------------------

    private fun isHandOpen(landmarks: List<NormalizedLandmark>, w: Float, h: Float): Boolean {
        val wrist = landmarks[0]
        var extended = 0
        for ((tip, mcp) in listOf(8 to 5, 12 to 9, 16 to 13, 20 to 17)) {
            if (dist(wrist, landmarks[tip], w, h) > dist(wrist, landmarks[mcp], w, h) * 1.3f)
                extended++
        }
        return extended >= 3
    }

    private fun getFingerDirection(landmarks: List<NormalizedLandmark>): String {
        var up = 0; var down = 0
        for (i in listOf(8, 12, 16, 20).indices) {
            val tipY = landmarks[listOf(8, 12, 16, 20)[i]].y()
            val mcpY = landmarks[listOf(5, 9, 13, 17)[i]].y()
            when { tipY < mcpY -> up++; tipY > mcpY -> down++ }
        }
        return when { up >= 3 -> "FINGERS_UP"; down >= 3 -> "FINGERS_DOWN"; else -> "UNCLEAR" }
    }

    private fun getStableDirection(): String {
        if (dirHistory.size < 3) return "UNCLEAR"
        val up   = dirHistory.count { it == "FINGERS_UP" }
        val down = dirHistory.count { it == "FINGERS_DOWN" }
        return when { up >= STABLE_COUNT_NEEDED -> "FINGERS_UP"
            down >= STABLE_COUNT_NEEDED -> "FINGERS_DOWN"
            else -> "UNCLEAR" }
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark, w: Float, h: Float): Float =
        hypot((a.x() - b.x()) * w, (a.y() - b.y()) * h)
}