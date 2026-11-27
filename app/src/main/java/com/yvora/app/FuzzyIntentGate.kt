package com.yvora.app

import kotlin.math.min

/**
 * Continuous membership of a relaxed swipe pose, not a probability or a learned model.
 * Strong motion is still required by HorizontalSwipeEngine. The user supplies bounded
 * feedback via a saved tolerance preset; no automatic execution of unknown actions.
 */
internal object FuzzyPose {
    fun rise(x:Float, low:Float, high:Float):Float =
        if(x.isFinite()) ((x-low)/(high-low)).coerceIn(0f,1f) else 0f
    fun score(edge:Float, extension:Float, spread:Float):Float = minOf(
        rise(edge,0.24f,0.80f),
        rise(extension,1.35f,3.15f),
        1f-rise(spread,1.15f,2.0f)
    )
    fun threshold(level:Int):Float = when(level.coerceIn(-1,1)) { -1 -> .72f;1 -> .50f;else -> .61f }
}
internal class FuzzyIntentGate {
    private var armed=false
    private var samples=0
    fun reset() { armed=false;samples=0 }
    fun update(enabled:Boolean, score:Float, threshold:Float, pinchClear:Boolean):Boolean {
        if(!enabled || !pinchClear || !score.isFinite() || score < threshold-(if(armed) .10f else 0f)) {
            reset();return false
        }
        if(++samples>=2) armed=true
        return armed
    }
}
