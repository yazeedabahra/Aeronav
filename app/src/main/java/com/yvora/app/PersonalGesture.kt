package com.yvora.app

import kotlin.math.abs

/** Normalized pose memberships, never raw landmarks or camera frames. */
internal data class PoseFeatures(val edge:Float,val extension:Float,val together:Float) {
    fun valid()=edge.isFinite() && extension.isFinite() && together.isFinite() && edge in 0f..1f && extension in 0f..1f && together in 0f..1f
    fun eligible()=valid() && edge>=.40f && extension>=.45f && together>=.45f
    /** Fuzzy Jaccard similarity: sum of intersections / sum of unions. */
    fun similarity(other:PoseFeatures):Float {
        if(!valid() || !other.valid()) return 0f
        val a=floatArrayOf(edge,extension,together);val b=floatArrayOf(other.edge,other.extension,other.together)
        var numerator=0f;var denominator=0f
        for(i in a.indices) { numerator+=FuzzyCore.and(a[i],b[i]);denominator+=FuzzyCore.or(a[i],b[i]) }
        return if(denominator>1e-6f) numerator/denominator else 0f
    }
}
internal object PersonalGesture {
    // The learned alternative cannot accept a fist, open face-on palm or closed pinch.
    // It still must pass the existing two-sample pose and horizontal-motion gates.
    fun degree(sample:PoseFeatures?,template:PoseFeatures?,quality:Float,pinchClear:Boolean):Float {
        if(sample==null || template==null || !sample.eligible() || !template.eligible() || !pinchClear) return 0f
        val similarity=sample.similarity(template)
        if(similarity<.80f) return 0f
        return FuzzyCore.and(similarity,quality)
    }
}
internal class PoseTraining(val slot:Int,private val start:Long) {
    private val samples=ArrayList<PoseFeatures>()
    val count get()=samples.size
    fun expired(now:Long)=now-start>12000L || now<start
    fun add(trace:FuzzyHandTrace,now:Long) {
        if(expired(now) || samples.size>=100 || !trace.present || trace.quality<.6f ||
            !trace.pinchRatio.isFinite() || trace.pinchRatio<.34f) return
        trace.features?.takeIf{it.eligible()}?.let{samples.add(it)}
    }
    fun finish(now:Long):PoseFeatures? {
        if(expired(now) || now-start<2000L || samples.size<10) return null
        val mean=PoseFeatures(samples.map{it.edge}.average().toFloat(),
            samples.map{it.extension}.average().toFloat(),samples.map{it.together}.average().toFloat())
        if(!mean.eligible() || samples.count { it.similarity(mean)>=.80f } < samples.size*.85f) return null
        return mean
    }
}
