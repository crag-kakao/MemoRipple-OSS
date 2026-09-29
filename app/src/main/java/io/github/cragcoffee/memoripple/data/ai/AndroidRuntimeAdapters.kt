package io.github.cragcoffee.memoripple.data.ai

import android.content.Context
import android.os.PowerManager
import io.github.cragcoffee.memoripple.domain.ai.runtime.BundledPromptAssets
import io.github.cragcoffee.memoripple.domain.ai.runtime.PromptAssets
import io.github.cragcoffee.memoripple.domain.ai.runtime.StatusThermalGate
import io.github.cragcoffee.memoripple.domain.ai.runtime.ThermalGate

/** The platform's thermal status behind the domain's gate (0 NONE … 3 SEVERE … 6 SHUTDOWN). */
fun androidThermalGate(context: Context): ThermalGate {
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return StatusThermalGate { power.currentThermalStatus }
}

/**
 * Power Save Mode as a policy input for the resource controller (Phase 2,
 * docs/AI_RESOURCE_CONTROLLER.md): a one-shot read at the moment a generation is budgeted —
 * never a receiver, never monitoring, no permission (the same one-shot philosophy as Phase 7's
 * isOnline). ECO shrinks the budget; it never forbids the AI and never switches the model.
 */
fun androidPowerSaveReader(context: Context): () -> Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return { power.isPowerSaveMode }
}

/** Prompt v1 and the IntentProposal grammar from `assets/ai`, read once. */
class AssetPromptAssets(context: Context) : PromptAssets {
    private val assets = context.assets
    override val promptVersion: String = BundledPromptAssets.PROMPT_VERSION
    override val intentSystemPrompt: String by lazy { read(BundledPromptAssets.INTENT_SYSTEM_FILE).trim() }
    override val intentGrammar: String by lazy { read(BundledPromptAssets.INTENT_GRAMMAR_FILE) }
    private fun read(name: String): String = assets.open("${BundledPromptAssets.ASSET_DIR}/$name").bufferedReader().use { it.readText() }
}
