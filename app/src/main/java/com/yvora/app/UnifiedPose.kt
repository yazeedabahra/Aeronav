package com.yvora.app

import kotlin.math.abs

internal enum class ControlPose { POINTER,TAP,DRAG;
    fun title()=when(this){POINTER->localized("Pointer","المؤشر");TAP->localized("Tap","النقر");DRAG->localized("Drag","السحب المستمر")}
}
internal data class PoseSignature(val mask:Int,val pinch:Float,val ring:Float,val edge:Float){
    fun valid()=mask in 0..31 && listOf(pinch,ring,edge).all{it.isFinite()} && pinch in 0f..2f && ring in 0f..2f && edge in 0f..1f
    fun eligible()=valid() && ring>=.42f
    fun degree(b:PoseSignature):Float {
        if(!valid()||!b.valid())return 0f
        return minOf(1f-Integer.bitCount(mask xor b.mask)*.34f,
            FuzzyCore.fall(abs(pinch-b.pinch),.12f,.50f),
            FuzzyCore.fall(abs(ring-b.ring),.18f,.70f),
            FuzzyCore.fall(abs(edge-b.edge),.15f,.60f)).coerceIn(0f,1f)
    }
    fun encode()="$mask,$pinch,$ring,$edge"
    companion object{
        fun decode(raw:String?):PoseSignature?{
            val a=raw?.split(',') ?: return null
            if(a.size!=4)return null
            return PoseSignature(a[0].toIntOrNull()?:return null,a[1].toFloatOrNull()?:return null,
                a[2].toFloatOrNull()?:return null,a[3].toFloatOrNull()?:return null).takeIf{it.eligible()}
        }
    }
}
internal data class PairPose(val left:PoseSignature,val right:PoseSignature){
    fun degree(other:PairPose)=minOf(left.degree(other.left),right.degree(other.right))
    fun encode()=left.encode()+";"+right.encode()
    companion object{
        fun decode(raw:String?):PairPose?{
            val parts=raw?.split(';')?:return null
            if(parts.size!=2)return null
            return PairPose(PoseSignature.decode(parts[0])?:return null,PoseSignature.decode(parts[1])?:return null)
        }
    }
}
internal val pairActions=listOf(RegistryAction.ZOOM_IN,RegistryAction.ZOOM_OUT,RegistryAction.ROTATE_CW,RegistryAction.ROTATE_CCW)
internal object PoseRegistry {
    const val MIN=.80f
    const val CONFLICT=.65f
    const val MARGIN=.15f
    fun learn(samples:List<PoseSignature>):PoseSignature?{
        if(samples.size!=4 || samples.any{!it.eligible()})return null
        val first=samples.first()
        if(samples.any{first.degree(it)<MIN})return null
        val trained=PoseSignature(first.mask,samples.take(3).map{it.pinch}.average().toFloat(),
            samples.take(3).map{it.ring}.average().toFloat(),samples.take(3).map{it.edge}.average().toFloat())
        return trained.takeIf{it.eligible() && samples.all{s->it.degree(s)>=MIN}}
    }
    // Conservative overlap checks against built-in active pose domains, not percentages of probability.
    fun defaults(p:PoseSignature,overrides:Map<ControlPose,PoseSignature>,except:ControlPose?=null):List<String>{
        val hits=mutableListOf<String>()
        if(except!=ControlPose.POINTER && ControlPose.POINTER !in overrides && Integer.bitCount(p.mask)>=4 && p.pinch>=.27f && p.edge<.46f)hits.add(localized("Default pointer","المؤشر الافتراضي"))
        if((except!=ControlPose.TAP && ControlPose.TAP !in overrides || except!=ControlPose.DRAG && ControlPose.DRAG !in overrides) && p.pinch<.34f)hits.add(localized("Default pinch tap/drag","النقر أو السحب الافتراضي"))
        val nonThumb=p.mask and 30
        if(nonThumb==2 || nonThumb==6)hits.add(localized("Default vertical swipe","السحب العمودي الافتراضي"))
        if(Integer.bitCount(nonThumb)>=3 && p.edge>=.32f)hits.add(localized("Default horizontal swipe","السحب الأفقي الافتراضي"))
        if(p.ring<.42f)hits.add(localized("Reserved motion clutch","وضع تفعيل المسارات المحجوز"))
        return hits
    }
    fun conflicts(action:ControlPose,samples:List<PoseSignature>,existing:Map<ControlPose,PoseSignature>,pairs:Map<RegistryAction,PairPose>,slot:Int):List<String>{
        val result=mutableListOf<String>()
        for(p in samples){
            result.addAll(defaults(p,existing,action))
            existing.filterKeys{it!=action}.forEach{(a,b)->val d=p.degree(b);if(d>=CONFLICT)result.add(a.title()+" "+(d*100).toInt()+"%")}
            pairs.forEach{(a,b)->val d=p.degree(if(slot==0)b.left else b.right);if(d>=CONFLICT)result.add(a.title()+" "+(d*100).toInt()+"%")}
        }
        return result.distinct()
    }
    fun pairConflicts(samples:List<PairPose>,action:RegistryAction,controls:Array<Map<ControlPose,PoseSignature>>,pairs:Map<RegistryAction,PairPose>):List<String>{
        val result=mutableListOf<String>()
        for(p in samples){
            for(i in 0..1){
                val part=if(i==0)p.left else p.right
                result.addAll(defaults(part,controls[i]))
                controls[i].forEach{(a,b)->val d=part.degree(b);if(d>=CONFLICT)result.add(a.title()+" "+(d*100).toInt()+"%")}
            }
            pairs.filterKeys{it!=action}.forEach{(a,b)->val d=p.degree(b);if(d>=CONFLICT)result.add(a.title()+" "+(d*100).toInt()+"%")}
        }
        return result.distinct()
    }
    fun choose(p:PoseSignature,templates:Map<ControlPose,PoseSignature>):ControlPose?{
        val ranked=templates.map{(a,b)->a to p.degree(b)}.sortedByDescending{it.second}
        val best=ranked.firstOrNull()?:return null
        return best.first.takeIf{best.second>=MIN && (ranked.size==1||best.second-ranked[1].second>=MARGIN)}
    }
}
/** One gesture at a time. Tap fires only on release. Drag starts on sustained pose.
 * Requires a neutral pointer frame before each new touch; never converts tap into drag. */
