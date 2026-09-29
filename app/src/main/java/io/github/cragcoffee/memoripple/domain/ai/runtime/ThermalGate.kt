package io.github.cragcoffee.memoripple.domain.ai.runtime

/** Whether a generation may start now. Based on the platform's thermal status, read by the adapter. */
enum class ThermalDecision(val allowsGeneration: Boolean) {
    ALLOWED(true),
    /** Light or moderate: allowed, but the caller may shorten or defer. */
    THROTTLED(true),
    /** Severe or worse: no new generation (Phase 0's stop rule). */
    BLOCKED(false),
}

interface ThermalGate {
    fun check(): ThermalDecision
}

/**
 * Maps a thermal status number to a decision: 0 → ALLOWED, 1–2 → THROTTLED, ≥ 3 → BLOCKED. The
 * numbers are the platform's thermal statuses (NONE, LIGHT, MODERATE, SEVERE, …); the source is
 * injected so the domain never imports the platform.
 */
class StatusThermalGate(private val status: () -> Int) : ThermalGate {
    override fun check(): ThermalDecision {
        val s = status()
        return when {
            s <= 0 -> ThermalDecision.ALLOWED
            s < SEVERE -> ThermalDecision.THROTTLED
            else -> ThermalDecision.BLOCKED
        }
    }

    companion object {
        const val SEVERE = 3
    }
}

/** Memory pressure as the runtime holder sees it; the application maps the platform's trim levels to it. */
enum class MemoryPressure { MODERATE, LOW, CRITICAL }
