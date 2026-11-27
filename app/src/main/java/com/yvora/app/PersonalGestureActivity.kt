package com.yvora.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** Supervised personal pose calibration. No actions are executed here. */
class PersonalGestureActivity: LocalizedActivity() {
    private val handler=Handler(Looper.getMainLooper())
    private var service:YvoraService?=null
    private var previousCalibration=false
    private var slot=0
    private var capturing=false
    private lateinit var state:TextView
    private lateinit var saved:TextView
    private lateinit var start:Button
    private lateinit var picker:Spinner
    private fun fmt(v:Float)=String.format(Locale.ROOT,"%.2f",v)
    private fun refreshSaved() {
        val p=CameraSettings.readPersonalPose(this,slot)
        saved.text=if(p==null) tr("No saved pose for selected hand.","لا توجد وضعية محفوظة لليد المحددة.") else
            tr("Saved memberships: edge ","درجات الانتماء المحفوظة: حافة ")+fmt(p.edge)+tr(" • extension "," • امتداد ")+fmt(p.extension)+tr(" • together "," • تقارب ")+fmt(p.together)
    }
    private fun cancelCapture() {
        service?.cancelPoseTraining()
        capturing=false
        start.text=tr("Start learning","بدء التعلم");picker.isEnabled=true
    }
    private val tick=object:Runnable{
        override fun run(){
            val engine=YvoraService.instance
            val tr=engine?.fuzzyTrace(slot)
            if(capturing && (engine!==service || engine?.trainingActive()!=true)) {
                cancelCapture();state.text=tr("Capture interrupted or expired. Try again.","انقطع التسجيل أو انتهت مهلته. حاول مجددًا.")
            } else if(engine==null || engine!==service) state.text=tr("Start the engine from the main screen first.","شغّل المحرك من الشاشة الرئيسية أولًا.")
            else {
                val f=tr?.features
                state.text=buildString {
                    append(if(capturing) tr("Accepted examples: ","الأمثلة المقبولة: ")+engine.trainingCount()+tr(" / minimum 10\n"," / الحد الأدنى 10\n") else tr("Ready to learn\n","جاهز للتعلم\n"))
                    if(tr?.present!=true || f==null) append(tr("Selected hand not reliably detected.","لم تُرصد اليد المحددة بشكل موثوق."))
                    else {
                        append(tr("Tracking quality ","جودة التتبع ")+fmt(tr.quality)+"\n")
                        append(tr("Edge ","الحافة ")+fmt(f.edge)+tr(" • extension "," • امتداد ")+fmt(f.extension)+tr(" • together "," • تقارب ")+fmt(f.together)+"\n")
                        append(tr("Personal membership ","الانتماء الشخصي ")+fmt(tr.personal))
                        if(!f.eligible() || tr.pinchRatio<.34f) append(tr("\nUse a slightly edge-on hand with fingers extended together and thumb separated.","\nوجّه حافة الكف قليلًا مع مد الأصابع متقاربة وفصل الإبهام."))
                    }
                }
            }
            handler.postDelayed(this,300L)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
        fun text(value:String,size:Float=15f)=TextView(this).apply { text=value;textSize=size;panel.addView(this) }
        text(tr("PERSONAL GESTURE STUDIO","استوديو الإيماءات الشخصية"),24f)
        text(tr("Learn your own comfortable variation of the horizontal swipe pose. Keep the selected palm slightly edge-on, fingers extended together, thumb separated. Gently vary the pose for 3–10 seconds. No taps, drags or swipes will execute during training.","علّم التطبيق وضعية السحب الأفقي المريحة لك. اجعل الكف مائلًا قليلًا نحو حافته مع مد الأصابع متقاربة وفصل الإبهام. غيّر الوضعية قليلًا لمدة 3–10 ثوانٍ. لا تُنفذ إيماءات خلال التدريب."))
        picker=Spinner(this).apply {
            adapter=ArrayAdapter(this@PersonalGestureActivity,android.R.layout.simple_spinner_dropdown_item,listOf(tr("Left hand (cyan)","اليسرى (سماوي)"),tr("Right hand (amber)","اليمنى (كهرماني)")))
            panel.addView(this)
        }
        saved=text("")
        state=text(tr("Waiting for engine…","بانتظار المحرك…"))
        start=Button(this).apply{text=tr("Start learning","بدء التعلم");panel.addView(this)}
        val cancel=Button(this).apply{text=tr("Cancel learning","إلغاء التعلم");panel.addView(this)}
        val reset=Button(this).apply{text=tr("Forget saved pose for this hand","حذف الوضعية المحفوظة لهذه اليد");panel.addView(this)}
        text(tr("Only three averaged membership values are saved locally. No photos, coordinates or API requests. The learned pose is used only with Assisted gestures enabled; actual swipes still require horizontal movement and safety gates.","تُحفظ ثلاثة متوسطات لدرجات الانتماء محليًا فقط. لا صور أو إحداثيات أو طلبات API. تُستخدم الوضعية المتعلمة عند تفعيل الإيماءات المساعدة فقط، ويبقى السحب مشروطًا بالحركة الأفقية وقواعد الأمان."))
        picker.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                if(slot!=position) {cancelCapture();slot=position}
                refreshSaved()
            }
        }
        start.setOnClickListener {
            val engine=service
            if(engine==null || engine!==YvoraService.instance) { Toast.makeText(this,tr("Start engine first","شغّل المحرك أولًا"),Toast.LENGTH_SHORT).show();return@setOnClickListener }
            if(!capturing) {
                if(engine.beginPoseTraining(slot)) {capturing=true;start.text=tr("Finish and review","إنهاء ومراجعة");picker.isEnabled=false}
                else Toast.makeText(this,tr("Camera not ready; wait for tracking or stop active AI","الكاميرا غير جاهزة؛ انتظر التتبع أو أوقف التحكم الذكي."),Toast.LENGTH_SHORT).show()
            } else {
                val selected=slot
                val result=engine.finishPoseTraining()
                cancelCapture()
                if(result==null) Toast.makeText(this,tr("Not enough consistent examples; retry for 3–10 seconds.","الأمثلة المتناسقة غير كافية؛ أعد المحاولة لمدة 3–10 ثوانٍ."),Toast.LENGTH_LONG).show()
                else androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(tr("Save learned swipe pose?","حفظ وضعية السحب المتعلمة؟"))
                    .setMessage(tr("Edge ","الحافة ")+fmt(result.edge)+tr(" • extension "," • امتداد ")+fmt(result.extension)+tr(" • together "," • تقارب ")+fmt(result.together)+tr("\nThis replaces only the selected hand's saved pose. Other safety gates remain unchanged.","\nيستبدل هذا وضعية اليد المحددة فقط. تبقى قواعد الأمان الأخرى دون تغيير."))
                    .setNegativeButton(tr("Cancel","إلغاء"),null)
                    .setPositiveButton(tr("Save","حفظ")) { _,_ ->
                        CameraSettings.savePersonalPose(this,selected,result)
                        YvoraService.instance?.reloadPersonalPose(selected)
                        refreshSaved()
                    }.show()
            }
        }
        cancel.setOnClickListener { cancelCapture();state.text=tr("Learning cancelled.","أُلغي التعلم.") }
        reset.setOnClickListener {
            cancelCapture()
            val selected=slot
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(tr("Forget saved pose?","حذف الوضعية المحفوظة؟"))
                .setMessage(tr("Restore the original fuzzy pose rules for this hand.","استعادة قواعد الوضعية الضبابية الأصلية لهذه اليد."))
                .setNegativeButton(tr("Cancel","إلغاء"),null)
                .setPositiveButton(tr("Forget","حذف")){_,_->
                    CameraSettings.savePersonalPose(this,selected,null)
                    YvoraService.instance?.reloadPersonalPose(selected)
                    refreshSaved()
                }.show()
        }
        refreshSaved()
        setContentView(ScrollView(this).apply{addView(panel)})
    }
    override fun onResume() {
        super.onResume()
        YvoraAccessibilityService.stopAi(tr("AI stopped for personal gesture training","أُوقف التحكم الذكي لتدريب الإيماءة الشخصية"))
        service=YvoraService.instance;previousCalibration=service?.isCalibrating==true
        service?.setCalibrationMode(true)
        handler.post(tick)
    }
    override fun onPause() {
        handler.removeCallbacks(tick);cancelCapture()
        if(service===YvoraService.instance) service?.setCalibrationMode(previousCalibration)
        service=null
        super.onPause()
    }
}
