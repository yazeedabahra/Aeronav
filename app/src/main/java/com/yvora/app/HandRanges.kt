package com.yvora.app

/** Slot 0 = displayed left hand (cyan), slot 1 = right hand (amber).
 * Shared and individual profiles are stored separately, so linking is reversible.
 */
internal data class HandRanges(
    val linked:Boolean=true,
    val shared:ControlRange=ControlRange(),
    val left:ControlRange=shared,
    val right:ControlRange=shared
) {
    fun effective(slot:Int)=if(linked) shared else if(slot==0) left else right
    fun edit(slot:Int,range:ControlRange):HandRanges =
        if(linked) copy(shared=range) else if(slot==0) copy(left=range) else copy(right=range)
    fun link(enabled:Boolean,source:Int):HandRanges =
        if(enabled==linked) this
        else if(enabled) copy(linked=true,shared=effective(source))
        else copy(linked=false)
}

/** Collect only one labelled hand's raw coordinates during safe calibration.
 * No images, network, or persistent samples. Bounded duration and memory.
 */
internal class RangeCapture(val slot:Int,private val started:Long) {
    private val xs=ArrayList<Float>()
    private val ys=ArrayList<Float>()
    val count:Int get()=xs.size
    fun expired(now:Long)=now-started>15000L
    fun add(x:Float,y:Float,now:Long) {
        if(expired(now) || xs.size>=180 || !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return
        xs.add(x);ys.add(y)
    }
    fun finish(now:Long):ControlRange? {
        if(now-started<2000L || expired(now) || count<10) return null
        fun bounds(samples:List<Float>):Pair<Float,Float> {
            val ordered=samples.sorted()
            // Trim only the outer 5% to reduce isolated landmark glitches.
            val trim=(ordered.size*.05f).toInt()
            return ordered[trim] to ordered[ordered.lastIndex-trim]
        }
        val (x0,x1)=bounds(xs);val (y0,y1)=bounds(ys)
        if(x1-x0<.08f || y1-y0<.08f) return null
        fun percent(v:Float)=(v*100f).toInt().coerceIn(10,100)
        fun center(a:Float,b:Float)=kotlin.math.round((a+b)*50f).toInt().coerceIn(0,100)
        // Small inward margin makes the observed comfortable extremes reachable.
        return ControlRange(percent((x1-x0)*.97f),percent((y1-y0)*.97f),center(x0,x1),center(y0,y1))
    }
}
