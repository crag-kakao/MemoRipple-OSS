package io.github.cragcoffee.memoripple.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice

class AndroidTextToSpeechGateway(context: Context) : TextToSpeechGateway {
    private val applicationContext = context.applicationContext
    private val lock = Any()
    private var engine: TextToSpeech? = null
    private var initializing = false
    private var progressListener: SpeechProgressListener? = null
    private var initializationCallbacks = mutableListOf<(SpeechGatewayInitialization) -> Unit>()

    override val maxInputLength: Int
        get() = TextToSpeech.getMaxSpeechInputLength()

    override fun setProgressListener(listener: SpeechProgressListener) {
        synchronized(lock) {
            progressListener = listener
            engine?.setOnUtteranceProgressListener(androidProgressListener())
        }
    }

    override fun initialize(onComplete: (SpeechGatewayInitialization) -> Unit) {
        synchronized(lock) {
            if (engine != null && !initializing) {
                onComplete(SpeechGatewayInitialization.READY)
                return
            }
            initializationCallbacks += onComplete
            if (initializing) return
            initializing = true
            engine = TextToSpeech(applicationContext) { status -> handleInitialization(status) }
        }
    }

    override fun speak(
        text: String,
        queueMode: SpeechQueueMode,
        utteranceId: String,
    ): Boolean = synchronized(lock) {
        val current = engine ?: return false
        val androidQueueMode = when (queueMode) {
            SpeechQueueMode.FLUSH -> TextToSpeech.QUEUE_FLUSH
            SpeechQueueMode.ADD -> TextToSpeech.QUEUE_ADD
        }
        current.speak(text, androidQueueMode, null, utteranceId) == TextToSpeech.SUCCESS
    }

    override fun stop() {
        synchronized(lock) { engine?.stop() }
    }

    override fun shutdown() {
        val callbacks: List<(SpeechGatewayInitialization) -> Unit>
        synchronized(lock) {
            callbacks = initializationCallbacks.toList()
            initializationCallbacks.clear()
            initializing = false
            engine?.stop()
            engine?.shutdown()
            engine = null
        }
        callbacks.forEach { it(SpeechGatewayInitialization.ERROR) }
    }

    private fun handleInitialization(status: Int) {
        val result: SpeechGatewayInitialization
        val callbacks: List<(SpeechGatewayInitialization) -> Unit>
        synchronized(lock) {
            val current = engine
            result = if (status != TextToSpeech.SUCCESS || current == null) {
                engine?.shutdown()
                engine = null
                SpeechGatewayInitialization.ERROR
            } else {
                current.setOnUtteranceProgressListener(androidProgressListener())
                val voice = selectJapaneseOfflineVoice(current.voices.orEmpty())
                if (voice == null || current.setVoice(voice) != TextToSpeech.SUCCESS) {
                    current.shutdown()
                    engine = null
                    SpeechGatewayInitialization.UNAVAILABLE
                } else {
                    SpeechGatewayInitialization.READY
                }
            }
            initializing = false
            callbacks = initializationCallbacks.toList()
            initializationCallbacks.clear()
        }
        callbacks.forEach { it(result) }
    }

    private fun selectJapaneseOfflineVoice(voices: Set<Voice>): Voice? = voices.asSequence()
        .filter { it.locale.language.equals("ja", ignoreCase = true) }
        .filterNot(Voice::isNetworkConnectionRequired)
        .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
        .sortedWith(
            compareByDescending<Voice>(Voice::getQuality)
                .thenBy(Voice::getLatency)
                .thenBy(Voice::getName),
        )
        .firstOrNull()

    private fun androidProgressListener() = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId?.let { progressListener?.onStart(it) }
        }

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { progressListener?.onDone(it) }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            utteranceId?.let { progressListener?.onRangeStart(it, start, end) }
        }

        @Deprecated("Framework compatibility callback")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { progressListener?.onError(it) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { progressListener?.onError(it) }
        }
    }
}
