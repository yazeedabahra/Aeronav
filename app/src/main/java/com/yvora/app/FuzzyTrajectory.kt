package com.yvora.app

import kotlin.math.abs
import kotlin.math.hypot

internal data class MotionPoint(val x:Float,val y:Float,val time:Long)
internal data class MotionPattern(val values:List<Float>,val duration:Long) {
    fun valid()=duration in 400L..2500L && values.size==32 && values.all{it.isFinite() && it in -1.01f..1.01f}
}
internal object MotionSimilarity {
    // Time-normalized sequence, preserving direction and curvature, invariant to translation and uniform scale.
    fun normalize(points:List<MotionPoint>,aspect:Float):MotionPattern? {
        if(points.size !in 5..60 || !aspect.isFinite() || aspect !in .4f..3f ||
            points.any{!it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f}) return null
        val duration=points.last().time-points.first().time
        if(duration !in 400L..2500L || points.zipWithNext().any{(a,b)->b.time-a.time !in 1L..550L}) return null
        val x0=points.first().x;val y0=points.first().y
        val scale=points.maxOf{hypot(it.x-x0,(it.y-y0)*aspect)}
        if(scale<.10f || scale>2f) return null
        val out=ArrayList<Float>(32)
        var j=0
        for(i in 0..15) {
            val t=points.first().time+duration*i/15.0
            while(j<points.lastIndex-1 && points[j+1].time<t) j++
            val a=points[j];val b=points[j+1]
            val u=((t-a.time)/(b.time-a.time)).toFloat().coerceIn(0f,1f)
            out.add((a.x+(b.x-a.x)*u-x0)/scale)
            out.add((a.y+(b.y-a.y)*u-y0)*aspect/scale)
        }
        return MotionPattern(out,duration).takeIf{it.valid()}
    }
    fun degree(a:MotionPattern,b:MotionPattern):Float {
        if(!a.valid() || !b.valid()) return 0f
        var sum=0f;var worst=0f
        for(i in 0 until 32 step 2) {
            val d=hypot(a.values[i]-b.values[i],a.values[i+1]-b.values[i+1])
            sum+=d;worst=maxOf(worst,d)
        }
        val durationRatio=minOf(a.duration,b.duration).toFloat()/maxOf(a.duration,b.duration)
        return FuzzyCore.and(FuzzyCore.and(FuzzyCore.fall(sum/16f,.08f,.38f),
            FuzzyCore.fall(worst,.20f,.70f)),FuzzyCore.rise(durationRatio,.35f,.70f))
    }
    fun learn(samples:List<MotionPattern>):MotionPattern? {
        if(samples.size!=3 || samples.any{!it.valid()}) return null
        for(i in samples.indices) for(j in i+1 until samples.size)
            if(degree(samples[i],samples[j])<.72f) return null
        return MotionPattern(List(32){i->samples.map{it.values[i]}.average().toFloat()},
            samples.map{it.duration}.average().toLong()).takeIf{it.valid()}
    }
    fun choose(sample:MotionPattern,templates:Map<AiAction,MotionPattern>):Pair<AiAction,Float>? {
        val ranked=templates.filterKeys{it in trajectoryActions}.map{(action,pattern)->action to degree(sample,pattern)}
            .sortedByDescending{it.second}
        val best=ranked.firstOrNull() ?: return null
        return if(best.second>=.80f && (ranked.size==1 || best.second-ranked[1].second>=.15f)) best else null
    }
}
internal val trajectoryActions=listOf(AiAction.SWIPE_LEFT,AiAction.SWIPE_RIGHT,AiAction.SWIPE_UP,AiAction.SWIPE_DOWN)
internal data class MotionResult(val pattern:MotionPattern?=null,val status:String="Open palm to arm")

/** Pinch is a recording clutch, not a tap. A completed gesture requires a fresh release.
 * No motion may survive loss of identity, stale frames, or a mode change. */
internal class MotionClutch {
    private val points=ArrayList<MotionPoint>()
    private var openCount=0
    private var armed=false
    private var last=-1L
    fun reset(){points.clear();openCount=0;armed=false;last=-1L}
    fun update(sample:ZoomSample?,now:Long,aspect:Float):MotionResult {
        if(sample==null || !sample.quality.isFinite() || sample.quality<.55f ||
            !sample.x.isFinite() || !sample.y.isFinite() || !sample.pinch.isFinite() ||
            !aspect.isFinite() || aspect !in .4f..3f || sample.x !in 0f..1f || sample.y !in 0f..1f) {
            reset();return MotionResult(status="Tracking uncertain; open palm again")
        }
        if(last>=0 && (now<=last || now-last>550L)) reset()
        last=now
        if(points.isNotEmpty()) {
            if(now-points.first().time>2500L || points.size>=60) {reset();return MotionResult(status="Motion too long; re-arm")}
            if(sample.pinch>=.34f) {
                val pattern=MotionSimilarity.normalize(points,aspect)
                reset()
                return MotionResult(pattern,if(pattern==null) "Motion too short or small; retry" else "Motion captured")
            }
            points.add(MotionPoint(sample.x,sample.y,now))
            return MotionResult(status="Recording motion; release pinch to finish")
        }
        if(sample.open && sample.pinch>=.34f) {
            if(++openCount>=2) armed=true
            return MotionResult(status=if(armed) "Ready: pinch and draw" else "Hold palm open")
        }
        openCount=0
        if(armed && sample.pinch<.23f) {
            points.add(MotionPoint(sample.x,sample.y,now));armed=false
            return MotionResult(status="Recording motion; release pinch to finish")
        }
        return MotionResult()
    }
}

internal class MotionTraining(val slot:Int,val action:AiAction,private val started:Long) {
    private val clutch=MotionClutch()
    private val samples=ArrayList<MotionPattern>(3)
    var status="Open palm to arm"
        private set
    val count get()=samples.size
    fun expired(now:Long)=now-started>60000L || now<started
    fun observe(sample:ZoomSample?,now:Long,aspect:Float) {
        if(expired(now) || samples.size>=3) return
        val result=clutch.update(sample,now,aspect)
        status=result.status
        result.pattern?.let { samples.add(it);status="Captured ${samples.size}/3; open palm and repeat" }
    }
    fun finish(now:Long)=if(expired(now)) null else MotionSimilarity.learn(samples)
}
