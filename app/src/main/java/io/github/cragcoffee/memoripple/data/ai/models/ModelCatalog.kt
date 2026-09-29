package io.github.cragcoffee.memoripple.data.ai.models

import io.github.cragcoffee.memoripple.domain.ai.models.CatalogEntry
import io.github.cragcoffee.memoripple.domain.ai.models.CpuRequirement
import io.github.cragcoffee.memoripple.domain.ai.models.ModelCategory
import io.github.cragcoffee.memoripple.domain.ai.models.ModelLicense
import io.github.cragcoffee.memoripple.domain.ai.models.ModelSource

/**
 * The product catalog's two entries (docs/AI_MODEL_MANAGEMENT.md): the vendor and file facts live
 * here in the data layer, as the Phase 2 rule says; the domain sees [CatalogEntry] values only.
 * Every source fact was re-verified live against the publisher on 2026-09-20.
 */
object ModelCatalog {
    val qwen3_4bInstruct2507 = CatalogEntry(
        modelId = "qwen3-4b-instruct-2507",
        displayName = "Qwen3-4B-Instruct-2507",
        description = "指示の理解と、依頼に必要な情報の抽出を重視した候補。",
        category = ModelCategory.BALANCED,
        approximateDownloadBytes = 2_497_280_736L,
        approximateLoadedMemoryBytes = 5_600_000_000L,
        strengths = listOf(
            "意図の読み取りが安定している（評価 190 件で意図一致 95 %）",
            "依頼に必要な項目をそろえやすい（必要項目の充足 89 %）",
        ),
        tradeoffs = listOf(
            "読み込み時のメモリ使用量が高め（約 5.6 GB）",
            "まれに、依頼にない語を補うことがある",
        ),
        source = ModelSource(
            repository = "bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF",
            revision = "ae44f08e1392f39c0e474af10c3ff8355c8b6688",
            fileName = "Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/ae44f08e1392f39c0e474af10c3ff8355c8b6688/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            publisher = "bartowski",
            publisherNote = "bartowski によるコミュニティ変換（Qwen は 2507 Instruct の公式 GGUF を公開していない）。元モデル: Qwen/Qwen3-4B-Instruct-2507",
        ),
        expectedSha256 = "2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e",
        quantization = "Q4_K_M",
        minimumCpu = CpuRequirement.ARM_DOTPROD,
        contextSize = 4096,
        license = ModelLicense("Apache 2.0（元モデル・GGUF とも）", "https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507"),
        runtimeProfileId = "qwen3-4b-instruct-2507",
    )

    val ministral3_3bInstruct2512 = CatalogEntry(
        modelId = "ministral-3-3b-instruct-2512",
        displayName = "Ministral 3 3B Instruct 2512",
        description = "慎重な判断と、余計な補完の少なさを重視した候補。",
        category = ModelCategory.SAFETY_ORIENTED,
        approximateDownloadBytes = 2_147_023_008L,
        approximateLoadedMemoryBytes = 4_750_000_000L,
        strengths = listOf(
            "事実を作り込まない（評価 190 件で重大な失敗 0）",
            "Qwen より少し軽く、最初の応答が少し速い",
        ),
        tradeoffs = listOf(
            "そのまま実行できる提案に至る割合はやや低め（75 %）",
            "足りない情報の確認を求めることが Qwen より多い",
        ),
        source = ModelSource(
            repository = "mistralai/Ministral-3-3B-Instruct-2512-GGUF",
            revision = "eb599d408350ea2bb60452cb86be7c7b2fc28227",
            fileName = "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
            url = "https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF/resolve/eb599d408350ea2bb60452cb86be7c7b2fc28227/Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
            publisher = "Mistral AI",
            publisherNote = "Mistral AI 公式の GGUF。元モデル: mistralai/Ministral-3-3B-Instruct-2512",
        ),
        expectedSha256 = "9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8",
        quantization = "Q4_K_M",
        minimumCpu = CpuRequirement.ARM_DOTPROD,
        contextSize = 4096,
        license = ModelLicense("Apache 2.0（元モデル・GGUF とも）", "https://huggingface.co/mistralai/Ministral-3-3B-Instruct-2512-GGUF"),
        runtimeProfileId = "ministral-3-3b-instruct-2512",
    )

    val all: List<CatalogEntry> = listOf(qwen3_4bInstruct2507, ministral3_3bInstruct2512)

    fun find(modelId: String): CatalogEntry? = all.firstOrNull { it.modelId == modelId }
}
