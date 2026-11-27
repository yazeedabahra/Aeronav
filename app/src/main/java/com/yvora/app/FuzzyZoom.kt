package com.yvora.app

import kotlin.math.abs
import kotlin.math.hypot

internal enum class ZoomAction { IN, OUT }
internal data class ZoomSample(val x:Float,val y:Float,val open:Boolean,val pinch:Float,val quality:Float)
internal data class ZoomDecision(val action:ZoomAction?=null,val degree:Float=0f,val message:String="Open both palms to arm")

/** Explicit zoom-only mode. Open both palms, close both pinches, change separation.
 * One bounded command per cycle; missing/ambiguous hands cannot create an action. */
internal class FuzzyZoom {
    private var opened=0
    private var armed=false
    private var waiting=-1L
    private var anchor=-1f
    private var started=-1L
    private var last=-1L
    private var latched=false
    fun reset() { opened=0;armed=false;waiting=-1L;anchor=-1f;started=-1L;last=-1L;latched=false }
    fun update(a:ZoomSample?,b:ZoomSample?,now:Long,aspect:Float):ZoomDecision {
        if(a==null || b==null || !aspect.isFinite() || aspect<=0f ||
            listOf(a,b).any{!it.x.isFinite() || !it.y.isFinite() || !it.pinch.isFinite() || !it.quality.isFinite() || it.quality<.40f}) {
            reset();return ZoomDecision(message="Both hands must be tracked reliably")
        }
        if(last>=0 && (now<last || now-last>550L)) reset()
        last=now
        if(a.open && b.open && a.pinch>=.34f && b.pinch>=.34f) {
            anchor=-1f;started=-1L;waiting=-1L
            if(++opened>=2) { armed=true;latched=false }
            return ZoomDecision(message=if(armed) "Ready: pinch both hands" else "Hold both palms open")
        }
        opened=0
        if(latched || !armed) return ZoomDecision(message="Open both palms to re-arm")
        val both=a.pinch<.23f && b.pinch<.23f
        if(!both) {
            if(anchor>=0f) { reset();return ZoomDecision() }
            if(waiting<0L) waiting=now
            if(now-waiting>650L) reset()
            return ZoomDecision(message="Pinch both hands together")
        }
        val separation=hypot(a.x-b.x,(a.y-b.y)*aspect)
        if(anchor<0f) {
            if(separation<.12f || waiting>=0L && now-waiting>650L) {
                reset();return ZoomDecision(message="Open palms and separate hands")
            }
            anchor=separation;started=now
            return ZoomDecision(message="Move hands apart or together")
        }
        if(now-started>1800L || separation<.075f) { reset();return ZoomDecision() }
        val delta=separation-anchor
        val degree=FuzzyCore.and(
            FuzzyCore.and(FuzzyCore.rise(abs(delta),.035f,.095f),FuzzyCore.rise(abs(delta)/anchor,.10f,.27f)),
            FuzzyCore.and(a.quality,b.quality))
        if(now-started>=100L && FuzzyCore.alphaCut(degree,.55f)) {
            latched=true;armed=false
            return ZoomDecision(if(delta>0f) ZoomAction.IN else ZoomAction.OUT,degree,"Zoom gesture accepted; open palms to repeat")
        }
        return ZoomDecision(degree=degree,message="Move hands apart or together")
    }
}
