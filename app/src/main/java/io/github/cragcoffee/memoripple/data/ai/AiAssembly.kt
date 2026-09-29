package io.github.cragcoffee.memoripple.data.ai

import android.content.Context
import io.github.cragcoffee.memoripple.data.ai.llamacpp.CpuFeatures
import io.github.cragcoffee.memoripple.data.ai.llamacpp.LlamaCppEngine
import io.github.cragcoffee.memoripple.domain.ai.resource.DefaultAiResourceController
import io.github.cragcoffee.memoripple.domain.ai.resource.IdleUnloadPolicy
import io.github.cragcoffee.memoripple.domain.ai.AiOrchestrator
import io.github.cragcoffee.memoripple.domain.ai.CommandExecutor
import io.github.cragcoffee.memoripple.domain.ai.LocalAiOrchestrator
import io.github.cragcoffee.memoripple.domain.ai.ModelAvailability
import io.github.cragcoffee.memoripple.domain.ai.ModelSelection
import io.github.cragcoffee.memoripple.domain.ai.ModelUnavailableReason
import io.github.cragcoffee.memoripple.data.ai.models.ModelCatalog
import io.github.cragcoffee.memoripple.domain.ai.Resolver
import io.github.cragcoffee.memoripple.domain.ai.TemplateLookup
import io.github.cragcoffee.memoripple.domain.ai.models.ModelStore
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRequest
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.LoadResult
import io.github.cragcoffee.memoripple.domain.ai.runtime.LocalModelRuntime
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelFileResolver
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelLocation
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure
import io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState
import io.github.cragcoffee.memoripple.domain.diary.TimeProvider
import io.github.cragcoffee.memoripple.domain.documents.DocumentAccess
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.Properties

/**
 * Phase 5 model selection (docs/AI_MODEL_MANAGEMENT.md): the runtime loads the model the user
 * **selected** (the one id in the preferences) and only if it is **installed** under
 * `models/<id>/model.gguf` — an installed file that was not selected is never picked (no
 * unilateral default). A **debug** build may still name a model and an absolute path in
 * `models/developer.properties` (`modelId=…`, `path=…`), the developer configuration the device
 * smokes use; it is authoritative when present (an unknown id is no model); a release build never
 * reads it, so a developer path never reaches a shipped flow.
 */
class ProductModelSelection(
    private val noBackupDir: File,
    private val store: ModelStore,
    private val isDebugBuild: Boolean,
    /** The verified download's length per catalog id (the committed file has exactly it); null = unknown, presence only. */
    private val expectedBytes: (String) -> Long? = { ModelCatalog.find(it)?.approximateDownloadBytes },
    private val selectedId: suspend () -> String?,
) : ModelSelection {
    override suspend fun selected(): ModelDescriptor? = (availability() as? ModelAvailability.Available)?.model

    /**
     * Phase 7 (docs/CHAT_V1_RELEASE_READINESS.md): the reason, decided from the preference and the
     * file alone — nothing selected; the selected file gone (MISSING, the selection is kept for the
     * user); the selected file not the verified length (CORRUPT — a full hash on every ask would
     * read gigabytes, so the length is the pre-load check and the engine's own header validation the
     * rest); otherwise the descriptor. Never another installed model in place of the chosen one.
     */
    override suspend fun availability(): ModelAvailability {
        if (isDebugBuild && developerFile().isFile) {
            val d = developer() ?: return ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED)
            val path = (d.location as? ModelLocation.DeveloperPath)?.absolutePath
            if (path != null && !File(path).isFile) return ModelAvailability.Unavailable(ModelUnavailableReason.MODEL_FILE_MISSING)
            return ModelAvailability.Available(d)
        }
        val id = selectedId() ?: return ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED)
        val descriptor = ModelProfiles.all.firstOrNull { it.descriptor.id == id }?.descriptor ?: return ModelAvailability.Unavailable(ModelUnavailableReason.NO_MODEL_CONFIGURED)
        val bytes = store.installedBytes(id) ?: return ModelAvailability.Unavailable(ModelUnavailableReason.MODEL_FILE_MISSING)
        val expected = expectedBytes(id)
        if (expected != null && bytes != expected) return ModelAvailability.Unavailable(ModelUnavailableReason.MODEL_FILE_CORRUPT)
        return ModelAvailability.Available(descriptor)
    }

    private fun developerFile(): File = File(File(noBackupDir, ModelFileResolver.MODELS_DIR), DEVELOPER_FILE)

    private fun developer(): ModelDescriptor? {
        val file = developerFile()
        if (!file.isFile) return null
        val props = Properties().also { p -> file.reader().use { p.load(it) } }
        val id = props.getProperty("modelId")?.trim().orEmpty()
        val descriptor = ModelProfiles.all.firstOrNull { it.descriptor.id == id }?.descriptor ?: return null
        val path = props.getProperty("path")?.trim()
        return if (path.isNullOrEmpty()) descriptor else descriptor.copy(location = ModelLocation.DeveloperPath(path))
    }

    companion object {
        const val DEVELOPER_FILE = "developer.properties"
    }
}

