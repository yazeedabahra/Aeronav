package com.yvora.app

import kotlin.math.abs

/** Android thermal severity is an ordinal (0 none, 1 light, 2 moderate, 3 severe...). */
internal class ThermalPolicy(private val lowRam: Boolean, private val responsive:Boolean=false) {
    var cooling = false
        private set
    var warm = false
        private set
    var fuzzyHeat=0f
        private set
    private var recoverySince = -1L
    private var lastActivity = -1L
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastFingers = -1
    private var hand = false

    fun environment(now: Long, severity: Int, batteryTenths: Int?, powerSave: Boolean) {
        val hot = severity >= 3 || (batteryTenths != null && batteryTenths >= 430)
        val recovered = severity <= 1 && (batteryTenths == null || batteryTenths < 400)
        if (hot) {
            cooling = true
            recoverySince = -1L
        } else if (cooling) {
            if (!recovered) recoverySince = -1L
            else {
                if (recoverySince < 0L) recoverySince = now
                if (now - recoverySince >= 15000L) {
                    cooling = false
                    recoverySince = -1L
                }
            }
        }
        warm = severity >= 2 || powerSave || (batteryTenths != null && batteryTenths >= 400)
        fuzzyHeat=FuzzyControllers.heatDegree(batteryTenths,severity)
    }

    fun observe(now: Long, detected: Boolean, x: Float, y: Float, fingers: Int, gesture: Boolean) {
        if (!detected) {
            hand = false
            lastX = Float.NaN; lastY = Float.NaN; lastFingers = -1
            lastActivity = -1L
            return
        }
        if (!hand || gesture || fingers != lastFingers ||
            abs(x - lastX) + abs(y - lastY) >= 0.012f) {
            lastActivity = now
            lastX = x; lastY = y
        }
        hand = true
        lastFingers = fingers
    }

    // The faster inference budget is only available when cool and outside power saver.
    val boostAllowed:Boolean get()=responsive && !warm && !cooling && fuzzyHeat<.50f
    fun interval(now: Long): Long {
        val active=lastActivity>=0L && now-lastActivity<=700L
        val base=when {
            !hand -> if(warm) 320L else 250L
            warm && active -> 150L
            warm -> 220L
            boostAllowed && active -> if(lowRam) 65L else 48L
            boostAllowed -> if(lowRam) 100L else 85L
            active -> if(lowRam) 110L else 90L
            else -> 160L
        }
        return FuzzyControllers.thermalInterval(base,fuzzyHeat,warm)
    }
}
