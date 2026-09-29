package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A template (docs/CHAT_UI_TEMPLATE_V2.md): a declarative, reusable action — CREATE a document
 * from a body, SEARCH by a fixed query, APPEND a rendered text to a target — with typed fields
 * the user fills in and `{{key}}` placeholders the app substitutes. Nothing here is executable:
 * no expression, no script, no code; a template runs only through the same preview and Human
 * Confirmation an AI write goes through.
 *
 * A legacy template (Phase 8 and before: name + body) is a CREATE MEMO template with no fields —
 * every new part has a default, so old JSON decodes unchanged and the three-argument constructor
 * still makes the old shape.
 */
@Serializable
data class MemoTemplate(
    val id: String,
    val name: String,
    /** CREATE: the document body; APPEND: the text appended. Placeholders `{{key}}` refer to [fields]. */
    val body: String,
    val description: String = "",
    val action: TemplateAction = TemplateAction.CREATE,
    val documentKind: DocumentKind = DocumentKind.MEMO,
    val fields: List<TemplateField> = emptyList(),
    val searchSpec: TemplateSearchSpec? = null,
    val targetSpec: TemplateTargetSpec? = null,
    /** Conversation script (§16): what an APPEND that asks its target says — empty → the generic question. */
    val targetQuestion: String = "",
    /**
     * How the script ends (docs/THINK_TEMPLATES.md): RECORD runs the action at its preview; THINK
     * shows the rendered body as a result in the conversation and writes nothing until the user
     * chooses 「メモとして保存」 — then the same CREATE path. A flow, not an action: a Think template
     * is a CREATE MEMO template underneath, which is what an older reader sees.
     */
    val flow: TemplateFlow = TemplateFlow.RECORD,
    /**
     * The one flat template folder this (custom) template sits in — a [TemplateFolder] id — or null
     * for 未分類 (2026-09-22). Template organisation only: never a document folder, never in the
     * template file (a folder id is this device's); an id that names no folder reads as 未分類. A
     * starter is classified by what it is and carries none.
     */
    val folderId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val formatVersion: Int = FORMAT_VERSION,
) {
    /** The Phase 8 shape: CREATE MEMO, no fields, no specs — what the format-18 backup carries whole. */
    val isLegacyShape: Boolean
        get() = action == TemplateAction.CREATE && documentKind == DocumentKind.MEMO && fields.isEmpty() && searchSpec == null && targetSpec == null && flow == TemplateFlow.RECORD

    companion object {
        const val FORMAT_VERSION = 1
    }
}

enum class TemplateAction { CREATE, SEARCH, APPEND }

/** What the script does when every question is answered: record (the action's preview) or think (a result, saved only on request). */
enum class TemplateFlow { RECORD, THINK }

enum class TemplateFieldType { TEXT, MULTILINE, DATE, CHOICE, BOOLEAN }

/**
 * One value the user gives when a template runs. [default] is text: for DATE a token (`TODAY`,
 * `YESTERDAY`) or an ISO date; for BOOLEAN `true` / `false`; for CHOICE one of [choices].
 */
@Serializable
data class TemplateField(
    val key: String,
    val label: String,
    val type: TemplateFieldType,
    val required: Boolean = false,
    val default: String = "",
    val choices: List<String> = emptyList(),
    /** Conversation script (§16): the question the chat asks for this field; empty → asked by its label. */
    val question: String = "",
)

/** A SEARCH template's fixed query — the same shape the AI's SEARCH resolves to; placeholders allowed in [query]. */
@Serializable
data class TemplateSearchSpec(
    val query: String = "",
    val dateToken: TemplateDateToken? = null,
    val kinds: Set<DocumentKind> = setOf(DocumentKind.MEMO, DocumentKind.OUTLINE, DocumentKind.JOURNAL),
)

/** The date words a template may carry — resolved by the app's clock when it runs, never stored as a date. */
enum class TemplateDateToken { TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK }

/** Where an APPEND template writes: a document named once (resolved by search every run), or a name asked when it runs. Never an id. */
@Serializable
sealed class TemplateTargetSpec {
    @Serializable
    @SerialName("named")
    data class Named(val name: String) : TemplateTargetSpec()

    @Serializable
    @SerialName("ask")
    data object AskAtRun : TemplateTargetSpec()

    companion object {
        /** The value key the run-time target name travels under. */
        const val TARGET_KEY = "__target"
    }
}

/** Rules a template list obeys, kept away from where it happens to be stored. */
object MemoTemplatePolicy {

    const val MAX_TEMPLATES = 30
    const val MAX_NAME_CHARS = 40

    /** A template with no body is a template that does nothing, so it is refused. */
    fun isUsable(name: String, body: String): Boolean =
        name.isNotBlank() && body.isNotBlank()

    fun cleanName(name: String, fallback: String = "テンプレート"): String =
        name.trim().take(MAX_NAME_CHARS).ifBlank { fallback }

    /**
     * Puts [template] into [existing], replacing one with the same id. The newest sits first, and
     * the list stops growing at [MAX_TEMPLATES] so it stays something a person can read through.
     */
    fun upsert(existing: List<MemoTemplate>, template: MemoTemplate): List<MemoTemplate> =
        (listOf(template) + existing.filterNot { it.id == template.id }).take(MAX_TEMPLATES)

    /** An import: each incoming template replaces the one with its id, newest first, still capped. */
    fun upsertAll(existing: List<MemoTemplate>, incoming: List<MemoTemplate>): List<MemoTemplate> =
        incoming.fold(existing) { acc, t -> upsert(acc, t) }

    fun remove(existing: List<MemoTemplate>, id: String): List<MemoTemplate> =
        existing.filterNot { it.id == id }
}

/**
 * Removing one of the user's own templates (2026-09-23, the user's review): the one template
 * mutation a screen outside the editor may ask for. A starter is code and cannot be removed; a
 * template that no longer exists simply is not there any more, and no memo, journal or outline is
 * touched by this.
 */
interface TemplateRemover {
    suspend fun remove(id: String)
}
