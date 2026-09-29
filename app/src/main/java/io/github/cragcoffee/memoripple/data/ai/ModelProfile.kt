package io.github.cragcoffee.memoripple.data.ai

import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelCapability
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelLocation

/**
 * What differs per model and must not leak upward: how thinking is switched off, and notes for
 * the operator. Templates, stop tokens and tokenizer behaviour come from the GGUF itself through
 * the engine's Jinja renderer, so they need no field here — the flag is the one knob Phase 0 used.
 */
data class ModelProfile(
    val modelId: String,
    /** Always false for structured intents (Phase 0 baseline); the renderer passes it to the chat template. */
    val enableThinking: Boolean,
    val notes: String = "",
)

data class ProfiledModel(val descriptor: ModelDescriptor, val profile: ModelProfile)

/**
 * The two Phase 1 candidates (docs/LLM_PHASE0.md §13), keyed like the Phase 5 catalog; the files are
 * never bundled — the user installs them under `models/<id>/model.gguf` (docs/AI_MODEL_MANAGEMENT.md),
 * or a developer path stands in during a smoke. No default is chosen here.
 */
object ModelProfiles {
    val qwen3_4bInstruct2507 = ProfiledModel(
        descriptor = ModelDescriptor(
            id = "qwen3-4b-instruct-2507",
            displayName = "Qwen3-4B-Instruct-2507",
            location = ModelLocation.Installed("qwen3-4b-instruct-2507"),
            architecture = "qwen3",
            contextSize = 4096,
            quantization = "Q4_K_M",
            capabilities = setOf(ModelCapability.STRUCTURED_OUTPUT, ModelCapability.JAPANESE),
        ),
        profile = ModelProfile("qwen3-4b-instruct-2507", enableThinking = false, notes = "non-thinking checkpoint; ChatML template; Phase 0: balanced / completeness"),
    )

    val ministral3_3bInstruct2512 = ProfiledModel(
        descriptor = ModelDescriptor(
            id = "ministral-3-3b-instruct-2512",
            displayName = "Ministral 3 3B Instruct 2512",
            location = ModelLocation.Installed("ministral-3-3b-instruct-2512"),
            architecture = "mistral3",
            contextSize = 4096,
            quantization = "Q4_K_M",
            capabilities = setOf(ModelCapability.STRUCTURED_OUTPUT, ModelCapability.JAPANESE),
        ),
        profile = ModelProfile("ministral-3-3b-instruct-2512", enableThinking = false, notes = "Instruct (no reasoning channel); [SYSTEM_PROMPT]/[INST] template; Phase 0: safety-oriented"),
    )

    val all: List<ProfiledModel> = listOf(qwen3_4bInstruct2507, ministral3_3bInstruct2512)

    fun profileFor(descriptor: ModelDescriptor): ModelProfile =
        all.firstOrNull { it.descriptor.id == descriptor.id }?.profile ?: ModelProfile(descriptor.id, enableThinking = false)
}
