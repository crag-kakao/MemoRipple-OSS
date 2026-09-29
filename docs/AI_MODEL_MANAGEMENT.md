# AI Model Management — Local LLM Phase 5 (Model Management / User Model Choice)

> Branch `feature/ai-model-management` from `main` `d48f8c1`, 2026-09-20. The product UX for
> installing, verifying, selecting and deleting a Local AI model. The AI pipeline
> (`docs/AI_SAFE_INTENT_PIPELINE.md`), the runtime adapter (`docs/LOCAL_LLM_RUNTIME.md`), the
> chat preview (`docs/AI_CHAT_PREVIEW.md`) and the Human Confirmation boundary
> (`docs/AI_CONFIRMED_WRITE.md`) are unchanged; this round only decides *which file* the runtime
> is allowed to load, and how it gets there.

## 1. Audit (before RED)

| # | Looked at | Found |
|---|---|---|
| 1 | `ModelDescriptor` / `ModelProfiles` | Two profiles keyed by the raw GGUF file name under `models/<basename>`; no default. |
| 2 | `InstalledModelSelection` (Phase 3) | Picked the first candidate whose file exists — an implicit default, replaced. |
| 3 | `noBackupFilesDir/models` | The layout existed on paper only; nothing wrote there. `allowBackup="false"` and every backup domain excluded. |
| 4 | `DeveloperPath` | Debug-only through `models/developer.properties`; both device smokes use it. Kept, debug only. |
| 5–8 | `LocalModelRuntimeHolder`, model-id / file-name assumptions, CPU gate, unsupported behaviour | Lazy runtime behind `CpuFeatures.hasDotProduct()`; a refusing runtime on an unsupported CPU; nothing stopped a download first. |
| 9–10 | Settings architecture, DataStore | One settings screen of sections; `SettingsRepository` on Preferences DataStore (string / boolean keys). |
| 11 | Storage UI | None. |
| 12 | Network permission, HTTP client | INTERNET declared; the Drive code on `HttpURLConnection`; no OkHttp / Ktor. |
| 13–14 | Download code, WorkManager | None. |
| 15 | Notifications, foreground services | POST_NOTIFICATIONS and two services (shortService, mediaPlayback) exist for playback; a download worker would need the dataSync type and permission on Android 14. |
| 16 | Checksum | SHA-256 via `MessageDigest` in the attachment store. |
| 17–19 | Free space, metered network, battery | No helpers; `File.usableSpace`, `ConnectivityManager.isActiveNetworkMetered` (needs the normal `ACCESS_NETWORK_STATE` — the S20 smoke and lint both said so; declared with its reason) and `BatteryManager`. |
| 20 | Model deletion → runtime | `AiOrchestrator.release()` is the unload path. |
| 21 | Where the selector goes | A new settings section 「AI」 → 「Local AIモデル」 screen. |
| 22 | Backup exclusion | Already total (`allowBackup="false"` + rules). |
| 23 | ARM CPU feature decision | Open (A / B / C); Phase 5 reflects the gate in the UI only. |
| 24 | Release APK size | Needs the upload signing secrets → not built here; debug 108 MB with 0 model bytes. |
| 25 | Large models on Play | Never in the APK / AAB; the catalog ships facts, the file is downloaded on demand. |

**Model facts re-verified live on 2026-09-20** (not from memory): both repositories ungated;
revisions unchanged (`ae44f08e…`, `eb599d40…`); sizes and SHA-256 identical to Phase 0
(`x-linked-etag`); Apache 2.0 upstream and GGUF; the CDN answers byte-range requests.

## 2. Catalog

`CatalogEntry` (domain, `domain/ai/models/CatalogEntry.kt`): modelId, displayName, description,
category (Balanced / Safety-oriented), approximate download and loaded-memory bytes, strengths,
tradeoffs, source (repository, revision, fileName, pinned url, publisher, publisher note),
expectedSha256, quantization, minimumCpu (ARM_DOTPROD), contextSize, license (summary + url),
runtimeProfileId. The two entries live in the **data layer** (`data/ai/models/ModelCatalog.kt`)
because they name vendors and files, which the Phase 2 rule keeps out of `domain/ai`; the domain
sees values only. Static and bundled; no catalog server; only the model file is remote.

| | Qwen3-4B-Instruct-2507 | Ministral 3 3B Instruct 2512 |
|---|---|---|
| Category | Balanced | Safety-oriented |
| Description | 指示の理解と、依頼に必要な情報の抽出を重視した候補。 | 慎重な判断と、余計な補完の少なさを重視した候補。 |
| File | `bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF` @ `ae44f08e…` / `…Q4_K_M.gguf` | `mistralai/Ministral-3-3B-Instruct-2512-GGUF` @ `eb599d40…` / `…Q4_K_M.gguf` |
| Download / loaded | 2,497,280,736 B / ≈ 5.6 GB | 2,147,023,008 B / ≈ 4.75 GB |
| SHA-256 | `2fde00ce…4464e` | `9ed150d4…fc5f8` |
| Licence | Apache 2.0 (upstream and GGUF) | Apache 2.0 (upstream and GGUF) |

