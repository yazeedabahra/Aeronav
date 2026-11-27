package com.yvora.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Explicit local start on the visible target screen. No remote response can
 * invoke this directly. At most four navigation operations, no tap/type API.
 * All state and dispatch callbacks run on main. No screen text is collected.
 */
internal class AiActionSession(private val service:YvoraAccessibilityService) {
    private val handler=Handler(Looper.getMainLooper())
    private var panel:LinearLayout?=null
    private var manager:WindowManager?=null
    private var status:TextView?=null
    private var start:Button?=null
    private var actions:List<AiAction> = emptyList()
    private var generation=0L
    private var deadline=0L
    private var index=0
    private var target:String?=null
    var active=false
        private set
    var running=false
        private set

    fun prepare(plan:List<AiAction>):Boolean {
        if(active || !AiCommandGate.executable(plan) || YvoraAccessibilityService.dragBusy() || !unlocked()) return false
        val wm=service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layout=LinearLayout(service).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(12,8,12,8)
            setBackgroundColor(Color.rgb(25,35,48))
        }
        val caption=TextView(service).apply {
            text="Yvora AI: ${plan.size} navigation action(s). Choose screen, then Run here."
            setTextColor(Color.WHITE)
            textSize=13f
        }
        val run=Button(service).apply { text="Run here";isAllCaps=false }
        val stop=Button(service).apply { text="Stop AI";isAllCaps=false }
        layout.addView(caption)
        layout.addView(LinearLayout(service).apply { orientation=LinearLayout.HORIZONTAL;addView(run);addView(stop) })
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;y=24 }
        try { wm.addView(layout,params) } catch(_:RuntimeException) { return false }
        panel=layout;manager=wm;status=caption;start=run
        actions=plan.toList();index=0;target=null;running=false;active=true
        deadline=SystemClock.uptimeMillis()+25000L
        val token=++generation
        // Reset hand touch ownership and hide cursors before accepting the plan.
        YvoraService.instance?.forceHidePointer()
        run.setOnClickListener { if(active && !running && generation==token) begin(token) }
        stop.setOnClickListener { cancel("AI stopped by user") }
        handler.postDelayed({ if(active && generation==token && !running) cancel("AI plan expired") },25000L)
        return true
    }

    private fun unlocked():Boolean {
        val power=service.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock=service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return power.isInteractive && !lock.isKeyguardLocked && !lock.isDeviceLocked
    }
    private fun packageNow():String? {
        val root=try { service.rootInActiveWindow } catch(_:RuntimeException) { null } ?: return null
        @Suppress("DEPRECATION")
        return try { root.packageName?.toString() } finally { root.recycle() }
    }
    private fun permitted(pkg:String?):Boolean {
        if(pkg.isNullOrBlank() || pkg==service.packageName || pkg=="android") return false
        val lower=pkg.lowercase(java.util.Locale.ROOT)
        return !listOf("systemui","settings","permissioncontroller","packageinstaller","keyguard","authenticator").any { lower.contains(it) }
    }

    private fun begin(token:Long) {
        if(!unlocked() || SystemClock.uptimeMillis()>deadline || YvoraAccessibilityService.dragBusy()) {
            cancel("AI not started: screen locked, expired or touch busy");return
        }
        val pkg=packageNow()
        if(!permitted(pkg)) {
            status?.text="Choose an ordinary app screen first."
            return
        }
        target=pkg
        running=true
        deadline=SystemClock.uptimeMillis()+12000L
        start?.isEnabled=false
        status?.text="AI running in selected app. Stop AI cancels remaining actions."
        handler.postDelayed({ step(token) },350L)
    }
    private fun step(token:Long) {
        if(!active || !running || generation!=token) return
        val current=packageNow()
        if(!unlocked() || SystemClock.uptimeMillis()>deadline ||
            !permitted(current) || current!=target || YvoraAccessibilityService.dragBusy()) {
            cancel("AI stopped: screen changed, locked or touch busy");return
        }
        if(index>=actions.size) { cancel("AI navigation plan completed");return }
        val a=actions[index]
        status?.text="AI ${index+1}/${actions.size}: ${a.name}"
        if(a==AiAction.BACK || a==AiAction.HOME) {
            val code=if(a==AiAction.BACK) AccessibilityService.GLOBAL_ACTION_BACK else AccessibilityService.GLOBAL_ACTION_HOME
            val ok=service.performGlobalAction(code)
            cancel(if(ok) "AI global navigation accepted by Android" else "AI action rejected by Android")
            return
        }
        val direction=when(a) {
            AiAction.SWIPE_LEFT -> "left"
            AiAction.SWIPE_RIGHT -> "right"
            AiAction.SWIPE_UP -> "up"
            AiAction.SWIPE_DOWN -> "down"
            else -> return
        }
        val stepIndex=index
        val ok=service.doScroll(direction) { completed ->
            if(!active || generation!=token || index!=stepIndex) return@doScroll
            if(!completed) { cancel("AI swipe interrupted by Android");return@doScroll }
            index++
            if(index==actions.size) cancel("AI navigation strokes completed")
            else handler.postDelayed({ step(token) },500L)
        }
        if(!ok) { cancel("AI swipe rejected by Android");return }
        handler.postDelayed({
            if(active && generation==token && index==stepIndex) cancel("AI swipe callback timed out")
        },2000L)
    }
    fun cancel(message:String?=null) {
        if(!active) return
        generation++;active=false;running=false;target=null;actions=emptyList()
        handler.removeCallbacksAndMessages(null)
        panel?.let { try { manager?.removeView(it) } catch(_:RuntimeException) {} }
        panel=null;manager=null;status=null;start=null
        // An already dispatched, short stroke can finish; remaining steps cannot run.
        YvoraService.instance?.forceHidePointer()
        if(message!=null) Toast.makeText(service,message,Toast.LENGTH_SHORT).show()
    }
}
