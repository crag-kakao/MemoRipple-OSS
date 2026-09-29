package io.github.cragcoffee.memoripple.data.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.LocalModelRuntime
import io.github.cragcoffee.memoripple.domain.ai.runtime.MemoryPressure

/**
 * Owns the one runtime of the process, created on first use, and unloads it when the system
 * signals low memory. Nothing else keeps a model resident.
 */
class LocalModelRuntimeHolder(private val create: () -> LocalModelRuntime) {
    private var instance: LocalModelRuntime? = null

    val runtime: LocalModelRuntime
        get() = instance ?: create().also { instance = it }

    val isCreated: Boolean get() = instance != null

    /** LOW or CRITICAL pressure unloads a loaded model; MODERATE leaves it (the next idle timer will). */
    suspend fun onMemoryPressure(pressure: MemoryPressure) {
        val rt = instance ?: return
        if (pressure == MemoryPressure.LOW || pressure == MemoryPressure.CRITICAL) rt.unload()
    }

    companion object {
        /** Android's `ComponentCallbacks2` trim levels, mapped without importing the platform here. */
        fun pressureOf(trimLevel: Int): MemoryPressure? = when {
            trimLevel >= 80 -> MemoryPressure.CRITICAL   // TRIM_MEMORY_COMPLETE
            trimLevel >= 15 -> MemoryPressure.LOW        // TRIM_MEMORY_RUNNING_CRITICAL (15), MODERATE (40), BACKGROUND (60)
            trimLevel >= 5 -> MemoryPressure.MODERATE    // TRIM_MEMORY_RUNNING_MODERATE (5), RUNNING_LOW (10)
            else -> null
        }
    }
}
