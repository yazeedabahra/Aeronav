package com.yvora.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** One fixed, non-touchable window; only its contents change. Main thread only.
 * No per-frame WindowManager relayout. Window is invisible without a cursor. */
internal class CursorCanvas(context:Context):View(context) {
    private val shown=BooleanArray(2)
    private val xs=FloatArray(2)
    private val ys=FloatArray(2)
    private val base=intArrayOf(Color.rgb(56,189,248),Color.rgb(250,180,65))
    private val colors=base.copyOf()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density=resources.displayMetrics.density
    private val origin=IntArray(2)
    private var manager:WindowManager?=null
    private var sampleStart=-1L
    private var drawn=0
    private var measuredRate=0f
    private var lastDraw=-1L
    var lastDrawGapMs=0L
        private set
    init {visibility=View.INVISIBLE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;setWillNotDraw(false)}
    fun attach(wm:WindowManager):Boolean {
        val type=if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT).apply{
            gravity=Gravity.TOP or Gravity.LEFT
            // One window at .50 opacity, below Android's standard untrusted-overlay threshold.
            alpha=.50f
        }
        return try {wm.addView(this,params);manager=wm;true}
        catch(_:RuntimeException){false}
    }
    fun setPosition(slot:Int,x:Float,y:Float) {
        if(xs[slot]==x && ys[slot]==y)return
        xs[slot]=x;ys[slot]=y
        if(shown[slot])postInvalidateOnAnimation()
    }
    fun setColor(slot:Int,color:Int) {
        if(colors[slot]==color)return
        colors[slot]=color
        if(shown[slot])postInvalidateOnAnimation()
    }
    fun isShown(slot:Int)=shown[slot]
    fun setShown(slot:Int,value:Boolean) {
        if(shown[slot]==value)return
        shown[slot]=value
        val any=shown.any{it}
        visibility=if(any)View.VISIBLE else View.INVISIBLE
        if(any)postInvalidateOnAnimation()
        else resetStats()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(origin)
        for(i in 0..1)if(shown[i]){
            val x=xs[i]-origin[0];val y=ys[i]-origin[1]
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2f*density;paint.color=base[i]
            canvas.drawCircle(x,y,22f*density,paint)
            paint.style=Paint.Style.FILL;paint.color=colors[i]
            canvas.drawCircle(x,y,8f*density,paint)
        }
        val now=SystemClock.uptimeMillis()
        if(lastDraw>=0 && now-lastDraw<=500L)lastDrawGapMs=now-lastDraw
        else {sampleStart=-1L;drawn=0;measuredRate=0f;lastDrawGapMs=0}
        lastDraw=now
        if(sampleStart<0){sampleStart=now;drawn=0}
        else {
            drawn++
            val elapsed=now-sampleStart
            if(elapsed>=1000L){measuredRate=drawn*1000f/elapsed;drawn=0;sampleStart=now}
        }
    }
    fun drawRate(now:Long)=if(lastDraw>=0 && now-lastDraw<=500L)measuredRate else 0f
    private fun resetStats(){sampleStart=-1;lastDraw=-1;drawn=0;measuredRate=0f;lastDrawGapMs=0}
    fun detach(){
        shown.fill(false);visibility=View.INVISIBLE;resetStats()
        try{manager?.removeView(this)}catch(_:RuntimeException){}
        manager=null
    }
}
