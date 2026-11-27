package com.yvora.app

import kotlin.math.min
import kotlin.math.max
internal enum class TNorm { MINIMUM, PRODUCT }
internal enum class SNorm { MAXIMUM, ALGEBRAIC_SUM }

/** Shared numerical primitives; memberships are not statistical probabilities. */
internal object FuzzyCore {
    fun unit(x:Float)=if(x.isFinite()) x.coerceIn(0f,1f) else 0f
    fun rise(x:Float,lo:Float,hi:Float):Float {
        require(lo.isFinite() && hi.isFinite() && hi>lo)
        return if(x.isFinite()) unit((x-lo)/(hi-lo)) else 0f
    }
    fun fall(x:Float,lo:Float,hi:Float)=if(x.isFinite()) 1f-rise(x,lo,hi) else 0f
    fun triangle(x:Float,a:Float,b:Float,c:Float)=min(rise(x,a,b),fall(x,b,c))
    fun and(a:Float,b:Float,op:TNorm=TNorm.MINIMUM)=if(op==TNorm.MINIMUM) min(unit(a),unit(b)) else unit(a)*unit(b)
    fun or(a:Float,b:Float,op:SNorm=SNorm.MAXIMUM):Float {
        val x=unit(a);val y=unit(b)
        return if(op==SNorm.MAXIMUM) max(x,y) else unit(x+y-x*y)
    }
    fun not(a:Float)=1f-unit(a)
    fun alphaCut(a:Float,alpha:Float)=a.isFinite() && alpha.isFinite() && a in 0f..1f && alpha in 0f..1f && a>=alpha
    fun height(values:FloatArray)=values.maxOfOrNull { unit(it) } ?: 0f
    fun compose(input:FloatArray,relation:Array<FloatArray>):FloatArray {
        require(input.isNotEmpty() && input.size==relation.size)
        val n=relation.first().size
        require(relation.all { it.size==n })
        return FloatArray(n){ j ->
            var v=0f
            for(i in input.indices) v=max(v,min(unit(input[i]),unit(relation[i][j])))
            v
        }
    }
    fun sugeno(weights:FloatArray,outputs:FloatArray,fallback:Float):Float {
        require(weights.size==outputs.size && outputs.all { it.isFinite() })
        var total=0f;var sum=0f
        for(i in weights.indices) { val w=unit(weights[i]);total+=w;sum+=w*outputs[i] }
        return if(total>1e-6f) sum/total else fallback
    }
    /** Discrete centroid, uniformly spaced universe, min implication / max aggregation. */
    fun mamdani(weights:FloatArray,sets:Array<FloatArray>,universe:FloatArray,fallback:Float):Float {
        require(weights.size==sets.size && sets.all { it.size==universe.size } && universe.all { it.isFinite() })
        var sum=0f;var mass=0f
        for(j in universe.indices) {
            var mu=0f
            for(i in weights.indices) mu=max(mu,min(unit(weights[i]),unit(sets[i][j])))
            sum+=mu*universe[j];mass+=mu
        }
        return if(mass>1e-6f) sum/mass else fallback
    }
}
internal object FuzzyControllers {
    fun speedDegrees(speed:Float):FloatArray {
        val x=FuzzyCore.rise(speed,.04f,1f)
        return floatArrayOf(FuzzyCore.fall(x,0f,.5f),FuzzyCore.triangle(x,0f,.5f,1f),FuzzyCore.rise(x,.5f,1f))
    }
    private val tauOutputs=floatArrayOf(.095f,.0565f,.018f)
    fun cursorTau(speed:Float)=if(!speed.isFinite()) .095f else FuzzyCore.sugeno(speedDegrees(speed),tauOutputs,.095f)
    fun heatDegree(battery:Int?,severity:Int)=FuzzyCore.or(
        if(battery==null) 0f else FuzzyCore.rise(battery.toFloat(),375f,400f),
        if(severity==1) .25f else if(severity>=2) 1f else 0f)
    fun thermalInterval(base:Long,heat:Float,warm:Boolean):Long {
        if(warm) return base
        val h=FuzzyCore.unit(heat)
        return (base+(40f*h).toLong()).coerceIn(base,maxOf(base,320L))
    }
    fun quality(label:Float,age:Long)=if(!label.isFinite() || age<0) 0f else
        FuzzyCore.and(FuzzyCore.rise(label,.60f,.85f),FuzzyCore.fall(age.toFloat(),150f,500f))
    fun assistedScore(pose:Float,quality:Float,pinchClear:Boolean)=if(pinchClear) FuzzyCore.and(pose,quality) else 0f
}
internal data class FuzzyHandTrace(val present:Boolean=false,val label:Float=0f,val quality:Float=0f,
    val pose:Float=0f,val assisted:Float=0f,val swipe:Float=0f,val tau:Float=.095f,
    val threshold:Float=.61f,val enabled:Boolean=false,val candidate:String="None",val pinchRatio:Float=1f,val features:PoseFeatures?=null,val personal:Float=0f)
