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

        fun performBack(): Boolean {
            return instance?.doBack() ?: false
        }

        fun isEnabled() = instance != null
    }

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
    // SCROLL — always uses swipe gesture
    // This works on ALL apps including Instagram Reels,
    // YouTube Shorts, TikTok, home screen, everything
    // -------------------------------------------------------

    fun doScroll(direction: String): Boolean {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()

        val centerX = screenW / 2f

        // Vertical scroll points
        val topY    = screenH * 0.25f
        val bottomY = screenH * 0.75f

        // Horizontal scroll points
        val leftX  = screenW * 0.20f
        val rightX = screenW * 0.80f
        val midY   = screenH / 2f

        val path = Path()

        when (direction.lowercase()) {
            "up" -> {
                // Swipe from bottom to top = scroll up / next content up
                path.moveTo(centerX, bottomY)
                path.lineTo(centerX, topY)
            }
            "down" -> {
                // Swipe from top to bottom = scroll down / next content down
                path.moveTo(centerX, topY)
                path.lineTo(centerX, bottomY)
            }
            "left" -> {
                // Swipe from right to left = scroll left / go back
                path.moveTo(rightX, midY)
                path.lineTo(leftX, midY)
            }
            "right" -> {
                // Swipe from left to right = scroll right / go forward
                path.moveTo(leftX, midY)
                path.lineTo(rightX, midY)
            }
            else -> return false
        }

        // Duration: 250ms feels natural for a swipe
        // Too fast = might not register in some apps
        // Too slow = feels laggy
        val stroke = GestureDescription.StrokeDescription(path, 0L, 250L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.d(TAG, "Scroll $direction completed")
            }
            override fun onCancelled(g: GestureDescription?) {
                Log.d(TAG, "Scroll $direction cancelled")
            }
        }, null)
    }

    // -------------------------------------------------------
    // TAP — short quick tap
    // -------------------------------------------------------

    fun doTap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }

        val stroke  = GestureDescription.StrokeDescription(path, 0L, 80L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.d(TAG, "Tap at ($x, $y) completed")
            }
        }, null)
    }

    // -------------------------------------------------------
    // BACK — system back button
    // -------------------------------------------------------

    fun doBack(): Boolean {
        Log.d(TAG, "Performing BACK")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }
}