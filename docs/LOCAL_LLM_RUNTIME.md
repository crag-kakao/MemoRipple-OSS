# Local LLM Phase 2 — Runtime Adapter / Structured Inference

Branch `feature/local-llm-runtime-adapter` (from main `337f9a0`), 2026-09-19/20. The two Phase 1
candidates' runtimes are connected **upstream** of the safe pipeline only:

```
user text → LocalModelRuntime → prompt (v1) → GBNF generation → raw JSON → IntentProposalParser
          → IntentProposal → SemanticValidator → Resolver   (Phase 2 reach ends here)
          → ExecutionPolicy → Preview / Confirmation → ConfirmedCommand → CommandExecutor → DocumentAccess   (Phase 1, not wired to a UI)
```

No Chat UI change, no DocumentAccess write from a model, no model resident, no download UI, no
model selector, no conversation history.

## 1. Audit before the build (2026-09-19)

- Phase 1 `domain/ai` is pure and its `Resolver` already refuses invalid proposals; the
  `AiResultContext.shownLines()` are label + title only — exactly what a prompt may carry.
- Phase 0's JNI (`llmbench/src/main/cpp/llmbench.cpp`) proved the path: mmap load, Jinja through
  `llama-common` with `enable_thinking=false`, grammar → greedy chain, a per-token stop flag; the
  product needs none of the bench's runner / metrics / workloads. The llama.cpp pin is the
  submodule under `llmbench/` (**b11039**, `4fea119de30f6a923992780f6fd5ccb0bee5d47d`).
- Prompt v1 and the IntentProposal GBNF live in `tools/llm-eval/`; both models' chat templates
  come from their GGUFs (ChatML; `[SYSTEM_PROMPT]…[INST]…[/INST]`); the only per-model knob so far
  is the thinking flag (Qwen3-4B-2507 and Ministral 3 3B have none to switch; the flag is still
  passed as false).
- The app had no native code, no NDK / CMake config, no `assets/ai`; `MemoRippleApplication`
  wires repositories lazily and had no `onTrimMemory`; nobody used `PowerManager`;
  `allowBackup=false` with explicit rules; `noBackupFilesDir` unused.
- Phase 0's isolation policy forbade any native code in `:app`; Phase 2 revises it to "no
  `:llmbench` dependency, the product's own library only, no GGUF in the APK".
- **Risk recorded:** a `-march=armv8.2-a+dotprod+fp16` build is not executable on ARMv8.0 devices
  inside minSdk 29; the engine adapter reads `/proc/cpuinfo` and refuses to call the native code
  without `asimddp` (`RuntimeFailure.UNSUPPORTED_DEVICE`). A release decision (baseline build,
  dual build, or feature gating) is open — see §9.

## 2. Layering

```
domain/ai/runtime      LocalModelRuntime, ModelDescriptor, ModelLocation, RuntimeState, GenerationRequest/Result,
                       ThermalGate + StatusThermalGate, MemoryPressure, PromptAssets, IntentProposalParser
                       (RawIntentProposalDto, FieldLimits), StructuredIntentGenerator, LocalIntentPipeline
        ↑              knows no engine, vendor, file format, JNI or Android (AiRuntimeAdapterPolicyTest)
data/ai                NativeInferenceEngine (the engine surface), LocalModelRuntimeImpl (the state machine),
                       ModelProfile / ModelProfiles (Qwen3-4B-2507, Ministral 3 3B), LocalModelRuntimeHolder
                       (lazy, unload on low memory), androidThermalGate (PowerManager), AssetPromptAssets
        ↑              knows no DAO / Entity / Room / UI / route
data/ai/llamacpp       LlamaCppEngine (JNI to libmemoripple_llm), CpuFeatures
app/src/main/cpp       memoripple_llm.cpp + CMakeLists.txt → libmemoripple_llm.so (llama.cpp statically, from the submodule)
```

Another engine (a different backend, a cloud fallback) is another `NativeInferenceEngine` or
another `LocalModelRuntime`; the domain does not change.

## 3. LocalModelRuntime

