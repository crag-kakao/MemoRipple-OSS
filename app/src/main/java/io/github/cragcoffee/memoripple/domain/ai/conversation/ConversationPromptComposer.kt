package io.github.cragcoffee.memoripple.domain.ai.conversation

import io.github.cragcoffee.memoripple.domain.ai.AiResultContext
import io.github.cragcoffee.memoripple.domain.ai.runtime.StructuredIntentGenerator

/**
 * Frozen prompt v1 + a conversation section: the system prompt is the bundled asset, untouched;
 * the recent window goes into the **user turn** as plain `USER:` / `ASSISTANT:` lines before the
 * shown candidates and the input. With an empty window the user turn is exactly Phase 0's. No id,
 * ref, kind or hidden state is ever written here — only what the user saw.
 */
object ConversationPromptComposer {
    fun userMessage(userText: String, context: AiResultContext, window: ConversationWindow): String {
        val base = StructuredIntentGenerator.userMessage(userText, context)
        if (window.isEmpty) return base
        val sb = StringBuilder("直前の会話:\n")
        if (window.truncated) sb.append("（それより前の会話は省略）\n")
        window.messages.forEach { line ->
            sb.append(if (line.role == ChatRole.USER) "USER: " else "ASSISTANT: ").append(line.text.replace('\n', ' ')).append('\n')
        }
        sb.append('\n').append(base)
        return sb.toString()
    }
}
