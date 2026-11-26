package com.yvora.app

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Explicit single-request preview lab, isolated from all touch injection. */
class AiLabActivity : AppCompatActivity() {
    private val executor=Executors.newSingleThreadExecutor()
    private var generation=0
    private var pending=false
    private var pausedService: YvoraService?=null
    private var wasCalibrating=false
    private lateinit var keyField:EditText
    private lateinit var result:TextView
    private lateinit var submit:Button
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
        text("Model Lab • preview only")
        text("Only the command you type is sent to your selected provider. No camera, screen, or gesture data is sent. Use a restricted test API key; do not use a production key. Keys are not saved. API usage may cost money. Production distribution requires a secure backend.")
        val providers=AiProvider.values()
        val spinner=Spinner(this).apply {
            adapter=ArrayAdapter(this@AiLabActivity,android.R.layout.simple_spinner_dropdown_item,providers.map { it.title })
            panel.addView(this)
        }
        val model=edit("Model ID from provider dashboard")
        keyField=edit("Temporary / restricted API key",true)
        val prompt=edit("Command (e.g. swipe right, then go back)")
        submit=Button(this).apply { text="Preview AI command";panel.addView(this) }
        val clear=Button(this).apply { text="Clear key and cancel preview";panel.addView(this) }
        result=text("No model connected yet.")
        val scroll=ScrollView(this).apply { addView(panel) }
        setContentView(scroll)
        clear.setOnClickListener {
            generation++
            keyField.text.clear()
            result.text="Cleared. Any already-sent provider request may finish remotely."
            submit.isEnabled=!pending
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
            val ticket=++generation
            pending=true
            submit.isEnabled=false
            keyField.text.clear()
            result.text="Requesting preview from ${provider.title}…"
            executor.execute {
                val message=try {
                    val response=AiProviderClient.plan(provider,modelId,key,command)
                    val actions=AiCommandGate.parse(response)
                    if(actions==null) "Unsupported or invalid response. No actions executed."
                    else "Validated preview only:\n"+actions.joinToString("\n") { it.name }+"\nNo actions executed."
                } catch(e:AiNetworkException) { e.message ?: "Provider rejected request." }
                catch(e:IllegalArgumentException) { "Invalid configuration or command." }
                catch(e:Exception) { "Connection or response error. Check model ID, key, and internet." }
                runOnUiThread {
                    pending=false
                    if(!isDestroyed) {
                        if(ticket==generation) result.text=message
                        submit.isEnabled=true
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
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
