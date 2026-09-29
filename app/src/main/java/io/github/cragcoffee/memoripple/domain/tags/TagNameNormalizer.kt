package io.github.cragcoffee.memoripple.domain.tags

import java.text.Normalizer
import java.util.Locale

enum class TagNameIssue { BLANK, TOO_LONG }

data class ValidTagName(val displayName: String, val normalizedName: String)

sealed interface TagNameValidation {
    data class Valid(val value: ValidTagName) : TagNameValidation
    data class Invalid(val issue: TagNameIssue) : TagNameValidation
}

object TagNameNormalizer {
    const val MAX_CODE_POINTS = 40

    fun validate(input: String): TagNameValidation {
        val displayName = input.trim()
        val normalizedName = normalizeKey(displayName)
        if (normalizedName.isBlank()) return TagNameValidation.Invalid(TagNameIssue.BLANK)
        if (displayName.codePointCount(0, displayName.length) > MAX_CODE_POINTS) {
            return TagNameValidation.Invalid(TagNameIssue.TOO_LONG)
        }
        return TagNameValidation.Valid(ValidTagName(displayName, normalizedName))
    }

    fun normalizeKey(input: String): String = Normalizer
        .normalize(input.trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)

    fun matches(name: String, query: String): Boolean =
        normalizeKey(name).contains(normalizeKey(query))
}
