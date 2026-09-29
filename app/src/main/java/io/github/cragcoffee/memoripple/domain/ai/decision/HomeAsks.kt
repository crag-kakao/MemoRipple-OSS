package io.github.cragcoffee.memoripple.domain.ai.decision

import io.github.cragcoffee.memoripple.domain.ai.AiIntent
import io.github.cragcoffee.memoripple.domain.ai.IntentProposal
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind

/** The two entrances of the chat home's launcher that open a question instead of a template. */
enum class HomeAsk { MEMO, SEARCH }

/**
 * The chat home's launcher (human brief 2026-09-23) asks a question the same way the
 * [DecisionEngine] does: **one fixed slot, one fixed question, an empty draft**, and the answer
 * completes through the unchanged validator → resolver → policy → preview → human confirmation.
 *
 * The questions are constants, never generated and never a technical word; the drafts carry no
 * id, no date and no text — only what the entrance itself means. Nothing here can execute
 * anything: a [DecisionResult.NeedsClarification] is a question, and the chat's screen never
 * builds a proposal of its own.
 */
object HomeAsks {
    const val ASK_MEMO = "何をメモしますか？"
    const val ASK_SEARCH = "何を探しますか？"

    fun clarify(ask: HomeAsk): DecisionResult.NeedsClarification = when (ask) {
        HomeAsk.MEMO -> DecisionResult.NeedsClarification(
            slot = ClarificationSlot.BODY,
            question = ASK_MEMO,
            choices = emptyList(),
            draft = IntentProposal(AiIntent.CREATE, documentKind = DocumentKind.MEMO),
        )
        HomeAsk.SEARCH -> DecisionResult.NeedsClarification(
            slot = ClarificationSlot.QUERY,
            question = ASK_SEARCH,
            choices = emptyList(),
            draft = IntentProposal(AiIntent.SEARCH),
        )
    }
}
