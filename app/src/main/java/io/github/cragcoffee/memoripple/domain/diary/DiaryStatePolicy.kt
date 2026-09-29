package io.github.cragcoffee.memoripple.domain.diary

/**
 * What the four stored states mean now (HANDOFF §16.18). A journal entry is an ordinary editable
 * document; only LOCKED — the state old rows reached through the retired 確定 → 修正 → ロック
 * lifecycle — refuses edits. The names stay on disk so no row is rewritten.
 */
object DiaryStatePolicy {
    fun canTransition(from: DiaryState, to: DiaryState): Boolean =
        when (from) {
            DiaryState.DRAFT -> to == DiaryState.FINALIZED || to == DiaryState.LOCKED
            DiaryState.FINALIZED -> to == DiaryState.CORRECTING || to == DiaryState.LOCKED
            DiaryState.CORRECTING -> to == DiaryState.LOCKED
            DiaryState.LOCKED -> false
        }

    fun canEdit(state: DiaryState): Boolean = state != DiaryState.LOCKED
}
