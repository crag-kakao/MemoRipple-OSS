package io.github.cragcoffee.memoripple.ui.memos

/**
 * The panels the editors' shortcut bar can raise — テンプレート, メモへのリンク, コメントリンク.
 * An editor holds at most one of them open, so the bar can never stack two and leave one
 * behind a ×: a tap on another tool swaps the panel, a tap on the open tool closes it.
 */
enum class EditorShortcutPanel {
    TEMPLATE,
    MEMO_LINK,
    COMMENT_LINK,
    ;

    companion object {
        /** The panel after a tap on [tapped] while [current] is open (null = none). */
        fun toggle(current: EditorShortcutPanel?, tapped: EditorShortcutPanel): EditorShortcutPanel? =
            if (current == tapped) null else tapped
    }
}
