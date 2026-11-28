package com.yvora.app

/** Local bounded benchmark, sampled on the service main thread. No raw landmarks or network. */
internal class PerformanceSession(val start:Long,private val maxDuration:Long=1_800_000L){
    private val ageBins=IntArray(51)
    private var ageSum=0L
    private var frames=0
    private var draws=0
    private var drawSum=0.0
    private var samples=0
    private var warm=0
    private var paused=0
    private var maxTemp:Int?=null
    private var end:Long?=null
    fun expired(now:Long)=now-start>=maxDuration || now<start
    val active get()=end==null
    fun frame(age:Long,now:Long){
        if(!active)return
        if(expired(now)){finish(now);return}
        val a=age.coerceIn(0L,500L)
        frames++;ageSum+=a;ageBins[(a/10).toInt()]++
    }
    fun sample(now:Long,drawRate:Float,battery:Int?,warmNow:Boolean,pausedNow:Boolean){
        if(!active)return
        if(expired(now)){finish(now);return}
        samples++;if(warmNow)warm++;if(pausedNow)paused++
        if(battery!=null)maxTemp=maxOf(maxTemp ?: battery,battery)
        if(drawRate.isFinite() && drawRate>0f){draws++;drawSum+=drawRate}
    }
    fun finish(now:Long){if(end==null)end=now.coerceIn(start,start+maxDuration)}
    fun snapshot(now:Long):PerformanceSnapshot {
        val elapsed=((end ?: now).coerceIn(start,start+maxDuration)-start)
        var cumulative=0
        val rank=kotlin.math.ceil(frames*.95).toInt()
        var p95:Long?=null
        if(rank>0)for(i in ageBins.indices){
            cumulative+=ageBins[i]
            if(cumulative>=rank){p95=(i*10L+9L).coerceAtMost(500L);break}
        }
        return PerformanceSnapshot(active,elapsed,frames,if(frames>0)ageSum.toDouble()/frames else null,p95,
            if(draws>0)drawSum/draws else null,draws,maxTemp,samples,warm,paused)
    }
}
internal data class PerformanceSnapshot(val active:Boolean,val elapsed:Long,val frames:Int,val meanAge:Double?,val p95Age:Long?,val meanDraw:Double?,val drawSamples:Int,val peakTemp:Int?,val samples:Int,val warmSamples:Int,val pausedSamples:Int)
internal object LocalPerformance {
    private var current:PerformanceSession?=null
    fun start(now:Long){current=PerformanceSession(now)}
    fun frame(age:Long,now:Long){current?.frame(age,now)}
    fun sample(now:Long,drawRate:Float,battery:Int?,warm:Boolean,paused:Boolean){current?.sample(now,drawRate,battery,warm,paused)}
    fun stop(now:Long){current?.finish(now)}
    fun snapshot(now:Long):PerformanceSnapshot? {
        current?.let{if(it.active && it.expired(now))it.finish(now)}
        return current?.snapshot(now)
    }
}
