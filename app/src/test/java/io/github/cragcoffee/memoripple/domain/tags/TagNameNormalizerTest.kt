package io.github.cragcoffee.memoripple.domain.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNameNormalizerTest {
    @Test
    fun trimsDisplayNameAndUsesNfkcRootLowercaseForIdentity() {
        val result = TagNameNormalizer.validate("  Ｃｏｆｆｅｅ  ") as TagNameValidation.Valid

        assertEquals("Ｃｏｆｆｅｅ", result.value.displayName)
        assertEquals("coffee", result.value.normalizedName)
    }

    @Test
    fun preservesInternalWhitespace() {
        val result = TagNameNormalizer.validate("長期  計画") as TagNameValidation.Valid

        assertEquals("長期  計画", result.value.displayName)
        assertEquals("長期  計画", result.value.normalizedName)
    }

    @Test
    fun countsUnicodeCodePointsInsteadOfUtf16Units() {
        assertTrue(TagNameNormalizer.validate("😀".repeat(40)) is TagNameValidation.Valid)
        assertEquals(
            TagNameIssue.TOO_LONG,
            (TagNameNormalizer.validate("😀".repeat(41)) as TagNameValidation.Invalid).issue,
        )
    }

    @Test
    fun rejectsWhitespaceOnlyNames() {
        assertEquals(
            TagNameIssue.BLANK,
            (TagNameNormalizer.validate(" \n\t ") as TagNameValidation.Invalid).issue,
        )
        assertEquals(
            TagNameIssue.BLANK,
            (TagNameNormalizer.validate("　　") as TagNameValidation.Invalid).issue,
        )
    }

    @Test
    fun searchUsesTheSameNormalization() {
        assertTrue(TagNameNormalizer.matches("Ｃｏｆｆｅｅ", "coffee"))
        assertTrue(TagNameNormalizer.matches("長期 計画", "期 計"))
    }
}
