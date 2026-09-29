package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.data.MemoEntity

/**
 * What a memo is: a free-form メモ, or an アウトライン made in the outliner.
 *
 * Decided when the memo is made and never read off its body: a memo that happens to be
 * written as `- 項目 / - 子` is still a memo, and stays on the memo wall. The stored id is a
 * wire contract shared by the Room column and the backup field; ordinals are never stored.
 */
enum class MemoKind(val storageId: String) {
    MEMO("memo"),
    OUTLINE("outline"),
    ;

    companion object {
        fun fromStorageId(value: String): MemoKind? = entries.firstOrNull { it.storageId == value }
    }
}

/** The kind as the app reads it. A value it does not know reads as a memo, not as a crash. */
val MemoEntity.memoKind: MemoKind
    get() = MemoKind.fromStorageId(kind) ?: MemoKind.MEMO

val MemoEntity.isOutline: Boolean
    get() = memoKind == MemoKind.OUTLINE
