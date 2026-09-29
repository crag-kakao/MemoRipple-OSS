package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface TemplateRendering {
    data class Rendered(val text: String) : TemplateRendering
    data class UnknownPlaceholder(val key: String) : TemplateRendering
}

/**
 * `{{key}}` substitution and nothing else. A placeholder is exactly a field key (`[a-z0-9_]`,
 * optional spaces inside the braces); anything else between braces — an expression, a filter, a
 * path, a call — is not a placeholder and stays literal text. Values are inserted as they are:
 * a value that looks like a placeholder is text, never rendered again.
 */
object TemplateRenderer {
    val KEY = Regex("[a-z0-9_]{1,32}")
    private val PLACEHOLDER = Regex("\\{\\{\\s*([a-z0-9_]{1,32})\\s*\\}\\}")

    fun placeholders(text: String): Set<String> = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()

    fun render(text: String, values: Map<String, String>): TemplateRendering {
        placeholders(text).firstOrNull { it !in values }?.let { return TemplateRendering.UnknownPlaceholder(it) }
        val out = PLACEHOLDER.replace(text) { m -> values.getValue(m.groupValues[1]) }   // a lambda result is literal text, never a replacement pattern
        return TemplateRendering.Rendered(out)
    }
}

/** The values of a run, checked against the fields: what is missing, what is invalid, or the resolved text per key. */
sealed interface TemplateValues {
    data class Ready(val values: Map<String, String>) : TemplateValues
    data class Missing(val keys: Set<String>) : TemplateValues
    data class Invalid(val key: String, val reason: String) : TemplateValues

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN)

        /** `{{today}}`: the run's day as `yyyy-MM-dd` — filled by the clock, never a field, so its key is reserved. */
        const val TODAY_KEY = "today"
        const val TODAY_LABEL = "今日の日付"

        /**
         * Given values fill the fields; an empty one falls back to the field's default; a required
         * field with neither is missing; DATE / CHOICE / BOOLEAN values are checked, never trusted.
         * DATE: `TODAY` / `YESTERDAY` (the app's clock) or an ISO date → 「yyyy年M月d日」; BOOLEAN →
         * 「はい」 / 「いいえ」.
         */
        fun resolve(fields: List<TemplateField>, given: Map<String, String>, today: LocalDate): TemplateValues {
            val out = LinkedHashMap<String, String>()
            // the one value every template has without asking: the day, from the app's clock (docs/THINK_TEMPLATES.md)
            out[TODAY_KEY] = today.toString()
            val missing = LinkedHashSet<String>()
            for (f in fields) {
                val raw = given[f.key]?.trim().orEmpty().ifEmpty { f.default.trim() }
                if (raw.isEmpty()) {
                    if (f.required) missing += f.key else out[f.key] = ""
                    continue
                }
                out[f.key] = when (f.type) {
                    TemplateFieldType.TEXT, TemplateFieldType.MULTILINE -> raw
                    TemplateFieldType.DATE -> resolveDate(raw, today)?.format(DAY) ?: return Invalid(f.key, "日付として読めません")
                    TemplateFieldType.CHOICE -> if (raw in f.choices) raw else return Invalid(f.key, "選択肢にありません")
                    TemplateFieldType.BOOLEAN -> when (raw.lowercase()) {
                        "true", "yes", "1", "はい", "on" -> "はい"
                        "false", "no", "0", "いいえ", "off" -> "いいえ"
                        else -> return Invalid(f.key, "はい / いいえ で答えてください")
                    }
                }
            }
            return if (missing.isNotEmpty()) Missing(missing) else Ready(out)
        }

        /** A DATE value as the run would print it, or null when it is not a date. */
        fun formatDate(raw: String, today: LocalDate): String? = resolveDate(raw, today)?.format(DAY)

        fun resolveDate(raw: String, today: LocalDate): LocalDate? = when (raw.trim().uppercase()) {
            "TODAY", "今日" -> today
            "YESTERDAY", "昨日" -> today.minusDays(1)
            else -> runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
        }
    }
}

