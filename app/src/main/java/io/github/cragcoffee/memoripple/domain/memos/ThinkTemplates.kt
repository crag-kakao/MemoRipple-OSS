package io.github.cragcoffee.memoripple.domain.memos

import java.time.LocalDate

/** What a Think conversation ends in: the rendered body, and its first line as the memo's title should the user save. */
data class ThinkResult(val text: String, val title: String)

/**
 * Think templates (docs/THINK_TEMPLATES.md, 2026-09-22): a template whose flow is THINK asks its
 * questions like any other and then shows a *result* instead of a preview — the body rendered
 * by `{{key}}` substitution and nothing else, `{{today}}` filled by the clock, a skipped question
 * reading 「（なし）」. Deterministic: the same answers give the same words, with or without a model;
 * no summary is asked of anything. The result has no authority: it writes nothing, and a save is
 * the user's tap into the ordinary CREATE path. The picker's three sections come from here too.
 */
object ThinkTemplates {
    const val NONE = "（なし）"

    /** The picker's grouping (記録 / 整理・壁打ち / 探す) — from what the template is, never from a list of ids. */
    enum class Section { RECORD, THINK, SEARCH }

    fun sectionOf(t: MemoTemplate): Section = when {
        t.flow == TemplateFlow.THINK -> Section.THINK
        t.action == TemplateAction.SEARCH -> Section.SEARCH
        else -> Section.RECORD
    }

    /**
     * The result of [template] for [answers] on [today]. The script asks every question first, so
     * every key is there; a blank (a skip) reads 「（なし）」, and so would a missing one — nothing is
     * ever guessed in its place. A placeholder no field declares stays as typed.
     */
    fun result(template: MemoTemplate, answers: Map<String, String>, today: LocalDate): ThinkResult {
        val values = LinkedHashMap<String, String>()
        values[TemplateValues.TODAY_KEY] = today.toString()
        template.fields.forEach { f ->
            val raw = answers[f.key]?.trim().orEmpty().ifEmpty { f.default.trim() }
            values[f.key] = when {
                raw.isEmpty() -> NONE
                f.type == TemplateFieldType.DATE -> TemplateValues.formatDate(raw, today) ?: raw
                f.type == TemplateFieldType.BOOLEAN -> TemplateScript.spokenAnswer(f, raw)
                else -> raw
            }
        }
        val text = when (val rendered = TemplateRenderer.render(template.body, values)) {
            is TemplateRendering.Rendered -> rendered.text
            is TemplateRendering.UnknownPlaceholder -> template.body
        }
        val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return ThinkResult(text, title)
    }
}
