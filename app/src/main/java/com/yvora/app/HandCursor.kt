package com.yvora.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import kotlin.math.roundToInt
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

/** Each hand has its own filtered position, gesture recognizer and overlay. Main thread only. */
internal class HandCursor(val slot:Int) {
    val recognizer=GestureRecognizer()
    val predictor=FuzzyMotionPredictor()
    private val baseColor=if(slot==0) Color.rgb(56,189,248) else Color.rgb(250,180,65)
    private var view:View?=null
    private var canvas:CursorCanvas?=null
    private fun visible()=canvas?.isShown(slot) ?: (view?.visibility==View.VISIBLE)
    fun attachCanvas(layer:CursorCanvas){canvas=layer;layer.setColor(slot,baseColor);lastColor=baseColor}
    private var params:WindowManager.LayoutParams?=null
    private var wm:WindowManager?=null
    private var half=0f
    var x=0f
        private set
    var y=0f
        private set
    var initialized=false
        private set
    var armed=false
    var anchorX=0f
    var anchorY=0f
    var startX=0f
    var startY=0f
    var dragging=false
    private var lastColor:Int?=null
    private val glide=CursorGlide()
    private var lastTargetAt=-1L
    private var lastRenderAt=-1L
    private var renderGapMs=16L
    private var renderPosted=false
    private val renderCallback=object:Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos:Long) {
            renderPosted=false
            if(!visible() || !glide.active){glide.cancel();return}
            val now=SystemClock.uptimeMillis()
            if(lastRenderAt<0 || now-lastRenderAt>=renderGapMs-2L) {
                glide.sample(now)?.let { pos->
                    applyPosition(pos.x,pos.y)
                    lastRenderAt=now
                }
            }
            scheduleRender()
        }
    }
    private fun scheduleRender(){
        if(glide.active && !renderPosted){renderPosted=true;Choreographer.getInstance().postFrameCallback(renderCallback)}
    }
    fun freezeMotion(){
        glide.cancel();lastTargetAt=-1L;lastRenderAt=-1L
        if(renderPosted){Choreographer.getInstance().removeFrameCallback(renderCallback);renderPosted=false}
    }

    fun attach(context:Context,manager:WindowManager) {
        half=24f*context.resources.displayMetrics.density
        val v=LayoutInflater.from(context).inflate(R.layout.overlay_pointer,null)
        val type=if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val p=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT
            // Android 12+ limits combined obscuring opacity for touch-through overlays.
            alpha=0.50f
        }
        v.visibility=View.GONE
        try {
            manager.addView(v,p)
            view=v;params=p;wm=manager
            color()
        } catch(_:RuntimeException) { }
    }
    private fun target(nx:Float,ny:Float,width:Int,height:Int):Pair<Float,Float>? {
        if(!nx.isFinite() || !ny.isFinite()) return null
        val maxX=(width-1).coerceAtLeast(0).toFloat()
        val maxY=(height-1).coerceAtLeast(0).toFloat()
        fun snap(value:Float,max:Float):Float=when {
            value<=3f -> 0f
            value>=max-3f -> max
            else -> value
        }
        return snap(nx.coerceIn(0f,1f)*maxX,maxX) to snap(ny.coerceIn(0f,1f)*maxY,maxY)
    }
    /** Immediate position for re-arm, active drag and special modes. */
    fun position(nx:Float,ny:Float,width:Int,height:Int) {
        freezeMotion()
        val (px,py)=target(nx,ny,width,height) ?: return
        applyPosition(px,py)
    }
    /** Only the normal open-palm cursor is interpolated. Input anchors use rendered x/y. */
    fun movePointer(nx:Float,ny:Float,width:Int,height:Int,lowPower:Boolean,responsive:Boolean=false) {
        val (px,py)=target(nx,ny,width,height) ?: return
        val now=SystemClock.uptimeMillis()
        val gap=if(lastTargetAt<0) 90L else now-lastTargetAt
        lastTargetAt=now
        renderGapMs=if(lowPower) 33L else 16L
        if(!initialized || !visible() || gap>350L){
            glide.cancel();applyPosition(px,py);return
        }
        if(!glide.aim(x,y,px,py,now,gap,responsive)){
            // Tiny movements must accumulate instead of restarting a pointless animation.
            if(renderPosted){Choreographer.getInstance().removeFrameCallback(renderCallback);renderPosted=false}
            return
        }
        scheduleRender()
    }
    private fun applyPosition(px:Float,py:Float){
        val layer=canvas
        if(layer!=null){x=px;y=py;initialized=true;layer.setPosition(slot,x,y);return}
        x=px.roundToInt().toFloat();y=py.roundToInt().toFloat();initialized=true
        val p=params ?: return
        val v=view ?: return
        val left=(x-half).roundToInt();val top=(y-half).roundToInt()
        if(p.x!=left || p.y!=top){
            p.x=left;p.y=top
            try{wm?.updateViewLayout(v,p)}catch(_:RuntimeException){}
        }
    }
    fun show() { if(initialized){canvas?.setShown(slot,true);view?.visibility=View.VISIBLE} }
    fun hide() { freezeMotion();canvas?.setShown(slot,false);view?.visibility=View.GONE }
    fun color(value:Int=baseColor) {
        if(lastColor==value) return
        canvas?.setColor(slot,value)
        view?.findViewById<View>(R.id.innerDot)?.background=GradientDrawable().apply {
            shape=GradientDrawable.OVAL;setColor(value)
        }
        view?.findViewById<View>(R.id.outerRing)?.background=GradientDrawable().apply {
            shape=GradientDrawable.OVAL;setColor(Color.TRANSPARENT);setStroke(2,baseColor)
        }
        lastColor=value
    }
    fun reset() {
        recognizer.reset();predictor.reset();initialized=false;armed=false;dragging=false;hide();color()
    }
    fun remove() {
        freezeMotion()
        canvas?.setShown(slot,false);canvas=null
        view?.let { try { wm?.removeView(it) } catch(_:RuntimeException) {} }
        view=null;params=null;wm=null
    }
}
