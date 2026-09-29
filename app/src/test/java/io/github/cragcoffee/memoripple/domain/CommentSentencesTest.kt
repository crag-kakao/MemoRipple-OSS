package io.github.cragcoffee.memoripple.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentSentencesTest {

    @Test
    fun aParagraphIsCutIntoItsSentencesAndKeepsTheirMarks() {
        val split = CommentSentences.split("朝の光が届いていた。誰かが名前を呼んだ気がした。そこには誰もいなかった。")

        assertEquals(
            listOf("朝の光が届いていた。", "誰かが名前を呼んだ気がした。", "そこには誰もいなかった。"),
            split,
        )
    }

    @Test
    fun aQuestionOrAnExclamationAlsoEndsASentence() {
        assertEquals(listOf("本当に？", "そうだ！"), CommentSentences.split("本当に？そうだ！"))
    }

    @Test
    fun oneShortLineStaysOneComment() {
        assertEquals(listOf("牛乳を買う"), CommentSentences.split("牛乳を買う"))
    }

    @Test
    fun nothingWrittenGivesNothingToFly() {
        assertTrue(CommentSentences.split("").isEmpty())
        assertTrue(CommentSentences.split("   ").isEmpty())
    }

    @Test
    fun aSentenceTooLongToReadIsCutWhereItAlreadyPauses() {
        val long = "改札を抜けると朝の光がホームの端まで届いていて、" +
            "誰かが名前を呼んだ気がして振り返ったが、そこには誰もいなかった。"

        val split = CommentSentences.split(long)

        assertTrue(split.size >= 2)
        assertTrue(split.all { it.length <= CommentSentences.MAX_CHARACTERS })
        // Cut at the comma, which is kept at the end of the piece it closed.
        assertTrue(split.first().endsWith("、"))
        assertEquals(long.replace("、", ""), split.joinToString("").replace("、", ""))
    }

    @Test
    fun aLongRunWithNoPauseIsStillCutRatherThanLeftUnreadable() {
        val split = CommentSentences.split("あ".repeat(120))

        assertTrue(split.size >= 3)
        assertTrue(split.all { it.length <= CommentSentences.MAX_CHARACTERS })
        assertEquals(120, split.sumOf(String::length))
    }

    @Test
    fun nothingIsLostWhenAParagraphIsCut() {
        val text = "一つ目。二つ目。三つ目。"

        assertEquals(text, CommentSentences.split(text).joinToString(""))
    }
}
