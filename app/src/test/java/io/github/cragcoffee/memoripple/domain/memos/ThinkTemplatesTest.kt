package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Think templates (docs/THINK_TEMPLATES.md, human brief 2026-09-22): a template whose *flow* is
 * THINK asks its questions like any other and then shows a deterministic result instead of a
 * preview — no model, no write; the four starters ask the brief's questions in its order; the
 * result is rendered by `{{key}}` substitution with a `{{today}}` the app's clock fills; a
 * skipped question reads 「（なし）」; the first line is the memo's title should the user save.
 */
class ThinkTemplatesTest {
    private val today: LocalDate = LocalDate.of(2026, 9, 22)

    @Test
    fun theFlowIsSeparateFromTheActionAndDefaultsToRecord() {
        val legacy = MemoTemplate("l", "朝", "# 朝")
        assertEquals(TemplateFlow.RECORD, legacy.flow)
        assertTrue(legacy.isLegacyShape)
        val think = legacy.copy(flow = TemplateFlow.THINK, fields = listOf(TemplateField("a", "A", TemplateFieldType.TEXT, question = "A?")))
        assertEquals("a Think template is still a CREATE MEMO template underneath", TemplateAction.CREATE, think.action)
        assertEquals(DocumentKind.MEMO, think.documentKind)
        assertFalse(think.isLegacyShape)
    }

    @Test
    fun theFourStartersAskTheBriefsQuestionsInItsOrder() {
        val all = StarterTemplates.all
        assertEquals(10, all.size)
        assertTrue(StarterTemplates.MAX_STARTERS >= 10)
        val think = all.filter { it.flow == TemplateFlow.THINK }
        assertEquals(listOf("今日やること整理", "アイデア壁打ち", "企画整理", "プロジェクト整理"), think.map { it.name })
        assertEquals(listOf("starter-think-today-tasks", "starter-think-idea", "starter-think-plan", "starter-think-project"), think.map { it.id })
        think.forEach { t ->
            assertEquals(t.name, TemplateAction.CREATE, t.action)
            assertEquals(t.name, DocumentKind.MEMO, t.documentKind)
            assertTrue("${t.name} is a valid definition: ${TemplateValidation.problems(t)}", TemplateValidation.problems(t).isEmpty())
            assertTrue("${t.name} asks at least one question", t.fields.isNotEmpty())
            assertTrue("${t.name}'s first question is required", t.fields.first().required)
        }
        assertEquals(
            listOf("今日やる必要があることを、思いつくまま教えてください。", "その中で、今日中に終わらせたいものはどれですか？", "最初に取りかかるものは何ですか？", "困りそうなことはありますか？"),
            StarterTemplates.thinkTodayTasks.fields.map { it.question },
        )
        assertEquals(
            listOf("どんなアイデアですか？", "何を解決したいですか？", "誰が使うものですか？", "面白いと思う点はどこですか？", "気になっている問題はありますか？", "次に試すなら何をしますか？"),
            StarterTemplates.thinkIdea.fields.map { it.question },
        )
        assertEquals(
            listOf("何を企画していますか？", "その目的は何ですか？", "誰に使ってほしいですか？", "一番大事な価値は何ですか？", "似たものとの違いは何ですか？", "実現するうえでの課題は何ですか？", "次に決めることは何ですか？"),
            StarterTemplates.thinkPlan.fields.map { it.question },
        )
        assertEquals(
            listOf("どのプロジェクトについて整理しますか？", "今どこまで進んでいますか？", "残っている作業は何ですか？", "今詰まっていることはありますか？", "次にやることは何ですか？"),
            StarterTemplates.thinkProject.fields.map { it.question },
        )
        assertFalse("困りそうなことは optional", StarterTemplates.thinkTodayTasks.fields.last().required)
        // the six record starters are untouched
        listOf("starter-daily-review", "starter-meeting-memo", "starter-idea-memo", "starter-project-log", "starter-this-week", "starter-yesterday-journal").forEach { id ->
            assertEquals(id, TemplateFlow.RECORD, all.first { it.id == id }.flow)
        }
    }

    @Test
    fun theScriptAsksAThinkTemplateOneQuestionAtATimeAndSkipsAnOptionalOne() {
        val t = StarterTemplates.thinkTodayTasks
        var answers = emptyMap<String, String>()
        val asked = ArrayList<String>()
        while (true) {
            when (val step = TemplateScript.next(t, answers, null)) {
                is TemplateScript.Step.Ask -> { asked += step.field.question; answers = answers + (step.field.key to if (step.field.required) "回答" else "") }
                TemplateScript.Step.Ready -> break
                TemplateScript.Step.AskTarget -> throw AssertionError("a Think template never asks a target")
            }
        }
        assertEquals(t.fields.map { it.question }, asked)
    }

