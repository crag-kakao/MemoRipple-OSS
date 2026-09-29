package io.github.cragcoffee.memoripple.domain.ai.runtime

import io.github.cragcoffee.memoripple.domain.ai.AiIntent
import io.github.cragcoffee.memoripple.domain.ai.AiResultRef
import io.github.cragcoffee.memoripple.domain.ai.DateToken
import io.github.cragcoffee.memoripple.domain.ai.IntentProposal
import io.github.cragcoffee.memoripple.domain.ai.ProposalField
import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Size guards on what a model may hand the app. Chosen from the golden set's longest values with headroom. */
object FieldLimits {
    const val MAX_RAW_CHARS = 8_000
    const val MAX_QUERY_CHARS = 200
    const val MAX_NAME_CHARS = 200
    const val MAX_TEXT_CHARS = 2_000
    const val MAX_TEMPLATE_ID_CHARS = 100
    const val MAX_MISSING_FIELDS = 8
    const val MAX_MISSING_FIELD_CHARS = 40
}

enum class ParseFailure { MALFORMED_JSON, NOT_AN_OBJECT, OVERSIZED_OUTPUT, OVERSIZED_FIELD, MALFORMED_TARGET_REF, INVALID_DATE_TOKEN }

sealed interface ProposalParseResult {
    data class Parsed(val proposal: IntentProposal, val raw: RawIntentProposalDto) : ProposalParseResult
    data class Rejected(val failure: ParseFailure) : ProposalParseResult
}

/**
 * The transport shape of a model answer: every field a string or absent, exactly as received.
 * It exists so that a raw answer is never fed to [IntentProposal]'s constructor.
 */
data class RawIntentProposalDto(
    val intent: String?,
    val query: String?,
    val targetRef: String?,
    val targetName: String?,
    val documentKind: String?,
    val text: String?,
    val templateId: String?,
    val dateToken: String?,
    val missingFields: List<String>,
)

/**
 * Raw JSON → [RawIntentProposalDto] → [IntentProposal], defensively: malformed JSON, a non-object,
 * an oversized answer or field, a malformed `result_N` or a date where a token belongs are
 * rejections; an unknown intent word or kind is UNKNOWN / null, an unknown missing-field name is
 * dropped. Nothing here throws on model output.
 */
object IntentProposalParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parse(raw: String): ProposalParseResult {
        if (raw.length > FieldLimits.MAX_RAW_CHARS) return ProposalParseResult.Rejected(ParseFailure.OVERSIZED_OUTPUT)
        val element = try { json.parseToJsonElement(raw) } catch (_: Exception) { return ProposalParseResult.Rejected(ParseFailure.MALFORMED_JSON) }
        val obj = element as? JsonObject ?: return ProposalParseResult.Rejected(ParseFailure.NOT_AN_OBJECT)
        val dto = RawIntentProposalDto(
            intent = obj.string("intent"), query = obj.string("query"), targetRef = obj.string("targetRef"),
            targetName = obj.string("targetName"), documentKind = obj.string("documentKind"), text = obj.string("text"),
            templateId = obj.string("templateId"), dateToken = obj.string("dateToken"), missingFields = obj.strings("missingFields"),
        )
        return toProposal(dto)
    }

    fun toProposal(dto: RawIntentProposalDto): ProposalParseResult {
        if ((dto.query?.length ?: 0) > FieldLimits.MAX_QUERY_CHARS) return rejected(ParseFailure.OVERSIZED_FIELD)
        if ((dto.targetName?.length ?: 0) > FieldLimits.MAX_NAME_CHARS) return rejected(ParseFailure.OVERSIZED_FIELD)
        if ((dto.text?.length ?: 0) > FieldLimits.MAX_TEXT_CHARS) return rejected(ParseFailure.OVERSIZED_FIELD)
        if ((dto.templateId?.length ?: 0) > FieldLimits.MAX_TEMPLATE_ID_CHARS) return rejected(ParseFailure.OVERSIZED_FIELD)
        if (dto.missingFields.size > FieldLimits.MAX_MISSING_FIELDS || dto.missingFields.any { it.length > FieldLimits.MAX_MISSING_FIELD_CHARS }) return rejected(ParseFailure.OVERSIZED_FIELD)
        val targetRef = when (val r = dto.targetRef) {
            null -> null
            else -> AiResultRef.parse(r) ?: return rejected(ParseFailure.MALFORMED_TARGET_REF)
        }
        val dateToken = when (val d = dto.dateToken) {
            null -> null
            else -> DateToken.fromModel(d) ?: return rejected(ParseFailure.INVALID_DATE_TOKEN)
        }
        val proposal = IntentProposal(
            intent = AiIntent.fromModel(dto.intent),
            query = dto.query?.takeIf { it.isNotBlank() },
            targetRef = targetRef,
            targetName = dto.targetName?.takeIf { it.isNotBlank() },
            documentKind = dto.documentKind?.trim()?.uppercase()?.let { k -> DocumentKind.entries.firstOrNull { it.name == k } },
            text = dto.text,
            templateId = dto.templateId?.takeIf { it.isNotBlank() },
            dateToken = dateToken,
            missingFields = dto.missingFields.mapNotNull { fieldOf(it) }.toSet(),
        )
        return ProposalParseResult.Parsed(proposal, dto)
    }

    private fun rejected(f: ParseFailure) = ProposalParseResult.Rejected(f)

    /** `targetName` / `TARGET_NAME` / `targetname` all name the same field; anything else is dropped. */
    private fun fieldOf(raw: String): ProposalField? {
        val key = raw.trim().replace("_", "").lowercase()
        return ProposalField.entries.firstOrNull { it.name.replace("_", "").lowercase() == key }
    }

    /** A string value; a JSON null or a non-string (number, object) counts as absent — except a number where a ref belongs. */
    private fun JsonObject.string(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        if (v is JsonPrimitive) return if (v.isString) v.content else v.content.takeIf { key == "targetRef" || key == "intent" }
        return null
    }

    private fun JsonObject.strings(key: String): List<String> {
        val v = this[key] as? JsonArray ?: return emptyList()
        return v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }
}
