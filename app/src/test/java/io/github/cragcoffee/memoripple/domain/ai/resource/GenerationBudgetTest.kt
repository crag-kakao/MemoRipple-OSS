package io.github.cragcoffee.memoripple.domain.ai.resource

import io.github.cragcoffee.memoripple.domain.ai.conversation.ActiveContextBudget
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatRole
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationLine
import io.github.cragcoffee.memoripple.domain.ai.conversation.ConversationWindow
import io.github.cragcoffee.memoripple.domain.ai.runtime.GenerationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generation budget (Phase 2): NORMAL is exactly today's numbers — introducing the controller
 * changes nothing about a NORMAL ask (RED 41) — and ECO / THERMAL_LIMITED shrink the window and
 * the output without touching prompt v1 or the grammar (RED 42–45 pin the values; the byte
 * identity of the assets has its own guards).
 */
class GenerationBudgetTest {

    @Test
    fun normalIsExactlyTheCurrentBaseline() {
        assertEquals(ActiveContextBudget.MAX_MESSAGES, GenerationBudget.NORMAL.maxRecentMessages)
        assertEquals(ActiveContextBudget.MAX_CHARS, GenerationBudget.NORMAL.maxContextChars)
        assertEquals(ActiveContextBudget.MAX_MESSAGE_CHARS, GenerationBudget.NORMAL.maxMessageChars)
        assertEquals(GenerationRequest.DEFAULT_MAX_TOKENS, GenerationBudget.NORMAL.maxOutputTokens)
    }

    @Test
    fun theReducedBudgetShrinksWithoutCollapsing() {
        val r = GenerationBudget.REDUCED
        assertTrue(r.maxRecentMessages in 1 until GenerationBudget.NORMAL.maxRecentMessages)
        assertTrue(r.maxContextChars in 200 until GenerationBudget.NORMAL.maxContextChars)
        assertTrue(r.maxOutputTokens in 64 until GenerationBudget.NORMAL.maxOutputTokens)
        assertTrue("the request type still accepts the reduced output budget", r.maxOutputTokens in 1..GenerationRequest.MAX_TOKENS_CEILING)
    }

    @Test
    fun profilesMapToBudgets() {
        assertEquals(GenerationBudget.NORMAL, GenerationBudget.forProfile(PerformanceProfile.NORMAL))
        assertEquals(GenerationBudget.REDUCED, GenerationBudget.forProfile(PerformanceProfile.ECO))
        assertEquals(GenerationBudget.REDUCED, GenerationBudget.forProfile(PerformanceProfile.THERMAL_LIMITED))
        assertEquals(GenerationBudget.REDUCED, GenerationBudget.forProfile(PerformanceProfile.LOW_MEMORY))
    }

    @Test
    fun clippingKeepsTheNewestLinesInsideTheBudget() {
        val lines = (1..6).map { ConversationLine(ChatRole.USER, "m$it-" + "あ".repeat(250)) }
        val window = ConversationWindow(lines, truncated = false)
        val clipped = GenerationBudget.REDUCED.clip(window)
        assertTrue(clipped.messages.size <= GenerationBudget.REDUCED.maxRecentMessages)
        assertTrue(clipped.messages.sumOf { it.text.length } <= GenerationBudget.REDUCED.maxContextChars)
        assertEquals("the newest line survives", lines.last().text, clipped.messages.last().text)
        assertTrue("dropping older lines marks the window truncated", clipped.truncated)
    }

    @Test
    fun aNormalClipOfAnAlreadyBudgetedWindowChangesNothing() {
        val lines = (1..ActiveContextBudget.MAX_MESSAGES).map { ConversationLine(ChatRole.ASSISTANT, "短い行$it") }
        val window = ConversationWindow(lines, truncated = false)
        assertEquals(window, GenerationBudget.NORMAL.clip(window))
    }
}
