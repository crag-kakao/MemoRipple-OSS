# AI Target Resolution — Local LLM Phase 6 (Chat v1 Reliability / Target Resolution)

> Branch `feature/ai-target-resolution` from `main` `b78086e`, 2026-09-20. No new AI feature: the
> current チャット AI mode made to succeed safely when the model drops a field. Read
> `docs/AI_SAFE_INTENT_PIPELINE.md` (Phase 1), `docs/AI_CHAT_PREVIEW.md` (Phase 3) and
> `docs/AI_CONFIRMED_WRITE.md` (Phase 4) first; this document adds only the deterministic
> candidate layer and what it may and may not do.

## 1. Why

The permanent regression fixture 「MemoRipple開発に『Folder対応完了』を追記して」 (case C) came back from
Qwen3-4B on the S20, three rounds in a row, as `APPEND`, `text = "Folder対応完了"`,
`targetName = null`, `missingFields = ["targetName"]` — the model does exactly what prompt v1
tells it to do with a value it is unsure of. The pipeline then stops at NeedsInformation
(「追記先が分かりません」), which is safe but fails an ordinary request whose target is plainly
written in the user's own sentence. Phase 6 lets the app read that name from the sentence —
by rule, with evidence — and still resolves, previews and confirms it like any other target.

## 2. Audit (before RED)

| # | Looked at | Found |
|---|---|---|
| 1–4 | Orchestrator, `IntentProposal`, Resolver, target lookup | The raw user text is available at the resolution point; the Resolver already searches a name across the AI scope, prefers exact title matches (NFKC-folded, trimmed, lowercased) over partial hits, filters by kind, and answers one / several / none. Nothing app-side touched a missing target. |
| 5–6 | `DocumentSearch`, `DocumentQuery` | Terms AND over title, body and tags (memo / outline), over the body for journals; NFKC folding; order updatedAt DESC, never used to pick. |
| 7 | Result context | `result_N` within one request already covers 「2番目を開いて」; 「前のやつ」 has no meaning without history. |
| 8–10 | Ambiguous / NeedsInformation UI, confirmed-write preview | Phase 3 cards keyed by intent; the Phase 4 preview, conflict and duplicate protection. |
| 11–12 | Input ownership, raw input retention | The view model owns the AI input; the orchestrator keeps the raw text for the whole ask and never persists it. |
| 13 | Case C fixtures | Phase 0 `smoke_C` (expected `targetName` 「MemoRipple開発」), Phase 3–5 tests, the S20 raw answers. |
| 14 | Prompt v1 | Names in `targetName`, unknowns null with `missingFields`. Untouched in Phase 6 (byte-identity test). |
| 15–17 | Text utilities, tokenizer, quotes | No Japanese tokenizer or parser; `ProseTyping` knows 「」『』 pairs; `MemoSearch` parses the search syntax only. |
| 18–23 | Title semantics, duplicates, folders, ranking, normalization, exact vs partial | Journal title = first non-blank body line; duplicate titles are legal and become Ambiguous; folders are not in the AI scope of a name lookup; exact title wins over partial; ranking never picks. |
| 24–25 | Candidate UI, error strings | Phase 3's candidate rows; wording consistent in shape (plain title, next step, 「現在のデータは変更されていません」 on write failures); the runtime-failure cards did not yet say the search mode still works. |

## 3. The deterministic candidate layer

`TargetCandidateExtractor` (`domain/ai`): pure and rule-based; input the user's text and the
proposal; output at most one `TargetCandidate(text, source)` where `source` is evidence
(`BEFORE_PARTICLE_NI`, `BEFORE_OPEN_VERB`), never a score. It searches nothing, refers to
nothing, and cannot execute anything (it names no `DocumentRef`, `DocumentAccess`, Resolver or
executor — tests read its source).

Shapes handled — and only these:

