package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Template v2 RED (docs/CHAT_UI_TEMPLATE_V2.md): a template is a declarative, reusable action —
 * CREATE / SEARCH / APPEND with typed fields and `{{key}}` substitution, nothing executable. A legacy
 * template (name + body) is a CREATE MEMO template with no fields, byte for byte.
 */
class TemplateV2Test {
    private val json = Json { ignoreUnknownKeys = true }

    // --- RED 23–25: legacy compatibility ---

    @Test
    fun aLegacyTemplateDecodesAsACreateMemoTemplateWithNoFieldsAndTheSameBody() {
        val legacy = """[{"id":"abc","name":"週報","body":"## 今週\n- "}]"""
        val list = json.decodeFromString<List<MemoTemplate>>(legacy)
        val t = list.single()
        assertEquals("abc", t.id)
        assertEquals("週報", t.name)
        assertEquals("## 今週\n- ", t.body)
        assertEquals(TemplateAction.CREATE, t.action)
        assertEquals(DocumentKind.MEMO, t.documentKind)
        assertTrue(t.fields.isEmpty())
        assertNull(t.searchSpec)
        assertNull(t.targetSpec)
        assertEquals("", t.description)
        assertEquals(MemoTemplate.FORMAT_VERSION, t.formatVersion)
    }

    @Test
    fun theThreeArgumentConstructorStillMakesALegacyShapedTemplate() {
        val t = MemoTemplate("id", "名前", "本文")
        assertEquals(TemplateAction.CREATE, t.action)
        assertEquals(DocumentKind.MEMO, t.documentKind)
        assertTrue(t.isLegacyShape)
        val v2 = t.copy(fields = listOf(TemplateField("x", "X", TemplateFieldType.TEXT)))
        assertFalse(v2.isLegacyShape)
    }

    @Test
    fun aV2TemplateRoundTripsThroughJsonWithEveryPart() {
        val t = MemoTemplate(
            id = "m1", name = "会議メモ", body = "# {{meeting_name}}\n\n日付: {{date}}",
            description = "会議のひな形", action = TemplateAction.CREATE, documentKind = DocumentKind.MEMO,
            fields = listOf(
                TemplateField("meeting_name", "会議名", TemplateFieldType.TEXT, required = true),
                TemplateField("date", "日付", TemplateFieldType.DATE, default = "TODAY"),
                TemplateField("kind", "種類", TemplateFieldType.CHOICE, choices = listOf("定例", "臨時"), default = "定例"),
                TemplateField("remote", "オンライン", TemplateFieldType.BOOLEAN, default = "true"),
                TemplateField("notes", "メモ", TemplateFieldType.MULTILINE),
            ),
            createdAt = 1, updatedAt = 2,
        )
        val back = json.decodeFromString<MemoTemplate>(json.encodeToString(MemoTemplate.serializer(), t))
        assertEquals(t, back)
        val s = MemoTemplate(id = "s1", name = "今週のMemoRipple", body = "", action = TemplateAction.SEARCH, searchSpec = TemplateSearchSpec("MemoRipple", TemplateDateToken.THIS_WEEK, setOf(DocumentKind.MEMO, DocumentKind.JOURNAL)))
        assertEquals(s, json.decodeFromString<MemoTemplate>(json.encodeToString(MemoTemplate.serializer(), s)))
        val a = MemoTemplate(id = "a1", name = "開発ログへ追記", body = "- {{entry}}", action = TemplateAction.APPEND, targetSpec = TemplateTargetSpec.Named("MemoRipple開発"), fields = listOf(TemplateField("entry", "内容", TemplateFieldType.TEXT, required = true)))
        assertEquals(a, json.decodeFromString<MemoTemplate>(json.encodeToString(MemoTemplate.serializer(), a)))
        val ask = a.copy(targetSpec = TemplateTargetSpec.AskAtRun)
        assertEquals(ask, json.decodeFromString<MemoTemplate>(json.encodeToString(MemoTemplate.serializer(), ask)))
    }

    // --- RED 26–33: fields, defaults, required, rendering ---

    private val fields = listOf(
        TemplateField("title", "タイトル", TemplateFieldType.TEXT, required = true),
        TemplateField("body", "本文", TemplateFieldType.MULTILINE, default = "（なし）"),
        TemplateField("day", "日付", TemplateFieldType.DATE, default = "TODAY"),
        TemplateField("kind", "種類", TemplateFieldType.CHOICE, choices = listOf("定例", "臨時"), default = "定例"),
        TemplateField("remote", "オンライン", TemplateFieldType.BOOLEAN, default = "false"),
    )
    private val today = LocalDate.of(2026, 9, 21)

    @Test
    fun requiredFieldsMustBeGivenAndDefaultsFillTheRest() {
        val v = TemplateValues.resolve(fields, mapOf("title" to "  "), today)
        assertEquals(setOf("title"), (v as TemplateValues.Missing).keys)
        val ok = TemplateValues.resolve(fields, mapOf("title" to "定例会"), today) as TemplateValues.Ready
        assertEquals("定例会", ok.values["title"])
        assertEquals("（なし）", ok.values["body"])
        assertEquals("2026年9月21日", ok.values["day"])
        assertEquals("定例", ok.values["kind"])
        assertEquals("いいえ", ok.values["remote"])
    }

