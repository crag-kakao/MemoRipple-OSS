package io.github.cragcoffee.memoripple.domain.ai.models

import io.github.cragcoffee.memoripple.data.ai.ModelProfiles
import io.github.cragcoffee.memoripple.data.ai.models.ModelCatalog
import io.github.cragcoffee.memoripple.domain.ai.runtime.ModelLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 RED (docs/AI_MODEL_MANAGEMENT.md): the product catalog — the two Phase 0 candidates with
 * the exact files, sizes, hashes and licences verified live on 2026-09-20, neutral descriptions
 * from the Phase 0 characterisations, no ranking, no default, path-safe ids.
 */
class ModelCatalogTest {
    private val qwen = ModelCatalog.qwen3_4bInstruct2507
    private val ministral = ModelCatalog.ministral3_3bInstruct2512

    @Test
    fun theCatalogHoldsExactlyTheTwoPhaseZeroCandidatesAndNoDefault() {
        assertEquals(listOf(qwen, ministral), ModelCatalog.all)
        assertEquals(setOf("qwen3-4b-instruct-2507", "ministral-3-3b-instruct-2512"), ModelCatalog.all.map { it.modelId }.toSet())
        assertEquals(qwen, ModelCatalog.find("qwen3-4b-instruct-2507"))
        assertEquals(null, ModelCatalog.find("gemma-4-e2b"))
        assertFalse("no default anywhere in the catalog API", ModelCatalog::class.java.methods.any { it.name.contains("default", ignoreCase = true) })
    }

    @Test
    fun qwenIsTheBalancedCandidateWithThePhaseZeroFileVerifiedLive() {
        assertEquals("Qwen3-4B-Instruct-2507", qwen.displayName)
        assertEquals(ModelCategory.BALANCED, qwen.category)
        assertEquals("bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF", qwen.source.repository)
        assertEquals("ae44f08e1392f39c0e474af10c3ff8355c8b6688", qwen.source.revision)
        assertEquals("Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf", qwen.source.fileName)
        assertEquals("https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/ae44f08e1392f39c0e474af10c3ff8355c8b6688/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf", qwen.source.url)
        assertEquals(2_497_280_736L, qwen.approximateDownloadBytes)
        assertEquals("2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e", qwen.expectedSha256)
        assertEquals("Q4_K_M", qwen.quantization)
        assertEquals(4096, qwen.contextSize)
        assertEquals(CpuRequirement.ARM_DOTPROD, qwen.minimumCpu)
        assertTrue(qwen.license.summary.contains("Apache 2.0"))
        assertTrue(qwen.license.url.startsWith("https://"))
        assertTrue("community conversion is said", qwen.source.publisherNote.contains("bartowski"))
        assertTrue(qwen.approximateLoadedMemoryBytes > qwen.approximateDownloadBytes)
    }

    @Test
    fun ministralIsTheSafetyOrientedCandidateWithThePhaseZeroFileVerifiedLive() {
        assertEquals("Ministral 3 3B Instruct 2512", ministral.displayName)
        assertEquals(ModelCategory.SAFETY_ORIENTED, ministral.category)
        assertEquals("mistralai/Ministral-3-3B-Instruct-2512-GGUF", ministral.source.repository)
        assertEquals("eb599d408350ea2bb60452cb86be7c7b2fc28227", ministral.source.revision)
        assertEquals("Ministral-3-3B-Instruct-2512-Q4_K_M.gguf", ministral.source.fileName)
        assertEquals("https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF/resolve/eb599d408350ea2bb60452cb86be7c7b2fc28227/Ministral-3-3B-Instruct-2512-Q4_K_M.gguf", ministral.source.url)
        assertEquals(2_147_023_008L, ministral.approximateDownloadBytes)
        assertEquals("9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8", ministral.expectedSha256)
        assertTrue(ministral.license.summary.contains("Apache 2.0"))
        assertTrue("official publisher is said", ministral.source.publisherNote.contains("Mistral"))
        assertTrue(ministral.approximateDownloadBytes < qwen.approximateDownloadBytes)
        assertTrue(ministral.approximateLoadedMemoryBytes < qwen.approximateLoadedMemoryBytes)
    }

    @Test
    fun descriptionsFollowThePhaseZeroCharacterisationsAndRankNothing() {
        assertTrue(qwen.description.contains("指示") || qwen.description.contains("必要な情報"))
        assertTrue(ministral.description.contains("慎重") || ministral.description.contains("補完"))
        assertEquals("Balanced", qwen.category.label)
        assertEquals("Safety-oriented", ministral.category.label)
        val everything = ModelCatalog.all.flatMap { listOf(it.displayName, it.description) + it.strengths + it.tradeoffs + listOf(it.category.label) }.joinToString(" ")
        listOf("おすすめ", "No.1", "最強", "最高", "一番", "ランキング", "推奨", "デフォルト").forEach { assertFalse("ranking word: $it", everything.contains(it)) }
        ModelCatalog.all.forEach { e ->
            assertTrue("${e.modelId} has strengths and tradeoffs", e.strengths.isNotEmpty() && e.tradeoffs.isNotEmpty())
        }
    }

    @Test
    fun idsFileNamesAndHashesAreSourceControlledAndPathSafe() {
        ModelCatalog.all.forEach { e ->
            assertTrue(e.modelId, Regex("[a-z0-9-]{1,64}").matches(e.modelId))
            assertTrue(e.source.fileName, Regex("[A-Za-z0-9._-]+\\.gguf").matches(e.source.fileName))
            assertFalse(e.source.fileName.contains("/") || e.source.fileName.contains(".."))
            assertTrue(e.expectedSha256, Regex("[0-9a-f]{64}").matches(e.expectedSha256))
            assertTrue(e.source.url.startsWith("https://huggingface.co/${e.source.repository}/resolve/${e.source.revision}/${e.source.fileName}"))
            assertTrue(e.source.revision, Regex("[0-9a-f]{40}").matches(e.source.revision))
        }
        assertEquals("distinct ids", ModelCatalog.all.size, ModelCatalog.all.map { it.modelId }.toSet().size)
        assertEquals("distinct hashes", ModelCatalog.all.size, ModelCatalog.all.map { it.expectedSha256 }.toSet().size)
    }

    @Test
    fun everyEntryHasARuntimeProfileAndTheProfileLoadsFromTheInstalledLayout() {
        ModelCatalog.all.forEach { e ->
            val profiled = ModelProfiles.all.firstOrNull { it.descriptor.id == e.runtimeProfileId }
            assertNotNull("${e.modelId} → runtime profile", profiled)
            assertEquals(e.modelId, profiled!!.descriptor.id)
            assertEquals(ModelLocation.Installed(e.modelId), profiled.descriptor.location)
            assertEquals(e.contextSize, profiled.descriptor.contextSize)
            assertEquals(e.quantization, profiled.descriptor.quantization)
        }
        assertEquals(ModelProfiles.all.map { it.descriptor.id }.toSet(), ModelCatalog.all.map { it.modelId }.toSet())
    }
}