| Intent | Shape | Candidate |
|---|---|---|
| APPEND | `Xに『Y』を追記して` / `Xに「Y」を追記して` / `Xに"Y"を追記して` / `XにYを追記して` / `Xに追記して` (also 追加 / 書き足) | X — the segment before the first に, with every quoted segment removed first, so the quoted append text is never a target and a に inside the quote cannot split the name |
| OPEN | `Xを開いて` / `Xを開く` / `Xを見せて` / `Xを表示して` / `Xを出して` | X |

Normalization: NFKC (full-width letters and spaces to plain), trimmed, inner whitespace
collapsed; nothing else — no edit distance, no phonetic guess, no similarity, no embedding.

Refused on purpose (false-positive protection): demonstratives and references to earlier turns
(これ / それ / あれ / さっきの / 前のやつ / N番目 …); bare document and folder words (メモ / 日記 /
アウトライン / ノート / フォルダ / テンプレート / 記録 …); a date word followed by such a word
(今日の日記, 明日のメモ, 9月19日の日記, 今週の予定); clauses carrying sentence particles or
punctuation (…は / …が / …ので / …から / …けど / …して / 、); empty or over 64 characters. A title
that merely contains の (今後の予定, 旅の記録) stays a candidate. Known limit, accepted: a title
that itself contains に (日曜日にやること) is cut at that に; the Resolver then finds nothing or
several, both safe stops.

## 4. Where it sits

```
LLM → IntentProposal
       → [OPEN / APPEND with neither targetRef nor targetName] TargetCandidateExtractor.assist → targetName filled, the model's TARGET_* gaps cleared
       → Resolver (SemanticValidator inside; exact title → partial → kind) → one / several / none
       → ExecutionPolicy → Direct (OPEN) | CommandPreview (APPEND) → Human Confirmation → DocumentAccess
```

- The assist runs **only** when the model gave neither a shown ref nor a name for OPEN / APPEND.
- A name the model did give is honoured as it is; a name that is NotFound stays NotFound —
  **no fallback** to the extracted candidate (fixed by test, so the two never compete).
- A shown `result_N` still wins over everything.
- `proposal.text` is never touched; a missing text stays a question.
- The candidate is a *name*: the unchanged Resolver searches it — exact title first, then
  partial; **one resolves, several are Ambiguous (the user chooses), none is NotFound**. The
  first hit is never taken. A kind from the model narrows the search; without a kind the whole AI
  scope (memo, outline, journal by first line) is searched.
- The resolution source is a developer note (`onNote` on `interact`, logged by the view model
  under `AiChat`) — **redacted**: `assistApplied=true source=BEFORE_PARTICLE_NI candidateLength=12
  intent=APPEND`, never the candidate text, a title, the input, the append text, a raw answer or a
  ref (human decision 2026-09-20; `AiLoggingPolicyTest` scans every AI log line). The screen shows
  the ordinary preview (追記先: MemoRipple開発) with no technical explanation.

## 5. Case C, end to end

| Documents | Result |
|---|---|
| exactly one titled 「MemoRipple開発」 (a body mention elsewhere is only a partial hit) | append preview of that document; database untouched until 追記; one confirmed write; a second tap refused; a document that moved after the preview is a conflict with nothing written |
| two titled 「MemoRipple開発」 (memo + outline) | candidates, nothing opened, nothing written |
| none | NotFound (「その名前の記録は見つかりません」), nothing created |
| model returned `targetName` | used as given; the extractor never runs |
| model returned a name that does not exist | NotFound, no fallback |

OPEN: 「MemoRipple開発を開いて」 with no model target → unique → opens; several → candidates; none
→ a safe stop. 「前のやつを開いて」, 「これに追記して」, 「今日は疲れたので日記に追記して」,
「フォルダにメモを追加して」 → no candidate → NeedsInformation as before.

## 6. Not used, not built

No LLM confidence, no embeddings, no semantic or vector search, no fuzzy matching, no prompt
change (prompt v1 and the grammar are byte-identical to `tools/llm-eval`), no conversation
history (inputs that need one stop at NeedsInformation), no new folder memory, no journal title
column, no destructive intent, no automatic write. Room 24 / Backup 18 / the five saved keys /
the model management unchanged (policy test).

## 7. Wording audit