/** What makes a definition usable — checked when it is saved, imported and run. Problems are the user's words. */
object TemplateValidation {
    const val MAX_FIELDS = 12
    const val MAX_BODY_CHARS = 20_000
    const val MAX_DESCRIPTION_CHARS = 200
    const val MAX_CHOICES = 20
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")

    fun problems(t: MemoTemplate): List<String> {
        val out = ArrayList<String>()
        if (!ID.matches(t.id)) out += "テンプレートIDが不正です"
        if (t.name.isBlank()) out += "名前が空です"
        if (t.name.length > MemoTemplatePolicy.MAX_NAME_CHARS) out += "名前が長すぎます"
        if (t.description.length > MAX_DESCRIPTION_CHARS) out += "説明が長すぎます"
        if (t.body.length > MAX_BODY_CHARS) out += "本文が長すぎます"
        if (t.fields.size > MAX_FIELDS) out += "項目が多すぎます（最大 $MAX_FIELDS）"
        val keys = HashSet<String>()
        val labels = HashSet<String>()
        t.fields.forEachIndexed { i, f ->
            val shown = f.label.trim().ifBlank { "${i + 1}番目" }   // a person knows a field by its label, never by its key
            if (!TemplateRenderer.KEY.matches(f.key)) out += "項目「$shown」の内部名が不正です"
            if (!keys.add(f.key)) out += "項目「$shown」の内部名が重複しています"
            if (f.label.isBlank()) out += "${i + 1}番目の項目の名前を入力してください"
            else if (!labels.add(f.label.trim())) out += "項目「$shown」の名前が重複しています"
            if (f.type == TemplateFieldType.CHOICE && (f.choices.isEmpty() || f.choices.size > MAX_CHOICES || f.choices.any { it.isBlank() })) out += "項目「$shown」の選択肢を1〜$MAX_CHOICES 件入れてください"
            if (f.type == TemplateFieldType.CHOICE && f.default.isNotBlank() && f.default !in f.choices) out += "項目「$shown」の初期値が選択肢にありません"
            if (f.key == TemplateTargetSpec.TARGET_KEY || f.key == TemplateValues.TODAY_KEY) out += "項目「$shown」の内部名は予約されています"
        }
        val declared = t.fields.map { it.key }.toSet() + TemplateValues.TODAY_KEY
        val used = TemplateRenderer.placeholders(t.body) + (t.searchSpec?.let { TemplateRenderer.placeholders(it.query) } ?: emptySet())
        (used - declared).forEach { out += "本文の「${TemplateBodyDisplay.OPEN}$it${TemplateBodyDisplay.CLOSE}」に対応する項目がありません" }
        if (t.flow == TemplateFlow.THINK) {
            // Think (docs/THINK_TEMPLATES.md): a conversation that ends in a result — questions to ask, a memo to offer
            if (t.action != TemplateAction.CREATE || t.documentKind != DocumentKind.MEMO) out += "整理するテンプレートは、メモを作る操作でだけ使えます"
            if (t.fields.isEmpty()) out += "整理するテンプレートには質問が1つ以上必要です"
        }
        when (t.action) {
            TemplateAction.CREATE -> if (t.body.isBlank()) out += "作成する本文が空です"
            TemplateAction.SEARCH -> {
                val s = t.searchSpec
                if (s == null) out += "検索条件がありません"
                else {
                    if (s.query.isBlank() && s.dateToken == null) out += "検索する言葉か日付が必要です"
                    if (s.kinds.isEmpty()) out += "検索する種類を1つ以上選んでください"
                }
            }
            TemplateAction.APPEND -> {
                if (t.body.isBlank()) out += "追記する本文が空です"
                when (val ts = t.targetSpec) {
                    null -> out += "追記先がありません"
                    is TemplateTargetSpec.Named -> if (ts.name.isBlank()) out += "追記先の名前が空です"
                    TemplateTargetSpec.AskAtRun -> Unit
                }
            }
        }
        return out
    }
}
