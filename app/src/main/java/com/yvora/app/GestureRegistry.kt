package com.yvora.app

/** A separate allowlist from cloud AI: custom navigation never grants new AI capabilities. */
internal enum class RegistryAction {
    SWIPE_LEFT, SWIPE_RIGHT, SWIPE_UP, SWIPE_DOWN, BACK, HOME,
    ZOOM_IN, ZOOM_OUT, ROTATE_CW, ROTATE_CCW;
    fun title()=when(this){
        SWIPE_LEFT->localized("Swipe left","سحب يسار")
        SWIPE_RIGHT->localized("Swipe right","سحب يمين")
        SWIPE_UP->localized("Swipe up","سحب أعلى")
        SWIPE_DOWN->localized("Swipe down","سحب أسفل")
        BACK->localized("Back","رجوع")
        HOME->localized("Home","الرئيسية")
        ZOOM_IN->localized("Zoom in","تكبير")
        ZOOM_OUT->localized("Zoom out","تصغير")
        ROTATE_CW->localized("Rotate clockwise","تدوير مع الساعة")
        ROTATE_CCW->localized("Rotate counterclockwise","تدوير عكس الساعة")
    }
}
internal data class RegistryConflict(val action:RegistryAction,val degree:Float)
internal data class RegistryReview(val pattern:MotionPattern?,val conflicts:List<RegistryConflict>,val verified:Boolean){
    val blocked get()=pattern==null || !verified || conflicts.any{it.degree>=GestureRegistry.CONFLICT}
}
/** Active domain: single selected hand, explicit pinch clutch, separate custom mode.
 * Default pointer, pinch tap/drag and transform recognizers do not compete in this mode. */
internal object GestureRegistry {
    const val ACCEPT=.80f
    const val MARGIN=.15f
    const val CONFLICT=.65f
    const val WARNING=.40f
    fun choose(sample:MotionPattern,templates:Map<RegistryAction,MotionPattern>):Pair<RegistryAction,Float>? {
        if(!sample.valid())return null
        val ranked=templates.filterValues{it.valid()}.map{(a,p)->a to MotionSimilarity.degree(sample,p)}.sortedByDescending{it.second}
        val best=ranked.firstOrNull() ?: return null
        return best.takeIf{it.second>=ACCEPT && (ranked.size==1 || it.second-ranked[1].second>=MARGIN)}
    }
    fun review(action:RegistryAction,examples:List<MotionPattern>,existing:Map<RegistryAction,MotionPattern>):RegistryReview {
        if(examples.size!=4 || examples.any{!it.valid()})return RegistryReview(null,emptyList(),false)
        val learned=MotionSimilarity.learn(examples.take(3)) ?: return RegistryReview(null,emptyList(),false)
        val others=existing.filterKeys{it!=action}
        val conflicts=others.map{(other,pattern)->
            RegistryConflict(other,(examples+learned).maxOf{MotionSimilarity.degree(it,pattern)})
        }.sortedByDescending{it.degree}.filter{it.degree>=WARNING}
        val verified=choose(examples.last(),others+(action to learned))?.first==action
        return RegistryReview(learned,conflicts,verified)
    }
}
internal class RegistryTraining(val slot:Int,val action:RegistryAction,private val started:Long){
    private val clutch=MotionClutch()
    private val samples=ArrayList<MotionPattern>(4)
    var status="Open palm to arm"
        private set
    val count get()=samples.size
    fun expired(now:Long)=now<started || now-started>90000L
    fun observe(sample:ZoomSample?,now:Long,aspect:Float){
        if(expired(now)||count>=4)return
        val r=clutch.update(sample,now,aspect);status=r.status
        r.pattern?.let{samples.add(it);status="Captured ${samples.size}/4"}
    }
    fun finish(now:Long)=if(!expired(now)&&count==4)samples.toList() else null
}
