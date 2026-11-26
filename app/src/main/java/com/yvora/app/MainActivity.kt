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
                    !handDetected -> "Searching for hand"
                    isTapPinching -> "Tap detected"
                    isGestureMode -> "Gesture armed"
                    else -> "Pointer tracking"
                }
                val summary = "$mode • Last: $gesture"
                val now = SystemClock.uptimeMillis()
                if (summary != previousStatus || now - lastUiUpdate >= 250L) {
                    previousStatus = summary
                    lastUiUpdate = now
                    txtEngineState.text = if (handDetected) "Tracking active" else "Searching"
                    txtEngineState.setTextColor(tint(if (handDetected) R.color.accent_green else R.color.accent))
                    txtStatus.text = summary
                    val rate = service.resultRate
                    txtResultRate.text = if (rate > 0f) String.format(java.util.Locale.ROOT, "%.1f", rate) + " /s" else "—"
                    txtFrameAge.text = if (service.frameAgeMs >= 0) "${service.frameAgeMs} ms" else "—"
                    txtProfile.text = if (service.isLowRamProfile) "Adaptive pacing • Low-memory profile" else "Adaptive pacing • Standard profile"
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
        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatuses()
        YvoraService.statusListener = statusListener
    }

    override fun onPause() {
        super.onPause()
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
        btnOverlay       = findViewById(R.id.btnOverlay)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnStart         = findViewById(R.id.btnStart)
        btnRunBg         = findViewById(R.id.btnRunBackground)
        btnStop          = findViewById(R.id.btnStop)
        btnTestUp        = findViewById(R.id.btnTestUp)
        btnTestDown      = findViewById(R.id.btnTestDown)
        btnTestTap       = findViewById(R.id.btnTestTap)
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
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_CODE) updateStatuses()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}