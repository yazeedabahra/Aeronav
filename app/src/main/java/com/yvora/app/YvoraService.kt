package com.yvora.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.atomic.AtomicBoolean

class YvoraService : LifecycleService() {

    // -------------------------------------------------------
    // STATUS LISTENER INTERFACE
    // Must be a top-level interface inside the class, not local
    // -------------------------------------------------------
    interface StatusListener {
        fun onStatus(
            handDetected: Boolean,
            gesture: String,
            isTapPinching: Boolean,
            isGestureMode: Boolean,
            debugInfo: String
        )
        fun onStopped() {}
    }

    companion object {
        private const val TAG = "YvoraService"
        private const val CHANNEL_ID = "yvora_channel"
        private const val NOTIFICATION_ID = 1001

        var instance: YvoraService? = null
        var statusListener: StatusListener? = null

        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_CALIBRATION = "calibration_mode"
    }

    // -------------------------------------------------------
    // FIELDS
    // -------------------------------------------------------
    private var windowManager: WindowManager? = null
    private var cameraOverlayView: View? = null
    private var pointerView: View? = null
    private var pointerParams: WindowManager.LayoutParams? = null

    private var handLandmarker: HandLandmarker? = null
    private val gestureRecognizer = GestureRecognizer()
    private lateinit var cameraExecutor: java.util.concurrent.ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    private var pointerX = 0f
    private var pointerY = 0f
    private var dragStarted = false
    private var pinchPending = false
    private var pinchAnchorX = 0f
    private var pinchAnchorY = 0f
    private var pinchStartX = 0f
    private var pinchStartY = 0f
    var screenWidth = 0
        private set
    var screenHeight = 0
        private set

    private var isPointerVisible = false
    private var isPointerAdded = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deadZone = 3f
    private var started = false
    @Volatile private var stopped = false
    private val resultPending = AtomicBoolean(false)
    private lateinit var framePacer: FramePacer
    private var lowRamDevice = false
    private var cameraDetail = CameraDetail.AUTO
    private var calibrationMode = false
    val isCalibrating: Boolean get() = calibrationMode
    private var needsOpenHand = false
    var sampleX = Float.NaN
        private set
    var sampleY = Float.NaN
        private set
    var analysisWidth = 0
        private set
    var analysisHeight = 0
        private set
    val usingDistanceDetail: Boolean get() = cameraDetail == CameraDetail.DISTANCE

    // These methods are called only on the main thread.
    internal fun updateControlRange(range: ControlRange) {
        if (pinchPending || dragStarted) {
            cancelDrag()
            gestureRecognizer.reset()
            needsOpenHand = true
        }
        gestureRecognizer.setControlRange(range)
    }

    internal fun setCalibrationMode(enabled: Boolean) {
        if (calibrationMode == enabled) return
        calibrationMode = enabled
        needsOpenHand = true
        gestureRecognizer.reset()
        forceHidePointer()
    }
    val isLowRamProfile: Boolean get() = lowRamDevice
    var resultRate = 0f
        private set
    var frameAgeMs = -1L
        private set
    private var rateWindowStart = -1L
    private var rateWindowCount = 0

    // Called on main thread for delivered tracking results only.
    private fun recordTelemetry(frameTime: Long) {
        val now = SystemClock.uptimeMillis()
        frameAgeMs = (now - frameTime).coerceAtLeast(0L)
        if (rateWindowStart < 0L) {
            rateWindowStart = now
            rateWindowCount = 0
        } else {
            rateWindowCount++
            val elapsed = now - rateWindowStart
            if (elapsed >= 1000L) {
                resultRate = rateWindowCount * 1000f / elapsed
                rateWindowStart = now
                rateWindowCount = 0
            }
        }
    }
    private var lastFrameTimestamp = -1L
    private var lastPointerColor: Int? = null

    // -------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate")

        cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        lowRamDevice = activityManager.isLowRamDevice || activityManager.memoryClass <= 128
        cameraDetail = CameraSettings.readDetail(this)
        gestureRecognizer.setControlRange(CameraSettings.readRange(this))
        // Preserve the existing pacing; distance detail can cost more per frame.
        framePacer = FramePacer(if (lowRamDevice) 100L else 66L)

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        pointerX = screenWidth / 2f
        pointerY = screenHeight / 2f

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (started || stopped) return START_STICKY
                started = true
                calibrationMode = intent?.getBooleanExtra(EXTRA_CALIBRATION, false) ?: false
                startForeground(NOTIFICATION_ID, buildNotification())
                setupOverlayWindows()
                forceHidePointer()
                gestureRecognizer.reset()
                cameraExecutor.execute {
                    if (!stopped) initMediaPipe()
                    val ready = handLandmarker != null
                    mainHandler.post {
                        if (!stopped) {
                            if (ready) startCamera() else stopSelf()
                        }
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        stopped = true
        cancelDrag()
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        // Immediately hide pointer
        try {
            pointerView?.visibility = View.GONE
            isPointerVisible = false
        } catch (e: Exception) { }

        mainHandler.removeCallbacksAndMessages(null)

        try { cameraProvider?.unbindAll() } catch (e: Exception) {
            Log.e(TAG, "unbind error: ${e.message}")
        }

        // Serialize native inference and close on the same worker.
        cameraExecutor.execute {
            try { handLandmarker?.close() } catch (e: Exception) {
                Log.e(TAG, "landmarker close error: ${e.message}")
            } finally { handLandmarker = null }
        }
        cameraExecutor.shutdown()

        try {
            if (isPointerAdded && pointerView != null) {
                windowManager?.removeView(pointerView)
            }
            if (cameraOverlayView != null) {
                windowManager?.removeView(cameraOverlayView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "removeView error: ${e.message}")
        }

        if (instance === this) {
            instance = null
            statusListener?.onStopped()
        }
    }

    // -------------------------------------------------------
    // OVERLAY SETUP
    // -------------------------------------------------------

    @SuppressLint("InflateParams")
    private fun setupOverlayWindows() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "No overlay permission")
                return
            }
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 1x1 invisible camera preview overlay
        val cameraParams = WindowManager.LayoutParams(
            1, 1,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        val cameraContainer = FrameLayout(this)
        val previewView = androidx.camera.view.PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
        }
        cameraContainer.addView(previewView)
        cameraOverlayView = cameraContainer

        try {
            windowManager?.addView(cameraOverlayView, cameraParams)
            Log.d(TAG, "Camera overlay added")
        } catch (e: Exception) {
            Log.e(TAG, "Camera overlay failed: ${e.message}", e)
        }

        // Visible pointer overlay
        val inflater = LayoutInflater.from(this)
        pointerView = inflater.inflate(R.layout.overlay_pointer, null)

        pointerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth / 2
            y = screenHeight / 2
        }

        try {
            windowManager?.addView(pointerView, pointerParams)
            pointerView?.visibility = View.GONE
            isPointerAdded = true
            Log.d(TAG, "Pointer overlay added")
        } catch (e: Exception) {
            Log.e(TAG, "Pointer overlay failed: ${e.message}", e)
        }
    }



    // -------------------------------------------------------
    // HIDING THE POINTER
    // -------------------------------------------------------

    fun forceHidePointer() {
        cancelDrag()
        gestureRecognizer.reset()
        mainHandler.post {
            try {
                pointerView?.visibility = View.GONE
                isPointerVisible = false
                pointerX = screenWidth / 2f
                pointerY = screenHeight / 2f
                // Also reset pointer color to default
                val inner = pointerView?.findViewById<View>(R.id.innerDot)
                inner?.setBackgroundResource(R.drawable.pointer_inner)
                lastPointerColor = android.graphics.Color.TRANSPARENT
                Log.d(TAG, "Pointer force hidden and reset")
            } catch (e: Exception) {
                Log.e(TAG, "forceHidePointer error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------
    // MEDIAPIPE INIT
    // -------------------------------------------------------

    private fun initMediaPipe() {
        try {
            val files = assets.list("") ?: emptyArray()
            if (!files.contains("hand_landmarker.task")) {
                Log.e(TAG, "hand_landmarker.task not found in assets")
                return
            }

            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setNumHands(1)
                .setRunningMode(RunningMode.VIDEO)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(this, options)
            Log.d(TAG, "HandLandmarker ready")

        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe init failed: ${e.message}", e)
        }
    }

    // -------------------------------------------------------
    // CAMERA
    // -------------------------------------------------------

    private fun startCamera() {
        if (stopped) return
        val previewView = (cameraOverlayView as? FrameLayout)
            ?.getChildAt(0) as? androidx.camera.view.PreviewView

        if (previewView == null) {
            Log.e(TAG, "PreviewView not found")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (stopped) return@addListener
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(cameraDetail.requestedSize(lowRamDevice))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                Log.d(TAG, "Camera started")

            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // -------------------------------------------------------
    // FRAME PROCESSING
    // -------------------------------------------------------

    private fun processFrame(imageProxy: ImageProxy) {
        val frameTime = SystemClock.uptimeMillis()
        val detector = handLandmarker
        // Drop before doing expensive conversion. Only one UI result can be pending.
        if (stopped || detector == null || resultPending.get() || !framePacer.canStart(frameTime)) {
            imageProxy.close()
            return
        }
        framePacer.started(frameTime)
        var proxyClosed = false
        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxy.toBitmap()
            imageProxy.close()
            proxyClosed = true
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                postScale(-1f, 1f)
            }
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) bitmap.recycle()
            val width = oriented.width
            val height = oriented.height
            val mpImage = BitmapImageBuilder(oriented).build()
            val timestamp = maxOf(frameTime, lastFrameTimestamp + 1L)
            lastFrameTimestamp = timestamp
            val result = try {
                detector.detectForVideo(mpImage, timestamp)
            } finally {
                mpImage.close()
            }
            if (!stopped && resultPending.compareAndSet(false, true)) {
                val posted = mainHandler.post {
                    try {
                        if (!stopped) {
                            if (SystemClock.uptimeMillis() - frameTime <= 500L) {
                                recordTelemetry(frameTime)
                                onHandLandmarkerResult(result, width, height)
                            } else {
                                cancelDrag()
                                gestureRecognizer.reset()
                                needsOpenHand = true
                                sampleX = Float.NaN
                                sampleY = Float.NaN
                                hidePointer()
                                statusListener?.onStatus(false, "None", false, false, "")
                            }
                        }
                    } finally { resultPending.set(false) }
                }
                if (!posted) resultPending.set(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame error: ${e.message}")
        } finally {
            if (!proxyClosed) imageProxy.close()
            framePacer.finished(SystemClock.uptimeMillis() - frameTime)
        }
    }

    // -------------------------------------------------------
    // MEDIAPIPE RESULT HANDLER
    // -------------------------------------------------------

    private fun onHandLandmarkerResult(result: HandLandmarkerResult, width: Int, height: Int) {
        val state = gestureRecognizer.processResult(result, width, height)
        sampleX = state.rawX
        sampleY = state.rawY
        analysisWidth = width
        analysisHeight = height

        // Calibration never injects system gestures. On exit require an open hand
        // without a pinch before resuming input.
        if (calibrationMode || needsOpenHand) {
            cancelDrag()
            if (!calibrationMode && state.handDetected && state.isPointerMode && !state.isTapPinching) {
                needsOpenHand = false
                gestureRecognizer.reset()
            }
            hidePointer()
            statusListener?.onStatus(state.handDetected, state.lastGesture,
                state.isTapPinching, state.gestureArmed, state.debugInfo)
            return
        }

        if (state.pinchAction != PinchAction.NONE) {
            when (state.pinchAction) {
                PinchAction.PENDING -> {
                    if (!pinchPending) {
                        pinchPending = true
                        pinchAnchorX = pointerX
                        pinchAnchorY = pointerY
                        pinchStartX = state.pointerX
                        pinchStartY = state.pointerY
                    }
                    showPointer()
                    setPointerColor(android.graphics.Color.CYAN)
                }
                PinchAction.TAP -> {
                    if (pinchPending && state.triggeredTap) {
                        YvoraAccessibilityService.performTap(pinchAnchorX, pinchAnchorY)
                        flashPointer()
                    }
                    pinchPending = false
                    setPointerColor(android.graphics.Color.TRANSPARENT)
                }
                PinchAction.START_DRAG -> {
                    if (pinchPending) dragStarted = YvoraAccessibilityService.beginDrag(pinchAnchorX, pinchAnchorY)
                    if (dragStarted) setPointerColor(android.graphics.Color.GREEN)
                }
                PinchAction.MOVE_DRAG -> {
                    if (dragStarted && YvoraAccessibilityService.dragBusy()) {
                        val px = (pinchAnchorX + (state.pointerX - pinchStartX) * screenWidth)
                            .coerceIn(0f, (screenWidth - 1).coerceAtLeast(0).toFloat())
                        val py = (pinchAnchorY + (state.pointerY - pinchStartY) * screenHeight)
                            .coerceIn(0f, (screenHeight - 1).coerceAtLeast(0).toFloat())
                        updatePointer(px / (screenWidth - 1).coerceAtLeast(1), py / (screenHeight - 1).coerceAtLeast(1))
                        YvoraAccessibilityService.moveDrag(pointerX, pointerY)
                        showPointer()
                        setPointerColor(android.graphics.Color.GREEN)
                    } else dragStarted = false
                }
                PinchAction.DROP -> {
                    if (dragStarted) YvoraAccessibilityService.endDrag()
                    dragStarted = false
                    pinchPending = false
                    setPointerColor(android.graphics.Color.TRANSPARENT)
                }
                PinchAction.CANCEL -> {
                    cancelDrag()
                    setPointerColor(android.graphics.Color.TRANSPARENT)
                    if (!state.handDetected) {
                        hidePointer()
                        pointerX = screenWidth / 2f
                        pointerY = screenHeight / 2f
                    }
                }
                PinchAction.NONE -> Unit
            }
            statusListener?.onStatus(state.handDetected, state.lastGesture,
                state.isTapPinching, false,
                if (dragStarted) "Dragging" else state.debugInfo)
            return
        }

        // Pointer — only move when in pointer mode (open hand)
        if (state.handDetected) {
            if (state.isPointerMode) {
                updatePointer(state.pointerX, state.pointerY)
                showPointer()
                setPointerColor(android.graphics.Color.TRANSPARENT)
            } else if (state.gestureArmed) {
                showPointer()
                val color = when (state.gestureDirection) {
                    "UP"    -> android.graphics.Color.GREEN
                    "DOWN"  -> android.graphics.Color.parseColor("#9C27B0")
                    "LEFT"  -> android.graphics.Color.CYAN
                    "RIGHT" -> android.graphics.Color.YELLOW
                    else    -> android.graphics.Color.RED
                }
                setPointerColor(color)
            } else {
                showPointer()
                setPointerColor(android.graphics.Color.TRANSPARENT)
            }
        } else {
            hidePointer()
            // Reset pointer position to center so old position doesn't linger
            pointerX = screenWidth / 2f
            pointerY = screenHeight / 2f
        }

        // Scroll / navigate gesture — ALL use swipe gestures now
        if (state.triggeredGesture != null) {
            when (state.triggeredGesture) {
                "UP" -> {
                    YvoraAccessibilityService.performScroll("up")
                    Log.d(TAG, "SCROLL UP")
                }
                "DOWN" -> {
                    YvoraAccessibilityService.performScroll("down")
                    Log.d(TAG, "SCROLL DOWN")
                }
                "LEFT" -> {
                    YvoraAccessibilityService.performScroll("left")
                    Log.d(TAG, "SWIPE LEFT")
                }
                "RIGHT" -> {
                    YvoraAccessibilityService.performScroll("right")
                    Log.d(TAG, "SWIPE RIGHT")
                }
            }
            flashPointer()
        }

        // UI update
        statusListener?.onStatus(
            state.handDetected,
            state.lastGesture,
            state.isTapPinching,
            state.gestureArmed,
            state.debugInfo
        )
    }

    private fun cancelDrag() {
        YvoraAccessibilityService.endDrag(cancel = true)
        dragStarted = false
        pinchPending = false
    }

    private fun showLShapeIndicator() {
        mainHandler.post {
            try {
                // Change pointer color to blue while drawing L-shape
                pointerView?.setBackgroundColor(android.graphics.Color.BLUE)
            } catch (e: Exception) { }
        }
    }

    private fun hideLShapeIndicator() {
        mainHandler.post {
            try {
                pointerView?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            } catch (e: Exception) { }
        }
    }

    // -------------------------------------------------------
    // POINTER CONTROL
    // -------------------------------------------------------

    private fun updatePointer(normX: Float, normY: Float) {
        val targetX = normX.coerceIn(0f, 1f) * (screenWidth - 1).coerceAtLeast(0)
        val targetY = normY.coerceIn(0f, 1f) * (screenHeight - 1).coerceAtLeast(0)

        val dx = targetX - pointerX
        val dy = targetY - pointerY

        if (Math.abs(dx) > deadZone || Math.abs(dy) > deadZone) {
            pointerX = targetX
            pointerY = targetY
        }

        val half = 24f * resources.displayMetrics.density
        val cx = pointerX - half
        val cy = pointerY - half

        mainHandler.post {
            try {
                if (!stopped && isPointerAdded && isPointerVisible) {
                    val params = pointerParams ?: return@post
                    val view = pointerView ?: return@post
                    if (params.x != cx.toInt() || params.y != cy.toInt()) {
                        params.x = cx.toInt()
                        params.y = cy.toInt()
                        windowManager?.updateViewLayout(view, params)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pointer update error: ${e.message}")
            }
        }
    }

    private fun showPointer() {
        if (!isPointerAdded || isPointerVisible) return
        mainHandler.post {
            try {
                pointerView?.visibility = View.VISIBLE
                isPointerVisible = true
            } catch (e: Exception) {
                Log.e(TAG, "showPointer error: ${e.message}")
            }
        }
    }

    private fun hidePointer() {
        if (!isPointerAdded || !isPointerVisible) return
        mainHandler.post {
            try {
                pointerView?.visibility = View.GONE
                isPointerVisible = false
            } catch (e: Exception) {
                Log.e(TAG, "hidePointer error: ${e.message}")
            }
        }
    }

    private fun flashPointer() {
        mainHandler.post {
            try {
                pointerView?.alpha = 0.2f
                mainHandler.postDelayed({
                    pointerView?.alpha = 1.0f
                }, 120)
            } catch (e: Exception) {
                Log.e(TAG, "flashPointer error: ${e.message}")
            }
        }
    }

    private fun setPointerColor(color: Int) {
        if (lastPointerColor == color) return
        mainHandler.post {
            try {
                if (stopped || lastPointerColor == color) return@post
                val inner = pointerView?.findViewById<View>(R.id.innerDot)
                if (color == android.graphics.Color.TRANSPARENT) {
                    // Reset to default red
                    inner?.setBackgroundResource(R.drawable.pointer_inner)
                } else {
                    val drawable = android.graphics.drawable.GradientDrawable()
                    drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                    drawable.setColor(color)
                    inner?.background = drawable
                }
                lastPointerColor = color
            } catch (e: Exception) {
                Log.e(TAG, "setPointerColor error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------
    // NOTIFICATION
    // -------------------------------------------------------

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, YvoraService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Yvora Running")
                .setContentText("Hand gesture navigation active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Yvora Running")
                .setContentText("Hand gestures active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(openPending)
                .setOngoing(true)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Yvora Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
}