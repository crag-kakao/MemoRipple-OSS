# Think Templates — 考えを整理するためのテンプレート

> Human brief 2026-09-22 (`feature/think-templates`, from main `c418ef3`). The chat becomes
> **探す + 操作する + 考える**: a template is no longer only a way to *record* but also a way to
> *think*, and it does so with no AI, no network and no write.

## 1. Purpose

Template v2 (docs/CHAT_UI_TEMPLATE_V2.md) made a template a declarative action — CREATE / SEARCH
/ APPEND, asked one question at a time, ending at a preview and a Human Confirmation. A **Think
template** keeps the whole conversation and changes only its end: the questions end in a
**result** — the answers rendered into a fixed shape and shown in the conversation — and a memo
is made only if the user then asks for one.

```
Conversation script → questions → answers → result → (optional) save
```

Starting a Think template creates nothing. Finishing one creates nothing. Only 「メモとして保存」
does, and it does so through the ordinary CREATE path.

## 2. The design — a flow, not an action (decision C)

Three shapes were weighed in the audit:

| | shape | why not / why |
|---|---|---|
| A | a fourth `TemplateAction.THINK` | would put a non-executing verb beside three executing ones; every `when (action)` in the runner, the validator, the editor, the mapper and the wire formats would grow a branch that means "do nothing"; an older reader of format 19 would refuse the word |
| B | a CREATE template with a flag read by the chat only | works, but the flag would live nowhere the validator or the editor could see |
| **C** | **a `flow` of the template — `TemplateFlow.RECORD` / `THINK` — beside the action** | the action stays what is *done* (CREATE MEMO underneath), the flow says how the script *ends*; a Think template is valid CREATE MEMO template to every older reader; the chat alone decides what Ready means |

C is what is built. `MemoTemplate.flow: TemplateFlow = RECORD` is defaulted; `isLegacyShape`
requires RECORD; `TemplateAction` is still exactly `{ CREATE, SEARCH, APPEND }`.

**Think has no execution authority.** The result is rendered by `ThinkTemplates.result` in
`domain/memos` — `{{key}}` substitution and nothing else — and handed to the chat as
`AiInteractionResult.ThinkResult(template, text, answers)`, a result type the orchestrator never
produces (source-policy-pinned). It carries no `PendingWrite`; there is nothing to confirm. A save
is `ChatViewModel.saveThinkResult()` → `executeTemplate(…, save = true)` →
`AiOrchestrator.runTemplate` (the CREATE branch, unchanged) → `CommandPreview.Template` → the one
`confirm()` → `CommandExecutor` → `DocumentAccess`. The runner asks every question of a Think
template before a save can render (`TemplateForm` for any unasked key), so a model's
USE_TEMPLATE of a Think template still goes through the conversation.

## 3. AI optional — and deterministic either way

- No model, no runtime, no thermal gate, no network is touched on the Think path
  (`ThinkTemplatePolicyTest`; `TestAiRuntime.loads == 0` in every journey).
- The result is not a summary. It is the template's body with the answers in it:

```
今日やること - {{today}}

## やること
{{tasks}}

## 今日中に終わらせたいこと
{{priority}}
…
```

- `{{today}}` is the one value every template has without asking — the run's day from the app's
  clock as `yyyy-MM-dd`; its key is reserved (a field may not be named `today`); the editor
  inserts it as `⟦今日の日付⟧`.
- A skipped question reads **（なし）**; nothing is guessed in its place.
- The first line of the result is the memo's title should the user save (a memo's title is its
  first line — `DocumentTitles`): 「今日やること - 2026-09-22」, 「アイデア - 音声メモ」, 「企画 -
  読書会」, 「プロジェクト整理 - MemoRipple」. No AI makes a title.
- With a model installed, the questions, their order and the result are the same deterministic
  engine. Adaptive questions and AI-written summaries are a later round (§11).

## 4. The four starter Think templates

Read-only code in `StarterTemplates` beside the six of the first brief (ids `starter-think-*`;
`MAX_STARTERS` 12). The first question of each is required; the rest may be skipped.

