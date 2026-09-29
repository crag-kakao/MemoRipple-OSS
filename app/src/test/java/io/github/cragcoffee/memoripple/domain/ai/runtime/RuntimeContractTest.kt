package io.github.cragcoffee.memoripple.domain.ai.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** RED 7, 11, 12, 28, 29, 32: the runtime contract the domain sees, with nothing model- or engine-specific in it. */
class RuntimeContractTest {
    @Test
    fun aModelDescriptorNamesAFileAndCapabilitiesNeverADatabaseIdOrAVendor() {
        val d = ModelDescriptor(
            id = "example-4b", displayName = "Example 4B", location = ModelLocation.AppFile("example-4b-Q4_K_M.gguf"),
            architecture = "example", contextSize = 4096, quantization = "Q4_K_M", capabilities = setOf(ModelCapability.STRUCTURED_OUTPUT),
        )
        assertEquals("example-4b", d.id)
        val names = ModelDescriptor::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(names.none { it.endsWith("id") && it != "id" })
        assertTrue(ModelDescriptor::class.java.declaredFields.none { it.type == java.lang.Long.TYPE })
        assertEquals(listOf("UNLOADED", "LOADING", "READY", "GENERATING", "UNLOADING", "FAILED"), RuntimeState.entries.map { it.name })
    }

    @Test
    fun thePromptIsPhaseZeroV1AndTheGrammarIsPhaseZerosByteForByte() {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "tools/llm-eval").isDirectory }
        val assets = File(root, "app/src/main/assets/ai")
        assertEquals("v1", BundledPromptAssets.PROMPT_VERSION)
        assertEquals(File(root, "tools/llm-eval/prompts/v1/intent_system.txt").readText(), File(assets, BundledPromptAssets.INTENT_SYSTEM_FILE).readText())
        assertEquals(File(root, "tools/llm-eval/grammar/intent_proposal.gbnf").readText(), File(assets, BundledPromptAssets.INTENT_GRAMMAR_FILE).readText())
        assertTrue(File(assets, BundledPromptAssets.INTENT_GRAMMAR_FILE).readText().contains("root ::="))
    }

    @Test
    fun theThermalGateAllowsAtZeroThrottlesAtLightAndModerateAndBlocksFromSevere() {
        var status = 0
        val gate = StatusThermalGate { status }
        assertEquals(ThermalDecision.ALLOWED, gate.check())
        status = 1; assertEquals(ThermalDecision.THROTTLED, gate.check())
        status = 2; assertEquals(ThermalDecision.THROTTLED, gate.check())
        status = 3; assertEquals(ThermalDecision.BLOCKED, gate.check())
        status = 4; assertEquals(ThermalDecision.BLOCKED, gate.check())
        status = 6; assertEquals(ThermalDecision.BLOCKED, gate.check())
        assertFalse(ThermalDecision.BLOCKED.allowsGeneration)
        assertTrue(ThermalDecision.THROTTLED.allowsGeneration)
    }

    @Test
    fun modelFilesLiveUnderTheNoBackupDirectoryAndADeveloperPathIsExplicit() {
        val noBackup = File("/data/user/0/io.github.cragcoffee.memoripple/no_backup")
        val resolver = ModelFileResolver(noBackup, "model.bin")
        val f = resolver.resolve(ModelLocation.AppFile("example.gguf"))
        assertTrue(f.path.startsWith(noBackup.path))
        assertEquals(File(noBackup, "models/example.gguf"), f)
        assertEquals(File("/data/local/tmp/llmbench/models/x.gguf"), resolver.resolve(ModelLocation.DeveloperPath("/data/local/tmp/llmbench/models/x.gguf")))
        // a file name cannot climb out of the models directory
        assertEquals(File(noBackup, "models/passwd"), resolver.resolve(ModelLocation.AppFile("../../etc/passwd")))
    }
}
