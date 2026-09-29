package io.github.cragcoffee.memoripple.speech

/** Keeps speech and visual comment controls independent while sharing lifecycle cleanup. */
class SpeechPlaybackCoordinator(
    private val speechController: SpeechController,
    private val stopCommentPlayback: () -> Unit,
) {
    fun startSpeech(text: String): Boolean = speechController.speak(text)

    fun startSpeechSegments(
        segments: List<String>,
        observer: SpeechSessionObserver?,
    ): Boolean = speechController.speakSegments(segments, observer)

    fun startCommentPlayback(start: () -> Unit) = start()

    fun stopSpeech() = speechController.stop()

    fun stopCommentPlayback() = stopCommentPlayback.invoke()

    fun stopAll() {
        stopCommentPlayback()
        speechController.stop()
    }
}
