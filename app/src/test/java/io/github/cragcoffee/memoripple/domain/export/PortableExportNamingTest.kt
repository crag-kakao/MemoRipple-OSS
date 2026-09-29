package io.github.cragcoffee.memoripple.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableExportNamingTest {

    @Test
    fun aTitleBecomesAReadableSegment() {
        assertEquals("旅行の準備", PortableExportNaming.titleSegment("旅行の準備", "無題"))
        assertEquals("plain title", PortableExportNaming.titleSegment("plain title", "無題"))
    }

    @Test
    fun unsafeCharactersLeaveTheSegment() {
        assertEquals("a_b_c", PortableExportNaming.titleSegment("a/b\\c", "無題"))
        val reserved = PortableExportNaming.titleSegment(""":*?"<>|x""", "無題")
        assertFalse(reserved.any { it in ":*?\"<>|" })
        assertFalse(PortableExportNaming.titleSegment("../../etc/passwd", "無題").contains("/"))
        assertFalse(PortableExportNaming.titleSegment("..", "無題").contains("."))
    }

    @Test
    fun dotOnlyAndEmptyTitlesFallBack() {
        assertEquals("無題のメモ", PortableExportNaming.titleSegment("", "無題のメモ"))
        assertEquals("無題のメモ", PortableExportNaming.titleSegment("   ", "無題のメモ"))
        assertEquals("無題のメモ", PortableExportNaming.titleSegment("....", "無題のメモ"))
    }

    @Test
    fun aVeryLongTitleIsCapped() {
        val long = "あ".repeat(500)
        assertTrue(PortableExportNaming.titleSegment(long, "無題").length <= 40)
    }

    @Test
    fun equalTitlesStayApartByShortId() {
        val a = PortableExportNaming.memoFolder("20260831", "同じ題", 1L)
        val b = PortableExportNaming.memoFolder("20260831", "同じ題", 2L)
        assertNotEquals(a, b)
    }

    @Test
    fun photoNamesKeepOrderAndKnowTheirFormat() {
        assertEquals("photo-01.jpg", PortableExportNaming.photoFileName(1, "image/jpeg"))
        assertEquals("photo-02.png", PortableExportNaming.photoFileName(2, "image/png"))
        assertEquals("photo-10.webp", PortableExportNaming.photoFileName(10, "image/webp"))
        // A malformed MIME still yields something a file manager can hold.
        assertEquals("photo-03.img", PortableExportNaming.photoFileName(3, "not/a-real-mime"))
        assertEquals("photo-04.img", PortableExportNaming.photoFileName(4, null))
    }

    @Test
    fun entryPathSafetyRefusesTraversal() {
        assertTrue(PortableExportNaming.isSafeEntryPath("memos/active/a/memo.md"))
        assertFalse(PortableExportNaming.isSafeEntryPath("/memos/memo.md"))
        assertFalse(PortableExportNaming.isSafeEntryPath("memos/../secret"))
        assertFalse(PortableExportNaming.isSafeEntryPath("memos//x"))
        assertFalse(PortableExportNaming.isSafeEntryPath("""memos\x"""))
        assertFalse(PortableExportNaming.isSafeEntryPath(""))
    }

    @Test
    fun chapterAndArchiveNamesAreDeterministic() {
        assertEquals("01-導入.md", PortableExportNaming.chapterFileName(1, "導入"))
        assertEquals(
            "MemoRipple-Export-20260831-1200.zip",
            PortableExportNaming.archiveFileName("20260831-1200"),
        )
    }
}
