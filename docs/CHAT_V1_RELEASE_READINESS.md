# Chat v1 Release Readiness — Local LLM Phase 7

> Branch `feature/chat-v1-release-readiness` from `main` `42f5dfc`, 2026-09-20. **No new AI
> capability**: the round audits the Chat v1 that exists (検索 v0 + the AI mode of Phases 3–6) for
> product quality, fixes what would block a release, polishes what a first user meets, and records
> what is deferred. Read `docs/AI_CHAT_PREVIEW.md`, `docs/AI_CONFIRMED_WRITE.md`,
> `docs/AI_MODEL_MANAGEMENT.md` and `docs/AI_TARGET_RESOLUTION.md` first; this document is the
> release view over them. Not in scope, unchanged, and still forbidden: conversation history, RAG,
> a vector database, semantic search, a background agent, destructive intents, Future Diary in the
> AI pipeline, a version bump, any Play step.

## 1. Verdict

| Class | Count | State |
|---|---|---|
| **BLOCKER** | 4 found | **all fixed in this round** (§3) |
| **POLISH** | 9 | done in this round (§4) |
| **DEFER** | 4 | recorded with an owner and a trigger (§5) |
| Remaining before a public release | 3 | human-owned, outside the code (§16) |

Chat v1 is **release-ready on the code side under the feature-gated CPU decision (C)** — see §8.
What remains is the human's: the public Privacy Policy text for the optional model download, the
signed build, and the Play-side Data Safety answers.

## 2. Audit (35 items, before RED)

| # | Item | Found (main `42f5dfc`) | Class |
|---|---|---|---|
| 1 | Chat 検索 / AI UI | Segmented 検索 / AI; the AI input, a note, an empty state with two examples. Discovery is the segment itself. | ok |
| 2 | First use, no selected model | The input was offered; the failure came only after a send (「AIモデルがまだ利用できません」 card). | **BLOCKER** |
| 3 | Model not installed | Same path as 2; the settings screen said 未ダウンロード. | covered by 2 |
| 4 | Downloading | Status text + progress bar + キャンセル; no state semantics. | polish |
| 5 | Installed but not selected | 「使用する」 button, 使用中 chip only when selected; the chat said 「使うモデルが選択されていません」 after a send. | covered by 2 |
| 6 | Selected model missing / corrupt | Missing → reported as 「選択されていません」 (wrong reason). Wrong length → shown **Installed**, selectable, handed to the engine → a load error with retry-by-resend. | **BLOCKER** |
| 7 | Unsupported CPU | Refused before the engine (`productLocalModelRuntime`, policy-pinned); downloads disabled; search works. | ok |
| 8 | Thermal blocked | Gate before load and inside the generator; the confirmed write has no gate (correct). Wording 「休ませています」. | polish |
| 9 | Memory-pressure unload | LOW deferred while an ask runs, CRITICAL unloads; the next ask reloads; search untouched. | ok |
| 10–12 | Load / generation / parse failure | One card each; 「もう一度お試しください」 but no button — the user had to resend. | polish |
| 13–15 | Invalid / NeedsInformation / Ambiguous | Worded per reason, candidates listed, nothing opened. | ok |
| 16 | Confirmed write success / failure | Conflict said 「もう一度内容を確認してください」 with only 閉じる. | polish |
| 17 | Model settings screen | Neutral, no ranking; state as one text line; installed vs selected only by the 使用中 chip. | polish |
| 18 | Interruption / resume | `.part` + `Range`; cancel keeps the part; tested since Phase 5. | ok |
| 19 | Offline | No network read: with no network the metered answer is "metered" → the dialog → a confirmed download that dies with a network failure. | **BLOCKER** |
| 20 | Process death / recreation | Mode + input saved; result / preview / ticket never; view-model test since Phase 4; no screen-level proof. | polish (test) |
| 21 | Accessibility semantics | Icons described; segmented buttons carry selected; cards merged. Missing: model card selected / state, live regions on the status lines. | polish |
| 22 | Large font / narrow screen | Two fixed `Row`s of chips (4 + 4) and the card header `Row` overflow at font scale 2.0 on 360 dp. | polish |
| 23 | Dark / light | Every colour from the Material roles; no literal colour in the AI UI. | ok (tested now) |
| 24 | Delete while selected | unload → deselect → remove; documents untouched (Phase 5 test). | ok |
| 25 | Model switch | Unloads, never loads (Phase 5 test). | ok |
| 26–28 | Free space / metered / battery guards | Present, ordered, tested. | ok |
| 29 | Privacy / logging | `AiLoggingPolicyTest` scans every AI log line; R8 keeps `Log` calls, so redaction is the guarantee (it is). | ok |
| 30 | Release manifest / permissions | 7 platform permissions; the loopback cleartext config is a debug overlay only. The Data Safety inventory (internal, not published) still said "exact 6" and no Hugging Face endpoint. | docs fixed |
| 31 | APK / AAB packaging | Debug: 0 `.gguf`, two native libs; release not built since Phase 2. Loopback HTTP was allowed by the shipped downloader class (an allowance, not an endpoint). | polish + §13 |
| 32 | ARM CPU feature gate | `asimddp` in `/proc/cpuinfo`, engine constructed only behind it; no SIGILL path. Decision A / B / C open. | §8 |
| 33 | S20 final regression | To run at the end (§15). | — |
| 34 | Known full-suite flake (Compose wall) | `dismissDisplayOptions` family, documented since Phase 8x; isolated 3 / 3 OK every time. | defer (known) |
| 35 | Portable-export browser-archive flake | New in the Phase 6 main gate: the memo HTML lacked `photos/photo-01.jpg` once. | classified (§14) |