    @Test
    fun dateChoiceAndBooleanValuesAreCheckedNotTrusted() {
        assertTrue(TemplateValues.resolve(fields, mapOf("title" to "x", "kind" to "未定義"), today) is TemplateValues.Invalid)
        assertTrue(TemplateValues.resolve(fields, mapOf("title" to "x", "day" to "not a date"), today) is TemplateValues.Invalid)
        val yesterday = TemplateValues.resolve(fields, mapOf("title" to "x", "day" to "YESTERDAY"), today) as TemplateValues.Ready
        assertEquals("2026年9月20日", yesterday.values["day"])
        val iso = TemplateValues.resolve(fields, mapOf("title" to "x", "day" to "2026-09-01"), today) as TemplateValues.Ready
        assertEquals("2026年9月1日", iso.values["day"])
        val on = TemplateValues.resolve(fields, mapOf("title" to "x", "remote" to "true"), today) as TemplateValues.Ready
        assertEquals("はい", on.values["remote"])
    }

    @Test
    fun placeholdersAreReplacedAndNothingElseIsInterpreted() {
        val r = TemplateRenderer.render("# {{title}}\n{{body}}\n{{ title }}", mapOf("title" to "定例会", "body" to "本文"))
        assertEquals("# 定例会\n本文\n定例会", (r as TemplateRendering.Rendered).text)
        // a value that looks like a placeholder or an expression stays literal text
        val literal = TemplateRenderer.render("{{title}}", mapOf("title" to "{{body}} \${x} <script>")) as TemplateRendering.Rendered
        assertEquals("{{body}} \${x} <script>", literal.text)
    }

    @Test
    fun anUnknownPlaceholderIsASafeFailureNotEmptyText() {
        val r = TemplateRenderer.render("# {{title}} {{nope}}", mapOf("title" to "x"))
        assertEquals(TemplateRendering.UnknownPlaceholder("nope"), r)
        assertEquals(setOf("title", "nope"), TemplateRenderer.placeholders("# {{title}} {{nope}} {{title}}"))
    }

    @Test
    fun noExpressionSyntaxIsEverAPlaceholder() {
        listOf("{{a + b}}", "{{ if x }}", "{{#each}}", "{{a.b}}", "{{a|upper}}", "{{fn()}}", "{{ }}").forEach {
            assertTrue(it, TemplateRenderer.placeholders(it).isEmpty())
            assertEquals(it, (TemplateRenderer.render(it, emptyMap()) as TemplateRendering.Rendered).text)
        }
    }

    // --- RED 35, 36, 43: validation of a definition ---

    @Test
    fun aDefinitionIsValidOnlyWhenItsPlaceholdersAreDeclaredAndItsKeysAreSafe() {
        val ok = MemoTemplate(id = "m", name = "会議メモ", body = "# {{title}}", fields = listOf(TemplateField("title", "タイトル", TemplateFieldType.TEXT, required = true)))
        assertEquals(emptyList<String>(), TemplateValidation.problems(ok))
        val undeclared = ok.copy(body = "# {{title}} {{who}}")
        assertTrue(TemplateValidation.problems(undeclared).any { it.contains("who") })
        val badKey = ok.copy(fields = listOf(TemplateField("../etc", "x", TemplateFieldType.TEXT)), body = "x")
        assertTrue(TemplateValidation.problems(badKey).isNotEmpty())
        val dupKey = ok.copy(fields = ok.fields + TemplateField("title", "again", TemplateFieldType.TEXT))
        assertTrue(TemplateValidation.problems(dupKey).isNotEmpty())
        val choiceless = ok.copy(fields = listOf(TemplateField("title", "x", TemplateFieldType.CHOICE)))
        assertTrue(TemplateValidation.problems(choiceless).isNotEmpty())
        val search = MemoTemplate(id = "s", name = "検索", body = "", action = TemplateAction.SEARCH, searchSpec = TemplateSearchSpec("", null, setOf(DocumentKind.MEMO)))
        assertTrue("an unbounded search is refused", TemplateValidation.problems(search).isNotEmpty())
        assertEquals(emptyList<String>(), TemplateValidation.problems(search.copy(searchSpec = TemplateSearchSpec("", TemplateDateToken.TODAY, setOf(DocumentKind.JOURNAL)))))
        val append = MemoTemplate(id = "a", name = "追記", body = "- {{e}}", action = TemplateAction.APPEND, fields = listOf(TemplateField("e", "内容", TemplateFieldType.TEXT, required = true)))
        assertTrue("an append needs a target spec", TemplateValidation.problems(append).isNotEmpty())
        assertEquals(emptyList<String>(), TemplateValidation.problems(append.copy(targetSpec = TemplateTargetSpec.AskAtRun)))
        val emptyCreate = MemoTemplate(id = "c", name = "空", body = "  ")
        assertTrue("a create with no body does nothing", TemplateValidation.problems(emptyCreate).isNotEmpty())
        val tooManyFields = ok.copy(fields = (1..(TemplateValidation.MAX_FIELDS + 1)).map { TemplateField("f$it", "f", TemplateFieldType.TEXT) }, body = "x")
        assertTrue(TemplateValidation.problems(tooManyFields).isNotEmpty())
    }
}
