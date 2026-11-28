package com.yvora.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

internal enum class DualMode { NORMAL, ZOOM, ROTATE, CUSTOM, UNIFIED }
internal enum class RotationAction { CLOCKWISE, COUNTERCLOCKWISE }
internal data class RotationDecision(val action:RotationAction?=null,val degree:Float=0f,val message:String="Open both palms to arm")

/** Dedicated rotate mode; angle change must dominate radial change.
 * Each cycle generates at most one 30-degree two-contact gesture. */
internal class FuzzyRotation {
    private var openCount=0
    private var armed=false
    private var waiting=-1L
    private var started=-1L
    private var last=-1L
    private var ax=0f;private var ay=0f
    private var radius=0f
    fun reset() {openCount=0;armed=false;waiting=-1L;started=-1L;last=-1L;ax=0f;ay=0f;radius=0f}
    fun update(a:ZoomSample?,b:ZoomSample?,now:Long,aspect:Float):RotationDecision {
        if(a==null || b==null || !aspect.isFinite() || aspect<=0f ||
            listOf(a,b).any{ !it.x.isFinite() || !it.y.isFinite() || !it.pinch.isFinite() || !it.quality.isFinite() || it.quality<.40f }) {
            reset();return RotationDecision(message="Both hands must be reliably tracked")
        }
        if(last>=0 && (now<last || now-last>550L)) reset()
        last=now
        if(a.open && b.open && a.pinch>=.34f && b.pinch>=.34f) {
            started=-1L;waiting=-1L
            if(++openCount>=2) armed=true
            return RotationDecision(message=if(armed) "Ready: pinch both hands" else "Hold both palms open")
        }
        openCount=0
        if(!armed) return RotationDecision()
        if(a.pinch>=.23f || b.pinch>=.23f) {
            if(started>=0) { reset();return RotationDecision() }
            if(waiting<0) waiting=now
            if(now-waiting>650L) reset()
            return RotationDecision(message="Pinch both hands together")
        }
        val dx=b.x-a.x;val dy=(b.y-a.y)*aspect
        val r=hypot(dx,dy)
        if(started<0) {
            if(r<.16f || waiting>=0 && now-waiting>650L) {reset();return RotationDecision()}
            ax=dx;ay=dy;radius=r;started=now
            return RotationDecision(message="Rotate the line between your hands")
        }
        if(now-started>1800L || r<.12f) {reset();return RotationDecision()}
        // Camera frame Y grows down: positive angle is clockwise on the displayed plane.
        val angle=atan2(ax*dy-ay*dx,ax*dx+ay*dy)*180f/kotlin.math.PI.toFloat()
        val radialChange=abs(r-radius)/radius
        val degree=FuzzyCore.and(FuzzyCore.and(FuzzyCore.rise(abs(angle),12f,35f),
            FuzzyCore.fall(radialChange,.08f,.26f)),FuzzyCore.and(a.quality,b.quality))
        if(now-started>=100L && FuzzyCore.alphaCut(degree,.60f)) {
            armed=false;started=-1L
            return RotationDecision(if(angle>0f) RotationAction.CLOCKWISE else RotationAction.COUNTERCLOCKWISE,
                degree,"Rotation accepted; open palms to re-arm")
        }
        return RotationDecision(degree=degree,message="Rotate while keeping hand separation steady")
    }
}
