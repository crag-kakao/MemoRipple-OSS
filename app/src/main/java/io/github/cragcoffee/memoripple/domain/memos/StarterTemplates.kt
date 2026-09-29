package io.github.cragcoffee.memoripple.domain.memos

import io.github.cragcoffee.memoripple.domain.documents.DocumentKind

/**
 * The built-in starter templates — six that record or search (docs/CHAT_UI_TEMPLATE_V2.md §14, §16)
 * and four that think (docs/THINK_TEMPLATES.md): read-only code, never
 * copied into the store by the app itself — the picker lists them beside the user's own, the AI
 * lookup sees them by id and name, and the editor opens a *copy* (a new id) the user may change.
 * Each is a conversation script: its fields are asked one at a time, in this order, with these
 * questions. They are declarative like every template: no code, no path, no URL; they run
 * through the same validation, preview and Human Confirmation.
 */
object StarterTemplates {
    const val MAX_STARTERS = 12
    const val ID_PREFIX = "starter-"

    val dailyReview = MemoTemplate(
        id = "starter-daily-review", name = "今日の振り返り", description = "一日の終わりに、三つの質問で日記へ",
        action = TemplateAction.CREATE, documentKind = DocumentKind.JOURNAL,
        fields = listOf(
            TemplateField("good", "良かったこと", TemplateFieldType.MULTILINE, required = true, question = "今日の良かったことは？"),
            TemplateField("issue", "うまくいかなかったこと", TemplateFieldType.MULTILINE, question = "うまくいかなかったことは？"),
            TemplateField("tomorrow", "明日やること", TemplateFieldType.MULTILINE, question = "明日やることは？"),
        ),
        body = "## 良かったこと\n{{good}}\n\n## うまくいかなかったこと\n{{issue}}\n\n## 明日やること\n{{tomorrow}}\n",
    )

    val meetingMemo = MemoTemplate(
        id = "starter-meeting-memo", name = "会議メモ", description = "会議名・参加者・話したこと・決まったこと・次にやること",
        action = TemplateAction.CREATE, documentKind = DocumentKind.MEMO,
        fields = listOf(
            TemplateField("meeting_name", "会議名", TemplateFieldType.TEXT, required = true, question = "会議名は？"),
            TemplateField("participants", "参加者", TemplateFieldType.TEXT, question = "参加者は？"),
            TemplateField("agenda", "話したこと", TemplateFieldType.MULTILINE, question = "何について話しましたか？"),
            TemplateField("decisions", "決まったこと", TemplateFieldType.MULTILINE, question = "何が決まりましたか？"),
            TemplateField("next_actions", "次にやること", TemplateFieldType.MULTILINE, question = "次にやることは？"),
        ),
        body = "# {{meeting_name}}\n\n参加者: {{participants}}\n\n## 話したこと\n{{agenda}}\n\n## 決まったこと\n{{decisions}}\n\n## 次にやること\n{{next_actions}}\n",
    )

    val ideaMemo = MemoTemplate(
        id = "starter-idea-memo", name = "アイデアメモ", description = "アイデア・きっかけ・面白いところ・次に試すこと",
        action = TemplateAction.CREATE, documentKind = DocumentKind.MEMO,
        fields = listOf(
            TemplateField("idea", "アイデア", TemplateFieldType.MULTILINE, required = true, question = "どんなアイデアですか？"),
            TemplateField("trigger", "きっかけ", TemplateFieldType.TEXT, question = "何がきっかけでしたか？"),
            TemplateField("why", "面白いところ", TemplateFieldType.MULTILINE, question = "どこが面白いと思いますか？"),
            TemplateField("next_step", "次に試すこと", TemplateFieldType.TEXT, question = "次に何を試しますか？"),
        ),
        body = "# アイデア\n{{idea}}\n\n## きっかけ\n{{trigger}}\n\n## 面白いところ\n{{why}}\n\n## 次に試すこと\n{{next_step}}\n",
    )

    val projectLog = MemoTemplate(
        id = "starter-project-log", name = "プロジェクトログ", description = "選んだメモに、今日やったこと・困りごと・次の一歩を追記",
        action = TemplateAction.APPEND, documentKind = DocumentKind.MEMO, targetSpec = TemplateTargetSpec.AskAtRun, targetQuestion = "どのプロジェクトですか？",
        fields = listOf(
            TemplateField("done", "今日やったこと", TemplateFieldType.MULTILINE, required = true, question = "今日やったことは？"),
            TemplateField("issue", "困っていること", TemplateFieldType.MULTILINE, question = "困っていることはありますか？"),
            TemplateField("next", "次にやること", TemplateFieldType.MULTILINE, question = "次にやることは？"),
        ),
        body = "- 今日やったこと: {{done}}\n- 困っていること: {{issue}}\n- 次にやること: {{next}}",
    )

    val thisWeek = MemoTemplate(
        id = "starter-this-week", name = "今週の記録を探す", description = "今週作った・書いたメモ、アウトライン、日記",
        action = TemplateAction.SEARCH, body = "",
        searchSpec = TemplateSearchSpec(query = "", dateToken = TemplateDateToken.THIS_WEEK, kinds = setOf(DocumentKind.MEMO, DocumentKind.OUTLINE, DocumentKind.JOURNAL)),
    )

    val yesterdayJournal = MemoTemplate(
        id = "starter-yesterday-journal", name = "昨日の日記を探す", description = "昨日の日記",
        action = TemplateAction.SEARCH, body = "",
        searchSpec = TemplateSearchSpec(query = "", dateToken = TemplateDateToken.YESTERDAY, kinds = setOf(DocumentKind.JOURNAL)),
    )

    // --- Think starters (docs/THINK_TEMPLATES.md, 2026-09-22): a conversation that ends in a result, a memo only on request ---

