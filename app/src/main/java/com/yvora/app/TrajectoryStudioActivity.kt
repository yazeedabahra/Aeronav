package com.yvora.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class TrajectoryStudioActivity: LocalizedActivity() {
    private val handler=Handler(Looper.getMainLooper())
    private var engine:YvoraService?=null
    private var previousCalibration=false
    private var capturing=false
    private lateinit var hand:Spinner
    private lateinit var command:Spinner
    private lateinit var start:Button
    private lateinit var info:TextView
    private lateinit var saved:TextView
    private fun slot()=hand.selectedItemPosition.coerceIn(0,1)
    private fun action()=trajectoryActions[command.selectedItemPosition.coerceIn(0,trajectoryActions.lastIndex)]
    private fun refresh(){
        val templates=CameraSettings.readMotions(this,slot())
        saved.text=if(templates.isEmpty()) tr("No saved trajectories for this hand.","لا توجد مسارات محفوظة لهذه اليد.")
            else tr("Saved commands: ","الأوامر المحفوظة: ")+templates.keys.joinToString{uiStatus(it.name)}
    }
    private fun cancel(){
        engine?.cancelMotionTraining();capturing=false
        hand.isEnabled=true;command.isEnabled=true;start.text=tr("Start recording 3 examples","بدء تسجيل ثلاثة أمثلة")
    }
    private val tick=object:Runnable{
        override fun run(){
            val e=engine
            if(capturing){
                if(e==null || e!==YvoraService.instance || !e.motionTrainingActive()) {
                    cancel();info.text=tr("Capture interrupted or expired.","انقطع التسجيل أو انتهت مهلته.")
                } else {
                    val count=e.motionTrainingCount()
                    info.text=tr("Examples: $count/3\n","الأمثلة: $count/3\n")+uiStatus(e.motionTrainingStatus())
                    if(count==3) start.text=tr("Review and save template","مراجعة وحفظ النموذج")
                }
            } else if(e==null || e!==YvoraService.instance) info.text=tr("Start the tracking engine first.","شغّل محرك التتبع أولًا.")
            handler.postDelayed(this,250L)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        fun label(value:String,size:Float=15f)=TextView(this).apply{text=value;textSize=size;panel.addView(this)}
        fun spinner(items:List<String>)=Spinner(this).apply{
            adapter=ArrayAdapter(this@TrajectoryStudioActivity,android.R.layout.simple_spinner_dropdown_item,items);panel.addView(this)
        }
        label(tr("CUSTOM TRAJECTORY STUDIO","استوديو المسارات المخصصة"),24f)
        label("Choose a swipe command. Open your selected palm, pinch thumb and index, draw a path for 0.4–2.5 seconds, then release. Repeat three times. No gestures execute during recording.")
        label(tr("Training hand","يد التدريب"));hand=spinner(listOf(tr("Left (cyan)","اليسرى (سماوي)"),tr("Right (amber)","اليمنى (كهرماني)")))
        label(tr("Assigned command","الأمر المرتبط"));command=spinner(trajectoryActions.map{uiStatus(it.name)})
        saved=label("");info=label(tr("Ready","جاهز"))
        start=Button(this).apply{text=tr("Start recording 3 examples","بدء تسجيل ثلاثة أمثلة");panel.addView(this)}
        val stop=Button(this).apply{text=tr("Cancel recording","إلغاء التسجيل");panel.addView(this)}
        val forget=Button(this).apply{text=tr("Forget selected command template","حذف نموذج الأمر المحدد");panel.addView(this)}
        label(tr("Runtime hand in custom mode","يد التحكم في الوضع المخصص"))
        val activeHand=spinner(listOf(tr("Left (cyan)","اليسرى (سماوي)"),tr("Right (amber)","اليمنى (كهرماني)")))
        activeHand.setSelection(CameraSettings.readMotionSlot(this))
        label(tr("Changes apply after engine restart. Choose Custom trajectories on the main screen. Only selected hand's saved swipes execute; normal taps, drags, zoom and rotation are disabled. Saved data is normalized 16-point paths and duration, never camera frames.","تُطبّق التغييرات بعد إعادة تشغيل المحرك. اختر المسارات المخصصة من الشاشة الرئيسية. تُنفّذ أوامر السحب المحفوظة لليد المحددة فقط؛ يتوقف النقر والسحب المستمر والتكبير والتدوير. تُحفظ مسارات معيارية من 16 نقطة ومدتها، دون إطارات كاميرا."))
        val listener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){refresh()}
        }
        hand.onItemSelectedListener=listener;command.onItemSelectedListener=listener
        activeHand.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){CameraSettings.saveMotionSlot(this@TrajectoryStudioActivity,position)}
        }
        start.setOnClickListener{
            val e=engine
            if(e==null || e!==YvoraService.instance){info.text=tr("Start engine first.","شغّل المحرك أولًا.");return@setOnClickListener}
            if(!capturing){
                if(e.startMotionTraining(slot(),action())){
                    capturing=true;hand.isEnabled=false;command.isEnabled=false
                    start.text=tr("Review after 3 examples","مراجعة بعد ثلاثة أمثلة");info.text=tr("Open palm to arm","افتح الكف للاستعداد")
                } else info.text=tr("Camera not ready or active touch/AI.","الكاميرا غير جاهزة أو اللمس/الذكاء الاصطناعي نشط.")
            } else {
                if(e.motionTrainingCount()<3){info.text=tr("Record three complete examples first.","سجل ثلاثة أمثلة كاملة أولًا.");return@setOnClickListener}
                val selected=slot();val assigned=action()
                val pattern=e.finishMotionTraining();cancel()
                if(pattern==null){info.text=tr("Examples inconsistent or expired; repeat training.","الأمثلة غير متناسقة أو انتهت المهلة؛ كرر التدريب.");return@setOnClickListener}
                val others=CameraSettings.readMotions(this,selected).filterKeys{it!=assigned}
                if(others.values.any{MotionSimilarity.degree(pattern,it)>=.65f}){
                    info.text=tr("Too similar to another saved command. Choose a more distinct path.","المسار مشابه جدًا لأمر محفوظ آخر. اختر مسارًا أكثر تميزًا.");return@setOnClickListener
                }
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle(tr("Save ${assigned.name} trajectory?","حفظ مسار ${uiStatus(assigned.name)}؟"))
                    .setMessage(tr("Three consistent examples. Mean duration ${pattern.duration} ms. Replaces this hand's template for this command. Restart engine after saving.","ثلاثة أمثلة متناسقة. متوسط المدة ${pattern.duration} مللي ثانية. يستبدل نموذج هذه اليد لهذا الأمر. أعد تشغيل المحرك بعد الحفظ."))
                    .setNegativeButton(tr("Cancel","إلغاء"),null)
                    .setPositiveButton(tr("Save","حفظ")){_,_->CameraSettings.saveMotion(this,selected,assigned,pattern);refresh();info.text=tr("Saved. Restart engine to apply.","تم الحفظ. أعد تشغيل المحرك للتطبيق.")}.show()
            }
        }
        stop.setOnClickListener{cancel();info.text=tr("Recording cancelled.","أُلغي التسجيل.")}
        forget.setOnClickListener{
            cancel();val selected=slot();val assigned=action()
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(tr("Forget ${assigned.name}?","حذف ${uiStatus(assigned.name)}؟"))
                .setNegativeButton(tr("Cancel","إلغاء"),null).setPositiveButton(tr("Forget","حذف")){_,_->
                    CameraSettings.saveMotion(this,selected,assigned,null);refresh();info.text=tr("Forgotten. Restart engine to apply.","تم الحذف. أعد تشغيل المحرك للتطبيق.")
                }.show()
        }
        refresh();setContentView(ScrollView(this).apply{addView(panel)})
    }
    override fun onResume(){
        super.onResume();YvoraAccessibilityService.stopAi(tr("AI stopped for trajectory training","أُوقف التحكم الذكي لتدريب المسارات"))
        engine=YvoraService.instance;previousCalibration=engine?.isCalibrating==true
        engine?.setCalibrationMode(true);handler.post(tick)
    }
    override fun onPause(){
        handler.removeCallbacks(tick);cancel()
        if(engine===YvoraService.instance) engine?.setCalibrationMode(previousCalibration)
        engine=null;super.onPause()
    }
}
