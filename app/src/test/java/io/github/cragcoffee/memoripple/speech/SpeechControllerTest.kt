package io.github.cragcoffee.memoripple.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechControllerTest {
    @Test
    fun `initialization resumes request and queues flush then add with unique ids`() {
        val gateway = FakeGateway(maxInputLength = 4)
        val subject = SpeechController(gateway)

        assertTrue(subject.speak("あいうえおかきく"))
        assertEquals(SpeechStatus.INITIALIZING, subject.state.value.status)
        gateway.completeInitialization(SpeechGatewayInitialization.READY)

        assertEquals(SpeechStatus.SPEAKING, subject.state.value.status)
        assertEquals(SpeechQueueMode.FLUSH, gateway.requests.first().queueMode)
        assertTrue(gateway.requests.drop(1).all { it.queueMode == SpeechQueueMode.ADD })
        assertEquals(gateway.requests.size, gateway.requests.map { it.utteranceId }.toSet().size)
    }

    @Test
    fun `segment sessions report the caller's own index as each utterance starts`() {
        val gateway = readyGateway()
        val subject = SpeechController(gateway)

        assertTrue(subject.speakSegments(listOf("一つ目。", "二つ目。", "三つ目。")))
        assertEquals(3, gateway.requests.size)
        assertEquals(null, subject.state.value.activeSegmentIndex)

        gateway.listener.onStart(gateway.requests[0].utteranceId)
        assertEquals(0, subject.state.value.activeSegmentIndex)
        gateway.listener.onStart(gateway.requests[1].utteranceId)
        assertEquals(1, subject.state.value.activeSegmentIndex)
        gateway.listener.onStart(gateway.requests[2].utteranceId)
        assertEquals(2, subject.state.value.activeSegmentIndex)

        gateway.listener.onDone(gateway.requests.last().utteranceId)
        assertEquals(SpeechStatus.IDLE, subject.state.value.status)
        assertEquals(null, subject.state.value.activeSegmentIndex)
    }

    @Test
    fun `a blank-only segment list is refused`() {
        val gateway = readyGateway()
        val subject = SpeechController(gateway)

        assertFalse(subject.speakSegments(listOf("", "  ")))
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun `only final completion returns state to idle`() {
        val gateway = readyGateway(maxInputLength = 4)
        val subject = SpeechController(gateway)
        subject.speak("あいうえおかきく")

        gateway.listener.onDone(gateway.requests.first().utteranceId)
        assertEquals(SpeechStatus.SPEAKING, subject.state.value.status)
        gateway.listener.onDone(gateway.requests.last().utteranceId)
        assertEquals(SpeechStatus.IDLE, subject.state.value.status)
    }

    @Test
    fun `stale callback cannot finish a newer session`() {
        val gateway = readyGateway(maxInputLength = 20)
        val subject = SpeechController(gateway)
        subject.speak("session A")
        val staleId = gateway.requests.last().utteranceId
        subject.stop()
        subject.speak("session B")
        val currentId = gateway.requests.last().utteranceId

        assertNotEquals(staleId, currentId)
        gateway.listener.onDone(staleId)
        assertEquals(SpeechStatus.SPEAKING, subject.state.value.status)
        gateway.listener.onDone(currentId)
        assertEquals(SpeechStatus.IDLE, subject.state.value.status)
    }

    @Test
    fun `stop clears session calls gateway and returns idle`() {
        val gateway = readyGateway()
        val subject = SpeechController(gateway)
        subject.speak("本文")

        subject.stop()

        assertTrue(gateway.stopCalls > 0)
        assertEquals(SpeechStatus.IDLE, subject.state.value.status)
    }

    @Test
    fun `segment error is contained and reported`() {
        val gateway = readyGateway(maxInputLength = 4)
        val subject = SpeechController(gateway)
        subject.speak("長い読み上げ本文")

        gateway.listener.onError(gateway.requests.first().utteranceId)

        assertEquals(SpeechStatus.ERROR, subject.state.value.status)
        assertEquals(SpeechController.SPEECH_ERROR_MESSAGE, subject.state.value.message)
    }

    @Test
    fun `rejected speak call stops queue and reports error`() {
        val gateway = readyGateway().apply { acceptRequests = false }
        val subject = SpeechController(gateway)

        assertTrue(subject.speak("本文"))

        assertEquals(SpeechStatus.ERROR, subject.state.value.status)
        assertTrue(gateway.stopCalls > 0)
    }

    @Test
    fun `empty content does not initialize a session`() {
        val gateway = FakeGateway()
        val subject = SpeechController(gateway)

        assertFalse(subject.speak(" \n "))
        assertEquals(0, gateway.initializeCalls)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun `unavailable offline voice reports unavailable`() {
        val gateway = FakeGateway()
        val subject = SpeechController(gateway)
        subject.speak("本文")

        gateway.completeInitialization(SpeechGatewayInitialization.UNAVAILABLE)

        assertEquals(SpeechStatus.UNAVAILABLE, subject.state.value.status)
    }

    @Test
    fun `an idle release hands the engine back and the next speak reinitializes`() {
        val gateway = FakeGateway()
        val subject = SpeechController(gateway)
        subject.speak("ひとこと")
        gateway.completeInitialization(SpeechGatewayInitialization.READY)
        gateway.listener.onDone(gateway.requests.last().utteranceId)

        subject.releaseWhenIdle()

        assertEquals(SpeechStatus.IDLE, subject.state.value.status)
        assertTrue(subject.speak("ふたこと"))
        // A fresh engine is being brought up — release was not terminal.
        assertEquals(SpeechStatus.INITIALIZING, subject.state.value.status)
        gateway.completeInitialization(SpeechGatewayInitialization.READY)
        assertEquals(SpeechStatus.SPEAKING, subject.state.value.status)
    }

    @Test
    fun `a release while speech is in flight leaves it alone`() {
        val gateway = readyGateway()
        val subject = SpeechController(gateway)
        subject.speak("ながいはなし")

        subject.releaseWhenIdle()

        assertEquals(SpeechStatus.SPEAKING, subject.state.value.status)
    }

    private fun readyGateway(maxInputLength: Int = 100): FakeGateway =
        FakeGateway(maxInputLength).apply { autoInitialization = SpeechGatewayInitialization.READY }

    private data class Request(
        val text: String,
        val queueMode: SpeechQueueMode,
        val utteranceId: String,
    )

    private class FakeGateway(
        override val maxInputLength: Int = 100,
    ) : TextToSpeechGateway {
        lateinit var listener: SpeechProgressListener
        var autoInitialization: SpeechGatewayInitialization? = null
        var initializeCalls = 0
        var stopCalls = 0
        var acceptRequests = true
        val requests = mutableListOf<Request>()
        private var initializationCallback: ((SpeechGatewayInitialization) -> Unit)? = null

        override fun setProgressListener(listener: SpeechProgressListener) {
            this.listener = listener
        }

        override fun initialize(onComplete: (SpeechGatewayInitialization) -> Unit) {
            initializeCalls += 1
            initializationCallback = onComplete
            autoInitialization?.let(onComplete)
        }

        fun completeInitialization(result: SpeechGatewayInitialization) {
            checkNotNull(initializationCallback)(result)
        }

        override fun speak(
            text: String,
            queueMode: SpeechQueueMode,
            utteranceId: String,
        ): Boolean {
            requests.add(Request(text, queueMode, utteranceId))
            return acceptRequests
        }

        override fun stop() {
            stopCalls += 1
        }

        override fun shutdown() = Unit
    }
}
