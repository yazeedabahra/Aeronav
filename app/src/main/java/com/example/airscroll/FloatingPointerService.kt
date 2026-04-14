package com.example.airscroll

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

class FloatingPointerService : Service() {

    companion object {
        private const val TAG = "FloatingPointer"
        var instance: FloatingPointerService? = null

        // Smoothing — lower = smoother but more lag, higher = more responsive
        private const val SMOOTH_ALPHA = 0.20f

        // Dead zone in pixels — ignore tiny jitter movements
        private const val DEAD_ZONE = 3f
    }

    private var windowManager: WindowManager? = null
    private var pointerView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var screenWidth = 0
        private set
    var screenHeight = 0
        private set

    // Smoothed current pointer position on screen
    private var currentX = 0f
    private var currentY = 0f

    private var isViewAdded = false
    private var isVisible = false

    override fun onCreate() {
        super.onCreate()
        instance = this

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        currentX = screenWidth / 2f
        currentY = screenHeight / 2f

        Log.d(TAG, "Screen size: ${screenWidth}x${screenHeight}")

        mainHandler.postDelayed({ tryCreatePointerView() }, 200)
    }

    private fun tryCreatePointerView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "Overlay permission not granted")
                return
            }
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val inflater = LayoutInflater.from(this)
            pointerView = inflater.inflate(R.layout.overlay_pointer, null)

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screenWidth / 2)
                y = (screenHeight / 2)
            }

            windowManager?.addView(pointerView, layoutParams)
            pointerView?.visibility = View.GONE
            isViewAdded = true

            Log.d(TAG, "Pointer view added successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create pointer view: ${e.message}", e)
            isViewAdded = false
        }
    }

    /**
     * Update pointer position.
     *
     * normX and normY are normalized landmark coordinates (0.0 to 1.0)
     * from MediaPipe after the image has already been flipped horizontally
     * for front camera in HandLandmarkerHelper.
     *
     * Since HandLandmarkerHelper already mirrors the image with postScale(-1, 1),
     * landmarks are already in correct left-right orientation.
     * So we map directly WITHOUT inverting X.
     */
    fun updatePosition(normX: Float, normY: Float) {
        if (!isViewAdded) return

        // Direct mapping — no X inversion because image is already flipped
        val targetX = normX * screenWidth
        val targetY = normY * screenHeight

        val dx = targetX - currentX
        val dy = targetY - currentY

        // Apply dead zone
        if (Math.abs(dx) > DEAD_ZONE || Math.abs(dy) > DEAD_ZONE) {
            currentX += dx * SMOOTH_ALPHA
            currentY += dy * SMOOTH_ALPHA
        }

        // Clamp to screen bounds accounting for pointer size (48dp ~ 150px)
        val clampedX = currentX.coerceIn(0f, (screenWidth - 60).toFloat())
        val clampedY = currentY.coerceIn(0f, (screenHeight - 60).toFloat())

        mainHandler.post {
            try {
                if (isViewAdded && isVisible && pointerView != null) {
                    layoutParams?.x = clampedX.toInt()
                    layoutParams?.y = clampedY.toInt()
                    windowManager?.updateViewLayout(pointerView, layoutParams)
                }
            } catch (e: Exception) {
                Log.e(TAG, "updateViewLayout error: ${e.message}")
            }
        }
    }

    fun showPointer() {
        if (!isViewAdded || isVisible) return
        mainHandler.post {
            try {
                pointerView?.visibility = View.VISIBLE
                isVisible = true
            } catch (e: Exception) {
                Log.e(TAG, "showPointer error: ${e.message}")
            }
        }
    }

    fun hidePointer() {
        if (!isViewAdded || !isVisible) return
        mainHandler.post {
            try {
                pointerView?.visibility = View.GONE
                isVisible = false
            } catch (e: Exception) {
                Log.e(TAG, "hidePointer error: ${e.message}")
            }
        }
    }

    // Returns the actual screen X position of the pointer center
    fun getCurrentScreenX(): Float = currentX

    // Returns the actual screen Y position of the pointer center
    fun getCurrentScreenY(): Float = currentY

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            if (isViewAdded && pointerView != null) {
                windowManager?.removeView(pointerView)
                isViewAdded = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "removeView error: ${e.message}")
        }
        pointerView = null
        windowManager = null
        if (instance === this) instance = null
        Log.d(TAG, "Service destroyed")
    }
}