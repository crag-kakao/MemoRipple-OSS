package io.github.cragcoffee.memoripple.speech

import io.github.cragcoffee.memoripple.domain.diary.FutureCommentRevealContext
import io.github.cragcoffee.memoripple.domain.diary.RevealedFutureDiaryComment
import io.github.cragcoffee.memoripple.domain.diary.SourceDiaryContext
import io.github.cragcoffee.memoripple.domain.speech.FutureSpeechContent
import io.github.cragcoffee.memoripple.domain.speech.SpeechContentComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPlaybackCoordinatorTest {
    @Test
    fun `starting speech while comments play leaves comments running`() {
        val gateway = ReadyGateway()
        val speechController = SpeechController(gateway)
        var commentStopCalls = 0
        val subject = SpeechPlaybackCoordinator(
            speechController = speechController,
            stopCommentPlayback = { commentStopCalls += 1 },
        )

        assertTrue(subject.startSpeech("本文"))
        assertEquals(0, commentStopCalls)
        assertEquals(SpeechStatus.SPEAKING, speechController.state.value.status)
    }

    @Test
    fun `starting comments while speech runs leaves speech running`() {
        val gateway = ReadyGateway()
        val speechController = SpeechController(gateway)
        var commentStartCalls = 0
        val subject = SpeechPlaybackCoordinator(speechController) {}
        subject.startSpeech("本文")

        subject.startCommentPlayback { commentStartCalls += 1 }

        assertEquals(0, gateway.stopCalls)
        assertEquals(SpeechStatus.SPEAKING, speechController.state.value.status)
        assertEquals(1, commentStartCalls)
    }

    @Test
    fun `speech stop does not stop comments`() {
        val gateway = ReadyGateway()
        val speechController = SpeechController(gateway)
        var commentStopCalls = 0
        val subject = SpeechPlaybackCoordinator(speechController) { commentStopCalls += 1 }
        subject.startSpeech("本文")

        subject.stopSpeech()

        assertEquals(0, commentStopCalls)
        assertEquals(SpeechStatus.IDLE, speechController.state.value.status)
    }

    @Test
    fun `comment stop does not stop speech`() {
        val gateway = ReadyGateway()
        val speechController = SpeechController(gateway)
        var commentStopCalls = 0
        val subject = SpeechPlaybackCoordinator(speechController) { commentStopCalls += 1 }
        subject.startSpeech("本文")

        subject.stopCommentPlayback()

        assertEquals(1, commentStopCalls)
        assertEquals(0, gateway.stopCalls)
        assertEquals(SpeechStatus.SPEAKING, speechController.state.value.status)
    }

    @Test
    fun `lifecycle cleanup stops both`() {
        val gateway = ReadyGateway()
        val speechController = SpeechController(gateway)
        var commentStopCalls = 0
        val subject = SpeechPlaybackCoordinator(speechController) { commentStopCalls += 1 }
        subject.startSpeech("本文")

        subject.stopAll()

        assertEquals(1, commentStopCalls)
        assertTrue(gateway.stopCalls > 0)
        assertEquals(SpeechStatus.IDLE, speechController.state.value.status)
    }

    @Test
    fun `completed future presentation can speak while replay starts`() {
        val gateway = ReadyGateway()
        val speechController = SpeechController(gateway)
        var replayStarts = 0
        val subject = SpeechPlaybackCoordinator(speechController) {}
        val context = FutureCommentRevealContext(
            sourceDiary = SourceDiaryContext(1, 2, "元の日記"),
            comment = RevealedFutureDiaryComment(
                id = 3,
                diaryEntryId = 1,
                text = "未来コメント",
                revealAt = 10,
                revealedAt = 20,
                firstPresentedAt = 30,
            ),
        )
        val speech = SpeechContentComposer().future(context, FutureSpeechContent.BOTH)

        subject.startSpeech(speech)
        subject.startCommentPlayback { replayStarts += 1 }

        assertEquals(SpeechStatus.SPEAKING, speechController.state.value.status)
        assertEquals(1, replayStarts)
        assertEquals(0, gateway.stopCalls)
    }

    private class ReadyGateway : TextToSpeechGateway {
        override val maxInputLength: Int = 100
        var stopCalls = 0

        override fun setProgressListener(listener: SpeechProgressListener) = Unit

        override fun initialize(onComplete: (SpeechGatewayInitialization) -> Unit) {
            onComplete(SpeechGatewayInitialization.READY)
        }

        override fun speak(
            text: String,
            queueMode: SpeechQueueMode,
            utteranceId: String,
        ): Boolean = true

        override fun stop() {
            stopCalls += 1
        }

        override fun shutdown() = Unit
    }
}
