package io.github.cragcoffee.memoripple.domain.memos

/**
 * Template = conversation script (docs/CHAT_UI_TEMPLATE_V2.md §16): the deterministic engine that
 * turns a template into one question at a time. It knows nothing of a screen, a model or a store:
 * given the template and what has been answered so far, it says what to ask next — the target
 * first when an APPEND asks for one, then the fields in their order — or that everything is
 * there. A skipped optional field is an answer of "" so it is not asked again. No AI, no
 * network; the same engine whether a model is installed or not.
 */
object TemplateScript {
    sealed interface Step {
        /** An APPEND that asks its target at run time: 「どのメモに追記しますか？」 */
        data object AskTarget : Step
        data class Ask(val field: TemplateField, val index: Int, val total: Int) : Step
        /** Every question answered (or skipped): the run may go to its preview. */
        data object Ready : Step
    }

    fun next(template: MemoTemplate, answers: Map<String, String>, target: String?): Step {
        if (template.action == TemplateAction.APPEND && template.targetSpec == TemplateTargetSpec.AskAtRun && target.isNullOrBlank()) return Step.AskTarget
        val pending = template.fields.withIndex().firstOrNull { (_, f) -> f.key !in answers } ?: return Step.Ready
        return Step.Ask(pending.value, pending.index, template.fields.size)
    }

    /** The field's own question, or one made from its label; an optional field says it may be skipped. */
    fun questionOf(field: TemplateField): String {
        val q = field.question.trim().ifBlank { "${field.label}を入力してください" }
        return q
    }

    /** The first question is introduced by the template's name; the rest follow まず / 次に / 最後に as the brief's example reads. */
    fun spoken(template: MemoTemplate, step: Step.Ask, first: Boolean): String {
        val q = questionOf(step.field)
        val lead = when {
            step.total <= 1 -> ""
            step.index == 0 -> "まず、"
            step.index == step.total - 1 -> "最後に、"
            else -> "次に、"
        }
        val skip = if (!step.field.required && step.field.type != TemplateFieldType.BOOLEAN) "（なければスキップできます）" else ""
        return (if (first) "${template.name}を始めます。\n" else "") + lead + q + skip
    }

    const val TARGET_QUESTION = "どのメモに追記しますか？"

    /** The APPEND's own target question, or the generic one; the name of a memo is what is asked for either way. */
    fun targetQuestionOf(template: MemoTemplate): String = template.targetQuestion.trim().ifBlank { TARGET_QUESTION } + "名前を教えてください。"

    /** What a chip answer reads as in the transcript: the user's words, never a token. */
    fun spokenAnswer(field: TemplateField, value: String): String = when (field.type) {
        TemplateFieldType.DATE -> when (value.uppercase()) { "TODAY" -> "今日"; "YESTERDAY" -> "昨日"; else -> value }
        TemplateFieldType.BOOLEAN -> if (value.lowercase() in setOf("true", "yes", "1", "はい", "on")) "はい" else "いいえ"
        else -> value
    }
}
