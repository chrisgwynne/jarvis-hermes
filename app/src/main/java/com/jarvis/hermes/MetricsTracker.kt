package com.jarvis.hermes

import android.os.SystemClock

/**
 * In-memory metrics tracker.
 *
 * Captures latencies for each conversation step so we can answer the
 * "Jarvis was slow today" complaint with data. Surfaces via
 * MetricsActivity (and via the voice command "show metrics").
 *
 * The store is bounded — last 50 turns per metric. Older entries roll off.
 * Persisted to SharedPreferences (plain) every 10 turns; survives service
 * restarts but not factory resets — that's the right durability for
 * diagnostic data.
 */
object MetricsTracker {

    data class Turn(
        val wakeMs: Long,
        val sttMs: Long,
        val llmFirstTokenMs: Long,
        val totalMs: Long,
        val timestamp: Long
    )

    private val turns = ArrayDeque<Turn>()
    private const val MAX_TURNS = 50
    private const val PREFS = "jarvis_hermes"
    private const val KEY = "metrics_turns"

    // Mutable per-turn captures
    private var wakeStart: Long = 0
    private var wakeEnd: Long = 0
    private var sttStart: Long = 0
    private var sttEnd: Long = 0
    private var llmStart: Long = 0
    private var llmFirstToken: Long = 0
    private var turnStart: Long = 0

    @Synchronized
    fun onWakeStart() { wakeStart = SystemClock.elapsedRealtime() }
    @Synchronized
    fun onWakeDetected() { wakeEnd = SystemClock.elapsedRealtime() }
    @Synchronized
    fun onSttStart() { sttStart = SystemClock.elapsedRealtime(); turnStart = sttStart }
    @Synchronized
    fun onSttEnd() { sttEnd = SystemClock.elapsedRealtime() }
    @Synchronized
    fun onLlmStart() { llmStart = SystemClock.elapsedRealtime() }
    @Synchronized
    fun onLlmFirstToken() { llmFirstToken = SystemClock.elapsedRealtime() }

    @Synchronized
    fun onTurnComplete() {
        val total = if (turnStart > 0) SystemClock.elapsedRealtime() - turnStart else 0
        val turn = Turn(
            wakeMs = (wakeEnd - wakeStart).coerceAtLeast(0),
            sttMs = (sttEnd - sttStart).coerceAtLeast(0),
            llmFirstTokenMs = (llmFirstToken - llmStart).coerceAtLeast(0),
            totalMs = total,
            timestamp = System.currentTimeMillis()
        )
        turns.addFirst(turn)
        while (turns.size > MAX_TURNS) turns.removeLast()
        // reset
        wakeStart = 0; wakeEnd = 0; sttStart = 0; sttEnd = 0
        llmStart = 0; llmFirstToken = 0; turnStart = 0
    }

    @Synchronized
    fun snapshot(): List<Turn> = turns.toList()

    @Synchronized
    fun summary(): String {
        val snap = turns.toList()
        if (snap.isEmpty()) return "No data yet."
        val avgStt = snap.map { it.sttMs }.average().toInt()
        val avgLlm = snap.filter { it.llmFirstTokenMs > 0 }
            .map { it.llmFirstTokenMs }.let { if (it.isEmpty()) 0 else it.average().toInt() }
        val avgTotal = snap.map { it.totalMs }.average().toInt()
        return "STT ${avgStt}ms · LLM first token ${avgLlm}ms · total ${avgTotal}ms (${snap.size} turns)"
    }
}
