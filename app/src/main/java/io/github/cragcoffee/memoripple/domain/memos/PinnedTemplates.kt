package io.github.cragcoffee.memoripple.domain.memos

import kotlinx.coroutines.flow.Flow

/**
 * Template pins (Review Batch 2, 2026-09-22): the templates the user keeps at the top of the ＋
 * picker — **ids only**, in a light preference, never a copy of a template. A starter, a custom or
 * an imported template pins alike; an id that names no template (a deleted custom one) is simply
 * skipped; a pinned template is shown once, in the ピン留め section, and left out of the others.
 * Not in the portable backup: a preference of this device, like the recent list and the drawer's
 * pins (docs/CHAT_UI_TEMPLATE_V2.md §18).
 */
object PinnedTemplates {
    /** The pinned templates, in the pin order, skipping ids that no longer name one. */
    fun resolve(pinnedIds: Collection<String>, templates: List<MemoTemplate>): List<MemoTemplate> =
        pinnedIds.mapNotNull { id -> templates.firstOrNull { it.id == id } }

    /** [templates] without the pinned ones — what the other sections list. */
    fun without(templates: List<MemoTemplate>, pinnedIds: Set<String>): List<MemoTemplate> = templates.filterNot { it.id in pinnedIds }

    fun toggle(pinnedIds: Set<String>, id: String): Set<String> = if (id in pinnedIds) pinnedIds - id else pinnedIds + id
}

/** Where the pins live (a preference); the chat reads the ids and toggles one. */
interface PinnedTemplateStore {
    val pinnedIds: Flow<List<String>>
    suspend fun setPinned(id: String, pinned: Boolean)
}
