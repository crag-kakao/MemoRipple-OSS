package io.github.cragcoffee.memoripple.domain.memos

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** One recent use of a template: its id and when — never the template, never the values typed. */
@Serializable
data class RecentTemplate(val templateId: String, val lastUsedAt: Long)

/** The rule for the 最近使ったテンプレート list: newest first, one entry per template, at most [MAX_RECENT]. */
object RecentTemplates {
    const val MAX_RECENT = 5

    fun push(existing: List<RecentTemplate>, templateId: String, at: Long): List<RecentTemplate> =
        (listOf(RecentTemplate(templateId, at)) + existing.filterNot { it.templateId == templateId })
            .sortedByDescending { it.lastUsedAt }
            .take(MAX_RECENT)

    /** The templates the list points at, in order; an id that no longer exists is simply skipped. */
    fun resolve(recent: List<RecentTemplate>, templates: List<MemoTemplate>): List<MemoTemplate> =
        recent.mapNotNull { r -> templates.firstOrNull { it.id == r.templateId } }
}

/** Where the recent list lives (a light preference); recorded when a template runs. */
interface RecentTemplateStore {
    val recent: Flow<List<RecentTemplate>>
    suspend fun record(templateId: String, at: Long)
    suspend fun clear()
}
