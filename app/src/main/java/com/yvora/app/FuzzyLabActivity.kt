package com.yvora.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** Read-only live trace + isolated mathematical simulation. No gesture injection or network. */
class FuzzyLabActivity:AppCompatActivity() {
    private val handler=Handler(Looper.getMainLooper())
    private var service:YvoraService?=null
    private var oldCalibration=false
    private lateinit var live:TextView
    private lateinit var simulation:TextView
    private lateinit var graph:SpeedGraph
    private var a=.8f;private var b=.7f;private var alpha=.6f;private var speed=.5f
    private var t=TNorm.MINIMUM;private var s=SNorm.MAXIMUM
    private fun fmt(x:Float)=String.format(Locale.ROOT,"%.3f",x)
    private val tick=object:Runnable {
        override fun run() {
            val engine=YvoraService.instance
            live.text=if(engine==null) "Engine stopped. Start Yvora to see live degrees."
                else buildString {
                    append("Thermal membership: "+fmt(engine.fuzzyThermal())+" | analysis target: "+engine.fuzzyInterval()+" ms\n")
                    for(slot in 0..1) {
                        val tr=engine.fuzzyTrace(slot)
                        append(if(slot==0) "\nLEFT (cyan)" else "\nRIGHT (amber)")
                        if(!tr.present) { append(": no fresh hand result\n");continue }
                        append("\nHandedness score: "+fmt(tr.label)+" | quality: "+fmt(tr.quality))
                        append("\nRelaxed pose: "+fmt(tr.pose)+" | assisted rule: "+fmt(tr.assisted))
                        append("\nHorizontal motion: "+fmt(tr.swipe)+" | alpha: "+fmt(tr.threshold))
                        append("\nCandidate: "+tr.candidate+" | assist: "+if(tr.enabled) "on" else "off")
                        append("\nCursor smoothing tau: "+fmt(tr.tau*1000f)+" ms\n")
                    }
                }
            handler.postDelayed(this,300L)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,20,24,24) }
        fun text(value:String,size:Float=15f)=TextView(this).apply { text=value;textSize=size;panel.addView(this) }
        text("YVORA FUZZY INTELLIGENCE",24f)
        text("Live read-only telemetry. Hand injection is suspended while this lab is open. No data sent to AI.")
        live=text("Waiting for engine…")
        text("SANDBOX • change rules without affecting the phone",19f)
        text("Membership values are degrees of compatibility, not probabilities. Operator choices here do not change runtime safety rules.")
        fun slider(label:String,initial:Float,update:(Float)->Unit) {
            val caption=text(label+": "+fmt(initial))
            panel.addView(SeekBar(this).apply {
                max=100;progress=(100*initial).toInt()
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar:SeekBar?) {}
                    override fun onStopTrackingTouch(bar:SeekBar?) {}
                    override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean) {
                        val v=value/100f;caption.text=label+": "+fmt(v);update(v)
                        if(::simulation.isInitialized) render()
                    }
                })
            })
        }
        fun picker(items:List<String>,change:(Int)->Unit) {
            panel.addView(Spinner(this).apply {
                adapter=ArrayAdapter(this@FuzzyLabActivity,android.R.layout.simple_spinner_dropdown_item,items)
                onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent:AdapterView<*>?) {}
                    override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                        change(position);if(::simulation.isInitialized) render()
                    }
                }
            })
        }
        slider("Membership A",a){a=it};slider("Membership B",b){b=it};slider("Alpha cut",alpha){alpha=it}
        text("T-Norm");picker(listOf("Minimum","Algebraic product")){t=TNorm.values()[it]}
        text("S-Norm");picker(listOf("Maximum","Algebraic sum")){s=SNorm.values()[it]}
        slider("Normalized hand speed",speed){speed=it}
        graph=SpeedGraph(this)
        panel.addView(graph,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,(170*resources.displayMetrics.density).toInt()))
        simulation=text("")
        text("Graph: blue = slow, green = medium, amber = fast; white marker = input. Mamdani output is a discrete centroid example, Sugeno is a singleton weighted average. Only the actual cursor smoothing controller is used for runtime.")
        setContentView(ScrollView(this).apply { addView(panel) })
        render()
    }
    private fun render() {
        val and=FuzzyCore.and(a,b,t);val or=FuzzyCore.or(a,b,s)
        val composed=FuzzyCore.compose(floatArrayOf(a,b),arrayOf(floatArrayOf(.9f,.2f),floatArrayOf(.4f,.8f)))
        val deg=FuzzyControllers.speedDegrees(speed)
        val universe=FloatArray(101){it/100f}
        val sets=Array(3){i->FloatArray(101){j ->
            val u=universe[j]
            when(i){0->FuzzyCore.fall(u,0f,.5f);1->FuzzyCore.triangle(u,0f,.5f,1f);else->FuzzyCore.rise(u,.5f,1f)}
        }}
        val mam=FuzzyCore.mamdani(deg,sets,universe,.5f)
        val sug=FuzzyCore.sugeno(deg,floatArrayOf(0f,.5f,1f),.5f)
        // De Morgan is checked for the chosen pair; mismatched duals need not agree.
        val dmLeft=FuzzyCore.not(and)
        val dmRight=FuzzyCore.or(FuzzyCore.not(a),FuzzyCore.not(b),s)
        simulation.text=buildString {
            append("AND = "+fmt(and)+" | OR = "+fmt(or)+"\nNOT A = "+fmt(FuzzyCore.not(a)))
            append("\nAlpha cut (AND): "+if(FuzzyCore.alphaCut(and,alpha)) "accepted in sandbox" else "rejected in sandbox")
            append("\nDe Morgan: "+fmt(dmLeft)+" vs "+fmt(dmRight)+" | "+if(kotlin.math.abs(dmLeft-dmRight)<.0001f) "equal for these inputs" else "not equal for this operator pair")
            append("\n\nMax-min: [A,B] ○ [[.9,.2],[.4,.8]]")
            append("\nOutput = ["+fmt(composed[0])+", "+fmt(composed[1])+"] | Height = "+fmt(FuzzyCore.height(composed)))
            append("\n\nSlow / medium / fast: "+deg.joinToString(" / "){fmt(it)})
            append("\nSugeno: "+fmt(sug)+" | Mamdani: "+fmt(mam))
            append("\nRuntime cursor tau for example speed: "+fmt(FuzzyControllers.cursorTau(speed)*1000f)+" ms")
        }
        graph.speed=speed;graph.invalidate()
    }
    override fun onResume() {
        super.onResume()
        YvoraAccessibilityService.stopAi("AI paused for Fuzzy Lab")
        service=YvoraService.instance;oldCalibration=service?.isCalibrating==true
        service?.setCalibrationMode(true)
        handler.post(tick)
    }
    override fun onPause() {
        handler.removeCallbacks(tick)
        if(service===YvoraService.instance) service?.setCalibrationMode(oldCalibration)
        service=null
        super.onPause()
    }

    private class SpeedGraph(context:Context):View(context) {
        var speed=.5f
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            val pad=22f
            val w=(width-2*pad).coerceAtLeast(1f);val h=(height-2*pad).coerceAtLeast(1f)
            fun px(v:Float)=pad+w*v
            fun py(v:Float)=pad+h*(1-v)
            paint.color=Color.GRAY;paint.strokeWidth=1f
            canvas.drawLine(px(0f),py(0f),px(1f),py(0f),paint)
            canvas.drawLine(px(0f),py(0f),px(0f),py(1f),paint)
            val colors=intArrayOf(Color.rgb(56,189,248),Color.rgb(34,197,94),Color.rgb(250,180,65))
            for(i in 0..2) {
                paint.color=colors[i];paint.strokeWidth=3f
                var last=FuzzyControllers.speedDegrees(0f)[i]
                for(j in 1..50) {
                    val now=j/50f;val v=FuzzyControllers.speedDegrees(now)[i]
                    canvas.drawLine(px((j-1)/50f),py(last),px(now),py(v),paint);last=v
                }
            }
            paint.color=Color.WHITE;paint.strokeWidth=2f
            canvas.drawLine(px(speed),py(0f),px(speed),py(1f),paint)
        }
    }
}
