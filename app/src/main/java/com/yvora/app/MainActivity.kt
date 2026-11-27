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
    private var ranges=HandRanges()
    private var selectedHand=0
    private lateinit var linkSwitch:Switch
    private lateinit var handPicker:android.widget.Spinner
    private lateinit var autoButton:Button
    private var capturing=false
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
                    calibrationView.setSamples(service.sampleX(0),service.sampleY(0),
                        service.sampleX(1),service.sampleY(1),service.analysisWidth,service.analysisHeight)
                    val x=service.sampleX(selectedHand);val y=service.sampleY(selectedHand)
                    if(capturing && !service.captureActive()) {
                        capturing=false;autoButton.text="Start auto calibration"
                        calibrationStatus.text="Capture expired or interrupted. Try again."
                    } else calibrationStatus.text=if(capturing) "Move selected open palm to comfortable X/Y extremes. Samples: ${service.captureCount()}"
                        else if(x.isFinite() && y.isFinite()) "Selected hand • ${(x*100).toInt()}% X / ${(y*100).toInt()}% Y"
                        else "Selected hand not detected"
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
            runOnUiThread { updateStatuses();cancelCaptureUi();calibrationView.setSamples(Float.NaN,Float.NaN,Float.NaN,Float.NaN,0,0) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupButtons()
        setupCalibration()
        findViewById<Switch>(R.id.switchDualHand).apply {
            isChecked=CameraSettings.readDualHand(this@MainActivity)
            setOnCheckedChangeListener { _, enabled -> CameraSettings.saveDualHand(this@MainActivity,enabled) }
        }
        findViewById<Switch>(R.id.switchAssistIntent).apply {
            isChecked=CameraSettings.readAssist(this@MainActivity)
            setOnCheckedChangeListener { _,enabled -> CameraSettings.saveAssist(this@MainActivity,enabled) }
        }
        findViewById<android.widget.Spinner>(R.id.spinnerAssistLevel).apply {
            adapter=android.widget.ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,
                listOf("Fewer false swipes","Balanced","More flexible swipes"))
            setSelection(CameraSettings.readAssistLevel(this@MainActivity)+1)
            onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {
                    CameraSettings.saveAssistLevel(this@MainActivity,position-1)
                }
            }
        }
        findViewById<Button>(R.id.btnAiCore).setOnClickListener { startActivity(Intent(this, AiLabActivity::class.java)) }
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        distanceSwitch.isChecked = CameraSettings.readDetail(this) == CameraDetail.DISTANCE
        updateStatuses()
        YvoraService.statusListener = statusListener
        YvoraService.instance?.let { safeSwitch.isChecked = it.isCalibrating }
    }

    override fun onPause() {
        super.onPause()
        cancelCaptureUi()
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
        linkSwitch       = findViewById(R.id.switchLinkedRanges)
        handPicker       = findViewById(R.id.spinnerCalibrationHand)
        autoButton       = findViewById(R.id.btnAutoRange)
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
        widthSeek.progress+10,heightSeek.progress+10,centerXSeek.progress,centerYSeek.progress
    )

    private fun renderRange() {
        val r=ranges.effective(selectedHand)
        val label=if(ranges.linked) "Shared (both hands)" else if(selectedHand==0) "Left hand" else "Right hand"
        rangeSummary.text="$label • X ${r.widthPercent}% • Y ${r.heightPercent}%\nCenter X ${r.horizontalCenter}% • Y ${r.verticalCenter}%"
        calibrationView.setRanges(ranges,selectedHand)
    }
    private fun persistRanges() {
        CameraSettings.saveRanges(this,ranges)
        YvoraService.instance?.updateControlRanges(ranges)
        renderRange()
    }
    private fun restoreRange() {
        val r=ranges.effective(selectedHand)
        restoringRange=true
        widthSeek.progress=r.widthPercent-10;heightSeek.progress=r.heightPercent-10
        centerXSeek.progress=r.horizontalCenter;centerYSeek.progress=r.verticalCenter
        restoringRange=false
        renderRange()
    }
    private fun cancelCaptureUi() {
        YvoraService.instance?.cancelRangeCapture()
        capturing=false
        autoButton.text="Start auto calibration"
    }

    private fun setupCalibration() {
        ranges=CameraSettings.readRanges(this)
        restoreRange()
        linkSwitch.isChecked=ranges.linked
        handPicker.adapter=android.widget.ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,
            listOf("Left hand (cyan)","Right hand (amber)"))
        handPicker.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {
                if(selectedHand!=position) { cancelCaptureUi();selectedHand=position;restoreRange() }
            }
        }
        linkSwitch.setOnCheckedChangeListener { _,enabled ->
            cancelCaptureUi()
            ranges=ranges.link(enabled,selectedHand)
            persistRanges();restoreRange()
        }
        val listener=object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean) {
                if(fromUser && !restoringRange) {
                    cancelCaptureUi()
                    ranges=ranges.edit(selectedHand,currentRange())
                    persistRanges()
                }
            }
            override fun onStartTrackingTouch(seekBar:SeekBar?) { if(!safeSwitch.isChecked) safeSwitch.isChecked=true }
            override fun onStopTrackingTouch(seekBar:SeekBar?) {}
        }
        listOf(widthSeek,heightSeek,centerXSeek,centerYSeek).forEach { it.setOnSeekBarChangeListener(listener) }
        autoButton.setOnClickListener {
            val service=YvoraService.instance
            if(service==null) { toast("Start the engine first");return@setOnClickListener }
            if(capturing) {
                val suggested=service.finishRangeCapture()
                cancelCaptureUi()
                if(suggested==null) {
                    toast("Not enough reliable X/Y movement. Try again for 3–12 seconds.")
                } else {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Apply measured range?")
                        .setMessage("X ${suggested.widthPercent}% • Y ${suggested.heightPercent}%\nCenter X ${suggested.horizontalCenter}% • Y ${suggested.verticalCenter}%")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Apply") { _,_ ->
                            ranges=ranges.edit(selectedHand,suggested)
                            persistRanges();restoreRange()
                        }.show()
                }
            } else {
                safeSwitch.isChecked=true
                service.setCalibrationMode(true)
                if(service.beginRangeCapture(selectedHand)) {
                    capturing=true
                    autoButton.text="Finish and review range"
                    calibrationStatus.text="Move your selected open palm across comfortable left/right and up/down extremes, then finish."
                } else toast("Camera not ready or AI navigation active.")
            }
        }
        distanceSwitch.isChecked=CameraSettings.readDetail(this)==CameraDetail.DISTANCE
        distanceSwitch.setOnCheckedChangeListener { _,enabled ->
            CameraSettings.saveDetail(this,if(enabled) CameraDetail.DISTANCE else CameraDetail.AUTO)
            detailStatus.text=if(YvoraService.instance!=null) "Stop and restart engine to apply detail mode." else "Applies at next engine start."
        }
        safeSwitch.setOnCheckedChangeListener { _,enabled ->
            cancelCaptureUi()
            YvoraService.instance?.setCalibrationMode(enabled)
        }
        findViewById<Button>(R.id.btnResetCalibration).setOnClickListener {
            cancelCaptureUi()
            ranges=ranges.edit(selectedHand,ControlRange())
            persistRanges();restoreRange()
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