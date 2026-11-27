package com.yvora.app

import android.content.Context

internal object TutorialPrefs {
    private fun prefs(c:Context)=c.getSharedPreferences("tutorial",Context.MODE_PRIVATE)
    fun seen(c:Context)=prefs(c).getBoolean("intro_seen_v1",false)
    fun finish(c:Context){prefs(c).edit().putBoolean("intro_seen_v1",true).apply()}
}
internal data class PracticeSample(val time:Long,val x:Float,val y:Float,val pinch:Float,val open:Boolean,val quality:Float)
internal enum class PracticeStage { OPEN, MOVE, PINCH, RELEASE, DONE }
internal class TutorialPractice {
    var stage=PracticeStage.OPEN
        private set
    var slot=-1
        private set
    private var last=-1L
    private var openFrames=0
    private var anchorX=0f
    private var anchorY=0f
    fun reset(){stage=PracticeStage.OPEN;slot=-1;last=-1L;openFrames=0;anchorX=0f;anchorY=0f}
    fun observe(left:PracticeSample?,right:PracticeSample?,now:Long):PracticeStage {
        if(stage==PracticeStage.DONE)return stage
        val candidates=listOf(left,right)
        fun valid(s:PracticeSample?)=s!=null && s.time>=0 && now-s.time in 0L..500L &&
            s.x.isFinite() && s.y.isFinite() && s.pinch.isFinite() && s.quality>=.55f
        val s=if(slot<0){
            val i=candidates.indexOfFirst{it!=null && valid(it) && it.open && it.pinch>=.34f}
            if(i<0){reset();return stage};slot=i;candidates[i]
        }else candidates[slot]
        if(!valid(s)){reset();return stage}
        val sample=s!!
        if(sample.time==last)return stage // one vote per processed camera frame
        if(last>=0 && (sample.time<last || sample.time-last>550L)){reset();return stage}
        last=sample.time
        when(stage){
            PracticeStage.OPEN->{
                if(sample.open && sample.pinch>=.34f){
                    if(++openFrames>=2){anchorX=sample.x;anchorY=sample.y;stage=PracticeStage.MOVE}
                }else openFrames=0
            }
            PracticeStage.MOVE->if(sample.open && sample.pinch>=.34f &&
                kotlin.math.hypot(sample.x-anchorX,sample.y-anchorY)>=.12f)stage=PracticeStage.PINCH
            PracticeStage.PINCH->if(sample.pinch<.23f)stage=PracticeStage.RELEASE
            PracticeStage.RELEASE->if(sample.pinch>=.34f)stage=PracticeStage.DONE
            PracticeStage.DONE->Unit
        }
        return stage
    }
}