Descriptions and strengths / tradeoffs follow the Phase 0 measurements (intent 95 % / complete
89 % / memory high; 0 major failures / executable 75 % / lighter). **No ranking words, no
default** — a test reads every string.

## 3. Storage

`FileModelStore(noBackupFilesDir)`: `models/<modelId>/model.gguf`, written as `model.gguf.part`
during a download. The id is validated (`[a-z0-9-]{1,64}`, `ModelFileResolver.safeId`) so only a
catalog id can name a directory — no path traversal. The runtime's `ModelLocation.Installed(id)`
resolves to the same file; the file name is the data layer's (`FileModelStore.MODEL_FILE`), never
a word in the domain. Everything under the no-backup root is outside every backup.

## 4. Download, checksum, partial files, interruption

`ModelManager.requestDownload(id, allowMetered)`:

```
unknown → AlreadyInstalled → AlreadyDownloading / AnotherDownloadRunning (one at a time)
→ UnsupportedDevice (CPU gate, before any byte)
→ InsufficientStorage (remaining bytes + 512 MiB margin > usable)
→ LowBattery (< 15 % and not charging)
→ NeedsMeteredConfirmation (metered and not yet allowed)
→ Started: Checking → Downloading(bytes / total) → Verifying → Installed | Failed
```

- The `.part` is the only thing written during a download; `HttpModelDownloader` streams over
  `HttpURLConnection` (HTTPS only; loopback HTTP for tests), 256 KiB chunks, cancellation checked
  between chunks, a short body reported as a failure, the sink owned by the caller.
- **Resume:** a part on disk means `Range: bytes=<len>-` on the next request and a 206 is
  required; a server that ignores the range is a failure, not a corrupted resume. Process death,
  app exit and a dropped network leave the part; the screen shows 「ダウンロード途中」 with 再開.
  Nothing resumes by itself.
- **Checksum:** `verifyAndCommit` hashes the whole part and only on a match renames it atomically
  over `model.gguf`; a mismatch removes the part and reports `CHECKSUM_MISMATCH` — never Installed,
  never loadable, the screen says 「ファイルの検証に失敗したため、削除しました」 with 再試行.
- **Cancel:** stops the job; the part stays (resumable); nothing installs; the selection is
  untouched. **Retry** is the user's tap; there is no automatic retry.
- **Background:** an application-scoped coroutine, not WorkManager — no new dependency, no
  dataSync foreground service, no notification (the brief forbids an unneeded permission). The
  download survives the Activity but not the process; the part and the resume make that safe.

## 5. Guards

Free space counts the remaining bytes plus a 512 MiB margin (rename, filesystem, the app's own
needs). A metered network asks 「モバイルデータでダウンロードしますか？」 with the size before a byte
moves; nothing multi-GB starts silently. Below 15 % battery without a charger the download is
not started (charging is not required). An unsupported CPU shows 「この端末では現在Local AIを利用でき
ません」 and disables every download button — the "download 2 GB, then learn the CPU cannot run it"
path does not exist. The ARM release decision (A / B / C) stays open.

## 6. Selection

`installed ≠ selected`. The preference is one key, `ai_selected_model_id`, holding a catalog id
or nothing — never a path, url or hash (policy-tested). `select(id)` requires an installed model,
**unloads the runtime, persists the id and loads nothing**; the next AI ask loads the new model.
Two installed, one selected. No unilateral default: with nothing selected the screen says
「使うモデルを一つ選択してください」 and チャット's AI mode says 「AIモデルがまだ利用できません — 使うモデルが
選択されていません」 with 「設定を開く」; the search mode keeps working.

`ProductModelSelection` (the runtime's view): the selected id, only if installed
(`models/<id>/model.gguf`); in a **debug** build `models/developer.properties` stays
authoritative (the device smokes); a release build never reads it.

## 7. Deletion

Confirmed by a dialog (「メモや日記は削除されません」). Order: if the model is selected → unload the
runtime → clear the selection → remove the model's directory (file, part, anything
model-specific). A model being downloaded is not deleted under the download. Nothing else on the
device is touched — the manager knows no document type (test).

## 8. Screen

Settings → 「AI」 → 「Local AIモデル」 (`ai-models`): an intro, an unsupported-CPU banner when it
applies, one card per entry (name, category chip, description, strengths / tradeoffs, download
and loaded-memory size, licence, publisher note, status, progress bar while downloading, and the
verbs: ダウンロード / 再開 / 再試行 / キャンセル, 削除, 使用する, a 「使用中」 chip). Messages (space,
battery, one-at-a-time) are a dismissible banner. No ranking, no default, no file or engine
word on the screen (policy-tested).

## 9. Runtime and チャット

Selecting or deleting unloads; nothing loads until an AI ask. The Phase 3 / 4 paths are untouched:
the orchestrator asks `ModelSelection.selected()` (now `suspend`) at each ask and gets the
selected installed model or nothing. The chat's unavailable card distinguishes "not selected",
"selected but not installed" and "unsupported CPU", and opens the settings for the first two.

