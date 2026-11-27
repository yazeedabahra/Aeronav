package com.yvora.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class YvoraAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yService"
        var instance: YvoraAccessibilityService? = null

        fun performScroll(direction: String): Boolean {
            if(aiActive()) return false
            return instance?.doScroll(direction) ?: false
        }

        fun performTap(x: Float, y: Float): Boolean {
            if(aiActive()) return false
            return instance?.doTap(x, y) ?: false
        }

        fun performBack(): Boolean {
            if(aiActive()) return false
            return instance?.doBack() ?: false
        }

        fun isEnabled() = instance != null
        internal fun beginDrag(x: Float, y: Float): Boolean = !aiActive() && !zoomBusy() && instance?.drag?.start(x, y) == true
        internal fun moveDrag(x: Float, y: Float) { instance?.drag?.move(x, y) }
        internal fun endDrag(cancel: Boolean = false) { instance?.drag?.finish(cancel) }
        internal fun dragBusy(): Boolean = instance?.drag?.busy == true
        internal fun dragReady(): Boolean = instance?.drag?.ready == true
        internal fun zoomBusy():Boolean = instance?.zoomInProgress==true
        internal fun performZoom(action:ZoomAction):Boolean = instance?.doZoom(action)==true
        internal fun prepareAiPlan(actions:List<AiAction>):Boolean = !zoomBusy() && instance?.aiSession?.prepare(actions) == true
        internal fun aiActive():Boolean = instance?.aiSession?.active == true
        internal fun aiRunning():Boolean = instance?.aiSession?.running == true
        internal fun stopAi(message:String?=null) { instance?.aiSession?.cancel(message) }
    }

    private var drag: ContinuousDrag? = null
    private var zoomInProgress=false
    private var zoomGeneration=0L
    private val zoomHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var aiSession: AiActionSession? = null

    override fun onServiceConnected() {
        if (Build.VERSION.SDK_INT >= 26) drag = ContinuousDrag(this)
        aiSession?.cancel()
        aiSession=AiActionSession(this)
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { aiSession?.cancel();drag?.finish(true) }

    override fun onDestroy() {
        aiSession?.cancel()
        aiSession=null
        zoomGeneration++;zoomInProgress=false;zoomHandler.removeCallbacksAndMessages(null)
        drag?.disconnected()
        drag = null
        super.onDestroy()
        if (instance === this) instance = null
    }

    /** Actual two-contact Android stroke; fixed center, bounded duration. */
    private fun doZoom(action:ZoomAction):Boolean {
        if(Build.VERSION.SDK_INT<24 || drag?.busy==true || aiActive() || zoomInProgress) return false
        val power=getSystemService(POWER_SERVICE) as android.os.PowerManager
        val lock=getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if(!power.isInteractive || lock.isKeyguardLocked) return false
        val dm=resources.displayMetrics
        val cx=dm.widthPixels/2f;val cy=dm.heightPixels/2f
        val scale=minOf(dm.widthPixels,dm.heightPixels).toFloat()
        if(scale<100f) return false
        val start=scale*(if(action==ZoomAction.IN) .11f else .30f)
        val end=scale*(if(action==ZoomAction.IN) .30f else .11f)
        val builder=GestureDescription.Builder()
        for(side in intArrayOf(-1,1)) {
            val path=Path().apply { moveTo(cx+side*start,cy);lineTo(cx+side*end,cy) }
            builder.addStroke(GestureDescription.StrokeDescription(path,0L,320L))
        }
        val token=++zoomGeneration
        zoomInProgress=true
        fun done() { if(token==zoomGeneration) zoomInProgress=false }
        val ok=try {
            dispatchGesture(builder.build(),object:GestureResultCallback(){
                override fun onCompleted(g:GestureDescription?) { done();Log.d(TAG,"Zoom strokes completed") }
                override fun onCancelled(g:GestureDescription?) { done();Log.d(TAG,"Zoom strokes cancelled") }
            },null)
        } catch(_:RuntimeException){false}
        if(!ok) done() else zoomHandler.postDelayed({done()},1600L)
        return ok
    }

    // -------------------------------------------------------
    // SCROLL — always uses swipe gesture
    // This works on ALL apps including Instagram Reels,
    // YouTube Shorts, TikTok, home screen, everything
    // -------------------------------------------------------

    fun doScroll(direction: String, completed: ((Boolean)->Unit)? = null): Boolean {
        if (drag?.busy == true || zoomInProgress) return false
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()

        val centerX = screenW / 2f

        // Vertical scroll points
        val topY    = screenH * 0.25f
        val bottomY = screenH * 0.75f

        // Horizontal scroll points
        val leftX  = screenW * 0.20f
        val rightX = screenW * 0.80f
        val midY   = screenH / 2f

        val path = Path()

        when (direction.lowercase()) {
            "up" -> {
                // Swipe from bottom to top = scroll up / next content up
                path.moveTo(centerX, bottomY)
                path.lineTo(centerX, topY)
            }
            "down" -> {
                // Swipe from top to bottom = scroll down / next content down
                path.moveTo(centerX, topY)
                path.lineTo(centerX, bottomY)
            }
            "left" -> {
                // Swipe from right to left = scroll left / go back
                path.moveTo(rightX, midY)
                path.lineTo(leftX, midY)
            }
            "right" -> {
                // Swipe from left to right = scroll right / go forward
                path.moveTo(leftX, midY)
                path.lineTo(rightX, midY)
            }
            else -> return false
        }

        // Duration: 250ms feels natural for a swipe
        // Too fast = might not register in some apps
        // Too slow = feels laggy
        val stroke = GestureDescription.StrokeDescription(path, 0L, 250L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.d(TAG, "Scroll $direction completed")
                completed?.invoke(true)
            }
            override fun onCancelled(g: GestureDescription?) {
                Log.d(TAG, "Scroll $direction cancelled")
                completed?.invoke(false)
            }
        }, null)
    }

    // -------------------------------------------------------
    // TAP — short quick tap
    // -------------------------------------------------------

    fun doTap(x: Float, y: Float): Boolean {
        if (drag?.busy == true || zoomInProgress) return false
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }

        val stroke  = GestureDescription.StrokeDescription(path, 0L, 80L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.d(TAG, "Tap at ($x, $y) completed")
            }
        }, null)
    }

    // -------------------------------------------------------
    // BACK — system back button
    // -------------------------------------------------------

    fun doBack(): Boolean {
        if (drag?.busy == true || zoomInProgress) return false
        Log.d(TAG, "Performing BACK")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }
}