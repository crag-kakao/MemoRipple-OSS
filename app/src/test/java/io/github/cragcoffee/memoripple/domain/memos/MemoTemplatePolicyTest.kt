package io.github.cragcoffee.memoripple.domain.memos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoTemplatePolicyTest {

    private fun template(id: String, name: String = "名前", body: String = "本文") =
        MemoTemplate(id, name, body)

    @Test
    fun aTemplateWithoutABodyDoesNothingSoItIsRefused() {
        assertFalse(MemoTemplatePolicy.isUsable("名前", ""))
        assertFalse(MemoTemplatePolicy.isUsable("", "本文"))
        assertTrue(MemoTemplatePolicy.isUsable("名前", "本文"))
    }

    @Test
    fun anUnnamedTemplateFallsBackRatherThanBeingLost() {
        assertEquals("週報", MemoTemplatePolicy.cleanName("   ", fallback = "週報"))
        assertEquals("テンプレート", MemoTemplatePolicy.cleanName(""))
    }

    @Test
    fun aVeryLongNameIsShortened() {
        val name = MemoTemplatePolicy.cleanName("あ".repeat(100))

        assertEquals(MemoTemplatePolicy.MAX_NAME_CHARS, name.length)
    }

    @Test
    fun theNewestTemplateSitsFirst() {
        val existing = listOf(template("a"), template("b"))

        assertEquals(
            listOf("c", "a", "b"),
            MemoTemplatePolicy.upsert(existing, template("c")).map(MemoTemplate::id),
        )
    }

    @Test
    fun savingUnderAnExistingIdReplacesRatherThanDuplicates() {
        val existing = listOf(template("a", name = "古い"), template("b"))

        val result = MemoTemplatePolicy.upsert(existing, template("a", name = "新しい"))

        assertEquals(2, result.size)
        assertEquals("新しい", result.first().name)
    }

    @Test
    fun theListStopsGrowingSoItStaysReadable() {
        val full = (1..MemoTemplatePolicy.MAX_TEMPLATES).map { template("id$it") }

        val result = MemoTemplatePolicy.upsert(full, template("new"))

        assertEquals(MemoTemplatePolicy.MAX_TEMPLATES, result.size)
        assertEquals("new", result.first().id)
        assertFalse(result.any { it.id == "id${MemoTemplatePolicy.MAX_TEMPLATES}" })
    }

    @Test
    fun removingTakesOnlyTheOneNamed() {
        val existing = listOf(template("a"), template("b"))

        assertEquals(listOf("b"), MemoTemplatePolicy.remove(existing, "a").map(MemoTemplate::id))
        assertEquals(2, MemoTemplatePolicy.remove(existing, "missing").size)
    }
}