## 10. Persistence and boundaries

No Room table (24 unchanged), no new DataStore, no WorkManager, one normal permission added (`ACCESS_NETWORK_STATE`, for the metered check — the S20 smoke found `isActiveNetworkMetered` throws without it; the guard treats a refused answer as metered); the developer
file never reaches a release UI; `domain/ai/models` imports no platform; the manager never
downloads, selects or loads by itself (init and refresh are read-only). The pipeline and the
`confirm()` boundary are byte-for-byte the Phase 4 ones (`AiModelPolicyTest`).

## 11. Tests

JVM: `ModelCatalogTest` 6, `FileModelStoreTest` 10, `HttpModelDownloaderTest` 8 (a loopback fixture
server with range, cut, status, stall), `ModelManagerTest` 20, `AiModelPolicyTest` 7,
`ModelSelectionTest` 8 (rewritten), Phase 2 assertions updated (installed layout, catalog facts
in the data layer). Device (emulator, the real manager / store / downloader / DataStore over a
loopback fixture server with ~1 MB "models", guards under test control): `AiModelManagementInstrumentationTest`
8 — the settings row and the neutral list; metered confirmation → progress → verification →
install → explicit select; cancel keeps the part and resume finishes with a range; a corrupt
body is rejected and retryable; storage / battery / CPU guards; delete of the selected model
(dialog, unload, deselect, files gone, documents untouched); two installed, one selected,
switching unloads without loading; チャット's unavailable card → settings, search still works.
No test downloads a real model.

## 12. Device verification

- **S20, regression:** the Phase 3 / 4 smokes (developer file) still pass with the new selection.
- **S20, one real end-to-end (`AiModelDownloadSmokeTest`, `-e realDownload=true`, Wi-Fi, 77 GB free):**
  the product screen downloads Ministral 3 3B (2.15 GB) from the pinned URL into
  `models/ministral-3-3b-instruct-2512/model.gguf`, the SHA-256 matches, the user-style select
  runs one AI ask through the runtime **from the installed file** (no developer path), and the
  model is deleted through the manager afterwards. The Phase 0 GGUFs under `/data/local/tmp`
  are never touched. Result: HANDOFF §16.55 and below.

**Results (2026-09-20, S20, Wi-Fi, USB, thermal 0 before and after, branch debug
build installed in place, versionName 1.1.0, data intact):**

- **Phase 3 chat smoke (developer file) with the new selection:** `OK (1 test)` — ask 1 search
  results in 36.4 s, case C NeedsInformation in 33.2 s, UNKNOWN in 31.4 s, unloaded on leaving;
  the developer file still takes precedence in a debug build, as designed.
- **Real end-to-end (`AiModelDownloadSmokeTest -e realDownload true`):** `OK (1 test)`. The first
  attempt failed before any byte with `SecurityException … ACCESS_NETWORK_STATE` (§5, fixed);
  the second: Ministral 3 3B **2,147,023,008 B downloaded in 326 s** (≈ 6.6 MB/s over Wi-Fi, one
  progress line per 10 %), `Verifying` → **`Installed(2147023008)`** (SHA-256 matched), no `.part`
  left; `select` persisted the id with the runtime still UNLOADED; one ask 「昨日の日記を探して」
  through the orchestrator **loaded the model from `models/ministral-3-3b-instruct-2512/model.gguf`**
  and answered `SearchResults` in 31.8 s (load + generation); `delete` unloaded, cleared the
  selection and removed the directory; the five Phase 0 GGUFs under `/data/local/tmp` untouched;
  memo and journal counts unchanged; thermal 0 → 0.

## 13. Not in Phase 5

Conversation history, AI draft persistence, background autonomous agent, destructive intents,
Future Diary AI integration, semantic RAG / vector DB, a catalog server, the ARM release
decision, a product default model.

## 14. Phase 7 amendments (docs/CHAT_V1_RELEASE_READINESS.md)

- **Offline**: `DeviceGuards.isOnline()` is read at the download decision, after the CPU gate and
  before the metered question; no network → `DownloadRequest.Offline`, nothing starts, nothing is
  asked, the part (if any) stays. An installed model needs no network to be selected or used. The
  read is a one-shot `ConnectivityManager` capability check under the same `ACCESS_NETWORK_STATE`;
  never a callback or receiver.
- **Corrupt file**: an installed `model.gguf` whose length is not the catalog's verified byte count
  is `Failed(CORRUPT_FILE)` — never `Installed`, never selectable; 再試行 removes it and downloads
  afresh (no resume of a corrupt file). The selection, if it named that model, stays the user's; the
  chat shows 「選択したAIモデルのファイルが壊れています」 until they act.
- **Screen**: refreshes from the disk on entry; a state chip beside the category chip; `selected` and
  `stateDescription` semantics; the header and the action row wrap (§DESIGN_SYSTEM).
- **Downloader**: HTTPS only in the product; loopback cleartext is a test-time opt-in
  (`allowLoopbackHttp`), never set by the application.
