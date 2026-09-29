package io.github.cragcoffee.memoripple.ui.components

/**
 * What the two imports say — the wall's 「Markdownを読み込む」 and settings' 「書き出したZIPを取り込む」.
 *
 * Both bring back memos and outlines alike (docs/OUTLINE_EXPORT_IMPORT.md), so what they count is
 * a **ドキュメント**, never a メモ: one word that is right for a memo, an outline or a mix of both, with
 * no sentence branching on the kind. What the import leaves out (journals, notes, comments) and what
 * it could not bring (a photo it could not read) is still said.
 */
object ImportWording {

    // The wall's Markdown import.

    fun markdownConfirmTitle(count: Int): String = "${count}件のドキュメントを読み込みますか？"

    fun markdownConfirmBody(withTags: Boolean): String = buildString {
        append("新しいドキュメントとして追加します。今あるドキュメントは変わりません。")
        if (withTags) append("ファイルが指定したタグは、無ければ作成して付けます。")
    }

    const val MARKDOWN_NOTHING_TO_READ = "読み込めるドキュメントがありませんでした"

    fun markdownDone(count: Int): String = "${count}件を読み込みました"

    // The portable ZIP import.

    fun portablePreview(documents: Int, photos: Int): String = buildString {
        append("ドキュメント${documents}件")
        if (photos > 0) append("と写真${photos}枚")
        append("を新しく取り込みます。\n")
        append("既存のデータは変更されません。")
        append("日記・ノート・コメントはこの取り込みの対象外です。")
    }

    fun portableDone(documents: Int, photos: Int, skippedPhotos: Int): String = buildString {
        append("ドキュメント${documents}件")
        if (photos > 0) append("・写真${photos}枚")
        append("を取り込みました")
        if (skippedPhotos > 0) append("（取り込めなかった写真が${skippedPhotos}枚あります）")
    }

    const val PORTABLE_CANCELLED = "取り込みを中止しました（途中まで取り込んだドキュメントは残ります）"

    const val PORTABLE_NOT_AN_EXPORT = "読める形式の書き出しZIPではないか、ドキュメントが入っていません"

    const val PORTABLE_ROW_DESCRIPTION = "読める形式のZIPからドキュメントを新規追加（既存データは変更されません）"
}