`load(ModelDescriptor)`, `generate(GenerationRequest)`, `unload()`, `state()`. States: UNLOADED →
LOADING → READY ⇄ GENERATING → UNLOADING → UNLOADED, and FAILED. Rules (tested):

| situation | result |
|---|---|
| `load` while READY | `Failed(INVALID_STATE)` (unload first) |
| `load` while LOADING | `Failed(BUSY)` |
| `load` on a CPU without dotprod / a missing file | `UNSUPPORTED_DEVICE` / `MODEL_FILE_MISSING`, engine untouched |
| engine load failure | state FAILED; a later `load` may retry |
| `generate` unless READY | `Failed(INVALID_STATE)`; a second concurrent call `Failed(BUSY)` |
| `unload` while GENERATING | the engine is asked to stop; the generation returns CANCELLED; then unload |
| `unload` while UNLOADED | no-op |

`GenerationRequest` = system prompt + user message + grammar + `maxTokens` (default 256, ceiling
512): structured intents are short generations. Context 4096, batch 512, 4 threads, temperature 0
(greedy under the grammar) — Phase 0's conditions.

## 4. ModelDescriptor and ModelProfile

`ModelDescriptor(id, displayName, location, architecture, contextSize, quantization, capabilities)`
carries no vendor logic and no id of anything else. `ModelLocation.AppFile(fileName)` resolves
under `noBackupFilesDir/models/` (never backed up; a file name cannot climb out of that
directory); `ModelLocation.DeveloperPath` is the smoke's injection only.

`ModelProfiles` (data layer) hold the two candidates: `qwen3-4b-instruct-2507` (`qwen3`,
`Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf`) and `ministral-3-3b-instruct-2512` (`mistral3`,
`Ministral-3-3B-Instruct-2512-Q4_K_M.gguf`), both `enableThinking = false`. Templates, stop
tokens and tokenizer behaviour come from the GGUF through the engine's Jinja renderer. **No
default model is chosen.**

## 5. StructuredIntentGenerator and the parser

The generator refuses when the `ThermalGate` says BLOCKED or the runtime is not READY; otherwise
it sends prompt v1 as the system message and Phase 0's user message (`表示中の候補:` + the shown
lines or `(なし)` + `入力: …`) with the GBNF, and parses the answer:

raw JSON (≤ 8,000 chars) → `RawIntentProposalDto` (all strings) → `IntentProposal`. Rejections:
malformed JSON, not an object, oversized output, oversized field (query / name 200, text 2,000,
templateId 100, missingFields ≤ 8 × 40 chars), a malformed `result_N` (`42`, `memo:7`,
`result_0`, a number), a date where a token belongs (`2026-09-18`, `TOMORROW`). Not rejections:
an unknown intent word (→ UNKNOWN, incl. DELETE / UPDATE / MOVE / RENAME / ARCHIVE / RESTORE), an
unknown kind (→ null), an unknown missing-field name (dropped). Nothing throws on model output.

**GBNF valid ≠ semantic safe** stays the rule: a parsed proposal is only the *input* to
`SemanticValidator` → `Resolver`; `LocalIntentPipeline.propose()` returns the resolution and
stops. Writes resolve to a command and nothing executes (tested).

## 6. Thermal, lifecycle, memory

- `StatusThermalGate`: status 0 → ALLOWED, 1–2 → THROTTLED (allowed), ≥ 3 → BLOCKED (no new
  generation) — Phase 0's SEVERE rule, read from `PowerManager.currentThermalStatus`.
- Lifecycle: load → one or a few generations → idle → unload. The interface makes unload possible
  at any time (including mid-generation); an idle timer is not built yet.
