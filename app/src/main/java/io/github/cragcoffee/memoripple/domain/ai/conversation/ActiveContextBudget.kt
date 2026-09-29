package io.github.cragcoffee.memoripple.domain.ai.conversation

/** One line of the window the model sees: a role and the text the user saw. */
data class ConversationLine(val role: ChatRole, val text: String)

/** The recent part of a transcript that goes into one ask; [truncated] says older lines exist. */
data class ConversationWindow(val messages: List<ConversationLine>, val truncated: Boolean) {
    val isEmpty: Boolean get() = messages.isEmpty()

    companion object {
        val EMPTY = ConversationWindow(emptyList(), truncated = false)
    }
}

/**
 * The bounded active context (docs/AI_CONVERSATION_HISTORY.md §active context): the newest
 * messages of the transcript, at most [MAX_MESSAGES] and [MAX_CHARS] characters, each message
 * clipped to [MAX_MESSAGE_CHARS]. Older lines stay in the history on screen but leave the prompt;
 * nothing is summarised.
 *
 * Budget: the models run with a [MODEL_CONTEXT_TOKENS] context; prompt v1 is 510 / 553 tokens on the
 * two candidates (docs/LLM_PHASE0.md), the generation reserve is 256 tokens
 * (`GenerationRequest.DEFAULT_MAX_TOKENS`), the shown lines are at most 50 × ~40 characters and
 * the input at most 200 characters (parser limits) — about 2,000 tokens of headroom in the worst
 * case. Japanese runs near one token per character on these tokenizers, so 1,200 characters of
 * history keep a wide margin.
 */
object ActiveContextBudget {
    const val MODEL_CONTEXT_TOKENS = 4096
    const val MAX_MESSAGES = 6
    const val MAX_CHARS = 1200
    const val MAX_MESSAGE_CHARS = 300

    fun window(transcript: List<ChatMessage>): ConversationWindow {
        if (transcript.isEmpty()) return ConversationWindow.EMPTY
        val kept = ArrayList<ConversationLine>()
        var chars = 0
        var truncated = false
        for (message in transcript.asReversed()) {
            // Phase 4B (docs/GENERATION_EFFICIENCY.md): the model reads the words and the result lines; a write
            // confirmation or a failure card is a UI event that adds tokens and no meaning, and is left out
            if (message.kind == ChatMessageKind.WRITE_EVENT || message.kind == ChatMessageKind.FAILURE) continue
            if (kept.size >= MAX_MESSAGES) { truncated = true; break }
            var text = message.text.trim()
            if (kept.isEmpty()) {
                // only the newest line may be clipped; every older line is kept whole or left out
                if (text.length > MAX_MESSAGE_CHARS) { text = text.take(MAX_MESSAGE_CHARS); truncated = true }
            } else if (text.length > MAX_MESSAGE_CHARS || chars + text.length > MAX_CHARS) {
                truncated = true
                break
            }
            kept += ConversationLine(message.role, text)
            chars += text.length
        }
        return ConversationWindow(kept.asReversed(), truncated)
    }
}
