package com.example.airscroll

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
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
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.ByteArrayOutputStream

class AirScrollService : LifecycleService() {

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
    }

    companion object {
        private const val TAG = "AirScrollService"
        private const val CHANNEL_ID = "airscroll_channel"
        private const val NOTIFICATION_ID = 1001

        var instance: AirScrollService? = null
        var statusListener: StatusListener? = null

        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
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
    var screenWidth = 0
        private set
    var screenHeight = 0
        private set

    private var isPointerVisible = false
    private var isPointerAdded = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val smoothAlpha = 0.20f
    private val deadZone = 3f

    // -------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate")

        cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

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
                startForeground(NOTIFICATION_ID, buildNotification())
                setupOverlayWindows()
                // Hide any existing pointer from previous session
                forceHidePointer()
                // Reset gesture recognizer
                gestureRecognizer.reset()
                cameraExecutor.execute { initMediaPipe() }
                mainHandler.postDelayed({ startCamera() }, 500)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
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

        cameraExecutor.shutdown()

        try { handLandmarker?.close() } catch (e: Exception) {
            Log.e(TAG, "landmarker close error: ${e.message}")
        }

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

        if (instance === this) instance = null
        statusListener = null
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
        mainHandler.post {
            try {
                pointerView?.visibility = View.GONE
                isPointerVisible = false
                pointerX = screenWidth / 2f
                pointerY = screenHeight / 2f
                // Also reset pointer color to default
                val inner = pointerView?.findViewById<View>(R.id.innerDot)
                inner?.setBackgroundResource(R.drawable.pointer_inner)
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
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::onHandLandmarkerResult)
                .setErrorListener { e ->
                    Log.e(TAG, "MediaPipe error: ${e.message}")
                }
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
        val previewView = (cameraOverlayView as? FrameLayout)
            ?.getChildAt(0) as? androidx.camera.view.PreviewView

        if (previewView == null) {
            Log.e(TAG, "PreviewView not found")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
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
        try {
            val bitmap = convertToBitmap(imageProxy)
            imageProxy.close()

            if (bitmap == null) return

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }

            val finalBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            val mpImage = BitmapImageBuilder(finalBitmap).build()
            handLandmarker?.detectAsync(mpImage, frameTime)

        } catch (e: Exception) {
            Log.e(TAG, "Frame error: ${e.message}")
            try { imageProxy.close() } catch (_: Exception) {}
        }
    }

    private fun convertToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            when (imageProxy.format) {
                ImageFormat.YUV_420_888 -> yuvToBitmap(imageProxy)
                ImageFormat.JPEG -> jpegToBitmap(imageProxy)
                else -> yuvToBitmap(imageProxy)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Convert error: ${e.message}")
            null
        }
    }

    private fun yuvToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val width = imageProxy.width
            val height = imageProxy.height

            val yPlane = imageProxy.planes[0]
            val uPlane = imageProxy.planes[1]
            val vPlane = imageProxy.planes[2]

            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            val yData = ByteArray(yPlane.buffer.remaining())
            val uData = ByteArray(uPlane.buffer.remaining())
            val vData = ByteArray(vPlane.buffer.remaining())

            yPlane.buffer.get(yData)
            uPlane.buffer.get(uData)
            vPlane.buffer.get(vData)

            val nv21 = ByteArray(width * height * 3 / 2)

            var nv21Index = 0
            for (row in 0 until height) {
                val srcOffset = row * yRowStride
                val len = minOf(width, yData.size - srcOffset)
                if (len <= 0) break
                System.arraycopy(yData, srcOffset, nv21, nv21Index, len)
                nv21Index += width
            }

            val uvHeight = height / 2
            val uvWidth = width / 2
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val vIdx = minOf(
                        row * uvRowStride + col * uvPixelStride,
                        vData.size - 1
                    )
                    val uIdx = minOf(
                        row * uvRowStride + col * uvPixelStride,
                        uData.size - 1
                    )
                    if (nv21Index + 1 < nv21.size) {
                        nv21[nv21Index] = vData[vIdx]
                        nv21[nv21Index + 1] = uData[uIdx]
                        nv21Index += 2
                    }
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val bytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        } catch (e: Exception) {
            Log.e(TAG, "YUV error: ${e.message}")
            null
        }
    }

    private fun jpegToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val buf = imageProxy.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    // -------------------------------------------------------
    // MEDIAPIPE RESULT HANDLER
    // -------------------------------------------------------

    private fun onHandLandmarkerResult(result: HandLandmarkerResult, input: MPImage) {
        val state = gestureRecognizer.processResult(
            result,
            input.width,
            input.height
        )

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
                    AirScrollAccessibilityService.performScroll("up")
                    Log.d(TAG, "SCROLL UP")
                }
                "DOWN" -> {
                    AirScrollAccessibilityService.performScroll("down")
                    Log.d(TAG, "SCROLL DOWN")
                }
                "LEFT" -> {
                    AirScrollAccessibilityService.performScroll("left")
                    Log.d(TAG, "SWIPE LEFT")
                }
                "RIGHT" -> {
                    AirScrollAccessibilityService.performScroll("right")
                    Log.d(TAG, "SWIPE RIGHT")
                }
            }
            flashPointer()
        }

        // Tap
        if (state.triggeredTap) {
            AirScrollAccessibilityService.performTap(pointerX, pointerY)
            flashPointer()
            Log.d(TAG, "Tap at ($pointerX, $pointerY)")
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
        val targetX = normX * screenWidth
        val targetY = normY * screenHeight

        val dx = targetX - pointerX
        val dy = targetY - pointerY

        if (Math.abs(dx) > deadZone || Math.abs(dy) > deadZone) {
            pointerX += dx * smoothAlpha
            pointerY += dy * smoothAlpha
        }

        val cx = pointerX.coerceIn(0f, (screenWidth - 60).toFloat())
        val cy = pointerY.coerceIn(0f, (screenHeight - 60).toFloat())

        mainHandler.post {
            try {
                if (isPointerAdded && isPointerVisible) {
                    pointerParams?.x = cx.toInt()
                    pointerParams?.y = cy.toInt()
                    windowManager?.updateViewLayout(pointerView, pointerParams)
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
        mainHandler.post {
            try {
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
            } catch (e: Exception) {
                Log.e(TAG, "setPointerColor error: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------
    // NOTIFICATION
    // -------------------------------------------------------

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, AirScrollService::class.java).apply {
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
                .setContentTitle("AirScroll Running")
                .setContentText("Hand gestures active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("AirScroll Running")
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
                "AirScroll Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
}