- Memory: `MemoRippleApplication.onTrimMemory` maps the trim level to `MemoryPressure`
  (MODERATE 5–14, LOW 15–79, CRITICAL ≥ 80) and `LocalModelRuntimeHolder` unloads on LOW /
  CRITICAL; the holder creates the runtime lazily, so an app that never used AI never loads the
  library. No reload UX yet. **Phase 3 amendment (2026-09-20, docs/AI_CHAT_PREVIEW.md §9):** the
  S20 smoke showed that loading a 2.5 GB model raises the trim level by itself and the hook
  unloaded the model between load and generate; pressure now goes through `AiOrchestrator` when
  it exists — LOW waits for an ask in flight, CRITICAL unloads regardless — and the holder's rule
  applies only while nothing else owns the runtime. An idle timer (2 minutes) exists since Phase 3.
  **Phase 2 of the resource work (2026-09-23, docs/AI_RESOURCE_CONTROLLER.md):** the whole
  lifecycle — lazy load, keep warm, the idle clock, this memory policy, the power / thermal
  profile and the generation budget — now lives in the application-scoped
  `domain/ai/resource/AiResourceController`; the orchestrator forwards pressure to it, and only a
  genuine generation leases (and therefore loads or keeps) the model.

## 7. Native runtime and packaging

llama.cpp b11039 from the pinned submodule, compiled into `libmemoripple_llm.so`: CPU only,
`arm64-v8a` only, `GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF`, KleidiAI off,
`GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`, repack ON, `LLAMA_BUILD_COMMON=ON` (Jinja only),
static ggml + llama, `c++_shared`, NDK 29.0.14206865, CMake 3.31.6. No GGUF is bundled
(`AiRuntimeAdapterPolicyTest` reads the APK). APK size before / after: §9.

## 8. Storage

Model files: `noBackupFilesDir/models/<file>.gguf` (first candidate; excluded from backup by the
directory itself and by `allowBackup=false`). Phase 2 places no file: the smoke injects a
developer path to the Phase 0 GGUFs under `/data/local/tmp/llmbench/models/`, which is not
MemoRipple's data area and is never copied into it.

## 9. Measurements (filled at the end of the phase)

| item | value |
|---|---|
| debug APK before / after the native bridge | 99,466,640 B → 108,177,076 B (**+8.7 MB**) |
| native libraries in the APK | `libmemoripple_llm.so` 7,325,528 B (llama.cpp + ggml + common, static) and `libc++_shared.so` 1,374,336 B; the two AndroidX libs that were already there |
| `.gguf` entries in the APK | 0 |
| ABIs | arm64-v8a only |
| llama.cpp | b11039 (`4fea119de`), the submodule under `llmbench/` |

### S20 runtime smoke (2026-09-19 23:08 JST, the branch's debug build installed in place over the S20's debug MemoRipple; Room 24, data intact)

`LocalLlmRuntimeSmokeTest` with `-e llmModelPath /data/local/tmp/llmbench/models/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf`
(the Phase 0 file, not copied anywhere), thermal status 0, 27.7 °C at start:

| step | result |
|---|---|
| CPU feature gate | `asimddp` present → engine allowed |
| load (`LlamaCppEngine`, ctx 4096, 4 threads, mmap) | **7,483 ms**, template = Jinja |
| 「昨日の日記を探して」 → raw JSON (grammar-constrained) | ≈ 30 s wall (prompt processing dominates, as in Phase 0) |
| parsed `IntentProposal` | **SEARCH · documentKind JOURNAL · dateToken YESTERDAY** · `query = 昨日的日記` (the Phase 0 over-fill, with its mixed-script character — recorded, not corrected) |
| prompt version | v1 |
| unload | state UNLOADED |
| thermal after | 0, 28.1 °C |
| DocumentAccess | not called |

One command, one model (Qwen3-4B-2507). The Ministral profile went through the same interface
in unit tests only; its device smoke is left for the moment a second command is warranted.

### Open decision for release (recorded, not decided here)

The native build assumes ARMv8.2 dotprod + fp16. Devices in minSdk 29 without those extensions
exist; today the adapter answers `UNSUPPORTED_DEVICE` before touching the engine, and the app
otherwise runs normally. Before any release with the runtime enabled: a baseline `armv8-a` build
(≈ 2× slower prompt processing, §10.2 of `docs/LLM_PHASE0.md`), two libraries selected at runtime,
or a feature gate — the human's call.
