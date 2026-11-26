package com.yvora.app

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot
import android.os.SystemClock

class GestureRecognizer {

    data class GestureState(
        val handDetected: Boolean = false,
        val pointerX: Float = 0.5f,
        val pointerY: Float = 0.5f,
        val rawX: Float = Float.NaN,
        val rawY: Float = Float.NaN,

        // Hand info
        val handedness: String = "Unknown",
        val fingersUp: Int = 0,
        val isPointerMode: Boolean = false,
        val isTapPinching: Boolean = false,

        // Gesture mode
        val gestureArmed: Boolean = false,
        val gestureDirection: String? = null,

        // Results
        val lastGesture: String = "None",
        val gestureCount: Int = 0,
        val triggeredGesture: String? = null,
        val triggeredTap: Boolean = false,

        // Debug
        val debugInfo: String = ""
    )

    companion object {
        // Tap: index + thumb pinch
        private const val TAP_PINCH_THRESHOLD = 0.06f
        private const val TAP_COOLDOWN_MS     = 400L

        // Finger extended: tip must be this much farther from wrist than MCP
        private const val EXTENDED_RATIO = 1.4f

        // Finger curled: tip must be closer to wrist than this ratio of MCP distance
        private const val CURLED_RATIO = 1.2f

        // How long the finger gesture must be held to fire
        // Make gesture faster to trigger
        private const val GESTURE_CONFIRM_MS = 400L      // lower = faster

        // Cooldown after gesture fires
        private const val GESTURE_COOLDOWN_MS = 1000L     // lower = faster repeat

        // Open hand = pointer mode (4+ fingers extended)
        private const val POINTER_MIN_FINGERS = 4
    }

    // -------------------------------------------------------
    // STATE
    // -------------------------------------------------------

    // Tap
    private var wasTapPinching = false
    private var lastTapTime    = 0L

    // Finger gesture
    private var currentGestureDirection: String? = null
    private var gestureStartTime = 0L
    private var lastGestureTriggerTime = 0L

    // Results
    private var lastGesture  = "None"
    private var gestureCount = 0

    // Pointer smoothing
    private val pointerFilter = AdaptivePointerFilter()
    private val horizontalSwipe = HorizontalSwipeEngine()
    private var controlRange = ControlRange()

    internal fun setControlRange(value: ControlRange) {
        if (controlRange != value) {
            controlRange = value
            pointerFilter.reset()
        }
    }

    fun reset() {
        wasTapPinching = false
        currentGestureDirection = null
        gestureStartTime = 0L
        pointerFilter.reset()
        horizontalSwipe.reset()
    }

    // -------------------------------------------------------
    // MAIN
    // -------------------------------------------------------