## 3. Blockers and their fixes

1. **AI mode usable before a model exists (2, 3, 5).** `AiOrchestrator.availability()` (new) answers
   `ModelAvailability` — `Available(model)` or `Unavailable(reason)` — from the CPU gate and the
   selection alone, **without constructing a runtime**. `ChatViewModel.refreshAvailability()` asks it
   when the AI mode opens, whenever the screen comes back in front (`LifecycleResumeEffect`: the
   return from the Local AI モデル screen, the return from the background) and after every dismissed
   card. While unavailable the screen shows the **setup card** in place of the input:
   「AIモデルを選択すると、メモや日記を自然な言葉で検索・操作できます」 / 「検索モードは今すぐ使えます」 /
   [AIモデルを設定] — one tap to the models screen (`onOpenAiModels`, a new route callback). No note,
   no input, no send. An ask that still finds no model (the file removed between the check and the
   send) turns into the same card.
2. **Corrupt file treated as installed (6).** `ModelManager.scan` compares an installed file's length
   with the catalog's verified byte count: a mismatch is `Failed(CORRUPT_FILE)`, never `Installed`;
   `select` refuses it; the user's 再試行 removes it and downloads afresh (never a resume of a corrupt
   file). `ProductModelSelection.availability()` decides the reason from the preference and the file:
   nothing selected → `NO_MODEL_CONFIGURED`; selected but the file gone → `MODEL_FILE_MISSING`; wrong
   length → `MODEL_FILE_CORRUPT`; **the selection is never switched by the app** and no other installed
   model is picked. The orchestrator returns `ModelUnavailable` **before any load** (`interact` reads the
   availability first). Why length, not a hash: a full SHA-256 of a 2.5 GB file on every ask would read
   gigabytes; the hash was verified at install, the length is the pre-load check, and the engine's own
   GGUF header validation is the rest (a failure there is the load-error card with 再試行). The models
   screen re-reads the disk when it opens (`refresh()` on entry), so a vanished or corrupt file shows as
   it is.
3. **Offline download start (19).** `DeviceGuards.isOnline()` (new) is read at the same guard point,
   right after the CPU gate and **before** the metered question: no network → `DownloadRequest.Offline`
   → 「ネットワークに接続していないため、ダウンロードを始められません。ダウンロード済みのモデルはオフラインでも使えます。」
   Nothing starts, no `.part` is touched, no dialog. The read is `ConnectivityManager.activeNetwork` +
   `NET_CAPABILITY_INTERNET`, a one-shot, under the already-declared `ACCESS_NETWORK_STATE`; no
   callback, no receiver, nothing monitored (policy-pinned). An installed, selected model needs no
   network: the ask path never touches one (journey K).
