| a template | by what it does | that template's own script — a Think template starts Think; a long press renames it or takes it off the home |
| 追加 | `Add` | the ＋ picker in its **choosing** mode: a tap puts a template on the home instead of running it |# Chat UI Redesign + Template v2

> Branch `feature/chat-ui-template-v2` from `main` `5378f07`, 2026-09-21, after the user's Chat v1
> UI/UX Personal Review. Two changes in one round: the chat becomes **one conversation** (the
> 検索 / AI switch, the date and kind chips, the search results section and the 定型操作 buttons leave
> the chat top), and a template becomes a **reusable, declarative action** (CREATE / SEARCH / APPEND
> with typed fields) that runs with or without a Local AI model through the same preview and
> Human Confirmation. Read `docs/AI_CHAT_PREVIEW.md`, `docs/AI_CONFIRMED_WRITE.md` and
> `docs/AI_CONVERSATION_HISTORY.md` first.

## 1. The chat's role

チャット is **the place to ask MemoRipple for something in natural words**. Manual exploration
belongs to the memo wall's search, the folders and the calendar; the chat's search is the AI's
SEARCH intent or a SEARCH template, answered as result cards inside the conversation — never a
search screen. Removed from the chat top: the segmented control, 今日 / 昨日 / 今週, すべて / メモ /
アウトライン / 日記, the results section, 定型操作 with 新しいメモ / 新しいアウトライン / 今日の日記を書く /
カレンダーを開く. `DocumentAccess.search` and the chat's own view model no longer meet: every read
and write goes through the orchestrator (Resolver → DocumentAccess), as an AI ask always did.

## 2. Screen

- **Top bar** (shape since §13): the menu icon = history (`chat_history`; on the stage a back arrow
  `chat_stage_back`), the conversation's title (`chat_title`, 「チャット」 with none) with the model as a
  tappable subtitle (`chat_subtitle` / `chat_model_button`), the compose icon = new chat
  (`chat_new_conversation`, opens the stage from the tab), overflow (`chat_overflow`: テンプレートを管理 /
  Local AIモデル / 設定; on the stage also チャット履歴).
- **Body**: the transcript, then the current operation's cards. USER lines are dark rounded bubbles on
  the right (`surfaceContainerHigh`); ASSISTANT lines are flat text on the left with a copy action and,
  under the current answer, the model's timing; a failure line is in the error colour. Cards under the last assistant line: search results, candidates, the CREATE /
  APPEND / template preview with キャンセル and the one confirm button, the written / conflict /
  failure cards, the setup card. Nothing is ever rebuilt from history.
- **Empty state** (`chat_empty`): 「MemoRippleに何を頼みますか？」 and three example lines
  (`chat_example`) a tap puts into the input — no chip wall.
- **Bottom bar**: ＋ (`chat_plus`), the input (`chat_input`, 「メッセージを入力」), send (`chat_send`); it
  follows the keyboard (§13).
  With no usable model a one-line hint above it (`chat_ai_hint`: 「自由文の依頼にはLocal AIモデルが必要です。
  ＋のテンプレートはそのまま使えます。」 + 設定) — the screen is never replaced by a setup card; a free-text
  send then answers with the setup card as a result.
- **＋** opens the template picker sheet (`chat_template_picker`): the templates as rows
  (`chat_template_item_<id>`, name, description, 作成 / 検索 / 追記), 「テンプレートを作成」
  (`chat_template_create`) and 「テンプレートを管理」. Back closes the sheet.
- **Form** (`chat_template_form`): one control per field (`chat_template_field_<key>`), the target
  name when the template asks for it (`chat_template_field___target`), キャンセル / 確認へ (検索 for a
  SEARCH template). A required field left empty keeps the form and names it
  (`chat_template_form_missing`). Ephemeral: nothing typed here is stored until it runs.
- **Back**: form → close; preview or candidates → close; while a write runs → nothing; the stage →
  the tab; history → chat; an opened document → chat; the top-level chat → メモ.
- Saved state: the draft input only (`KEY_INPUT`). The current conversation stays the one
  preference of Phase 8.

## 3. AI optional

Free text needs a model: the orchestrator's availability decides the hint and what a send comes
back with (Phase 7's card as a result, with 「AIモデルを設定」). Everything else needs none: the
history, the templates, the editor, a template run, a preview, a confirmation, an opened result.
On an unsupported CPU the hint says so and offers no setup.

## 4. Template v2

`MemoTemplate` (DataStore JSON, `TemplateRepository`, unchanged store):

| Part | Meaning |
|---|---|
| `id`, `name`, `description` | as before, plus one line of description |
| `action` | `CREATE` (a document from `body`), `SEARCH` (a fixed query), `APPEND` (a rendered text to a target) |
| `documentKind` | what CREATE makes: メモ / アウトライン / 日記 (a journal on the day the template runs) |
| `fields` | `TemplateField(key, label, type, required, default, choices)`; types TEXT / MULTILINE / DATE / CHOICE / BOOLEAN; at most 12 |
| `body` | CREATE: the document body; APPEND: the appended text; `{{key}}` placeholders |
| `searchSpec` | `query` (placeholders allowed), `dateToken` (TODAY / YESTERDAY / THIS_WEEK / LAST_WEEK, resolved by the app's clock), `kinds` |
| `targetSpec` | `Named(name)` — resolved by search every run (exact title, then partial; one → preview, several → choice, none → not found) — or `AskAtRun` (the form asks the name). Never an id. |
| `createdAt`, `updatedAt`, `formatVersion` | bookkeeping |

**Legacy compatibility.** Every new part has a default; old JSON (`id`, `name`, `body`) decodes as
`CREATE` / `MEMO` / no fields / the same body; the three-argument constructor still builds that
shape; `isLegacyShape` names it. The memo editor's 「テンプレートとして保存」 still makes such a
template; the editor's insert list shows only body-only CREATE templates (a template with fields
runs from the chat). **No Room change** (25 stays), no DataStore key change.

**Values.** `TemplateValues.resolve(fields, given, today)`: a given value fills a field, an empty
one falls back to the default, a required field with neither is *missing*; DATE accepts `TODAY` /
`YESTERDAY` / an ISO date and renders 「yyyy年M月d日」; CHOICE must be one of the choices; BOOLEAN
renders 「はい」 / 「いいえ」. Missing or invalid → the form, never a guess.

**Rendering.** `{{key}}` (a field key, `[a-z0-9_]{1,32}`, optional spaces inside the braces) and
nothing else; an undeclared placeholder is a safe failure; anything else between braces — an
expression, a filter, a path, a call — is literal text; a value is inserted as text, never
rendered again. No JavaScript, no expressions, no loops, no conditions, no code, anywhere.

**Validation** (`TemplateValidation.problems`, on save, on import, before a run): a safe id and
keys, unique keys, labels, choices for CHOICE, declared placeholders only, a bounded search, a
target for APPEND, a body for CREATE / APPEND, the caps (12 fields, 20,000 body characters).

## 5. Execution — one path with or without a model

```
＋ → template → [form] → AiOrchestrator.runTemplate(template, values)
   → TemplateValidation → TemplateValues → TemplateRenderer
   → SEARCH: DocumentQuery → CommandExecutor (direct) → result cards
   → CREATE: ResolvedCommand.UseTemplate(template, renderedBody, request) → ExecutionPolicy → CommandPreview.Template → PendingWrite
   → APPEND: IntentProposal(APPEND, targetName, text) → Resolver (exact → partial, read-only check, version) → ExecutionPolicy → CommandPreview.Append → PendingWrite
   → the same confirm button → AiOrchestrator.execute(pending) → CommandExecutor → DocumentAccess
```

No model, no runtime, no thermal gate is touched by a template run (policy-pinned); the confirm
boundary is unchanged (one `confirm()` caller, one `execute(` in the view model, one ticket = one
write). The model's `USE_TEMPLATE` goes into the same runner: a template with fields opens the
form (the model never fills values — prompt v1 stays frozen); a legacy template renders its body
and previews as before. AI field-value proposal and AI-drafted templates are **deferred** (§9).

## 6. Conversation events

A template run is a turn of the conversation: the user's line is the template's name, the
assistant's line the result (「1件見つかりました。」, 「メモの作成内容を確認してください。」 → after the
confirmation 「メモを作成しました。」, after a cancel 「キャンセルしました。」). The form itself is not
recorded. The preview, the ticket and the form are ephemeral: a process death keeps the
transcript and drops them, writing nothing (journey test).

## 7. Editor