    fun processResult(
        result: HandLandmarkerResult,
        imageWidth: Int,
        imageHeight: Int
    ): GestureState {

        if (result.landmarks().isEmpty()) {
            wasTapPinching = true
            currentGestureDirection = null
            gestureStartTime = 0L
            pointerFilter.reset()
            horizontalSwipe.trackingLost()
            return GestureState(
                handDetected = false,
                lastGesture  = lastGesture,
                gestureCount = gestureCount
            )
        }

        val landmarks = result.landmarks()[0]
        val w   = imageWidth.toFloat()
        val h   = imageHeight.toFloat()
        val now = SystemClock.uptimeMillis()

        // --- Handedness ---
        // MediaPipe returns handedness from camera perspective
        // Since we flip the image, "Left" from MediaPipe = user's Left hand
        val handedness = if (result.handednesses().isNotEmpty() &&
            result.handednesses()[0].isNotEmpty()
        ) {
            result.handednesses()[0][0].categoryName() ?: "Unknown"
        } else {
            "Unknown"
        }

        // --- Pointer position ---
        val rawPX = (landmarks[0].x() + landmarks[5].x() + landmarks[9].x() +
                landmarks[13].x() + landmarks[17].x()) / 5f
        val rawPY = (landmarks[0].y() + landmarks[5].y() + landmarks[9].y() +
                landmarks[13].y() + landmarks[17].y()) / 5f
        pointerFilter.update(controlRange.mapX(rawPX), controlRange.mapY(rawPY), now)

        // --- Finger analysis ---
        val thumbUp  = isThumbExtended(landmarks, w, h)
        val indexUp  = isFingerExtended(landmarks, 8, 6, 5, w, h)
        val middleUp = isFingerExtended(landmarks, 12, 10, 9, w, h)
        val ringUp   = isFingerExtended(landmarks, 16, 14, 13, w, h)
        val pinkyUp  = isFingerExtended(landmarks, 20, 18, 17, w, h)

        val fingersUp = (if (indexUp) 1 else 0) + (if (middleUp) 1 else 0) +
                (if (ringUp) 1 else 0) + (if (pinkyUp) 1 else 0)
        val totalUp   = fingersUp + if (thumbUp) 1 else 0

        // --- Tap: index + thumb pinch ---
        val thumbTip = landmarks[4]
        val indexTip = landmarks[8]
        val tapDist  = hypot(thumbTip.x() - indexTip.x(), thumbTip.y() - indexTip.y())
        val swipe = horizontalSwipe.update(totalUp, rawPX, rawPY, now)
        val swipePose = totalUp == 3
        val blocksOther = swipePose || horizontalSwipe.blocksOtherGestures
        val rawPinch = tapDist < TAP_PINCH_THRESHOLD
        val tapPinching = rawPinch && !blocksOther

        var triggeredTap = false
        if (tapPinching && !wasTapPinching) {
            if (now - lastTapTime >= TAP_COOLDOWN_MS) {
                triggeredTap = true
                lastTapTime  = now
                lastGesture  = "TAP"
                gestureCount++
            }
        }
        // Do not turn a pinch held during a swipe into a tap on release.
        wasTapPinching = rawPinch

        // --- Pointer mode: open hand (4+ fingers up) ---
        val isPointerMode = totalUp >= POINTER_MIN_FINGERS && !blocksOther

        // --- Finger gesture detection ---
        // Only detect when NOT in pointer mode and NOT tapping
        var triggeredGesture: String? = null
        var gestureArmed = false
        var gestureDirection: String? = null

        if (blocksOther) {
            currentGestureDirection = null
            gestureStartTime = 0L
            if (swipePose) {
                gestureArmed = true
                gestureDirection = swipe
                if (swipe != null) {
                    triggeredGesture = swipe
                    lastGesture = swipe
                    gestureCount++
                }
            }
        } else if (!isPointerMode && !tapPinching) {
            val detectedDirection = classifyFingerGesture(
                handedness = handedness,
                indexUp    = indexUp,
                middleUp   = middleUp,
                ringUp     = ringUp,
                pinkyUp    = pinkyUp,
                thumbUp    = thumbUp
            )

            if (detectedDirection != null) {
                gestureDirection = detectedDirection
                gestureArmed     = true

                if (detectedDirection == currentGestureDirection) {
                    // Same gesture held — check if confirm time reached
                    if (now - gestureStartTime >= GESTURE_CONFIRM_MS) {
                        // Check cooldown
                        if (now - lastGestureTriggerTime >= GESTURE_COOLDOWN_MS) {
                            triggeredGesture       = detectedDirection
                            lastGesture            = detectedDirection
                            gestureCount++
                            lastGestureTriggerTime = now
                            // Reset so it doesn't fire again immediately
                            currentGestureDirection = null
                            gestureStartTime        = 0L
                        }
                    }
                } else {
                    // New gesture direction — start timer
                    currentGestureDirection = detectedDirection
                    gestureStartTime        = now
                }
            } else {
                // No valid gesture — reset
                currentGestureDirection = null
                gestureStartTime        = 0L
            }
        } else {
            // In pointer mode or tapping — reset gesture
            currentGestureDirection = null
            gestureStartTime        = 0L
        }

        // --- Debug ---
        val fingerStr = buildString {
            if (thumbUp)  append("T")
            if (indexUp)  append("I")
            if (middleUp) append("M")
            if (ringUp)   append("R")
            if (pinkyUp)  append("P")
            if (isEmpty()) append("FIST")
        }

        // Display corrected handedness (swap because MediaPipe reports from camera perspective)
        val displayHand = when (handedness) {
            "Left"  -> "Right"
            "Right" -> "Left"
            else    -> handedness
        }

        val debugInfo = "$displayHand hand | Fingers: $fingerStr ($totalUp) | " +
                "${if (swipePose) "Horizontal swipe ready" else if (gestureArmed) "Armed: $gestureDirection" else "Idle"}"

        return GestureState(
            handDetected     = true,
            pointerX         = pointerFilter.x,
            pointerY         = pointerFilter.y,
            rawX             = rawPX,
            rawY             = rawPY,
            handedness       = handedness,
            fingersUp        = totalUp,
            isPointerMode    = isPointerMode,
            isTapPinching    = tapPinching,
            gestureArmed     = gestureArmed,
            gestureDirection = gestureDirection,
            lastGesture      = lastGesture,
            gestureCount     = gestureCount,
            triggeredGesture = triggeredGesture,
            triggeredTap     = triggeredTap,
            debugInfo        = debugInfo
        )
    }

