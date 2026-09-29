package io.github.cragcoffee.memoripple.ui.settings

import io.github.cragcoffee.memoripple.domain.memos.MemoTemplate
import java.util.concurrent.atomic.AtomicReference

/**
 * A draft on its way to the template editor (「この会話からテンプレートを作成」): offered by the chat just
 * before it navigates, taken once by the editor route as its initial template. Memory only — a
 * process death between the two opens the editor empty, as the brief allows (no draft system).
 */
class TemplateDraftHandoff {
    private val pending = AtomicReference<MemoTemplate?>(null)
    fun offer(draft: MemoTemplate) { pending.set(draft) }
    fun take(): MemoTemplate? = pending.getAndSet(null)
}
