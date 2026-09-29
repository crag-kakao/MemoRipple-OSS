package io.github.cragcoffee.memoripple

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import io.github.cragcoffee.memoripple.ui.notes.NoteReaderScreen
import io.github.cragcoffee.memoripple.ui.notes.NoteReaderUiState
import io.github.cragcoffee.memoripple.domain.playback.CommentAnimationState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The reader's assisted-reading wiring, driven without a speech engine. */
class NoteReaderAssistInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tappingProseHandsBackTheSentenceUnderTheFinger() {
        val tapped = mutableListOf<Int>()
        composeRule.setContent {
            NoteReaderScreen(
                state = NoteReaderUiState(
                    noteTitle = "本",
                    episodeTitle = "第一話",
                    body = "最初の文。二つ目の文。",
                    isLoading = false,
                ),
                playbackStatus = io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus.IDLE,
                playbackState = MutableStateFlow(CommentAnimationState()),
                canPlay = false,
                offsetProvider = { _, _, _, _ -> 0f },
                onBack = {},
                onPlay = {},
                onPause = {},
                onResume = {},
                onStop = {},
                onOpenEpisode = {},
                onEdit = {},
                activeSentenceIndex = 1,
                onTapSentence = tapped::add,
            )
        }

        // A tap at the paragraph's left edge lands in the first sentence.
        composeRule.onNodeWithTag("note_reader_body").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(x = left + 120f, y = top + 220f))
        }
        composeRule.waitForIdle()

        assertTrue(tapped.isNotEmpty())
        assertEquals(0, tapped.first())
    }

    @Test
    fun aSpeakingReaderOffersPauseAndStopLikeTheCommentStream() {
        composeRule.setContent {
            NoteReaderScreen(
                state = NoteReaderUiState(episodeTitle = "第一話", body = "文。", isLoading = false),
                playbackStatus = io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus.IDLE,
                playbackState = MutableStateFlow(CommentAnimationState()),
                canPlay = false,
                offsetProvider = { _, _, _, _ -> 0f },
                onBack = {}, onPlay = {}, onPause = {}, onResume = {}, onStop = {},
                onOpenEpisode = {}, onEdit = {},
                speechActive = true,
            )
        }
        composeRule.onNodeWithTag("reader_speech_pause").assertExists()
        composeRule.onNodeWithTag("reader_speech_stop").assertExists()
    }

    @Test
    fun aPausedReaderOffersResumeAndStop() {
        composeRule.setContent {
            NoteReaderScreen(
                state = NoteReaderUiState(episodeTitle = "第一話", body = "文。", isLoading = false),
                playbackStatus = io.github.cragcoffee.memoripple.domain.playback.PlaybackStatus.IDLE,
                playbackState = MutableStateFlow(CommentAnimationState()),
                canPlay = false,
                offsetProvider = { _, _, _, _ -> 0f },
                onBack = {}, onPlay = {}, onPause = {}, onResume = {}, onStop = {},
                onOpenEpisode = {}, onEdit = {},
                speechActive = false,
                speechPaused = true,
            )
        }
        composeRule.onNodeWithTag("reader_speech_resume").assertExists()
        composeRule.onNodeWithTag("reader_speech_stop").assertExists()
    }
}
