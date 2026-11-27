package com.yvora.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
    private val cursors = Array(2) { HandCursor(it) }
    private val touchArbiter = TouchArbiter()
    private var dualHandMode = true

    private var handLandmarker: HandLandmarker? = null
    private lateinit var cameraExecutor: java.util.concurrent.ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    @Volatile private var cameraRunning = false
    @Volatile private var cameraEpoch = 0L
    private var cameraStarting = false
    private var modelReady = false
    private lateinit var thermalPolicy: ThermalPolicy
    private lateinit var powerManager: PowerManager
    private var receiverRegistered = false
    @Volatile private var analysisIntervalMs = 250L
    private var thermalLevel = 0
    private var captureRateLabel = "Auto"
    private var batteryTenths: Int? = null
    var pauseReason: String? = null
        private set
    val powerStatus: String
        get() {
            val battery = batteryTenths?.let { " • Battery ${it / 10f}°C" } ?: ""
            val level = if (Build.VERSION.SDK_INT >= 29) {
                when (thermalLevel) {
                    0 -> "Normal"
                    1 -> "Light"
                    2 -> "Moderate"
                    else -> "Severe or higher"
                }
            } else "Thermal status unavailable"
            return (pauseReason ?: "Thermal: $level • Analysis target ${1000 / analysisIntervalMs} /s • Camera $captureRateLabel") + battery
        }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!stopped && started) refreshPowerState()
        }
    }
    private val powerTick = object : Runnable {
        override fun run() {
            if (stopped || !started) return
            refreshPowerState()
            mainHandler.postDelayed(this, 2000L)
        }
    }

     internal fun diagnosticState(): String = "engine_running=$cameraRunning; calibration=$calibrationMode; thermal_status=${if (Build.VERSION.SDK_INT >= 29) thermalLevel.toString() else "unavailable"}; battery_temp_tenths_c=${batteryTenths ?: "unavailable"}; analysis_target_ms=$analysisIntervalMs; analysis_resolution=${analysisWidth}x$analysisHeight; distance_detail=$usingDistanceDetail; accessibility_connected=${YvoraAccessibilityService.isEnabled()}"
    var gestureDispatch = "No command yet"
        private set
    var screenWidth = 0
        private set
    var screenHeight = 0
        private set


    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false
    @Volatile private var stopped = false
    private val resultPending = AtomicBoolean(false)
    private lateinit var framePacer: FramePacer
    private var lowRamDevice = false
    private var cameraDetail = CameraDetail.AUTO
    private var calibrationMode = false
    val isCalibrating: Boolean get() = calibrationMode
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
    private var ranges=HandRanges()
    private var dualMode=DualMode.NORMAL
    private var motionSlot=0
    private var motionTemplates:Map<AiAction,MotionPattern> = emptyMap()
    private val motionClutch=MotionClutch()
    private var motionTraining:MotionTraining?=null
    internal fun startMotionTraining(slot:Int,action:AiAction):Boolean {
        if(!cameraRunning || !calibrationMode || slot !in 0..1 || action !in trajectoryActions || YvoraAccessibilityService.aiActive() || YvoraAccessibilityService.zoomBusy()) return false
        motionTraining=MotionTraining(slot,action,SystemClock.uptimeMillis());return true
    }
    internal fun motionTrainingCount()=motionTraining?.count ?: 0
    internal fun motionTrainingStatus()=motionTraining?.status ?: "No active capture"
    internal fun motionTrainingActive()=motionTraining!=null
    internal fun cancelMotionTraining(){motionTraining=null}
    internal fun finishMotionTraining():MotionPattern? {
        val t=motionTraining ?: return null;motionTraining=null
        return if(cameraRunning && calibrationMode) t.finish(SystemClock.uptimeMillis()) else null
    }
    private val zoomEngine=FuzzyZoom()
    private val rotationEngine=FuzzyRotation()
    private var zoomDegree=0f
    private var zoomMessage="Off"
    internal fun zoomActiveMode()=dualMode!=DualMode.NORMAL
    internal fun transformModeName()=dualMode.name
    internal fun zoomMembership()=zoomDegree
    internal fun zoomTrace()=zoomMessage
    private var poseTraining:PoseTraining?=null
    internal fun beginPoseTraining(slot:Int):Boolean {
        if(slot !in 0..1 || !cameraRunning || !calibrationMode || YvoraAccessibilityService.aiActive() || YvoraAccessibilityService.zoomBusy()) return false
        poseTraining=PoseTraining(slot,SystemClock.uptimeMillis())
        return true
    }
    internal fun trainingCount()=poseTraining?.count ?: 0
    internal fun trainingActive()=poseTraining!=null
    internal fun cancelPoseTraining(){poseTraining=null}
    internal fun finishPoseTraining():PoseFeatures? {
        val training=poseTraining ?: return null
        poseTraining=null
        return if(calibrationMode && cameraRunning) training.finish(SystemClock.uptimeMillis()) else null
    }
    internal fun reloadPersonalPose(slot:Int) {
        if(slot !in 0..1 || !calibrationMode) return
        cursors[slot].recognizer.setPersonalTemplate(CameraSettings.readPersonalPose(this,slot))
        cursors[slot].reset()
    }
    private var fuzzyTraces=Array(2){FuzzyHandTrace()}
    private val identity=FuzzyHandIdentity()
    private var identityDegrees=FloatArray(2)
    private var identityAmbiguous=false
    private var predictionDegrees=FloatArray(2)
    private var conflictStatus="None"
    internal fun identityDegree(slot:Int)=identityDegrees[slot]
    internal fun predictionDegree(slot:Int)=predictionDegrees[slot]
    internal fun conflictTrace()=conflictStatus
    internal fun identityUncertain()=identityAmbiguous
    private var lastFuzzyAt=-1L
    internal fun fuzzyTrace(slot:Int):FuzzyHandTrace =
        if(cameraRunning && lastFuzzyAt>=0 && SystemClock.uptimeMillis()-lastFuzzyAt<=750L) fuzzyTraces[slot] else FuzzyHandTrace()
    internal fun fuzzyThermal():Float=thermalPolicy.fuzzyHeat
    internal fun fuzzyInterval():Long=analysisIntervalMs
    private fun clearFuzzyTraces() { cancelPoseTraining();cancelMotionTraining();motionClutch.reset();lastFuzzyAt=-1L;fuzzyTraces=Array(2){FuzzyHandTrace()};identity.reset();identityDegrees.fill(0f);predictionDegrees.fill(0f);identityAmbiguous=false;conflictStatus="None" }
    private val trackingWatchdog=object:Runnable {
        override fun run() {
            if(stopped) return
            if(cameraRunning && lastFuzzyAt>=0 && SystemClock.uptimeMillis()-lastFuzzyAt>750L) {
                clearFuzzyTraces();forceHidePointer()
                sampleX=Float.NaN;sampleY=Float.NaN
                sampleXs.fill(Float.NaN);sampleYs.fill(Float.NaN);cancelRangeCapture();clearFuzzyTraces()
                statusListener?.onStatus(false,"Tracking timed out",false,false,"Open palm to re-arm")
            }
            mainHandler.postDelayed(this,150L)
        }
    }
    private val sampleXs=FloatArray(2){Float.NaN}
    private val sampleYs=FloatArray(2){Float.NaN}
    private var capture:RangeCapture?=null
    internal fun sampleX(slot:Int)=sampleXs[slot]
    internal fun sampleY(slot:Int)=sampleYs[slot]
    internal fun captureCount():Int=capture?.count ?: 0
    internal fun captureActive():Boolean=capture!=null
    internal fun beginRangeCapture(slot:Int):Boolean {
        if(slot !in 0..1 || !cameraRunning || !calibrationMode || YvoraAccessibilityService.aiActive()) return false
        capture=RangeCapture(slot,SystemClock.uptimeMillis())
        return true
    }
    internal fun cancelRangeCapture() { capture=null }
    internal fun finishRangeCapture():ControlRange? {
        val active=capture ?: return null
        capture=null
        return if(cameraRunning && calibrationMode) active.finish(SystemClock.uptimeMillis()) else null
    }
    internal fun updateControlRanges(value:HandRanges) {
        if(ranges==value) return
        if((0..1).any { ranges.effective(it)!=value.effective(it) }) {
            cancelDrag()
            cursors.forEachIndexed { slot,cursor ->
                cursor.reset();cursor.recognizer.setControlRange(value.effective(slot))
            }
        }
        ranges=value
    }

    internal fun setCalibrationMode(enabled: Boolean) {
        if (calibrationMode == enabled) return
        calibrationMode = enabled
        cancelMotionTraining()
        cancelPoseTraining()
        cancelRangeCapture()
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
        dualHandMode = CameraSettings.readDualHand(this)
        val requestedMode=CameraSettings.readDualMode(this)
        dualMode=if(!dualHandMode && requestedMode in listOf(DualMode.ZOOM,DualMode.ROTATE)) DualMode.NORMAL else requestedMode
        motionSlot=CameraSettings.readMotionSlot(this)
        motionTemplates=CameraSettings.readMotions(this,motionSlot)
        ranges=CameraSettings.readRanges(this)
        cursors.forEachIndexed { slot,cursor ->
            cursor.recognizer.setPersonalTemplate(CameraSettings.readPersonalPose(this,slot))
            cursor.recognizer.setControlRange(ranges.effective(slot))
            cursor.recognizer.configureAssist(CameraSettings.readAssist(this), CameraSettings.readAssistLevel(this))
        }
        framePacer = FramePacer(if (lowRamDevice) 100L else 66L)
        thermalPolicy = ThermalPolicy(lowRamDevice)
        powerManager = getSystemService(POWER_SERVICE) as PowerManager

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

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
                registerPowerMonitoring()
                mainHandler.post(trackingWatchdog)
                cameraExecutor.execute {
                    if (!stopped) initMediaPipe()
                    val ready = handLandmarker != null
                    mainHandler.post {
                        if (!stopped) {
                            modelReady = ready
                            if (ready) refreshPowerState() else stopSelf()
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
        YvoraAccessibilityService.stopAi("Yvora engine stopped")
        if (receiverRegistered) {
            unregisterReceiver(powerReceiver)
            receiverRegistered = false
        }
        stopCamera()
        cancelDrag()
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        cursors.forEach { it.hide() }

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

        cursors.forEach { it.remove() }

        if (instance === this) {
            instance = null
            statusListener?.onStopped()
        }
    }

    // -------------------------------------------------------
    // OVERLAY SETUP
    // -------------------------------------------------------

    private fun setupOverlayWindows() {
        if (!Settings.canDrawOverlays(this)) return
        val manager=getSystemService(WINDOW_SERVICE) as WindowManager
        cursors.forEach { it.attach(this,manager) }
    }

    fun forceHidePointer() {
        motionClutch.reset();zoomEngine.reset();rotationEngine.reset();zoomDegree=0f;zoomMessage=if(dualMode!=DualMode.NORMAL) "Open both palms to arm" else "Off"
        cancelDrag()
        cursors.forEach { it.reset() }
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
                .setNumHands(if (dualHandMode) 2 else 1)
                .setRunningMode(RunningMode.VIDEO)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(this, options)
            Log.d(TAG, "HandLandmarker ready")

        } catch (e: Exception) {
            DiagnosticCounters.error()
            Log.e(TAG, "MediaPipe init failed: ${e.message}", e)
        }
    }

    // -------------------------------------------------------
    // CAMERA
    // -------------------------------------------------------

    private fun registerPowerMonitoring() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(this, powerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        powerTick.run()
    }

    private fun refreshPowerState() {
        if (stopped) return
        val now = SystemClock.uptimeMillis()
        thermalLevel = if (Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else 0
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryTenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it in 0..1000 }
        thermalPolicy.environment(now, thermalLevel, batteryTenths, powerManager.isPowerSaveMode)
        analysisIntervalMs = thermalPolicy.interval(now)
        val reason = when {
            !powerManager.isInteractive -> "Screen off • Camera paused"
            thermalPolicy.cooling -> "Cooling • Camera paused"
            else -> null
        }
        val changed = reason != pauseReason
        pauseReason = reason
        if (reason != null) {
            if (thermalPolicy.cooling || !powerManager.isInteractive) YvoraAccessibilityService.stopAi("AI paused for screen or thermal safety")
            if (cameraRunning || cameraStarting) stopCamera()
            statusListener?.onStatus(false, "Paused", false, false, reason)
        } else if (modelReady && !cameraRunning && !cameraStarting) {
            startCamera()
        }
        if (changed) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun stopCamera() {
        cameraRunning = false
        cameraStarting = false
        cameraEpoch++
        val analysis = analysisUseCase
        analysisUseCase = null
        try {
            analysis?.clearAnalyzer()
            if (analysis != null) cameraProvider?.unbind(analysis)
        } catch (e: Exception) {
            Log.w(TAG, "Camera unbind failed", e)
        }
        forceHidePointer()
        thermalPolicy.observe(SystemClock.uptimeMillis(), false, 0f, 0f, 0, false)
        sampleX = Float.NaN
        sampleY = Float.NaN
        sampleXs.fill(Float.NaN);sampleYs.fill(Float.NaN);cancelRangeCapture();clearFuzzyTraces()
        resultRate = 0f
        frameAgeMs = -1L
        rateWindowStart = -1L
        rateWindowCount = 0
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun buildAnalysis(provider: ProcessCameraProvider, selector: CameraSelector, epoch: Long, limitRate: Boolean): ImageAnalysis {
        val builder = ImageAnalysis.Builder()
            .setTargetResolution(cameraDetail.requestedSize(lowRamDevice))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        captureRateLabel = "Auto"
        if (limitRate) {
            try {
                // Only request a range advertised by the selected front camera.
                val info = selector.filter(provider.availableCameraInfos).firstOrNull()
                val ranges = info?.let {
                    Camera2CameraInfo.from(it).getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                }
                val range = ranges?.filter { it.lower >= 8 && it.upper in 12..20 }
                    ?.minWithOrNull(compareBy<android.util.Range<Int>> { it.upper }.thenByDescending { it.lower })
                if (range != null) {
                    Camera2Interop.Extender(builder).setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                    captureRateLabel = "${range.lower}–${range.upper} fps requested"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Camera FPS request unavailable; using automatic capture", e)
            }
        }
        return builder.build().also { analysis ->
            analysis.setAnalyzer(cameraExecutor) { imageProxy -> processFrame(imageProxy, epoch) }
        }
    }

    private fun startCamera() {
        if (stopped || pauseReason != null || cameraRunning || cameraStarting || !modelReady) return
        cameraStarting = true
        val epoch = ++cameraEpoch
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (stopped || epoch != cameraEpoch || pauseReason != null) return@addListener
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val selector = CameraSelector.DEFAULT_FRONT_CAMERA
                val imageAnalysis = buildAnalysis(provider, selector, epoch, true)
                analysisUseCase = imageAnalysis
                cameraRunning = true
                try {
                    provider.bindToLifecycle(this, selector, imageAnalysis)
                } catch (e: Exception) {
                    if (captureRateLabel == "Auto") throw e
                    Log.w(TAG, "Retrying without camera FPS override", e)
                    imageAnalysis.clearAnalyzer()
                    provider.unbind(imageAnalysis)
                    val fallback = buildAnalysis(provider, selector, epoch, false)
                    analysisUseCase = fallback
                    provider.bindToLifecycle(this, selector, fallback)
                }
                cameraStarting = false
                Log.d(TAG, "Analysis-only camera started")
            } catch (e: Exception) {
                DiagnosticCounters.error()
                Log.e(TAG, "Camera start failed", e)
                stopCamera()
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // -------------------------------------------------------
    // FRAME PROCESSING
    // -------------------------------------------------------

    private fun processFrame(imageProxy: ImageProxy, epoch: Long) {
        val frameTime = SystemClock.uptimeMillis()
        val detector = handLandmarker
        // Drop before doing expensive conversion. Only one UI result can be pending.
        if (stopped || !cameraRunning || epoch != cameraEpoch || detector == null || resultPending.get() || !framePacer.canStart(frameTime, analysisIntervalMs)) {
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
            if (!stopped && cameraRunning && epoch == cameraEpoch && resultPending.compareAndSet(false, true)) {
                val posted = mainHandler.post {
                    try {
                        if (!stopped && cameraRunning && epoch == cameraEpoch) {
                            if (SystemClock.uptimeMillis() - frameTime <= 500L) {
                                recordTelemetry(frameTime)
                                onHandLandmarkerResult(result, width, height)
                            } else {
                                DiagnosticCounters.stale()
                                forceHidePointer()
                                sampleX = Float.NaN
                                sampleY = Float.NaN
                                sampleXs.fill(Float.NaN);sampleYs.fill(Float.NaN);cancelRangeCapture();clearFuzzyTraces()
                                statusListener?.onStatus(false, "None", false, false, "")
                            }
                        }
                    } finally { resultPending.set(false) }
                }
                if (!posted) resultPending.set(false)
            }
        } catch (e: Exception) {
            DiagnosticCounters.error()
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
        val now=SystemClock.uptimeMillis()
        val observations=result.landmarks().mapIndexed { index,points ->
            val label=result.handednesses().getOrNull(index)?.firstOrNull()
            val ids=intArrayOf(0,5,9,13,17)
            val px=if(points.size>=21) ids.sumOf { points[it].x().toDouble() }.toFloat()/5f else Float.NaN
            val py=if(points.size>=21) ids.sumOf { points[it].y().toDouble() }.toFloat()/5f else Float.NaN
            HandObservation(label?.categoryName(),label?.score() ?: 0f,px,py)
        }
        val identification=identity.update(observations,now)
        val assignment=identification.indices
        identityDegrees=identification.degrees;identityAmbiguous=identification.ambiguous
        predictionDegrees.fill(0f);conflictStatus="None"
        val states=cursors.mapIndexed { slot,cursor ->
            cursor.recognizer.processResult(result,width,height,handIndex=assignment[slot],frameAgeMs=frameAgeMs)
        }
        fuzzyTraces=Array(2){ cursors[it].recognizer.fuzzyTrace }
        lastFuzzyAt=now
        fun motionSample(slot:Int):ZoomSample? {
            val state=states[slot];val tr=fuzzyTraces[slot]
            return if(state.handDetected && !identityAmbiguous) ZoomSample(state.rawX,state.rawY,state.isPointerMode,tr.pinchRatio,FuzzyCore.and(tr.quality,identityDegrees[slot])) else null
        }
        val motionAspect=height.toFloat()/width.coerceAtLeast(1)
        motionTraining?.let { t ->
            if(t.expired(now) || !calibrationMode || YvoraAccessibilityService.aiActive()) cancelMotionTraining()
            else t.observe(motionSample(t.slot),now,motionAspect)
        }
        poseTraining?.let { session ->
            if(session.expired(now) || !calibrationMode || YvoraAccessibilityService.aiActive()) cancelPoseTraining()
            else if(!identityAmbiguous) session.add(fuzzyTraces[session.slot],now)
        }
        val detected=states.filter { it.handDetected }
        val first=detected.firstOrNull()
        DiagnosticCounters.result(first!=null,frameAgeMs)
        // Use the moving hand and a second movement check to avoid cancellation of opposite motion.
        var moving=false
        for(i in 0..1) {
            val state=states[i];val cursor=cursors[i]
            if(state.handDetected && cursor.initialized &&
                (kotlin.math.abs(state.pointerX*(screenWidth-1)-cursor.x)+
                 kotlin.math.abs(state.pointerY*(screenHeight-1)-cursor.y)) > screenWidth*0.012f) moving=true
        }
        thermalPolicy.observe(now,first!=null,first?.rawX ?: 0f,first?.rawY ?: 0f,
            detected.sumOf { it.fingersUp },moving || detected.any { it.isTapPinching || it.gestureArmed })
        analysisIntervalMs=thermalPolicy.interval(now)
        sampleX=first?.rawX ?: Float.NaN
        sampleY=first?.rawY ?: Float.NaN
        for(slot in 0..1) {
            sampleXs[slot]=states[slot].rawX
            sampleYs[slot]=states[slot].rawY
        }
        capture?.let { active ->
            if(active.expired(now) || !calibrationMode || YvoraAccessibilityService.aiActive()) capture=null
            else {
                val state=states[active.slot]
                if(state.handDetected && state.isPointerMode && !state.isTapPinching)
                    active.add(state.rawX,state.rawY,now)
            }
        }
        analysisWidth=width;analysisHeight=height
        if(YvoraAccessibilityService.aiActive()) {
            motionClutch.reset();zoomEngine.reset();rotationEngine.reset();zoomDegree=0f
            // Explicit pinch or recognized swipe interrupts running AI, never injects a competing stroke.
            if(!calibrationMode && YvoraAccessibilityService.aiRunning() && detected.any { it.pinchAction==PinchAction.PENDING || it.triggeredGesture!=null }) {
                YvoraAccessibilityService.stopAi("AI stopped by manual gesture")
            }
            cursors.forEach { it.hide() }
            statusListener?.onStatus(first!=null,"AI navigation",false,false,"AI owns input; use Stop AI or a manual pinch to interrupt")
            return
        }
        if(calibrationMode) {
            // Reset touch state, but keep identity and prediction history available for lab.
            for(i in 0..1) if(states[i].handDetected && states[i].isPointerMode) {
                val state=states[i]
                predictionDegrees[i]=cursors[i].predictor.update(state.pointerX,state.pointerY,now,frameAgeMs,fuzzyTraces[i].quality).degree
            } else cursors[i].predictor.reset()
            cancelDrag()
            cursors.forEach { it.hide();it.armed=false }
            statusListener?.onStatus(first!=null,"Calibration",false,false,"Safe calibration")
            return
        }

        if(dualMode==DualMode.CUSTOM) {
            val result=motionClutch.update(motionSample(motionSlot),now,motionAspect)
            val match=result.pattern?.let{MotionSimilarity.choose(it,motionTemplates)}
            zoomDegree=match?.second ?: 0f
            zoomMessage=if(result.pattern!=null) {
                if(match==null) "No unambiguous saved trajectory" else "Matched "+match.first.name
            } else result.status
            for(i in 0..1) {
                val state=states[i];val cursor=cursors[i]
                cursor.armed=false;cursor.predictor.reset()
                if(i==motionSlot && state.handDetected && !identityAmbiguous) {
                    cursor.position(state.pointerX,state.pointerY,screenWidth,screenHeight);cursor.show()
                } else cursor.hide()
            }
            if(match!=null) {
                val direction=match.first.name.removePrefix("SWIPE_").lowercase(java.util.Locale.ROOT)
                val ok=YvoraAccessibilityService.performCustomScroll(direction)
                gestureDispatch=if(ok) "Custom "+match.first.name+" dispatched" else "Custom gesture rejected"
                if(!ok) DiagnosticCounters.rejected()
            }
            statusListener?.onStatus(first!=null,"Custom trajectories",false,true,zoomMessage+" | "+gestureDispatch)
            return
        }

        if(dualMode!=DualMode.NORMAL) {
            // Dedicated mode: never let either individual recognizer inject a tap/drag/swipe.
            fun zoomSample(slot:Int):ZoomSample? {
                val state=states[slot];val tr=fuzzyTraces[slot]
                return if(state.handDetected && !identityAmbiguous) ZoomSample(state.rawX,state.rawY,
                    state.isPointerMode,tr.pinchRatio,FuzzyCore.and(tr.quality,identityDegrees[slot])) else null
            }
            val a=zoomSample(0);val b=zoomSample(1)
            val aspect=height.toFloat()/width.coerceAtLeast(1)
            val zoom=if(dualMode==DualMode.ZOOM) zoomEngine.update(a,b,now,aspect) else null
            val rotation=if(dualMode==DualMode.ROTATE) rotationEngine.update(a,b,now,aspect) else null
            zoomDegree=zoom?.degree ?: rotation?.degree ?: 0f
            zoomMessage=zoom?.message ?: rotation?.message ?: "Open both palms to arm"
            val action=zoom?.action!=null || rotation?.action!=null
            for(i in 0..1) {
                val state=states[i];val cursor=cursors[i]
                cursor.armed=false;cursor.predictor.reset()
                if(state.handDetected && !identityAmbiguous) {
                    cursor.position(state.pointerX,state.pointerY,screenWidth,screenHeight)
                    cursor.color(if(action) android.graphics.Color.GREEN else android.graphics.Color.CYAN)
                    cursor.show()
                } else cursor.hide()
            }
            if(action) {
                val za=zoom?.action;val ra=rotation?.action
                val ok=if(za!=null) YvoraAccessibilityService.performZoom(za)
                    else ra!=null && YvoraAccessibilityService.performRotation(ra)
                gestureDispatch=if(ok) "${dualMode.name} ${za?.name ?: ra?.name} dispatched"
                    else "Transform rejected (busy or accessibility unavailable)"
                if(!ok) DiagnosticCounters.rejected()
            }
            statusListener?.onStatus(first!=null,"${dualMode.name} mode",false,true,zoomMessage+" | "+gestureDispatch)
            return
        }

        if(identityAmbiguous) {
            forceHidePointer()
            conflictStatus="Identity ambiguous: input suspended"
            statusListener?.onStatus(false,"Identity uncertain",false,false,"Separate hands; open palm to re-arm")
            return
        }
        // Handle lost ownership before any new action. Never switch a held touch to another hand.
        touchArbiter.owner?.let { slot ->
            if(!states[slot].handDetected) {
                YvoraAccessibilityService.endDrag(cancel=true)
                touchArbiter.release(slot,now)
                cursors[slot].reset()
            }
        }
        // Update both independent cursors even while another hand owns the touch stream.
        val previouslyArmed=BooleanArray(2){cursors[it].armed}
        for(i in 0..1) {
            val state=states[i];val cursor=cursors[i]
            if(!state.handDetected) {
                cursor.reset()
                continue
            }
            if(!cursor.armed) {
                if(state.isPointerMode && !state.isTapPinching) {
                    cursor.armed=true
                    cursor.recognizer.reset()
                    cursor.position(state.pointerX,state.pointerY,screenWidth,screenHeight)
                    cursor.color();cursor.show()
                }
                continue
            }
            if(state.isPointerMode && touchArbiter.owner!=i) {
                val prediction=cursor.predictor.update(state.pointerX,state.pointerY,now,frameAgeMs,
                    FuzzyCore.and(fuzzyTraces[i].quality,identityDegrees[i]))
                predictionDegrees[i]=prediction.degree
                cursor.position(prediction.x,prediction.y,screenWidth,screenHeight)
                cursor.color()
            } else cursor.predictor.reset()
            cursor.show()
        }

        // Current owner first, otherwise choose unambiguous fuzzy candidate. Android dispatchGesture
        // cancels other streams; second hand remains free to move its cursor, not inject a touch.
        val owner=touchArbiter.owner
        val candidates=(0..1).mapNotNull { i ->
            val state=states[i];val tr=fuzzyTraces[i]
            if(!state.handDetected || !previouslyArmed[i] || !cursors[i].armed) null
            else if(state.pinchAction==PinchAction.PENDING) IntentCandidate(i,true,
                FuzzyCore.and(tr.quality,FuzzyCore.fall(tr.pinchRatio,.12f,.34f)))
            else if(state.triggeredGesture!=null) IntentCandidate(i,false,
                FuzzyCore.and(tr.quality,if(state.triggeredGesture=="LEFT" || state.triggeredGesture=="RIGHT") tr.swipe else .8f))
            else null
        }
        val chosen=FuzzyConflict.choose(owner,candidates)
        if(candidates.size>1 || owner!=null && candidates.any { it.slot!=owner }) {
            conflictStatus=if(chosen==null) "Ambiguous competing actions: abstain" else "Hand ${if(chosen==0) "left" else "right"} owns action"
            candidates.filter{it.slot!=chosen}.forEach { losing ->
                cursors[losing.slot].armed=false;cursors[losing.slot].recognizer.reset();cursors[losing.slot].predictor.reset()
            }
        }
        val order=if(chosen==1) intArrayOf(1,0) else intArrayOf(0,1)
        for(i in order) {
            val state=states[i];val cursor=cursors[i]
            if(!state.handDetected || !cursor.armed || !previouslyArmed[i]) continue
            if(state.pinchAction!=PinchAction.NONE) {
                when(state.pinchAction) {
                    PinchAction.PENDING -> {
                        if(touchArbiter.owner!=i) {
                            if(YvoraAccessibilityService.dragBusy() || !touchArbiter.claim(i,now)) {
                                cursor.armed=false
                                cursor.recognizer.reset()
                                continue
                            }
                            cursor.anchorX=cursor.x;cursor.anchorY=cursor.y
                            cursor.startX=state.pointerX;cursor.startY=state.pointerY
                        }
                        cursor.color(android.graphics.Color.CYAN)
                    }
                    PinchAction.TAP -> if(touchArbiter.owner==i) {
                        if(state.triggeredTap) {
                            val ok=YvoraAccessibilityService.performTap(cursor.anchorX,cursor.anchorY)
                            gestureDispatch=if(ok) "Tap dispatched" else "Tap rejected"
                            if(!ok) DiagnosticCounters.rejected()
                        }
                        touchArbiter.release(i,now)
                        cursor.color()
                    }
                    PinchAction.START_DRAG -> if(touchArbiter.owner==i) {
                        DiagnosticCounters.dragStart()
                        cursor.dragging=YvoraAccessibilityService.beginDrag(cursor.anchorX,cursor.anchorY)
                        gestureDispatch=if(cursor.dragging) "Hold: waiting for Android" else "Drag rejected"
                        if(!cursor.dragging) DiagnosticCounters.rejected()
                    }
                    PinchAction.MOVE_DRAG -> if(touchArbiter.owner==i && cursor.dragging) {
                        if(YvoraAccessibilityService.dragBusy()) {
                            val x=cursor.anchorX+(state.pointerX-cursor.startX)*screenWidth
                            val y=cursor.anchorY+(state.pointerY-cursor.startY)*screenHeight
                            cursor.position(x/(screenWidth-1).coerceAtLeast(1),y/(screenHeight-1).coerceAtLeast(1),screenWidth,screenHeight)
                            YvoraAccessibilityService.moveDrag(cursor.x,cursor.y)
                            val ready=YvoraAccessibilityService.dragReady()
                            cursor.color(if(ready) android.graphics.Color.GREEN else android.graphics.Color.CYAN)
                            gestureDispatch=if(ready) "Dragging: touch held" else "Hold: waiting for Android"
                        } else {
                            cursor.dragging=false
                            DiagnosticCounters.rejected()
                            gestureDispatch="Drag interrupted by Android"
                        }
                    }
                    PinchAction.DROP, PinchAction.CANCEL -> if(touchArbiter.owner==i) {
                        if(state.pinchAction==PinchAction.DROP) DiagnosticCounters.dragDrop()
                        if(cursor.dragging) YvoraAccessibilityService.endDrag(cancel=state.pinchAction==PinchAction.CANCEL)
                        cursor.dragging=false
                        touchArbiter.release(i,now)
                        cursor.color()
                    }
                    PinchAction.NONE -> Unit
                }
                continue
            }
            val direction=state.triggeredGesture
            if(direction!=null && touchArbiter.owner==null && !YvoraAccessibilityService.dragBusy() && touchArbiter.claim(i,now)) {
                if(direction=="LEFT" || direction=="RIGHT") DiagnosticCounters.swipe()
                val ok=YvoraAccessibilityService.performScroll(direction.lowercase())
                gestureDispatch=if(ok) "$direction dispatched" else "$direction rejected"
                if(!ok) DiagnosticCounters.rejected()
                touchArbiter.release(i,now)
                cursor.color(android.graphics.Color.YELLOW)
            } else if(state.gestureArmed && touchArbiter.owner!=i) {
                cursor.color(android.graphics.Color.YELLOW)
            }
        }
        val active=touchArbiter.owner?.let { states[it] } ?: first
        statusListener?.onStatus(first!=null,active?.lastGesture ?: "None",
            detected.any { it.isTapPinching },detected.any { it.gestureArmed },
            "Hands: ${detected.size}/${if(dualHandMode) 2 else 1} | "+detected.joinToString(" / ") { it.debugInfo })
    }

    private fun cancelDrag() {
        YvoraAccessibilityService.endDrag(cancel=true)
        touchArbiter.reset()
        cursors.forEach { it.dragging=false }
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
                .setContentTitle(localized("Yvora Running","Yvora يعمل"))
                .setContentText(pauseReason?.let(::uiStatus) ?: localized("Hand gesture navigation active","التنقل بإيماءات اليد نشط"))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_delete, localized("Stop","إيقاف"), stopPending)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(localized("Yvora Running","Yvora يعمل"))
                .setContentText(pauseReason?.let(::uiStatus) ?: localized("Hand gestures active","إيماءات اليد نشطة"))
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
                localized("Yvora Service","خدمة Yvora"),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }
}