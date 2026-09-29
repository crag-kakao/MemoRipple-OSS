package io.github.cragcoffee.memoripple.speech

enum class SpeechGatewayInitialization { READY, UNAVAILABLE, ERROR }

enum class SpeechQueueMode { FLUSH, ADD }

interface SpeechProgressListener {
    fun onStart(utteranceId: String)
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String)

    /**
     * The engine's word-by-word position inside one utterance, when the engine reports it at
     * all — many do, some never will. Default is silence so existing gateways and fakes stay
     * exactly what they were.
     */
    fun onRangeStart(utteranceId: String, startInclusive: Int, endExclusive: Int) {}
}

interface TextToSpeechGateway {
    val maxInputLength: Int
    fun setProgressListener(listener: SpeechProgressListener)
    fun initialize(onComplete: (SpeechGatewayInitialization) -> Unit)
    fun speak(text: String, queueMode: SpeechQueueMode, utteranceId: String): Boolean
    fun stop()
    fun shutdown()
}
