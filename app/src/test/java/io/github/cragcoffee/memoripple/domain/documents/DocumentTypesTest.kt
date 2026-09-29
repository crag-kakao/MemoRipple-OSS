package io.github.cragcoffee.memoripple.domain.documents

import io.github.cragcoffee.memoripple.domain.memos.MemoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The shared vocabulary: kinds, refs, destinations — and what they must not know. */
class DocumentTypesTest {
    @Test
    fun documentKindOfMemoKindIsTheOnlyBridgeAndCoversBothMemoKinds() {
        assertEquals(DocumentKind.MEMO, DocumentKind.of(MemoKind.MEMO))
        assertEquals(DocumentKind.OUTLINE, DocumentKind.of(MemoKind.OUTLINE))
        assertEquals(MemoKind.MEMO, DocumentKind.MEMO.memoKind)
        assertEquals(MemoKind.OUTLINE, DocumentKind.OUTLINE.memoKind)
        assertEquals(null, DocumentKind.JOURNAL.memoKind)
        assertEquals(listOf("MEMO", "OUTLINE", "JOURNAL"), DocumentKind.entries.map { it.name })
    }

    @Test
    fun aRefMeansSomethingOnlyAsKindPlusId() {
        val memo5 = DocumentRef(DocumentKind.MEMO, 5)
        assertEquals(memo5, DocumentRef(DocumentKind.MEMO, 5))
        assertNotEquals(memo5, DocumentRef(DocumentKind.JOURNAL, 5))
        assertNotEquals(memo5, DocumentRef(DocumentKind.OUTLINE, 5))
        assertEquals(3, setOf(memo5, DocumentRef(DocumentKind.OUTLINE, 5), DocumentRef(DocumentKind.JOURNAL, 5)).size)
    }

    @Test
    fun eachKindOpensOnItsOwnScreenKindWithoutARouteString() {
        assertEquals(DocumentDestination.MemoEditor(7), DocumentRef(DocumentKind.MEMO, 7).destination())
        assertEquals(DocumentDestination.Outliner(8), DocumentRef(DocumentKind.OUTLINE, 8).destination())
        assertEquals(DocumentDestination.Journal(9), DocumentRef(DocumentKind.JOURNAL, 9).destination())
    }

    @Test
    fun theDomainDocumentsPackageKnowsNoRoutesEntitiesOrDaos() {
        val dir = File("src/main/java/io/github/cragcoffee/memoripple/domain/documents")
        assertTrue(dir.isDirectory)
        val sources = dir.listFiles { f -> f.extension == "kt" }!!.associate { it.name to it.readText() }
        assertTrue(sources.isNotEmpty())
        sources.forEach { (name, text) ->
            listOf("editor/", "outliner/", "journal/", "NavController", "Routes.", "import io.github.cragcoffee.memoripple.data.", "Dao", "Entity").forEach { forbidden ->
                assertTrue("$name mentions $forbidden", !text.contains(forbidden))
            }
        }
    }

    @Test
    fun theSearchScopeOfV1IsMemoOutlineAndJournal() {
        assertEquals(setOf(DocumentKind.MEMO, DocumentKind.OUTLINE, DocumentKind.JOURNAL), DocumentSearchScope.V1)
        assertEquals(DocumentSearchScope.V1, DocumentQuery("x").kinds)
    }

    @Test
    fun aQueryIsBoundedByWordsOrADateRangeNeverByNothing() {
        assertTrue(DocumentQuery("会議").isBounded)
        assertTrue(DocumentQuery(dateRange = DocumentDateRange.day(java.time.LocalDate.of(2026, 9, 18))).isBounded)
        assertTrue(!DocumentQuery("   ").isBounded)
        assertTrue(!DocumentQuery(kinds = setOf(DocumentKind.JOURNAL)).isBounded)
        val range = DocumentDateRange.of(java.time.LocalDate.of(2025, 12, 31), java.time.LocalDate.of(2026, 1, 1))
        assertTrue(java.time.LocalDate.of(2025, 12, 31).toEpochDay() in range)
        assertTrue(java.time.LocalDate.of(2026, 1, 1).toEpochDay() in range)
        assertTrue(java.time.LocalDate.of(2026, 1, 2).toEpochDay() !in range)
    }

    @Test
    fun nothingFromTheDataLayerCrossesTheAccessApi() {
        val api = listOf(
            DocumentAccess::class.java, DocumentReader::class.java, DocumentWriter::class.java, DocumentSearch::class.java,
            DocumentContent::class.java, DocumentSummary::class.java, DocumentRef::class.java, DocumentQuery::class.java,
            DocumentMetadata.Memo::class.java, DocumentMetadata.Outline::class.java, DocumentMetadata.Journal::class.java,
            DocumentReadResult.Found::class.java, DocumentWriteResult.Done::class.java, DocumentWriteResult.Rejected::class.java,
            DocumentCreate.Memo::class.java, DocumentCreate.Outline::class.java, DocumentCreate.Journal::class.java,
        )
        val leaked = api.flatMap { cls ->
            val fromMethods = cls.declaredMethods.flatMap { m -> (m.parameterTypes.toList() + m.returnType) }
            val fromFields = cls.declaredFields.map { it.type }
            (fromMethods + fromFields).filter { it.name.startsWith("io.github.cragcoffee.memoripple.data") }.map { "${cls.simpleName} -> ${it.name}" }
        }
        assertTrue("data types leak: $leaked", leaked.isEmpty())
    }
}
