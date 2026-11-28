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
    private fun action()=RegistryAction.values()[command.selectedItemPosition.coerceIn(0,RegistryAction.values().lastIndex)]
    private fun refresh(){
        val templates=CameraSettings.readRegistry(this,slot())
        saved.text=if(templates.isEmpty()) localized("No saved trajectories for this hand.","لا توجد مسارات محفوظة لهذه اليد.")
            else localized("Saved commands: ","الأوامر المحفوظة: ")+templates.keys.joinToString{it.title()}
    }
    private fun cancel(){
        engine?.cancelMotionTraining();capturing=false
        hand.isEnabled=true;command.isEnabled=true;start.text=localized("Record 3 examples + 1 test","تسجيل ثلاثة أمثلة + اختبار")
    }
    private val tick=object:Runnable{
        override fun run(){
            val e=engine
            if(capturing){
                if(e==null || e!==YvoraService.instance || !e.motionTrainingActive()) {
                    cancel();info.text=localized("Capture interrupted or expired.","انقطع التسجيل أو انتهت مهلته.")
                } else {
                    val count=e.motionTrainingCount()
                    info.text=localized("Examples: $count/4\n","الأمثلة: $count/4\n")+uiStatus(e.motionTrainingStatus())
                    if(count==4) start.text=localized("Review and save template","مراجعة وحفظ النموذج")
                }
            } else if(e==null || e!==YvoraService.instance) info.text=localized("Start the tracking engine first.","شغّل محرك التتبع أولًا.")
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
        label(localized("GESTURE STUDIO • REGISTRY","استوديو الإيماءات • السجل المركزي"),24f)
        label(localized("Choose an action. Open your selected palm, pinch thumb and index, draw a path for 0.4–2.5 seconds and release. Repeat 3 times, then repeat once for an independent test. No actions execute in training.","اختر أمرًا. افتح كف اليد المحددة، واقرص الإبهام والسبابة، وارسم مسارًا خلال 0.4–2.5 ثانية ثم افتح الأصابع. كرر ثلاث مرات ثم مرة للاختبار المستقل. لا تُنفذ أوامر أثناء التدريب."))
        label(localized("Training hand","يد التدريب"));hand=spinner(listOf(localized("Left (cyan)","اليسرى (سماوي)"),localized("Right (amber)","اليمنى (كهرماني)")))
        label(localized("Assigned command","الأمر المرتبط"));command=spinner(RegistryAction.values().map{it.title()})
        saved=label("");info=label(localized("Ready","جاهز"))
        start=Button(this).apply{text=localized("Record 3 examples + 1 test","تسجيل ثلاثة أمثلة + اختبار");panel.addView(this)}
        val stop=Button(this).apply{text=localized("Cancel recording","إلغاء التسجيل");panel.addView(this)}
        Button(this).apply{text=localized("Train pointer, tap, drag or two-hand poses","تدريب المؤشر والنقر والسحب وإيماءات اليدين");panel.addView(this);setOnClickListener{cancel();showPoseStudio()}}
        val forget=Button(this).apply{text=localized("Forget selected command template","حذف نموذج الأمر المحدد");panel.addView(this)}
        label(localized("Runtime hand in custom mode","يد التحكم في الوضع المخصص"))
        val activeHand=spinner(listOf(localized("Left (cyan)","اليسرى (سماوي)"),localized("Right (amber)","اليمنى (كهرماني)")))
        activeHand.setSelection(CameraSettings.readMotionSlot(this))
        label(localized("Choose Custom gesture registry on the main screen and restart. Only the selected hand’s registered commands execute. In Unified mode, use thumb-ring pinch (index open) to draw the same trained paths. Ordinary gestures are inactive in this mode; the pinch is a recording clutch, not a tap. Fixed-center zoom/rotation work in supported apps. Saved data is normalized paths, not camera images.","اختر سجل الإيماءات المخصصة من الرئيسية وأعد التشغيل. تُنفذ فقط أوامر اليد المحددة المسجلة. في الوضع الموحّد استخدم قرص الإبهام والبنصر مع إبقاء السبابة مفتوحة لرسم المسار نفسه. الإيماءات العادية غير نشطة في هذا الوضع؛ القرص لتسجيل المسار وليس للنقر. يعمل التكبير والتدوير حول مركز الشاشة في التطبيقات الداعمة. تُحفظ المسارات المعيارية لا صور الكاميرا."))
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
            if(e==null || e!==YvoraService.instance){info.text=localized("Start engine first.","شغّل المحرك أولًا.");return@setOnClickListener}
            if(!capturing){
                if(e.startMotionTraining(slot(),action())){
                    capturing=true;hand.isEnabled=false;command.isEnabled=false
                    start.text=localized("Review after 4 examples","مراجعة بعد أربعة أمثلة");info.text=localized("Open palm to arm","افتح الكف للاستعداد")
                } else info.text=localized("Camera not ready or active touch/AI.","الكاميرا غير جاهزة أو اللمس/الذكاء الاصطناعي نشط.")
            } else {
                if(e.motionTrainingCount()<4){info.text=localized("Record three examples and one test first.","سجل ثلاثة أمثلة واختبارًا أولًا.");return@setOnClickListener}
                val selected=slot();val assigned=action()
                val examples=e.finishMotionTraining();cancel()
                if(examples==null){info.text=localized("Training expired; try again.","انتهت مهلة التدريب؛ أعد المحاولة.");return@setOnClickListener}
                val review=GestureRegistry.review(assigned,examples,CameraSettings.readRegistry(this,selected))
                val detail=review.conflicts.joinToString("\n"){ c->
                    c.action.title()+": "+(c.degree*100).toInt()+"% — "+if(c.degree>=GestureRegistry.CONFLICT)localized("Conflict: blocked","تعارض: ممنوع")else localized("Warning","تنبيه")
                }
                if(review.blocked){
                    info.text=localized("Not saved. Change the gesture or remove conflicting command.","لم تُحفظ. غيّر الإيماءة أو احذف الأمر المتعارض.")+
                        if(detail.isNotBlank()) "\n"+detail else localized("\nExamples inconsistent or independent test failed.","\nالأمثلة غير متناسقة أو الاختبار المستقل فشل.")
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(localized("Gesture conflict / test failed","تعارض إيماءة / فشل الاختبار"))
                        .setMessage(info.text)
                        .setPositiveButton(localized("Retry","إعادة المحاولة"),null).show()
                    return@setOnClickListener
                }
                val pattern=review.pattern ?: return@setOnClickListener
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle(localized("Save gesture?","حفظ الإيماءة؟")+" "+assigned.title())
                    .setMessage(localized("Independent test passed. Saving replaces only this command for the selected hand. Restart engine to apply.","اجتاز الاختبار المستقل. يستبدل الحفظ هذا الأمر فقط لليد المحددة. أعد تشغيل المحرك للتطبيق.")+if(detail.isBlank())localized("\nNo significant conflict.","\nلا يوجد تعارض ملحوظ.")else "\n"+detail)
                    .setNegativeButton(localized("Cancel","إلغاء"),null)
                    .setPositiveButton(localized("Save","حفظ")){_,_->
                        // Re-check current store; no concurrent edit may bypass the conflict gate.
                        val checked=GestureRegistry.review(assigned,examples,CameraSettings.readRegistry(this,selected))
                        if(checked.blocked){info.text=localized("New conflict; not saved.","تعارض جديد؛ لم تُحفظ.")}
                        else {CameraSettings.saveRegistry(this,selected,assigned,pattern);refresh();info.text=localized("Saved. Restart engine to apply.","تم الحفظ. أعد تشغيل المحرك.")}
                    }.show()
            }
        }
        stop.setOnClickListener{cancel();info.text=localized("Recording cancelled.","أُلغي التسجيل.")}
        forget.setOnClickListener{
            cancel();val selected=slot();val assigned=action()
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(localized("Forget ","حذف ")+assigned.title()+"?")
                .setNegativeButton(localized("Cancel","إلغاء"),null).setPositiveButton(localized("Forget","حذف")){_,_->
                    CameraSettings.saveRegistry(this,selected,assigned,null);refresh();info.text=localized("Forgotten. Restart engine to apply.","تم الحذف. أعد تشغيل المحرك للتطبيق.")
                }.show()
        }
        refresh();setContentView(ScrollView(this).apply{addView(panel)})
    }
    private fun showPoseStudio(){
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,16,24,16)}
        fun label(t:String)=TextView(this).apply{text=t;panel.addView(this)}
        val items=ControlPose.values().map{it.title()}+pairActions.map{it.title()+" • "+localized("two hands","يدان")}
        val pick=Spinner(this).apply{adapter=ArrayAdapter(this@TrajectoryStudioActivity,android.R.layout.simple_spinner_dropdown_item,items);panel.addView(this)}
        label(localized("Choose a pose distinct from default swipes and other active controls. For two hands hold both poses. Use four timed captures (3 learning, 1 independent test); hold steady after tapping capture. Thumb-ring pinch is reserved for drawn paths in Unified mode.","اختر وضعية مميزة عن السحب الافتراضي والتحكم النشط. لليدين ثبّت الوضعيتين معًا. التقط أربع عينات (3 للتعلم وواحدة للاختبار). اثبت بعد الضغط على الالتقاط. قرص الإبهام والبنصر محجوز للمسارات في الوضع الموحّد."))
        val message=label(localized("0/4 samples","0/4 عينات"))
        val captures=mutableListOf<PoseSignature>()
        val pairs=mutableListOf<PairPose>()
        val slot=slot()
        var pending=false
        val capture=Button(this).apply{text=localized("Capture in 2 seconds","التقاط بعد ثانيتين");panel.addView(this)}
        val dialog=androidx.appcompat.app.AlertDialog.Builder(this).setTitle(localized("Pose studio","استوديو الوضعيات"))
            .setView(panel).setNegativeButton(localized("Close","إغلاق"),null)
            .setNeutralButton(localized("Delete selected","حذف المحدد"),null)
            .setPositiveButton(localized("Review and save","مراجعة وحفظ"),null).create()
        dialog.setOnShowListener{
            val save=dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            val delete=dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
            capture.setOnClickListener{
                if(pending)return@setOnClickListener
                val idx=pick.selectedItemPosition
                val count=if(idx<3)captures.size else pairs.size
                if(count>=4)return@setOnClickListener
                pick.isEnabled=false;pending=true;capture.isEnabled=false
                message.text=localized("Hold the pose now…","ثبّت الوضعية الآن…")
                handler.postDelayed({
                    if(!dialog.isShowing || engine==null || engine!==YvoraService.instance){pending=false;return@postDelayed}
                    val e=engine!!
                    if(idx<3){e.stablePose(slot)?.let{captures.add(it)}}
                    else {val a=e.stablePose(0);val b=e.stablePose(1);if(a!=null && b!=null)pairs.add(PairPose(a,b))}
                    val n=if(idx<3)captures.size else pairs.size
                    pending=false;capture.isEnabled=n<4
                    message.text=if(n==count)localized("Unstable or reserved pose; retry.","وضعية غير مستقرة أو محجوزة؛ أعد المحاولة.")else "$n/4"
                },2000L)
            }
            save.setOnClickListener{
                if(pending)return@setOnClickListener
                val idx=pick.selectedItemPosition
                val controls=Array(2){CameraSettings.readControls(this,it)}
                val existing=CameraSettings.readPairs(this)
                if(idx<3){
                    val a=ControlPose.values()[idx];val learned=PoseRegistry.learn(captures)
                    val conflicts=PoseRegistry.conflicts(a,captures+listOfNotNull(learned),controls[slot],existing,slot)
                    if(learned==null || conflicts.isNotEmpty()){
                        message.text=localized("Not saved: test failed or conflict. ","لم تُحفظ: فشل الاختبار أو تعارض. ")+conflicts.joinToString("\n");return@setOnClickListener
                    }
                    CameraSettings.saveControl(this,slot,a,learned)
                }else{
                    val a=pairActions[idx-3]
                    val left=PoseRegistry.learn(pairs.map{it.left});val right=PoseRegistry.learn(pairs.map{it.right})
                    val learned=if(left!=null&&right!=null)PairPose(left,right)else null
                    val conflicts=PoseRegistry.pairConflicts(pairs+listOfNotNull(learned),a,controls,existing)
                    if(learned==null || conflicts.isNotEmpty()){
                        message.text=localized("Not saved: test failed or conflict. ","لم تُحفظ: فشل الاختبار أو تعارض. ")+conflicts.joinToString("\n");return@setOnClickListener
                    }
                    CameraSettings.savePair(this,a,learned)
                }
                info.text=localized("Saved. Select Unified gestures and restart engine.","تم الحفظ. اختر الإيماءات الموحّدة وأعد تشغيل المحرك.");dialog.dismiss()
            }
            delete.setOnClickListener{
                val idx=pick.selectedItemPosition
                androidx.appcompat.app.AlertDialog.Builder(this).setMessage(localized("Remove selected template and restore default behavior?","حذف النموذج المحدد واستعادة السلوك الافتراضي؟"))
                    .setNegativeButton(localized("Cancel","إلغاء"),null).setPositiveButton(localized("Delete","حذف")){_,_->
                        val controls=Array(2){CameraSettings.readControls(this,it)}
                        var pairMap=CameraSettings.readPairs(this)
                        if(idx<3)controls[slot]=controls[slot]-ControlPose.values()[idx] else pairMap=pairMap-pairActions[idx-3]
                        val conflicts=PoseRegistry.validate(controls,pairMap)
                        if(conflicts.isNotEmpty()){message.text=localized("Restoring defaults conflicts with other saved poses. Remove those first: ","استعادة الافتراضي تتعارض مع وضعيات محفوظة. احذفها أولًا: ")+conflicts.joinToString(" / ");return@setPositiveButton}
                        if(idx<3)CameraSettings.saveControl(this,slot,ControlPose.values()[idx],null)
                        else CameraSettings.savePair(this,pairActions[idx-3],null)
                        dialog.dismiss();info.text=localized("Removed. Restart engine.","تم الحذف. أعد تشغيل المحرك.")
                    }.show()
            }
        }
        dialog.show()
    }
    override fun onResume(){
        super.onResume();YvoraAccessibilityService.stopAi(localized("AI stopped for trajectory training","أُوقف التحكم الذكي لتدريب المسارات"))
        engine=YvoraService.instance;previousCalibration=engine?.isCalibrating==true
        engine?.setCalibrationMode(true);handler.post(tick)
    }
    override fun onPause(){
        handler.removeCallbacks(tick);cancel()
        if(engine===YvoraService.instance) engine?.setCalibrationMode(previousCalibration)
        engine=null;super.onPause()
    }
}