/**
 * The product runtime, built **after** the CPU feature check: on a CPU without the dot-product
 * instructions the native build assumes, the engine is never constructed (constructing it loads
 * the library) and a refusing runtime answers UNSUPPORTED_DEVICE instead. Never "try and fall
 * back after a SIGILL" (CLAUDE §4).
 */
fun productLocalModelRuntime(
    noBackupDir: File,
    hasDotProduct: Boolean = CpuFeatures.hasDotProduct(),
    engine: () -> NativeInferenceEngine = { LlamaCppEngine() },
): LocalModelRuntime =
    if (!hasDotProduct) UnsupportedDeviceRuntime
    else LocalModelRuntimeImpl(engine = engine(), files = NoBackupModelFileLocator(noBackupDir), profileFor = ModelProfiles::profileFor)

/** The runtime of a device the native build cannot run on: never loads, never generates. */
object UnsupportedDeviceRuntime : LocalModelRuntime {
    override fun state(): RuntimeState = RuntimeState.UNLOADED
    override suspend fun load(model: ModelDescriptor): LoadResult = LoadResult.Failed(RuntimeFailure.UNSUPPORTED_DEVICE)
    override suspend fun generate(request: GenerationRequest): GenerationResult = GenerationResult.Failed(RuntimeFailure.INVALID_STATE)
    override suspend fun unload() = Unit
}

/**
 * The product orchestrator over the product resource controller (Phase 2,
 * docs/AI_RESOURCE_CONTROLLER.md): the holder's one runtime (created on the first genuinely
 * generative ask, never by a screen), the installed model, the platform's thermal status and
 * Power Save Mode as policy inputs, the bundled prompt. The controller owns lazy load, keep
 * warm, the idle clock, the memory policy and the budget.
 */
fun productAiOrchestrator(
    context: Context,
    holder: LocalModelRuntimeHolder,
    store: ModelStore,
    selectedModelId: suspend () -> String?,
    documents: DocumentAccess,
    templates: TemplateLookup,
    time: TimeProvider,
    scope: CoroutineScope,
    isDebugBuild: Boolean,
    idle: IdleUnloadPolicy = IdleUnloadPolicy(),
): AiOrchestrator {
    val thermal = androidThermalGate(context)
    return LocalAiOrchestrator(
        runtime = { holder.runtime },
        selection = ProductModelSelection(context.noBackupFilesDir, store, isDebugBuild, selectedId = selectedModelId),
        thermal = thermal,
        assets = AssetPromptAssets(context),
        resolver = Resolver(documents, templates, time),
        executor = CommandExecutor(documents),
        resources = DefaultAiResourceController(
            runtime = { holder.runtime },
            scope = scope,
            idle = idle,
            thermal = thermal,
            powerSaver = androidPowerSaveReader(context),
            log = { android.util.Log.i("AiChat", it) },
        ),
        deviceSupported = { CpuFeatures.hasDotProduct() },
        documents = documents,
    )
}
