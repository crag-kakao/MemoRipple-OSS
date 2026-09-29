package io.github.cragcoffee.memoripple.data

import io.github.cragcoffee.memoripple.domain.settings.UserCommentFont
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A font file nothing in the catalog names any more is an orphan — left by a crash between
 * the copy and the catalog write, or by a catalog that was capped after the copy. The store
 * removes exactly those and touches nothing the catalog still lists.
 */
class CommentFontStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun filesTheCatalogDoesNotNameAreRemovedAndListedOnesStay() = runBlocking {
        val root = File(temporaryFolder.root, "comment_fonts").apply { mkdirs() }
        val listed = File(root, "aaa.ttf").apply { writeText("f") }
        val orphan = File(root, "bbb.otf").apply { writeText("f") }
        val store = CommentFontStore(temporaryFolder.root)

        val removed = store.removeUnlisted(listOf(UserCommentFont("aaa", "A", "aaa.ttf")))

        assertEquals(1, removed)
        assertTrue(listed.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun aStoreWithNoDirectoryRemovesNothing() = runBlocking {
        assertEquals(0, CommentFontStore(temporaryFolder.root).removeUnlisted(emptyList()))
    }
}