`TemplateEditorRoute` (route `template-editor?templateId=`), reached from the picker's
「テンプレートを作成」, from テンプレート in the settings (a tap on a row), needing no model: name,
description, the action, the kind, the fields (key / label / type / required / default / choices),
the body or the search condition (query, date, kinds) or the target (a fixed name, or ask at run),
保存 (validated; problems listed in the user's words), delete with a confirmation.

## 8. Import / export

The MemoRipple template file: `{"format":"memoripple_templates","formatVersion":1,"templates":[…]}`
(`TemplateFile`, extension `.memoripple-templates.json`, `application/json`), written and read
through SAF from the テンプレート screen (書き出し / 読み込み). Import refuses anything that is not
such a file (empty, not JSON, another format, a newer version) and any template that fails
validation, naming the first problem; at most 200 templates, 4 MB; an imported template with a
known id replaces the old one. Nothing in the file is ever executed, opened or fetched: a path, a
URL or a script in a body is text.

**Portable backup (format 18, unchanged):** `TemplateBackupDto(id, name, body)` still carries the
list. A CREATE template round-trips its body (placeholders included) and comes back with no
fields; SEARCH / APPEND templates and field definitions are **not** in the portable backup —
they travel by the template file. Whether the backup should carry full v2 definitions is an open
decision for the human (§9); it was not changed here.

## 9. Not built / open

- **Backup format** — not changed. Options for the human: keep as is (v2 detail by the template
  file only); an additive optional `definition` field on the format-18 DTO (the released 1.1.0
  reader ignores unknown keys, so older builds would still restore name + body); or format 19.
- **AI field-value proposal / AI-drafted templates** — deferred (a prompt change; prompt v1 is
  frozen). The model may name a template; the form takes the values.
- **「この操作をテンプレートとして保存」** from a successful AI operation — v1.1 candidate.
- **Built-in / starter templates** — not added: the templates a user has are theirs; six
  candidates (今日の振り返り, 会議メモ, アイデアメモ, プロジェクトログ, 今週の記録を探す, 昨日の日記を探す) are
  listed here for a later decision, shippable as a template file rather than as code.
- **Fixed target by id** — not offered: a name is stable across a restore and is re-validated
  every run; an id would go stale.
- Rename of a conversation, retention limits, RAG, vectors, summarisation, background agents,
  destructive intents — unchanged from Phase 8.

## 10. Tests

JVM: `TemplateV2Test` 9, `TemplateFileTest` 5, `TemplateRunnerTest` 10, `ChatUiTemplateV2PolicyTest`
6; the older policy tests follow the new shape (one saved key, no search field in the chat).
Device: `ChatUiTemplateV2InstrumentationTest` 10 (journeys A–F, the free-text send with no
model, the legacy template, the editor, process death); the Phase 3–8 classes updated to the
conversation-first chat; `ChatFoundationInstrumentationTest` (the removed search UI) retired —
the search backend stays covered by `DocumentSearchInstrumentationTest` and
`DocumentAccessInstrumentationTest`.

## 11. S20

2026-09-21 00:36–00:37 JST, S20, the `bd9ca7e` debug build installed **in place**
(data intact), Qwen3-4B through the debug developer file, thermal 0 before and after:
**`OK (1 test)`**. The chat opened with no search controls; ＋ listed the test's two templates; the
**SEARCH template** answered with the temporary memo as a result card with the runtime **UNLOADED**
(no model touched); the **APPEND template** opened its form, rendered 「- テンプレート実機テスト」 into
the append preview of 「MemoRipple開発」 and was cancelled with nothing written, runtime still
UNLOADED; one **AI free-text** 「MemoRipple開発を開いて」 loaded the model and opened the memo in
**42.0 s** (`windowLines=5 shown=1` — the template turns were in the window; a LOW trim during
LOADING ignored by the Phase 3 rule); the transcript held 7 lines; no other document touched; the
templates, the memo and the conversation removed afterwards. The `bd9ca7e` build stays on the S20
for the user's UI/UX review.


## 12. Gate

`gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` on `bd9ca7e` (2026-09-21 00:33–00:56 JST): unit **1262 / 0** (`TemplateV2Test` 9, `TemplateFileTest` 5, `TemplateRunnerTest` 10, `ChatUiTemplateV2PolicyTest` 6, `LlmBenchIsolationPolicyTest` 4, every Phase 1–8 policy test green on the new chat shape); lintDebug **0 errors**, 34 warnings, 1 hint; app-debug.apk **109,095,129 B** (+246 KB over main: the editor, the picker, the domain), androidTest 4,859,772 B; packaging `libmemoripple_llm.so` + `libc++_shared.so` beside the two AndroidX libraries, **0 `.gguf`**; the backup package 0 diff lines against `5378f07` (format 18), AppDatabase `version = 25`, prompt v1 and the grammar unchanged. Device, the whole suite in **one process** on emulator-5554 (fresh install, `pm clear`, `am instrument`): **459 run — 446 finished, 12 assumption skips** (the five known + the seven device-only smokes, `ChatUiTemplateV2S20SmokeTest` among them), **1 failure**, no OOM, no crash, Java heap 22–68 MB. The failure, `MainActivityNavigationTest.memoDiaryAndSearchEmptyStatesExplainWhatToDoNext` (the memo wall's search empty state — a screen and a test this round did not touch, 0 diff lines), is classified as a **test-host timing flake, not a product regression**: the assertion follows the typed query with no wait; the same test APK passes **3 / 3** against main's build and **3 / 3** against the branch build after a fresh install; the two isolated failures came right after the 459-test suite on the loaded emulator (2 / 6 in total). No assertion changed. In the run: `ChatUiTemplateV2InstrumentationTest` 10 / 10 (journeys A–F), `ChatV1ReleaseReadinessInstrumentationTest` 13 / 13 (with the template search and the setup-card-as-result), `ChatV1LayoutInstrumentationTest` 9 / 9, `ConversationHistoryInstrumentationTest` 8 / 8, `AiChatPreviewInstrumentationTest` 11 / 11, `AiConfirmedWriteInstrumentationTest` 13 / 13, `AiTargetResolutionInstrumentationTest` 5 / 5, `AiModelManagementInstrumentationTest` 8 / 8, `ChatHistory*` 7 / 7, `DocumentSearchInstrumentationTest` 6 / 6 + `DocumentAccessInstrumentationTest` 8 / 8 (the search backend, untouched), `AppDatabaseMigrationTest` 19 / 19 + the migration tests, `BackupRestoreInstrumentationTest` 15 / 15, the three leak tripwires green, `PortableExport*` 18 / 18. No model file left in the emulator app.

## 13. UI/UX review fixes (2026-09-21, the user's five remarks)

The user's second review of the chat came with two screenshots — a reference chat app
(「こんばんは」 / Gemma 3 1B) and the current MemoRipple chat — and five remarks. What each became:

1. **「チャットのUIを一枚目のスクショのように変更」** — the chat now has the reference's shape.
   Top bar: the menu icon (`chat_history`, the history) left; the conversation's title with the
   model as its subtitle in the centre (`chat_title` / `chat_subtitle`); the compose icon
   (`chat_new_conversation`) and the overflow right. Transcript: the user's words as a **dark
   rounded bubble** on the right (`surfaceContainerHigh`, `extraLarge` shape, 84 % width);
   MemoRipple's flat on the left with a small **meta row** under the line — a copy action
   (`chat_message_copy_<id>`, the line's text to the clipboard) and, under the answer of the
   current ask only, the model's timing (`chat_ai_timing`: 「39ms/トークン, 25.94 トークン/秒, 160ms
   TTFT」 as the reference reads it). A **scroll-to-bottom chevron** (`chat_scroll_to_bottom`)
   appears when the list is scrolled up. The input reads 「メッセージを入力」. The reference's
   voice controls are not replicated. The timing is `AiTiming(promptTokens, generatedTokens,
   ttftMillis, totalMillis)` — the generator's numbers, handed to the screen by
   `interact(…, onTiming)`; **numbers only**, held in `AiPanelState.timing` for one ask, never in
   the transcript, the saved state or a store.
2. **「新しいチャットを選んだ場合、新ステージ背景になり下バーは非表示。戻るボタンで戻る」** — 新しいチャット on
   the tab opens the chat as its **own stage** (`Routes.CHAT_STAGE`, `chat_stage`): the same
   screen with a distinct background — **black over a dark theme** (as the reference), the lowest
   surface over a light one, the system bars painted with it too (`chatStageBackground()`) — **no
   bottom navigation**, and a **back arrow** (`chat_stage_back`) at the top-left; the system Back
   does the same. The stage clears the remembered conversation and begins with none; nothing is
   created by opening it; its first message creates a conversation, which the tab then follows,
   so Back after a message shows that conversation on the tab. On the stage the compose icon
   starts another fresh conversation in place; the history is in the overflow (`chat_open_history`)
   and a conversation picked there is followed. Not chosen: the コメントステージ背景 setting as the
   stage's colour (a forced colour scheme and system-bar styling would follow it) — open for the
   human if the black stage is not what was meant.
3. **「新しいチャットのアイコンを1枚のスクショに変更」** — `res/drawable/ic_edit_square.xml`, the
   Material Symbols `edit_square` glyph (Apache 2.0); the Material Icons set has no such glyph.
4. **「キーボードとチャットボードが追従していない問題を修正」** — the activity is edge-to-edge, so
   `adjustResize` alone moves nothing. The chat's Scaffold consumes the navigation-bar insets (the
   host already keeps its content above them, or above its bottom bar) and the input bar wears
   `imePadding()`, so it rises with the keyboard by exactly the keyboard's height; the list scrolls
   to its newest line when the keyboard appears; on the tab the **bottom navigation gives way
   while the keyboard is up** (`WindowInsets.isImeVisible` in `MemoRippleApp`), as the reference has
   no bar under the keyboard. Other screens are untouched.
5. **「aiのモデル変更ボタン追加、「aiモデルなし」も追加。これを行うことでaiは停止」** — the subtitle is the
   **model button** (`chat_model_button`): a tap lists the installed models
   (`chat_model_option_<id>`, from `ModelManager.states` — id and display name only),
   **「AIモデルなし」** (`chat_model_none`) and 「モデルを管理」 (`chat_model_manage`, the Local AI モデル
   screen). Choosing 「AIモデルなし」 is `ModelManager.clearSelection()`: the selection is cleared and
   the runtime **unloaded** (after a running answer finishes — the orchestrator's lock; never
   mid-write), the subtitle reads 「AIモデルなし」, the hint appears under the input, the next free
   text gets the setup card, and templates keep working. Choosing a model is `ModelManager.select`
   — it **never loads**; the next free text loads it. The chat view model never loads a model and
   has no path to a file or an engine (`ChatUiReviewFixPolicyTest`).

Unchanged: the pipeline, the confirm boundary, prompt v1 and the grammar, Room 25, backup 18, the
preference set (`ai_selected_model_id`), the AI logging rule (the new lines carry counts and enums
only), no version bump. Tests: `ChatUiReviewFixPolicyTest` 4 (the reference shape, the keyboard, the
stage, the model switch through the manager and the timing as numbers), `ChatUiReviewFixInstrumentationTest`
3 (the stage and Back, 「AIモデルなし」 stopping the AI and the installed model bringing it back, the
timing and copy row); the older classes follow the new shape (the history journey through the
stage's overflow; the placeholder pin). The emulator's Gboard runs in floating mode with the
hardware keyboard, so the keyboard behaviour was checked on the S20 (§13.1).

### 13.1 S20 and the gate for the review fixes

**S20** (2026-09-21 02:19 JST, the `a367ad8` debug build installed **in place**, data
intact — the memo wall showed the user's folder and memos afterwards; thermal 0): the chat tab
with the reference shape (menu / 「チャット」 + 「AIモデルなし」 with the chevron / edit-square / overflow,
「メッセージを入力」); a tap into the input raised the Samsung keyboard and **the input bar sat
directly on it with the bottom navigation gone** (the emulator's floating Gboard could not show
this); the model menu listed 「AIモデルなし」 and 「モデルを管理」 (no model is installed on the S20's
product store, so no model row); 新しいチャット opened the black stage with the back arrow and no
bottom bar; Back returned to the tab. Nothing was sent, created or changed. The S26 was not
attached in this round.

**Gate** (`gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
:app:assembleDebugAndroidTest` on `a367ad8`, 2026-09-21 01:54–02:17 JST): unit **1266 / 0**
(`ChatUiReviewFixPolicyTest` 4 among them, every older policy test green); lintDebug **0 errors**, 34
warnings, 1 hint (unchanged); app-debug.apk **109,177,906 B** (+83 KB over `bd9ca7e`: the drawable and
the screen), androidTest 4,886,356 B; packaging `libmemoripple_llm.so` + `libc++_shared.so` beside
the two AndroidX libraries, **0 `.gguf`**; backup package 0 diff lines against `5378f07`, AppDatabase
`version = 25`, prompt v1 and the grammar unchanged. Device, the whole suite in **one process** on
emulator-5554 (fresh install, `pm clear`, `am instrument`): **462 run — 448 finished, 12 assumption
skips** (the five known + the seven device-only smokes), **2 failures**, no OOM, no crash, Java heap
23–61 MB. The two failures were the Phase 8 journeys `ConversationHistoryInstrumentationTest.
openingTheChatMakesNoConversationTheFirstMessageDoes` (it tapped the bottom navigation with the
keyboard up — the navigation now gives way) and `…aNewConversationHasNoContextAndTheOldOneReopensFromTheHistoryList`
(it tapped the history icon after 新しいチャット — now the stage, where the history is in the overflow):
**test expectations of the previous shape, not product regressions**; both updated to the new
behaviour (`6141e4a`), then **8 / 8** in isolation with the review class (11 / 11). No assertion was
loosened. In the run: `ChatUiReviewFixInstrumentationTest` 3 / 3, `ChatUiTemplateV2InstrumentationTest`
10 / 10, `ChatV1ReleaseReadinessInstrumentationTest` 14 / 14, `ChatV1LayoutInstrumentationTest` 9 / 9,
`AiChatPreviewInstrumentationTest` 12 / 12, `AiConfirmedWriteInstrumentationTest` 13 / 13,
`AiTargetResolutionInstrumentationTest` 5 / 5, `AiModelManagementInstrumentationTest` 8 / 8,
`ChatHistory*` 7 / 7, `DocumentSearch` 6 / 6 + `DocumentAccess` 8 / 8, `AppDatabaseMigrationTest` 19 / 19,
`BackupRestoreInstrumentationTest` 15 / 15, the leak tripwires green, `PortableExport*` 18 / 18; the
flake of the previous gate (`MainActivityNavigationTest`) passed. No model file left in the emulator app.

## 14. AI optional, Template first-class (2026-09-21, the return to the first concept)

**The brief (human):** the chat must be *usable with no model* through templates and *freer with
one* — AI = the aid that structures free text; Template = a deterministic action that works with
no AI; Application = validation / preview / execution. One chat, no modes; the removed search
UI stays removed. Portable backups must not lose v2 templates any more.

### 14.1 The two entrances

- **AI**: free text → LLM → intent / template candidate → Resolver → preview / result → Human
  Confirmation → execution. Unchanged (§5, §13).
- **No AI**: ＋ → template picker → template → the form (fields in the user's words) → preview /
  search result → Human Confirmation → execution. No model file, no runtime, no thermal gate, no
  network — `TemplateRunner` is source-pinned away from all of them (`TemplateFirstClassPolicyTest`),
  and the no-AI journeys run with `TestGuards.online = false` and no descriptor.
- Free text **with no model**: only after a send, the card 「自由な文章での操作にはLocal AIモデルが必要です」
  (`chat_ai_model_unavailable`) with 「AIモデルを設定」 and **「テンプレートを使う」** (`chat_ai_use_templates`,
  opens the picker). The chat is never replaced by a setup card; the subtitle keeps saying
  「AIモデルなし」; the empty state says 「メッセージを入力するか、＋からテンプレートを選べます。」.
- **Suggestion** (deterministic, never a run): when the sent sentence contains the name of a
  template (two characters or more; the longest wins), a line 「「会議メモ」テンプレートを使えます」
  (`chat_template_suggestion`) with 「使う」 appears above the result, with or without a model; the
  tap opens the template's form — the same path as the picker. The model never runs a template;
  its USE_TEMPLATE still goes into the runner as before (a form when values are needed).

### 14.2 Starter templates (six, built in)

`StarterTemplates` in `domain/memos`: read-only code, **never copied into the store by the app**;
the picker lists them after the user's own with a small 「スターター」 mark; the AI's template
lookup sees them by id and name; the settings' テンプレート screen lists them under
「スターターテンプレート」 and a tap opens the editor on a **copy** (a new id) the user may change and
keep. Ids `starter-*`; at most six (`MAX_STARTERS`); each valid by `TemplateValidation`.

| id | name | action | shape |
|---|---|---|---|
| `starter-daily-review` | 今日の振り返り | CREATE 日記 | 良かったこと / うまくいかなかったこと / 明日やること (複数行) |
| `starter-meeting-memo` | 会議メモ | CREATE メモ | 会議名 (必須) / 日付 (今日) / 参加者 / 議題 / 決定事項 |
| `starter-idea-memo` | アイデアメモ | CREATE メモ | タイトル (必須) / アイデア (必須) / 次の一歩 |
| `starter-project-log` | プロジェクトログ | APPEND, target asked at run | 日付 (今日) / 進捗 (必須) → 「- 日付: 進捗」 |
| `starter-this-week` | 今週の記録を探す | SEARCH | 今週, メモ・アウトライン・日記 |
| `starter-yesterday-journal` | 昨日の日記を探す | SEARCH | 昨日, 日記 |

Hiding a starter is a future option; none is forced on anyone — they are rows in a list.

### 14.3 最近使ったテンプレート

`RecentTemplate(templateId, lastUsedAt)` — an id and a time, never a body or a typed value —
kept as one preference (`recent_templates`, `RecentTemplateRepository`, DataStore; not in Room,
not in any backup). Recorded when a template **runs** (a form still waiting records nothing);
newest first, one entry per template, five at most (`RecentTemplates.push`); an id that no longer
exists is skipped. The picker shows the section only when there is something in it.
**Pinned / favourites: deferred** — with recents in place a second list would add a concept for
little gain in v1; reconsider after the review.

### 14.4 The picker and the form

＋ → a **full-height** sheet (`chat_template_picker`, `chat_template_list`): 最近使ったテンプレート
(`chat_template_section_recent`, rows `chat_template_recent_<id>`) → すべてのテンプレート
(`chat_template_section_all`, rows `chat_template_item_<id>`, the user's own then the starters;
each row: name, description, 作成する / 探す / 追記する) → 「＋ テンプレートを作成」 → 「テンプレートを管理」.
The form (`chat_template_form`): TEXT a line, MULTILINE a paragraph, **DATE** the resolved day
with 「今日」 / 「昨日」 chips and 「日付を選ぶ」 (a calendar dialog; the value stays `TODAY` /
`YESTERDAY` / an ISO date the run resolves by the app's clock), CHOICE chips, **BOOLEAN a switch**.
A required field is marked 「（必須）」; left empty, the form stays and says
「会議名を入力してください」 under the field and 「会議名、追記先を入力してください」 above the buttons
(`chat_template_form_missing`) — the required fields and an asked target are named **at once**.
No key, type name, token or schema word reaches the screen.

### 14.5 The editor, step by step

基本情報 (名前, 説明) → 操作 (作成する / 探す / 追記する; then メモ / アウトライン / 日記, or the search words,
いつの記録 (指定なし / 今日 / 昨日 / 今週 / 先週) and どの種類, or 実行するたびに選ぶ / 決まった記録 + the name)
→ 入力項目 (each field: 項目の名前, どんな入力 = 1行 / 複数行 / 日付 / 選択 / はい・いいえ, 必ず入力してもらう,
an initial value in the field's own terms, 上へ / 下へ / 削除; 「＋ 項目を追加」) → 内容 (the body with
**「＋ 項目を挿入」** — a menu of the fields by name that inserts `⟦会議名⟧` at the cursor; a SEARCH
template shows its condition in words instead) → 確認 (the summary, a **preview** with the
defaults or 「（名前）」 as sample values through the same renderer, 保存, and 削除 for an existing
template). 次へ / 戻る at the bottom; the first step insists on a name; everything else is judged
at 保存 by `TemplateValidation`, whose words now name a field by its **label** (「項目「会議名」の…」,
「2番目の項目の名前を入力してください」, 「項目「同じ」の名前が重複しています」) and never a key.
Keys are the app's: `field_N`, never shown, never typed (`TemplateBodyDisplay.nextKey`); the body
is shown as `⟦名前⟧` and stored as `{{key}}` (`TemplateBodyDisplay.toDisplay` / `fromDisplay`,
bijective for unique labels; a bracketed word that names no field stays as typed and the
validator names it). Import / export keep the file format (§8); the buttons read
「テンプレートを書き出す」 / 「テンプレートを読み込む」.

### 14.6 Backup: format 19

**Decision (human-allowed, reasoned here):** format **19**, not optional fields on 18.
A v2 template is a new data contract — the house rule versions the contract (16 folders, 17
order, 18 several journals + templates); and an 18 reader could not even *validate* a SEARCH
template (its body is empty, and 18 required a body), so a v2 file would fail there with a
misleading message rather than degrade gracefully. Refusing by version is the honest path.
`TemplateBackupDto` keeps `id` / `name` / `body` first and adds `description`, `action`
(`create` / `search` / `append`), `documentKind` (`memo` / `outline` / `journal`), `fields`
(`TemplateFieldBackupDto`: key / label / type `text` … `boolean` / required / default / choices),
`search` (`TemplateSearchBackupDto`: query / dateToken / kinds), `target`
(`TemplateTargetBackupDto`: `named` + name, or `ask`), `createdAt` / `updatedAt` — strings on the
wire, never the domain enums; `TemplateBackupMapping` maps both ways and yields no template for
an unknown word, which the validator reports as `INVALID_TEMPLATE`. The validator judges every
template by `TemplateValidation.problems` (a legacy name + body is a CREATE memo template and
must still have both). Compatibility: **1–18 still restore** (an 18 file's templates are the
legacy shape); a **19 file round-trips** action / fields / search / target whole (unit and
device tests); the released 1.1.0 line, which writes 17 and refuses 18, refuses 19 the same way.
Room stays 25; templates stay in the DataStore.

### 14.7 Safety, history, logging

A template is data: no code, script, URL, path or intent in `domain/memos` (policy-pinned); the
runner touches no runtime, network or thermal gate; a run still ends at the same single confirm
button and one ticket = one write; a cancel writes nothing; process death drops a preview. A
template-only conversation is an ordinary conversation — `USER: 会議メモ` / `ASSISTANT: 「会議メモ」を
作成します。内容を確認してください。` — never a second kind of chat, and field values are not copied
into the assistant line. Logs carry counts and enums only (the log scan covers `backup` too).

### 14.8 Not built / deferred

Pinned / favourite templates (deferred, see 14.3); AI field-value proposal and AI-drafted
templates (deferred — prompt v1 frozen); 「この操作をテンプレートとして保存」 (v1.1 candidate); hiding a
starter (future); a fixed target by id (not offered).

### 14.9 Tests and journeys

`TemplateFirstClassPolicyTest` 6, `TemplateFirstClassTest` 5, `BackupTemplateMappingTest` +2,
`BackupValidatorTest` +1; the six policy tests and the mapper test now pin backup 19.
`NoAiTemplateJourneysInstrumentationTest` — with no model and no network: **A** ＋ → 昨日の日記を探す
→ one result card, no load, a recent; **B** ＋ → 会議メモ → 「会議名を入力してください」 → the preview →
one confirmed memo, then a second run and the recents in order; **C** ＋ → プロジェクトログ → the target
and the progress asked at once → the append preview → cancel → the database byte-identical;
**D** a custom template step by step (two fields named, no key, 書名 inserted by name, the
preview 「（書名）」) → saved → run to one memo; **E** export → delete → import → the same v2
definition; **F** free text → the setup card → 「テンプレートを使う」 → the picker; 「会議メモを作りたい」
→ the suggestion → the form, nothing written; **G** with a model, free text SEARCH as before and a
template beside it with no second load. The older journeys follow the new shape.

### 14.10 S20 and the gate

**S20** (2026-09-21 17:36 JST, the `a993964` debug build installed **in place**, data
intact, thermal 0; the S26 not used): the chat opened on the user's own conversation with
「AIモデルなし」; ＋ showed 「すべてのテンプレート」 with the six starters (作成する / 追記する / 探す, 「スターター」);
会議メモ opened its form — 「会議名（必須）」, 日付 as 2026年9月21日 with 今日 / 昨日 / 日付を選ぶ, 参加者, 議題,
決定事項, キャンセル / 確認へ — and was cancelled; ＋ テンプレートを作成 opened the editor at 「1 / 5 基本情報」
with やめる / 次へ and was left without saving. Nothing was created, run or changed on the S20;
the review state on it is: the chat, the picker with the starters, the field form, the stepwise
editor, the templates screen with its starter section, the no-AI state.

**Gate** (`gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
:app:assembleDebugAndroidTest` on `a993964`, 2026-09-21 17:11–17:34 JST): unit **1277 / 0** in the
gate, **1280 / 0** after the three backup-19 tests received the `@Test` they had been written
without (`aa78ffc`); lintDebug **0 errors**, 34 warnings, 2 hints (the new hint an
`mutableIntStateOf` suggestion on the editor's step); app-debug.apk **109,374,514 B** (+197 KB over
`a367ad8`: the starters, the editor, the backup DTOs), androidTest 4,926,424 B; packaging
`libmemoripple_llm.so` + `libc++_shared.so` beside the two AndroidX libraries, **0 `.gguf`**;
AppDatabase `version = 25`; the backup package **190 diff lines** against `5378f07` — the format-19
change of §14.6, on purpose; prompt v1 and the grammar unchanged. Device, the whole suite in
**one process** on emulator-5554 (fresh install, `pm clear`, `am instrument`): **469 run — 456
finished, 12 assumption skips** (the five known + the seven device-only smokes), **1 failure**, no
OOM, no crash, Java heap 22–73 MB. The failure, `ChatHistoryBackupExclusionInstrumentationTest.
aBackupMadeWithChatHistoryCarriesNoneOfItAndRestoresTheDocuments`, asserted `"formatVersion":18`
in the written file — **an expectation of the previous format, not a regression** (the history is
still outside the backup; the memo restores); pinned to 19 (`5445b48`), then **23 / 23** in
isolation with `BackupRestoreInstrumentationTest` and the no-AI journeys. In the run:
`NoAiTemplateJourneysInstrumentationTest` 7 / 7, `ChatUiTemplateV2InstrumentationTest` 10 / 10,
`ChatV1ReleaseReadinessInstrumentationTest` 14 / 14, `ChatUiReviewFixInstrumentationTest` 3 / 3,
`ChatV1LayoutInstrumentationTest` 9 / 9, `ConversationHistoryInstrumentationTest` 8 / 8,
`AiChatPreviewInstrumentationTest` 12 / 12, `AiConfirmedWriteInstrumentationTest` 13 / 13,
`AiTargetResolutionInstrumentationTest` 5 / 5, `AiModelManagementInstrumentationTest` 8 / 8,
`ChatHistory*` 6 / 6 + the migration tests, `BackupRestoreInstrumentationTest` 15 / 15 (18 and older
files still restore), `AppDatabaseMigrationTest` 19 / 19, `DocumentSearch` 6 / 6 + `DocumentAccess`
8 / 8, the leak tripwires green, `PortableExport*` 18 / 18. No model file left in the emulator app.

## 15. The history as a side drawer (2026-09-21 evening)

**The remark (human, with a ChatGPT screenshot):** 「チャットの履歴の左上ボタンをchatGPTやclaudeのようなUIに
変更してください」 — the menu icon should open the history as a **side drawer over the chat**, not
leave for a separate screen.

- The menu icon (`chat_history`) opens a modal drawer (`chat_drawer`): 「チャット」 with a compose
  icon (`chat_drawer_compose`), 「最近」, the conversations newest first as rows
  (`chat_drawer_row_<id>`: the title and 「M/d HH:mm」; the current one on `secondaryContainer` with
  `selected` semantics), then a filled **「新しいチャット」** (`chat_drawer_new`, the stage) and
  **「履歴を管理」** (`chat_drawer_manage`, the full チャット履歴 screen, unchanged — delete buttons and
  「すべてのチャット履歴を削除」 live there). Empty: 「チャット履歴はありません」 (`chat_drawer_empty`).
- A **tap** on a row closes the drawer and opens that conversation in place — through the
  remembered-conversation preference the view model already follows (`openConversation`). A
  **long press** asks 「「title」を削除しますか？」 (`chat_drawer_delete_dialog`) and deletes only that
  conversation's rows (`deleteConversation`); the documents it named stay.
- Gestures open the drawer on the tab; on the stage the left icon stays Back (the user's earlier
  remark) and the overflow keeps 「チャット履歴」. Back closes an open drawer (the drawer's own
  handler). The drawer sits inside the chat's area of the host — under the status bar band and
  above the bottom navigation; a full-window drawer would have to be hosted by the app shell
  (open for the human if wanted).
- Nothing new is stored: the drawer reads the same conversation list as the history screen.
  `ChatDrawerPolicyTest` 1, `ChatDrawerInstrumentationTest` 2 (a row opens in place; 新しいチャット →
  the stage; a long press → cancel keeps, confirm deletes; 履歴を管理 → the screen); the Phase 8 delete
  journey reaches the screen through 履歴を管理.
- **Gate** (`gradlew clean …` on `58ed3ab`, 2026-09-21 18:44–19:07 JST): unit **1281 / 0**; lint **0 errors**, 34
  warnings, 2 hints; app-debug.apk **109,423,666 B** (+49 KB); 0 `.gguf`; Room 25 / backup 19. Device, one
  process on emulator-5554: **471 run — 458 finished, 12 skips, 1 failure** —
  `MainActivityNavigationTest.memoOrganizationControlsComposeAndSurviveConfiguration` (the memo wall's
  display options, 0 diff lines this round), **3 / 3 in isolation** on the same build: a test-host timing
  flake, no assertion changed. `ChatDrawerInstrumentationTest` 2 / 2, `ConversationHistory` 8 / 8, the
  chat and template classes green. **S20** (19:08 JST, `58ed3ab` in place, data intact): the menu icon
  opened the drawer over the chat with the user's 「日記」 conversation marked and 「新しいチャット」 /
  「履歴を管理」 beneath. The S26 was not attached for this build (it holds `a993964`).

## 16. Conversation Template Flow — Template = conversation script (2026-09-21)

**The remark (human, from the UI/UX Personal Review):** the field form of §14 was functionally
right but turned the chat into 「打ち込み作業」 — a large form to fill in at once. A template is to
run instead as **a script for the conversation**: the system asks one question, the user answers
one thing, the next question follows, and when everything is there the preview and the Human
Confirmation come — with no model at all.

### 16.1 Why the form was rejected

A form is a screen inside a chat: it breaks the transcript, asks for everything at once, needs a
key for every box and reads like data entry. A conversation asks for one thing, in the user's
words, and remembers the answer as a line anyone can read back. The same template, the same
validation, the same preview and confirmation — a different way of collecting the values.

### 16.2 The principle (Obsidian-inspired, applied to a conversation)

- **Reusable commands / templates** — a template is a reusable recipe, not a body.
- **Conversation as workflow** — the recipe runs as questions and answers in the transcript.
- **Context-aware note operations** — the recipe knows what it creates, searches or appends to.
- **Local-first** — everything runs on the device; a template needs no network.
- **AI optional** — the AI structures free text; the template engine is deterministic and the same
  with or without a model.
- **Deterministic templates remain useful without AI** — the six starters work on a phone with no
  model file.

Not Obsidian's UI: MemoRipple's chat, with the conversation in the middle.

### 16.3 One question at a time

`TemplateScript` (`domain/memos`) is the engine: given the template, the answers so far and the
target, `next()` says what comes now — `AskTarget` for an APPEND that asks its target, `Ask(field,
index, total)` for the first field without an answer (a skipped optional field is an answer of
""), `Ready` when everything is there. Fields are asked **in their order** — the order the editor
lets the user set. `questionOf(field)` is the field's own `question`, or 「{名前}を入力してください」
for a template written before questions existed; `spoken()` introduces the first question with
「{テンプレート名}を始めます。」 and leads with まず / 次に / 最後に (nothing for a lone question), adding
「（なければスキップできます）」 to an optional one. A required field is always asked; an optional one
may be skipped with the chip — the default then applies (a DATE with 「実行した日」 needs no
answer). No AI fills an answer from the context in v1 (deferred); the user answers.

### 16.4 The session (ephemeral)

`TemplateSessionState(template, step, answers, target, targetCandidates, editing)` lives in the
view model only. It is never in the saved state, a preference or Room. **A process death drops
it**: the transcript stays (the questions and answers are plain lines), nothing resumes by
itself, and nothing is written — a send after that is ordinary free text. Resuming mid-flow is a
future candidate, not v1.

### 16.5 How the chat renders it

The transcript is the whole UI: the user's answers as right bubbles, the system's questions as
flat assistant lines — never a card around a question, never a second screen. Under an open
question a row of chips (`chat_answer_options`): DATE → 今日 / 昨日 / 日付を選ぶ (the calendar only
on request; the value stays `TODAY` / `YESTERDAY` / an ISO date), BOOLEAN → はい / いいえ, CHOICE →
the choices, スキップ on an optional question, やめる always. A TEXT / MULTILINE question has no
chips — **the message input is the answer** (its placeholder reads 「回答を入力」); a typed date,
choice or yes / no is accepted when it reads as one and otherwise asked again in words. The
transcript keeps the user's words (「昨日」, 「はい」, 「スキップ」), never a token or a key. The
conversation starts a little under the top bar (16 dp) so the first line does not stick to it.
Timing numbers stay on AI answers; a template question shows none. Copy stays on every
assistant line (small; harmless on a question — audited, kept).

### 16.6 SEARCH, CREATE, APPEND

- **SEARCH with nothing to ask** runs at once: `USER: 昨日の日記を探す` → `ASSISTANT: 昨日の日記を探すを探します。`
  → the result cards → `N件見つかりました。`. A SEARCH with fields asks them first.
- **CREATE**: the questions, then `ASSISTANT: ありがとうございます。この内容で日記を作成します。` and the
  preview card with 「修正」 / 「キャンセル」 / 「作成」.
- **APPEND that asks its target**: the target question first (`targetQuestion`, e.g.
  「どのプロジェクトですか？」, or 「どのメモに追記しますか？」) — the name typed is looked up at once through
  the orchestrator's own SEARCH (no model): **one hit** is taken (「「MemoRipple開発」に追記します。」),
  **several** are shown as candidate cards to tap (`chat_target_candidate_*`), **none** asks again;
  the run still resolves the name itself before the preview (target revalidation, as always).
  Then its questions, then the append preview.

### 16.7 Edit before confirm

「修正」 on the preview opens the answers as rows (`chat_edit_field_<key>`: 名前 — the answer, 「（スキップ）」
or 「（未回答）」); a tap asks that one question again and the preview returns with the new value. A
summary form never comes back.

### 16.8 The starters' questions

| starter | questions, in order |
|---|---|
| 今日の振り返り (CREATE 日記) | 今日の良かったことは？ (必須) / うまくいかなかったことは？ / 明日やることは？ |
| 会議メモ (CREATE メモ) | 会議名は？ (必須) / 参加者は？ / 何について話しましたか？ / 何が決まりましたか？ / 次にやることは？ |
| アイデアメモ (CREATE メモ) | どんなアイデアですか？ (必須) / 何がきっかけでしたか？ / どこが面白いと思いますか？ / 次に何を試しますか？ |
| プロジェクトログ (APPEND, target asked) | どのプロジェクトですか？ (the target) / 今日やったことは？ (必須) / 困っていることはありますか？ / 次にやることは？ |
| 今週の記録を探す, 昨日の日記を探す (SEARCH) | none — at once |

Still six; the cap is eight (a 壁打ち category — 今日やること整理, 企画の壁打ち — is a candidate, not built).

### 16.9 The editor

Each field has 項目の名前 and 質問 (the placeholder shows what the label would ask); an APPEND
that asks its target has 追記先を聞く質問（任意）. The 確認 step previews **チャットで聞く順番** (質問1 …,
the target question first, 「（スキップ可）」 on an optional one) above できあがり. Keys, type
names, action words and JSON stay hidden. The file format and backup 19 carry `question` and
`targetQuestion` (defaults; older files decode).

### 16.10 Tests

`ConversationTemplateFlowPolicyTest` 4, `TemplateScriptTest` 5; `ConversationTemplateFlowInstrumentationTest`
— with no model and no network: **A** 今日の振り返り one question at a time → the preview → cancel →
nothing changed, the transcript holding the questions and answers; **B** 会議メモ with a skip → one
confirmed memo; **C** 昨日の日記を探す at once; **D** プロジェクトログ — the target question, two candidates,
one tapped, the questions, the append preview, cancel; **E** with a model the AI free text as before;
**F** a process death mid-flow — the transcript stays, no chips, a send is free text, nothing
written; **G** DATE / BOOLEAN / CHOICE as chips, 「修正」 asking one answer again. The older template
journeys answer one question at a time.

### 16.11 Gate and S20

**Gate** (`gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
:app:assembleDebugAndroidTest` on `c164237`, 2026-09-21 19:53–20:17 JST): unit **1290 / 0**; lintDebug
**0 errors**, 34 warnings, 2 hints; app-debug.apk **109,489,202 B** (+66 KB); packaging unchanged, **0
`.gguf`**; Room 25, backup 19. Device, the whole suite in **one process** on emulator-5554: **478 run —
466 finished, 12 assumption skips, 0 failures**, no OOM, no crash. `ConversationTemplateFlowInstrumentationTest`
7 / 7, `NoAiTemplateJourneys` 7 / 7, `ChatUiTemplateV2` 10 / 10, `ChatV1ReleaseReadiness` 14 / 14, `ChatDrawer`
2 / 2, `ConversationHistory` 8 / 8, the rest as before; the leak tripwires green. No model file left
in the emulator app.

**S20** (2026-09-21 20:17 JST, the `c164237` debug build installed **in place**, data
intact, thermal 0; the S26 not used): the chat opened on the user's conversation with the new
16 dp gap under the top bar. The one-question flow was exercised on the emulator (今日の振り返り: the
opening line, 「まず、今日の良かったことは？」, the answer bubble, 「次に、うまくいかなかったことは？（なければスキップ
できます）」 with スキップ / やめる, the placeholder 「回答を入力」) rather than on the S20, so no
conversation was added to the user's data; the S20 is ready for the review of the top spacing,
今日の振り返り and 会議メモ one question at a time, the SEARCH starters, the no-AI path and the AI path.

### 15.1 The drawer refined (2026-09-21 21:29)

The user's second look, with ChatGPT's drawer beside it: the blank at the top, the compose icon,
a search, pins, the width.

- **Tight at the top**: the sheet takes no window insets of its own (`WindowInsets(0)` — the host
  already keeps the chat below the status bar), so 「チャット」 sits where the red box was.
- **No compose icon** at the top right — 「新しいチャット」 is the button at the bottom. In its place a
  **search** icon (`chat_drawer_search`) that opens a field (`chat_drawer_search_field`, 「履歴を検索」)
  filtering the rows by title; 「見つかりません」 when nothing matches.
- **Long press → menu** (`chat_drawer_menu`): 「ピン留め」 / 「ピン留めを外す」 (`chat_drawer_pin`) and 「削除」
  (`chat_drawer_delete`, then the confirmation as before). Pinned conversations sit under
  **「ピン留め」** (`chat_drawer_section_pinned`, rows `chat_drawer_pinned_<id>`) above 「最近」. Pins are
  conversation ids in one preference (`chat_pinned_conversation_ids`, `PinnedConversationStore` /
  `DataStorePinnedConversationStore`); never content, never in a backup, no Room change; an id that
  no longer exists is skipped.
- **Narrower**: the sheet is 80 % of the width, so the chat shows beside it as in the reference.
- `ChatDrawerPolicyTest` +1, `ChatDrawerInstrumentationTest` +1 (pin, unpin, search); the delete
  journey goes through the menu.
- **Gate** (`gradlew clean …` on `6968bab`, 2026-09-21 21:44–22:08 JST): unit **1291 / 0**; lint **0 errors**, 34
  warnings, 2 hints; app-debug.apk **109,521,970 B** (+33 KB); 0 `.gguf`; Room 25 / backup 19. Device, one process
  on emulator-5554: **479 run — 467 finished, 12 skips, 0 failures**. **S20** (22:08 JST, `6968bab` in place,
  data intact). The S26 was not attached for this build (it holds `c164237`).

### 16.12 The S20 review's three small fixes (2026-09-21 night)

1. **A user line is a bubble as wide as its words** — `wrapContentWidth(Alignment.End)` inside a
   box capped at 84 % of the row, on the right; the template's opening 「今日の振り返り」 too. The
   assistant stays flat on the left. (`ChatV1LayoutInstrumentationTest`: a short line at 360 dp is
   narrower than 180 dp and ends at the right edge.)
2. **やめる on the first question only** — the chip appears at the first question or the target
   question (`index == 0`, not while an answer is being changed); from the second question on it
   is gone; スキップ stays on an optional question; Back still cancels the flow; the preview / 修正 /
   キャンセル / 作成 flow is unchanged.
3. **The caret shows in the dark** — the chat input's `cursorBrush` is the theme's `onSurface`
   (white-ish in the dark, near-black in the light; a `BasicTextField`'s default is black) and its
   selection colours are the primary (handle) and the primary at 40 % (background). What the app
   controls: the caret, the selection handles and the selection background of its own fields.
   What it does not: anything drawn by the Samsung keyboard itself (its keys, its candidate bar,
   its own 「|」 key glyph) — those follow the keyboard's theme. Checked on the S20 with the Samsung
   keyboard: the caret is white.

Unit **1294 / 0**, lint **0 errors**; the chat classes on the emulator (layout 10 / 10, the flow 7 / 7, the
no-AI journeys 7 / 7, Template v2 10 / 10, readiness 14 / 14, the drawer 3 / 3, review fixes 3 / 3) green.
**S20** (22:22 JST, `0a1dc46` in place, data intact): the input typed into shows a white caret; the
first and second question, the right bubbles and the preview are the emulator run's shape.

### 15.2 The long-press menu on the right (2026-09-21 22:58)

The menu a long press opens now sits on the **right of the row**, as ChatGPT's — anchored at the
row's end, rounded, a pin icon on 「ピン留め」 / 「ピン留めを外す」 and a red bin on 「削除」. Nothing else
changed. Unit 1295 / 0, lint 0 errors, `ChatDrawerInstrumentationTest` 3 / 3; `dac5893` on the S20 in
place (23:14 JST); the S26 was not attached (it holds `0a1dc46`).

### 16.13 The editors' shortcut-bar panels (2026-09-22 00:00 JST; the memo editor and the outliner, not the chat)

The user's remark, with the 再生設定 sheet as the reference: make the テンプレート / メモへのリンク /
コメントリンク panels of the shortcut bar dismissible with a swipe down like it; fix the panels stacking
(テンプレート then メモへのリンク left two layers, and × left the one behind); and let a template be made
from the bar alone, without the settings list.

1. **One panel at a time** — `EditorShortcutPanel { TEMPLATE, MEMO_LINK, COMMENT_LINK }` and one
   nullable state in each editor in place of three booleans; `toggle(current, tapped)`: another tool
   swaps the panel, the same tool closes it, × / scrim / Back / a pick leave nothing behind. The bar
   stays tappable under an open panel on purpose (it sits in the Scaffold's bottom bar), which is
   why a swap, not a stack, is the rule.
2. **The hand's contract of 再生設定, without its window** — `KeyboardKeepingPanel` still lives in the
   editor's window (a modal sheet is its own window and pulls the keyboard down; the caret must stay
   where the insert goes). It now slides up (220 ms), wears `BottomSheetDefaults.DragHandle()` and the
   title row with ×, and the head of the panel (handle + title) is `draggable` vertically: past a
   quarter of the panel's height or a flick over 1,500 px/s it slides away and dismisses; shorter, it
   springs back. The lists inside keep their own scrolling, so only the head drags. Tags:
   `<panel>_handle`, `<panel>_close`.
3. **「テンプレートを作成」 inside the テンプレート panel** — the panel turns into a name and a body
   (「このメモの本文を入れる」 copies the memo in); 保存 goes through `MemoEditorViewModel.createTemplate`
   = the same `MemoTemplatePolicy` and the same store as 「テンプレートとして保存」, then the list returns
   with the new template on top; an empty name or body is refused in place (「名前と内容を入力してください。」).
   A template made here is a plain paste template (a legacy name + body, no fields), which is exactly
   what the editors' panel lists and inserts; the chat's stepwise editor and the settings list are
   untouched, and a template with fields still belongs to the chat.

Tests: `EditorShortcutPanelPolicyTest` (toggle, one state in both editors, the panel's handle / drag /
no `ModalBottomSheet` / animation, the create form's parts and the policy), `EditorShortcutPanelInstrumentationTest`
(swap then × leaves none, the same tool closes; a swipe down on the handle; create → list → insert →
one template kept without fields; an empty one refused, the memo's body copied in, 戻る). Unit **1300 / 0**,
lint **0 errors**; the new class 4 / 4 (×3 in isolation), with `OutlinerToolbarInstrumentationTest` and
`MainActivityNavigationTest` 81 / 81 on the emulator. `f20847b` on the S20 in place (23:58 JST); the S26 not
attached.

### 16.14 Three editor remarks: the comment list's carry, the title's tap, the line's double tap (2026-09-22 00:06 JST)

1. **コメント一覧 carries a row like 設定's ショートカットバー** — the sheet's arranging mode used a
   step-swap drag (every 48 dp of travel swapped the row with its neighbour, nothing moved on
   screen until the swap). It now uses the same `CardDragController` as `ToolbarOrderScreen`: the
   handle lifts its row as a floating card (`carriedCard`), the neighbours slide as the card's
   middle crosses them (`animateItem` on every row but the carried one, `onCrossed` → `moveComment`),
   and release writes the order once and lets the card settle. The outliner's comments sheet is
   the same composable. The accessibility actions 上へ移動 / 下へ移動 and 作成順に戻す are unchanged.
2. **A single tap on the title in 閲覧モード opens writing on the title** — a transparent tap layer
   over the read-only title (`memo_title_reading_tap`, only while `readingMode && !speechActive &&
   !playbackActive`) leaves 閲覧モード and, on the frame after the editor is composed, asks the
   title's `FocusRequester` for focus and the keyboard to show.
3. **A double tap on a written line opens writing at that line** — `MemoReadingView.onDoubleTapLine`
   (per row; the row's own links, checkboxes and folds still win the tap): the page leaves 閲覧モード,
   the caret is placed at the start of that source line (`BodyReading.lineStartOffset`) and the body
   is focused with the keyboard. **Unchanged, as asked:** the page's own double tap on an empty place
   (`onDoubleTapEdit`) still opens writing with no field taken; under a voice (読み上げ) or a playback
   both doors are null and the page keeps itself; ⋮ → 編集モード and the toolbar's 編集 are as before.

Tests: `EditorReadingTapPolicyTest` (the sheet's helpers = the toolbar screen's, no threshold drag; the
tap layer and its guard; the requester parameter; the two `null` guards), `EditorReadingTapInstrumentationTest`
(title tap → editor with the title focused; line double tap → body focused, title not; empty-place double
tap → editor, nothing focused). Unit **1302 / 0**, lint **0 errors**; the new class 3 / 3 (×4);
`MainActivityNavigationTest` (incl. the comment-reorder journey) + `CommentLinkInstrumentationTest` +
`OutlinerToolbarInstrumentationTest` + `EditorShortcutPanelInstrumentationTest` **91 / 91**. `6110476` on the S20
in place (00:29 JST); the S26 was disconnected at install time (it holds `f20847b`).

#### 16.14.1 The caret lands where the writing continues (2026-09-22 00:35 JST)

The S20 showed the double-tapped line's caret at the line's *head* (the drop handle at the left
edge). It now sits at the **end of that line** (`BodyReading.lineEndOffset` in place of the head),
and the title's tap puts the caret at the **end of the title** (`EditorTextField(String)` takes a
`caretToEndKey`; each tap bumps it). The journeys assert both carets. Unit **1304 / 0**, lint **0 errors**,
`EditorReadingTapInstrumentationTest` 3 / 3 (×3). `b84eaf4` on the S20 and the S26 in place (00:41 JST).

## 17. Think templates (2026-09-22) — see `docs/THINK_TEMPLATES.md`

The next round after the merge of this document's rounds (`9cab86c`): a template may *think* as well
as record. `TemplateFlow.THINK` is a flow beside the action (never a fourth action); the questions are
this document's §16 script unchanged; the end is a deterministic result in the conversation with
「メモとして保存」 / 「終了」 instead of a preview, and a save is §14's CREATE path — the same preview, the
same single `confirm()`. Four Think starters join the six; the ＋ picker groups 記録 / 整理・壁打ち / 探す;
the editor offers 整理する; Backup 19 carries the flow as a defaulted word. Everything else in this
document stands.

## 18. Review Batch 2 (2026-09-22) — pins, quick create, the picker's order; Think 修正; conversation → memo

Before the user's combined UI/UX review of Chat / Template / Think (the review was postponed to
gather these). Nothing here is "finished" until the user has seen it.

- **The ＋ picker's order:** ピン留め → 最近使ったテンプレート → 記録 → 整理・壁打ち → 探す → 「＋ テンプレートを作成」
  / 「テンプレートを管理」. An empty section is not shown. The rows stay the same small rows.
- **Template pins:** a **long press on a row** opens its menu on the right — 「ピン留め」 / 「ピン留めを外す」 (the
  history drawer's convention; no standing pin icon on every row). The pins are **ids only** in the
  preference `chat_pinned_template_ids` (a newline-joined list, so the pin order is kept), never a copy
  of a template; a starter, a custom or an imported template pins alike; an id that names no template
  (a deleted custom one) is ignored where it is read; no duplicate; no cap. A pinned template is shown
  **once** — under ピン留め — and left out of 最近 and of its own section (the recent list keeps its data).
  Pins survive a process restart. **Not in the portable backup:** a preference of this device, like the
  recent list and the drawer's pins; the templates themselves stay format 19 as before.
- **「＋ テンプレートを作成」** was already in the picker (§14); the audit confirmed the flow the brief asked for:
  ＋ → the entry at the bottom → the **one** `TemplateEditorScreen` (route `template-editor`) → 保存 → back
  to the chat → the new template in its section, runnable. Back mid-edit follows the editor's existing
  unsaved handling; no second editor, no draft system. `ReviewBatch2InstrumentationTest` makes a Think
  template this way (整理する) and runs it.
- **Think 修正:** see `docs/THINK_TEMPLATES.md` §6.1.
- **Conversation → memo:** see `docs/AI_CONVERSATION_HISTORY.md` §16.

## 19. Conversation → template draft (2026-09-22) — 「この会話からテンプレートを作成」

A conversation that went well — a Think or a record template's questions, answered — can be
**promoted to a reusable template**, but never by a model and never by itself:

```
Conversation → draft extraction (a rule) → the template editor → the user's edits → 保存
```

- **Entry:** the chat's overflow, beside 「この会話をメモとして保存」; enabled with a conversation that has
  lines. Nothing beside the input. v1 takes the whole current conversation (partial selection deferred).
- **The rule** (`ConversationTemplateDraft`, `domain/ai/conversation`): a MemoRipple line of kind **TEXT**
  (what it *said*; never a RESULT card, a WRITE_EVENT preview / confirmation, or a FAILURE) whose last
  sentence asks (「？」 / 「?」 / 「…ください」, the script's まず / 次に / 最後に and
  「（なければスキップできます）」 removed), followed by the user's TEXT line, is **one field** — in order,
  with the question itself and a label cut by a small rule (「今日の良かったことは？」→「今日の良かったこと」,
  「〜はありますか？」→「〜」, 「〜を…教えてください」→ the part before を; the question itself when nothing
  sensible is left). A question asked twice (修正) is one field; two questions that cut to one label
  are kept apart. Kinds first, words second: the transcript's own classification decides what was a
  card, not a text heuristic.
- **Never in the draft:** the user's answers (no default, no body text), ids, `result_N`, timing,
  previews, a Think result, any operation state — by construction of the transcript and of the rule.
- **The shape:** flow **THINK** on a CREATE MEMO template (questions and a result; a memo only on
  request — the safest promotion), MULTILINE optional fields `field_1…`, the body
  `<name> - ⟦今日の日付⟧` then `## <label>` / `⟦label⟧` per field; the name is the conversation's title or
  **「会話テンプレート」** when it is empty or the generic 「新しいチャット」. Words like 「DELETE」 in the
  conversation change nothing: the only actions that exist are CREATE / SEARCH / APPEND and the draft
  is always CREATE + THINK.
- **The editor:** the draft rides `TemplateDraftHandoff` (memory only; a process death in between opens
  the editor empty) to the **one** `TemplateEditorScreen`, opened as a new template (名前 / 操作 = 整理する /
  入力項目 / 内容 / 確認 all editable). **The chat writes no template**: only the editor's 保存 keeps it,
  through the same store, validation, file export and Backup 19 as any template. Back leaves without
  saving, as the editor always did.
- **Nothing to draft:** no question → answer pair → one small dismissable card 「テンプレートにできる質問が
  見つかりませんでした」, no editor, no empty template.
- AI installed or not, the same rule; AI-shaped labels, merged or de-duplicated questions, a generated
  name or body, and 「この会話をテンプレートにして」 as free text are deferred.

## 20. The picker as an entrance; template folders (2026-09-22)

With ten starters and the user's own, the ＋ picker had become one long list. It is now **an
entrance to find a template**, and the user's templates can be filed in one flat level of folders.

### 20.1 The root

```
テンプレート
  ピン留め              ≤ 3 rows   (+「すべて表示」 when there are more)
  最近使ったテンプレート  ≤ 3 rows   (pins left out)
フォルダ
  📁 記録              4  ›
  📁 整理・壁打ち       4  ›
  📁 探す              2  ›
  📁 自分のテンプレート  n  ›
＋ テンプレートを作成
テンプレートを管理
```

- The root **renders no folder's templates** (`TemplatePicker.root`; `TemplatePicker.MAX_ROOT_ROWS = 3`).
  A pinned or recent row runs directly. An empty section is not shown.
- A folder row is compact — icon, name, count, chevron — never a card.
- **Search** at the root was audited and not added: with a dozen-odd templates the folders are enough;
  it stays a candidate for when the count grows.

### 20.2 Folders

- **Built-in** (the starters, by what they are — `ThinkTemplates.sectionOf`, immutable): 記録 = 今日の振り返り ·
  会議メモ · アイデアメモ · プロジェクトログ; 整理・壁打ち = the four Think starters; 探す = 今週の記録を探す ·
  昨日の日記を探す. A starter carries no `folderId`; a copy of a starter is a custom template and files freely.
- **自分のテンプレート**: the user's folders in their order, each with its count, then **未分類**. A custom
  template is never in a built-in folder.
- **Drill-down inside the sheet** (`PickerPage`: Root / PinnedAll / BuiltIn / Mine / MineFolder): a page
  with a back arrow and the folder's name; no route, no full screen. **Back** walks one page up, and closes
  the sheet from the root (the sheet is its own window, so its `BackHandler` registers on the dialog's
  dispatchers).

### 20.3 The model — template organisation, not a document folder

- `MemoTemplate.folderId: String? = null` — one flat level; null = 未分類; an id that names no folder
  (deleted elsewhere, restored from another device) reads as 未分類. Nested folders are not a thing.
- `TemplateFolder(id, name, order)` in **its own store** (`TemplateFolderRepository`, the preference
  `memo_template_folders` beside `memo_templates`). No colour, no icon. It is **not** the memo wall's
  folder (`FolderEntity`, a document's location in Room) and shares no type, store or screen with it —
  the same word, a different thing (`docs/ARCHITECTURE.md`).
- **テンプレートを管理 → フォルダを管理** (`TemplateFoldersRoute`): create, rename, move up / down, delete.
  Deleting a folder **never deletes a template** — `TemplateRepository.clearFolder` unassigns them to 未分類
  first, then the folder goes; the dialog says so with the count.
- **The editor's 基本情報** gains 「フォルダ」: a row showing 未分類 or the folder, opening the list of the
  user's folders. A starter's copy, an imported template and a conversation draft all start 未分類.
- **Pins and recents** are ids and survive a move or a rename; a deleted template's pin is an orphan and
  is ignored, as before.
- **The template file** (`memoripple_templates` 1) drops `folderId` on export — a folder id is this
  device's — and an import is 未分類. **Backup 19 — not bumped:** `TemplateBackupDto.folderId` and
  `payload.templateFolders` are defaulted fields; a reader before them ignores both and sees unclassified
  templates; a 19 file from this build restores the folders and the assignments whole
  (`BackupMapperTest`). Folders are template organisation and belong in the backup, unlike the pins and
  the recent list, which stay device preferences.
- Room 25 unchanged.

## 21. The chat's folder and the one-pill input (2026-09-22)

The user's reference: ChatGPT's input (＋ and 送る inside one rounded field) and Codex's
「プロジェクトを選択」 above it. Decided "全て推奨" on the four questions asked.

### 21.1 「フォルダを選択」

- A small chip **above the input** (`chat_folder_chip`): 「フォルダを選択」 when nothing is chosen, 「📁 仕事」 when a
  folder is — **no × beside the name** (2026-09-22 21:00: a folder is changed by choosing another and cleared by
  the menu's first row, so nothing sits by the name to be hit by accident). Tapping it lists **the wall's
  folders** — the same tree the navigator shows (`FolderChoices.of` over `FolderTree.flatten`, the one tree
  walk), indented by depth — under 「フォルダなし（通常）」 (`chat_folder_choice_none`) and
  **「＋ 新しいフォルダ」** (`chat_folder_new`), which makes a folder at the root through the wall's own repository
  (`DocumentFolderChoices.create`, `FolderName`'s rule) and chooses it at once — the chat waits for the folders
  flow to carry it, so the vanished-folder rule below cannot mistake a just-made folder for a deleted one.
- **Scope (b):** one chat-wide preference, `chat_create_folder_id` (`ChatDestinationStore`), remembered
  across sessions like the last conversation; × clears it. **Not a Room column** (Room 25 unchanged) and
  not per conversation.
- **What it applies to:** every document the chat **creates** — the AI's CREATE (memo / outline), a CREATE
  template, a Think 「メモとして保存」, 「この会話をメモとして保存」. **A journal has no folder** and ignores it;
  APPEND / SEARCH / OPEN touch existing records and ignore it. With nothing chosen everything is as before.
- **The preview names it:** 「保存先: 仕事」 (`chat_ai_preview_folder`) on the 新規作成 / テンプレートから作成
  previews before the one confirmation; no line without a choice.
- **A deleted folder:** the chip falls back to 「フォルダを選択」 and the preference is cleared
  (`FolderChoices.destination` finds no such id); the boundary re-checks too
  (`RepositoryDocumentAccess.existingFolder`: a dangling id → the root).
- **Never the model's:** `IntentProposal` has no folder word; the destination enters a create only through
  `Resolver.resolve(…, destination)`, `TemplateRunner.run(…, destination)` and `previewMemo(…, destination)`
  as `DocumentCreate.Memo(folderId)` / `Outline(folderId)` (`CreateDestination(folderId, name)`;
  `ChatDestinationPolicyTest`, `ChatDestinationTest`).

### 21.2 The one pill

The bar is **one rounded field**: ＋ (templates) on its left inside, the text, × when there is text, 送る on
its right inside (enabled with text). No microphone. The no-model hint stays above it, the chip above the
pill. The caret and selection colours are unchanged (§16.12).

## 22. The closable hint and the conversation's size (UI/UX review, 2026-09-23)

Two remarks from the user's own review of the chat on the S20.

### 22.1 「自由文の依頼にはLocal AIモデルが必要です」 closes for good

The one-line hint above the input — the line shown while no model can answer free text — now ends with a
**×** (`chat_ai_hint_dismiss`, 「この案内を閉じる」). A tap closes it **permanently**: `ChatViewModel.dismissAiHint`
→ `ChatHintStore` (domain) → `SettingsRepository.dismissChatAiHint` writes `chat_ai_hint_dismissed = "true"`
once, and **there is no write that sets it back** — no re-open path in the domain, the repository or the UI
(`ChatHintAndTranscriptPolicyTest`). The line is not drawn again on this install, for either reason the model
is missing (no model configured, or an unsupported CPU).

Nothing is lost by closing it, which is why a one-tap permanent dismissal is safe here:

- the top bar still names the model, or says 「AIモデルなし」, and its menu still reaches 「モデルを管理」;
- a free-text send with no model still answers with the setup card 「AIモデルを設定」 (§13, unchanged) —
  the screen is still never replaced by a setup card, and the chat is never disabled for lack of a model;
- every template, Think session and search keeps working with no model, as before.

**Boundaries unchanged:** the screen takes a flag (`ChatUiState.aiHintDismissed`) and a callback
(`onDismissAiHint`); the view model writes through the domain store and still knows no DataStore, repository,
DAO or entity. It is a **device preference** — outside the portable backup (like the pins) — so Backup 19 and
Room 25 are untouched, and no new permission or dependency is involved.

### 22.2 The conversation reads at the body size of the design system

The transcript was set at `bodyMedium` (14 sp); on the device the conversation read noticeably smaller than
the text around it. Both lines now use **`bodyLarge` — 16 sp / 26 sp**, the system's body step with the line
height Japanese needs: the user's bubble and MemoRipple's flat line alike, so their relationship is unchanged
and only the reading size moves. The meta row (copy, timing), the chips, the cards and the empty state keep
their own sizes. Pinned by `ChatHintDismissInstrumentationTest.theConversationReadsAtSixteenSp`, which reads
the size off the laid-out text rather than the source.

## 23. The chat home launcher (human brief 2026-09-23)

> A one-feature exception to the V1 feature freeze, approved by the human for this round only; the
> freeze returns with it. No version bump, no AAB, no release work.

The chat's empty state gains a small grid of shortcuts — Chrome's new tab, a phone's home screen —
so the things the user does often are one tap away instead of one sentence away.

### 23.1 What it is, and what it is not

A cell is an **entrance, not an action**. Tapping one opens a path the chat already has; nothing on
the grid writes, confirms, or asks a model for anything, and the Fast Path, the DecisionEngine, the
template flow, the preview and the Human Confirmation are all untouched. It is also a **projection**
(`domain/memos/HomeShortcuts.kt`): it invents no id of its own and carries no template — only which
template a slot names and what the user called it.

The chat now has three entrances, each with its own job:

| | |
|---|---|
| the home grid | the few things done often |
| the lower ＋ | the whole template library (unchanged) |
| the input | anything, in the user's own words |

### 23.2 The grid

Four columns, at most two rows, at most **8 cells**: the fixed four, then up to **4** templates the
user put there, in the order they were added, then 「追加」 only while there is room (four of them
fill the grid, so the last cell gives way). No empty slot is ever drawn. Each cell is a mark and a
word — light, no card, no colour of its own, the app's own icon set and no emoji.

| cell | mark | what it opens |
|---|---|---|
| メモ | `EditNote` | 「何をメモしますか？」 → the answer is a memo's body → the ordinary **preview** → the one confirmation |
| 探す | `Search` | 「何を探しますか？」 → the answer is a search → cards in the conversation |
| 日記 | `CalendarMonth` | the built-in 今日の振り返り template, one question at a time as always |
| 整理 | `Lightbulb` | the ＋ picker opened straight at **整理・壁打ち** (Back walks up to its root) |
| a pin | by what it does | that template's own script — a Think template starts Think |
| 追加 | `Add` | the ＋ picker at its root, where pinning already lives |

The two questions are the **same clarification the DecisionEngine uses** — one fixed slot, one fixed
question, an empty draft (`domain/ai/decision/HomeAsks.kt`), answered through the message input and
completed by `completeDecision` → validator → Resolver → policy → preview → Human Confirmation.
`ClarificationSlot` gains `QUERY` for the search question; the deterministic engine never asks for
it. The chat's screen still builds no proposal of its own (`AiBoundaryPolicyTest`).

### 23.3 The home is its own short list — not the picker's pins

The user's review of the same day: **「＋ 追加」 puts a template on the home, and a long press takes
it off or gives it another name** — so the home needs a name of its own per slot, which a pin
cannot carry. It is therefore **its own list**, `chat_home_shortcuts`: a small JSON list of
`HomeShortcutEntry(templateId, label)` in one preference of this device — ids and names, never a
template, never in the portable backup, never a Room column. The ＋ picker's ピン留め (§18) stays
exactly what it was and orders the picker; the two are shown differently and never confused.

- **「＋ 追加」** opens the same picker in its **choosing** mode: the title reads 「ホームに追加する
  テンプレート」, every row says 「ホームに追加」 instead of 作成する / 探す, a template already there
  reads 「ホームにあります」 and is not tappable, and a full home says so rather than silently refusing.
  A tap **adds and closes** — it never runs the template.
- **A long press on a cell** (a template cell only — the fixed four are not the user's to change)
  offers 「名前を変更」 and 「ホームから削除」. The name is at most 12 characters on one line; blank
  restores the template's own name; the template itself is never touched.
- **A long press on a row in the ＋ picker, or in 設定 → テンプレート,** offers 「削除」 for one of the
  user's **own** templates, behind a confirmation that says 「メモや日記は削除されません」. A starter is
  code and is never offered; deleting takes the template's home slot with it.

What follows for free and is tested: a template **moved between template folders keeps its cell**;
an **orphan slot is skipped** and leaves no hole; more than four chosen templates simply do not
reach the grid. **Recents are deliberately not on the home**: a launcher whose places move is not a
launcher.

### 23.4 When it shows

Only on an **empty** conversation, and only while no question is open: one line of transcript — the
user's or MemoRipple's — and the grid is gone with the rest of the empty state. 新しいチャット brings
it back. It shows with **no model installed**, and 探す / 日記 / 整理 / a template pin all work with
no model, no load and no network.

### 23.5 Accessibility, layout, storage

Every cell is a `Role.Button` with a content description that is its word (the mark never carries
the meaning alone), at least a 48 dp touch target, one line with an ellipsis for a long name (the
full name stays readable in the picker). Pinned at 360 dp × font scale 2.0 and in both themes.
Room 25 and Backup 19 are untouched, the saved state is still only the chat's draft, and no
permission or dependency was added; the one thing the home persists is its own list of ids and
names.

### 23.6 Not in this round

Dragging cells to reorder, removing or rearranging the fixed four, a home of more than eight, a
management screen of its own, recents on the home, renaming the template itself from the home, and
any coloured launcher iconography.

## 24. The preview by field, and 「修正」 as rows (2026-09-24, the user's review)

### 24.1 「修正」 is a list of pressable rows

The chooser used to be field names coloured like links beside their values, so what could be tapped
was unclear. Each field is now **one row**: its name, what it says now underneath as secondary text,
and a chevron at the end. The **whole row** is the target — `Role.Button`, at least 48 dp, and a
content description that reads the name and the value together — and the heading says 「修正する項目
を選んでください」. An unanswered or skipped field reads **「未入力」**, never 「（スキップ）」.

A tap asks that one question again through the **unchanged** one-question-at-a-time script, and the
preview is rendered again from the answers. Deterministic throughout: no model is asked anything.

### 24.2 The preview reads by field

A template preview showed the Markdown the file would hold (`## 良かったこと` and the rest). It now
shows the template's **own fields and their values**, drawn from the field metadata — so every
template reads the same way and nothing is special-cased. A template with no fields (a legacy
name + body one) still shows its body, because there is nothing else to show.

**The body that will be written is unchanged.** This is presentation only: the same rendering, the
same `ResolvedCommand`, the same ticket. The preview still ends with 「『作成』を押すまで書き込み
ません。」 and the one confirmation.

### 24.3 A memo asked for in one question can be asked again

The launcher's メモ (and 探す before it) asks one question and previews the result; that preview now
carries **修正** as well. It asks the same question again and puts the previous answer back in the
input, so it is changed rather than retyped. The preview goes with it; nothing is written until the
next one is confirmed. The question, its slot and its draft are the ones already open — nothing new
is inferred, and the memory of them is ephemeral like the question itself.
