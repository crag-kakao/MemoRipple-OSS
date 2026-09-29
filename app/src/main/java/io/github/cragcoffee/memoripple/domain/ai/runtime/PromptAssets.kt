package io.github.cragcoffee.memoripple.domain.ai.runtime

/** The shared prompt and grammar of the structured-intent generation. One version at a time. */
interface PromptAssets {
    val promptVersion: String
    val intentSystemPrompt: String
    val intentGrammar: String
}

/**
 * Names of the bundled assets. The texts are Phase 0's prompt v1 and its IntentProposal GBNF,
 * copied byte for byte (a test compares them with `tools/llm-eval`); a prompt change is a new
 * version, never an edit in place.
 */
object BundledPromptAssets {
    const val PROMPT_VERSION = "v1"
    const val INTENT_SYSTEM_FILE = "intent_system.v1.txt"
    const val INTENT_GRAMMAR_FILE = "intent_proposal.gbnf"
    const val ASSET_DIR = "ai"
}
