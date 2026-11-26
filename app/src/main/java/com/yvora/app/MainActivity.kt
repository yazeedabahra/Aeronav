package com.yvora.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var txtOverlay: TextView
    private lateinit var txtAccessibility: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtEngineState: TextView
    private lateinit var txtResultRate: TextView
    private lateinit var txtFrameAge: TextView
    private lateinit var txtProfile: TextView
    private lateinit var calibrationView: CalibrationView
    private lateinit var rangeSummary: TextView
    private lateinit var calibrationStatus: TextView
    private lateinit var detailStatus: TextView
    private lateinit var widthSeek: SeekBar
    private lateinit var heightSeek: SeekBar
    private lateinit var centerXSeek: SeekBar
    private lateinit var centerYSeek: SeekBar
    private lateinit var distanceSwitch: Switch
    private lateinit var safeSwitch: Switch
    private var restoringRange = false
    private var lastSampleUpdate = -1000L
    private var lastUiUpdate = -1000L
    private var previousStatus = ""
    private fun tint(id: Int) = ContextCompat.getColor(this, id)
    private lateinit var btnOverlay: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnStart: Button
    private lateinit var btnRunBg: Button
    private lateinit var btnStop: Button
    private lateinit var btnTestUp: Button
    private lateinit var btnTestDown: Button
    private lateinit var btnTestTap: Button

    companion object {
        private const val CAMERA_CODE = 100
        private const val OVERLAY_CODE = 101
    }

    // StatusListener with 4 parameters matching YvoraService.StatusListener

    private val statusListener = object : YvoraService.StatusListener {
        override fun onStatus(
            handDetected: Boolean,
            gesture: String,
            isTapPinching: Boolean,
            isGestureMode: Boolean,
            debugInfo: String
        ) {
            runOnUiThread {
                val service = YvoraService.instance ?: return@runOnUiThread
                val mode = when {
                    service.pauseReason != null -> service.pauseReason!!
                    !handDetected -> "Searching for hand"
                    service.isCalibrating -> "Safe calibration • Gestures disabled"
                    service.gestureDispatch == "Dragging: touch held" && isTapPinching -> "Dragging • Open fingers to drop"
                    isTapPinching -> "Pinch held"
                    isGestureMode -> "Gesture armed"
                    else -> "Pointer tracking"
                }
                val summary = "$mode • Last: $gesture"
                val now = SystemClock.uptimeMillis()
                if (now - lastSampleUpdate >= 250L) {
                    lastSampleUpdate = now
                    calibrationView.setSample(service.sampleX, service.sampleY,
                        service.analysisWidth, service.analysisHeight)
                    calibrationStatus.text = if (!handDetected) "No hand detected"
                        else if (service.sampleX.isFinite() && service.sampleY.isFinite())
                            "Hand detected • ${(service.sampleX * 100).toInt()}% X / ${(service.sampleY * 100).toInt()}% Y"
                        else "Hand detected"
                    detailStatus.text = if (distanceSwitch.isChecked != service.usingDistanceDetail)
                        "Stop and restart engine to apply detail mode."
                        else if (service.analysisWidth > 0)
                            "Active analysis: ${service.analysisWidth} × ${service.analysisHeight}"
                        else "Waiting for camera."
                }
                if (summary != previousStatus || now - lastUiUpdate >= 250L) {
                    previousStatus = summary
                    lastUiUpdate = now
                    txtEngineState.text = if (service.pauseReason != null) "Paused" else if (handDetected) "Tracking active" else "Searching"
                    txtEngineState.setTextColor(tint(if (handDetected) R.color.accent_green else R.color.accent))
                    txtStatus.text = "$summary\n$debugInfo\n${service.gestureDispatch}"
                    val rate = service.resultRate
                    txtResultRate.text = if (rate > 0f) String.format(java.util.Locale.ROOT, "%.1f", rate) + " /s" else "—"
                    txtFrameAge.text = if (service.frameAgeMs >= 0) "${service.frameAgeMs} ms" else "—"
                    txtProfile.text = service.powerStatus
                    btnStart.text = "ENGINE\nACTIVE"
                }
            }
        }

        override fun onStopped() {
            runOnUiThread { updateStatuses() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupButtons()
        setupCalibration()
        findViewById<Button>(R.id.btnAiCore).setOnClickListener { startActivity(Intent(this, AiLabActivity::class.java)) }
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatuses()
        YvoraService.statusListener = statusListener
        YvoraService.instance?.let { safeSwitch.isChecked = it.isCalibrating }
    }

    override fun onPause() {
        super.onPause()
        if (safeSwitch.isChecked) {
            // Keep input suppressed until the user explicitly exits safe mode or stops.
            YvoraService.instance?.setCalibrationMode(true)
        }
        YvoraService.statusListener = null
    }

    private fun bindViews() {
        txtOverlay       = findViewById(R.id.txtOverlayStatus)
        txtAccessibility = findViewById(R.id.txtAccessibilityStatus)
        txtStatus        = findViewById(R.id.txtStatus)
        txtEngineState   = findViewById(R.id.txtEngineState)
        txtResultRate    = findViewById(R.id.txtResultRate)
        txtFrameAge      = findViewById(R.id.txtFrameAge)
        txtProfile       = findViewById(R.id.txtProfile)
        calibrationView  = findViewById(R.id.calibrationView)
        rangeSummary     = findViewById(R.id.txtRangeSummary)
        calibrationStatus = findViewById(R.id.txtCalibrationStatus)
        detailStatus     = findViewById(R.id.txtCameraDetailStatus)
        widthSeek        = findViewById(R.id.seekRangeWidth)
        heightSeek       = findViewById(R.id.seekRangeHeight)
        centerXSeek      = findViewById(R.id.seekCenterX)
        centerYSeek      = findViewById(R.id.seekCenterY)
        distanceSwitch   = findViewById(R.id.switchDistanceDetail)
        safeSwitch       = findViewById(R.id.switchSafeCalibration)
        btnOverlay       = findViewById(R.id.btnOverlay)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnStart         = findViewById(R.id.btnStart)
        btnRunBg         = findViewById(R.id.btnRunBackground)
        btnStop          = findViewById(R.id.btnStop)
        btnTestUp        = findViewById(R.id.btnTestUp)
        btnTestDown      = findViewById(R.id.btnTestDown)
        btnTestTap       = findViewById(R.id.btnTestTap)
    }

    private fun currentRange() = ControlRange(
        widthSeek.progress + 30, heightSeek.progress + 30,
        centerXSeek.progress, centerYSeek.progress
    )

    private fun applyRange(save: Boolean) {
        val range = currentRange()
        rangeSummary.text = "Width ${range.widthPercent}%  •  Height ${range.heightPercent}%\nCenter X ${range.horizontalCenter}%  •  Y ${range.verticalCenter}%"
        calibrationView.setRange(range)
        YvoraService.instance?.updateControlRange(range)
        if (save) CameraSettings.saveRange(this, range)
    }

    private fun restoreRange(range: ControlRange) {
        restoringRange = true
        widthSeek.progress = range.widthPercent - 30
        heightSeek.progress = range.heightPercent - 30
        centerXSeek.progress = range.horizontalCenter
        centerYSeek.progress = range.verticalCenter
        restoringRange = false
        applyRange(false)
    }

    private fun setupCalibration() {
        restoreRange(CameraSettings.readRange(this))
        distanceSwitch.isChecked = CameraSettings.readDetail(this) == CameraDetail.DISTANCE
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !restoringRange) applyRange(true)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        listOf(widthSeek, heightSeek, centerXSeek, centerYSeek).forEach {
            it.setOnSeekBarChangeListener(listener)
        }
        distanceSwitch.setOnCheckedChangeListener { _, enabled ->
            CameraSettings.saveDetail(this, if (enabled) CameraDetail.DISTANCE else CameraDetail.AUTO)
            detailStatus.text = if (YvoraService.instance != null)
                "Stop and restart engine to apply detail mode." else "Applies at next engine start."
        }
        safeSwitch.setOnCheckedChangeListener { _, enabled ->
            YvoraService.instance?.setCalibrationMode(enabled)
        }
        findViewById<Button>(R.id.btnResetCalibration).setOnClickListener {
            restoreRange(ControlRange())
            applyRange(true)
            distanceSwitch.isChecked = false
        }
    }

    private fun setupButtons() {
        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ),
                    OVERLAY_CODE
                )
            }
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnStart.setOnClickListener {
            if (!hasCamera()) {
                toast("Camera permission required")
                return@setOnClickListener
            }
            if (!hasOverlay()) {
                toast("Overlay permission required")
                return@setOnClickListener
            }

            // Check if already running
            if (YvoraService.instance != null) {
                toast("⚡ Yvora is already running")
                return@setOnClickListener
            }

            startYvora()
            txtEngineState.text = "Starting"
            txtStatus.text = "Initializing hand tracking"
        }

        btnRunBg.setOnClickListener {
            if (safeSwitch.isChecked) {
                toast("Turn off safe calibration before running in background")
                return@setOnClickListener
            }
            if (!hasCamera()) {
                toast("Camera permission required")
                return@setOnClickListener
            }
            if (!hasOverlay()) {
                toast("Overlay permission required")
                return@setOnClickListener
            }

            // Check if already running
            if (YvoraService.instance != null) {
                toast("⚡ Already running — going to background")
                YvoraService.instance?.forceHidePointer()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val home = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(home)
                }, 300)
                return@setOnClickListener
            }

            YvoraService.instance?.forceHidePointer()
            startYvora()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(home)
            }, 300)
            toast("✅ Yvora running in background")
        }

        btnStop.setOnClickListener {
            // Check if running
            if (YvoraService.instance == null) {
                toast("❌ Yvora is not running")
                return@setOnClickListener
            }

            YvoraService.instance?.forceHidePointer()
            stopYvora()
            txtEngineState.text = "Stopping"
            txtStatus.text = "Ending hand tracking"
        }

        btnTestUp.setOnClickListener {
            val ok = YvoraAccessibilityService.performScroll("up")
            toast(if (ok) "✅ Scroll UP" else "❌ Enable accessibility first")
        }

        btnTestDown.setOnClickListener {
            val ok = YvoraAccessibilityService.performScroll("down")
            toast(if (ok) "✅ Scroll DOWN" else "❌ Enable accessibility first")
        }

        btnTestTap.setOnClickListener {
            val service = YvoraService.instance
            if (service != null) {
                val ok = YvoraAccessibilityService.performTap(
                    service.screenWidth / 2f,
                    service.screenHeight / 2f
                )
                toast(if (ok) "✅ Tap center" else "❌ Enable accessibility first")
            } else {
                toast("Start Yvora first")
            }
        }
    }

    private fun startYvora() {
        val intent = Intent(this, YvoraService::class.java).apply {
            action = YvoraService.ACTION_START
            putExtra(YvoraService.EXTRA_CALIBRATION, safeSwitch.isChecked)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopYvora() {
        startService(Intent(this, YvoraService::class.java).apply {
            action = YvoraService.ACTION_STOP
        })
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, needed.toTypedArray(), CAMERA_CODE
            )
        }
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasOverlay() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this)
        else true

    private fun updateStatuses() {
        val ov = hasOverlay()
        txtOverlay.text =
            if (ov) "Enabled" else "Not enabled"
        txtOverlay.setTextColor(
            if (ov) tint(R.color.accent_green) else tint(R.color.accent_orange)
        )
        btnOverlay.text = if (ov) "Settings" else "Enable"

        val ac = YvoraAccessibilityService.isEnabled()
        txtAccessibility.text =
            if (ac) "Enabled" else "Not enabled"
        txtAccessibility.setTextColor(
            if (ac) tint(R.color.accent_green) else tint(R.color.accent_orange)
        )
        btnAccessibility.text = if (ac) "Settings" else "Enable"

        val running = YvoraService.instance != null
        txtEngineState.text = if (running) "Engine running" else "Standby"
        txtEngineState.setTextColor(tint(R.color.accent))
        txtStatus.text = if (running) "Waiting for a tracking result" else "Start to detect hand gestures."
        txtStatus.setTextColor(tint(R.color.text_secondary))
        btnStart.text = if (running) "ENGINE\nACTIVE" else "START\nENGINE"
        txtResultRate.text = "—"
        txtFrameAge.text = "—"
        txtProfile.text = "Adaptive pacing enabled"
        previousStatus = ""
        lastUiUpdate = -1000L
        lastSampleUpdate = -1000L
        if (!running) {
            calibrationView.setSample(Float.NaN, Float.NaN, 0, 0)
            calibrationStatus.text = "Start engine to test your camera."
            detailStatus.text = "Applies at next engine start."
        }

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_CODE) updateStatuses()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}