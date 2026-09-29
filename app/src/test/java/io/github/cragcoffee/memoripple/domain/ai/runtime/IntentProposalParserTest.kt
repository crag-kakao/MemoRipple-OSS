package io.github.cragcoffee.memoripple.domain.ai.runtime

import io.github.cragcoffee.memoripple.domain.ai.AiIntent
import io.github.cragcoffee.memoripple.domain.ai.AiResultRef
import io.github.cragcoffee.memoripple.domain.ai.DateToken
import io.github.cragcoffee.memoripple.domain.ai.ProposalField
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RED 13–20: the model's JSON becomes a proposal only through a guarded transport model. */
class IntentProposalParserTest {
    private fun parsed(raw: String) = (IntentProposalParser.parse(raw) as ProposalParseResult.Parsed).proposal
    private fun rejected(raw: String) = (IntentProposalParser.parse(raw) as ProposalParseResult.Rejected).failure

    @Test
    fun aWellFormedAnswerParses() {
        val p = parsed("""{"intent":"SEARCH","query":null,"targetRef":null,"targetName":null,"documentKind":"JOURNAL","text":null,"templateId":null,"dateToken":"YESTERDAY","missingFields":[]}""")
        assertEquals(AiIntent.SEARCH, p.intent)
        assertEquals(DocumentKind.JOURNAL, p.documentKind)
        assertEquals(DateToken.YESTERDAY, p.dateToken)
        assertNull(p.query)
        val q = parsed("""{"intent": "APPEND", "query": null, "targetRef": "result_2", "targetName": null, "documentKind": null, "text": "あとで電話", "templateId": null, "dateToken": null, "missingFields": ["targetName"]}""")
        assertEquals(AiResultRef(2), q.targetRef)
        assertEquals("あとで電話", q.text)
        assertEquals(setOf(ProposalField.TARGET_NAME), q.missingFields)
    }

    @Test
    fun malformedJsonIsRejectedNotThrown() {
        assertEquals(ParseFailure.MALFORMED_JSON, rejected("""{"intent":"SEARCH","query":"""))
        assertEquals(ParseFailure.MALFORMED_JSON, rejected(""))
        assertEquals(ParseFailure.NOT_AN_OBJECT, rejected("""["SEARCH"]"""))
        assertEquals(ParseFailure.MALFORMED_JSON, rejected("not json at all"))
    }

    @Test
    fun unsupportedIntentsBecomeUnknownWithoutAnException() {
        for (word in listOf("DELETE", "UPDATE", "MOVE", "RENAME", "ARCHIVE", "RESTORE", "REPLACE", "EXECUTE", "", "null"))
            assertEquals(word, AiIntent.UNKNOWN, parsed("""{"intent":"$word","query":null,"targetRef":null,"targetName":null,"documentKind":null,"text":null,"templateId":null,"dateToken":null,"missingFields":[]}""").intent)
        assertEquals(AiIntent.UNKNOWN, parsed("""{"intent":42,"missingFields":[]}""").intent)
        assertEquals(AiIntent.UNKNOWN, parsed("""{"query":"x"}""").intent)
    }

    @Test
    fun aMalformedTargetRefIsRejected() {
        val base = """"intent":"OPEN","query":null,"targetName":null,"documentKind":null,"text":null,"templateId":null,"dateToken":null,"missingFields":[]"""
        assertEquals(ParseFailure.MALFORMED_TARGET_REF, rejected("""{"targetRef":"42",$base}"""))
        assertEquals(ParseFailure.MALFORMED_TARGET_REF, rejected("""{"targetRef":"memo:7",$base}"""))
        assertEquals(ParseFailure.MALFORMED_TARGET_REF, rejected("""{"targetRef":"result_0",$base}"""))
        assertEquals(ParseFailure.MALFORMED_TARGET_REF, rejected("""{"targetRef":7,$base}"""))
        assertEquals(AiResultRef(3), parsed("""{"targetRef":"result_3",$base}""").targetRef)
        assertNull(parsed("""{"targetRef":null,$base}""").targetRef)
    }

    @Test
    fun anActualDateOrAnUnknownTokenIsRejectedAsADateToken() {
        val base = """"intent":"SEARCH","query":"日記","targetRef":null,"targetName":null,"documentKind":null,"text":null,"templateId":null,"missingFields":[]"""
        assertEquals(ParseFailure.INVALID_DATE_TOKEN, rejected("""{"dateToken":"2026-09-18",$base}"""))
        assertEquals(ParseFailure.INVALID_DATE_TOKEN, rejected("""{"dateToken":"TOMORROW",$base}"""))
        assertEquals(ParseFailure.INVALID_DATE_TOKEN, rejected("""{"dateToken":"9月10日",$base}"""))
        assertEquals(DateToken.THIS_WEEK, parsed("""{"dateToken":"THIS_WEEK",$base}""").dateToken)
        assertNull(parsed("""{"dateToken":null,$base}""").dateToken)
    }

    @Test
    fun oversizedFieldsAreRejected() {
        val big = "あ".repeat(FieldLimits.MAX_QUERY_CHARS + 1)
        assertEquals(ParseFailure.OVERSIZED_FIELD, rejected("""{"intent":"SEARCH","query":"$big","missingFields":[]}"""))
        val bigText = "x".repeat(FieldLimits.MAX_TEXT_CHARS + 1)
        assertEquals(ParseFailure.OVERSIZED_FIELD, rejected("""{"intent":"APPEND","targetName":"n","text":"$bigText","missingFields":[]}"""))
        val ok = "x".repeat(FieldLimits.MAX_TEXT_CHARS)
        assertEquals(ok, parsed("""{"intent":"APPEND","targetName":"n","text":"$ok","missingFields":[]}""").text)
        assertEquals(ParseFailure.OVERSIZED_FIELD, rejected("""{"intent":"OPEN","targetName":"${"n".repeat(FieldLimits.MAX_NAME_CHARS + 1)}","missingFields":[]}"""))
        assertEquals(ParseFailure.OVERSIZED_OUTPUT, rejected("{" + "\"query\":\"" + "x".repeat(FieldLimits.MAX_RAW_CHARS) + "\"}"))
    }

    @Test
    fun missingFieldsAreCappedAndUnknownNamesAreDroppedNotFatal() {
        val many = (1..FieldLimits.MAX_MISSING_FIELDS + 1).joinToString(",") { "\"query\"" }
        assertEquals(ParseFailure.OVERSIZED_FIELD, rejected("""{"intent":"SEARCH","missingFields":[$many]}"""))
        val p = parsed("""{"intent":"APPEND","targetName":"n","text":"t","missingFields":["targetRef","何をするか","TEXT"]}""")
        assertEquals(setOf(ProposalField.TARGET_REF, ProposalField.TEXT), p.missingFields)
        assertEquals(emptySet<ProposalField>(), parsed("""{"intent":"SEARCH","query":"x","missingFields":"query"}""").missingFields)
    }
}
