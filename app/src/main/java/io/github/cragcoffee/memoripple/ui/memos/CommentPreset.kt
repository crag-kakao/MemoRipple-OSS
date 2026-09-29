package io.github.cragcoffee.memoripple.ui.memos

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.max
import kotlin.math.min

internal fun insertCommentPreset(
    current: TextFieldValue,
    presetText: String,
): TextFieldValue {
    val selectionStart = min(current.selection.start, current.selection.end)
        .coerceIn(0, current.text.length)
    val selectionEnd = max(current.selection.start, current.selection.end)
        .coerceIn(selectionStart, current.text.length)
    val updatedText = current.text.replaceRange(selectionStart, selectionEnd, presetText)
    return current.copy(
        text = updatedText,
        selection = TextRange(selectionStart + presetText.length),
        composition = null,
    )
}
