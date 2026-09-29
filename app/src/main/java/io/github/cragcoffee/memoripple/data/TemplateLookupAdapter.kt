package io.github.cragcoffee.memoripple.data

import io.github.cragcoffee.memoripple.domain.ai.TemplateLookup
import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import io.github.cragcoffee.memoripple.domain.memos.StarterTemplates

/** The AI Resolver's view of the templates: the user's own by id, then by name; then the built-in starters. No new schema. */
class TemplateLookupAdapter(private val repository: TemplateRepository) : TemplateLookup {
    override suspend fun find(idOrName: String): MemoTemplate? {
        val all = repository.current()
        return all.firstOrNull { it.id == idOrName } ?: all.firstOrNull { it.name.trim() == idOrName.trim() } ?: StarterTemplates.find(idOrName)
    }
}