The AI failure cards were read side by side. Shape kept: a plain title, one next step, and
「現在のデータは変更されていません」 on every write failure; the model-state cards (not selected, not
installed, unsupported CPU, hot device) already said 「検索モードはそのまま使えます」. One
unification: the runtime-failure cards (load, generation, parse) now say it too. NeedsInformation
keeps its intent-specific first line (追記先 / 開く対象 / 追記する内容 / どれを作るか / どのテンプレート /
何を探すか); Ambiguous keeps 「どれを開きますか？」 with the candidate rows.

## 8. Tests

JVM: `TargetCandidateExtractorTest` 13, `AiTargetResolutionTest` 18 (case C on the fake boundary
end to end, OPEN, false positives, exact before partial, kind, journal first line, authority),
`AiTargetResolutionPolicyTest` 4. Device (emulator, scripted runtime answering exactly what Qwen
answered, real Resolver and database): `AiTargetResolutionInstrumentationTest` 5 — unique →
preview → one confirmed append; two → candidates; none → not found; moved → conflict; OPEN
unique and the false positives. The Phase 3 / 4 / 5 classes and Chat v0 stay green beside it.

## 9. Device verification

S20 (`AiTargetResolutionSmokeTest`, real Qwen3-4B, skipped without `-e llmModelPath`): the test
makes one temporary memo of its own with a unique title, asks the case C sentence about it,
expects the append preview whether or not the model names the target, presses キャンセル (never a
write on the device), verifies its memo and every other document unchanged, and removes its memo.
No existing document is used as a fixture. Result: HANDOFF §16.57 and below.

**Results (2026-09-20 16:49–16:55 JST, S20, Qwen3-4B-Instruct-2507 via the debug
developer file, USB, thermal 0 before and after every run, branch build installed in place, data
intact):** three runs, all `OK (1 test)`, the temporary memo removed after each.

| Run | Temporary title | Card | Wall time | Developer note |
|---|---|---|---|---|
| 1 (`edce639`) | 「MemoRipple開発 71734」 | `chat_ai_preview_append` | 42.3 s | invisible: the note was `Log.d`, which this user build filters |
| 2 (`6ad16bf`, notes at info level) | 「MemoRipple開発 59756」 | `chat_ai_preview_append` | 44.8 s | no assist note → the model itself named the numbered title |
| 3 (exact fixture title) | 「MemoRipple開発」 | `chat_ai_preview_append` | 38.1 s | **`target assist: … BEFORE_PARTICLE_NI …`** (at that commit the note still printed the candidate; redacted afterwards, see run 4) — the model dropped the target as in every earlier round, the assist named it, the Resolver found the unique memo, the preview showed 追記先 MemoRipple開発 / 追加する内容 Folder対応完了 |
| 4 (`880963c`, redacted note) | 「MemoRipple開発」 | `chat_ai_preview_append` | 36.6 s | **`target assist: assistApplied=true source=BEFORE_PARTICLE_NI candidateLength=12 intent=APPEND`** — no title, no input, no text in the log; a MODERATE and a LOW memory-pressure event during LOADING / GENERATING ignored by the Phase 3 rule |

In every run キャンセル was pressed, the temporary memo's body and every other document were
unchanged, and the memo was removed afterwards. Run 1's log also shows a LOW memory-pressure
event during LOADING being ignored by the Phase 3 rule — the ask went on to its preview.

## 10. Phase 8 amendment (docs/AI_CONVERSATION_HISTORY.md)

§3's refusal of demonstratives and §5's 「前のやつを開いて」 → NeedsInformation describe the extractor,
which still refuses them. Since Phase 8 a conversation carries a **structured anchor** (the last
document the user opened, chose or confirmed a write on) and the latest shown results by position:
「それ」 / 「前のやつ」 / 「さっきのメモ」 resolve to the anchor by rule (re-read through the boundary, the
kind checked) and 「N番目」 to a stored result — never from the transcript's words, and only when
such a structured reference exists; without one they remain questions. Every such target still
goes through the Resolver, the preview and the Human Confirmation.
