package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentPresetTest {
    @Test
    fun defaultLabelsAndInputTextsMatchTheProductDefinition() {
        val defaults = io.github.cragcoffee.memoripple.data.FavoriteComment.Defaults
        assertEquals(
            listOf("草", "拍手", "驚き", "ｷﾀ━━", "ここ好き", "がんばれ"),
            defaults.map { it.label },
        )
        assertEquals(
            listOf(
                "wwwwwwww",
                "88888888",
                "！？！？！？",
                "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!",
                "ここ好き",
                "がんばれ！",
            ),
            defaults.map { it.text },
        )
        assertTrue(defaults.none { it.label.isBlank() || it.text.isBlank() })
        assertTrue(defaults.size <= io.github.cragcoffee.memoripple.data.FavoriteComment.MAXIMUM)
    }

    @Test
    fun presetIsInsertedIntoAnEmptyInput() {
        val result = insertCommentPreset(TextFieldValue(), "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!")

        assertEquals("ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!", result.text)
        assertEquals(result.text.length, result.selection.start)
    }

    @Test
    fun presetIsInsertedAtCursorWithoutLosingExistingInput() {
        val current = TextFieldValue("これはです", selection = TextRange(3))

        val result = insertCommentPreset(current, "wwwwwwww")

        assertEquals("これはwwwwwwwwです", result.text)
        assertEquals(11, result.selection.start)
    }

    @Test
    fun presetReplacesOnlyTheCurrentSelection() {
        val current = TextFieldValue("前置換後", selection = TextRange(1, 3))

        val result = insertCommentPreset(current, "88888888")

        assertEquals("前88888888後", result.text)
    }
}
