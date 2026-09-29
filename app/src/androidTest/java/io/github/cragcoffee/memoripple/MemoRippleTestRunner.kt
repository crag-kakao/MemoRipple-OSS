package io.github.cragcoffee.memoripple

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import io.github.cragcoffee.memoripple.speech.SpeechController
import kotlinx.coroutines.flow.first
import io.github.cragcoffee.memoripple.speech.SpeechGatewayInitialization
import io.github.cragcoffee.memoripple.speech.SpeechProgressListener
import io.github.cragcoffee.memoripple.speech.SpeechQueueMode
import io.github.cragcoffee.memoripple.speech.TextToSpeechGateway

class MemoRippleTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(
        classLoader,
        TestMemoRippleApplication::class.java.name,
        context,
    )
}

class TestMemoRippleApplication : MemoRippleApplication() {
    override fun onCreate() {
        super.onCreate()
        // The one-time first-use guides stay out of instrumentation's way: without this
        // every screen test would land on the welcome demo instead of the app. The guides'
        // own tests flip these back to false and restore them afterwards, the way every
        // device-local preference must be restored.
        kotlinx.coroutines.runBlocking {
            settingsRepository.setHasSeenWelcomeDemo(true)
            settingsRepository.setHasSeenOverlaySetup(true)
            settingsRepository.setHasSeenOutlineGuide(true)
        }
    }

    override fun createSpeechController(): SpeechController =
        SpeechController(DeterministicTextToSpeechGateway())

    /**
     * The AI path under test: the product orchestrator over a scripted runtime, a fixed model
     * and a settable thermal status — the parser, validator, resolver, policy and boundary are
     * real. When the operator injects `llmModelPath` (the device smoke) the product orchestrator
     * and the real runtime are used instead.
     */
    /** The real manager over the loopback fixture server and the guards under test control. */
    override fun createModelManager(): io.github.cragcoffee.memoripple.domain.ai.models.ModelManager {
        val args = try { androidx.test.platform.app.InstrumentationRegistry.getArguments() } catch (_: Throwable) { android.os.Bundle() }
        if (args.getString("realDownload") == "true") return super.createModelManager()
        return io.github.cragcoffee.memoripple.domain.ai.models.ModelManager(
            catalog = TestModelServer.catalog,
            store = modelStore,
            downloader = io.github.cragcoffee.memoripple.data.ai.models.HttpModelDownloader(connectTimeoutMillis = 5_000, readTimeoutMillis = 20_000, allowLoopbackHttp = true),
            guards = TestGuards,
            selection = io.github.cragcoffee.memoripple.data.ai.models.DataStoreSelectedModelStore(settingsRepository),
            unloadRuntime = { aiOrchestrator.release() },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
            safetyMarginBytes = 0,
        )
    }

    override fun createAiOrchestrator(): io.github.cragcoffee.memoripple.domain.ai.AiOrchestrator {
        val args = try { androidx.test.platform.app.InstrumentationRegistry.getArguments() } catch (_: Throwable) { android.os.Bundle() }
        if (!args.getString("llmModelPath").isNullOrBlank() || args.getString("realDownload") == "true") return super.createAiOrchestrator()
        val real = io.github.cragcoffee.memoripple.domain.ai.LocalAiOrchestrator(
            runtime = { TestAiRuntime.current ?: TestAiRuntime().also { TestAiRuntime.current = it } },
            selection = TestAiSelection,
            thermal = io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate { TestThermal.status },
            assets = io.github.cragcoffee.memoripple.data.ai.AssetPromptAssets(this),
            resolver = io.github.cragcoffee.memoripple.domain.ai.Resolver(documentAccess, io.github.cragcoffee.memoripple.data.TemplateLookupAdapter(templateRepository), timeProvider),
            executor = io.github.cragcoffee.memoripple.domain.ai.CommandExecutor(documentAccess),
            // Phase 2 (docs/AI_RESOURCE_CONTROLLER.md): the same lease/keep-warm/idle controller as the
            // product, over the scripted runtime; its idle clock is TestIdleClock — a delay never
            // elapses by itself, a journey advances it, so no test waits on a real timer.
            resources = io.github.cragcoffee.memoripple.domain.ai.resource.DefaultAiResourceController(
                runtime = { TestAiRuntime.current ?: TestAiRuntime().also { TestAiRuntime.current = it } },
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
                idle = io.github.cragcoffee.memoripple.domain.ai.resource.IdleUnloadPolicy(),
                thermal = io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate { TestThermal.status },
                powerSaver = { TestGuards.powerSaver },
                delayFn = TestIdleClock::delayFn,
            ),
            deviceSupported = { TestGuards.supported },
            documents = documentAccess,
        )
        // the gate is test plumbing only: the AI-pipeline classes script model answers for plain sentences the
        // Fast Path would otherwise take, so they switch it off to keep testing the route they were written for
        return object : io.github.cragcoffee.memoripple.domain.ai.AiOrchestrator by real {
            override suspend fun interactFast(userText: String, context: io.github.cragcoffee.memoripple.domain.ai.AiResultContext, referents: io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationReferents, destination: io.github.cragcoffee.memoripple.domain.documents.CreateDestination?): io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult? =
                if (TestGuards.fastPathEnabled) real.interactFast(userText, context, referents, destination) else null
            // the same gate covers the DecisionEngine door (Phase 3): the model-pipeline classes' scripted
            // sentences must reach the generator, not the deterministic pre-model routing
            override suspend fun interactDecide(userText: String, context: io.github.cragcoffee.memoripple.domain.ai.AiResultContext, referents: io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationReferents, destination: io.github.cragcoffee.memoripple.domain.documents.CreateDestination?): io.github.cragcoffee.memoripple.domain.ai.decision.DecisionOutcome? =
                if (TestGuards.fastPathEnabled) real.interactDecide(userText, context, referents, destination) else null
        }
    }
}

