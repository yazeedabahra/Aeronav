package com.yvora.app

import kotlin.math.hypot

internal data class HandObservation(val label:String?,val score:Float,val x:Float,val y:Float)
internal data class IdentityResult(val indices:IntArray,val degrees:FloatArray,val ambiguous:Boolean)

/** Label is necessary but not sufficient: continuity vetoes suspicious label swaps.
 * Uncertain frames release input rather than guessing a new owner. */
internal class FuzzyHandIdentity {
    private data class Track(val x:Float,val y:Float,val vx:Float,val vy:Float,val time:Long)
    private val tracks=arrayOfNulls<Track>(2)
    fun reset() { tracks.fill(null) }
    fun update(hands:List<HandObservation>,now:Long):IdentityResult {
        for(i in 0..1) if(tracks[i]?.let { now-it.time>600L || now<it.time }==true) tracks[i]=null
        val labels=hands.map { if(it.score.isFinite() && it.score>=.65f && it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f) it.label else null }
        val indices=HandSlots.assign(labels)
        val scores=FloatArray(2)
        var ambiguous=labels.filterNotNull().groupingBy{it}.eachCount().values.any{it>1}
        fun distance(o:HandObservation,t:Track):Float {
            val dt=((now-t.time).coerceIn(0L,180L))/1000f
            return hypot(o.x-(t.x+t.vx*dt).coerceIn(0f,1f),o.y-(t.y+t.vy*dt).coerceIn(0f,1f))
        }
        for(i in 0..1) {
            val j=indices[i]
            if(j<0) continue
            val o=hands[j];val own=tracks[i];val other=tracks[1-i]
            val labelDegree=FuzzyCore.rise(o.score,.60f,.90f)
            val ownDist=own?.let{distance(o,it)}
            val otherDist=other?.let{distance(o,it)}
            val continuity=if(ownDist==null) 1f else FuzzyCore.fall(ownDist,.10f,.42f)
            scores[i]=FuzzyCore.and(labelDegree,continuity)
            // A closer opposing track is a veto, never a reason to transfer a touch.
            if((ownDist!=null && otherDist!=null && ownDist>.13f && ownDist-otherDist>.09f) ||
                (ownDist!=null && ownDist>.42f) ||
                (ownDist==null && otherDist!=null && otherDist<.085f)) ambiguous=true
        }
        // Reject both during overlapping palms with two established tracks: preserve identities.
        if(indices.all{it>=0}) {
            val a=hands[indices[0]];val b=hands[indices[1]]
            if(hypot(a.x-b.x,a.y-b.y)<.065f) ambiguous=true
        }
        if(ambiguous) return IdentityResult(intArrayOf(-1,-1),scores,true)
        for(i in 0..1) {
            val j=indices[i];if(j<0) continue
            val o=hands[j];val old=tracks[i]
            val dt=if(old==null) 0f else (now-old.time)/1000f
            val vx=if(old!=null && dt in 0.025f..0.35f) ((o.x-old.x)/dt).coerceIn(-2f,2f) else 0f
            val vy=if(old!=null && dt in 0.025f..0.35f) ((o.y-old.y)/dt).coerceIn(-2f,2f) else 0f
            tracks[i]=Track(o.x,o.y,vx,vy,now)
        }
        return IdentityResult(indices,scores,false)
    }
}

internal data class CursorPrediction(val x:Float,val y:Float,val degree:Float=0f,val lead:Float=0f)
/** Short bounded extrapolation for displayed open-palm pointer only.
 * Never predicts a pinch, swipe decision or drag coordinates. */
internal class FuzzyMotionPredictor {
    private var time=-1L
    private var x=0f;private var y=0f;private var vx=0f;private var vy=0f
    fun reset(){time=-1L;vx=0f;vy=0f}
    fun update(nx:Float,ny:Float,now:Long,age:Long,quality:Float):CursorPrediction {
        if(!nx.isFinite() || !ny.isFinite()) { reset();return CursorPrediction(.5f,.5f) }
        val dt=(now-time)/1000f
        if(time<0 || dt !in 0.025f..0.35f) {
            x=nx;y=ny;time=now;vx=0f;vy=0f
            return CursorPrediction(nx,ny)
        }
        val dx=(nx-x)/dt;val dy=(ny-y)/dt
        val speed=hypot(dx,dy)
        val oldSpeed=hypot(vx,vy)
        val agreement=if(speed>.001f && oldSpeed>.001f) ((dx*vx+dy*vy)/(speed*oldSpeed)).coerceIn(-1f,1f) else 0f
        val degree=FuzzyCore.and(FuzzyCore.and(FuzzyCore.rise(speed,.12f,.8f),FuzzyCore.rise(agreement,.5f,.95f)),
            FuzzyCore.and(quality,FuzzyCore.fall(age.toFloat(),130f,400f)))
        vx=dx;vy=dy;x=nx;y=ny;time=now
        val horizon=(age.coerceIn(0L,65L)/1000f)*degree
        val length=(speed*horizon).coerceAtMost(.012f)
        return if(speed>.001f) CursorPrediction((nx+dx/speed*length).coerceIn(0f,1f),(ny+dy/speed*length).coerceIn(0f,1f),degree,length)
            else CursorPrediction(nx,ny)
    }
}
internal data class IntentCandidate(val slot:Int,val pinch:Boolean,val score:Float)
/** Preserve current owner. On simultaneous new actions, clear winner only;
 * a tie abstains instead of letting detector array order decide. */
internal object FuzzyConflict {
    fun choose(owner:Int?,candidates:List<IntentCandidate>):Int? {
        if(owner!=null) return owner
        if(candidates.isEmpty()) return null
        if(candidates.size==1) return candidates[0].slot
        val pinches=candidates.filter{it.pinch}
        if(pinches.size==1) return pinches[0].slot
        val sorted=(if(pinches.isEmpty()) candidates else pinches).sortedByDescending{it.score}
        val first=sorted[0];val second=sorted[1]
        return if(FuzzyCore.alphaCut(first.score,.55f) && first.score-second.score>=.15f) first.slot else null
    }
}
