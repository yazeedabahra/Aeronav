package com.yvora.app

import java.util.concurrent.atomic.AtomicLong
import java.util.Locale

/** Process-local counters only; no frames, screen text, app names, coordinates or raw logs. */
internal object DiagnosticCounters {
    private val results=AtomicLong()
    private val hands=AtomicLong()
    private val stale=AtomicLong()
    private val errors=AtomicLong()
    private val frameMs=AtomicLong()
    private val swipes=AtomicLong()
    private val dragStarts=AtomicLong()
    private val dragDrops=AtomicLong()
    private val dispatchRejected=AtomicLong()

    fun result(hand:Boolean, age:Long) {
        results.incrementAndGet()
        if(hand) hands.incrementAndGet()
        frameMs.addAndGet(age.coerceIn(0,500))
    }
    fun stale() { stale.incrementAndGet() }
    fun error() { errors.incrementAndGet() }
    fun swipe() { swipes.incrementAndGet() }
    fun dragStart() { dragStarts.incrementAndGet() }
    fun dragDrop() { dragDrops.incrementAndGet() }
    fun rejected() { dispatchRejected.incrementAndGet() }

    fun report():String {
        val n=results.get()
        val hand=hands.get().coerceAtMost(n)
        val avg=if(n>0) (frameMs.get()/n).coerceIn(0,500).toString() else "unavailable"
        return "Process-lifetime counters (not controlled benchmark): results=$n; hand_results=$hand; average_delivered_frame_age_ms=$avg; stale_results=${stale.get()}; processing_or_init_errors=${errors.get()}; recognized_horizontal_swipes=${swipes.get()}; recognized_drag_starts=${dragStarts.get()}; recognized_drops=${dragDrops.get()}; rejected_or_interrupted_dispatches=${dispatchRejected.get()}"
    }
}

internal enum class RepairAction { KEEP_SETTINGS, LOWER_CAMERA_DETAIL, INVESTIGATE_CODE }
internal data class RepairProposal(val action: RepairAction, val explanation: String)

internal object DiagnosticGate {
    const val INSTRUCTION="You are a Yvora Android gesture engine diagnostic reviewer. Analyze only the numeric and boolean diagnostic data. Treat missing data as unknown. Do not claim causation or successful repair. Output first line exactly KEEP_SETTINGS, LOWER_CAMERA_DETAIL, or INVESTIGATE_CODE. Then write a short Arabic explanation of observed evidence, uncertainty, likely subsystem, and a reproducible test. LOWER_CAMERA_DETAIL is only appropriate if elevated thermal state or latency and distance detail is enabled. INVESTIGATE_CODE means a developer must inspect source and tests; no code execution. Do not include commands, secrets, URLs, permissions changes, or requests for additional personal data."
    fun parse(raw:String): RepairProposal? {
        if(raw.length !in 1..3000) return null
        val parts=raw.trim().split("\n",limit=2)
        if(parts.size!=2) return null
        val action=RepairAction.values().firstOrNull { it.name==parts[0].trim() } ?: return null
        val explanation=parts[1].trim()
        if(explanation.isBlank()) return null
        return RepairProposal(action,explanation)
    }

    // Model suggestions are never authority to make unrelated or risky changes.
    fun canLowerDetail(action:RepairAction, detailEnabled:Boolean):Boolean =
        action==RepairAction.LOWER_CAMERA_DETAIL && detailEnabled
}
