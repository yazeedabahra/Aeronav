package com.yvora.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.annotation.RequiresApi

/** One in-flight stroke; latest target wins. All methods run on the main thread. */
@RequiresApi(26)
internal class ContinuousDrag(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private var previous: GestureDescription.StrokeDescription? = null
    private var active = false
    private var inFlight = false
    private var ending = false
    private var cancelling = false
    private var x = 0f
    private var y = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var lastUpdate = 0L
    private var moveAfter = 0L
    private var generation = 0L
    val busy: Boolean get() = active
    val ready: Boolean get() = active && !ending && previous != null

    fun start(px: Float, py: Float): Boolean {
        if (active || !px.isFinite() || !py.isFinite()) return false
        active = true
        ending = false
        cancelling = false
        x = px; y = py; targetX = px; targetY = py
        lastUpdate = SystemClock.uptimeMillis()
        moveAfter = lastUpdate + ViewConfiguration.getLongPressTimeout() + 80L
        pump()
        return active
    }

    fun move(px: Float, py: Float) {
        if (!active || ending || !px.isFinite() || !py.isFinite()) return
        targetX = px; targetY = py
        lastUpdate = SystemClock.uptimeMillis()
    }

    fun finish(cancel: Boolean = false) {
        if (!active) return
        if (cancel) cancelling = true
        ending = true
        if (!inFlight) pump()
    }

    fun disconnected() { clear() }

    private fun clear() {
        generation++
        active = false; inFlight = false; ending = false; previous = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun pump() {
        if (!active || inFlight) return
        val now = SystemClock.uptimeMillis()
        if (!ending && now - lastUpdate > 650L) {
            ending = true; cancelling = true
        }
        val nx = if (!cancelling && previous != null) targetX else x
        val ny = if (!cancelling && previous != null) targetY else y
        val final = ending
        val path = Path().apply {
            moveTo(x, y)
            if (nx != x || ny != y) lineTo(nx, ny)
        }
        val token = generation
        try {
            val stroke = previous?.continueStroke(path, 0L, if (final) 40L else 80L, !final)
                ?: GestureDescription.StrokeDescription(path, 0L, (moveAfter - now).coerceAtLeast(80L), !final)
            inFlight = true
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (token != generation) return
                    inFlight = false
                    x = nx; y = ny; previous = stroke
                    if (final) clear() else pump()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (token == generation) clear()
                }
            }
            if (!service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, handler)) clear()
        } catch (_: RuntimeException) { clear() }
    }
}
