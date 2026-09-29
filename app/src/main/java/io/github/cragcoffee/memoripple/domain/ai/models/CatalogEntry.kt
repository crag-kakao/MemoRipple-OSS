package io.github.cragcoffee.memoripple.domain.ai.models

/**
 * A catalog entry (docs/AI_MODEL_MANAGEMENT.md): static, bundled metadata for a model the user may
 * download, verify, select and delete. The file itself is never in the APK — only these facts
 * are. The entries live in the data layer; nothing here ranks them and nothing here is a default.
 */
data class CatalogEntry(
    val modelId: String,
    val displayName: String,
    /** One neutral sentence: what the model was measured to be good at. */
    val description: String,
    val category: ModelCategory,
    val approximateDownloadBytes: Long,
    /** Process memory with the model loaded, as Phase 0 measured on the S20. */
    val approximateLoadedMemoryBytes: Long,
    val strengths: List<String>,
    val tradeoffs: List<String>,
    val source: ModelSource,
    val expectedSha256: String,
    val quantization: String,
    val minimumCpu: CpuRequirement,
    val contextSize: Int,
    val license: ModelLicense,
    /** The runtime profile (data layer) that knows how to run this file. */
    val runtimeProfileId: String,
)

/** Phase 0's positions, as labels the user sees. Neither is a rank. */
enum class ModelCategory(val label: String) {
    BALANCED("Balanced"),
    SAFETY_ORIENTED("Safety-oriented"),
}

/** What the shipped native runtime needs of the CPU (docs/LOCAL_LLM_RUNTIME.md, open ARM decision). */
enum class CpuRequirement { ARM_DOTPROD }

data class ModelSource(
    val repository: String,
    val revision: String,
    val fileName: String,
    /** The exact, pinned download URL: repository + revision + file. */
    val url: String,
    val publisher: String,
    val publisherNote: String,
)

data class ModelLicense(val summary: String, val url: String)
