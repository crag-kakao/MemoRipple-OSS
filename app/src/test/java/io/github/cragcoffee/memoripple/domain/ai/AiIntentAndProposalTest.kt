package io.github.cragcoffee.memoripple.domain.ai

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RED 1–4: the six intents, no write intent beyond them, no real id in a proposal, an opaque target ref. */
class AiIntentAndProposalTest {
    @Test
    fun exactlySixIntentsAreSupported() {
        assertEquals(listOf("SEARCH", "OPEN", "CREATE", "APPEND", "USE_TEMPLATE", "UNKNOWN"), AiIntent.entries.map { it.name })
    }

    @Test
    fun unsupportedOperationsFromTheModelBecomeUnknownNeverAnExecutableIntent() {
        for (raw in listOf("UPDATE", "REPLACE", "DELETE", "MOVE", "RENAME", "ARCHIVE", "RESTORE", "delete", "Delete all", "", null)) {
            assertEquals("'$raw' must map to UNKNOWN", AiIntent.UNKNOWN, AiIntent.fromModel(raw))
        }
        assertEquals(AiIntent.APPEND, AiIntent.fromModel("APPEND"))
        assertEquals(AiIntent.SEARCH, AiIntent.fromModel(" search "))
        assertFalse(AiIntent.entries.any { it.name in setOf("UPDATE", "REPLACE", "DELETE", "MOVE", "RENAME", "ARCHIVE", "RESTORE") })
    }

    @Test
    fun aProposalCarriesNoRealDatabaseId() {
        val names = IntentProposal::class.java.declaredFields.map { it.name.lowercase() }
        for (forbidden in listOf("memoid", "journalid", "folderid", "databaseid", "entryid", "noteid", "id")) {
            assertFalse("IntentProposal must not carry $forbidden", names.any { it == forbidden || it.endsWith(forbidden) && it != "templateid" })
        }
        // no Long-typed field at all: every reference is opaque, every date a token
        assertTrue(IntentProposal::class.java.declaredFields.none { it.type == java.lang.Long.TYPE || it.type == java.lang.Long::class.java })
        assertTrue(IntentProposal::class.java.declaredFields.none { it.type == java.time.LocalDate::class.java })
    }

    @Test
    fun targetRefIsAnOpaqueTypeParsedOnlyFromTheResultShape() {
        assertEquals(AiResultRef(1), AiResultRef.parse("result_1"))
        assertEquals(AiResultRef(12), AiResultRef.parse("result_12"))
        assertNull(AiResultRef.parse("result_0"))
        assertNull(AiResultRef.parse("result_100"))
        assertNull(AiResultRef.parse("42"))
        assertNull(AiResultRef.parse("memo:42"))
        assertNull(AiResultRef.parse("DocumentRef(kind=MEMO, id=42)"))
        assertEquals("result_3", AiResultRef(3).label)
        val proposal = IntentProposal(intent = AiIntent.OPEN, targetRef = AiResultRef(2))
        assertEquals(AiResultRef::class.java, IntentProposal::class.java.getDeclaredField("targetRef").type)
        assertEquals(DocumentKind::class.java, IntentProposal::class.java.getDeclaredField("documentKind").type)
        assertEquals(AiResultRef(2), proposal.targetRef)
    }
}
