package com.example.airscroll

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.abs
import kotlin.math.hypot

class GestureRecognizer {

    // -------------------------------------------------------
    // STATES
    // -------------------------------------------------------
    // POINTER  — normal mode, pointer follows hand
    // FIST     — fist detected, waiting for L-shape
    // L_DETECT — L-shape detected, classify direction
    // COOLDOWN — gesture fired, wait before next

    enum class Mode {
        POINTER,
        FIST,
        L_DETECT,
        COOLDOWN
    }

    data class GestureState(
        val handDetected: Boolean = false,
        val pointerX: Float = 0.5f,
        val pointerY: Float = 0.5f,
        val isTapPinching: Boolean = false,
        val mode: Mode = Mode.POINTER,
        val lShapeDirection: String? = null,
        val lastGesture: String = "None",
        val gestureCount: Int = 0,
        val triggeredGesture: String? = null,
        val triggeredTap: Boolean = false,
        val showFistNotification: Boolean = false,
        val showLShapeDetected: Boolean = false,
        val debugInfo: String = ""
    )

    companion object {
        // Tap: index + thumb
        private const val TAP_PINCH_THRESHOLD = 0.06f
        private const val TAP_COOLDOWN_MS     = 400L

        // Fist detection
        // All fingertips must be within this distance of palm center
        private const val FIST_MAX_FINGER_DIST = 0.12f

        // L-shape detection
        // Index and thumb must be extended beyond this ratio
        private const val L_EXTENDED_RATIO = 1.5f

        // Other fingers must be curled (tip close to MCP)
        private const val L_CURLED_RATIO = 1.2f

        // Direction detection: minimum extension in pixels (normalized)
        private const val MIN_DIRECTION_LENGTH = 0.08f

        // How long fist must be held before entering gesture mode
        private const val FIST_HOLD_MS = 400L

        // How long L-shape must be held to confirm direction
        private const val L_CONFIRM_MS = 300L

        // Cooldown after gesture fires
        private const val GESTURE_COOLDOWN_MS = 1200L

        // How long to stay in L_DETECT before timeout back to POINTER
        private const val L_TIMEOUT_MS = 2000L
    }

    // -------------------------------------------------------
    // STATE
    // -------------------------------------------------------

    private var mode: Mode = Mode.POINTER

    // Tap
    private var wasTapPinching = false
    private var lastTapTime    = 0L

    // Fist
    private var fistStartTime   = 0L
    private var fistConfirmed   = false

    // L-shape
    private var lShapeStartTime      = 0L
    private var lShapeDirection: String? = null
    private var lShapeConfirmed      = false
    private var lShapeConfirmStartMs = 0L

    // Cooldown
    private var cooldownStartTime = 0L

    // Results
    private var lastGesture  = "None"
    private var gestureCount = 0

    // Pointer smoothing
    private var smoothPointerX = 0.5f
    private var smoothPointerY = 0.5f
    private val pointerAlpha   = 0.20f

    // -------------------------------------------------------
    // RESET
    // -------------------------------------------------------

    fun reset() {
        mode              = Mode.POINTER
        wasTapPinching    = false
        fistStartTime     = 0L
        fistConfirmed     = false
        lShapeStartTime   = 0L
        lShapeDirection   = null
        lShapeConfirmed   = false
        lShapeConfirmStartMs = 0L
        cooldownStartTime = 0L
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
            reset()
            return GestureState(
                handDetected = false,
                lastGesture  = lastGesture,
                gestureCount = gestureCount
            )
        }

        val landmarks = result.landmarks()[0]
        val w   = imageWidth.toFloat()
        val h   = imageHeight.toFloat()
        val now = System.currentTimeMillis()

        // --- Always update pointer ---
        val rawPX = listOf(0, 5, 9, 13, 17)
            .map { landmarks[it].x() }.average().toFloat()
        val rawPY = listOf(0, 5, 9, 13, 17)
            .map { landmarks[it].y() }.average().toFloat()

        smoothPointerX = pointerAlpha * rawPX + (1f - pointerAlpha) * smoothPointerX
        smoothPointerY = pointerAlpha * rawPY + (1f - pointerAlpha) * smoothPointerY

        // --- Always check tap (index + thumb) ---
        val thumbTip = landmarks[4]
        val indexTip = landmarks[8]
        val tapDist  = hypot(thumbTip.x() - indexTip.x(), thumbTip.y() - indexTip.y())
        val tapPinching = tapDist < TAP_PINCH_THRESHOLD