internal class PoseTouch {
    private var ready=false
    private var kind:ControlPose?=null
    private var start=-1L
    private var last=-1L
    private var dragging=false
    val active get()=kind!=null
    fun reset(){ready=false;kind=null;start=-1;last=-1;dragging=false}
    fun update(intent:ControlPose?,neutral:Boolean,now:Long,reliable:Boolean,supportsDrag:Boolean):PinchAction {
        if(!reliable || (active && (now<=last || now-last>550L))){val was=active;reset();return if(was)PinchAction.CANCEL else PinchAction.NONE}
        last=now
        val owned=kind
        if(owned!=null){
            if(intent!=owned){
                val result=when{
                    !neutral->PinchAction.CANCEL
                    dragging->PinchAction.DROP
                    owned==ControlPose.TAP && now-start in 100L..1500L->PinchAction.TAP
                    else->PinchAction.CANCEL
                }
                reset();return result
            }
            if(owned==ControlPose.TAP && now-start>1500L){reset();return PinchAction.CANCEL}
            if(dragging)return PinchAction.MOVE_DRAG
            if(owned==ControlPose.DRAG && supportsDrag && now-start>=300L){dragging=true;return PinchAction.START_DRAG}
            return PinchAction.PENDING
        }
        if(neutral){ready=true;return PinchAction.NONE}
        if(!ready || intent==null || intent==ControlPose.POINTER){ready=false;return PinchAction.NONE}
        if(intent==ControlPose.DRAG && !supportsDrag){ready=false;return PinchAction.NONE}
        kind=intent;start=now;ready=false;return PinchAction.PENDING
    }
}
internal class PairGate {
    private var ready=false
    private var candidate:RegistryAction?=null
    private var count=0
    private var since=-1L
    private var last=-1L
    var reserved=false
        private set
    fun reset(){ready=false;candidate=null;count=0;since=-1;last=-1;reserved=false}
    fun update(sample:PairPose?,templates:Map<RegistryAction,PairPose>,neutral:Boolean,now:Long):RegistryAction?{
        if(sample==null||templates.isEmpty()||(last>=0&&(now<=last||now-last>550L))){reset();return null}
        last=now
        val ranked=templates.map{(a,p)->a to sample.degree(p)}.sortedByDescending{it.second}
        val best=ranked.first()
        reserved=best.second>=PoseRegistry.CONFLICT
        if(!reserved){candidate=null;count=0;if(neutral)ready=true;return null}
        if(!ready || best.second<PoseRegistry.MIN || (ranked.size>1 && best.second-ranked[1].second<PoseRegistry.MARGIN)){candidate=null;count=0;return null}
        if(candidate!=best.first){candidate=best.first;count=1;since=now;return null}
        count++
        if(count>=2 && now-since>=300L){ready=false;candidate=null;count=0;return best.first}
        return null
    }
}
