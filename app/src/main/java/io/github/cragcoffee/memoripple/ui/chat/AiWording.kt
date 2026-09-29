package io.github.cragcoffee.memoripple.ui.chat

import io.github.cragcoffee.memoripple.domain.ai.AiFailureStage
import io.github.cragcoffee.memoripple.domain.ai.AiIntent
import io.github.cragcoffee.memoripple.domain.ai.AiInteractionResult
import io.github.cragcoffee.memoripple.domain.ai.AiRejection
import io.github.cragcoffee.memoripple.domain.ai.AiTiming
import io.github.cragcoffee.memoripple.domain.ai.ModelAvailability
import io.github.cragcoffee.memoripple.domain.ai.CommandPreview
import io.github.cragcoffee.memoripple.domain.ai.ModelUnavailableReason
import io.github.cragcoffee.memoripple.domain.ai.ProposalField
import io.github.cragcoffee.memoripple.domain.ai.WriteOutcome
import io.github.cragcoffee.memoripple.domain.ai.conversation.ChatMessageKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary

/**
 * The AI mode's words, in one place: what a card says, and (Phase 8) the one line the transcript
 * keeps for what the user saw. Only user-visible sentences — never a raw answer, a developer
 * detail or an id.
 */
internal object AiWording {
    const val CANCELLED = "キャンセルしました。"

    val DocumentKind.chatLabel: String
        get() = when (this) {
            DocumentKind.MEMO -> "メモ"
            DocumentKind.OUTLINE -> "アウトライン"
            DocumentKind.JOURNAL -> "日記"
        }

    fun opened(summary: DocumentSummary): String = "${summary.ref.kind.chatLabel}「${summary.title}」を開きました。"

    fun needsInformationTitle(r: AiInteractionResult.NeedsInformation): String {
        val target = ProposalField.TARGET_NAME in r.fields || ProposalField.TARGET_REF in r.fields
        return when {
            r.intent == AiIntent.APPEND && target -> "追記先が分かりません"
            r.intent == AiIntent.OPEN && target -> "開く対象が分かりません"
            r.intent == AiIntent.APPEND && ProposalField.TEXT in r.fields -> "追記する内容が分かりません"
            r.intent == AiIntent.CREATE -> "メモ・アウトライン・日記のどれを作るか分かりません"
            r.intent == AiIntent.USE_TEMPLATE -> "どのテンプレートを使うか分かりません"
            r.intent == AiIntent.SEARCH -> "何を探すか分かりません"
            else -> "足りない情報があります"
        }
    }

    fun notFoundTitle(r: AiInteractionResult.NotFound): String = when (r.field) {
        ProposalField.TEMPLATE_ID -> "そのテンプレートはありません"
        ProposalField.TARGET_REF -> "その記録は見つかりません（削除された可能性があります）"
        else -> "その名前の記録は見つかりません"
    }

    /** A rejection, in the user's words: no code, no internal name. */
    val AiRejection.explanation: String
        get() = when (this) {
            AiRejection.UNKNOWN_INTENT -> "扱えない依頼です。"
            AiRejection.UNBOUNDED_SEARCH -> "検索する言葉か日付が必要です。"
            AiRejection.REF_NOT_IN_CONTEXT -> "表示中の候補にない項目が指定されました。"
            AiRejection.BLANK_TEXT -> "追記する内容が空です。"
            AiRejection.KIND_MISMATCH -> "指定された種類と対象が一致しません。"
            AiRejection.TARGET_READ_ONLY -> "この日記は確定済みのため追記できません。"
            AiRejection.TARGET_NOT_FOUND -> "対象が見つかりません。"
            AiRejection.FUTURE_DATE -> "未来の日付は扱えません。"
        }

