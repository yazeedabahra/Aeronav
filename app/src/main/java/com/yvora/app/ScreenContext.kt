package com.yvora.app

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

internal data class ScreenStructure(val visible:Int,val clickable:Int,val scrollable:Int,
    val forward:Int,val backward:Int,val vertical:Int,val horizontal:Int,val truncated:Boolean) {
    fun describe():String = buildString {
        append(localized("Local screen structure: $visible visible nodes; $clickable clickable; $scrollable scrollable.","بنية الشاشة المحلية: $visible عنصر ظاهر؛ $clickable قابل للنقر؛ $scrollable قابل للتمرير."))
        if(vertical>0) append(localized("\nVertical scroll actions exposed.","\nتوجد إجراءات تمرير عمودي."))
        if(horizontal>0) append(localized("\nHorizontal scroll actions exposed.","\nتوجد إجراءات تمرير أفقي."))
        if(forward+backward>0) append(localized("\nForward/backward scroll actions exposed.","\nتوجد إجراءات تمرير للأمام والخلف."))
        if(scrollable==0) append(localized("\nNo scrollable nodes exposed. The app may still support gestures.","\nلا توجد عناصر تمرير ظاهرة للخدمة. قد يدعم التطبيق الإيماءات."))
        if(truncated) append(localized("\nPartial inspection (bounded scan).","\nفحص جزئي (محدود النطاق)."))
        append(localized("\nNo text, labels, IDs, screenshots or form contents read. Not sent to AI.","\nلم تُقرأ النصوص أو التسميات أو المعرّفات أو الصور أو بيانات النماذج. لم تُرسل إلى الذكاء الاصطناعي."))
    }
}

/** Explicit bounded structural read. No text, content descriptions, stored snapshot or actions. */
internal object ScreenContext {
    @Suppress("DEPRECATION")
    fun inspect(service:YvoraAccessibilityService,allowed:(String?)->Boolean):ScreenStructure? {
        val root=try { service.rootInActiveWindow } catch(_:RuntimeException){null} ?: return null
        val queue=ArrayDeque<Pair<AccessibilityNodeInfo,Int>>()
        queue.add(root to 0)
        var visited=0;var visible=0;var clickable=0;var scrollable=0
        var forward=0;var backward=0;var vertical=0;var horizontal=0;var truncated=false
        try {
            val pkg=root.packageName?.toString()
            if(!allowed(pkg)) return null
            while(queue.isNotEmpty() && visited<120) {
                val (node,depth)=queue.removeFirst()
                try {
                    visited++
                    if(node.packageName?.toString()!=pkg) continue
                    if(node.isPassword) return null
                    if(node.isVisibleToUser) {
                        visible++
                        if(node.isEnabled && node.isClickable && !node.isEditable) clickable++
                        if(node.isEnabled && node.isScrollable && !node.isEditable) {
                            scrollable++
                            val ids=node.actionList.map{it.id}.toSet()
                            if(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids) forward++
                            if(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids) backward++
                            if(16908344 in ids || 16908346 in ids) vertical++
                            if(16908345 in ids || 16908347 in ids) horizontal++
                        }
                    }
                    if(depth>=12 || node.isEditable) {if(node.childCount>0) truncated=true;continue}
                    val limit=minOf(node.childCount,120-visited-queue.size)
                    if(limit<node.childCount) truncated=true
                    for(i in 0 until limit.coerceAtLeast(0)) node.getChild(i)?.let{queue.add(it to depth+1)}
                } finally { node.recycle() }
            }
            if(queue.isNotEmpty()) truncated=true
            return ScreenStructure(visible,clickable,scrollable,forward,backward,vertical,horizontal,truncated)
        } catch(_:RuntimeException) { return null }
        finally { while(queue.isNotEmpty()) queue.removeFirst().first.recycle() }
    }
}
