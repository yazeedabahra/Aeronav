package com.yvora.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class TrajectoryStudioActivity:AppCompatActivity() {
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
        saved.text=if(templates.isEmpty()) "No saved trajectories for this hand."
            else "Saved commands: "+templates.keys.joinToString{it.name}
    }
    private fun cancel(){
        engine?.cancelMotionTraining();capturing=false
        hand.isEnabled=true;command.isEnabled=true;start.text="Start recording 3 examples"
    }
    private val tick=object:Runnable{
        override fun run(){
            val e=engine
            if(capturing){
                if(e==null || e!==YvoraService.instance || !e.motionTrainingActive()) {
                    cancel();info.text="Capture interrupted or expired."
                } else {
                    val count=e.motionTrainingCount()
                    info.text="Examples: $count/3\n"+e.motionTrainingStatus()
                    if(count==3) start.text="Review and save template"
                }
            } else if(e==null || e!==YvoraService.instance) info.text="Start the tracking engine first."
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
        label("CUSTOM TRAJECTORY STUDIO",24f)
        label("Choose a swipe command. Open your selected palm, pinch thumb and index, draw a path for 0.4–2.5 seconds, then release. Repeat three times. No gestures execute during recording.")
        label("Training hand");hand=spinner(listOf("Left (cyan)","Right (amber)"))
        label("Assigned command");command=spinner(trajectoryActions.map{it.name})
        saved=label("");info=label("Ready")
        start=Button(this).apply{text="Start recording 3 examples";panel.addView(this)}
        val stop=Button(this).apply{text="Cancel recording";panel.addView(this)}
        val forget=Button(this).apply{text="Forget selected command template";panel.addView(this)}
        label("Runtime hand in custom mode")
        val activeHand=spinner(listOf("Left (cyan)","Right (amber)"))
        activeHand.setSelection(CameraSettings.readMotionSlot(this))
        label("Changes apply after engine restart. Choose Custom trajectories on the main screen. Only selected hand's saved swipes execute; normal taps, drags, zoom and rotation are disabled. Saved data is normalized 16-point paths and duration, never camera frames.")
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
            if(e==null || e!==YvoraService.instance){info.text="Start engine first.";return@setOnClickListener}
            if(!capturing){
                if(e.startMotionTraining(slot(),action())){
                    capturing=true;hand.isEnabled=false;command.isEnabled=false
                    start.text="Review after 3 examples";info.text="Open palm to arm"
                } else info.text="Camera not ready or active touch/AI."
            } else {
                if(e.motionTrainingCount()<3){info.text="Record three complete examples first.";return@setOnClickListener}
                val selected=slot();val assigned=action()
                val pattern=e.finishMotionTraining();cancel()
                if(pattern==null){info.text="Examples inconsistent or expired; repeat training.";return@setOnClickListener}
                val others=CameraSettings.readMotions(this,selected).filterKeys{it!=assigned}
                if(others.values.any{MotionSimilarity.degree(pattern,it)>=.65f}){
                    info.text="Too similar to another saved command. Choose a more distinct path.";return@setOnClickListener
                }
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Save ${assigned.name} trajectory?")
                    .setMessage("Three consistent examples. Mean duration ${pattern.duration} ms. Replaces this hand's template for this command. Restart engine after saving.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Save"){_,_->CameraSettings.saveMotion(this,selected,assigned,pattern);refresh();info.text="Saved. Restart engine to apply."}.show()
            }
        }
        stop.setOnClickListener{cancel();info.text="Recording cancelled."}
        forget.setOnClickListener{
            cancel();val selected=slot();val assigned=action()
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Forget ${assigned.name}?")
                .setNegativeButton("Cancel",null).setPositiveButton("Forget"){_,_->
                    CameraSettings.saveMotion(this,selected,assigned,null);refresh();info.text="Forgotten. Restart engine to apply."
                }.show()
        }
        refresh();setContentView(ScrollView(this).apply{addView(panel)})
    }
    override fun onResume(){
        super.onResume();YvoraAccessibilityService.stopAi("AI stopped for trajectory training")
        engine=YvoraService.instance;previousCalibration=engine?.isCalibrating==true
        engine?.setCalibrationMode(true);handler.post(tick)
    }
    override fun onPause(){
        handler.removeCallbacks(tick);cancel()
        if(engine===YvoraService.instance) engine?.setCalibrationMode(previousCalibration)
        engine=null;super.onPause()
    }
}
