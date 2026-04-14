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

    fun doScroll(direction: String): Boolean {
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f
        val top = m.heightPixels * 0.25f
        val bot = m.heightPixels * 0.75f

        val path = Path()
        when (direction.lowercase()) {
            "up" -> { path.moveTo(cx, bot); path.lineTo(cx, top) }
            "down" -> { path.moveTo(cx, top); path.lineTo(cx, bot) }
            else -> return false
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, 300L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.d(TAG, "Scroll $direction done")
            }
        }, null)
    }

    fun doTap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 100L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.d(TAG, "Tap at ($x, $y) done")
            }
        }, null)
    }
}