/** The scripted model of the チャット AI tests: canned answers, a forced load outcome, gates for the loading and generating states, counters. */
class TestAiRuntime : io.github.cragcoffee.memoripple.domain.ai.runtime.LocalModelRuntime {
    companion object {
        val answers = java.util.concurrent.ConcurrentLinkedDeque<String>()
        @Volatile var loadFailure: io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure? = null
        @Volatile var loadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        @Volatile var generateGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        @Volatile var loads = 0
        @Volatile var unloads = 0
        @Volatile var requests = 0
        @Volatile var current: TestAiRuntime? = null
        /** Phase 2 (resource controller): what the last generation was budgeted and which model the last load took. */
        @Volatile var lastMaxTokens = 0
        @Volatile var lastLoadedModelId = ""
        /** Phase 4B: the cache key of every generation, in order (null = no reuse asked) — the wiring the journeys prove. */
        val cacheKeys = java.util.concurrent.CopyOnWriteArrayList<String?>()
        @Volatile private var cancelRequested = false
        private val messages = java.util.concurrent.CopyOnWriteArrayList<String>()

        fun lastUserMessage(): String = messages.lastOrNull().orEmpty()

        fun reset() {
            answers.clear(); loadFailure = null; loadGate = null; generateGate = null
            loads = 0; unloads = 0; requests = 0; messages.clear()
            lastMaxTokens = 0; lastLoadedModelId = ""; cancelRequested = false; cacheKeys.clear()
        }

        internal fun requestCancel() { cancelRequested = true; generateGate?.complete(Unit) }
        internal fun takeCancel(): Boolean = cancelRequested.also { cancelRequested = false }
    }

    @Volatile private var state = io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.UNLOADED
    @Volatile private var held: String? = null
    /** The key the last generation ran under, forgotten on unload — mirrors the product runtime's rule. */
    fun cacheKey(): String? = held

    override fun state() = state

    override suspend fun load(model: io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor): io.github.cragcoffee.memoripple.domain.ai.runtime.LoadResult {
        loads++
        lastLoadedModelId = model.id
        state = io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.LOADING
        loadGate?.await()
        loadFailure?.let { state = io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.UNLOADED; return io.github.cragcoffee.memoripple.domain.ai.runtime.LoadResult.Failed(it) }
        state = io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.READY
        return io.github.cragcoffee.memoripple.domain.ai.runtime.LoadResult.Loaded(model, 1)
    }

    override suspend fun generate(request: io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRequest): io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationResult {
        requests++
        messages += request.userMessage
        lastMaxTokens = request.maxTokens
        cacheKeys += request.cacheKey; held = request.cacheKey
        if (state != io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.READY) return io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationResult.Failed(io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure.INVALID_STATE)
        state = io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.GENERATING
        try {
            generateGate?.await()
            // as the real engine: an unload during the generation stops it and the result is CANCELLED, never text
            if (takeCancel()) return io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationResult.Generated("", 0, 0, 0, 0, io.github.cragcoffee.memoripple.domain.ai.runtime.StopReason.CANCELLED)
            val text = answers.pollFirst() ?: return io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationResult.Failed(io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeFailure.ENGINE_ERROR)
            return io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationResult.Generated(text, 300, 40, 5, 10, io.github.cragcoffee.memoripple.domain.ai.runtime.StopReason.END)
        } finally {
            if (state == io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.GENERATING) state = io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.READY
        }
    }