4. **Portable-export flake (35).** Classified as a shared-fixture race with the app's own attachment
   garbage collection (§14); fixture reordered, no assertion changed.

## 4. Polish done

- **Retry as one tap.** Load / generation / parse failure cards carry 「再試行」 (`chat_ai_retry` →
  `ChatViewModel.retry()`: the same words asked again). No loop, no timer, nothing retries by itself
  (`ChatV1ReleasePolicyTest`: no `while` / `repeat` / `delay` in the view model). A conflict carries
  「もう一度確認する」 (`chat_ai_reconfirm`): a **new inference and a new preview** of the document as it
  is now; the old ticket is never run again (`.confirm(` still has exactly one caller).
- **Wording.** Thermal: 「端末が熱くなっているため、AIを一時停止しています」 / 「冷めてからもう一度お試しください。検索モードはそのまま使えます。」.
  Generating: 「処理しています…」 (the model is not a person). Loading: 「AIモデルを読み込んでいます…」 kept.
  Every unavailable reason has its own title and next step (§3.1); every failure card still names the
  search mode and says 「現在のデータは変更されていません」 where data could have changed.
- **Model status at a glance.** Each card carries a state chip (未ダウンロード / ダウンロード途中 /
  ダウンロード中 / 検証中 / ダウンロード済み / 失敗) beside the category chip, and the 使用中 chip only when
  selected — installed and selected are two chips, never one.
- **Accessibility.** Model cards expose `selected` and a `stateDescription` (the status sentence, plus
  「、使用中」 when selected); the progress bar exposes its range; the chat status lines (loading,
  generating, writing) are polite live regions; disabled downloads are disabled for a reader too.
- **Large font / small screen.** The search chips, the model card header chips, the preview buttons and
  the card actions are `FlowRow`s: they wrap instead of running off a 360 dp column at font scale 2.0
  (`ChatV1LayoutInstrumentationTest` reads the bounds).
- **Release tightening.** `HttpModelDownloader(allowLoopbackHttp = false)`: the shipped downloader is
  HTTPS-only; the loopback cleartext the fixture server needs is a test-time opt-in the product never
  sets (policy-pinned).
- **Process-death proof at the screen level.** `processDeathDuringAPreviewLosesThePreviewAndTheTicketAndWritesNothing`
  clears the view-model store and recreates the activity with a preview on screen: the mode and the
  input come back, the preview and its ticket do not, the database is byte-identical.
- **Docs consistency.** The Data Safety inventory (internal, not published): seven platform permissions (ACCESS_NETWORK_STATE
  added in Phase 5), the Hugging Face model download as an optional, user-initiated network path,
  the model files in the data-flow matrix.

## 5. Deferred (recorded, not built)

| Item | Why deferred | Trigger to reopen |
|---|---|---|
| ARM option B (baseline + dotprod dual native library) | A native build change; C ships (§8). | Play device data showing non-dotprod arm64 devices among users, or a report. |
| Compose wall flake (`dismissDisplayOptions`) | Environment; documented family; 3 / 3 alone every time. | Never loosened; revisit if it fails in isolation. |
| Real multi-GB download E2E | Proven on the S20 in Phase 5 (Ministral 2.15 GB, SHA-256 verified). | A catalog change. |
| A full-hash integrity check before load | Gigabytes per ask; length + engine validation instead. | A corrupt-but-right-length report. |

## 6. Functional readiness (what the user can do)

