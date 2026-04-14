package com.example.airscroll

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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

    private val statusListener = object : AirScrollService.StatusListener {
        override fun onStatus(handDetected: Boolean, gesture: String, isPinching: Boolean) {
            runOnUiThread {
                val handText = if (handDetected) "✅ Hand detected" else "❌ No hand"
                val handColor = if (handDetected) Color.GREEN else Color.RED
                txtStatus.setTextColor(handColor)
                txtStatus.text = "$handText | Last: $gesture | Pinch: $isPinching"
            }
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
        // Register status listener when app is visible
        AirScrollService.statusListener = statusListener
    }

    override fun onPause() {
        super.onPause()
        // Remove UI listener when going background
        // Service still runs
        AirScrollService.statusListener = null
    }

    private fun bindViews() {
        txtOverlay = findViewById(R.id.txtOverlayStatus)
        txtAccessibility = findViewById(R.id.txtAccessibilityStatus)
        txtStatus = findViewById(R.id.txtStatus)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnStart = findViewById(R.id.btnStart)
        btnRunBg = findViewById(R.id.btnRunBackground)
        btnStop = findViewById(R.id.btnStop)
        btnTestUp = findViewById(R.id.btnTestUp)
        btnTestDown = findViewById(R.id.btnTestDown)
        btnTestTap = findViewById(R.id.btnTestTap)
    }

    private fun setupButtons() {
        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ), OVERLAY_CODE
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
            startAirScroll()
            toast("AirScroll started")
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
            startAirScroll()
            // Go to home screen
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
            toast("AirScroll running in background")
        }

        btnStop.setOnClickListener {
            stopAirScroll()
            toast("AirScroll stopped")
        }

        btnTestUp.setOnClickListener {
            val ok = AirScrollAccessibilityService.performScroll("up")
            toast(if (ok) "✅ Scroll UP" else "❌ Enable accessibility")
        }

        btnTestDown.setOnClickListener {
            val ok = AirScrollAccessibilityService.performScroll("down")
            toast(if (ok) "✅ Scroll DOWN" else "❌ Enable accessibility")
        }

        btnTestTap.setOnClickListener {
            val service = AirScrollService.instance
            if (service != null) {
                val ok = AirScrollAccessibilityService.performTap(
                    service.screenWidth / 2f,
                    service.screenHeight / 2f
                )
                toast(if (ok) "✅ Tap center" else "❌ Enable accessibility")
            } else {
                toast("Start AirScroll first")
            }
        }
    }

    private fun startAirScroll() {
        val intent = Intent(this, AirScrollService::class.java).apply {
            action = AirScrollService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopAirScroll() {
        val intent = Intent(this, AirScrollService::class.java).apply {
            action = AirScrollService.ACTION_STOP
        }
        startService(intent)
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
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), CAMERA_CODE)
        }
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasOverlay() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this)
        else true

    private fun hasAccessibility() = AirScrollAccessibilityService.isEnabled()

    private fun updateStatuses() {
        val ov = hasOverlay()
        txtOverlay.text = if (ov) "Overlay: ✅" else "Overlay: ❌ — tap ENABLE"
        txtOverlay.setTextColor(if (ov) Color.GREEN else Color.parseColor("#FF9800"))
        btnOverlay.text = if (ov) "SETTINGS" else "ENABLE"

        val ac = hasAccessibility()
        txtAccessibility.text = if (ac) "Accessibility: ✅" else "Accessibility: ❌ — tap ENABLE"
        txtAccessibility.setTextColor(if (ac) Color.GREEN else Color.parseColor("#FF9800"))
        btnAccessibility.text = if (ac) "SETTINGS" else "ENABLE"

        val running = AirScrollService.instance != null
        txtStatus.text = if (running) "Service: Running" else "Service: Stopped"
        txtStatus.setTextColor(if (running) Color.GREEN else Color.GRAY)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_CODE) updateStatuses()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}