    @Test
    fun theResultIsDeterministicNamesTheDayAndReadsASkipAsNone() {
        val t = StarterTemplates.thinkTodayTasks
        val answers = mapOf("tasks" to "MemoRipple開発\n買い物", "priority" to "MemoRipple開発", "first_step" to "Chat UI確認", "concerns" to "")
        val result = ThinkTemplates.result(t, answers, today)
        val expected = "今日やること - 2026-09-22\n\n## やること\nMemoRipple開発\n買い物\n\n## 今日中に終わらせたいこと\nMemoRipple開発\n\n## 最初にやること\nChat UI確認\n\n## 気になること\n（なし）\n"
        assertEquals(expected, result.text)
        assertEquals("今日やること - 2026-09-22", result.title)
        assertEquals("the same answers give the same words, every time", result, ThinkTemplates.result(t, answers, today))
        // the body saved as a memo is the result text itself — its first line is the memo's title
        val idea = ThinkTemplates.result(StarterTemplates.thinkIdea, StarterTemplates.thinkIdea.fields.associate { it.key to if (it.key == "idea") "音声メモ" else "x" }, today)
        assertEquals("アイデア - 音声メモ", idea.title)
        val plan = ThinkTemplates.result(StarterTemplates.thinkPlan, StarterTemplates.thinkPlan.fields.associate { it.key to if (it.key == "plan") "読書会" else "x" }, today)
        assertEquals("企画 - 読書会", plan.title)
        val project = ThinkTemplates.result(StarterTemplates.thinkProject, StarterTemplates.thinkProject.fields.associate { it.key to if (it.key == "project") "MemoRipple" else "x" }, today)
        assertEquals("プロジェクト整理 - MemoRipple", project.title)
    }

    @Test
    fun todayIsAPlaceholderTheClockFillsAndAReservedKey() {
        val values = TemplateValues.resolve(emptyList(), emptyMap(), today) as TemplateValues.Ready
        assertEquals("2026-09-22", values.values["today"])
        val t = MemoTemplate("t", "日付", "{{today}} のメモ")
        assertTrue("{{today}} needs no field", TemplateValidation.problems(t).isEmpty())
        val bad = t.copy(fields = listOf(TemplateField("today", "今日", TemplateFieldType.TEXT)))
        assertTrue(TemplateValidation.problems(bad).any { it.contains("予約") })
        assertEquals("⟦今日の日付⟧ のメモ", TemplateBodyDisplay.toDisplay("{{today}} のメモ", emptyList()))
        assertEquals("{{today}} のメモ", TemplateBodyDisplay.fromDisplay("⟦今日の日付⟧ のメモ", emptyList()))
    }

    @Test
    fun aThinkTemplateMustBeACreateMemoWithAtLeastOneQuestion() {
        val ok = MemoTemplate("t", "考える", "{{a}}", flow = TemplateFlow.THINK, fields = listOf(TemplateField("a", "A", TemplateFieldType.TEXT, question = "A?")))
        assertTrue(TemplateValidation.problems(ok).isEmpty())
        assertTrue("no question → refused", TemplateValidation.problems(ok.copy(fields = emptyList(), body = "x")).isNotEmpty())
        assertTrue("a SEARCH cannot think", TemplateValidation.problems(ok.copy(action = TemplateAction.SEARCH, searchSpec = TemplateSearchSpec("x"))).isNotEmpty())
        assertTrue("an APPEND cannot think", TemplateValidation.problems(ok.copy(action = TemplateAction.APPEND, targetSpec = TemplateTargetSpec.AskAtRun)).isNotEmpty())
        assertTrue("a journal / outline is a later decision", TemplateValidation.problems(ok.copy(documentKind = DocumentKind.JOURNAL)).isNotEmpty())
    }

    @Test
    fun aThinkTemplateRoundTripsThroughTheTemplateFile() {
        val custom = MemoTemplate(
            "u-reading", "読書の振り返り", "読書 - {{book}}\n\n## 印象に残ったこと\n{{impression}}\n\n## 疑問\n{{question}}\n\n## 次に調べたいこと\n{{next}}\n",
            flow = TemplateFlow.THINK,
            fields = listOf(
                TemplateField("book", "本", TemplateFieldType.TEXT, required = true, question = "何を読んだ？"),
                TemplateField("impression", "印象に残ったこと", TemplateFieldType.MULTILINE, question = "印象に残ったことは？"),
                TemplateField("question", "疑問", TemplateFieldType.MULTILINE, question = "疑問は？"),
                TemplateField("next", "次に調べたいこと", TemplateFieldType.TEXT, question = "次に調べたいことは？"),
            ),
        )
        val text = TemplateFile.export(listOf(custom, StarterTemplates.thinkIdea))
        val back = TemplateFile.import(text) as TemplateImport.Ready
        assertEquals(listOf(custom, StarterTemplates.thinkIdea), back.templates)
        assertTrue(text.contains("\"flow\":\"THINK\""))
    }
}