| starter | questions (in order) | result |
|---|---|---|
| **今日やること整理** | 今日やる必要があることを、思いつくまま教えてください。／ その中で、今日中に終わらせたいものはどれですか？ ／ 最初に取りかかるものは何ですか？ ／ 困りそうなことはありますか？ | 今日やること - 日付 · やること · 今日中に終わらせたいこと · 最初にやること · 気になること |
| **アイデア壁打ち** | どんなアイデアですか？ ／ 何を解決したいですか？ ／ 誰が使うものですか？ ／ 面白いと思う点はどこですか？ ／ 気になっている問題はありますか？ ／ 次に試すなら何をしますか？ | アイデア - … · アイデア · 目的 · 対象 · 強み · 懸念 · 次の一歩 |
| **企画整理** | 何を企画していますか？ ／ その目的は何ですか？ ／ 誰に使ってほしいですか？ ／ 一番大事な価値は何ですか？ ／ 似たものとの違いは何ですか？ ／ 実現するうえでの課題は何ですか？ ／ 次に決めることは何ですか？ | 企画 - … · 企画概要 · 目的 · 対象 · 価値 · 違い · 課題 · 次の判断 |
| **プロジェクト整理** | どのプロジェクトについて整理しますか？ ／ 今どこまで進んでいますか？ ／ 残っている作業は何ですか？ ／ 今詰まっていることはありますか？ ／ 次にやることは何ですか？ | プロジェクト整理 - … · 現在地 · 残作業 · 課題 · 次の作業 |

## 5. The picker — three light sections

Ten starters plus the user's own would be one long list, so the ＋ picker groups by what each
template *is* (`ThinkTemplates.sectionOf`, never a list of ids), under 最近使ったテンプレート:

- **記録** — CREATE and APPEND templates (flow RECORD)
- **整理・壁打ち** — THINK
- **探す** — SEARCH

An empty section is not shown. Rows are the same small rows as before (name, description, what
it does — a Think template says 整理する, and 「スターター」 on a built-in one). No cards, no more
sections than these three.

## 6. The conversation

Exactly the Template v2 shape: MemoRipple asks, the user answers through the input or the chips,
MemoRipple asks the next. No Think screen — everything stays in the conversation.

- **One question at a time**, in the template's order (`TemplateScript`, unchanged).
- **やめる** on the first question only; Back cancels the open question (the standing rule).
- **スキップ** on an optional question.
- **The result:** an assistant line 「整理すると、こんな内容です。」 followed by the rendered text
  (`AiWording.THINK_RESULT_LEAD`), and under it a small card (`chat_think_result`) with
  **「メモとして保存」** (`chat_think_save`) and **「終了」** (`chat_think_done`). The words are the
  transcript line; the card only offers what to do with them (flat summary + action).
- **終了:** the card and the session go; the line stays; nothing was written.
- **メモとして保存:** the user's line 「メモとして保存」, then the CREATE preview
  (`chat_ai_preview_template`) with the same rendered body, 修正 for one answer, キャンセル, 作成 —
  the Template v2 preview, unchanged. Confirm makes exactly one memo (one ticket = one write);
  cancel makes nothing.

### 6.1 修正 on the result (Review Batch 2, 2026-09-22)

The result card reads **「修正」 「メモとして保存」 「終了」**. 修正 opens the same list the preview's 修正 opens —
「どの項目を修正しますか？」 with each question's **label** and the current answer (a skip reads 「（スキップ）」),
never a key — inside the conversation, not a form. One row → the field's original question is asked again
as an assistant line → the message input answers it → the result is rendered again by the same
deterministic engine and shown with the same three actions. The earlier result line stays in the
transcript (the fix is a new 「整理すると、こんな内容です。」 line); the other answers are kept. During a fix:
no write, no model, no runtime, no network (`ReviewBatch2PolicyTest`, journey A). A save after a fix
uses the new answer through the CREATE preview and the one confirm (journey F).

## 7. Persistence, process death, privacy

- **Conversation history:** the questions, the answers and the result line are ordinary transcript
  lines (`TEXT` / `RESULT`); no field key, no template state, no `{{…}}` is ever recorded — the
  result line is the rendered words (`ThinkTemplateInstrumentationTest` checks the transcript).
- **Process death:** the session and the result card are ephemeral (`TemplateSessionState`,
  `AiPanelState`); a death mid-conversation keeps the transcript, drops the session, writes
  nothing and resumes nothing. The same rule as every template session.
- **Recent templates:** a Think template is recorded as recent when its result shows (id + time
  only), the same store and cap as before.
- **Privacy / logging:** the one log line is `think completed: fields=N renderedChars=N` — no
  question, answer, title or result text (`ThinkTemplatePolicyTest` scans every log line).

