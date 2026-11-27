package com.yvora.app

import java.util.Locale

internal data class AiRoute(val actions:List<AiAction>?,val coverage:Float,val similarity:Float,val localDegree:Float)

/** Local dictionary plus fuzzy lexical compatibility. Compatibility is advisory:
 * only a complete exact allowlisted plan may be executed after explicit approval. */
internal object FuzzyAiRouter {
    private fun norm(s:String)=s.trim().lowercase(Locale.ROOT)
        .replace(Regex("[أإآ]"),"ا").replace(Regex("[ \\t]+")," ")
        .trimEnd('.','!')
        .trim()
    private val aliases=buildMap<String,AiAction> {
        fun add(a:AiAction,vararg phrases:String) { for(p in phrases) put(norm(p),a);put(a.name.lowercase(Locale.ROOT),a) }
        add(AiAction.SWIPE_LEFT,"swipe left","اسحب يسار","اسحب لليسار","اسحب الى اليسار","سحب يسار")
        add(AiAction.SWIPE_RIGHT,"swipe right","اسحب يمين","اسحب لليمين","اسحب الى اليمين","سحب يمين")
        add(AiAction.SWIPE_UP,"swipe up","اسحب فوق","اسحب للاعلى","اسحب الى الاعلى","اسحب لأعلى")
        add(AiAction.SWIPE_DOWN,"swipe down","اسحب تحت","اسحب للاسفل","اسحب الى الاسفل","اسحب لأسفل")
        add(AiAction.BACK,"back","go back","ارجع","رجوع","الرجوع")
        add(AiAction.HOME,"home","go home","go to home screen","الشاشة الرئيسية","ارجع للشاشة الرئيسية")
    }
    private fun similarity(a:String,b:String):Float {
        val x=a.split(' ').filter{it.isNotBlank()}.toSet()
        val y=b.split(' ').filter{it.isNotBlank()}.toSet()
        val union=x.union(y)
        return if(union.isEmpty()) 0f else x.intersect(y).size.toFloat()/union.size
    }
    fun route(raw:String):AiRoute {
        if(raw.isBlank() || raw.length>300) return AiRoute(null,0f,0f,0f)
        val parts=norm(raw).split(Regex("\\s*(?:\\r?\\n|;|؛|,?\\s+then\\s+|،?\\s+ثم\\s+)\\s*")).map(::norm)
        if(parts.size !in 1..4 || parts.any { it.isBlank() }) return AiRoute(null,0f,0f,0f)
        val matched=parts.map { aliases[it] }
        val coverage=matched.count { it!=null }.toFloat()/parts.size
        val lexical=parts.map { part -> aliases.keys.maxOf { similarity(part,it) } }.average().toFloat()
        val degree=FuzzyCore.and(coverage,lexical)
        val plan=matched.filterNotNull()
        return AiRoute(if(FuzzyCore.alphaCut(degree,1f) && matched.all{it!=null} && AiCommandGate.executable(plan)) plan else null,
            coverage,lexical,degree)
    }
}
