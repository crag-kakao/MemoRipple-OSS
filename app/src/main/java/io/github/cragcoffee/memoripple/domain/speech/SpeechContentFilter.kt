package io.github.cragcoffee.memoripple.domain.speech

class SpeechContentFilter {
    fun shouldSpeak(text: String): Boolean = text.trim() !in NON_SPEECH_PRESETS

    private companion object {
        val NON_SPEECH_PRESETS = setOf(
            "wwwwwwww",
            "88888888",
            "！？！？！？",
            "ｷﾀ━━━━(ﾟ∀ﾟ)━━━━!!",
        )
    }
}
