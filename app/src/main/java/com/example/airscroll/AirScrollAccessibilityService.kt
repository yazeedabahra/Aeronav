package com.example.airscroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AirScrollAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yService"
        var instance: AirScrollAccessibilityService? = null

        fun performScroll(direction: String): Boolean {
            return instance?.doScroll(direction) ?: false
        }

        fun performTap(x: Float, y: Float): Boolean {
            return instance?.doTap(x, y) ?: false
        }

        fun startHold(x: Float, y: Float): Boolean {
            return instance?.doHoldStart(x, y) ?: false
        }

        fun continueHold(x: Float, y: Float): Boolean {
            return instance?.doHoldContinue(x, y) ?: false
        }

        fun endHold(x: Float, y: Float): Boolean {
            return instance?.doHoldEnd(x, y) ?: false
        }

        fun isEnabled() = instance != null
    }

    // Hold/drag state
    private var holdStartX      = 0f
    private var holdStartY      = 0f
    private var holdCurrentX    = 0f
    private var holdCurrentY    = 0f
    private var isHolding       = false

    // Collected drag path points while hold is active
    // We build one big stroke from all of them
    private val dragPoints      = mutableListOf<Pair<Float, Float>>()
    private var holdStartTimeMs = 0L

    // We dispatch one ongoing gesture for the entire hold+drag
    // Android does not support truly continuous gestures across
    // multiple dispatchGesture calls, so we use a different strategy:
    // 1. On hold START → dispatch a long press gesture (600ms at fixed point)
    // 2. On hold MOVE  → dispatch individual short swipe strokes
    // 3. On hold END   → do nothing (last move already released touch)
    // This gives the feel of hold + drag without repeated taps.

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    // -------------------------------------------------------
    // SCROLL
    // -------------------------------------------------------

    fun doScroll(direction: String): Boolean {
        val m   = resources.displayMetrics
        val cx  = m.widthPixels / 2f
        val top = m.heightPixels * 0.25f
        val bot = m.heightPixels * 0.75f

        val path = Path()
        when (direction.lowercase()) {
            "up"   -> { path.moveTo(cx, bot); path.lineTo(cx, top) }
            "down" -> { path.moveTo(cx, top); path.lineTo(cx, bot) }
            else   -> return false
        }

        val stroke  = GestureDescription.StrokeDescription(path, 0L, 350L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.d(TAG, "Scroll $direction done")
            }
        }, null)
    }

    // -------------------------------------------------------
    // TAP — index + thumb
    // Short quick tap, not a long press
    // -------------------------------------------------------

    fun doTap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }

        // 80ms is short enough to feel instant but not trigger long press
        val stroke  = GestureDescription.StrokeDescription(path, 0L, 80L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.d(TAG, "Tap done at ($x, $y)")
            }
        }, null)
    }

    // -------------------------------------------------------
    // HOLD START — middle + thumb touch
    //
    // Strategy: dispatch a LONG gesture that stays at one point
    // for 600ms (long press threshold on Android is ~500ms)
    // then releases. This activates long press on the target.
    // After this, drag moves are sent as separate swipe strokes.
    // -------------------------------------------------------

    fun doHoldStart(x: Float, y: Float): Boolean {
        holdStartX      = x
        holdStartY      = y
        holdCurrentX    = x
        holdCurrentY    = y
        isHolding       = true
        holdStartTimeMs = System.currentTimeMillis()
        dragPoints.clear()
        dragPoints.add(Pair(x, y))

        Log.d(TAG, "Hold start at ($x, $y)")

        // Long press = touch held for 650ms without moving
        val path = Path().apply {
            moveTo(x, y)
            // Tiny move to make it a valid path but stay essentially in place
            lineTo(x + 0.5f, y + 0.5f)
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            650L    // 650ms = long press duration
        )

        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.d(TAG, "Hold start gesture completed")
            }
            override fun onCancelled(g: GestureDescription?) {
                Log.d(TAG, "Hold start gesture cancelled")
            }
        }, null)
    }

    // -------------------------------------------------------
    // HOLD CONTINUE — middle + thumb held, hand moving
    //
    // Strategy: each meaningful hand movement dispatches
    // a short swipe stroke from previous position to new position.
    // Duration is long enough to feel smooth but short enough
    // to be responsive.
    // -------------------------------------------------------

    fun doHoldContinue(x: Float, y: Float): Boolean {
        if (!isHolding) return false

        val fromX = holdCurrentX
        val fromY = holdCurrentY
        holdCurrentX = x
        holdCurrentY = y

        val dx = x - fromX
        val dy = y - fromY
        val dist = Math.hypot(dx.toDouble(), dy.toDouble())

        // Skip very tiny movements
        if (dist < 3.0) return false

        Log.d(TAG, "Hold drag from ($fromX,$fromY) to ($x,$y)")

        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(x, y)
        }

        // 120ms per segment feels smooth
        val stroke  = GestureDescription.StrokeDescription(path, 0L, 120L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, null, null)
    }

    // -------------------------------------------------------
    // HOLD END — middle + thumb separate
    // -------------------------------------------------------

    fun doHoldEnd(x: Float, y: Float): Boolean {
        if (!isHolding) return false
        isHolding = false
        dragPoints.clear()
        Log.d(TAG, "Hold end at ($x, $y)")

        // Small lift gesture to signal touch release
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.5f, y + 0.5f)
        }

        val stroke  = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.d(TAG, "Hold end gesture completed")
            }
        }, null)
    }
}