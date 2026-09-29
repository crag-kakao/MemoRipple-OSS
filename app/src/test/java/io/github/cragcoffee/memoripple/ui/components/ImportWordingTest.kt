package io.github.cragcoffee.memoripple.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The imports count ドキュメント — right for a memo, an outline and a mix — and still say what was left out. */
class ImportWordingTest {

    private val everything = listOf(
        ImportWording.markdownConfirmTitle(1),
        ImportWording.markdownConfirmTitle(3),
        ImportWording.markdownConfirmBody(withTags = false),
        ImportWording.markdownConfirmBody(withTags = true),
        ImportWording.MARKDOWN_NOTHING_TO_READ,
        ImportWording.markdownDone(2),
        ImportWording.portablePreview(2, 5),
        ImportWording.portablePreview(2, 0),
        ImportWording.portableDone(2, 5, 0),
        ImportWording.portableDone(2, 5, 1),
        ImportWording.PORTABLE_CANCELLED,
        ImportWording.PORTABLE_NOT_AN_EXPORT,
        ImportWording.PORTABLE_ROW_DESCRIPTION,
    )

    @Test
    fun theConfirmationCountsDocuments() {
        assertEquals("1件のドキュメントを読み込みますか？", ImportWording.markdownConfirmTitle(1))
        assertEquals("3件のドキュメントを読み込みますか？", ImportWording.markdownConfirmTitle(3))
    }

    @Test
    fun theResultCountsDocumentsAndKeepsWhatWasSkipped() {
        assertEquals("ドキュメント2件・写真5枚を取り込みました", ImportWording.portableDone(2, 5, 0))
        assertEquals("ドキュメント2件を取り込みました", ImportWording.portableDone(2, 0, 0))
        assertEquals(
            "ドキュメント2件・写真4枚を取り込みました（取り込めなかった写真が1枚あります）",
            ImportWording.portableDone(2, 4, 1),
        )
    }

    @Test
    fun thePortablePreviewStillSaysWhatIsLeftOut() {
        assertEquals(
            "ドキュメント2件と写真5枚を新しく取り込みます。\n既存のデータは変更されません。日記・ノート・コメントはこの取り込みの対象外です。",
            ImportWording.portablePreview(2, 5),
        )
    }

    @Test
    fun noImportSentenceCallsWhatItBringsAMemo() {
        everything.forEach { assertFalse(it, it.contains("メモ")) }
    }
}
