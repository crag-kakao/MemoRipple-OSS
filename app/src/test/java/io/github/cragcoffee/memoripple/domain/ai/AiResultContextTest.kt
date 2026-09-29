package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import io.github.cragcoffee.memoripple.domain.documents.DocumentRef
import io.github.cragcoffee.memoripple.domain.documents.DocumentSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** RED 21, 22, 43: result_N maps to a DocumentRef only inside the request; nothing about it is persisted. */
class AiResultContextTest {
    private val results = listOf(
        DocumentSummary(DocumentRef(DocumentKind.MEMO, 7), "会議のメモ", 1, 1),
        DocumentSummary(DocumentRef(DocumentKind.JOURNAL, 3), "9/17 の日記", 2, 2),
    )

    @Test
    fun result1MapsToTheFirstShownDocumentAndOnlyTheResolverSeesTheRef() {
        val ctx = AiResultContext.of(results)
        assertEquals(2, ctx.size)
        assertEquals(DocumentRef(DocumentKind.MEMO, 7), ctx.ref(AiResultRef(1)))
        assertEquals(DocumentRef(DocumentKind.JOURNAL, 3), ctx.ref(AiResultRef(2)))
        assertEquals("会議のメモ", ctx.summary(AiResultRef(1))?.title)
        // what the model is shown carries the label and the title, never the id
        assertEquals(listOf("result_1: 会議のメモ", "result_2: 9/17 の日記"), ctx.shownLines())
        assertFalse(ctx.shownLines().any { it.contains("7") && it.contains("MEMO") })
    }

    @Test
    fun aRefBeyondTheShownListResolvesToNothing() {
        val ctx = AiResultContext.of(results)
        assertNull(ctx.ref(AiResultRef(3)))
        assertFalse(AiResultRef(3) in ctx)
        assertTrue(AiResultRef(2) in ctx)
        assertNull(AiResultContext.EMPTY.ref(AiResultRef(1)))
    }

    @Test
    fun theContextIsRequestScopedAndNeverPersisted() {
        // not serializable, not parcelable, no storage import in the package
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(AiResultContext::class.java))
        assertTrue(AiResultContext::class.java.interfaces.none { it.simpleName == "Parcelable" })
        val dir = sequenceOf(File("src/main/java/io/github/cragcoffee/memoripple/domain/ai"), File("app/src/main/java/io/github/cragcoffee/memoripple/domain/ai")).first { it.isDirectory }
        val sources = dir.listFiles { f -> f.extension == "kt" }!!.joinToString("\n") { it.readText() }
        for (forbidden in listOf("DataStore", "androidx.room", "SharedPreferences", "java.io.File", "Serializable", "Parcelable", "SavedStateHandle"))
            assertFalse("domain/ai must not mention $forbidden", sources.contains(forbidden))
    }
}
