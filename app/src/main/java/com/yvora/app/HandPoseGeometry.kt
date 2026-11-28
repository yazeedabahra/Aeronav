package com.yvora.app

import kotlin.math.hypot
import kotlin.math.sqrt

/** Pixel-aspect-corrected geometry, normalized by the actual detected palm. */
internal class HandPoseGeometry {
    private val xy = FloatArray(42)
    private var mask = 0
    fun set(index: Int, x: Float, y: Float) {
        xy[index * 2] = x
        xy[index * 2 + 1] = y
    }
    private fun d(a: Int, b: Int): Float =
        hypot(xy[a*2] - xy[b*2], xy[a*2+1] - xy[b*2+1])
    fun palmSize(): Float = maxOf(d(5, 17), d(0, 9), 1f)
    fun pinchRatio(): Float = d(4, 8) / palmSize()
    fun ringRatio(): Float = d(4, 16) / palmSize()
    // Cosine between consecutive forward segments: +1 means straight.
    private fun straight(a: Int, b: Int, c: Int): Float {
        val ux = xy[b*2] - xy[a*2]; val uy = xy[b*2+1] - xy[a*2+1]
        val vx = xy[c*2] - xy[b*2]; val vy = xy[c*2+1] - xy[b*2+1]
        val denom = sqrt((ux*ux+uy*uy)*(vx*vx+vy*vy))
        return if (denom > 0.001f) (ux*vx+uy*vy)/denom else -1f
    }
    fun updateMask(): Int {
        var next = 0
        val palm = palmSize()
        // A straight thumb folded over the palm is not a raised thumb.
        val thumbWas = mask and 1 != 0
        if (straight(2,3,4) >= (if (thumbWas) 0.30f else 0.48f)) {
            if (d(4, 5) >= palm * (if (thumbWas) 0.43f else 0.53f) &&
                d(4, 9) >= palm * (if (thumbWas) 0.68f else 0.82f)) next = next or 1
        }
        for (finger in 1..4) {
            val base = finger*4+1
            val was = mask and (1 shl finger) != 0
            val aligned = straight(base,base+1,base+2) >= (if (was) 0.25f else 0.45f) &&
                straight(base+1,base+2,base+3) >= (if (was) 0.15f else 0.30f)
            if (aligned && d(base,base+3) >= palm*(if (was) 0.38f else 0.45f) &&
                d(0,base+3) > d(0,base+1)) next = next or (1 shl finger)
        }
        mask = next
        return next
    }
    fun reset() { mask = 0 }
}