    fun unavailable(reason: ModelUnavailableReason): Pair<String, String> = when (reason) {
        ModelUnavailableReason.NO_MODEL_CONFIGURED ->
            "自由な文章での操作にはLocal AIモデルが必要です" to "「AIモデルを設定」から選ぶと、メモや日記を自然な言葉で検索・操作できます（モデルは端末内で動き、書き込みはあなたが確認したときだけ）。＋のテンプレートはそのまま使えます（モデルは要りません）。"
        ModelUnavailableReason.MODEL_FILE_MISSING ->
            "選択したAIモデルのファイルが見つかりません" to "「AIモデルを設定」からダウンロードし直すか、別のモデルを選択してください。＋のテンプレートはそのまま使えます。"
        ModelUnavailableReason.MODEL_FILE_CORRUPT ->
            "選択したAIモデルのファイルが壊れています" to "「AIモデルを設定」から再試行してダウンロードし直してください。＋のテンプレートはそのまま使えます。"
        ModelUnavailableReason.UNSUPPORTED_DEVICE ->
            "この端末ではLocal AIを利用できません" to "AIモデルの実行に必要なCPU機能に対応していません。＋のテンプレートはそのまま使えます。"
    }

    /** UI review 2026-09-21: the subtitle under the chat's title — the model that answers free text, or that none does. */
    const val NO_MODEL = "AIモデルなし"
    fun modelSubtitle(availability: ModelAvailability?): String = when (availability) {
        null -> ""
        is ModelAvailability.Available -> availability.model.displayName
        is ModelAvailability.Unavailable -> when (availability.reason) {
            ModelUnavailableReason.NO_MODEL_CONFIGURED -> NO_MODEL
            ModelUnavailableReason.MODEL_FILE_MISSING, ModelUnavailableReason.MODEL_FILE_CORRUPT -> "AIモデルを利用できません"
            ModelUnavailableReason.UNSUPPORTED_DEVICE -> "この端末ではLocal AIを利用できません"
        }
    }

    /** The meta line under an answer, as the reference app reads it: ms per token, tokens per second, time to the first token. */
    fun timing(t: AiTiming): String {
        val perToken = if (t.millisPerToken >= 10) "%.0f".format(java.util.Locale.ROOT, t.millisPerToken) else "%.1f".format(java.util.Locale.ROOT, t.millisPerToken)
        val perSecond = "%.2f".format(java.util.Locale.ROOT, t.tokensPerSecond)
        return "${perToken}ms/トークン, $perSecond トークン/秒, ${t.ttftMillis}ms TTFT"
    }

    fun runtimeErrorTitle(stage: AiFailureStage): String = when (stage) {
        AiFailureStage.LOAD -> "AIモデルを読み込めませんでした"
        AiFailureStage.GENERATION -> "AIが応答できませんでした"
        AiFailureStage.PARSE -> "AIの応答を理解できませんでした"
    }

    const val THERMAL_TITLE = "端末が熱くなっているため、AIを一時停止しています"

    private fun verbOf(preview: CommandPreview): String = when (preview) {
        is CommandPreview.Create, is CommandPreview.Template -> "作成"
        is CommandPreview.Append -> "追記"
    }

    /** The card of a confirmed write: tag, title, body. */
    fun written(outcome: WriteOutcome, preview: CommandPreview): Triple<String, String, String> {
        val verb = verbOf(preview)
        return when (outcome) {
            is WriteOutcome.Success -> Triple("chat_ai_write_success", "${verb}しました", "${outcome.ref.kind.chatLabel}を開きました。")
            WriteOutcome.Conflict -> Triple("chat_ai_write_conflict", "内容が変更されたため、${verb}できませんでした", "もう一度内容を確認してください。現在のデータは変更されていません。")
            WriteOutcome.ReadOnly -> Triple("chat_ai_write_read_only", "この日記は確定済みのため、${verb}できませんでした", "現在のデータは変更されていません。")
            WriteOutcome.NotFound -> Triple("chat_ai_write_not_found", "対象が見つからないため、${verb}できませんでした", "現在のデータは変更されていません。")
            is WriteOutcome.Rejected -> Triple("chat_ai_write_rejected", "この内容は${verb}できませんでした", "日付や対象を確かめて、もう一度頼んでみてください。現在のデータは変更されていません。")
            is WriteOutcome.Failed -> Triple("chat_ai_write_failed", "${verb}に失敗しました", "もう一度お試しください。現在のデータは変更されていません。")
            WriteOutcome.AlreadyExecuted -> Triple("chat_ai_write_failed", "この操作はすでに実行されています", "新しく頼み直してください。")
        }
    }

