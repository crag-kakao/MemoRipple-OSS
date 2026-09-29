package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.backup.TemplateBackupMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Template = conversation script (docs/CHAT_UI_TEMPLATE_V2.md §16): the deterministic engine, the questions, the starters' scripts, the question on the wire. */
class TemplateScriptTest {
    private val t = MemoTemplate(
        "t", "T", "{{a}} {{b}} {{c}}",
        fields = listOf(
            TemplateField("a", "一", TemplateFieldType.TEXT, required = true, question = "一つ目は？"),
            TemplateField("b", "二", TemplateFieldType.DATE),
            TemplateField("c", "三", TemplateFieldType.BOOLEAN, question = "完了していますか？"),
        ),
    )

    @Test
    fun theFieldsAreAskedInOrderASkipIsAnAnswerAndReadyComesWhenAllAreThere() {
        val s0 = TemplateScript.next(t, emptyMap(), null) as TemplateScript.Step.Ask
        assertEquals("a", s0.field.key); assertEquals(0, s0.index); assertEquals(3, s0.total)
        val s1 = TemplateScript.next(t, mapOf("a" to "x"), null) as TemplateScript.Step.Ask
        assertEquals("b", s1.field.key)
        val s2 = TemplateScript.next(t, mapOf("a" to "x", "b" to ""), null) as TemplateScript.Step.Ask   // skipped: "" is an answer
        assertEquals("c", s2.field.key)
        assertEquals(TemplateScript.Step.Ready, TemplateScript.next(t, mapOf("a" to "x", "b" to "", "c" to "true"), null))
        // an answer changed: only that field is asked again
        assertEquals("b", (TemplateScript.next(t, mapOf("a" to "x", "c" to "true"), null) as TemplateScript.Step.Ask).field.key)
    }

    @Test
    fun anAppendThatAsksItsTargetAsksItFirst() {
        val append = t.copy(action = TemplateAction.APPEND, targetSpec = TemplateTargetSpec.AskAtRun, targetQuestion = "どのプロジェクトですか？")
        assertEquals(TemplateScript.Step.AskTarget, TemplateScript.next(append, emptyMap(), null))
        assertEquals(TemplateScript.Step.AskTarget, TemplateScript.next(append, mapOf("a" to "x"), ""))
        assertEquals("a", (TemplateScript.next(append, emptyMap(), "MemoRipple開発") as TemplateScript.Step.Ask).field.key)
        assertEquals("どのプロジェクトですか？名前を教えてください。", TemplateScript.targetQuestionOf(append))
        assertEquals("どのメモに追記しますか？名前を教えてください。", TemplateScript.targetQuestionOf(t.copy(action = TemplateAction.APPEND, targetSpec = TemplateTargetSpec.AskAtRun)))
        val named = t.copy(action = TemplateAction.APPEND, targetSpec = TemplateTargetSpec.Named("X"))
        assertTrue(TemplateScript.next(named, emptyMap(), null) is TemplateScript.Step.Ask)
    }

    @Test
    fun aQuestionIsTheFieldsOwnOrMadeFromItsLabelAndIsSpokenWithALead() {
        assertEquals("一つ目は？", TemplateScript.questionOf(t.fields[0]))
        assertEquals("二を入力してください", TemplateScript.questionOf(t.fields[1]))
        val first = TemplateScript.spoken(t, TemplateScript.Step.Ask(t.fields[0], 0, 3), first = true)
        assertEquals("Tを始めます。\nまず、一つ目は？", first)
        assertEquals("次に、二を入力してください（なければスキップできます）", TemplateScript.spoken(t, TemplateScript.Step.Ask(t.fields[1], 1, 3), first = false))
        assertEquals("最後に、完了していますか？", TemplateScript.spoken(t, TemplateScript.Step.Ask(t.fields[2], 2, 3), first = false))
        val one = t.copy(fields = t.fields.take(1))
        assertEquals("一つ目は？", TemplateScript.spoken(one, TemplateScript.Step.Ask(one.fields[0], 0, 1), first = false))
        assertEquals("今日", TemplateScript.spokenAnswer(t.fields[1], "TODAY"))
        assertEquals("昨日", TemplateScript.spokenAnswer(t.fields[1], "YESTERDAY"))
        assertEquals("2026-09-20", TemplateScript.spokenAnswer(t.fields[1], "2026-09-20"))
        assertEquals("はい", TemplateScript.spokenAnswer(t.fields[2], "true"))
        assertEquals("いいえ", TemplateScript.spokenAnswer(t.fields[2], "false"))
    }

    @Test
    fun theStartersAskTheBriefsQuestionsInOrder() {
        fun questions(m: MemoTemplate) = m.fields.map { TemplateScript.questionOf(it) }
        assertEquals(listOf("今日の良かったことは？", "うまくいかなかったことは？", "明日やることは？"), questions(StarterTemplates.dailyReview))
        assertEquals(listOf("会議名は？", "参加者は？", "何について話しましたか？", "何が決まりましたか？", "次にやることは？"), questions(StarterTemplates.meetingMemo))
        assertEquals(listOf("どんなアイデアですか？", "何がきっかけでしたか？", "どこが面白いと思いますか？", "次に何を試しますか？"), questions(StarterTemplates.ideaMemo))
        assertEquals(listOf("今日やったことは？", "困っていることはありますか？", "次にやることは？"), questions(StarterTemplates.projectLog))
        assertEquals(TemplateScript.Step.AskTarget, TemplateScript.next(StarterTemplates.projectLog, emptyMap(), null))
        assertEquals("どのプロジェクトですか？名前を教えてください。", TemplateScript.targetQuestionOf(StarterTemplates.projectLog))
        listOf(StarterTemplates.thisWeek, StarterTemplates.yesterdayJournal).forEach { assertEquals("a SEARCH starter asks nothing", TemplateScript.Step.Ready, TemplateScript.next(it, emptyMap(), null)) }
        assertTrue(StarterTemplates.all.size <= StarterTemplates.MAX_STARTERS)
        StarterTemplates.all.forEach { assertTrue(TemplateValidation.problems(it).isEmpty()) }
    }

    @Test
    fun theQuestionTravelsInTheBackupAndTheFileAndALegacyFieldIsAskedByItsLabel() {
        val dto = TemplateBackupMapping.toDto(StarterTemplates.projectLog.copy(id = "u"))
        assertEquals("今日やったことは？", dto.fields[0].question)
        assertEquals("どのプロジェクトですか？", dto.targetQuestion)
        assertEquals(StarterTemplates.projectLog.copy(id = "u"), TemplateBackupMapping.toDomain(dto))
        val file = TemplateFile.export(listOf(StarterTemplates.meetingMemo))
        assertEquals(StarterTemplates.meetingMemo.fields.map { it.question }, (TemplateFile.import(file) as TemplateImport.Ready).templates.single().fields.map { it.question })
        // a field written before questions existed: the label asks
        val legacy = TemplateBackupMapping.toDomain(io.github.cragcoffee.memoripple.backup.TemplateBackupDto("l", "L", "{{x}}", fields = listOf(io.github.cragcoffee.memoripple.backup.TemplateFieldBackupDto("x", "書名", "text"))))!!
        assertEquals("", legacy.fields[0].question)
        assertEquals("書名を入力してください", TemplateScript.questionOf(legacy.fields[0]))
        assertEquals("", legacy.targetQuestion)
    }
}
