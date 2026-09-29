package io.github.cragcoffee.memoripple.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OutlineSymbolsTest {

    @Test
    fun everySymbolOfEverySetFlowsWithItsSpaceAndNotWithoutIt() {
        OutlineSymbolSet.entries.forEach { set ->
            OutlineSymbolRole.entries.forEach { role ->
                val symbol = set.symbol(role)
                val withSpace = WorkCommentSyntax.recognize("$symbol 中身")
                assertEquals("$symbol should be recognised", "中身", withSpace?.text)
                // Without the mandatory half-width space the mark does not take: either the
                // line stays prose, or (for `- [ ]`, whose own prefix is the item mark) it
                // reads as something else — never as this role with its clean text.
                val without = WorkCommentSyntax.recognize("$symbol" + "中身")
                assertNotEquals("$symbol without a space must not flow as itself",
                    "中身", without?.text)
            }
        }
    }

    @Test
    fun everySetKeepsTheIndentDepthRule() {
        OutlineSymbolSet.entries.forEach { set ->
            val line = "    " + set.marker(OutlineSymbolRole.ITEM) + "子項目"
            val recognised = WorkCommentSyntax.recognize(line)
            assertEquals(2, recognised?.depth)
            assertEquals("子項目", recognised?.text)
        }
    }

    @Test
    fun theVariationSelectorMakesNoDifference() {
        // ⚠️ carries U+FE0F; a keyboard writing bare ⚠ means the same mark.
        val withSelector = WorkCommentSyntax.recognize("⚠️ 危険")
        assertEquals(WorkLineType.IMPORTANT, withSelector?.type)
        assertEquals("危険", withSelector?.text)
        val without = WorkCommentSyntax.recognize("⚠ 危険")
        assertEquals(WorkLineType.IMPORTANT, without?.type)
        assertEquals("危険", without?.text)
        // The same both ways for ☑ — some keyboards add the selector, some do not.
        val checked = WorkCommentSyntax.recognize("☑️ 済み")
        assertEquals(WorkLineType.TASK_DONE, checked?.type)
        assertEquals("済み", checked?.text)
    }

    @Test
    fun theSetsMapEachSymbolToItsOneRole() {
        assertEquals(WorkLineType.HEADING, WorkCommentSyntax.recognize("◆ 章")?.type)
        assertEquals(WorkLineType.HEADING, WorkCommentSyntax.recognize("📌 章")?.type)
        assertEquals(WorkLineType.ITEM, WorkCommentSyntax.recognize("・ 品")?.type)
        assertEquals(WorkLineType.ITEM, WorkCommentSyntax.recognize("• 品")?.type)
        assertEquals(WorkLineType.TASK, WorkCommentSyntax.recognize("☐ 用")?.type)
        assertEquals(WorkLineType.TASK_DONE, WorkCommentSyntax.recognize("✅ 用")?.type)
        assertEquals(WorkLineType.NOTE, WorkCommentSyntax.recognize("※ 注")?.type)
        assertEquals(WorkLineType.NOTE, WorkCommentSyntax.recognize("💬 注")?.type)
        assertEquals(WorkLineType.IMPORTANT, WorkCommentSyntax.recognize("★ 大事")?.type)
        assertEquals(WorkLineType.QUESTION, WorkCommentSyntax.recognize("？ なぜ")?.type)
        assertEquals(WorkLineType.QUESTION, WorkCommentSyntax.recognize("❓ なぜ")?.type)
    }

    @Test
    fun markdownHeadingsStayValidInEverySet() {
        // The hash spelling is set-independent; the chosen set never turns it off.
        val recognised = WorkCommentSyntax.recognize("### 深い見出し")
        assertEquals(WorkLineType.HEADING, recognised?.type)
        assertEquals(2, recognised?.depth)
    }

    @Test
    fun aCheckboxIsNeverMistakenForAnItem() {
        assertEquals(WorkLineType.TASK, WorkCommentSyntax.recognize("- [ ] 買う")?.type)
        assertEquals(WorkLineType.ITEM, WorkCommentSyntax.recognize("- [買う]")?.type)
        assertEquals("[買う]", WorkCommentSyntax.recognize("- [買う]")?.text)
    }

    @Test
    fun previewsListTheSevenRolesOfEachSet() {
        assertEquals("■ - - [ ] - [x] > ! ?", OutlineSymbolSet.STANDARD.preview())
        assertEquals("◆ ・ ☐ ☑ ※ ★ ？", OutlineSymbolSet.JAPANESE.preview())
        assertEquals("📌 • ☐ ✅ 💬 ⚠️ ❓", OutlineSymbolSet.EMOJI.preview())
    }

    @Test
    fun theParserStripsEverySetsSymbolFromTheFlyingComment() {
        OutlineSymbolSet.entries.forEach { set ->
            val body = set.marker(OutlineSymbolRole.IMPORTANT) + "飛ぶことば"
            val lines = WorkCommentParser().parse(WorkCommentScope.OUTLINE, body)
            assertEquals(1, lines.size)
            assertEquals("飛ぶことば", lines.single().text)
            assertEquals(WorkLineType.IMPORTANT, lines.single().type)
        }
    }

    @Test
    fun aSelectionMixesRolesAndRoundTrips() {
        val mixed = OutlineSymbolSelection.Default
            .with(OutlineSymbolRole.HEADING, OutlineSymbolSet.JAPANESE)
            .with(OutlineSymbolRole.QUESTION, OutlineSymbolSet.EMOJI)
        assertEquals("◆", mixed.symbol(OutlineSymbolRole.HEADING))
        assertEquals("-", mixed.symbol(OutlineSymbolRole.ITEM))
        assertEquals("❓", mixed.symbol(OutlineSymbolRole.QUESTION))
        val decoded = OutlineSymbolSelection.decode(mixed.encode())
        OutlineSymbolRole.entries.forEach { role ->
            assertEquals(mixed.symbol(role), decoded.symbol(role))
        }
    }

    @Test
    fun theLegacySingleSetValueReadsAsEveryRole() {
        val decoded = OutlineSymbolSelection.decode("japanese")
        assertEquals("◆", decoded.symbol(OutlineSymbolRole.HEADING))
        assertEquals("・", decoded.symbol(OutlineSymbolRole.ITEM))
        assertEquals("※", decoded.symbol(OutlineSymbolRole.NOTE))
    }

    @Test
    fun unknownPairsAreForgivenAndTheRestHold() {
        val decoded = OutlineSymbolSelection.decode("heading:emoji|nonsense|item:gone")
        assertEquals("📌", decoded.symbol(OutlineSymbolRole.HEADING))
        assertEquals("-", decoded.symbol(OutlineSymbolRole.ITEM))
        assertEquals(
            OutlineSymbolSelection.Default.symbol(OutlineSymbolRole.NOTE),
            OutlineSymbolSelection.decode("").symbol(OutlineSymbolRole.NOTE),
        )
    }
}