    // -------------------------------------------------------
    // FINGER GESTURE CLASSIFIER
    //
    // Right hand:
    //   Index only         → SCROLL UP
    //   Index + Middle     → SCROLL DOWN
    //
    // Horizontal gestures: exactly three fingers, either hand, plus movement.
    // Handedness is retained for the existing vertical gesture mapping.
    //
    // Requirements:
    //   - Only the specified fingers should be extended
    //   - Ring and Pinky must be curled
    //   - Thumb can be in any position (ignored)
    // -------------------------------------------------------

    private fun classifyFingerGesture(
        handedness: String,
        indexUp: Boolean,
        middleUp: Boolean,
        ringUp: Boolean,
        pinkyUp: Boolean,
        thumbUp: Boolean
    ): String? {

        // Ring and pinky must be DOWN for gesture detection
        if (ringUp || pinkyUp) return null

        // One finger: index only
        val oneFingerUp = indexUp && !middleUp

        // Two fingers: index + middle
        val twoFingersUp = indexUp && middleUp

        if (!oneFingerUp && !twoFingersUp) return null

        return when (handedness) {
            "Left" -> {
                when {
                    oneFingerUp  -> "UP"
                    twoFingersUp -> "DOWN"
                    else         -> null
                }
            }
            else -> null
        }
    }

    // -------------------------------------------------------
    // FINGER EXTENDED DETECTION
    //
    // A finger is extended if:
    //   - tip is farther from wrist than PIP joint
    //   - tip Y is above PIP Y (for vertical check)
    //
    // Uses tip vs PIP comparison which is more reliable
    // than tip vs MCP for detecting extended vs curled
    // -------------------------------------------------------

    private fun isFingerExtended(
        landmarks: List<NormalizedLandmark>,
        tipIdx: Int,
        pipIdx: Int,
        mcpIdx: Int,
        w: Float,
        h: Float
    ): Boolean {
        val wrist = landmarks[0]
        val tip   = landmarks[tipIdx]
        val pip   = landmarks[pipIdx]
        val mcp   = landmarks[mcpIdx]

        val tipDist = dist(wrist, tip, w, h)
        val mcpDist = dist(wrist, mcp, w, h)

        // Tip must be significantly farther from wrist than MCP
        if (tipDist < mcpDist * EXTENDED_RATIO) return false

        // Additional check: tip must be farther from wrist than PIP
        val pipDist = dist(wrist, pip, w, h)
        return tipDist > pipDist
    }

    // -------------------------------------------------------
    // THUMB EXTENDED DETECTION
    //
    // Thumb is special — it moves sideways, not up/down
    // Check if thumb tip is far enough from palm center
    // -------------------------------------------------------

    private fun isThumbExtended(
        landmarks: List<NormalizedLandmark>,
        w: Float,
        h: Float
    ): Boolean {
        val wrist    = landmarks[0]
        val thumbTip = landmarks[4]
        val thumbIp  = landmarks[3]
        val thumbMcp = landmarks[2]

        val tipDist = dist(wrist, thumbTip, w, h)
        val mcpDist = dist(wrist, thumbMcp, w, h)

        return tipDist > mcpDist * 1.3f
    }

    private fun dist(
        a: NormalizedLandmark,
        b: NormalizedLandmark,
        w: Float,
        h: Float
    ): Float = hypot((a.x() - b.x()) * w, (a.y() - b.y()) * h)
}