package com.yvora.app

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Provider output stays a preview until separately approved and started on the target screen. */
class AiLabActivity : AppCompatActivity() {
    private val executor=Executors.newSingleThreadExecutor()
    private var generation=0
    private var pending=false
    private var pausedService: YvoraService?=null
    private var wasCalibrating=false
    private lateinit var keyField:EditText
    private lateinit var result:TextView
    private lateinit var submit:Button
    private lateinit var diagnose:Button
    private lateinit var applyFix:Button
    private var proposed:RepairProposal?=null
    private var plan:List<AiAction>?=null
    private var planAt=0L
    private lateinit var execute:Button
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(24,24,24,24)
        }
        fun text(value:String,size:Float=14f)=TextView(this).apply { text=value;textSize=size;panel.addView(this) }
        fun edit(hintText:String,secret:Boolean=false)=EditText(this).apply {
            hint=hintText
            isSaveEnabled=false
            if(secret) {
                inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                if(Build.VERSION.SDK_INT>=26) importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            panel.addView(this)
        }
        text("YVORA AI CORE",24f)
        text("AI navigation • review, run and diagnostics")
        text("Only the command you type, or a diagnostic report you explicitly review and approve, is sent to your selected provider. No camera images, screen text, raw logs or hand coordinates are sent. Use a restricted test API key; do not use a production key. Keys are not saved. API usage may cost money. Production distribution requires a secure backend.")
        val providers=AiProvider.values()
        val spinner=Spinner(this).apply {
            adapter=ArrayAdapter(this@AiLabActivity,android.R.layout.simple_spinner_dropdown_item,providers.map { it.title })
            panel.addView(this)
        }
        val model=edit("Model ID from provider dashboard")
        keyField=edit("Temporary / restricted API key",true)
        val prompt=edit("Command (e.g. swipe right, then go back)")
        submit=Button(this).apply { text="Preview AI command";panel.addView(this) }
        execute=Button(this).apply { text="Prepare approved AI actions";isEnabled=false;panel.addView(this) }
        diagnose=Button(this).apply { text="Review diagnostic report";panel.addView(this) }
        applyFix=Button(this).apply { text="Apply suggested lower camera detail";isEnabled=false;panel.addView(this) }
        val clear=Button(this).apply { text="Clear key and cancel preview";panel.addView(this) }
        result=text("No model connected yet.")
        val scroll=ScrollView(this).apply { addView(panel) }
        setContentView(scroll)
        clear.setOnClickListener {
            generation++
            keyField.text.clear()
            result.text="Cleared. Any already-sent provider request may finish remotely."
            submit.isEnabled=!pending
            diagnose.isEnabled=!pending
            proposed=null
            applyFix.isEnabled=false
            clearPlan()
            YvoraAccessibilityService.stopAi()
        }
        submit.setOnClickListener {
            if(pending) return@setOnClickListener
            val provider=providers[spinner.selectedItemPosition]
            val modelId=model.text.toString().trim()
            val key=keyField.text.toString().trim()
            val command=prompt.text.toString().trim()
            if(modelId.isEmpty() || key.isEmpty() || command.isEmpty()) {
                result.text="Enter model ID, API key, and command."
                return@setOnClickListener
            }
            clearPlan()
            val ticket=++generation
            pending=true
            proposed=null
            applyFix.isEnabled=false
            submit.isEnabled=false
            diagnose.isEnabled=false
            keyField.text.clear()
            result.text="Requesting preview from ${provider.title}…"
            executor.execute {
                var actions:List<AiAction>?=null
                val message=try {
                    val response=AiProviderClient.plan(provider,modelId,key,command)
                    actions=AiCommandGate.parse(response)
                    if(actions==null) "Unsupported or invalid response. No actions executed."
                    else "Validated plan:\n"+actions!!.joinToString("\n") { it.name }+"\nReview before execution."
                } catch(e:AiNetworkException) { e.message ?: "Provider rejected request." }
                catch(e:IllegalArgumentException) { "Invalid configuration or command." }
                catch(e:Exception) { "Connection or response error. Check model ID, key, and internet." }
                runOnUiThread {
                    pending=false
                    if(!isDestroyed) {
                        if(ticket==generation) {
                            result.text=message
                            val valid=actions?.takeIf { AiCommandGate.executable(it) }
                            plan=valid;planAt=android.os.SystemClock.uptimeMillis()
                            execute.isEnabled=valid!=null
                            if(actions!=null && valid==null) result.append("\nExecution blocked: BACK/HOME must be last.")
                        }
                        submit.isEnabled=true
                        diagnose.isEnabled=true
                    }
                }
            }
        }
        execute.setOnClickListener {
            val approved=plan ?: return@setOnClickListener
            if(pending || android.os.SystemClock.uptimeMillis()-planAt>120000L) {
                clearPlan();result.text="Plan expired. Request a new preview.";return@setOnClickListener
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Prepare these navigation actions?")
                .setMessage(approved.joinToString("\n") { it.name }+"\n\nChoose the destination app yourself, then press Run here on the floating AI control. Stop AI cancels remaining steps. No screen content is sent to the model.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Prepare") { _,_ ->
                    if(plan!=approved || pending || android.os.SystemClock.uptimeMillis()-planAt>120000L) {
                        clearPlan();result.text="Plan expired."
                    } else if(YvoraAccessibilityService.prepareAiPlan(approved)) {
                        clearPlan()
                        keyField.text.clear()
                        result.text="Plan prepared. Choose destination and press Run here."
                        if(!moveTaskToBack(true)) YvoraAccessibilityService.stopAi("Could not leave Yvora. AI cancelled.")
                    } else {
                        result.text="Cannot prepare: enable Accessibility, end active drag, or unlock the screen."
                    }
                }.show()
        }
        diagnose.setOnClickListener {
            if(pending) return@setOnClickListener
            val provider=providers[spinner.selectedItemPosition]
            val modelId=model.text.toString().trim()
            if(modelId.isEmpty() || keyField.text.isBlank()) {
                result.text="Enter model ID and API key first."
                return@setOnClickListener
            }
            val state=YvoraService.instance?.diagnosticState() ?: "engine_running=false; live_metrics=unavailable"
            val report="Yvora version 1.8-dev\n$state\n"+DiagnosticCounters.report()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Send only this report to ${provider.title}?")
                .setMessage(report+"\n\nAPI usage may cost money. No screenshots or raw logs are included.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Analyze") { _,_ ->
                    if(!pending) {
                        val key=keyField.text.toString().trim()
                        keyField.text.clear()
                        clearPlan()
                        val ticket=++generation
                        pending=true
                        proposed=null
                        applyFix.isEnabled=false
                        submit.isEnabled=false
                        diagnose.isEnabled=false
                        result.text="Analyzing diagnostics…"
                        executor.execute {
                            var repair:RepairProposal?=null
                            val message=try {
                                repair=DiagnosticGate.parse(AiProviderClient.diagnose(provider,modelId,key,report))
                                repair?.let { "Suggested action: ${it.action.name}\n${it.explanation}\nNo change applied." }
                                    ?: "Invalid diagnostic response. No change applied."
                            } catch(e:AiNetworkException) { e.message ?: "Provider rejected request." }
                            catch(e:Exception) { "Connection or response error. Check configuration." }
                            runOnUiThread {
                                pending=false
                                if(!isDestroyed) {
                                    if(ticket==generation) {
                                        proposed=repair
                                        result.text=message
                                        applyFix.isEnabled=DiagnosticGate.canLowerDetail(
                                            repair?.action ?: RepairAction.KEEP_SETTINGS,
                                            CameraSettings.readDetail(this)==CameraDetail.DISTANCE)
                                    }
                                    submit.isEnabled=true
                                    diagnose.isEnabled=true
                                }
                            }
                        }
                    }
                }.show()
        }
        applyFix.setOnClickListener {
            val repair=proposed ?: return@setOnClickListener
            if(!DiagnosticGate.canLowerDetail(repair.action,CameraSettings.readDetail(this)==CameraDetail.DISTANCE)) return@setOnClickListener
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Lower camera detail?")
                .setMessage("This saves AUTO camera detail for the next engine start. You can restore Distance Detail in settings. No other setting changes.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Apply") { _,_ ->
                    CameraSettings.saveDetail(this,CameraDetail.AUTO)
                    proposed=null
                    applyFix.isEnabled=false
                    result.text="AUTO detail saved. Stop and restart the engine to test. No improvement has been measured yet."
                }.show()
        }
    }
    private fun clearPlan() { plan=null;planAt=0L;execute.isEnabled=false }
    override fun onResume() {
        super.onResume()
        if(YvoraAccessibilityService.aiActive()) YvoraAccessibilityService.stopAi("AI cancelled on return to Yvora")
        pausedService=YvoraService.instance
        wasCalibrating=pausedService?.isCalibrating==true
        pausedService?.setCalibrationMode(true)
    }
    override fun onPause() {
        if (YvoraService.instance === pausedService) pausedService?.setCalibrationMode(wasCalibrating)
        pausedService=null
        super.onPause()
    }
    override fun onDestroy() {
        generation++
        keyField.text.clear()
        executor.shutdownNow()
        super.onDestroy()
    }
}
