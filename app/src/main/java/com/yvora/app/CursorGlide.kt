package com.yvora.app

/** Display-only interpolation. No future prediction, no input dispatch.
 * A fresh target starts from the last rendered position, not an unshown estimate.
 * The time budget stays short even when camera inference slows down. */
internal class CursorGlide {
    private var startX=0f
    private var startY=0f
    private var targetX=0f
    private var targetY=0f
    private var startTime=0L
    private var duration=0L
    var active=false
        private set
    data class Position(val x:Float,val y:Float,val done:Boolean)
    fun aim(fromX:Float,fromY:Float,toX:Float,toY:Float,now:Long,sampleGap:Long,responsive:Boolean=false):Boolean {
        if(!fromX.isFinite() || !fromY.isFinite() || !toX.isFinite() || !toY.isFinite()){
            cancel();return false
        }
        startX=fromX;startY=fromY;targetX=toX;targetY=toY;startTime=now
        // Reach the newest observed position quickly, with no long animation backlog.
        duration=if(responsive) (sampleGap.coerceIn(30L,200L)*.85f).toLong().coerceIn(24L,85L)
            else (sampleGap.coerceIn(40L,250L)*.65f).toLong().coerceIn(32L,95L)
        active=kotlin.math.abs(toX-fromX)>=.5f || kotlin.math.abs(toY-fromY)>=.5f
        return active
    }
    fun sample(now:Long):Position? {
        if(!active)return null
        val t=((now-startTime).coerceAtLeast(0L).toFloat()/duration).coerceIn(0f,1f)
        // Linear progression avoids repeatedly braking and accelerating at each camera frame.
        val done=t>=1f
        if(done)active=false
        return if(done)Position(targetX,targetY,true)
            else Position(startX+(targetX-startX)*t,startY+(targetY-startY)*t,false)
    }
    fun cancel(){active=false}
}
