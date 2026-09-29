package io.github.cragcoffee.memoripple.speech

import io.github.cragcoffee.memoripple.domain.speech.SpeechChunker
import io.github.cragcoffee.memoripple.domain.speech.SpeechSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpeechStatus { IDLE, INITIALIZING, SPEAKING, ERROR, UNAVAILABLE }

/**
 * What a caller may watch inside its own segment session, beyond the shared [SpeechState]:
 * each segment as it starts, the engine's in-segment position when the device reports one,
 * and the session reaching its natural end. Callbacks arrive off the controller's lock, on
 * the engine's thread.
 */
interface SpeechSessionObserver {
    fun onSegmentStart(index: Int) {}
    fun onSegmentRange(index: Int, startInclusive: Int) {}
    fun onFinished() {}
}

data class SpeechState(
    val status: SpeechStatus = SpeechStatus.IDLE,
    val message: String? = null,
    /**
     * Which of the session's segments is being spoken right now, counted from 0, or null when
     * nothing is. With [SpeechController.speakSegments] the caller chose the segments, so this
     * is its own index coming back — the follow-along cursor of an assisted reader.
     */
    val activeSegmentIndex: Int? = null,
)

class SpeechController(
    private val gateway: TextToSpeechGateway,
    private val chunker: SpeechChunker = SpeechChunker(),
    /** The user dictionary's pass over text about to be spoken; identity when there is none. */
    private val transformText: (String) -> String = { it },
) {
    private enum class EngineState { UNINITIALIZED, INITIALIZING, READY, UNAVAILABLE, ERROR, SHUTDOWN }

    private data class Session(
        val id: Long,
        val segments: List<SpeechSegment>,
        val utteranceIds: Set<String>,
        val finalUtteranceId: String,
        val observer: SpeechSessionObserver? = null,
    ) {
        val segmentIndexByUtteranceId: Map<String, Int> =
            segments.associate { "speechSession$id-segment${it.index}" to it.index }
    }

    private val lock = Any()
    private val mutableState = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = mutableState.asStateFlow()
    private var engineState = EngineState.UNINITIALIZED
    private var nextSessionId = 0L
    private var pendingSession: Session? = null
    private var activeSession: Session? = null

    init {
        gateway.setProgressListener(
            object : SpeechProgressListener {
                override fun onStart(utteranceId: String) = handleStart(utteranceId)
                override fun onDone(utteranceId: String) = handleDone(utteranceId)
                override fun onError(utteranceId: String) = handleError(utteranceId)
                override fun onRangeStart(
                    utteranceId: String,
                    startInclusive: Int,
                    endExclusive: Int,
                ) = handleRangeStart(utteranceId, startInclusive)
            },
        )
    }

    fun speak(text: String): Boolean = synchronized(lock) {
        beginSession(chunker.chunk(transformText(text), gateway.maxInputLength))
    }

    /**
     * Speaks the given texts as one session, one utterance per element, in order. The caller's
     * indices are the session's segment indices, so [SpeechState.activeSegmentIndex] speaks the
     * caller's own language — a reader that split its page into sentences hears which sentence
     * is up. An element longer than the engine accepts is cut at its limit rather than dropped.
     */
    fun speakSegments(
        texts: List<String>,
        observer: SpeechSessionObserver? = null,
    ): Boolean = synchronized(lock) {
        beginSession(
            texts.mapIndexed { index, text ->
                SpeechSegment(index, transformText(text).take(gateway.maxInputLength))
            },
            observer,
        )
    }

    private fun beginSession(
        segments: List<SpeechSegment>,
        observer: SpeechSessionObserver? = null,
    ): Boolean {
        if (segments.all { it.text.isBlank() } || engineState == EngineState.SHUTDOWN) return false

        invalidateCurrentSession(stopGateway = activeSession != null || pendingSession != null)
        val sessionId = ++nextSessionId
        val utteranceIds = segments.map { "speechSession$sessionId-segment${it.index}" }.toSet()
        val session = Session(
            id = sessionId,
            segments = segments,
            utteranceIds = utteranceIds,
            finalUtteranceId = "speechSession$sessionId-segment${segments.lastIndex}",
            observer = observer,
        )
        pendingSession = session
        when (engineState) {
            EngineState.UNINITIALIZED, EngineState.ERROR -> initializeGateway()
            EngineState.INITIALIZING -> mutableState.value = SpeechState(SpeechStatus.INITIALIZING)
            EngineState.READY -> enqueuePendingSession()
            EngineState.UNAVAILABLE -> mutableState.value = SpeechState(
                SpeechStatus.UNAVAILABLE,
                OFFLINE_VOICE_UNAVAILABLE_MESSAGE,
            ).also { pendingSession = null }
            EngineState.SHUTDOWN -> Unit
        }
        return true
    }

    fun stop() = synchronized(lock) {
        invalidateCurrentSession(stopGateway = true)
        mutableState.value = SpeechState()
    }

    fun clearMessage() = synchronized(lock) {
        val current = mutableState.value
        mutableState.value = when (current.status) {
            SpeechStatus.ERROR -> SpeechState()
            else -> current.copy(message = null)
        }
    }

    /**
     * Hands the engine back while nobody is listening. Called when the app leaves the
     * foreground with no speech in flight: the bound TTS engine otherwise keeps its process
     * alive — memory and battery — for as long as this app lives. Unlike [shutdown] this is
     * not terminal: the next [speak] initialises a fresh engine. Speech that is still playing
     * (reading aloud in the background) is left alone.
     */
    fun releaseWhenIdle(): Unit = synchronized(lock) {
        if (engineState == EngineState.SHUTDOWN || engineState == EngineState.UNINITIALIZED) return
        if (activeSession != null || pendingSession != null) return
        gateway.shutdown()
        engineState = EngineState.UNINITIALIZED
        mutableState.value = SpeechState()
    }

    fun shutdown(): Unit = synchronized(lock) {
        if (engineState == EngineState.SHUTDOWN) return
        invalidateCurrentSession(stopGateway = true)
        gateway.shutdown()
        engineState = EngineState.SHUTDOWN
        mutableState.value = SpeechState()
    }

    private fun initializeGateway() {
        engineState = EngineState.INITIALIZING
        mutableState.value = SpeechState(SpeechStatus.INITIALIZING)
        gateway.initialize(::handleInitialization)
    }

    private fun handleInitialization(result: SpeechGatewayInitialization): Unit = synchronized(lock) {
        if (engineState != EngineState.INITIALIZING) return
        when (result) {
            SpeechGatewayInitialization.READY -> {
                engineState = EngineState.READY
                if (pendingSession == null) mutableState.value = SpeechState()
                else enqueuePendingSession()
            }
            SpeechGatewayInitialization.UNAVAILABLE -> {
                engineState = EngineState.UNAVAILABLE
                pendingSession = null
                activeSession = null
                mutableState.value = SpeechState(
                    SpeechStatus.UNAVAILABLE,
                    OFFLINE_VOICE_UNAVAILABLE_MESSAGE,
                )
            }
            SpeechGatewayInitialization.ERROR -> {
                engineState = EngineState.ERROR
                pendingSession = null
                activeSession = null
                mutableState.value = SpeechState(SpeechStatus.ERROR, UNAVAILABLE_MESSAGE)
            }
        }
    }

    private fun enqueuePendingSession() {
        val session = pendingSession ?: return
        pendingSession = null
        activeSession = session
        mutableState.value = SpeechState(SpeechStatus.SPEAKING)
        session.segments.forEachIndexed { index, segment ->
            val accepted = gateway.speak(
                text = segment.text,
                queueMode = if (index == 0) SpeechQueueMode.FLUSH else SpeechQueueMode.ADD,
                utteranceId = "speechSession${session.id}-segment$index",
            )
            if (!accepted) {
                failActiveSession()
                return
            }
        }
    }

    private fun handleStart(utteranceId: String) {
        var notify: (() -> Unit)? = null
        synchronized(lock) {
            val session = activeSession ?: return
            if (utteranceId !in session.utteranceIds) return
            val index = session.segmentIndexByUtteranceId[utteranceId]
            mutableState.value = SpeechState(
                SpeechStatus.SPEAKING,
                activeSegmentIndex = index,
            )
            val observer = session.observer
            if (observer != null && index != null) {
                notify = { observer.onSegmentStart(index) }
            }
        }
        notify?.invoke()
    }

    private fun handleRangeStart(utteranceId: String, startInclusive: Int) {
        var notify: (() -> Unit)? = null
        synchronized(lock) {
            val session = activeSession ?: return
            val observer = session.observer ?: return
            val index = session.segmentIndexByUtteranceId[utteranceId] ?: return
            notify = { observer.onSegmentRange(index, startInclusive) }
        }
        notify?.invoke()
    }

    private fun handleDone(utteranceId: String) {
        var notify: (() -> Unit)? = null
        synchronized(lock) {
            val session = activeSession ?: return
            if (utteranceId !in session.utteranceIds) return
            if (utteranceId == session.finalUtteranceId) {
                activeSession = null
                mutableState.value = SpeechState()
                session.observer?.let { observer -> notify = { observer.onFinished() } }
            }
        }
        notify?.invoke()
    }

    private fun handleError(utteranceId: String): Unit = synchronized(lock) {
        if (utteranceId !in (activeSession?.utteranceIds ?: emptySet())) return
        failActiveSession()
    }

    private fun failActiveSession() {
        invalidateCurrentSession(stopGateway = true)
        mutableState.value = SpeechState(SpeechStatus.ERROR, SPEECH_ERROR_MESSAGE)
    }

    private fun invalidateCurrentSession(stopGateway: Boolean) {
        nextSessionId += 1
        pendingSession = null
        activeSession = null
        if (stopGateway) gateway.stop()
    }

    companion object {
        const val UNAVAILABLE_MESSAGE = "読み上げ機能を利用できません"
        const val OFFLINE_VOICE_UNAVAILABLE_MESSAGE =
            "この端末ではオフラインの日本語読み上げ音声を利用できません"
        const val SPEECH_ERROR_MESSAGE = "読み上げに失敗しました"
    }
}
