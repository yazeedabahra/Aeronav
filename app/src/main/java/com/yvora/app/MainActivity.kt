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

class MainActivity : LocalizedActivity() {

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
                    !handDetected -> localized("Searching for hand","البحث عن اليد")
                    service.isCalibrating -> localized("Safe calibration • Gestures disabled","معايرة آمنة • الإيماءات معطّلة")
                    service.gestureDispatch == "Dragging: touch held" && isTapPinching -> localized("Dragging • Open fingers to drop","سحب • افتح الأصابع للإفلات")
                    isTapPinching -> localized("Pinch held","قرص مستمر")
                    isGestureMode -> localized("Gesture armed","الإيماءة جاهزة")
                    else -> localized("Pointer tracking","تتبع المؤشر")
                }
                val summary = localized("$mode • Last: $gesture","${uiStatus(mode)} • آخر أمر: ${uiStatus(gesture)}")
                val now = SystemClock.uptimeMillis()
                if (now - lastSampleUpdate >= 250L) {
                    lastSampleUpdate = now
                    calibrationView.setSamples(service.sampleX(0),service.sampleY(0),
                        service.sampleX(1),service.sampleY(1),service.analysisWidth,service.analysisHeight)
                    val x=service.sampleX(selectedHand);val y=service.sampleY(selectedHand)
                    if(capturing && !service.captureActive()) {
                        capturing=false;autoButton.text=localized("Start auto calibration","بدء المعايرة التلقائية")
                        calibrationStatus.text=localized("Capture expired or interrupted. Try again.","انتهت مهلة التسجيل أو انقطع. حاول مجددًا.")
                    } else calibrationStatus.text=if(capturing) localized("Move selected open palm to comfortable X/Y extremes. Samples: ${service.captureCount()}","حرّك الكف المفتوح المحدد إلى حدود X/Y المريحة. العينات: ${service.captureCount()}")
                        else if(x.isFinite() && y.isFinite()) localized("Selected hand • ${(x*100).toInt()}% X / ${(y*100).toInt()}% Y","اليد المحددة • ${(x*100).toInt()}% X / ${(y*100).toInt()}% Y")
                        else localized("Selected hand not detected","لم تُرصد اليد المحددة")
                    detailStatus.text = if (distanceSwitch.isChecked != service.usingDistanceDetail)
                        localized("Stop and restart engine to apply detail mode.","أوقف المحرك وأعد تشغيله لتطبيق وضع التفاصيل.")
                        else if (service.analysisWidth > 0)
                            localized("Active analysis: ${service.analysisWidth} × ${service.analysisHeight}","دقة التحليل النشط: ${service.analysisWidth} × ${service.analysisHeight}")
                        else localized("Waiting for camera.","بانتظار الكاميرا.")
                }
                if (summary != previousStatus || now - lastUiUpdate >= 250L) {
                    previousStatus = summary
                    lastUiUpdate = now
                    txtEngineState.text = if (service.pauseReason != null) localized("Paused","متوقف مؤقتًا") else if (handDetected) localized("Tracking active","التتبع نشط") else localized("Searching","جاري البحث")
                    txtEngineState.setTextColor(tint(if (handDetected) R.color.accent_green else R.color.accent))
                    txtStatus.text = "$summary\n${uiStatus(debugInfo)}\n${uiStatus(service.gestureDispatch)}"
                    val rate = service.resultRate
                    txtResultRate.text = if (rate > 0f) String.format(java.util.Locale.ROOT, "%.1f", rate) + " /s" else "—"
                    txtFrameAge.text = if (service.frameAgeMs >= 0) "${service.frameAgeMs} ms" else "—"
                    txtProfile.text = uiStatus(service.powerStatus)
                    btnStart.text = localized("ENGINE\nACTIVE","المحرك\nنشط")
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
        findViewById<Button>(R.id.btnLanguage).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(localized("App language","لغة التطبيق"))
                .setSingleChoiceItems(arrayOf("English","العربية"),if(AppLanguage.arabic) 1 else 0) { dialog,which ->
                    dialog.dismiss()
                    if(AppLanguage.select(this,which==1)) recreate()
                }.setNegativeButton(localized("Cancel","إلغاء"),null).show()
        }
        bindViews()
        setupButtons()
        setupCalibration()
        findViewById<Switch>(R.id.switchDualHand).apply {
            isChecked=CameraSettings.readDualHand(this@MainActivity)
            setOnCheckedChangeListener { _, enabled -> CameraSettings.saveDualHand(this@MainActivity,enabled) }
        }
        findViewById<android.widget.Spinner>(R.id.spinnerDualMode).apply {
            adapter=android.widget.ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,
                listOf(localized("Normal gestures","الإيماءات العادية"),localized("Zoom only (experimental)","التكبير فقط (تجريبي)"),localized("Rotate only (experimental)","التدوير فقط (تجريبي)"),localized("Custom trajectories (experimental)","مسارات مخصصة (تجريبي)")))
            setSelection(CameraSettings.readDualMode(this@MainActivity).ordinal)
            onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {
                    val selected=DualMode.values()[position]
                    if(CameraSettings.readDualMode(this@MainActivity)!=selected) {
                        CameraSettings.saveDualMode(this@MainActivity,selected)
                        toast(localized("Stop and restart engine to apply. Zoom and rotation require dual hands.","أعد تشغيل المحرك للتطبيق. التكبير والتدوير يتطلبان اليدين."))
                    }
                }
            }
        }
        findViewById<Switch>(R.id.switchAssistIntent).apply {
            isChecked=CameraSettings.readAssist(this@MainActivity)
            setOnCheckedChangeListener { _,enabled -> CameraSettings.saveAssist(this@MainActivity,enabled) }
        }
        findViewById<android.widget.Spinner>(R.id.spinnerAssistLevel).apply {
            adapter=android.widget.ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,
                listOf(localized("Fewer false swipes","تقليل السحب الخاطئ"),localized("Balanced","متوازن"),localized("More flexible swipes","سحب أكثر مرونة")))
            setSelection(CameraSettings.readAssistLevel(this@MainActivity)+1)
            onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {
                    CameraSettings.saveAssistLevel(this@MainActivity,position-1)
                }
            }
        }
        findViewById<Button>(R.id.btnTrajectoryStudio).setOnClickListener { startActivity(Intent(this,TrajectoryStudioActivity::class.java)) }
        findViewById<Button>(R.id.btnPersonalStudio).setOnClickListener { startActivity(Intent(this,PersonalGestureActivity::class.java)) }
        findViewById<Button>(R.id.btnFuzzyLab).setOnClickListener { startActivity(Intent(this,FuzzyLabActivity::class.java)) }
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
        val label=if(ranges.linked) localized("Shared (both hands)","مشترك لليدين") else if(selectedHand==0) localized("Left hand","اليد اليسرى") else localized("Right hand","اليد اليمنى")
        rangeSummary.text=localized("$label • X ${r.widthPercent}% • Y ${r.heightPercent}%\nCenter X ${r.horizontalCenter}% • Y ${r.verticalCenter}%","$label • X ${r.widthPercent}% • Y ${r.heightPercent}%\nالمركز X ${r.horizontalCenter}% • Y ${r.verticalCenter}%")
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
        autoButton.text=localized("Start auto calibration","بدء المعايرة التلقائية")
    }

    private fun setupCalibration() {
        ranges=CameraSettings.readRanges(this)
        restoreRange()
        linkSwitch.isChecked=ranges.linked
        handPicker.adapter=android.widget.ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,
            listOf(localized("Left hand (cyan)","اليسرى (سماوي)"),localized("Right hand (amber)","اليمنى (كهرماني)")))
        handPicker.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {
                if(selectedHand!=position) { cancelCaptureUi();selectedHand=position;restoreRange() }
            }
        }
        linkSwitch.setOnCheckedChangeListener { _,enabled ->
            if(!safeSwitch.isChecked) safeSwitch.isChecked=true
            cancelCaptureUi()
            ranges=ranges.link(enabled,selectedHand)
            persistRanges();restoreRange()
        }
        val listener=object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean) {
                if(fromUser && !restoringRange) {
                    if(!safeSwitch.isChecked) safeSwitch.isChecked=true
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
            if(service==null) { toast(localized("Start the engine first","شغّل المحرك أولًا"));return@setOnClickListener }
            if(capturing) {
                val suggested=service.finishRangeCapture()
                cancelCaptureUi()
                if(suggested==null) {
                    toast(localized("Not enough reliable X/Y movement. Try again for 3–12 seconds.","الحركة الموثوقة غير كافية. حاول مجددًا لمدة 3–12 ثانية."))
                } else {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(localized("Apply measured range?","تطبيق النطاق المقاس؟"))
                        .setMessage(localized("X ${suggested.widthPercent}% • Y ${suggested.heightPercent}%\nCenter X ${suggested.horizontalCenter}% • Y ${suggested.verticalCenter}%","X ${suggested.widthPercent}% • Y ${suggested.heightPercent}%\nالمركز X ${suggested.horizontalCenter}% • Y ${suggested.verticalCenter}%"))
                        .setNegativeButton(localized("Cancel","إلغاء"),null)
                        .setPositiveButton(localized("Apply","تطبيق")) { _,_ ->
                            ranges=ranges.edit(selectedHand,suggested)
                            persistRanges();restoreRange()
                        }.show()
                }
            } else {
                safeSwitch.isChecked=true
                service.setCalibrationMode(true)
                if(service.beginRangeCapture(selectedHand)) {
                    capturing=true
                    autoButton.text=localized("Finish and review range","إنهاء ومراجعة النطاق")
                    calibrationStatus.text=localized("Move your selected open palm across comfortable left/right and up/down extremes, then finish.","حرّك الكف المفتوح المحدد إلى أقصى حدود مريحة أفقيًا وعموديًا، ثم أنهِ المعايرة.")
                } else toast(localized("Camera not ready or AI navigation active.","الكاميرا غير جاهزة أو التحكم الذكي نشط."))
            }
        }
        distanceSwitch.isChecked=CameraSettings.readDetail(this)==CameraDetail.DISTANCE
        distanceSwitch.setOnCheckedChangeListener { _,enabled ->
            CameraSettings.saveDetail(this,if(enabled) CameraDetail.DISTANCE else CameraDetail.AUTO)
            detailStatus.text=if(YvoraService.instance!=null) localized("Stop and restart engine to apply detail mode.","أوقف المحرك وأعد تشغيله لتطبيق وضع التفاصيل.") else localized("Applies at next engine start.","يُطبّق عند تشغيل المحرك مجددًا.")
        }
        safeSwitch.setOnCheckedChangeListener { _,enabled ->
            cancelCaptureUi()
            YvoraService.instance?.setCalibrationMode(enabled)
        }
        findViewById<Button>(R.id.btnResetCalibration).setOnClickListener {
            if(!safeSwitch.isChecked) safeSwitch.isChecked=true
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
                toast(localized("Camera permission required","إذن الكاميرا مطلوب"))
                return@setOnClickListener
            }
            if (!hasOverlay()) {
                toast(localized("Overlay permission required","إذن العرض فوق التطبيقات مطلوب"))
                return@setOnClickListener
            }

            // Check if already running
            if (YvoraService.instance != null) {
                toast(localized("⚡ Yvora is already running","Yvora يعمل بالفعل"))
                return@setOnClickListener
            }

            startYvora()
            txtEngineState.text = localized("Starting","جاري التشغيل")
            txtStatus.text = localized("Initializing hand tracking","تهيئة تتبع اليد")
        }

        btnRunBg.setOnClickListener {
            if (safeSwitch.isChecked) {
                toast(localized("Turn off safe calibration before running in background","أوقف المعايرة الآمنة قبل التشغيل في الخلفية"))
                return@setOnClickListener
            }
            if (!hasCamera()) {
                toast(localized("Camera permission required","إذن الكاميرا مطلوب"))
                return@setOnClickListener
            }
            if (!hasOverlay()) {
                toast(localized("Overlay permission required","إذن العرض فوق التطبيقات مطلوب"))
                return@setOnClickListener
            }

            // Check if already running
            if (YvoraService.instance != null) {
                toast(localized("⚡ Already running — going to background","المحرك يعمل بالفعل — الانتقال للخلفية"))
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
            toast(localized("✅ Yvora running in background","Yvora يعمل في الخلفية"))
        }

        btnStop.setOnClickListener {
            // Check if running
            if (YvoraService.instance == null) {
                toast(localized("❌ Yvora is not running","Yvora لا يعمل"))
                return@setOnClickListener
            }

            YvoraService.instance?.forceHidePointer()
            stopYvora()
            txtEngineState.text = localized("Stopping","جاري الإيقاف")
            txtStatus.text = localized("Ending hand tracking","إنهاء تتبع اليد")
        }

        btnTestUp.setOnClickListener {
            val ok = YvoraAccessibilityService.performScroll("up")
            toast(if (ok) localized("✅ Scroll UP","تمرير لأعلى") else localized("❌ Enable accessibility first","فعّل إمكانية الوصول أولًا"))
        }

        btnTestDown.setOnClickListener {
            val ok = YvoraAccessibilityService.performScroll("down")
            toast(if (ok) localized("✅ Scroll DOWN","تمرير لأسفل") else localized("❌ Enable accessibility first","فعّل إمكانية الوصول أولًا"))
        }

        btnTestTap.setOnClickListener {
            val service = YvoraService.instance
            if (service != null) {
                val ok = YvoraAccessibilityService.performTap(
                    service.screenWidth / 2f,
                    service.screenHeight / 2f
                )
                toast(if (ok) localized("✅ Tap center","نقر المركز") else localized("❌ Enable accessibility first","فعّل إمكانية الوصول أولًا"))
            } else {
                toast(localized("Start Yvora first","شغّل Yvora أولًا"))
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
            if (ov) localized("Enabled","مفعّل") else localized("Not enabled","غير مفعّل")
        txtOverlay.setTextColor(
            if (ov) tint(R.color.accent_green) else tint(R.color.accent_orange)
        )
        btnOverlay.text = if (ov) localized("Settings","الإعدادات") else localized("Enable","تفعيل")

        val ac = YvoraAccessibilityService.isEnabled()
        txtAccessibility.text =
            if (ac) localized("Enabled","مفعّل") else localized("Not enabled","غير مفعّل")
        txtAccessibility.setTextColor(
            if (ac) tint(R.color.accent_green) else tint(R.color.accent_orange)
        )
        btnAccessibility.text = if (ac) localized("Settings","الإعدادات") else localized("Enable","تفعيل")

        val running = YvoraService.instance != null
        txtEngineState.text = if (running) localized("Engine running","المحرك يعمل") else localized("Standby","استعداد")
        txtEngineState.setTextColor(tint(R.color.accent))
        txtStatus.text = if (running) localized("Waiting for a tracking result","بانتظار نتيجة التتبع") else localized("Start to detect hand gestures.","شغّل المحرك للتعرف على إيماءات اليد.")
        txtStatus.setTextColor(tint(R.color.text_secondary))
        btnStart.text = if (running) localized("ENGINE\nACTIVE","المحرك\nنشط") else localized("START\nENGINE","تشغيل\nالمحرك")
        txtResultRate.text = "—"
        txtFrameAge.text = "—"
        txtProfile.text = localized("Adaptive pacing enabled","تنظيم معدل المعالجة التكيفي مفعّل")
        previousStatus = ""
        lastUiUpdate = -1000L
        lastSampleUpdate = -1000L
        if (!running) {
            calibrationView.setSamples(Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0, 0)
            calibrationStatus.text = localized("Start engine to test your camera.","شغّل المحرك لاختبار الكاميرا.")
            detailStatus.text = localized("Applies at next engine start.","يُطبّق عند تشغيل المحرك مجددًا.")
        }

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_CODE) updateStatuses()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}