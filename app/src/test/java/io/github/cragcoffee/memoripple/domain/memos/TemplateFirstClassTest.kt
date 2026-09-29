package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** AI optional, Template first-class (docs/CHAT_UI_TEMPLATE_V2.md §14): the starters, the recents, the body shown by label, the preview samples, the validation words. */
class TemplateFirstClassTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 21)

    // --- starters: six that record or search (plus the four Think starters of 2026-09-22), valid, the shapes the brief named ---

    @Test
    fun thereAreSixValidStartersOfTheNamedShapes() {
        val all = StarterTemplates.all
        assertEquals(10, all.size)
        assertEquals("the six of the first brief, in their order, before the Think starters", 6, all.indexOfFirst { it.flow == TemplateFlow.THINK })
        assertTrue(all.size <= StarterTemplates.MAX_STARTERS)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        all.forEach { t ->
            assertTrue("starter ${t.name} is valid: ${TemplateValidation.problems(t)}", TemplateValidation.problems(t).isEmpty())
            assertTrue(StarterTemplates.isStarter(t.id))
            assertTrue(t.description.isNotBlank())
        }
        with(StarterTemplates.dailyReview) { assertEquals(TemplateAction.CREATE, action); assertEquals(DocumentKind.JOURNAL, documentKind) }
        with(StarterTemplates.meetingMemo) {
            assertEquals(TemplateAction.CREATE, action); assertEquals(DocumentKind.MEMO, documentKind)
            assertEquals(listOf("会議名", "参加者", "話したこと", "決まったこと", "次にやること"), fields.map { it.label })
            assertTrue(fields.first().required)
            assertEquals("会議名は？", fields.first().question)
        }
        with(StarterTemplates.ideaMemo) { assertEquals(TemplateAction.CREATE, action); assertEquals(DocumentKind.MEMO, documentKind); assertTrue(fields.any { it.required }) }
        with(StarterTemplates.projectLog) { assertEquals(TemplateAction.APPEND, action); assertEquals(TemplateTargetSpec.AskAtRun, targetSpec); assertTrue(fields.any { it.key == "done" && it.required }) }
        with(StarterTemplates.thisWeek) { assertEquals(TemplateAction.SEARCH, action); assertEquals(TemplateDateToken.THIS_WEEK, searchSpec!!.dateToken); assertEquals(3, searchSpec!!.kinds.size) }
        with(StarterTemplates.yesterdayJournal) { assertEquals(TemplateAction.SEARCH, action); assertEquals(TemplateDateToken.YESTERDAY, searchSpec!!.dateToken); assertEquals(setOf(DocumentKind.JOURNAL), searchSpec!!.kinds) }
        assertEquals(StarterTemplates.meetingMemo, StarterTemplates.find("starter-meeting-memo"))
        assertEquals(StarterTemplates.meetingMemo, StarterTemplates.find("会議メモ"))
        assertNull(StarterTemplates.find("nothing"))
    }

    @Test
    fun aStarterRendersWithSampleValuesAndRunsWithRealOnes() {
        val t = StarterTemplates.meetingMemo.copy(fields = StarterTemplates.meetingMemo.fields + TemplateField("date", "日付", TemplateFieldType.DATE, default = "TODAY"))
        val samples = TemplateBodyDisplay.sampleValues(t.fields, today)
        assertEquals("（会議名）", samples["meeting_name"])
        assertEquals("2026年9月21日", samples["date"])
        val rendered = TemplateRenderer.render(t.body, samples) as TemplateRendering.Rendered
        assertTrue(rendered.text.startsWith("# （会議名）"))
        val ready = TemplateValues.resolve(t.fields, mapOf("meeting_name" to "定例会"), today) as TemplateValues.Ready
        assertEquals("定例会", ready.values["meeting_name"])
        assertEquals("2026年9月21日", ready.values["date"])
        assertEquals(TemplateValues.Missing(setOf("meeting_name")), TemplateValues.resolve(t.fields, emptyMap(), today))
    }

    // --- recents: newest first, one per template, five at most; unknown ids skipped ---

    @Test
    fun recentsAreNewestFirstOnePerTemplateAndAtMostFive() {
        var list = emptyList<RecentTemplate>()
        listOf("a", "b", "c", "a", "d", "e", "f").forEachIndexed { i, id -> list = RecentTemplates.push(list, id, 100L + i) }
        assertEquals(listOf("f", "e", "d", "a", "c"), list.map { it.templateId })
        assertEquals(RecentTemplates.MAX_RECENT, list.size)
        assertEquals(103L, list.first { it.templateId == "a" }.lastUsedAt)
        val templates = listOf(MemoTemplate("a", "A", "x"), MemoTemplate("c", "C", "x"), MemoTemplate("f", "F", "x"))
        assertEquals(listOf("f", "a", "c"), RecentTemplates.resolve(list, templates).map { it.id })
    }

    // --- the body shown by label, stored by key ---

    @Test
    fun theBodyIsShownWithLabelsAndStoredWithKeys() {
        val fields = listOf(TemplateField("field_1", "会議名", TemplateFieldType.TEXT), TemplateField("field_2", "議題", TemplateFieldType.MULTILINE))
        val stored = "# {{field_1}}\n\n## 議題\n{{field_2}}\n{{unknown}}"
        val shown = TemplateBodyDisplay.toDisplay(stored, fields)
        assertEquals("# ⟦会議名⟧\n\n## 議題\n⟦議題⟧\n{{unknown}}", shown)
        assertEquals(stored, TemplateBodyDisplay.fromDisplay(shown, fields))
        assertEquals("⟦誰か⟧ stays", TemplateBodyDisplay.fromDisplay("⟦誰か⟧ stays", fields))
        assertEquals("field_3", TemplateBodyDisplay.nextKey(listOf("field_1", "field_2")))
        assertEquals("a key never collides with one in use", "field_4", TemplateBodyDisplay.nextKey(listOf("field_1", "field_3")))
        assertEquals("field_1", TemplateBodyDisplay.nextKey(emptyList()))
        assertEquals("昨日の日記 を探します", TemplateBodyDisplay.describeSearch(StarterTemplates.yesterdayJournal.searchSpec))
        assertEquals("今週のメモ・アウトライン・日記 を探します", TemplateBodyDisplay.describeSearch(StarterTemplates.thisWeek.searchSpec))
    }

    // --- validation speaks in labels; duplicate labels are refused; an unknown placeholder is named ---

    @Test
    fun validationNamesFieldsByLabelAndRefusesDuplicateLabels() {
        val dup = MemoTemplate("t", "T", "{{a}}", fields = listOf(TemplateField("a", "同じ", TemplateFieldType.TEXT), TemplateField("b", "同じ", TemplateFieldType.TEXT)))
        val problems = TemplateValidation.problems(dup)
        assertTrue(problems.toString(), problems.any { it.contains("「同じ」の名前が重複") })
        assertFalse("no key word reaches the user", problems.any { it.contains("キー") })
        val unknown = MemoTemplate("t", "T", "{{nowhere}}")
        assertTrue(TemplateValidation.problems(unknown).any { it.contains("⟦nowhere⟧") })
        val blankLabel = MemoTemplate("t", "T", "x", fields = listOf(TemplateField("a", " ", TemplateFieldType.TEXT)))
        assertTrue(TemplateValidation.problems(blankLabel).any { it.contains("1番目の項目の名前") })
        val dupKey = MemoTemplate("t", "T", "x", fields = listOf(TemplateField("a", "一", TemplateFieldType.TEXT), TemplateField("a", "二", TemplateFieldType.TEXT)))
        assertTrue(TemplateValidation.problems(dupKey).any { it.contains("「二」の内部名が重複") })
    }
}
