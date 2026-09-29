package io.github.cragcoffee.memoripple.domain.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A folder's name is the trimmed text; nothing, or only spaces, is not a name. */
class FolderNameTest {
    @Test
    fun aNameIsTrimmedAndKeptOtherwiseAsTyped() {
        assertEquals("開発", FolderName.normalize("  開発 "))
        assertEquals("MemoRipple 仕様", FolderName.normalize("MemoRipple 仕様"))
        assertEquals("a/b", FolderName.normalize("a/b"))
    }

    @Test
    fun emptyAndBlankAreNoName() {
        assertNull(FolderName.normalize(""))
        assertNull(FolderName.normalize("   "))
        assertNull(FolderName.normalize("　\t"))
    }
}