    val thinkTodayTasks = MemoTemplate(
        id = "starter-think-today-tasks", name = "今日やること整理", description = "今日のタスクを、四つの質問で整理する",
        action = TemplateAction.CREATE, documentKind = DocumentKind.MEMO, flow = TemplateFlow.THINK,
        fields = listOf(
            TemplateField("tasks", "やること", TemplateFieldType.MULTILINE, required = true, question = "今日やる必要があることを、思いつくまま教えてください。"),
            TemplateField("priority", "今日中に終わらせたいこと", TemplateFieldType.TEXT, question = "その中で、今日中に終わらせたいものはどれですか？"),
            TemplateField("first_step", "最初にやること", TemplateFieldType.TEXT, question = "最初に取りかかるものは何ですか？"),
            TemplateField("concerns", "気になること", TemplateFieldType.MULTILINE, question = "困りそうなことはありますか？"),
        ),
        body = "今日やること - {{today}}\n\n## やること\n{{tasks}}\n\n## 今日中に終わらせたいこと\n{{priority}}\n\n## 最初にやること\n{{first_step}}\n\n## 気になること\n{{concerns}}\n",
    )

    val thinkIdea = MemoTemplate(
        id = "starter-think-idea", name = "アイデア壁打ち", description = "アイデアを六つの質問で壁打ちして、形にする",
        action = TemplateAction.CREATE, documentKind = DocumentKind.MEMO, flow = TemplateFlow.THINK,
        fields = listOf(
            TemplateField("idea", "アイデア", TemplateFieldType.TEXT, required = true, question = "どんなアイデアですか？"),
            TemplateField("purpose", "目的", TemplateFieldType.MULTILINE, question = "何を解決したいですか？"),
            TemplateField("audience", "対象", TemplateFieldType.TEXT, question = "誰が使うものですか？"),
            TemplateField("strength", "強み", TemplateFieldType.MULTILINE, question = "面白いと思う点はどこですか？"),
            TemplateField("concern", "懸念", TemplateFieldType.MULTILINE, question = "気になっている問題はありますか？"),
            TemplateField("next_step", "次の一歩", TemplateFieldType.TEXT, question = "次に試すなら何をしますか？"),
        ),
        body = "アイデア - {{idea}}\n\n## アイデア\n{{idea}}\n\n## 目的\n{{purpose}}\n\n## 対象\n{{audience}}\n\n## 強み\n{{strength}}\n\n## 懸念\n{{concern}}\n\n## 次の一歩\n{{next_step}}\n",
    )

    val thinkPlan = MemoTemplate(
        id = "starter-think-plan", name = "企画整理", description = "企画の目的・対象・価値・違い・課題・次の判断を整理する",
        action = TemplateAction.CREATE, documentKind = DocumentKind.MEMO, flow = TemplateFlow.THINK,
        fields = listOf(
            TemplateField("plan", "企画概要", TemplateFieldType.TEXT, required = true, question = "何を企画していますか？"),
            TemplateField("purpose", "目的", TemplateFieldType.MULTILINE, question = "その目的は何ですか？"),
            TemplateField("audience", "対象", TemplateFieldType.TEXT, question = "誰に使ってほしいですか？"),
            TemplateField("value", "価値", TemplateFieldType.MULTILINE, question = "一番大事な価値は何ですか？"),
            TemplateField("difference", "違い", TemplateFieldType.MULTILINE, question = "似たものとの違いは何ですか？"),
            TemplateField("issues", "課題", TemplateFieldType.MULTILINE, question = "実現するうえでの課題は何ですか？"),
            TemplateField("next_decision", "次の判断", TemplateFieldType.TEXT, question = "次に決めることは何ですか？"),
        ),
        body = "企画 - {{plan}}\n\n## 企画概要\n{{plan}}\n\n## 目的\n{{purpose}}\n\n## 対象\n{{audience}}\n\n## 価値\n{{value}}\n\n## 違い\n{{difference}}\n\n## 課題\n{{issues}}\n\n## 次の判断\n{{next_decision}}\n",
    )

    val thinkProject = MemoTemplate(
        id = "starter-think-project", name = "プロジェクト整理", description = "現在地・残作業・課題・次の作業を整理する",
        action = TemplateAction.CREATE, documentKind = DocumentKind.MEMO, flow = TemplateFlow.THINK,
        fields = listOf(
            TemplateField("project", "プロジェクト", TemplateFieldType.TEXT, required = true, question = "どのプロジェクトについて整理しますか？"),
            TemplateField("progress", "現在地", TemplateFieldType.MULTILINE, question = "今どこまで進んでいますか？"),
            TemplateField("remaining", "残作業", TemplateFieldType.MULTILINE, question = "残っている作業は何ですか？"),
            TemplateField("blocker", "課題", TemplateFieldType.MULTILINE, question = "今詰まっていることはありますか？"),
            TemplateField("next", "次の作業", TemplateFieldType.MULTILINE, question = "次にやることは何ですか？"),
        ),
        body = "プロジェクト整理 - {{project}}\n\n## 現在地\n{{progress}}\n\n## 残作業\n{{remaining}}\n\n## 課題\n{{blocker}}\n\n## 次の作業\n{{next}}\n",
    )

    val all: List<MemoTemplate> = listOf(dailyReview, meetingMemo, ideaMemo, projectLog, thisWeek, yesterdayJournal, thinkTodayTasks, thinkIdea, thinkPlan, thinkProject)

    fun isStarter(id: String): Boolean = id.startsWith(ID_PREFIX)

    fun find(idOrName: String): MemoTemplate? = all.firstOrNull { it.id == idOrName } ?: all.firstOrNull { it.name == idOrName.trim() }
}
