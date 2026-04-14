package com.example.airscroll

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AirScrollForegroundService : LifecycleService(),
    HandLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "AirScrollFGService"
        private const val CHANNEL_ID = "airscroll_channel"
        private const val NOTIFICATION_ID = 1001

        var instance: AirScrollForegroundService? = null

        // Listener so MainActivity can receive UI updates
        var uiListener: DetectionListener? = null
    }

    interface DetectionListener {
        fun onDetectionResult(
            result: HandLandmarkerResult,
            inferenceTime: Long,
            imageWidth: Int,
            imageHeight: Int
        )
        fun onDetectionError(error: String)
    }

    // Binder for MainActivity to connect
    inner class LocalBinder : Binder() {
        fun getService(): AirScrollForegroundService = this@AirScrollForegroundService
    }

    private val binder = LocalBinder()
    private var handLandmarkerHelper: HandLandmarkerHelper? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gestureRecognizer = GestureRecognizer()

    override fun onCreate() {
        super.onCreate()
        instance = this
        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "Service onCreate")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "Service onStartCommand")

        // Init MediaPipe on background thread
        cameraExecutor.execute {
            try {
                handLandmarkerHelper = HandLandmarkerHelper(
                    context = this,
                    listener = this
                )
                handLandmarkerHelper?.setup()
                Log.d(TAG, "HandLandmarker ready")
            } catch (e: Exception) {
                Log.e(TAG, "HandLandmarker init failed: ${e.message}", e)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // Called by MainActivity to give us the PreviewView surface
    fun setSurfaceProvider(provider: Preview.SurfaceProvider?) {
        previewSurfaceProvider = provider
        if (provider != null) {
            startCamera()
        } else {
            stopCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build()
                previewSurfaceProvider?.let {
                    preview.setSurfaceProvider(it)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val helper = handLandmarkerHelper
                    if (helper != null) {
                        helper.detectLiveStream(imageProxy, isFrontCamera = true)
                    } else {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                cameraProvider?.unbindAll()

                // Bind to THIS service lifecycle (LifecycleService)
                // This means camera stays alive as long as service is alive
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                Log.d(TAG, "Camera bound to service lifecycle")

            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            Log.d(TAG, "Camera unbound")
        } catch (e: Exception) {
            Log.e(TAG, "Camera stop error: ${e.message}")
        }
    }

    // MediaPipe results come here
    override fun onResults(
        result: HandLandmarkerResult,
        inferenceTime: Long,
        imageWidth: Int,
        imageHeight: Int
    ) {
        // Process gesture
        val gestureState = gestureRecognizer.processResult(result, imageWidth, imageHeight)

        // Update floating pointer
        val pointerService = FloatingPointerService.instance
        if (gestureState.handDetected && pointerService != null) {
            pointerService.showPointer()
            pointerService.updatePosition(gestureState.pointerX, gestureState.pointerY)
        } else {
            pointerService?.hidePointer()
        }

        // Handle scroll
        if (gestureState.triggeredGesture != null) {
            val dir = gestureState.triggeredGesture.lowercase()
            val ok = AirScrollAccessibilityService.performScroll(dir)
            Log.d(TAG, "Scroll $dir -> $ok")
        }

        // Handle tap
        if (gestureState.triggeredTap && pointerService != null) {
            val tapX = pointerService.getCurrentScreenX()
            val tapY = pointerService.getCurrentScreenY()
            val ok = AirScrollAccessibilityService.performTap(tapX, tapY)
            Log.d(TAG, "Tap at ($tapX, $tapY) -> $ok")
        }

        // Send to UI if MainActivity is visible
        uiListener?.onDetectionResult(result, inferenceTime, imageWidth, imageHeight)
    }

    override fun onError(error: String) {
        Log.e(TAG, "Detection error: $error")
        uiListener?.onDetectionError(error)
    }

    fun resetGestures() {
        gestureRecognizer.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        stopCamera()
        cameraExecutor.shutdown()
        handLandmarkerHelper?.close()
        handLandmarkerHelper = null
        if (instance === this) instance = null
        uiListener = null
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("AirScroll Active")
                .setContentText("Hand gesture detection running in background")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("AirScroll Active")
                .setContentText("Hand gesture detection running in background")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
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
            ).apply {
                description = "AirScroll hand gesture detection"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}