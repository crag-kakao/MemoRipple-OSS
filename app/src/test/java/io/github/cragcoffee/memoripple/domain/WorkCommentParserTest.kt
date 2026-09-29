package io.github.cragcoffee.memoripple.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkCommentParserTest {
    private val parser = WorkCommentParser()

    @Test
    fun parsesSupportedMarkersAndIndentDepth() {
        val source = """
            # 主人公
            - 性格
              - 明るい
            > 補足
            ! 重要
            ? 疑問
            普通の本文
        """.trimIndent()

        assertEquals(
            listOf(
                WorkLine("主人公", WorkLineType.HEADING, 0, 0),
                WorkLine("性格", WorkLineType.ITEM, 0, 1),
                WorkLine("明るい", WorkLineType.ITEM, 1, 2),
                WorkLine("補足", WorkLineType.NOTE, 0, 3),
                WorkLine("重要", WorkLineType.IMPORTANT, 0, 4),
                WorkLine("疑問", WorkLineType.QUESTION, 0, 5),
                WorkLine("普通の本文", WorkLineType.PLAIN, 0, 6),
            ),
            parser.parse(source),
        )
    }

    @Test
    fun ignoresBlankAndMarkerOnlyLines() {
        assertEquals(emptyList<WorkLine>(), parser.parse("\n# \n- \n  \n"))
    }

    @Test
    fun playbackOutlineExcludesOrdinaryUnmarkedBodyText() {
        assertEquals(
            listOf(WorkLine("見出し", WorkLineType.HEADING, 0, 1)),
            parser.parseOutline("普通の本文\n# 見出し\n続きの本文"),
        )
    }
}
