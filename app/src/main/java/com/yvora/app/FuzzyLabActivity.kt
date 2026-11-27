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
class FuzzyLabActivity: LocalizedActivity() {
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
            live.text=if(engine==null) tr("Engine stopped. Start Yvora to see live degrees.","المحرك متوقف. شغّل Yvora لرؤية درجات الانتماء.")
                else buildString {
                    append(tr("Thermal membership: ","انتماء الحرارة: ")+fmt(engine.fuzzyThermal())+tr(" | analysis target: "," | معدل المعالجة المستهدف: ")+engine.fuzzyInterval()+" ms\n")
                    append(tr("Configured control mode: ","وضع التحكم المحدد: ")+uiStatus(engine.transformModeName())+"\n")
                    append(tr("Identity: ","هوية اليد: ")+if(engine.identityUncertain()) tr("ambiguous","ملتبسة") else tr("no ambiguity detected","لا يوجد التباس مرصود"))
                    append(tr("\nTouch arbitration and zoom injection disabled during safe lab mode.\n","\nتتوقف أوامر اللمس والتكبير أثناء المعايرة الآمنة.\n"))
                    for(slot in 0..1) {
                        val tr=engine.fuzzyTrace(slot)
                        append(if(slot==0) tr("\nLEFT (cyan)","\nاليسرى (سماوي)") else tr("\nRIGHT (amber)","\nاليمنى (كهرماني)"))
                        if(!tr.present) { append(tr(": no fresh hand result\n",": لا توجد نتيجة حديثة\n"));continue }
                        append(tr("\nHandedness score: ","\nدرجة تصنيف اليد: ")+fmt(tr.label)+tr(" | quality: "," | الجودة: ")+fmt(tr.quality))
                        append(tr("\nIdentity membership: ","\nانتماء الهوية: ")+fmt(engine.identityDegree(slot))+tr(" | prediction membership: "," | انتماء التوقع: ")+fmt(engine.predictionDegree(slot)))
                        append(tr("\nRelaxed pose: ","\nالوضعية المرنة: ")+fmt(tr.pose)+tr(" | assisted rule: "," | القاعدة المساعدة: ")+fmt(tr.assisted))
                        append(tr("\nPersonal pose membership: ","\nانتماء الوضعية الشخصية: ")+fmt(tr.personal))
                        append(tr("\nHorizontal motion: ","\nالحركة الأفقية: ")+fmt(tr.swipe)+tr(" | alpha: "," | ألفا: ")+fmt(tr.threshold))
                        append(tr("\nCandidate: ","\nالأمر المرشح: ")+uiStatus(tr.candidate)+tr(" | assist: "," | المساعدة: ")+if(tr.enabled) tr("on","مفعّل") else tr("off","متوقف"))
                        append(tr("\nCursor smoothing tau: ","\nمعامل تنعيم المؤشر تاو: ")+fmt(tr.tau*1000f)+" ms\n")
                    }
                }
            handler.postDelayed(this,300L)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,20,24,24) }
        fun text(value:String,size:Float=15f)=TextView(this).apply { text=value;textSize=size;panel.addView(this) }
        text(tr("YVORA FUZZY INTELLIGENCE","مختبر الذكاء الضبابي YVORA"),24f)
        text(tr("Live read-only telemetry. Hand injection is suspended while this lab is open. No data sent to AI.","قياسات مباشرة للقراءة فقط. يتوقف تنفيذ إيماءات اليد أثناء فتح المختبر. لا تُرسل بيانات إلى الذكاء الاصطناعي."))
        live=text(tr("Waiting for engine…","بانتظار المحرك…"))
        text(tr("SANDBOX • change rules without affecting the phone","بيئة تجريبية • غيّر القواعد دون التأثير على الهاتف"),19f)
        text(tr("Membership values are degrees of compatibility, not probabilities. Operator choices here do not change runtime safety rules.","درجات الانتماء تمثل التوافق وليست احتمالات. خيارات العمليات هنا لا تغيّر قواعد الأمان أثناء التشغيل."))
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
        slider(tr("Membership A","انتماء A"),a){a=it};slider(tr("Membership B","انتماء B"),b){b=it};slider(tr("Alpha cut","قطع ألفا (Alpha-Cut)"),alpha){alpha=it}
        text(tr("T-Norm","الاقتران الضبابي (T-Norm)"));picker(listOf(tr("Minimum","القيمة الصغرى"),tr("Algebraic product","الضرب الجبري"))){t=TNorm.values()[it]}
        text(tr("S-Norm","الفصل الضبابي (S-Norm)"));picker(listOf(tr("Maximum","القيمة الكبرى"),tr("Algebraic sum","الجمع الجبري"))){s=SNorm.values()[it]}
        slider(tr("Normalized hand speed","سرعة اليد المعيارية"),speed){speed=it}
        graph=SpeedGraph(this)
        panel.addView(graph,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,(170*resources.displayMetrics.density).toInt()))
        simulation=text("")
        text(tr("Graph: blue = slow, green = medium, amber = fast; white marker = input. Mamdani output is a discrete centroid example, Sugeno is a singleton weighted average. Only the actual cursor smoothing controller is used for runtime.","الرسم: أزرق = بطيء، أخضر = متوسط، كهرماني = سريع؛ العلامة البيضاء = المدخل. Mamdani مثال لمركز الثقل العددي، وSugeno متوسط موزون. وحده متحكم تنعيم المؤشر الفعلي يعمل أثناء التشغيل."))
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
            append(tr("AND = ","التقاطع AND = ")+fmt(and)+tr(" | OR = "," | الاتحاد OR = ")+fmt(or)+tr("\nNOT A = ","\nمتممة A = ")+fmt(FuzzyCore.not(a)))
            append(tr("\nAlpha cut (AND): ","\nقطع ألفا للتقاطع: ")+if(FuzzyCore.alphaCut(and,alpha)) tr("accepted in sandbox","مقبول في المختبر") else tr("rejected in sandbox","مرفوض في المختبر"))
            append(tr("\nDe Morgan: ","\nدي مورغان: ")+fmt(dmLeft)+" vs "+fmt(dmRight)+" | "+if(kotlin.math.abs(dmLeft-dmRight)<.0001f) tr("equal for these inputs","متساويان لهذه المدخلات") else tr("not equal for this operator pair","غير متساويين لهذا الزوج من العمليات"))
            append(tr("\n\nMax-min: [A,B] ○ [[.9,.2],[.4,.8]]","\n\nتركيب Max-Min: [A,B] ○ [[.9,.2],[.4,.8]]"))
            append(tr("\nOutput = [","\nالناتج = [")+fmt(composed[0])+", "+fmt(composed[1])+tr("] | Height = ","] | الارتفاع = ")+fmt(FuzzyCore.height(composed)))
            append(tr("\n\nSlow / medium / fast: ","\n\nبطيء / متوسط / سريع: ")+deg.joinToString(" / "){fmt(it)})
            append("\nSugeno: "+fmt(sug)+" | Mamdani: "+fmt(mam))
            append(tr("\nRuntime cursor tau for example speed: ","\nمعامل تنعيم المؤشر عند سرعة المثال: ")+fmt(FuzzyControllers.cursorTau(speed)*1000f)+" ms")
        }
        graph.speed=speed;graph.invalidate()
    }
    override fun onResume() {
        super.onResume()
        YvoraAccessibilityService.stopAi(tr("AI paused for Fuzzy Lab","أُوقف التحكم الذكي مؤقتًا لمختبر الفزي"))
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