| Journey | Test |
|---|---|
| A. Fresh: no model → setup card → models screen → install → select → back → AI usable | `aFreshAiModeShowsTheSetupCardNotAnInputAndAfterInstallAndSelectTheAiIsUsable` |
| B. Search only, no model | `searchWorksWithNoModelAtAll` |
| C. AI SEARCH 「昨日の日記を探して」 | `theChatV1JourneyOnAnInstalledModel…` |
| D. AI OPEN unique | same |
| E. Case C APPEND with the target dropped → assist → preview → cancel | same |
| F. CREATE → preview → confirm → exactly one document | same |
| G. APPEND → preview → confirm → exactly one append | same |
| H. Conflict → no write → new preview | `aConflictOffersANewPreviewAndNeverRerunsTheOldTicket` |
| I. Ambiguous → choose a candidate | journey test |
| J. Thermal blocked → no generation → recovers | `aHotDeviceRefusesTheGenerationWithTheUnifiedWordingAndRecoversWhenCool` |
| K. Offline + installed model → AI works | `offlineWithAnInstalledSelectedModelTheAiStillWorks` |
| L. Offline + no model → no download, search works | `offlineWithNoModelTheDownloadDoesNotStartAndSearchWorks` |
| M. Delete selected → unload → selection cleared → setup card → search works | `deletingTheSelectedModelReturnsTheChatToTheSetupCardAndSearchWorks` |

All on a **release-shaped path**: the product selection over the real store and the real preference
(`TestAiSelection.useProduct`, `isDebugBuild = false`, no developer file), the real manager over the
loopback fixture, the scripted runtime standing in for the model.

## 7. AI safety (unchanged, re-pinned)

LLM → IntentProposal → SemanticValidator → Resolver → ExecutionPolicy → CommandPreview → **Human
Confirmation** → ConfirmedCommand → CommandExecutor → DocumentAccess. One `confirm()` caller; one
`execute(` in the view model, reached only from the confirm button; one ticket = one write; a
conflict never retries; a retry is a new preview; no destructive verb; no history; nothing pending is
saved; Room 24 / Backup 18; prompt v1 and the grammar byte-identical. `ChatV1ReleasePolicyTest`,
`AiConfirmedWritePolicyTest`, `AiTargetResolutionPolicyTest`, `AiLoggingPolicyTest` all green.

## 8. Runtime compatibility and the ARM release decision

**Facts.** `libmemoripple_llm.so` is built with `-march=armv8.2-a+dotprod+fp16` (the S20's class, the
only class Phase 0 measured). `CpuFeatures.hasDotProduct()` reads `asimddp` from `/proc/cpuinfo`;
`productLocalModelRuntime` constructs the engine **only** behind it (constructing loads the library,
and a library compiled for dotprod may execute such an instruction in a static initialiser); the
manager's `supportsLocalInference()` reads the same gate before a byte moves; the orchestrator's
`availability()` reports `UNSUPPORTED_DEVICE` before an input is offered. There is no path that loads
the library on an unsupported CPU and no "fall back after a SIGILL" (`AiChatPreviewPolicyTest` pins the
one constructor site; `anUnsupportedCpuGetsARuntimeThatRefusesAndNeverBuildsTheEngine`).

**What an unsupported user gets.** The whole app; 検索 in チャット; the AI segment showing
「この端末ではLocal AIを利用できません」 with the CPU reason and no settings shortcut; the models screen
with its banner and every download control disabled; no download, no crash, no partial file.

