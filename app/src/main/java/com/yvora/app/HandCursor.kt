package com.yvora.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
    fun position(nx:Float,ny:Float,width:Int,height:Int) {
        if(!nx.isFinite() || !ny.isFinite()) return
        val maxX=(width-1).coerceAtLeast(0).toFloat()
        val maxY=(height-1).coerceAtLeast(0).toFloat()
        fun snap(value:Float,max:Float):Float=when {
            value<=3f -> 0f
            value>=max-3f -> max
            else -> value
        }
        val px=snap(nx.coerceIn(0f,1f)*maxX,maxX)
        val py=snap(ny.coerceIn(0f,1f)*maxY,maxY)
        val edgeChanged=(px!=x && (px==0f || px==maxX)) || (py!=y && (py==0f || py==maxY))
        if(!initialized || edgeChanged || kotlin.math.abs(px-x)>3f || kotlin.math.abs(py-y)>3f) {
            x=px;y=py;initialized=true
        }
        val p=params ?: return
        val v=view ?: return
        val left=(x-half).toInt(); val top=(y-half).toInt()
        if(p.x!=left || p.y!=top) {
            p.x=left;p.y=top
            try { wm?.updateViewLayout(v,p) } catch(_:RuntimeException) { }
        }
    }
    fun show() { if(initialized) view?.visibility=View.VISIBLE }
    fun hide() { view?.visibility=View.GONE }
    fun color(value:Int=baseColor) {
        if(lastColor==value) return
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
        view?.let { try { wm?.removeView(it) } catch(_:RuntimeException) {} }
        view=null;params=null;wm=null
    }
}