        var triggeredTap = false
        if (mode == Mode.POINTER && tapPinching && !wasTapPinching) {
            if (now - lastTapTime >= TAP_COOLDOWN_MS) {
                triggeredTap = true
                lastTapTime  = now
                lastGesture  = "TAP"
                gestureCount++
            }
        }
        wasTapPinching = tapPinching

        // --- Detect hand poses ---
        val isFist   = detectFist(landmarks, w, h)
        val lResult  = detectLShape(landmarks, w, h)
        val isLShape = lResult != null

        // --- State machine ---
        var triggeredGesture: String? = null
        var showFistNotification = false
        var showLShapeDetected   = false
        var debugInfo = "Mode: $mode"

        when (mode) {

            Mode.POINTER -> {
                if (isFist) {
                    if (fistStartTime == 0L) {
                        fistStartTime = now
                    }
                    if (now - fistStartTime >= FIST_HOLD_MS) {
                        mode          = Mode.FIST
                        fistConfirmed = true
                        showFistNotification = true
                        debugInfo = "FIST detected — show gesture now"
                    }
                } else {
                    fistStartTime = 0L
                }
            }

            Mode.FIST -> {
                showFistNotification = true
                debugInfo = "Waiting for L-shape..."

                if (isLShape) {
                    mode             = Mode.L_DETECT
                    lShapeStartTime  = now
                    lShapeDirection  = lResult
                    lShapeConfirmed  = false
                    lShapeConfirmStartMs = now
                    debugInfo = "L-shape detected: $lResult"
                }

                // If hand opens fully (not fist and not L) go back to pointer
                if (!isFist && !isLShape) {
                    // Give a small grace period
                    if (fistStartTime == 0L) fistStartTime = now
                    if (now - fistStartTime > 500L) {
                        mode = Mode.POINTER
                        fistStartTime = 0L
                        fistConfirmed = false
                    }
                } else {
                    fistStartTime = now
                }
            }

            Mode.L_DETECT -> {
                showLShapeDetected = true
                debugInfo = "L: ${lShapeDirection ?: "?"}"

                if (isLShape && lResult != null) {
                    // Direction matches — confirm after hold time
                    if (lResult == lShapeDirection) {
                        if (now - lShapeConfirmStartMs >= L_CONFIRM_MS) {
                            // FIRE!
                            triggeredGesture = lShapeDirection
                            lastGesture      = lShapeDirection ?: "None"
                            gestureCount++
                            mode = Mode.COOLDOWN
                            cooldownStartTime = now
                            debugInfo = "GESTURE FIRED: $lShapeDirection"
                        }
                    } else {
                        // Direction changed — update
                        lShapeDirection      = lResult
                        lShapeConfirmStartMs = now
                    }
                } else {
                    // L-shape lost
                    if (now - lShapeStartTime > L_TIMEOUT_MS) {
                        mode = Mode.POINTER
                        debugInfo = "L-shape timeout"
                    }
                    // Reset confirm timer
                    lShapeConfirmStartMs = now
                }
            }

            Mode.COOLDOWN -> {
                debugInfo = "Cooldown..."
                if (now - cooldownStartTime >= GESTURE_COOLDOWN_MS) {
                    mode = Mode.POINTER
                    fistStartTime   = 0L
                    fistConfirmed   = false
                    lShapeDirection = null
                }
            }
        }

