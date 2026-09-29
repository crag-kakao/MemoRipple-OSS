package io.github.cragcoffee.memoripple.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** ショートカットバーの見え方: the chip's face, in whichever glyph the writer chose. */
class OutlineChipLabelTest {

    private val japaneseHeading = OutlineSymbolSelection.Default
        .with(OutlineSymbolRole.HEADING, OutlineSymbolSet.JAPANESE)

    @Test
    fun eachFaceShowsWhatItPromises() {
        assertEquals(
            "■ 見出し",
            OutlineChipLabel.SYMBOL_AND_WORD.textFor(
                "見出し",
                OutlineSymbolRole.HEADING,
                OutlineSymbolSelection.Default,
            ),
        )
        assertEquals(
            "見出し",
            OutlineChipLabel.WORD.textFor(
                "見出し",
                OutlineSymbolRole.HEADING,
                OutlineSymbolSelection.Default,
            ),
        )
        assertEquals(
            "■",
            OutlineChipLabel.SYMBOL.textFor(
                "見出し",
                OutlineSymbolRole.HEADING,
                OutlineSymbolSelection.Default,
            ),
        )
    }

    @Test
    fun theFaceSpeaksTheWritersOwnGlyph() {
        assertEquals(
            "◆ 見出し",
            OutlineChipLabel.SYMBOL_AND_WORD.textFor(
                "見出し",
                OutlineSymbolRole.HEADING,
                japaneseHeading,
            ),
        )
        assertEquals(
            "◆",
            OutlineChipLabel.SYMBOL.textFor(
                "見出し",
                OutlineSymbolRole.HEADING,
                japaneseHeading,
            ),
        )
    }

    @Test
    fun nothingStoredMeansTheFaceTheBarHasAlwaysWorn() {
        assertEquals(OutlineChipLabel.SYMBOL_AND_WORD, OutlineChipLabel.fromStorageId(null))
        assertEquals(OutlineChipLabel.SYMBOL_AND_WORD, OutlineChipLabel.fromStorageId(""))
        assertEquals(OutlineChipLabel.SYMBOL_AND_WORD, OutlineChipLabel.fromStorageId("retired"))
        OutlineChipLabel.entries.forEach { label ->
            assertEquals(label, OutlineChipLabel.fromStorageId(label.storageId))
        }
    }
}