    override suspend fun unload() {
        if (state == io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.GENERATING) requestCancel()
        held = null
        if (state != io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.UNLOADED) unloads++
        state = io.github.cragcoffee.memoripple.domain.ai.runtime.RuntimeState.UNLOADED
    }
}

/** The model the tests pretend is installed; null = no model configured. */
object TestAiSelection : io.github.cragcoffee.memoripple.domain.ai.ModelSelection {
    val default = io.github.cragcoffee.memoripple.data.ai.ModelProfiles.qwen3_4bInstruct2507.descriptor
    @Volatile var descriptor: io.github.cragcoffee.memoripple.domain.ai.runtime.ModelDescriptor? = default
    /** Phase 7: when set, the real product selection (selected id + installed file under the test store) answers instead of [descriptor]. */
    @Volatile var product: io.github.cragcoffee.memoripple.domain.ai.ModelSelection? = null
    override suspend fun selected() = product?.selected() ?: descriptor
    override suspend fun availability(): io.github.cragcoffee.memoripple.domain.ai.ModelAvailability =
        product?.availability() ?: super.availability()
    fun reset() { descriptor = default; product = null }

    /** The product selection over the application's store, the fixture catalog's sizes and the real preference — a release-shaped path (no developer file). */
    fun useProduct(application: MemoRippleApplication) {
        product = io.github.cragcoffee.memoripple.data.ai.ProductModelSelection(
            application.noBackupFilesDir, application.modelStore, isDebugBuild = false,
            expectedBytes = { id -> TestModelServer.catalog.firstOrNull { it.modelId == id }?.approximateDownloadBytes },
        ) { application.settingsRepository.selectedAiModelId.first() }
    }
}

/** The thermal status the tests set (0 NONE … 3 SEVERE). */
object TestThermal {
    @Volatile var status = 0
}

/**
 * The resource controller's idle clock under test control (Phase 2, docs/AI_RESOURCE_CONTROLLER.md):
 * a scheduled idle delay never elapses by itself — it waits here until a journey [advance]s it, so
 * no test sleeps through a real timeout and no unrelated test sees a surprise unload. [reset]
 * cancels whatever is pending (the idle job dies without unloading).
 */
object TestIdleClock {
    private val waiters = java.util.concurrent.ConcurrentLinkedQueue<kotlinx.coroutines.CompletableDeferred<Unit>>()

    suspend fun delayFn(@Suppress("UNUSED_PARAMETER") millis: Long) {
        val waiter = kotlinx.coroutines.CompletableDeferred<Unit>()
        waiters += waiter
        try { waiter.await() } finally { waiters.remove(waiter) }
    }

    /** Lets the oldest pending idle delay elapse; false when none is pending. */
    fun advance(): Boolean = waiters.peek()?.complete(Unit) ?: false

    fun pending(): Int = waiters.size

    fun reset() { while (true) { (waiters.poll() ?: break).cancel() } }
}

/** A ready TTS boundary that remains active until SpeechController explicitly stops it. */
class DeterministicTextToSpeechGateway : TextToSpeechGateway {
    companion object {
        @Volatile
        var emitRanges = true
    }

    override val maxInputLength: Int = 4_000
    private var listener: SpeechProgressListener? = null

    override fun setProgressListener(listener: SpeechProgressListener) {
        this.listener = listener
    }

    override fun initialize(onComplete: (SpeechGatewayInitialization) -> Unit) {
        onComplete(SpeechGatewayInitialization.READY)
    }

    override fun speak(
        text: String,
        queueMode: SpeechQueueMode,
        utteranceId: String,
    ): Boolean {
        listener?.onStart(utteranceId)
        // A deterministic stand-in for engines that report word positions: every character,
        // in order, immediately. Tests exercising the no-range fallback turn this off.
        if (emitRanges) {
            text.indices.forEach { listener?.onRangeStart(utteranceId, it, it + 1) }
        }
        return true
    }

    override fun stop() = Unit

    override fun shutdown() = Unit
}

/**
 * The model-management fixture (docs/AI_MODEL_MANAGEMENT.md §tests): a loopback HTTP server that
 * serves two tiny "models" with Range support, a throttle, and a wrong-body switch, so the real
 * manager, store, downloader and DataStore run end to end without a multi-GB download. Started
 * once per process; its catalog entries point at it.
 */
