package io.github.cragcoffee.memoripple.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkCommentParserScopeTest {

    private val parser = WorkCommentParser()
    private val body = """
        ■ 見出し
        - 項目
        改札を抜けると朝の光が届いていた。誰もいなかった。
        > 補足
    """.trimIndent()

    @Test
    fun theOutlineScopeCarriesOnlyWhatTheWriterMarked() {
        val lines = parser.parse(WorkCommentScope.OUTLINE, body)

        assertEquals(listOf("見出し", "項目", "補足"), lines.map(WorkLine::text))
    }

    @Test
    fun theBodyScopeCarriesTheProseAsWell() {
        val lines = parser.parse(WorkCommentScope.BODY, body)

        assertEquals(
            listOf("見出し", "項目", "改札を抜けると朝の光が届いていた。", "誰もいなかった。", "補足"),
            lines.map(WorkLine::text),
        )
    }

    @Test
    fun aMarkedLineIsLeftWholeHoweverLongItIs() {
        val long = "- " + "あ".repeat(120)

        val lines = parser.parse(WorkCommentScope.BODY, long)

        // Marking it is the writer saying this is one thing, so it is not cut.
        assertEquals(1, lines.size)
        assertEquals(120, lines.single().text.length)
    }

    @Test
    fun theProseKeepsThePlaceItCameFrom() {
        val lines = parser.parse(WorkCommentScope.BODY, body)
            .filter { it.type == WorkLineType.PLAIN }

        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.sourceLine == 2 })
    }

    @Test
    fun theOldOutlineEntryPointStillMeansTheOutline() {
        assertEquals(
            parser.parse(WorkCommentScope.OUTLINE, body),
            parser.parseOutline(body),
        )
    }
}
