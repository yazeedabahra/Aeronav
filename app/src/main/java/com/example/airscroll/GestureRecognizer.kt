package com.example.airscroll

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.LinkedList
import kotlin.math.abs
import kotlin.math.hypot

class GestureRecognizer {

    data class GestureState(
        val handDetected: Boolean = false,

        // Normalized pointer position 0.0 to 1.0
        val pointerX: Float = 0.5f,
        val pointerY: Float = 0.5f,

        val fingerDirection: String = "UNCLEAR",
        val isPinching: Boolean = false,
        val isHandOpen: Boolean = false,

        val engineState: String = "IDLE",
        val armedFor: String? = null,

        val lastGesture: String = "None",
        val gestureCount: Int = 0,

        // Triggered this frame
        val triggeredGesture: String? = null,  // "UP" / "DOWN" / null
        val triggeredTap: Boolean = false       // true on pinch tap
    )

    // Flick state machine
    private var state = "IDLE"
    private var armedFor: String? = null
    private var poseStartTime: Long = 0
    private var startY: Float? = null
    private var lastFlickTriggerTime: Long = 0
    private var lastGesture: String = "None"
    private var gestureCount: Int = 0

    // Pinch state
    private var wasPinching = false
    private var pinchEnteredTime: Long = 0
    private var lastTapTime: Long = 0
    private var tapTriggeredThisPinch = false

    // Smoothing for hand center Y (for flick detection)
    private var smoothCenterY: Float? = null
    private val centerYAlpha = 0.35f

    // Smoothing for pointer position
    private var smoothPointerX: Float = 0.5f
    private var smoothPointerY: Float = 0.5f
    private val pointerAlpha = 0.20f

    // Direction history for stable classification
    private val dirHistory = LinkedList<String>()
    private val DIR_HISTORY_SIZE = 5
    private val STABLE_COUNT_NEEDED = 4

    companion object {
        // Flick gesture settings
        private const val FLICK_COOLDOWN_MS = 1200L
        private const val POSE_HOLD_MS = 200L
        private const val FLICK_TIMEOUT_MS = 500L
        private const val FLICK_MIN_PIXELS = 50f

        // Pinch settings
        private const val PINCH_THRESHOLD = 0.08f      // normalized distance thumb-index
        private const val PINCH_CONFIRM_MS = 60L       // how long pinch must be held
        private const val TAP_COOLDOWN_MS = 900L       // min time between taps
    }

    fun reset() {
        state = "IDLE"
        armedFor = null
        poseStartTime = 0
        startY = null
        smoothCenterY = null
        dirHistory.clear()
        wasPinching = false
        pinchEnteredTime = 0
        tapTriggeredThisPinch = false
    }

    fun processResult(
        result: HandLandmarkerResult,
        imageWidth: Int,
        imageHeight: Int
    ): GestureState {

        if (result.landmarks().isEmpty()) {
            reset()
            return GestureState(
                handDetected = false,
                lastGesture = lastGesture,
                gestureCount = gestureCount
            )
        }

        val landmarks = result.landmarks()[0]
        val w = imageWidth.toFloat()
        val h = imageHeight.toFloat()
        val now = System.currentTimeMillis()

        // --- Pointer position ---
        // Average of wrist + 4 MCP knuckles
        val rawPointerX = landmarks.let {
            listOf(0, 5, 9, 13, 17).map { i -> it[i].x() }.average().toFloat()
        }
        val rawPointerY = landmarks.let {
            listOf(0, 5, 9, 13, 17).map { i -> it[i].y() }.average().toFloat()
        }

        // Smooth pointer
        smoothPointerX = pointerAlpha * rawPointerX + (1f - pointerAlpha) * smoothPointerX
        smoothPointerY = pointerAlpha * rawPointerY + (1f - pointerAlpha) * smoothPointerY

        // --- Pinch detection ---
        val thumbTip = landmarks[4]
        val indexTip = landmarks[8]

        val pinchDist = hypot(
            thumbTip.x() - indexTip.x(),
            thumbTip.y() - indexTip.y()
        )

        val currentlyPinching = pinchDist < PINCH_THRESHOLD
        var triggeredTap = false

        when {
            // Pinch just started
            currentlyPinching && !wasPinching -> {
                pinchEnteredTime = now
                tapTriggeredThisPinch = false
                wasPinching = true
            }

            // Pinch is being held
            currentlyPinching && wasPinching -> {
                // Fire tap once per pinch after confirm delay
                if (!tapTriggeredThisPinch
                    && now - pinchEnteredTime >= PINCH_CONFIRM_MS
                    && now - lastTapTime >= TAP_COOLDOWN_MS
                ) {
                    triggeredTap = true
                    tapTriggeredThisPinch = true
                    lastTapTime = now
                    lastGesture = "TAP"
                    gestureCount++
                }
            }

            // Pinch released
            !currentlyPinching && wasPinching -> {
                wasPinching = false
                tapTriggeredThisPinch = false
            }
        }

        // --- Hand open check ---
        val handOpen = isHandOpen(landmarks, w, h)

        // --- Finger direction (raw) ---
        val rawDir = if (handOpen) getFingerDirection(landmarks) else "CLOSED"

        // Update direction history
        if (rawDir != "CLOSED" && rawDir != "UNCLEAR") {
            dirHistory.addLast(rawDir)
            while (dirHistory.size > DIR_HISTORY_SIZE) dirHistory.removeFirst()
        }

        val stableDir = getStableDirection()

        // --- Hand center Y for flick ---
        val centerY = listOf(0, 5, 9, 13, 17)
            .map { landmarks[it].y() * h }
            .average()
            .toFloat()

        smoothCenterY = if (smoothCenterY == null) centerY
        else centerYAlpha * centerY + (1f - centerYAlpha) * smoothCenterY!!

        val sy = smoothCenterY!!

        // --- Flick cooldown check ---
        var triggeredGesture: String? = null

        if (now - lastFlickTriggerTime >= FLICK_COOLDOWN_MS) {
            triggeredGesture = runFlickStateMachine(stableDir, sy, now)
        }

        return GestureState(
            handDetected = true,
            pointerX = smoothPointerX,
            pointerY = smoothPointerY,
            fingerDirection = stableDir,
            isPinching = currentlyPinching,
            isHandOpen = handOpen,
            engineState = state,
            armedFor = armedFor,
            lastGesture = lastGesture,
            gestureCount = gestureCount,
            triggeredGesture = triggeredGesture,
            triggeredTap = triggeredTap
        )
    }

