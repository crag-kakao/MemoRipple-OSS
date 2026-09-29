package io.github.cragcoffee.memoripple.domain.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderProseTest {
    @Test
    fun paragraphsJoinProseAndStandMarkedLinesAlone() {
        val paragraphs = ReaderProse.paragraphs("一行目です。\n二行目です。\n\n■ 見出し\n続きの文。")
        assertEquals(listOf("一行目です。二行目です。", "", "見出し", "続きの文。"), paragraphs)
    }

    @Test
    fun blankLinesStayAsTheAirTheWriterLeft() {
        val paragraphs = ReaderProse.paragraphs("一つ目。\n\n\n二つ目。\n\n")
        assertEquals(listOf("一つ目。", "", "", "二つ目。"), paragraphs)
    }

    @Test
    fun blankParagraphsSpeakNoSentences() {
        val sentences = ReaderProse.sentences(listOf("朝。", "", "夜。"))
        assertEquals(
            listOf(ReaderProse.Sentence(0, "朝。"), ReaderProse.Sentence(2, "夜。")),
            sentences,
        )
    }

    @Test
    fun sentencesKeepTheirParagraphAndTheirEndingMark() {
        val sentences = ReaderProse.sentences(listOf("朝が来た。鳥が鳴く。", "夜。"))
        assertEquals(
            listOf(
                ReaderProse.Sentence(0, "朝が来た。"),
                ReaderProse.Sentence(0, "鳥が鳴く。"),
                ReaderProse.Sentence(1, "夜。"),
            ),
            sentences,
        )
    }

    @Test
    fun aSpokenSentenceFoldsItsRubyToTheWords() {
        val sentence = ReaderProse.Sentence(0, "｜言葉《ことば》が来た。")
        assertEquals("言葉が来た。", sentence.spoken)
    }

    @Test
    fun aTrailingStretchWithoutAMarkIsStillASentence() {
        assertEquals(
            listOf(ReaderProse.Sentence(0, "終わらない文")),
            ReaderProse.sentences(listOf("終わらない文")),
        )
    }

    @Test
    fun linkMarkersRideTheirSentencesButNeverTheVoice() {
        val paragraphs = ReaderProse.paragraphs("朝が来た[R1]。鳥が鳴く。\n夜[R2][R3]。")
        assertEquals(listOf("朝が来た[R1]。鳥が鳴く。夜[R2][R3]。"), paragraphs)
        val sentences = ReaderProse.sentences(paragraphs)
        assertEquals(listOf(listOf(1), emptyList(), listOf(2, 3)), sentences.map { it.linkNumbers })
        assertEquals(listOf("朝が来た。", "鳥が鳴く。", "夜。"), sentences.map { it.spoken })
    }

    @Test
    fun aSpokenSentenceDropsMarkersAndFoldsRubyTogether() {
        val sentence = ReaderProse.Sentence(0, "｜言葉《ことば》[R7]が来た。")
        assertEquals("言葉が来た。", sentence.spoken)
        assertEquals(listOf(7), sentence.linkNumbers)
    }
}
