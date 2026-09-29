package io.github.cragcoffee.memoripple

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.cragcoffee.memoripple.speech.AndroidTextToSpeechGateway
import io.github.cragcoffee.memoripple.speech.SpeechGatewayInitialization
import io.github.cragcoffee.memoripple.speech.SpeechProgressListener
import io.github.cragcoffee.memoripple.speech.SpeechQueueMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTextToSpeechSmokeTest {
    @Test
    fun offlineJapaneseVoiceCanAcceptShortSpeechWhenInstalled() {
        val gateway = AndroidTextToSpeechGateway(
            ApplicationProvider.getApplicationContext(),
        )
        val initialized = CountDownLatch(1)
        val result = AtomicReference<SpeechGatewayInitialization>()
        gateway.setProgressListener(
            object : SpeechProgressListener {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) = Unit
                override fun onError(utteranceId: String) = Unit
            },
        )

        try {
            gateway.initialize {
                result.set(it)
                initialized.countDown()
            }
            assertTrue("TTS initialization timed out", initialized.await(20, TimeUnit.SECONDS))

            if (result.get() == SpeechGatewayInitialization.READY) {
                assertTrue(
                    gateway.speak(
                        text = "これはテストです",
                        queueMode = SpeechQueueMode.FLUSH,
                        utteranceId = "instrumentation-smoke",
                    ),
                )
            }
        } finally {
            gateway.shutdown()
        }
    }
}