    private fun runFlickStateMachine(
        stableDir: String,
        sy: Float,
        now: Long
    ): String? {

        when (state) {
            "IDLE" -> {
                when (stableDir) {
                    "FINGERS_UP" -> {
                        if (armedFor != "DOWN") {
                            armedFor = "DOWN"
                            poseStartTime = now
                            startY = sy
                        } else if (now - poseStartTime >= POSE_HOLD_MS) {
                            state = "ARMED"
                        }
                    }
                    "FINGERS_DOWN" -> {
                        if (armedFor != "UP") {
                            armedFor = "UP"
                            poseStartTime = now
                            startY = sy
                        } else if (now - poseStartTime >= POSE_HOLD_MS) {
                            state = "ARMED"
                        }
                    }
                    else -> {
                        resetFlick()
                    }
                }
            }

            "ARMED" -> {
                val armDeadline = poseStartTime + POSE_HOLD_MS + FLICK_TIMEOUT_MS
                if (now > armDeadline) {
                    resetFlick()
                    return null
                }

                val sY = startY ?: sy

                if (armedFor == "DOWN" && stableDir == "FINGERS_DOWN") {
                    val delta = sy - sY
                    return if (delta > FLICK_MIN_PIXELS) {
                        triggerFlick("DOWN")
                        "DOWN"
                    } else {
                        resetFlick()
                        null
                    }
                }

                if (armedFor == "UP" && stableDir == "FINGERS_UP") {
                    val delta = sy - sY
                    return if (delta < -FLICK_MIN_PIXELS) {
                        triggerFlick("UP")
                        "UP"
                    } else {
                        resetFlick()
                        null
                    }
                }
            }
        }
        return null
    }

    private fun triggerFlick(gesture: String) {
        lastFlickTriggerTime = System.currentTimeMillis()
        lastGesture = gesture
        gestureCount++
        resetFlick()
        dirHistory.clear()
    }

    private fun resetFlick() {
        state = "IDLE"
        armedFor = null
        poseStartTime = 0
        startY = null
    }

    private fun isHandOpen(landmarks: List<NormalizedLandmark>, w: Float, h: Float): Boolean {
        val wrist = landmarks[0]
        val pairs = listOf(8 to 5, 12 to 9, 16 to 13, 20 to 17)
        var extended = 0
        for ((tip, mcp) in pairs) {
            if (dist(wrist, landmarks[tip], w, h) > dist(wrist, landmarks[mcp], w, h) * 1.3f) {
                extended++
            }
        }
        return extended >= 3
    }

    private fun getFingerDirection(landmarks: List<NormalizedLandmark>): String {
        val tips = listOf(8, 12, 16, 20)
        val mcps = listOf(5, 9, 13, 17)
        var up = 0
        var down = 0
        for (i in tips.indices) {
            when {
                landmarks[tips[i]].y() < landmarks[mcps[i]].y() -> up++
                landmarks[tips[i]].y() > landmarks[mcps[i]].y() -> down++
            }
        }
        return when {
            up >= 3 -> "FINGERS_UP"
            down >= 3 -> "FINGERS_DOWN"
            else -> "UNCLEAR"
        }
    }

    private fun getStableDirection(): String {
        if (dirHistory.size < 3) return "UNCLEAR"
        val up = dirHistory.count { it == "FINGERS_UP" }
        val down = dirHistory.count { it == "FINGERS_DOWN" }
        return when {
            up >= STABLE_COUNT_NEEDED -> "FINGERS_UP"
            down >= STABLE_COUNT_NEEDED -> "FINGERS_DOWN"
            else -> "UNCLEAR"
        }
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark, w: Float, h: Float): Float {
        return hypot((a.x() - b.x()) * w, (a.y() - b.y()) * h)
    }
}