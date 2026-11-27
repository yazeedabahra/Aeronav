package com.yvora.app

/** Stable slots by handedness, never detector array order or screen position.
 * Preserve the app's existing mirrored-camera display convention.
 * Duplicate/uncertain labels are deliberately not assigned.
 */
internal object HandSlots {
    fun assign(labels: List<String?>): IntArray {
        val out=intArrayOf(-1,-1)
        for(slot in 0..1) {
            val expected=if(slot==0) "Right" else "Left"
            val matches=labels.indices.filter { labels[it]==expected }
            if(matches.size==1) out[slot]=matches[0]
        }
        return out
    }
}

/** Independent pointer positions, but one active Android touch stream at a time. */
internal class TouchArbiter {
    var owner:Int?=null
        private set
    private var nextAllowed=0L
    fun claim(slot:Int,now:Long):Boolean {
        if(owner==slot) return true
        if(owner!=null || now<nextAllowed) return false
        owner=slot
        return true
    }
    fun release(slot:Int,now:Long,cooldown:Long=350L) {
        if(owner==slot) { owner=null; nextAllowed=now+cooldown }
    }
    fun reset() { owner=null; nextAllowed=0L }
}