object TestModelServer {
    const val A_ID = "qwen3-4b-instruct-2507"
    const val B_ID = "ministral-3-3b-instruct-2512"
    val bodyA: ByteArray = ByteArray(1_200_000) { (it % 251).toByte() }
    val bodyB: ByteArray = ByteArray(800_000) { (it % 241).toByte() }
    private val wrong: ByteArray = ByteArray(1_200_000) { (it % 239).toByte() }
    @Volatile var throttleMillisPerChunk: Long = 0
    @Volatile var serveWrongBodyFor: String? = null
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val server: java.net.ServerSocket by lazy {
        java.net.ServerSocket(0).also { s ->
            kotlin.concurrent.thread(isDaemon = true, name = "TestModelServer") {
                while (!s.isClosed) {
                    val socket = try { s.accept() } catch (_: Exception) { return@thread }
                    kotlin.concurrent.thread(isDaemon = true) { serve(socket) }
                }
            }
        }
    }
    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    fun reset() { throttleMillisPerChunk = 0; serveWrongBodyFor = null; requests.clear() }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    val catalog: List<io.github.cragcoffee.memoripple.domain.ai.models.CatalogEntry> by lazy {
        io.github.cragcoffee.memoripple.data.ai.models.ModelCatalog.all.map { real ->
            val body = if (real.modelId == A_ID) bodyA else bodyB
            real.copy(
                approximateDownloadBytes = body.size.toLong(),
                approximateLoadedMemoryBytes = body.size * 2L,
                source = real.source.copy(url = "$baseUrl/${real.modelId}.gguf"),
                expectedSha256 = sha256(body),
            )
        }
    }

    private fun serve(socket: java.net.Socket) = try { serveOrThrow(socket) } catch (_: java.io.IOException) { /* the client hung up (a cancel): normal */ }

    private fun serveOrThrow(socket: java.net.Socket) = socket.use { s ->
        val input = s.getInputStream().bufferedReader()
        val head = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
        requests += head.joinToString(" | ")
        val path = head.firstOrNull()?.split(" ")?.getOrNull(1).orEmpty()
        val id = path.removePrefix("/").removeSuffix(".gguf")
        val body = when {
            serveWrongBodyFor == id -> wrong
            id == A_ID -> bodyA
            id == B_ID -> bodyB
            else -> null
        }
        val out = s.getOutputStream()
        if (body == null) { out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush(); return@use }
        val range = head.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter("bytes=")?.substringBefore("-")?.trim()?.toLongOrNull()
        val offset = (range ?: 0L).toInt()
        val header = if (range != null) {
            "HTTP/1.0 206 Partial Content\r\nContent-Length: ${body.size - offset}\r\nContent-Range: bytes $offset-${body.size - 1}/${body.size}\r\nAccept-Ranges: bytes\r\n\r\n"
        } else {
            "HTTP/1.0 200 OK\r\nContent-Length: ${body.size}\r\nAccept-Ranges: bytes\r\n\r\n"
        }
        out.write(header.toByteArray())
        var at = offset
        while (at < body.size) {
            val n = minOf(32 * 1024, body.size - at)
            out.write(body, at, n); out.flush(); at += n
            if (throttleMillisPerChunk > 0) Thread.sleep(throttleMillisPerChunk)
        }
    }
}

/** The device guards under test control: free space, metered network, battery, CPU support. */
object TestGuards : io.github.cragcoffee.memoripple.domain.ai.models.DeviceGuards {
    @Volatile var usableBytes: Long = 100L shl 30
    @Volatile var metered = false
    @Volatile var batteryPercent = 80
    @Volatile var charging = false
    @Volatile var supported = true
    @Volatile var online = true
    /** The Fast Path (docs/CHAT_FAST_PATH.md): on by default, as in the product; a class that exists to exercise the *model* pipeline turns it off so its scripted sentences still reach the generator. */
    @Volatile var fastPathEnabled = true
    /** Power Save Mode as the resource controller reads it (Phase 2, docs/AI_RESOURCE_CONTROLLER.md); never a receiver. */
    @Volatile var powerSaver = false
    fun reset() { usableBytes = 100L shl 30; metered = false; batteryPercent = 80; charging = false; supported = true; online = true; fastPathEnabled = true; powerSaver = false }
    override fun usableBytes() = usableBytes
    override fun isOnline() = online
    override fun isMetered() = metered
    override fun batteryPercent() = batteryPercent
    override fun isCharging() = charging
    override fun supportsLocalInference() = supported
}
