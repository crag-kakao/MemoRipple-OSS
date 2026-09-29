package io.github.cragcoffee.memoripple.domain.speech

import io.github.cragcoffee.memoripple.domain.BodyText
import io.github.cragcoffee.memoripple.domain.WorkCommentSyntax

class SpeechTextPreprocessor {
    fun preprocessBody(body: String, readRubyReadings: Boolean = false): String =
        body.lineSequence()
            .map { rawLine -> WorkCommentSyntax.recognize(rawLine)?.text ?: rawLine }
            .map { line -> BodyText.spoken(line, readRubyReadings) }
            .joinToString("\n")
            .trim()
}