        return GestureState(
            handDetected         = true,
            pointerX             = smoothPointerX,
            pointerY             = smoothPointerY,
            isTapPinching        = tapPinching,
            mode                 = mode,
            lShapeDirection      = lShapeDirection,
            lastGesture          = lastGesture,
            gestureCount         = gestureCount,
            triggeredGesture     = triggeredGesture,
            triggeredTap         = triggeredTap,
            showFistNotification = showFistNotification,
            showLShapeDetected   = showLShapeDetected,
            debugInfo            = debugInfo
        )
    }

    // -------------------------------------------------------
    // FIST DETECTOR
    //
    // All 4 fingertips (index, middle, ring, pinky) must be
    // close to palm center. Thumb can be anywhere.
    // -------------------------------------------------------

    private fun detectFist(
        landmarks: List<NormalizedLandmark>,
        w: Float,
        h: Float
    ): Boolean {
        val wrist = landmarks[0]

        // Palm center = average of wrist + 4 MCP joints
        val palmX = listOf(0, 5, 9, 13, 17).map { landmarks[it].x() }.average().toFloat()
        val palmY = listOf(0, 5, 9, 13, 17).map { landmarks[it].y() }.average().toFloat()

        // All 4 fingertips must be close to palm center
        val fingerTips = listOf(8, 12, 16, 20)

        var closedCount = 0
        for (tipIdx in fingerTips) {
            val tip  = landmarks[tipIdx]
            val dist = hypot(tip.x() - palmX, tip.y() - palmY)
            if (dist < FIST_MAX_FINGER_DIST) {
                closedCount++
            }
        }

        // Thumb tip must also be close
        val thumbDist = hypot(landmarks[4].x() - palmX, landmarks[4].y() - palmY)
        val thumbClosed = thumbDist < FIST_MAX_FINGER_DIST * 1.3f

        return closedCount >= 4 && thumbClosed
    }

    // -------------------------------------------------------
    // L-SHAPE DETECTOR
    //
    // Conditions:
    // 1. Index finger extended (tip far from wrist)
    // 2. Thumb extended (tip far from wrist)
    // 3. Middle, Ring, Pinky all curled (tip close to MCP)
    //
    // Then determine directions of index and thumb
    // and classify the gesture.
    // -------------------------------------------------------

    private fun detectLShape(
        landmarks: List<NormalizedLandmark>,
        w: Float,
        h: Float
    ): String? {
        val wrist = landmarks[0]

        // --- Check index is extended ---
        val indexTip = landmarks[8]
        val indexMcp = landmarks[5]
        val indexTipDist = dist(wrist, indexTip, w, h)
        val indexMcpDist = dist(wrist, indexMcp, w, h)
        val indexExtended = indexTipDist > indexMcpDist * L_EXTENDED_RATIO

        // --- Check thumb is extended ---
        val thumbTip = landmarks[4]
        val thumbMcp = landmarks[2]
        val thumbTipDist = dist(wrist, thumbTip, w, h)
        val thumbMcpDist = dist(wrist, thumbMcp, w, h)
        val thumbExtended = thumbTipDist > thumbMcpDist * L_EXTENDED_RATIO

        // --- Check middle, ring, pinky are curled ---
        val middleCurled = isFingerCurled(landmarks, 12, 9, w, h)
        val ringCurled   = isFingerCurled(landmarks, 16, 13, w, h)
        val pinkyCurled  = isFingerCurled(landmarks, 20, 17, w, h)

        val othersCurled = middleCurled && ringCurled && pinkyCurled

        if (!indexExtended || !thumbExtended || !othersCurled) {
            return null
        }

        // --- Determine index direction ---
        // Vector from index MCP to index tip
        val indexDx = indexTip.x() - indexMcp.x()
        val indexDy = indexTip.y() - indexMcp.y()
        val indexDir = getDominantDirection(indexDx, indexDy)

        // --- Determine thumb direction ---
        // Vector from thumb MCP to thumb tip
        val thumbDx = thumbTip.x() - thumbMcp.x()
        val thumbDy = thumbTip.y() - thumbMcp.y()
        val thumbDir = getDominantDirection(thumbDx, thumbDy)

        // Make sure both have enough length
        val indexLen = hypot(indexDx, indexDy)
        val thumbLen = hypot(thumbDx, thumbDy)
        if (indexLen < MIN_DIRECTION_LENGTH || thumbLen < MIN_DIRECTION_LENGTH) {
            return null
        }

        // Must be perpendicular (one horizontal, one vertical)
        if (!arePerpendicular(indexDir, thumbDir)) {
            return null
        }

        // --- Classify based on index direction ---
        // Index determines the scroll/navigation direction
        return when (indexDir) {
            "UP"    -> "UP"
            "DOWN"  -> "DOWN"
            "LEFT"  -> "LEFT"
            "RIGHT" -> "RIGHT"
            else    -> null
        }
    }

    private fun isFingerCurled(
        landmarks: List<NormalizedLandmark>,
        tipIdx: Int,
        mcpIdx: Int,
        w: Float,
        h: Float
    ): Boolean {
        val wrist = landmarks[0]
        val tipDist = dist(wrist, landmarks[tipIdx], w, h)
        val mcpDist = dist(wrist, landmarks[mcpIdx], w, h)
        return tipDist < mcpDist * L_CURLED_RATIO
    }

    private fun getDominantDirection(dx: Float, dy: Float): String {
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) "RIGHT" else "LEFT"
        } else {
            if (dy > 0) "DOWN" else "UP"
        }
    }

    private fun arePerpendicular(dir1: String, dir2: String): Boolean {
        val horizontal = setOf("LEFT", "RIGHT")
        val vertical   = setOf("UP", "DOWN")
        return (dir1 in horizontal && dir2 in vertical) ||
                (dir1 in vertical   && dir2 in horizontal)
    }

    private fun dist(
        a: NormalizedLandmark,
        b: NormalizedLandmark,
        w: Float,
        h: Float
    ): Float = hypot((a.x() - b.x()) * w, (a.y() - b.y()) * h)
}