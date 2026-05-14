package com.example.airscroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
    // SCROLL
    // -------------------------------------------------------

    fun doScroll(direction: String): Boolean {
        val action = when (direction.lowercase()) {
            "up"   -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else   -> return false
        }

        val root = rootInActiveWindow ?: return fallbackSwipeScroll(direction)

        val scrolled = findAndScroll(root, action)
        root.recycle()

        return if (scrolled) {
            Log.d(TAG, "Node scroll $direction succeeded")
            true
        } else {
            Log.d(TAG, "Node scroll failed, falling back to swipe")
            fallbackSwipeScroll(direction)
        }
    }

    private fun findAndScroll(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable) {
            val result = node.performAction(action)
            if (result) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findAndScroll(child, action)
            child.recycle()
            if (result) return true
        }

        return false
    }

    private fun fallbackSwipeScroll(direction: String): Boolean {
        val m   = resources.displayMetrics
        val cx  = m.widthPixels / 2f
        val top = m.heightPixels * 0.30f
        val bot = m.heightPixels * 0.70f

        val path = Path()
        when (direction.lowercase()) {
            "up"   -> { path.moveTo(cx, bot); path.lineTo(cx, top) }
            "down" -> { path.moveTo(cx, top); path.lineTo(cx, bot) }
            else   -> return false
        }

        val stroke  = GestureDescription.StrokeDescription(path, 0L, 300L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.d(TAG, "Fallback swipe $direction done")
            }
        }, null)
    }

    // -------------------------------------------------------
    // TAP
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
                Log.d(TAG, "Tap done at ($x, $y)")
            }
        }, null)
    }

    // -------------------------------------------------------
    // BACK
    // -------------------------------------------------------

    fun doBack(): Boolean {
        Log.d(TAG, "Performing BACK")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }
}