    /** The transcript line of a confirmed write: 「メモを作成しました。」 / 「追記しました。」 or the card's title. */
    fun writtenText(outcome: WriteOutcome, preview: CommandPreview): String = when (outcome) {
        is WriteOutcome.Success -> if (preview is CommandPreview.Append) "追記しました。" else "${outcome.ref.kind.chatLabel}を作成しました。"
        else -> written(outcome, preview).second + "。"
    }

    /** How a result is drawn in the transcript. */
    fun kindOf(result: AiInteractionResult): ChatMessageKind = when (result) {
        is AiInteractionResult.SearchResults, is AiInteractionResult.Open, is AiInteractionResult.Ambiguous, is AiInteractionResult.ThinkResult -> ChatMessageKind.RESULT
        is AiInteractionResult.WritePreview, is AiInteractionResult.Written -> ChatMessageKind.WRITE_EVENT
        is AiInteractionResult.TemplateForm -> ChatMessageKind.TEXT
        is AiInteractionResult.ThermalBlocked, is AiInteractionResult.ModelUnavailable, is AiInteractionResult.RuntimeError -> ChatMessageKind.FAILURE
        else -> ChatMessageKind.TEXT
    }

    /** The one line the transcript keeps for a result — what the user saw, nothing internal. */
    fun assistantText(result: AiInteractionResult): String = when (result) {
        is AiInteractionResult.SearchResults -> if (result.results.isEmpty()) "見つかりませんでした。" else "${result.results.size}件見つかりました。"
        is AiInteractionResult.Open -> opened(result.target)
        is AiInteractionResult.Ambiguous -> "同じ名前の記録が${result.candidates.size}件あります。ひとつ選んでください。"
        is AiInteractionResult.WritePreview -> when (val p = result.preview) {
            is CommandPreview.Create -> "${p.kind.chatLabel}の作成内容を確認してください。"
            is CommandPreview.Append -> "${p.target.ref.kind.chatLabel}「${p.target.title}」への追記内容を確認してください。"
            is CommandPreview.Template -> "ありがとうございます。この内容で${p.kind.chatLabel}を作成します。"
        }
        is AiInteractionResult.Written -> writtenText(result.outcome, result.preview)
        is AiInteractionResult.NeedsInformation -> needsInformationTitle(result) + "。"
        is AiInteractionResult.NotFound -> notFoundTitle(result) + "。"
        is AiInteractionResult.Invalid -> "この依頼は実行できません。" + result.reasons.joinToString("") { it.explanation }
        AiInteractionResult.Unknown -> "この依頼はまだ扱えません。"
        AiInteractionResult.ThermalBlocked -> "$THERMAL_TITLE。"
        is AiInteractionResult.ModelUnavailable -> unavailable(result.reason).first + "。"
        is AiInteractionResult.RuntimeError -> runtimeErrorTitle(result.stage) + "。"
        is AiInteractionResult.TemplateForm -> "「${result.template.name}」の項目を入力してください。"
        // Think (docs/THINK_TEMPLATES.md): the result itself is the line — the words the user will read back, never a key
        is AiInteractionResult.ThinkResult -> THINK_RESULT_LEAD + "\n\n" + result.text.trimEnd()
    }

    const val THINK_RESULT_LEAD = "整理すると、こんな内容です。"
}
