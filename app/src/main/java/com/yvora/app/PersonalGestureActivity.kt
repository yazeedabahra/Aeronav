package com.yvora.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** Supervised personal pose calibration. No actions are executed here. */
class PersonalGestureActivity:AppCompatActivity() {
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
        saved.text=if(p==null) "No saved pose for selected hand." else
            "Saved memberships: edge "+fmt(p.edge)+" • extension "+fmt(p.extension)+" • together "+fmt(p.together)
    }
    private fun cancelCapture() {
        service?.cancelPoseTraining()
        capturing=false
        start.text="Start learning";picker.isEnabled=true
    }
    private val tick=object:Runnable{
        override fun run(){
            val engine=YvoraService.instance
            val tr=engine?.fuzzyTrace(slot)
            if(capturing && (engine!==service || engine?.trainingActive()!=true)) {
                cancelCapture();state.text="Capture interrupted or expired. Try again."
            } else if(engine==null || engine!==service) state.text="Start the engine from the main screen first."
            else {
                val f=tr?.features
                state.text=buildString {
                    append(if(capturing) "Accepted examples: "+engine.trainingCount()+" / minimum 10\n" else "Ready to learn\n")
                    if(tr?.present!=true || f==null) append("Selected hand not reliably detected.")
                    else {
                        append("Tracking quality "+fmt(tr.quality)+"\n")
                        append("Edge "+fmt(f.edge)+" • extension "+fmt(f.extension)+" • together "+fmt(f.together)+"\n")
                        append("Personal membership "+fmt(tr.personal))
                        if(!f.eligible() || tr.pinchRatio<.34f) append("\nUse a slightly edge-on hand with fingers extended together and thumb separated.")
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
        text("PERSONAL GESTURE STUDIO",24f)
        text("Learn your own comfortable variation of the horizontal swipe pose. Keep the selected palm slightly edge-on, fingers extended together, thumb separated. Gently vary the pose for 3–10 seconds. No taps, drags or swipes will execute during training.")
        picker=Spinner(this).apply {
            adapter=ArrayAdapter(this@PersonalGestureActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Left hand (cyan)","Right hand (amber)"))
            panel.addView(this)
        }
        saved=text("")
        state=text("Waiting for engine…")
        start=Button(this).apply{text="Start learning";panel.addView(this)}
        val cancel=Button(this).apply{text="Cancel learning";panel.addView(this)}
        val reset=Button(this).apply{text="Forget saved pose for this hand";panel.addView(this)}
        text("Only three averaged membership values are saved locally. No photos, coordinates or API requests. The learned pose is used only with Assisted gestures enabled; actual swipes still require horizontal movement and safety gates.")
        picker.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                if(slot!=position) {cancelCapture();slot=position}
                refreshSaved()
            }
        }
        start.setOnClickListener {
            val engine=service
            if(engine==null || engine!==YvoraService.instance) { Toast.makeText(this,"Start engine first",Toast.LENGTH_SHORT).show();return@setOnClickListener }
            if(!capturing) {
                if(engine.beginPoseTraining(slot)) {capturing=true;start.text="Finish and review";picker.isEnabled=false}
                else Toast.makeText(this,"Camera not ready; wait for tracking or stop active AI",Toast.LENGTH_SHORT).show()
            } else {
                val selected=slot
                val result=engine.finishPoseTraining()
                cancelCapture()
                if(result==null) Toast.makeText(this,"Not enough consistent examples; retry for 3–10 seconds.",Toast.LENGTH_LONG).show()
                else androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Save learned swipe pose?")
                    .setMessage("Edge "+fmt(result.edge)+" • extension "+fmt(result.extension)+" • together "+fmt(result.together)+"\nThis replaces only the selected hand's saved pose. Other safety gates remain unchanged.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Save") { _,_ ->
                        CameraSettings.savePersonalPose(this,selected,result)
                        YvoraService.instance?.reloadPersonalPose(selected)
                        refreshSaved()
                    }.show()
            }
        }
        cancel.setOnClickListener { cancelCapture();state.text="Learning cancelled." }
        reset.setOnClickListener {
            cancelCapture()
            val selected=slot
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Forget saved pose?")
                .setMessage("Restore the original fuzzy pose rules for this hand.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Forget"){_,_->
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
        YvoraAccessibilityService.stopAi("AI stopped for personal gesture training")
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