## 8. The editor — 整理する

The 操作 step offers **作成する / 探す / 追記する / 整理する**. 整理する sets the flow to THINK on a
CREATE MEMO template (the kind chips disappear); the 入力項目 step says 「答えは結果に差し込めます。
質問は1つ以上必要です。」; the 内容 step is titled 整理した結果 and its insert menu offers
⟦今日の日付⟧ beside the fields; the 確認 step reads 「整理する（結果をチャットに表示。メモとして保存
は任意）」 and previews the result. No THINK / RECORD / CREATE word is shown. A user can make
「読書の振り返り」 — 何を読んだ？ / 印象に残ったことは？ / 疑問は？ / 次に調べたいことは？ — and it
runs exactly as a starter does (journey in `ThinkTemplateInstrumentationTest`).

Validation (`TemplateValidation`): a Think template must be CREATE + MEMO
(「整理するテンプレートは、メモを作る操作でだけ使えます」) with at least one question
(「整理するテンプレートには質問が1つ以上必要です」); `today` is a reserved key.

## 9. Import / export and the backup

- **Template file** (`memoripple_templates` formatVersion 1, unchanged): `flow` is serialised
  with the template (`"flow":"THINK"`); a reader before this round ignores the unknown key and
  imports the same template as a CREATE memo template with its questions.
- **Backup format 19 — not bumped.** `TemplateBackupDto` gains `flow: String = "record"`
  (`record` / `think`), a defaulted word. A 19 file written by this build restores whole here; the
  same bytes read by the build before Think templates (main `c418ef3`) yield a valid CREATE memo
  template with the same questions — `BackupMapperTest` simulates that reader. 1–18 still restore;
  an unknown word is refused as before. This is the "optional / defaulted field of the existing
  19" the brief asked to audit for; no format-20 reason was found.

## 10. Tests

- `ThinkTemplatesTest` (domain): flow beside action; the ten starters and the four's questions in
  order; the script asks one at a time and skips; the deterministic result, the day, （なし）, the
  titles; `{{today}}` and the reserved key; the Think validation; the template-file round trip.
- `ThinkTemplatePolicyTest` (sources): the action enum unchanged; the result made by the chat's
  engine, never the orchestrator; no runtime / network / DAO on the path; the save goes through
  `executeTemplate(save = true)`; one `confirm()`; the session never saved; the screen's tags and
  sections; the editor's 整理する with no technical word; format 19 unbumped with the defaulted
  word; the log lines.
- `BackupMapperTest.aThinkTemplateRoundTripsInFormat19AndAnOlderReaderSeesACreateTemplate`.
- `ThinkTemplateInstrumentationTest` (emulator, no model, offline): A 今日やること整理 → questions →
  result → 終了 → zero writes; B アイデア壁打ち → save → preview → cancel → nothing changed; C
  企画整理 → save → confirm → exactly one memo; D process death midway; E/F 今日の振り返り and
  昨日の日記を探す unchanged; a custom Think template. G (a model, free text) stays in
  `ConversationTemplateFlowInstrumentationTest`.

## 10.1 Reusing a Think conversation (2026-09-22)

A Think conversation that worked can become a template of its own: 「この会話からテンプレートを作成」 in
the chat's overflow drafts a THINK template from the questions asked and answered (never from the
result line, never with the answers) and opens it in the editor — `docs/CHAT_UI_TEMPLATE_V2.md` §19.

## 10.2 Folder classification (2026-09-22)

In the ＋ picker the four Think starters live in the built-in folder **整理・壁打ち** (`ThinkTemplates.sectionOf`
— by what a template is, never by a list of ids); a user's own Think template (made in the editor as
整理する, or drafted from a conversation) lives under 自分のテンプレート in whichever folder the user files
it, or 未分類 — `docs/CHAT_UI_TEMPLATE_V2.md` §20.

## 11. Deferred — Adaptive Think (a later round, not this one)

- Additional questions chosen from the answers by a model; a natural-language summary by a model.
- Saving to a journal or an outline (v1 saves a memo).
- ~~修正 from the result card itself~~ — done in Review Batch 2 (§6.1).
- A 壁打ち starter category beyond these four; template creation from the ＋ picker in place.

Nothing here is finished until the user has seen it on the device: the result's look, the card,
the sections. No version bump / AAB / Play.