**Who is unsupported.** minSdk 29 arm64 devices without the dot-product extension: ARMv8.0 designs
(Cortex-A53 / A72 / A73 only — e.g. Snapdragon 4xx / 6xx of 2016–2018, Helio P2x / A2x, Exynos 7870
class) that were updated to Android 10. Everything ARMv8.2 and later (Cortex-A55 / A75 onward, i.e. every
mainstream SoC since 2018, including the S20's Exynos 990 and Snapdragon 865) has it. The emulator's
arm64 image reports it too.

**Options.**
- **A — baseline ARMv8.0 build.** One library everyone can run; loses the dotprod / fp16 kernels on
  every device, including the ones Phase 0 measured (the 4B Q4_K_M token rates, the thermal windows).
  Would need a full Phase 0 re-measurement before any of its numbers could be trusted. Not for v1.
- **B — dual native runtime (baseline + dotprod, chosen at load).** The right long-term shape; a
  native build and packaging change (+ ≈ 8 MB), its own round with both libraries measured. Deferred.
- **C — feature-gated AI (what exists).** The app ships to every minSdk 29 device; Local AI is a
  feature the device either has or is told it does not have. Honest, tested, and the measured path is
  the shipped path.

**Analysis / recommendation: publish Chat v1 with C.** The unsupported set is old and small, the
degradation is a clear message and a fully working app, and no measured number changes. B stays the
follow-up if Play's device catalog or a report shows real demand. This is a recommendation for the
human's decision, not a decision.

## 9. Thermal and memory

- `thermal.check()` runs before `availability()` and before any load; `ThermalDecision.BLOCKED` at
  status ≥ 3 (SEVERE) refuses the generation (`ThermalBlocked`, no load, no request). The generator
  checks again before generating. A **confirmed write consults no gate** (`execute` has no thermal
  path — policy-pinned): a previewed write may be confirmed on a hot device, as Phase 4 decided.
- LOW pressure is ignored while an ask is in flight and unloads an idle model; CRITICAL unloads at
  once (Phase 3 amendment, `AiOrchestratorTest`). Search never touches the model. The next ask
  reloads (`created` stays, the state is UNLOADED → load). Two-minute idle unload; release on leaving
  the AI mode.

## 10. Offline

Fixed rule: an installed, selected model works with no network (K); a download never starts offline
and nothing is asked (L); search and every document operation are local. The online read happens
only at the download decision.

## 11. Privacy and logging

Production logs (the `AiChat` tag in the view model and the application; the orchestrator's notes)
carry: a state / phase, an error category (`LOAD` / `GENERATION` / `PARSE`, an exception class name,
a runtime failure enum), the assist source enum with `assistApplied` and `candidateLength`, a byte
count, a trim level, a model id. Never: user input, a title, a target name or candidate text, append
text, a document body, raw model output, a `DocumentRef` or a database id. `AiLoggingPolicyTest`
scans every `Log.` / `onNote(` line under `domain/ai`, `data/ai`, `ui/chat`, `ui/settings` and the
application for those sources; nothing new in Phase 7 logs anything. R8 does not strip `Log` calls
(no `-assumenosideeffects` rule, by design — the guarantee is the redaction, not the build type).

## 12. Permissions audit

| Permission | Level | Why it exists | Relation to Chat v1 |
|---|---|---|---|
| `INTERNET` | normal | Google Drive backup (optional); since Phase 5 the user-initiated model download from the pinned Hugging Face URL. | The download only. The ask path never uses the network. |
| `ACCESS_NETWORK_STATE` | normal | Phase 5 (human-approved, single purpose): the network state read before a model download — metered → confirmation; Phase 7 at the same point: no network → no download. Never a callback or receiver. | The download decision only. |
| `SYSTEM_ALERT_WINDOW` | special access (user-enabled) | Overlay comment playback. | None. |
| `FOREGROUND_SERVICE` | normal | Overlay playback + background TTS. | None (no download service; a download runs in the app process while the app lives — Phase 5 decision). |
| `FOREGROUND_SERVICE_SHORT_SERVICE` | normal | Overlay playback type. | None. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | normal | Background TTS type. | None. |
| `POST_NOTIFICATIONS` | runtime (Android 13+) | Overlay / TTS status and stop. | None (no download notification). |

Merged release manifest: these seven plus `androidx.core`'s package-local
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature). No new permission in Phase 7
(`AiModelPolicyTest` pins the set). The Data Safety inventory (internal) updated to say so.

## 13. Packaging (release variant)

`:app:assembleRelease :app:lintRelease` on `95d80ab` (2026-09-20 19:18 JST; the upload
signing secrets are not in this shell, so the artifact is the standing **unsigned** release — the
validated path of the internal release notes): **`app-release-unsigned.apk` 32,615,383 B**
(SHA-256 `493a1864…`; the debug APK is 108,636,377 B), `versionCode 4 / 1.1.0`, `targetSdk 36`,
`native-code arm64-v8a` only. Inside: **0 `.gguf`**; native libraries `libmemoripple_llm.so`,
`libc++_shared.so` and the two AndroidX ones (`libandroidx.graphics.path.so`,
`libdatastore_shared_counter.so`) — the production runtime only; `assets/ai/intent_system.v1.txt`
and `assets/ai/intent_proposal.gbnf` (prompt v1 and the grammar, byte-identical to main). The
release manifest carries the seven platform permissions plus `androidx.core`'s package-local
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, **no `networkSecurityConfig`**, **no `debuggable`**;
`res/xml/network_security_config.xml` is absent from the release APK (debug overlay only). The
release DEX contains **no** `developer.properties`, `llmModelPath`, `/data/local/tmp`, `llmbench`,
`TestModelServer` or `allowLoopbackHttp` string; `huggingface.co` appears 4× (the two pinned catalog
URLs and repositories — the product's one download endpoint); `127.0.0.1` / `localhost` appear once
each — the `LOOPBACK_HOSTS` constant behind the `allowLoopbackHttp` flag, an allowance list the
product never enables, not an endpoint. `lintRelease` 0 errors / 35 warnings / 1 hint;
`:app:verifyReleaseOssInventory` passes (no dependency drift). `:llmbench` is not configured
(`-PllmBench` absent). Nothing here was installed on a device; the release APK is unsigned by
contract.


## 14. Known flakes

- **`PortableExportInstrumentationTest.theArchiveAlsoReadsInABrowser`** (new in the Phase 6 main gate,
  full suite only, 3 / 3 alone). Classified: **shared-fixture race with the app's own attachment
  garbage collection**, not a product bug. The seed wrote the photo blob file *inside* its Room
  transaction, before its rows were visible; the application's `attachmentObserver` (fired by the
  relation tables' invalidation — `clearAllTables()` in `@Before` fires it) launches
  `AttachmentRepository.garbageCollect()` on the application scope, whose file pass lists the blob
  directory against `allBlobShas()` read on **another connection**, i.e. the committed rows; a file
  with no visible row is swept. The export then treats the photo as missing (by design "a single bad
  photo cannot sink the export") and the memo HTML has no `photos/photo-01.jpg` link — exactly the
  observed failure at line 476. Only the full suite provides the timing. Fix: the fixture writes the
  file **after** the transaction commits; the sweep then sees a referenced blob whichever way it
  interleaves. No assertion changed, no product change.
- **`MainActivityNavigationTest.memoOrganizationControlsComposeAndSurviveConfiguration`**
  (`dismissDisplayOptions`, a 5 s wait on a sheet-dismiss animation under full-suite load): the
  documented Compose test-host family; stays known; 3 / 3 alone in every gate.

## 15. S20 final smoke

2026-09-20 19:21–19:23 JST, S20, Qwen3-4B-Instruct-2507 at
`/data/local/tmp/llmbench/models/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf` through the debug
developer file, USB, thermal 0 before and after, the `95d80ab` debug build installed **in place**
(no uninstall, no clear; the five Phase 0 GGUFs untouched): **`OK (1 test)`**. The AI mode opened on
its input (no setup card — the model is there); 「昨日の日記を探して」 → `chat_ai_search_results` in
**35.8 s** (load + generation; a LOW trim during GENERATING was ignored by the Phase 3 rule on the
first attempt); case C on the test's own temporary memo 「MemoRipple開発」 → `chat_ai_preview_append`
in **33.4 s** with the redacted note `target assist: assistApplied=true source=BEFORE_PARTICLE_NI
candidateLength=12 intent=APPEND`, cancelled, the memo unchanged; 「Chat v1 smoke <nonce>というメモを
作って」 → `chat_ai_preview_create` in **34.2 s**, nothing written by the preview, one tap → exactly one
memo, opened, then 「作成しました」; leaving for 検索 → runtime **UNLOADED**, the search found the
temporary memo; the Local AIモデル screen listed both models 未ダウンロード (the developer file is not
an install, as designed). Every document the test made was removed; the snapshot of everything else
was identical. Two earlier attempts of the same run failed for test-host reasons, not the product:
the S20 was dozing (no composed hierarchy — woken, rerun) and the smoke tried to 閉じる a result list
that has no such button (fixed in the test).


## 16. Remaining before a public release (human-owned)

1. **Public Privacy Policy text** (`https://crag-kakao.github.io/MemoRipple-Privacy/`, a separate
   repository): must describe the optional, user-initiated model download from Hugging Face (no user
   data is sent; the file is stored on the device, excluded from backup) and the `ACCESS_NETWORK_STATE`
   purpose. The in-repo inventory now says it; the public page is the human's.
2. **Signed release build** (the `MEMORIPPLE_UPLOAD_*` shell) and the Play Data Safety answers from the
   updated inventory.
3. **Version** — not bumped in Phase 7 (human decision); to be chosen once readiness is green.

## 17. Tests added in Phase 7

JVM: `ChatV1ReadinessOrchestratorTest` 7, `ModelSelectionReadinessTest` 7, `ModelManagerReadinessTest`
6, `HttpModelDownloaderReleaseTest` 2, `ChatV1ReleasePolicyTest` 6. Device:
`ChatV1ReleaseReadinessInstrumentationTest` 13, `ChatV1LayoutInstrumentationTest` 10; the Phase 3 / 5
no-model flows follow the setup card; the portable-export fixture reordered.

## 18. Gate

`gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
on `95d80ab` (2026-09-20 19:19–19:40 JST): unit **1192 / 0** (`LlmBenchIsolationPolicyTest` 4 / 4,
`ChatV1ReleasePolicyTest` 6 / 6, `AiLoggingPolicyTest` 3 / 3, `AiModelPolicyTest` 7 / 7,
`AiTargetResolutionPolicyTest` 4 / 4 — prompt v1 and the grammar byte-identical, `AiConfirmedWritePolicyTest`
5 / 5, `AiChatPreviewPolicyTest` 7 / 7, `ChatV1ReadinessOrchestratorTest` 7 / 7,
`ModelSelectionReadinessTest` 7 / 7, `ModelManagerReadinessTest` 6 / 6, `HttpModelDownloaderReleaseTest`
2 / 2); lintDebug **0 errors**, 34 warnings, 1 hint; app-debug.apk **108,636,377 B** (+65,536 B over
main's 108,570,841), androidTest APK 4,716,680 B; packaging `libmemoripple_llm.so` + `libc++_shared.so`
beside the two AndroidX libraries, **0 `.gguf`**; Room 24 / Backup 18 with 0 diff lines against
`42f5dfc`; no schema, no assets change. Device, the whole suite in **one process** on emulator-5554
(fresh install, `pm clear`, `am instrument`): **`OK (442 tests)`** — 432 finished, **10 assumption
skips** (the five known + the five device-only smokes without their arguments, `ChatV1S20SmokeTest`
among them), **0 failures**, no OOM, no crash; Java heap 24–69 MB over the run. In the run:
`ChatV1ReleaseReadinessInstrumentationTest` 13 / 13 (journeys A–M), `ChatV1LayoutInstrumentationTest`
9 / 9, `AiChatPreviewInstrumentationTest` 12 / 12, `AiConfirmedWriteInstrumentationTest` 13 / 13,
`AiModelManagementInstrumentationTest` 8 / 8, `AiTargetResolutionInstrumentationTest` 5 / 5,
`ChatFoundationInstrumentationTest` 8 / 8 (検索 v0), `ChatViewModelState` / `ConfirmedWrite` 4 / 4,
`PortableExportInstrumentationTest` 12 / 12 (the reordered fixture: the browser-archive test green in
the full suite), the three `ComposeRuleRegistryTripwireTest` green, `MainActivityNavigationTest`
green including `memoOrganizationControlsComposeAndSurviveConfiguration` this time. No model file
left in the emulator app. Release variant inspection above; S20 smoke above.
