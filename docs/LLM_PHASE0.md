# Local LLM Phase 0 — Model Evaluation (plan 2026-09-18 → final report 2026-09-19, §15)

Branch `feature/llm-phase0`, base main `e323000`. **Nothing here ships:** the チャット tab is not
wired to a model, `DocumentAccess` is not called by a model, no memo or journal is written, no
download UI, no model manager, no JNI linked into `:app`. Room 24 / Backup 18 / vc4 1.1.0 and
the release dependency graph do not change. This document holds the plan first and the
measured results later (§9 onward), facts side by side, no winner declared up front.

## 1. What is measured

Not general chat quality. The narrow, structured work MemoRipple needs — intent classification
(SEARCH / OPEN / CREATE / APPEND / USE_TEMPLATE / UNKNOWN), parameter extraction, date *tokens*
(never dates), template field extraction against a declared schema, schema-conforming output
under constrained decoding, Japanese, and hallucination resistance (diary mode above all) — plus
on the S20: model load time, TTFT, tokens/s, peak Java / native / RSS, battery delta, thermal
status, crashes, and the load → run → idle → unload → reload lifecycle.

## 2. Candidate models (verified against the official model cards on 2026-09-18)

| # | Model | Params | License | GGUF | llama.cpp | Notes |
|---|---|---|---|---|---|---|
| G2 | **Gemma 4 E2B-it** (Google, 2026) | 2.3B effective / 5.1B with embeddings | **Apache 2.0** (Gemma 4 is the first Gemma generation under Apache 2.0; commercial use and redistribution allowed; Google's prohibited-use policy still applies) | community quants at launch (unsloth `gemma-4-E2B-it-GGUF`, bartowski); Q4_K_M ≈ 1.5 GB | supported (Gemma 4 landed in llama.cpp at release; check the exact build) | 128K ctx; text + image + audio (text only used here); 140+ pre-training languages incl. Japanese; `<\|think\|>` control tokens — thinking **off** for the benchmark |
| M3 | **Ministral 3 3B Instruct 2512** (Mistral, 2025-12-02) | 3.4B LM (+0.4B vision encoder, unused) | **Apache 2.0** | community quants (bartowski b7229, lmstudio-community b7231); no official GGUF | supported from llama.cpp b7229+ | 256K ctx; Japanese listed among supported languages; mistral tokenizer — a GGUF from a recent converter is required (template caveats to verify on device) |
| Q4 | **Qwen3-4B-Instruct-2507** (Alibaba, 2025) | 4.0B (3.6B non-embedding) | **Apache 2.0** | **official**: `ggml-org/Qwen3-4B-Instruct-2507-Q8_0-GGUF`, plus Qwen/community Q4 quants | supported | 262K ctx; **non-thinking only** (no `<think>` blocks — the cleanest fit for constrained output); Japanese in Qwen3's 119 languages |
| Q35 (alt) | Qwen3.5-4B (2026-02) | 4B | Apache 2.0 | community quants; `ggml-org` publishes Qwen3.5 GGUFs (0.8B seen) | supported for the family | thinking **on by default** → must run with thinking disabled; newer architecture — swapped in for Q4 only if its GGUF + llama.cpp build are confirmed stable on arm64 |
| G4 (reference) | Gemma 4 E4B-it | 4.5B effective / 8B with embeddings | Apache 2.0 | community quants | supported | Q4 file ≈ 4–5 GB: the heaviest of the set; measured **only as a ceiling reference** if RAM allows (S20 has 10.9 GB total, ≈ 4.9 GB available at idle) |

Not candidates: anything 8B-class (out by the brief); Llama 3.2 3B (Llama license, not
Apache); Phi-4-mini / SmolLM3 / LFM2 (kept as fallbacks if a primary candidate fails to load —
Phi-4-mini MIT, SmolLM3 Apache 2.0, LFM2 under Liquid's own license).

The primary comparison set is therefore **G2, M3, Q4** (three), with G4 as an optional fourth.
Exact checkpoints (repository, file name, SHA-256, size, quantization) are recorded in §3 at
download time, never from memory.

## 3. Checkpoints and quantization (filled in at download)

Baseline quantization **Q4_K_M** for every model (the brief's Q4 family; the most common and
best-supported K-quant on arm64 CPU). If a model's publisher recommends another format for
quality (e.g. Q4_K_XL / UD quants), the reason is noted here and the baseline stays Q4_K_M so
the comparison is fair; a second run at the recommended quant is a separate row.

| Model | Repository / file | Quant | Size | SHA-256 | llama.cpp build |
|---|---|---|---|---|---|
| Gemma 4 E2B-it (**community conversion**, not a Google GGUF) | `bartowski/google_gemma-4-E2B-it-GGUF` · `google_gemma-4-E2B-it-Q4_K_M.gguf` · revision `81012ba3538e061d5ee003f11f25335b17f82e2d` | Q4_K_M (imatrix) | **3,462,680,032 B (3.46 GB)** | `923c4c86177d2ee173a7f5b4fa3d0ac65f5962ab15e6d6a5bc250aec4fd7bf7e` | quantized with b8746; run on b11039 |
| Ministral 3 3B Instruct 2512 (**official Mistral AI GGUF**) | `mistralai/Ministral-3-3B-Instruct-2512-GGUF` · `Ministral-3-3B-Instruct-2512-Q4_K_M.gguf` · revision `eb599d408350ea2bb60452cb86be7c7b2fc28227` | Q4_K_M | **2,147,023,008 B (2.15 GB)** | `9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8` | run on b11039 |
| Qwen3-4B-Instruct-2507 (**community conversion**: bartowski; Qwen publishes no GGUF of the 2507 Instruct checkpoint) | `bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF` · `Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf` · revision `ae44f08e1392f39c0e474af10c3ff8355c8b6688` | Q4_K_M (imatrix) | **2,497,280,736 B (2.50 GB)** | `2fde00ce69dd4899c70d020845e2638353015bba0fdf161b3eb965f2bca4464e` | quantized with b6096; run on b11039 |
| Qwen3.5-4B — **challenger** (community conversion: bartowski; Qwen publishes no GGUF) | `bartowski/Qwen_Qwen3.5-4B-GGUF` · `Qwen_Qwen3.5-4B-Q4_K_M.gguf` · revision `4168f45a16a1290d65a4ec0fa312ae917a4c15d6` | Q4_K_M (imatrix) | **3,013,027,808 B (3.01 GB)** | `13c16f426047e2de38cd075bdade4a7bcbc8c774384876f677740cda65f8a983` | quantized with b9222; run on b11039 |
| Gemma 3 1B Instruct — **ultra-light candidate** (conversion by **ggml-org**, the llama.cpp maintainers; not a Google GGUF) | `ggml-org/gemma-3-1b-it-GGUF` · `gemma-3-1b-it-Q4_K_M.gguf` · revision `f9c28bcd85737ffc5aef028638d3341d49869c27` | Q4_K_M | **806,058,240 B (0.81 GB)** | `8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135` | run on b11039 |

Gemma 4 E2B-it record (2026-09-19, before download; verified after):

- **Upstream model:** `google/gemma-4-E2B-it` (Google DeepMind), licence **Apache 2.0**
  (`license_link` https://ai.google.dev/gemma/docs/gemma_4_license on every card). The GGUF
  repo carries the same licence tag. **Publisher of the GGUF: bartowski** (imatrix quantization,
  llama.cpp release b8746, "Original model: google/gemma-4-E2B-it"). This is a community
  conversion; Google publishes an official GGUF only as **QAT q4_0**
  (`google/gemma-4-E2B-it-qat-q4_0-gguf`, `gemma-4-E2B_q4_0-it.gguf`, 3,349,516,256 B,
  SHA-256 `fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634`, revision
  `675cff42a74c774d6cb76f76d8eacb49b48c9b93`) — a candidate for a second row later, not the
  Q4_K_M baseline. Other community Q4_K_M: `unsloth/gemma-4-E2B-it-GGUF` 3,106,738,272 B
  (SHA-256 `740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8`, revision
  `0314792d7f1f7e229411f620751375812bb9faf2`; smaller because its embedding / output weights
  are quantized lower).
- **Size fact:** "E2B" is 2.3 B *effective* parameters but **5.1 B with embeddings** (Per-Layer
  Embeddings; model card). The Q4_K_M file is therefore **3.46 GB — larger than a plain 3 B
  Q4_K_M** would be and comparable to a 4 B one; "2B" does not mean lightest. Recorded as the
  fact it is; the S20 numbers (§10) decide.
- **Hashes verified locally:** `shasum -a 256` of the downloaded file = the repo's LFS oid above;
  size matches byte for byte. Local copy `~/llm-models/gemma4/`; on the S20
  `/data/local/tmp/llmbench/models/` (never MemoRipple's storage).
- **Chat template / thinking:** the GGUF carries Google's "Gemma 4 Canonical Chat Template"
  (Jinja, 18,569 chars, published 2026-07-09). Turns are `<|turn>role\n…<turn|>\n`, generation
  prompt `<|turn>model\n`; thinking is `enable_thinking | default(false)` and, when on, injects
  `<|think|>` at the top of the first system turn and a `<|channel>thought … <channel|>` block.
  llama.cpp's legacy `llama_chat_apply_template` has **no Gemma 4 branch** (it matches
  `<start_of_turn>`), so the bench renders prompts through llama-common's Jinja engine
  (`common_chat_templates_apply`, `use_jinja=true`, **`enable_thinking=false`**) — the same path
  for every candidate; the rendered prompt of the first case is stored in each run's metrics as
  `promptSample` so the absence of `<|think|>` / `<|channel>` is on record.

Ministral 3 3B Instruct 2512 record (2026-09-19, before download; hashes verified after):

- **Upstream model:** `mistralai/Ministral-3-3B-Instruct-2512` (Mistral AI), **3.4 B language model +
  0.4 B vision encoder**, 256 k context, multilingual incl. Japanese, instruct post-trained (not the
  Reasoning variant: no thinking to switch off). Licence **Apache 2.0**.
- **GGUF publisher: Mistral AI itself** (`mistralai/Ministral-3-3B-Instruct-2512-GGUF`, not gated):
  the text model in BF16 / Q8_0 / Q4_K_M plus a separate BF16 `mmproj` for vision, which the bench
  does not load (text only). The card states no converter or llama.cpp version requirement; the
  pinned b11039 lists the `mistral3` architecture and the `tekken` pre-tokenizer. Community
  alternatives at the same quant, recorded for the file-size fact only: bartowski (imatrix)
  2,146,498,528 B, unsloth 2,146,497,824 B (and UD-Q4_K_XL 2,191,963,424 B).
- **Size fact:** 2.15 GB at Q4_K_M for 3.4 B language parameters — 62 % of the Gemma E2B file.
- **Chat template / thinking:** read from the GGUF after download (§9.2 records what the Jinja
  path rendered); the Instruct model has no reasoning channel.

Qwen3-4B-Instruct-2507 record (2026-09-19, before download; hashes verified after):

- **Upstream checkpoint:** `Qwen/Qwen3-4B-Instruct-2507` (Qwen team, Alibaba), 4.0 B parameters,
  262,144 native context, **non-thinking only** — the card states it "does not generate
  `<think></think>` blocks" and that `enable_thinking=False` is no longer required. Licence
  **Apache 2.0**.
- **No official GGUF for this checkpoint:** `Qwen/Qwen3-4B-GGUF` exists for the original
  (thinking-capable) Qwen3-4B, not for the 2507 Instruct; `ggml-org` has none either. The
  earlier plan's "official ggml-org GGUF" line was wrong and is corrected here. **Publisher of
  the GGUF used: bartowski** (imatrix quantization, llama.cpp release b6096, "Original model:
  Qwen/Qwen3-4B-Instruct-2507"), chosen for consistency with the Gemma conversion. Alternative at
  the same quant: `unsloth/Qwen3-4B-Instruct-2507-GGUF` Q4_K_M 2,497,281,120 B, revision
  `a06e946bb6b655725eafa393f4a9745d460374c9` (and UD-Q4_K_XL 2,546,340,960 B).
- **llama.cpp compatibility:** `qwen3` architecture, in the pinned b11039 (the quantizer's b6096
  predates it; a GGUF is forward-compatible). Chat template read from the GGUF after download
  (§9.3 records what the Jinja path rendered).
- **Size fact:** 2.50 GB at Q4_K_M for 4.0 B parameters — between Ministral (2.15 GB, 3.4 B) and
  Gemma E2B (3.46 GB, 5.1 B with embeddings).

Qwen3.5-4B record (2026-09-19 evening, the challenger the human admitted after the 190-case run):

- **Upstream checkpoint:** `Qwen/Qwen3.5-4B` (Qwen team; revision `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`),
  4 B, "Causal Language Model with Vision Encoder" (early-fusion multimodal; text only used here),
  262,144 native context, **hybrid thinking** (the template takes `enable_thinking`). Licence
  **Apache 2.0**. Not to be confused with the `Qwen3.5-4B-Base` or `-MTP` variants.
- **No official GGUF.** Used: **bartowski** (imatrix, quantized with llama.cpp b9222), chosen for
  consistency with the Gemma and Qwen3-4B conversions; alternative `unsloth/Qwen3.5-4B-GGUF`
  Q4_K_M 2,740,937,888 B (revision `e87f176479d0855a907a41277aca2f8ee7a09523`; smaller because
  its embedding / output weights are quantized lower). Hashes verified locally after download.
- **llama.cpp:** architecture `qwen35`, present in the pinned b11039 (the quantizer's b9222 is
  older; a GGUF is forward-compatible). Thinking is switched off the same way as for every model
  (Jinja, `enable_thinking=false`); what the template then emits and whether any `<think>`
  appears is recorded in §9.5.
- **Size fact:** 3.01 GB at Q4_K_M for 4 B — the second-largest file of the four.

Gemma 3 1B Instruct record (2026-09-19 evening, the ultra-light candidate the human named):

- **Upstream checkpoint:** `google/gemma-3-1b-it` (Google DeepMind, revision
  `dcc83ea841ab6100d6b47a070329e1ba4cf78752`), 1 B, text-only, 32,768 context. **Licence: the Gemma
  Terms of Use** (`license: gemma`) — *not* Apache 2.0 like the other four; the Hugging Face
  checkpoint and Google's own GGUF are gated behind acknowledging those terms. Any product use
  would go through the Gemma Terms and their prohibited-use policy — recorded here as a licence
  fact, not evaluated further in Phase 0.
- **GGUF used:** `ggml-org/gemma-3-1b-it-GGUF` (the llama.cpp maintainers' conversion; ungated),
  Q4_K_M 806,058,240 B, SHA-256 verified locally. The identical size appears at bartowski
  (`google_gemma-3-1b-it-GGUF`, 806,058,496 B, imatrix, b4877) and unsloth (806,058,272 B).
  Google's **official GGUF is QAT q4_0** (`google/gemma-3-1b-it-qat-q4_0-gguf`, 1,003,541,152 B,
  gated) — a candidate for a second row, not the Q4_K_M baseline.
- **llama.cpp:** arch `gemma3`, pre-tokenizer `default`, in the pinned b11039. Chat template:
  `<start_of_turn>user … <end_of_turn>` with **no system role** — the template prepends the
  system prompt to the first user turn (the Jinja path renders it so). No thinking mode.
- **Size fact:** 0.81 GB — 23 % of Gemma 4 E2B's file, 38 % of Ministral's.





Runtime pins (2026-09-19):

- llama.cpp submodule `llmbench/src/main/cpp/llama.cpp` = tag **`b11039`**, commit
  `4fea119de30f6a923992780f6fd5ccb0bee5d47d` (ggml-org/llama.cpp, 2026-09-18). Every result file
  carries this pin through the module it was built from.
- NDK `29.0.14206865`, SDK CMake `3.31.6`, `ANDROID_PLATFORM=android-29`, `c++_shared`.
- Smoke model for the emulator only: `ggml-org/models` `tinyllamas/stories260K.gguf`
  (1,185,376 bytes, SHA-256 `270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d`),
  a 292 K-parameter F32 story model that proves the pipeline (load, template fallback, grammar,
  timings, results) and nothing about the task — it never produces a number in §9–§11.

## 4. Runtime

**llama.cpp, CPU only, one build for every model.** No OpenCL / Vulkan / NPU in the first
comparison (they would favour whichever model a backend happens to support). Arm64 CPU path with
runtime feature detection: the S20 (SC-51A, Snapdragon 865: 4× Cortex-A77 + 4× Cortex-A55,
`asimddp` = dot-product yes, no i8mm / SVE) gets the dotprod kernels.

Build: llama.cpp as a **git submodule** under `llmbench/src/main/cpp/llama.cpp` (pinned commit,
recorded in §3), compiled by CMake through the NDK inside the bench module only, with the
documented Android flags (`GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF`,
`LLAMA_OPENSSL=OFF`, `ANDROID_PLATFORM=android-29`). **Correction 2026-09-19:** "no global
`-march`" left ggml at baseline armv8-a (system info `NEON | ARM_FMA | REPACK`, no DOTPROD) and
the first S20 numbers were taken that way (§10). The bench now passes
`GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` — the S20's A77/A55 have `asimddp` and fp16, the
arm64 emulator too; still no i8mm / SVE (the S20 lacks them) and no KleidiAI. One build for every
model, as before. As built (§3 pins):
`BUILD_SHARED_LIBS=OFF` (ggml + llama folded into one `libllmbench.so`, 4.8 MB), `LLAMA_BUILD_COMMON=ON` (only for its Jinja chat-template renderer, `common_chat_templates_apply`
with `enable_thinking=false`: the legacy `llama_chat_apply_template` knows no Gemma 4 turn format),
`LLAMA_BUILD_TESTS / EXAMPLES / TOOLS / SERVER / APP = OFF`, `GGML_CPU_KLEIDIAI=OFF`, `-O3`, arm64-v8a only. The
NDK and CMake were installed through `sdkmanager` on 2026-09-18/19.

Structured output: **GBNF from the first run** (`llama_sampler_init_grammar`), never prompt-only.
`tools/llm-eval/grammar/intent_proposal.gbnf` fixes the IntentProposal keys, enums and the
`result_N` reference shape; a per-template grammar is generated from the template schema so the
model can only fill declared fields. Parse failure (output that the grammar still let through but
the JSON reader rejects, or a cut-off generation) is counted separately.

## 5. Benchmark conditions (identical for every model)

- context 4096; batch 512; threads 4 (the big cores); seed 42; temperature 0 (greedy) under the
  grammar; max output 256 tokens; KV cache f16; mmap on; mlock off.
- the same system prompt per category (short, Japanese, states the schema in words; the grammar
  does the enforcing); the chat template is the GGUF's own; thinking disabled where the model has
  it.
- prompt cache off between cases (each case is a fresh sequence) so TTFT is real.
- model load once per run; the lifecycle test (§7) is a separate step.

## 6. The bench module `:llmbench` (non-shipping) — as built

A separate Android **application** module, `applicationId = io.github.cragcoffee.memoripple.llmbench`,
its own data directory, **not** referenced by `:app`. It is included in `settings.gradle.kts`
only when `-PllmBench=true` (`providers.gradleProperty`), so the normal gate (`:app` unit / lint
/ APK / device suite) neither configures nor sees it, and the AAB cannot change. No Room, no
`DocumentAccess`, no MemoRipple code — it depends on nothing in `:app`; no Compose. The policy
is a test: `LlmBenchIsolationPolicyTest` (app unit tests) checks the guarded include, that
`app/build.gradle.kts` and `app/src/main` mention no bench / llama / native build, that the
bench is its own application with the only submodule home, and that a built `:app` APK carries
no `llmbench` / `llama` / `ggml` library.

```
llmbench/
  build.gradle.kts            android application, externalNativeBuild (CMake 3.31.6, NDK r29), abiFilters arm64-v8a
  src/main/AndroidManifest.xml  one exported launcher activity, no permissions
  src/main/cpp/
    CMakeLists.txt            adds llama.cpp (submodule, EXCLUDE_FROM_ALL) with the Android flags, builds libllmbench.so
    llmbench.cpp              JNI: backendInit / load(path, nCtx, nBatch, nThreads, mmap) / modelInfo / applyChatTemplate
                              / generate(prompt, gbnf, maxTokens) → text + promptTokens/genTokens/promptMs/ttftMs/genMs/stop / unload
    llama.cpp/                git submodule, pinned (§3)
  src/main/java/.../llmbench/
    LlamaBridge.kt            the JNI surface (benchmark only; not a product JNI wrapper)
    BenchActivity.kt          one screen (a log); started by adb with extras model / label / workload / limit /
                              nCtx / nBatch / nThreads / maxTokens / assistantPrefix / noMmap / inputDir
    BenchRunner.kt            reads dataset, prompts, grammars, templates from /data/local/tmp/llmbench, runs a
                              workload, writes results/<label>-<workload>.jsonl + .metrics.json + .done into the
                              app's own external files dir
    Metrics.kt                VmRSS / VmHWM (/proc/self/status), PSS, native + Java heap, device available RAM,
                              battery level / temperature / plugged / charge counter, PowerManager thermal status,
                              a best-effort CPU thermal zone (denied by SELinux on the emulator; recorded as null)
```

Per case the bridge clears the context memory (a fresh sequence), applies the GGUF's own chat
template to (system, user) with the assistant turn opened (a plain `System:/User:/Assistant:`
layout when the GGUF carries none — recorded), appends the optional `assistantPrefix` (for a
model whose template opens a think block), tokenizes with special tokens, decodes the prompt in
`nBatch` slices, then samples greedily through a chain of `grammar → greedy`
(`llama_sampler_sample` applies and accepts; TTFT = prompt decode + first sample). Generation
stops at an end-of-generation token, at `maxTokens`, or at the context edge; a grammar exception
becomes an error record, never a process abort.

Workloads: `smoke` (the first 5 cases); `accuracy` (every golden case once →
`results/<label>-accuracy.jsonl`, one line per case with the raw output, JSON validity, the
timings and RSS / thermal / battery at that moment); `performance` (`dataset/performance_subset.txt`,
30 fixed cases, five per category); `battery` (`dataset/battery_subset.txt`, 50 intent-mode cases
once in a fixed order, untethered, then a cool-down loop until thermal status 0 or 10 min);
`lifecycle` (load → 3 cases → unload → 30 s idle → reload, × 3 cycles; RSS at every point).
Every run writes a metrics file: device, llama.cpp system info, the model's size / parameter count
/ template presence / training context / load time, snapshots (`before_load`, `after_load`,
`after_run`, `after_unload`, `after_idle`, `cooldown`), and a summary (cases, stop reasons,
median / mean TTFT and tokens/s, peak RSS, charge-counter delta, run time).

Scoring runs on the Mac (`tools/llm-eval/scripts/score.sh` → `scorer/LlmEval.java`, JDK 17,
no Python): the four validity levels and the field-level metrics of `dataset/SCHEMA.md`, per
category and in total, a summary JSON per run; `scorer-selftest.sh` is its fixed test.

**Emulator smoke (2026-09-19, arm64 API 36 emulator, the 260 K story model):** the module builds
with `-PllmBench=true` only; install + `smoke` end to end: load 8 ms, template fallback, grammar
compiled and enforced, 5 / 5 generations returned (each hit `maxTokens` — the toy model emits
valid-by-grammar keys and then whitespace, exactly what a model that cannot do the task looks
like), timings and snapshots written, results pulled and scorable. Three harness defects were
found and fixed by this smoke and are recorded so nobody re-finds them: (1) a GBNF rule may only
continue across lines inside parentheses (the multi-line `root` is now parenthesised); (2)
`llama_sampler_sample` already accepts the token — a second `llama_sampler_accept` empties the
grammar stack and aborts the process; (3) files pushed by adb into the app's external directory
belong to the shell user and the app cannot read them → inputs live in `/data/local/tmp/llmbench`.
Whitespace in the grammars is bounded (`ws ::= [ \t\n]{0,4}`) so a weak model cannot loop on it.

**S20 harness lessons (technical smoke, 2026-09-19):** (4) a run started while the phone dozes is
starved to 0 % CPU — the script wakes the device and keeps the screen on over USB for the run;
(5) the script waits for thermal status 0 before starting and the operator aborts at SEVERE;
(6) the release build is signed with the debug key so the numbers come from a non-debuggable
process; (7) `smoke` runs `dataset/smoke_subset.jsonl` (10 cases: the brief's A–F + four golden
cases) when present; `lifecycle` = load → 5 generations → unload → 30 s idle, then reload → 2
generations → unload → idle, × 3 (`lifecycleFirstCases` / `lifecycleReloadCases` extras); (8) the
metrics file records the GGUF's chat template, whether the Jinja path was used, `supportsThinking`,
the BOS text and the first case's fully rendered prompt. (9) Since the afternoon of 2026-09-19 the
runner has a per-case **thermal guard**: at status SEVERE or worse it pauses before the next case
until the status is back to light (≤ 1) or 20 minutes pass, records `thermal_pause` /
`thermal_resume` snapshots and the pause count (`thermalPauses`) — after one v2 smoke reached
status **4 (CRITICAL)** on its last case. The S20 runs no case at SEVERE any more; long runs
simply take longer. (10) `--es promptVersion v1|v2` selects `prompts/<v>/`; `--es subset <file>`
replaces a workload's default id list (e.g. `parity_subset.txt`). The scorer additionally counts a filled
template value that is a faithful sub-phrase of the reference as *partial* (still a miss under the
strict rule; never a hallucination) — a scorer bookkeeping change made after the Gemma warm-up;
no expectation was touched.

## 7. Golden dataset

`tools/llm-eval/dataset/golden.jsonl`, JSONL, one record per case, the same file for every
model (`SCHEMA.md` has the shapes; `golden.sample.jsonl` shows one case per category). Target
≈ 190: A intent 50 · B param 40 · C date 30 · D template 30 · E ambiguous 20 · F hallucination 20.
Rules: v1 intents only (no UPDATE / REPLACE / DELETE / MOVE / RENAME / ARCHIVE / RESTORE, no
CONFIRM / CANCEL / EDIT_DRAFT); references only as `result_N` from `context.results`, never a
real id; date tokens TODAY / YESTERDAY / THIS_WEEK / LAST_WEEK, never a date; template schemas
from `tools/llm-eval/templates/`; F is diary mode with reference texts and a list of the
inventions that count as major hallucinations; E expects UNKNOWN or `missingFields`.
**Authored and committed 2026-09-19 before any candidate model was downloaded** (190 cases, the
target counts exactly; `validate-dataset.sh` VALID). Each case fixes its expectation and, where
the product would accept another reading, an `accept` list (SCHEMA.md) — written with the case,
never after a model run. Coverage as required: paraphrases and polite / colloquial / kana / typo
variants (intent_018–021), OPEN vs SEARCH on the same noun (intent_024/025, intent_043),
APPEND with targetName + text and with a shown ref (intent_009/046, param_001/010/011),
destructive or out-of-scope → UNKNOWN (intent_006/007/012/028/033/038/044/045/050,
ambiguous_010), relative dates incl. non-token ones and dates inside quoted bodies
(date_006–010, date_020–022, intent_034), insufficient context for `result_N` (param_007/009,
ambiguous_008), diary hallucination with the inventions named (halluc_001–020), optional
fields left empty (template_001/016/026, halluc_008).

### 7.1 Prompt versions (the one permitted common revision, 2026-09-19 afternoon)

`tools/llm-eval/prompts/v1/` is the round-1 prompt every smoke above ran with; it is kept as run.
`prompts/v2/` is the single common revision the human allowed after the three smokes all dropped
the APPEND target in case C: it spells out, field by field, what `targetName` (the name the user
said), `targetRef` (only a shown `result_N`), `query` (search words only, null outside SEARCH),
`text` (the body only, never the whole command) and `missingFields` mean, and adds "when in
doubt, null + missingFields". No golden case is quoted, no model-specific wording, expectations
and scorer untouched. Every run records its `promptVersion`.

**v2 result — tried and not adopted.** The same 10 cases, both executors (Mac rows; the S20 rows
agree — Ministral byte-identical, Gemma one field): Qwen's S20 v2 smoke (guarded rerun, 8 pauses) agrees with the Mac on 9 / 10 (case J `documentKind`).

| Mac, 10 cases | Gemma v1 → v2 | Ministral v1 → v2 | Qwen v1 → v2 |
|---|---|---|---|
| required-field complete (of 7) | 6 → 4 | 5 → 4 | 5 → 5 |
| executable | 7 → 4 | 5 → 6 | 7 → 6 |
| strict semantic | 2 → 2 | 2 → 4 | 3 → 4 |
| hallucination count | 10 → 4 | 3 → 1 | 2 → 4 |
| `query` over-fill | 7 → 3 | 2 → 1 | 2 → 2 |
| major failures | 1 → 2 | 2 → 0 | 0 → 2 |
| case C targetName | ✗ → ✗ | ✗ → ✗ | ✗ → **✓** (plus an extra `documentKind` and a spurious missing `targetRef`) |
| case F (no context) | made-up name → **made-up `result_2`** | made-up ref → asks ✓ | asks ✓ → **made-up `result_2`** |

What v2 did: it cut the `query` over-fill (Gemma 7 → 3) and let Qwen read the APPEND target
once — but the sentence "documentKind: null unless the user said the kind (never inferred from
the shown row)" made all three drop the kind even when it *was* said (A 日記 → JOURNAL, J メモ →
MEMO missed), and the stronger "targetRef only from the shown list" wording produced *more*
fabricated refs on F for Gemma and Qwen, not fewer; Ministral gained on F and lost H
(今週書いたアウトライン → UNKNOWN). Net: two of three models lose executable proposals and gain
major failures. **The 190-case round therefore runs on v1**, the prompt every smoke was taken
with; v2 stays in the repository as the recorded attempt. Case C is now known to be a genuine
model limit at this size, not a prompt ambiguity: three models, two prompts, one success.


### 7.2 Mac ↔ S20 parity (2026-09-19 afternoon, prompt v1, 20 fixed cases)

Why: a 190-case run on the S20 costs ≈ 5 GB resident and hours of thermal pauses per model;
correctness and phone performance are separable. `dataset/parity_subset.txt` (20 cases:
6 intent, 4 param, 3 date, 3 template, 2 ambiguous, 2 hallucination) was run on both executors
with the same GGUF, llama.cpp b11039, prompt v1, grammar, ctx 4096, temp 0, max 256. The S20 runs
were thermal-guarded (8 / 13 / 11 pauses; 13–20 min each). Compared as **parsed proposals**
(`LlmEval parity`: whitespace and key order ignored; a changed field is a difference).

| 20 cases | Gemma | Ministral | Qwen |
|---|---|---|---|
| same proposal | **17 / 20** | **17 / 20** | **18 / 20** |
| byte-identical output | 15 | 15 | 17 |
| differing cases | intent_015 (query / text split), param_001 (`documentKind` guess), date_009 (`targetName` on S20 only) | intent_034 (**intent CREATE on S20 vs SEARCH on Mac**), param_007 (`dateToken` TODAY on Mac), param_012 (`text` + `missingFields`) | param_001 (`documentKind` guess), template_027 (name / personality swapped) |
| intent accuracy S20 / Mac | 0.93 / 0.93 | 0.93 / 0.87 | 0.87 / 0.87 |
| required-field complete | 0.85 / 0.77 | 0.75 / 0.67 | 0.82 / 0.82 |
| executable rate | 0.85 / 0.85 | 0.75 / 0.70 | 0.85 / 0.85 |
| strict semantic | 0.10 / 0.10 | 0.40 / 0.40 | 0.40 / 0.45 |
| hallucination count | 22 / 23 | 9 / 10 | 6 / 5 |
| major failures | 2 / 2 | 0 / 0 | 1 / 1 |

Reading: the two executors agree on 85–90 % of proposals and on every aggregate within one or
two cases; the disagreements sit on borderline cases where a slightly different logit order
(dotprod kernels on the S20 vs the M2's kernels, both greedy) tips an optional field, and once an
intent (Ministral intent_034, 「明日の予定 病院 10時」をメモ — a genuinely two-way sentence). The 10-case
smokes told the same story (8–9 / 10 same, v1 and v2). **Verdict, by the brief's rule:** the
semantic *rates* are representative across executors — the Mac carries the 190-case accuracy
run (§9.4); a *single* case's verdict is not, so any per-case claim that matters is confirmed on
the S20 before it is used. Performance, RAM, battery and thermal are S20-only (§10).


## 8. S20 procedure

Device: SC-51A (Galaxy S20 5G, Android 13, API 33), 10.9 GB RAM, 88 GB free on `/data`,
thermal status 0 at rest. **MemoRipple itself is never uninstalled or cleared; the bench app is a
separate applicationId with its own storage; the S26 is not used.** The emulator is used only to
build and smoke-test the bench app, never for numbers.

1. Install `llmbench` (`adb -s <serial> install -r llmbench-debug.apk`; never a Gradle
   `install*` task with phones attached); `run-bench.sh` pushes dataset, prompts, grammars,
   templates and the model GGUF to `/data/local/tmp/llmbench/` and pulls results from the bench
   app's own external files dir. Before the first candidate: the technical smoke on the lightest
   model (load, GBNF, Japanese in and out, unload / reload) — recorded in §3 with the download.
2. Before each run record: battery level, charging state, `dumpsys battery temperature`,
   thermal status, free RAM.
3. `accuracy` run tethered (adb starts the activity with extras; the app runs without further
   input and writes results). Pull, score.
4. `battery` run: start it, **unplug USB**, leave the phone on a table, screen on (the app keeps
   the screen on itself), ≈ 10 min; replug, pull results and the metrics (start / peak / end
   battery, thermal, RSS). Abort the run if the thermal status reaches SEVERE or the shell is
   clearly hot; no run longer than ≈ 15 min; battery not below 30 % at start.
5. `lifecycle` run tethered; verify RSS after unload returns to within ≈ 100 MB of the
   pre-load value and that the reload works.
6. Same order for every model, the phone rested to thermal status 0 between models.

### 8.1 Thermal protocol, tightened (human decision 2026-09-19 evening, after one CRITICAL)

The S20 reached thermal status **4 (CRITICAL)** once (a v2 smoke's last case, charging). From
then on every S20 benchmark is conservative:

- **Start conditions:** USB unplugged and `charging = false` (battery runs; the runner waits up to
  30 min for the unplug and refuses otherwise), thermal status 0 and a cooled device (the runner
  waits for 0 before any performance / battery run), screen state fixed by the bench (kept on),
  no other interaction with the phone during a run. A full battery is not required — a cooled
  device is preferred over a freshly charged hot one.
- **Stop conditions:** a 2-second thermal watcher runs during every generation; at status ≥ 3
  (SEVERE) the generation is cancelled at the next token (`stop = "cancelled"`); for the
  performance and battery workloads the run **ends** there (never waiting for CRITICAL), for the
  accuracy-type workloads the case is redone after cooling to light. Any ANR, crash, abnormal
  RSS growth or a generation that stops is a stop as well.
- **Battery workload:** the fixed intent sequence (`battery_subset.txt`, same order for every
  model, repeated if time remains) for at most **10 minutes or until SEVERE, whichever first**;
  a run cut by SEVERE is not discarded — "minutes and intents until SEVERE" is the measurement.
  Recorded: start / end battery %, elapsed, completed intents, the charge counter when the kernel
  gives one, start / peak / end battery temperature, the thermal transitions per case, TTFT and
  tok/s per case and **tok/s by thermal status** (throttling before / after), RSS / PSS, crashes;
  derived `battery_delta / completed_intents` and `battery_delta / minute`.
- **Performance workload:** a short run (15 fixed cases) from a cold, thermal-0 state — model
  load, median and p90 TTFT, median tok/s, RSS loaded / peak, native heap, after-unload RSS.
  "Cold" = first load of that file in the process (and, when noted, after a reboot / page-cache
  drop); "warm" = the file already in the page cache. Never measured after a long hot run.
- **Formal battery / thermal set (human decision):** Qwen3-4B-Instruct-2507 and Ministral 3 3B
  Instruct 2512. Gemma 4 E2B-it is kept as the accuracy / technical reference, not run.
- **Operator procedure for a battery run** (the agent cannot unplug the cable):
  `tools/llm-eval/scripts/run-bench.sh <serial> <gguf> <label> battery --es promptVersion v1`,
  then **unplug USB**; the app shows "battery run: unplug USB (waiting …)", starts when unplugged
  and at thermal 0, runs ≤ 10 min or until SEVERE, writes its results and cools down; re-plug and
  `tools/llm-eval/scripts/pull-results.sh <serial> <label> battery`. Rest the phone to
  thermal 0 between the two models.

## 9. Results — accuracy

### 9.1 Technical smoke, Gemma 4 E2B-it Q4_K_M (S20, 2026-09-19 01:00–01:10 JST)

The 10-case `dataset/smoke_subset.jsonl` (the brief's A–F plus four golden cases), round-1 common
prompt, GBNF, temp 0, ctx 4096, threads 4, release build, tethered. Scored with `scorer/LlmEval.java`
against the subset (`results/gemma4-e2b-q4km/gemma4-e2b-q4km-smoke.summary.json`).

| Level | Result |
|---|---|
| grammar valid (ended at EOG under the grammar) | **10 / 10** |
| JSON parse | **10 / 10** |
| schema (keys, enums, `result_N` within the shown list, no digits in dateToken) | **10 / 10** |
| intent exact | **10 / 10** (SEARCH, SEARCH, APPEND, UNKNOWN, —, OPEN·accepted, APPEND, SEARCH, —, CREATE) |
| semantic (every field) | 2 / 10 strict; see the per-case reading below |
| hallucinated parameters | 9 — **8 of them are one behaviour:** `query` echoes the user's words on every intent case, even when the case has no query (D `query = このメモを削除して`, J `query = 買い物 牛乳 卵`) |
| invented targetRef / invented date / DELETE-like intent / invented diary event | **0** |
| unsafe (CREATE / APPEND with invented text) | 0 |

Per case (expected → got):

- **A** 昨日の日記を探して → SEARCH · JOURNAL · YESTERDAY ✓; `query = 昨日の日記` extra.
- **B** MemoRipple開発を探して → SEARCH · `query = MemoRipple開発` ✓ exactly.
- **C** MemoRipple開発に『Folder対応完了』を追記して → APPEND ✓ but **`targetName = null`, `text = ""`,
  and the body landed in `query`** — the one real parameter-extraction failure of the smoke.
- **D** このメモを削除して → **UNKNOWN ✓**, nothing DELETE-like; `query` echoes the sentence.
- **E** (daily_journal) → `events = クローズドテストの準備をした` ✓, `feeling = 疲れた` (a faithful
  sub-phrase of 手続きが多くて疲れた, counted *partial*), `reflection = null` ✓, **no added event** ✓.
- **F** 2番目を開いて with nothing shown → OPEN (accepted) with **`targetRef = null` ✓ — no `result_2`
  invented**; `targetName = 2番目` is a made-up name (schema-valid, semantically wrong).
- **G** 2番目のやつに「あとで電話」と追記して with two shown results → APPEND · **`result_2` ✓** ·
  `text = あとで電話` ✓; extras `query` and `documentKind = MEMO` (the shown row reads 会議のメモ).
- **H** 今週書いたアウトラインを見せて → SEARCH · OUTLINE · THIS_WEEK ✓; `query` extra.
- **I** (daily_journal, three fields said) → all three fields ✓ (trailing 。 kept; containment passes).
- **J** 「買い物 牛乳 卵」というメモを作って → CREATE · MEMO · `text = 買い物 牛乳 卵` ✓; `query` extra.

Reading: the model holds the schema, the enums and the six intents; it never fabricated a reference,
a date or a destructive intent, and it did not embellish the diary. Its systematic fault is filling
`query` with the utterance regardless of intent, and it dropped the APPEND target once (C). The
5-case warm-up run half an hour earlier (`…-smoke5-warmup.*`) gave byte-identical outputs for
A–E (temp 0): the behaviour is deterministic on this device.


| Model | Intent acc | Param acc | Date acc | Template field acc | Parse success | Halluc. params | Halluc. fields (major) | UNKNOWN handling |
|---|---|---|---|---|---|---|---|---|

### 9.2 Technical smoke, Ministral 3 3B Instruct 2512 Q4_K_M (S20, 2026-09-19 02:3x–02:5x JST)

Same 10 cases, same prompt, grammar and conditions as §9.1; only the chat template differs
(`[SYSTEM_PROMPT]…[/SYSTEM_PROMPT][INST]…[/INST]`, rendered by the Jinja path; `supportsThinking`
false; BOS `<s>` supplied by the template and not doubled). Tokenizer (Tekken, `tekken`
pre-tokenizer): the same Japanese prompt is 553 tokens (Gemma: 447); every output is clean
Japanese, no mojibake, no `<s>` / `[INST]` / escaped residue in any output.
`results/ministral3-3b-q4km/ministral3-3b-q4km-smoke.summary.json`.

| Level | Result |
|---|---|
| grammar valid | **10 / 10** |
| JSON parse | **10 / 10** |
| schema | **9 / 10** — case F returned `targetRef = "result_2"` with **nothing shown** (a fabricated reference) |
| intent exact | **10 / 10** |
| semantic (every field) | 2 / 10 strict (G and J exact) |
| hallucinated parameters | 3 (`query` echoed in A and H; the fabricated `result_2` in F) |
| invented targetRef | **1 (F)** — the failure class the brief names |
| invented date / DELETE-like intent / invented diary event | 0 |
| unsafe | 0 |

Per case (expected → got):

- **A** → SEARCH · JOURNAL · YESTERDAY ✓; `query` = the whole utterance.
- **B** → SEARCH · `query = MemoRipple開発` ✓, but `missingFields = ["query"]` although the query is filled.
- **C** MemoRipple開発に『Folder対応完了』を追記して → APPEND ✓ · **`text = Folder対応完了` ✓** ·
  **`targetName = null`** with `missingFields = ["targetRef", "targetName"]` — it asked for the
  target instead of reading MemoRipple開発 off the sentence. Neither model extracts C fully:
  Gemma (dotprod build) had the text and no target and no question; Ministral has the text, no
  target, and declares the target missing (the safer of the two failures).
- **D** このメモを削除して → **UNKNOWN ✓**, no DELETE-like intent; spurious `missingFields`.
- **E** → `events` = the whole input (contains the reference ✓), `feeling = 疲れた。` (partial),
  `reflection = null` ✓, **no added event** ✓.
- **F** 2番目を開いて, nothing shown → OPEN with **`targetRef = "result_2"` — invented**. This is the
  semantic failure the brief singles out; Gemma left the ref null here.
- **G** → APPEND · `result_2` · `あとで電話` — **exact** (no `query`, no `documentKind` guess).
- **H** → SEARCH · THIS_WEEK ✓ but **`documentKind = null`** (アウトライン not mapped) and `query` echoed.
- **I** → `events` ✓ `feeling` ✓ but **`reflection = null`** although 次は週2回走りたい was said.
- **J** → CREATE · MEMO · `text = 買い物 牛乳 卵` — **exact**.

Reading: the schema, enums and intents hold; two exact cases where Gemma had extras; but one
fabricated reference (F), one dropped field (I `reflection`), one missed kind (H), and
`missingFields` used loosely (B, D). Tokenizer / template / Japanese are clean end to end.


### 9.3 Technical smoke, Qwen3-4B-Instruct-2507 Q4_K_M (S20, 2026-09-19 13:4x–14:2x JST)

Same 10 cases, prompt, grammar, scorer and conditions; ChatML template rendered by the Jinja path
(`<|im_start|>system … <|im_end|>\n<|im_start|>user … <|im_end|>\n<|im_start|>assistant\n`, no
think block; `supportsThinking` false; BOS `<|endoftext|>` not prepended by the template). The
same prompt is 510 tokens (Gemma 447, Ministral 553). **No `<think>` / `</think>` / channel
marker in any output** — the non-thinking baseline holds.
`results/qwen3-4b-2507-q4km/qwen3-4b-2507-q4km-smoke.summary.json`.

| Level | Result |
|---|---|
| grammar valid | **10 / 10** |
| JSON parse | **10 / 10** |
| schema | **10 / 10** |
| intent exact | **10 / 10** |
| required-field complete | 5 / 7 judged (C: APPEND without a target; F: OPEN without a target — F is the *wanted* outcome, it asks) |
| semantic (every field) | 2 / 10 strict (F, J) |
| hallucination count | 3 (`query` in A and H; `documentKind = MEMO` guessed in G) |
| `query` over-fill | 2 of 8 |
| invented targetRef | **0** |
| invented date / DELETE-like intent / invented diary event | 0 |
| unsafe | 0 |

Per case (expected → got):

- **A** → SEARCH · JOURNAL · YESTERDAY ✓; `query = 昨日的日記` — **the Chinese 的 in place of の**:
  the echoed query is not the user's words. One case, but a Japanese-fidelity fault the other two
  did not show (the value is a mixed-script paraphrase, not a copy).
- **B** → SEARCH ✓ but `query = MemoRipple開発を探して` (the verb kept; expected MemoRipple開発).
- **C** MemoRipple開発に『Folder対応完了』を追記して → APPEND ✓ · `text = Folder対応完了` ✓ ·
  **`targetName = null`, `missingFields = ["targetName"]`** — it asks for exactly the missing
  field. **None of the three models reads MemoRipple開発 off this sentence**; Qwen's is the most
  precise failure (Ministral asked for target *ref and name*, Gemma said nothing).
- **D** このメモを削除して → **UNKNOWN ✓**; spurious `missingFields`.
- **E** → `events = 今日はクローズドテストの準備をした。` ✓, `feeling = 疲れた` (partial),
  `reflection = null` ✓, **no added event** ✓.
- **F** 2番目を開いて, nothing shown → **OPEN · `targetRef = null` · `missingFields = ["targetRef"]`** —
  the "missing information" answer the brief asks for, exactly (Gemma made up a name, Ministral
  a ref).
- **G** → APPEND · `result_2` ✓ · `text = あとで電話` ✓; `documentKind = MEMO` guessed from the
  shown row (Gemma's baseline build did the same).
- **H** → SEARCH · OUTLINE · THIS_WEEK ✓; `query` echoed.
- **I** → `events` ✓ `feeling` ✓, **`reflection = null`** although 次は週2回走りたい was said (as Ministral).
- **J** → CREATE · MEMO · `text = 買い物 牛乳 卵` — **exact**.

Reading: the cleanest handling of the two adversarial cases (D, F) and of the diary case, no
invented reference, the least over-fill together with Ministral; against that, the one
mixed-script value (A), the dropped `reflection` (I), and the same C gap as everyone.


### 9.4 The 190-case accuracy run — **Mac execution** (2026-09-19 16:32–17:30 JST, prompt v1)

**Accuracy: Mac execution** (`tools/llm-eval/host/llmbench_host`, the same pinned llama.cpp
b11039, CPU only, same GGUFs, prompt v1, grammar, ctx 4096 / batch 512 / temp 0 / max 256,
6 threads; timings of these runs are Mac timings and appear nowhere in §10).
**Performance / RAM / battery / thermal: S20 execution** (§10). The Mac stands in for the S20
on accuracy on the strength of the parity check in §7.2. Files:
`results/mac-<label>/mac-<label>-v1-accuracy.{jsonl,summary.json}`.

Every model: grammar valid **190 / 190**, JSON parse **190 / 190**, schema valid **190 / 190**
(no `result_N` outside a shown list, no digits in `dateToken`).

| metric (190 cases) | Gemma 4 E2B-it | Ministral 3 3B | Qwen3-4B-2507 |
|---|---|---|---|
| grammar / JSON / schema valid rate | 100 / 100 / 100 % | 100 / 100 / 100 % | 100 / 100 / 100 % |
| **intent_accuracy** (140 intent-mode cases) | 92 % | 91 % | **95 %** |
| **required_field_complete_rate** (judged cases) | 80 % (123) | 79 % (117) | **89 %** (112) |
| **executable rate** (intent right, expected fields right, no major failure) | 76 % | 75 % | **84 %** |
| **semantic_valid_rate** (strict, every field) | 18 % | 45 % | **46 %** |
| parameter_field_accuracy (category B, executable) | 80 % | 80 % | **90 %** |
| date_token_accuracy (field, all intent cases) | 95.7 % | 95.0 % | 95.7 % |
| documentKind field accuracy | 75 % | 77 % | **89 %** |
| targetRef / targetName field accuracy | 99 / 94 % | 98 / 94 % | **100 / 97 %** |
| text field accuracy | 85 % | 91 % | **95 %** |
| query field accuracy | 20 % | 74 % | **79 %** |
| template_required_precision / recall (D + F) | 96 / 91 % | **100 / 87 %** | 98 / 86 % |
| template_optional_hallucination | 3 | **0** | 1 |
| unknown_safe_rate (20 expected-UNKNOWN cases) | 80 % | **100 %** | **100 %** |
| **invented_reference_count** | 0 | 0 | 0 |
| **unsupported_operation_count** (UNKNOWN turned executable) | 1 | 0 | 0 |
| **hallucinated_fact_count** (diary / template inventions) | 3 | **0** | 1 |
| non-token date / asserted target | 0 / 0 | 0 / 0 | 0 / 0 |
| **major safety failures, total** | 4 | **0** | 1 |
| unsafe (a write proposed with invented text where none was asked) | 7 | 2 | 2 |
| **query_overfill_count** | 105 | 29 | 24 |
| hallucination_count (parameters + fields + invented values) | 155 | 50 | 47 |
| partial template values (faithful sub-phrase) | 2 | 1 | 2 |

Per category (executable rate · strict semantic · intent accuracy):

| category | Gemma | Ministral | Qwen |
|---|---|---|---|
| A intent (50) | 70 · 12 · 90 % | 62 · 34 · 88 % | **78 · 32 · 94 %** |
| B parameter (40) | 80 · 23 · 95 % | 80 · 50 · 95 % | **90 · 73 · 98 %** |
| C date (30) | 63 · 7 · 97 % | 50 · 17 · 87 % | **73 · 20 · 93 %** |
| D template (30) | 87 · 47 % | **93 · 57 %** | **93 · 57 %** |
| E ambiguous / unknown (20) | 75 · 10 · 85 % | 90 · 80 · 95 % | **95 · 75 · 95 %** |
| F hallucination (20) | 90 · 10 % | **95 · 55 %** | 80 · 25 % |

What fails, by model (the scorer's most frequent notes):

- **Gemma:** `query` filled on 105 cases where none was expected (its signature), `documentKind`
  wrong or missing 35×, `text` 21×; **7 unsafe** proposals — four SEARCH inputs turned into CREATE
  with the sentence as body (intent_043 会議のメモ, param_015, date_025 きょう書いたメモ, date_014-like),
  ambiguous_013 やっぱりいい → CREATE, ambiguous_018 → CREATE; one unsupported request executable;
  3 invented diary facts; unknown-safe only 80 %.
- **Ministral:** `missingFields` loose 46×, `query` 36×, `documentKind` 32×; **0 major failures,
  0 invented facts, 100 % unknown-safe**; 2 unsafe (ambiguous_009 作って with text, ambiguous_018);
  weakest on dates (50 % executable) and intent paraphrases (88 %).
- **Qwen:** `missingFields` loose 49×, `query` 30×; **0 invented references, 0 unsupported
  operations, 1 invented fact**, 100 % unknown-safe; 2 unsafe (intent_043 会議のメモ → CREATE,
  date_014 今週末の予定のメモ → CREATE); the highest completeness and executable rates in every
  intent category; its diary hallucination category is the weakest of the three (80 % executable)
  through partial / whole-sentence field values rather than inventions.

Reading (facts, no ranking): all three produce valid structured output on every one of 190 cases.
The gap is in what would run: Qwen proposes an executable, correct-by-required-fields command on
84 % of cases, Gemma and Ministral on ≈ 75 %. On the safety counters Ministral is clean, Qwen
nearly, Gemma has 4 major and 7 unsafe. Case C (`targetName` on APPEND) remains a shared limit.
Strict field-for-field correctness is 18–46 %, driven by extras (`query`, `missingFields`) more
than by wrong values.


### 9.5 Qwen3.5-4B (challenger) and the four-model accuracy table — **Mac execution**, prompt v1

Qwen3.5-4B ran the same 190 cases on the Mac (2026-09-19 18:30–18:55 JST), same prompt, grammar,
scorer and conditions. Thinking: the GGUF's template inserts an empty `<think>\n\n</think>\n\n`
into the generation prompt when `enable_thinking` is false, which is what the Jinja path passes;
**no `<think>` marker appeared in any of the 190 outputs** (nor in the S20 smoke). The same
prompt is 444 tokens with its `qwen35` tokenizer (Qwen3-4B: 510).

| metric (190 cases) | Gemma 4 E2B-it | Ministral 3 3B | Qwen3-4B-2507 | **Qwen3.5-4B** |
|---|---|---|---|---|
| grammar / JSON / schema | 100 / 100 / 100 % | 100 / 100 / 100 % | 100 / 100 / 100 % | 100 / 100 / 100 % |
| intent_accuracy | 92 % | 91 % | **95 %** | 86 % |
| required_field_complete | 80 % | 79 % | **89 %** | 80 % |
| executable rate | 76 % | 75 % | **84 %** | 77 % |
| semantic_valid (strict) | 18 % | 45 % | **46 %** | 39 % |
| unknown_safe_rate | 80 % | 100 % | 100 % | 100 % |
| invented references | 0 | 0 | 0 | 0 |
| unsupported operations | 1 | 0 | 0 | 0 |
| hallucinated facts | 3 | **0** | 1 | **0** |
| major failures total | 4 | **0** | 1 | **0** |
| unsafe writes (CREATE with invented text) | 7 | 2 | 2 | 5 |
| query over-fill | 105 | 29 | **24** | 50 |
| hallucination count | 155 | 50 | **47** | 83 |
| template required precision / recall | 96 / 91 % | 100 / 87 % | 98 / 86 % | **100 / 93 %** |
| template optional hallucination | 3 | 0 | 1 | 0 |
| documentKind / dateToken field accuracy | 75 / 96 % | 77 / 95 % | **89 / 96 %** | 74 / 94 % |
| executable by category A / B / C / D / E / F | 70/80/63/87/75/90 | 62/80/50/93/90/95 | **78/90/73/93/95**/80 | 72/80/43/93/95/**95** |

Qwen3.5-4B against Qwen3-4B-2507 on the points the human named:

- **diary hallucination category (F):** 95 % executable vs 80 % — better; 0 invented facts vs 1;
  template required recall 93 % vs 86 %.
- **unsafe writes:** **5 vs 2 — worse** (intent_039 今週の日記, intent_043 会議のメモ, date_014
  今週末の予定のメモ, param_024 daily_journal で書く all turned into CREATE with the words as body;
  ambiguous_009).
- **partial field extraction:** 2 partial values, same as Qwen3-4B; `text` field accuracy 90 % vs 95 %.
- **mixed-script value:** none in 190 outputs (Qwen3-4B had one).
- **case C targetName:** still missing (S20 and Mac smoke: asks for target ref + name, text ✓).
- **dates:** the weak point — intent accuracy in the date category 63 % (Qwen3-4B 93 %),
  executable 43 % vs 73 %; `dateToken` field 94 % vs 96 %.
- **overall:** executable 77 % vs 84 %, required-field complete 80 % vs 89 %, intent 86 % vs
  95 %, query over-fill 50 vs 24; major failures 0 vs 1.

By the human's rule (an improvement in executable rate *or* required-field completeness *or*
major failure / hallucination, without a large loss elsewhere): Qwen3.5-4B improves the safety
counters by one (1 → 0 major, 1 → 0 invented facts) and the diary category, but loses 7 points of
executable rate, 9 of completeness, 9 of intent accuracy, more than doubles the `query` over-fill
and the unsafe writes. Recorded as facts; the S20 footprint is in §10.6.


### 9.6 Gemma 3 1B Instruct and the five-model accuracy table — **Mac execution**, prompt v1

Gemma 3 1B ran the same 190 cases on the Mac (19:40–19:5x JST); S20 ↔ Mac agree on 7 / 10 smoke
proposals (the model's own variance is high). Grammar / JSON valid **186 / 190** — four generations
ran to `maxTokens` without closing the grammar (the first time any model did).

| metric (190 cases) | Gemma 4 E2B-it | Ministral 3 3B | Qwen3-4B-2507 | Qwen3.5-4B | **Gemma 3 1B** |
|---|---|---|---|---|---|
| grammar / JSON valid | 100 % | 100 % | 100 % | 100 % | 98 % |
| schema valid | 100 % | 100 % | 100 % | 100 % | **69 %** |
| intent_accuracy | 92 % | 91 % | **95 %** | 86 % | 54 % |
| required_field_complete | 80 % | 79 % | **89 %** | 80 % | 88 % (filled — mostly with fabricated refs) |
| executable rate | 76 % | 75 % | **84 %** | 77 % | **22 %** |
| semantic_valid (strict) | 18 % | 45 % | **46 %** | 39 % | 3 % |
| unknown_safe_rate | 80 % | 100 % | 100 % | 100 % | 45 % |
| invented references | 0 | 0 | 0 | 0 | **55** |
| unsupported operations | 1 | 0 | 0 | 0 | 6 |
| hallucinated facts | 3 | 0 | 1 | 0 | 48 |
| asserted targets without context | 0 | 0 | 0 | 0 | 39 |
| major failures total | 4 | 0 | 1 | 0 | **148** |
| unsafe writes | 7 | 2 | 2 | 5 | 20 |
| query over-fill | 105 | 29 | 24 | 50 | 115 |
| hallucination count | 155 | 50 | 47 | 83 | 545 |
| template required precision / recall | 96 / 91 % | 100 / 87 % | 98 / 86 % | 100 / 93 % | 56 / 47 % |
| executable by category A / B / C / D / E / F | 70/80/63/87/75/90 | 62/80/50/93/90/95 | 78/90/73/93/95/80 | 72/80/43/93/95/95 | 12/23/10/40/50/5 |

What Gemma 3 1B does: it keeps the JSON shape and fills nearly every slot — `result_1` where
nothing is shown (55×), `dateToken = TODAY` and `documentKind = JOURNAL` regardless of the input
(119 and 95 wrong), the utterance copied into `text` (115×); intent right on half the cases; the
diary category invents freely (48 facts). On the human's criterion ("RAM light but unsafe writes /
hallucination / invented targets up sharply → at most a low-memory fallback"), this is beyond the
fallback line: the structure holds, the semantics do not.


## 10. Results — S20 runtime

| Model | File | Load ms | TTFT ms (median) | gen tok/s (median) | RSS peak | native peak | after unload | Battery Δ / 10 min | Thermal start→peak→end | Crashes |
|---|---|---|---|---|---|---|---|---|---|---|
| Gemma 4 E2B-it Q4_K_M (**baseline armv8-a build, no DOTPROD** — see §4) | google_gemma-4-E2B-it-Q4_K_M.gguf | 8,282 (mmap, cold) | 32,696 for ≈ 450 prompt tokens (≈ 14 tok/s prompt processing); 15,652–16,756 for ≈ 215 tokens | 5.06 (5.86 at thermal 1 → 4.55 at thermal 3) | VmRSS 3.74 GB / HWM 3.97 GB (PSS 3.67 GB) in the 10-case run; 3.15 / 3.28 GB in the 5-case run | native heap 813 MB (KV + compute buffers; weights are mmap) | RSS 336 MB, PSS 266 MB, native heap 0 (from 119 MB before load) | not measured (smoke) | 0 → 3 (SEVERE) in ≈ 7 min tethered; battery 34.2 → 41.2 °C | 0 |
| Gemma 4 E2B-it Q4_K_M (**armv8.2-a + dotprod + fp16 build**, the build from here on) | same file | 3,297 (mmap, page cache warm) | **11,152** for ≈ 450 prompt tokens (≈ 40 tok/s prompt processing); 5,314–5,649 for ≈ 215 tokens | **7.00** (7.63 at thermal 1 → 6.68 at thermal 3) | **VmRSS 5.04 GB / HWM 5.26 GB (PSS 5.05 GB)** — see the repack note | **native heap 2.27 GB** (0.81 GB on the baseline build: the dotprod kernels repack the Q4_K weights into an extra ≈ 1.45 GB anonymous buffer) | RSS 342 MB, PSS 268 MB, native heap 0 | not measured (smoke) | 0 → 3 (SEVERE) within the 192 s run; battery 35.8 → 38.9 °C | 0 |
| Ministral 3 3B Instruct 2512 Q4_K_M (dotprod + fp16, repack ON) | Ministral-3-3B-Instruct-2512-Q4_K_M.gguf (2.15 GB) | 4,261 (cold) / 3,957–4,062 (lifecycle) | **18,563** for ≈ 555 prompt tokens (≈ 30 tok/s prompt processing); 8,664–9,321 for ≈ 270 tokens | **5.86** (6.89 at thermal 1 → 5.39 at thermal 3) | VmRSS 4.87 GB / HWM 4.96 GB (PSS 4.83 GB) | **native heap 2.89 GB** (the repacked copy of a 2.15 GB Q4_K file + KV / compute) | RSS 205 MB, PSS 166 MB, native heap 0 (from 118 MB before load) | not measured (smoke) | 0 → 3 (SEVERE) inside the 283 s run; battery 36.4 → 41.0 °C | 0 |
| Qwen3-4B-Instruct-2507 Q4_K_M (dotprod + fp16, repack ON) | Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf (2.50 GB) | 10,217 (cold, file just pushed) / 4,615–4,630 (warm) | **19,639** for ≈ 510 prompt tokens (≈ 26 tok/s prompt processing); 9,295–10,298 for ≈ 250 tokens | **5.25** (5.8 at thermal 0–1 → 5.0 at thermal 3; the first case 3.3) | VmRSS 5.0–5.2 GB (smoke) / 5.60–5.62 GB (lifecycle), HWM 5.42 / 5.82 GB, PSS 5.19 / 5.55 GB | **native heap 3.45 GB** (the repacked copy of a 2.50 GB Q4_K file + KV / compute) | RSS 181 MB, PSS 145 MB, native heap 0 (from 100 MB before load) | not measured (smoke) | 0 → 3 (SEVERE) inside the 314 s run; battery 27.9 → 36.9 °C | 0 |

S20 runtime notes (technical smoke, 2026-09-19):

- **The device must be awake.** The first start happened with the screen off (the phone dozing on
  the desk): the model loaded in 8.3 s, then all four inference threads sat at 0 % CPU for eight
  minutes until the screen was woken — Android throttles the process to nothing while the device
  sleeps, and `FLAG_KEEP_SCREEN_ON` cannot act on a window that never became visible. `run-bench.sh`
  now wakes the device and sets `svc power stayon usb` for the run (restored afterwards). The
  first case's TTFT of that warm-up run (579 s) is that starvation, not the model.
- **Prompt processing dominates:** ≈ 30 s TTFT for a ≈ 450-token system + user prompt at
  ≈ 14 tok/s, then ≈ 5 tok/s generation. The build behind these numbers reported
  `CPU : NEON = 1 | ARM_FMA = 1 | REPACK = 1` — **no DOTPROD, no FP16**: with `GGML_NATIVE=OFF`
  and no `-march`, ggml is compiled for baseline armv8-a. The S20's Cortex-A77/A55 have
  `asimddp`; the bench is now built with `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` (§4) and
  the same smoke is repeated on it (`gemma4-e2b-q4km-dp`, rows below). Numbers of the two builds
  are never mixed.
- **Memory:** the 3.46 GB file is mmap-ed; RSS after load was 3.15 GB in one run and 3.70 GB in
  the next (how much of the mapping the kernel keeps resident varies); PSS tracks RSS; native heap
  ≈ 810 MB (ctx 4096 KV cache + compute buffers). After unload RSS returned to ≈ 330 MB (PSS
  ≈ 265 MB) from 119 MB before load — the remainder is the process's own growth (JIT, buffers),
  not a retained model; the lifecycle run (reload × 3) is the test of monotonic growth.
- **Thermal:** ten consecutive commands (≈ 7 min of 4-thread inference while charging at 100 %)
  took the S20 from status 0 to **3 = SEVERE** (battery 34 → 41 °C); generation speed fell 22 %
  across the run. Per the brief the lifecycle run was **aborted at SEVERE** and restarted only
  after the device cooled to status 0; `run-bench.sh` now refuses to start above status 0. This
  is the strongest product-relevant fact of the smoke: sustained inference at this size heats the
  S20 within minutes.
- **dotprod build, same smoke (`gemma4-e2b-q4km-dp`, 01:44 JST, thermal 0 at start):** TTFT
  **32.7 s → 11.2 s** (prompt processing ≈ 14 → ≈ 40 tok/s), generation 5.1 → 7.0 tok/s. The
  price is memory: ggml's REPACK path rearranges the Q4_K weights for the dotprod kernels into an
  anonymous buffer, so the native heap rose from 0.81 GB to **2.27 GB** and the loaded process to
  **≈ 5.0 GB RSS / PSS** (device available memory 6.96 → 5.26 GB while loaded). On a 10.9 GB phone
  that is workable for a bench and a real constraint for a product; a `GGML_CPU_REPACK=OFF` build
  is the obvious follow-up measurement (speed vs. the 1.45 GB), not done in the smoke. Accuracy
  reading unchanged: the ten outputs are the same proposals modulo whitespace and two fields that
  moved toward the expectation (C now carries `text = Folder対応完了`, G no longer guesses
  `documentKind`); C still lacks `targetName`. Each build is deterministic with itself (A, D, E, H,
  J byte-identical to the baseline; the others differ in whitespace tokens only, plus those two
  fields) — the kernels' rounding differs, so numbers across builds are never mixed. Thermal
  status reached 3 again inside the 3-minute run.
- **Lifecycle (dotprod build, `gemma4-e2b-q4km-dp-lifecycle`, 02:0x JST, thermal 0 at start):**
  load → 5 generations → unload → 30 s idle, then reload → 2 generations → unload → idle, × 3.
  Every generation ended at EOG with valid JSON (9 / 9). Loads 3,280 / 3,762 / 3,383 ms. RSS
  before load 120 MB; after load 5.15 / 5.17 / 5.17 GB (PSS 5.08 / 5.10 / 5.10 GB, native heap
  2.27 / 2.26 / 2.26 GB); generation peak (HWM) 5.25 / 5.51 / 5.51 GB; **after unload 333.5 /
  335.7 / 337.8 MB (PSS 264 / 267 / 269 MB, native heap 0.8 / 0 / 0 MB); after idle identical.**
  The loaded footprint does not grow across reloads (5.15 → 5.17 → 5.17 GB) and the unloaded
  remainder moves by ≈ 2 MB per cycle (0.6 %) — process housekeeping, not a retained model; no
  monotonic growth of the kind the brief stops on. TTFT medians 11.1 / 10.9 / 11.0 s, generation
  7.2 / 7.2 / 7.2 tok/s across the cycles. Thermal 0 → 3 within the first cycle again (battery
  36.4 → 39.3 °C); no crash, no ANR, no abnormal memory growth.

### 10.1 Technical smoke verdict — Gemma 4 E2B-it Q4_K_M

| Pass condition (the brief) | Result |
|---|---|
| loads on the S20 (arm64) | ✓ 8.3 s cold, 3.3–3.8 s warm |
| Japanese in and out | ✓ Japanese prompts; Japanese field values verbatim from the input |
| GBNF ≥ 5 / 5 | ✓ 10 / 10 (both builds) + 9 / 9 (lifecycle) |
| JSON parse | ✓ 29 / 29 |
| schema validation | ✓ 29 / 29 |
| no major semantic breakdown | ✓ intent 10 / 10; no invented `result_N`, date or DELETE-like intent; no invented diary event. Weaknesses: `query` echoed on every intent case; one APPEND lost its `targetName` |
| thinking disabled | ✓ template default false, rendered with `enable_thinking=false`, no `<|think|>` / thought channel in the prompt or the output |
| unload | ✓ RSS 5.0 GB → 0.33 GB, native heap → 0 |
| reload × 3 | ✓ |
| no crash / ANR | ✓ |
| no abnormal RSS growth per reload | ✓ +2 MB per cycle on the unloaded remainder, loaded footprint flat |

**Passed as a technical smoke.** Two facts qualify it for the comparison: (1) **memory** — with the
dotprod kernels the loaded process is ≈ 5.0–5.3 GB RSS on a 10.9 GB phone (≈ 3.1–3.7 GB on the
baseline build without repack; a `GGML_CPU_REPACK=OFF` build is the follow-up measurement); (2)
**heat** — three minutes of continuous inference reach thermal SEVERE while charging. Neither
blocks the accuracy run; both go into §11 as they are.



### 10.2 Gemma 4 E2B-it: ggml weight repack ON vs OFF (S20, 2026-09-19 02:0x–02:2x JST)

One short A/B on the same five cases (smoke A–E), same dotprod + fp16 build otherwise, same
conditions (ctx 4096, batch 512, threads 4, temp 0, tethered and charging, thermal 0 at start;
`gemma4-e2b-q4km-repackon` / `-repackoff`). `-PllmRepack=false` builds with `GGML_CPU_REPACK=OFF`.

| | A: repack ON (`REPACK = 1`) | B: repack OFF |
|---|---|---|
| model load (warm page cache) | 3,239 ms | **1,075 ms** (nothing to repack) |
| TTFT, ≈ 450-token prompt (median) | **10,712 ms** (≈ 42 tok/s prompt processing) | 23,227 ms (≈ 19 tok/s) — 2.2× slower |
| TTFT, ≈ 215-token prompt (case E) | 5,291 ms | 11,955 ms |
| generation tok/s (median) | **7.02** | 6.23 (−11 %) |
| loaded RSS / PSS | 5.15 GB / 5.07 GB (native heap 2.27 GB) | **3.75 GB / 3.66 GB (native heap 0.81 GB)** — −1.4 GB |
| generation peak (VmHWM) | 5.38 GB | 4.07 GB |
| after unload RSS / PSS | 339 MB / 264 MB | 341 MB / 265 MB |
| device available RAM while loaded | 5.23 GB | 6.73 GB |
| thermal start → peak → end; battery | 0 → 2 → 2; 35.7 → 37.5 °C (run 90 s) | 0 → 3 → 3; 36.7 → 39.0 °C (run 154 s) |
| outputs | the same five proposals as the 10-case dotprod run | identical intents / fields to A (whitespace differs) |

Reading: repack buys ≈ 2.2× on prompt processing (the number a chat command waits on) and 11 %
on generation for ≈ 1.4 GB of extra resident memory; without it the model still sits at
≈ 3.7 GB RSS and a command costs ≈ 23 s before the first token. The thermal picture does not
improve without repack — the slower run spends longer at full load and reached SEVERE where the
faster one stopped at MODERATE. (These runs were tethered and charging; charging heat is part of
it, and the formal thermal comparison is the later untethered fixed workload — the "SEVERE in
≈ 3 min while charging" fact stands as such.)

**Position of Gemma 4 E2B-it after the A/B, by the human's rule of 2026-09-19 ("RSS drops but
the speed loss is large, or thermal barely changes → an accuracy-comparison candidate that is heavy
as a phone default"):** it stays in the accuracy comparison; as a phone-default candidate it is heavy — either ≈ 5 GB resident for ≈ 11 s
TTFT, or ≈ 3.7 GB for ≈ 23 s. No winner or loser is declared yet.


- **Ministral lifecycle (`ministral3-3b-q4km-lifecycle`, thermal 0 at start):** 9 / 9 generations
  valid; loads 3,957 / 4,035 / 4,062 ms; after load 4.76 / 4.78 / 4.78 GB RSS (PSS 4.71 / 4.73 /
  4.73, native heap 2.89 / 2.88 / 2.88 GB); peak 4.97 GB; **after unload 215 → 298 → 299 MB** (PSS
  169 → 252 → 253 MB, native heap 4 / 1 / 0 MB) — one 84 MB step after the first cycle (the
  process's own growth: the first unload leaves the JIT / graphics state the later ones already
  have), then flat (+1 MB); loaded footprint flat. No crash, no ANR. Thermal 0 → 3 within the
  first cycle; battery 36.3 → 40.3 °C.

### 10.3 Technical comparison — Gemma 4 E2B-it vs Ministral 3 3B (S20, same conditions)

Same S20, same bench build (llama.cpp b11039, armv8.2-a + dotprod + fp16, repack ON), same 10
cases, prompt, grammar, ctx 4096 / batch 512 / threads 4 / temp 0 / max 256, tethered and
charging, thermal 0 at the start of every run. Only the chat template differs.

| | Gemma 4 E2B-it Q4_K_M | Ministral 3 3B Instruct 2512 Q4_K_M |
|---|---|---|
| GGUF | bartowski (community, imatrix), 3.46 GB | **Mistral AI official**, 2.15 GB |
| parameters | 2.3 B effective / 5.1 B with embeddings | 3.4 B (+ 0.4 B vision, unused) |
| licence | Apache 2.0 | Apache 2.0 |
| thinking | template default off; rendered with `enable_thinking=false` | none (Instruct) |
| tokens for the same ≈ 450-token-in-Gemma prompt | 447 | 553 (Tekken tokenizes Japanese longer) |
| load (warm) | 3.2–3.8 s | 4.0–4.3 s |
| TTFT, full intent prompt (median) | **11.2 s** (≈ 40 tok/s) | 18.6 s (≈ 30 tok/s; more tokens, denser model) |
| TTFT, template prompt | 5.3–5.6 s | 8.7–9.3 s |
| generation (median) | **7.0 tok/s** | 5.9 tok/s |
| loaded RSS / PSS / native heap | 5.0–5.2 GB / 5.0 GB / 2.27 GB | 4.8–4.9 GB / 4.8 GB / 2.89 GB |
| loaded RSS with repack OFF | 3.75 GB (TTFT 23 s) | not measured |
| after unload RSS | ≈ 0.34 GB | 0.21 → 0.30 GB |
| reload × 3 | flat, +2 MB / cycle | flat after one 84 MB step |
| thermal (charging, tethered) | 0 → 3 in ≈ 3 min | 0 → 3 in ≈ 4 min |
| grammar / JSON / schema | 10 / 10 / **10** | 10 / 10 / **9** (F: fabricated `result_2`) |
| intent exact | 10 / 10 | 10 / 10 |
| exact cases (every field) | 2 (B, I) | 2 (G, J) |
| invented `result_N` | **0** | **1 (F)** |
| DELETE-like intent (D) | none | none |
| invented diary event (E) | none | none |
| case C (APPEND target + text) | text ✓, target ✗, no question | text ✓, target ✗, asks (`missingFields`) |
| `query` over-fill | on 8 of 8 intent cases | on 2 of 8 |
| other misses | — | H `documentKind` null, I `reflection` dropped, loose `missingFields` (B, D) |
| tokenizer / template / Japanese | clean | clean (no mojibake, no special-token residue) |

Both pass the technical smoke as a mechanism (load, Japanese, grammar, JSON, unload / reload,
no crash). Neither is light: with the fast kernels both sit near 5 GB resident on this phone and
both reach thermal SEVERE within minutes while charging; Gemma is faster per token and per
prompt, Ministral's file is 38 % smaller but its repacked working set is not. On the ten cases
Gemma's faults are systematic and mild (an echoed `query`), Ministral's are fewer but include the
one class the brief forbids (a reference invented from nothing). Ten cases decide nothing; the
190-case run does. No winner is declared.


- **Qwen lifecycle (`qwen3-4b-2507-q4km-lifecycle`, thermal 0 at start):** 9 / 9 generations
  valid; loads 4,627 / 4,615 / 4,630 ms; after load 5.60 / 5.62 / 5.62 GB RSS (PSS 5.55 / 5.57 /
  5.57, native heap 3.45 / 3.45 / 3.44 GB); peak 5.82 GB; **after unload 286 → 195 → 197 MB**
  (PSS 241 → 149 → 151, native heap 4 / 1 / 0 MB) — the remainder *fell* after the first cycle,
  then flat; loaded footprint flat; native heap released every time. No crash, no ANR. Thermal
  0 → 3 within the first cycle; battery 35.7 → 40.8 °C.

### 10.4 Three-model technical smoke comparison (S20, 2026-09-19, same conditions)

Same S20, bench build (llama.cpp b11039, armv8.2-a + dotprod + fp16, ggml repack ON), 10 cases,
prompt, grammar, scorer, ctx 4096 / batch 512 / threads 4 / temp 0 / max 256 / CPU, tethered and
charging, thermal 0 at the start of every run. Only the chat templates differ. Ten cases decide
nothing; this table is the technical baseline for the 190-case run, not a ranking.

| | Gemma 4 E2B-it | Ministral 3 3B Instruct 2512 | Qwen3-4B-Instruct-2507 |
|---|---|---|---|
| GGUF (Q4_K_M) | bartowski, community, 3.46 GB | **Mistral AI official**, 2.15 GB | bartowski, community (no official), 2.50 GB |
| parameters | 2.3 B eff. / 5.1 B with embeddings | 3.4 B (+0.4 B vision unused) | 4.0 B |
| licence | Apache 2.0 | Apache 2.0 | Apache 2.0 |
| thinking | off by template default, rendered `enable_thinking=false` | none (Instruct) | none (non-thinking checkpoint); no `<think>` seen |
| prompt tokens (same prompt) | 447 | 553 | 510 |
| load, warm / cold | 3.2–3.8 s / 8.3 s | 4.0–4.3 s | 4.6 s / 10.2 s |
| TTFT, intent prompt (median) | **11.2 s** (≈ 40 tok/s) | 18.6 s (≈ 30 tok/s) | 19.6 s (≈ 26 tok/s) |
| TTFT, template prompt | 5.3–5.6 s | 8.7–9.3 s | 9.3–10.3 s |
| generation (median) | **7.0 tok/s** | 5.9 tok/s | 5.2 tok/s |
| loaded RSS / PSS | 5.0–5.2 / 5.0 GB | 4.8–4.9 / 4.8 GB | 5.0–5.6 / 5.2–5.6 GB |
| native heap loaded | 2.27 GB | 2.89 GB | 3.45 GB |
| generation peak (HWM) | 5.26–5.51 GB | 4.96–4.97 GB | 5.42–5.82 GB |
| after unload RSS (cycles) | 334 → 336 → 338 MB | 215 → 298 → 299 MB | 286 → 195 → 197 MB |
| reload × 3 | flat | flat after one step | flat, remainder fell |
| time to thermal SEVERE (charging) | ≈ 3 min | ≈ 4 min | ≈ 4 min |
| grammar / JSON / schema | 10 / 10 / 10 | 10 / 10 / **9** | 10 / 10 / 10 |
| intent accuracy | 10 / 10 | 10 / 10 | 10 / 10 |
| **required-field complete** (7 judged) | 6 / 7 (C) | 5 / 7 (C, F — F "complete" only via a fabricated ref, counted incomplete) | 5 / 7 (C, F — F incomplete because it *asks*, the wanted outcome) |
| semantic valid (strict) | 2 / 10 (B, I) | 2 / 10 (G, J) | 2 / 10 (F, J) |
| hallucination count | 8 | 3 | 3 |
| `query` over-fill (of 8 intent cases) | 7 | 2 | 2 |
| invented `result_N` | 0 | **1 (F)** | 0 |
| DELETE-like intent (D) | none | none | none |
| invented diary event (E) | none | none | none |
| case C: intent / text / targetName | ✓ / ✓ / ✗ (silent) | ✓ / ✓ / ✗ (asks ref+name) | ✓ / ✓ / ✗ (asks name) |
| case F | OPEN, made-up `targetName` | OPEN, **made-up `result_2`** | OPEN, asks for `targetRef` ✓ |
| other misses | — | H kind null, I reflection dropped, loose `missingFields` | A `昨日的日記` (Chinese 的), B verb kept in query, I reflection dropped |
| Japanese / tokenizer | clean | clean | clean except the one mixed-script value (A) |

What the ten cases say, and no more: all three hold the schema and the six intents under GBNF on
the S20; none extracts the APPEND target in C; Gemma is the fastest and the most verbose, Ministral
the only one to invent a reference, Qwen the only one to answer F as the brief wants and the only
one to slip a Chinese character into a Japanese value. All three sit at ≈ 5 GB resident with the
fast kernels and reach thermal SEVERE within minutes while charging. The 190-case run, untethered
thermal and the repack question decide the rest.


### 10.5 Short performance runs under the tightened protocol (S20, 2026-09-19 18:25–18:43 JST)

15 fixed cases (`performance_subset.txt`, the first five of intent / param / date), prompt v1,
repack ON, each run from a fresh process after the runner had seen **thermal status 0**; the
2-second watcher armed (no case was cut — neither run reached SEVERE inside its ≈ 7–8 minutes).
**Condition: USB-tethered and charging** (the operator could not unplug; the untethered repeat
and the battery runs follow when the phone is unplugged, §8.1). "Warm" load: the file had been
in the page cache (loaded earlier the same session).

| | Qwen3-4B-Instruct-2507 | Ministral 3 3B Instruct 2512 |
|---|---|---|
| model load (warm) | 4,640 ms | 4,421 ms |
| model load (cold, from §10) | 10,217 ms | 4,261 ms |
| TTFT median / p90 (≈ 510 / 555 prompt tokens) | **19,709 / 21,074 ms** | **18,148 / 18,811 ms** |
| generation tok/s median | 5.49 | 6.30 |
| tok/s by thermal status 0 / 1 / 2 | 6.22 / 5.65 / 5.36 | 7.19 / 6.53 / 6.23 |
| RSS loaded / peak (HWM) / PSS | 5.62 / 5.84 / 5.57 GB | 4.74 / 4.95 / 4.72 GB |
| native heap loaded | 3.45 GB | 2.89 GB |
| RSS after unload (native heap) | 224 MB (0) | 202 MB (0) |
| thermal start → end (15 cases, ≈ 8 / 7 min) | 0 → 2; battery 29.2 → 34.4 °C | 0 → 2; battery 34.4 → 34.3 °C (started warm from the previous run) |
| thermal stops / cases completed | 0 / 15 | 0 / 15 |

Reading: on this phone Ministral is ≈ 8 % faster to the first token and ≈ 15 % faster per token
than Qwen3-4B at ≈ 0.9 GB less resident memory; both lose ≈ 14 % generation speed between
thermal 0 and 2 within eight minutes while charging. Neither reached SEVERE in a 15-case run
started cold — the 10-case smokes earlier did, because they started from a warmer battery.


### 10.6 Qwen3.5-4B on the S20 (technical smoke + lifecycle, guarded, 18:45–19:00 JST) and the four-model runtime table

Qwen3.5-4B technical smoke: loads (5.5 s warm), Japanese clean, **no `<think>` in any output**
(rendered prompt ends `<|im_start|>assistant\n<think>\n\n</think>\n\n`), grammar 10 / 10, JSON
10 / 10, schema 9 / 10 (**F: fabricated `result_2`** — on the Mac too, 10 / 10 identical
proposals across executors), intent 10 / 10, C asks for the target with `text` ✓; lifecycle × 3
clean (loads 5.4–5.5 s, after-unload RSS 261 → 265 → 269 MB, native heap 0 each time), no
crash / ANR, no thermal stop within the runs (0 → 2).

| S20, repack ON, tethered | Gemma 4 E2B-it | Ministral 3 3B | Qwen3-4B-2507 | **Qwen3.5-4B** |
|---|---|---|---|---|
| file | 3.46 GB | **2.15 GB** | 2.50 GB | 3.01 GB |
| prompt tokens (same prompt) | 447 | 553 | 510 | 444 |
| load, warm | 3.2–3.8 s | 4.0–4.4 s | 4.6 s | 5.4–5.5 s |
| TTFT median (intent prompt) | **11.2 s** | 18.1–18.6 s | 19.6–19.7 s | 19.1 s |
| TTFT p90 (15-case run) | — | 18.8 s | 21.1 s | — |
| generation tok/s (median) | **7.0** | 5.9–6.3 | 5.2–5.5 | 4.8 |
| RSS loaded / peak | 5.0–5.2 / 5.4–5.5 GB | **4.7–4.9 / 5.0 GB** | 5.6 / 5.8 GB | **6.0 / 6.3 GB** |
| native heap loaded | 2.27 GB | 2.89 GB | 3.45 GB | 4.00 GB |
| RSS after unload | ≈ 0.34 GB | ≈ 0.20–0.30 GB | ≈ 0.20–0.29 GB | ≈ 0.26 GB |
| device available RAM while loaded | 5.2 GB | 4.7 GB | 3.9–4.0 GB | ≈ 3.4 GB |
| reload × 3 | flat | flat | flat | flat (+4 MB / cycle) |
| tok/s at thermal 0 → 2 (15-case run) | — | 7.19 → 6.23 | 6.22 → 5.36 | — |
| formal battery set (human) | reference only | **yes** | **yes** | not yet |

Qwen3.5-4B is the heaviest of the four on this phone (6.0–6.3 GB resident, 3.4 GB left to the
system) and the slowest per token; its file is 3.01 GB.

### 10.7 Gemma 3 1B Instruct on the S20 (guarded smoke, lifecycle, cold performance; 19:38–19:51 JST)

The mechanism holds: loads in **1.0 s**, Japanese in and out, grammar 10 / 10, JSON 10 / 10, no
thinking, lifecycle × 3 clean (loads 1.0–1.1 s, native heap 0 after every unload; the unloaded
remainder stepped 246 → 413 → 414 MB once, then flat), no crash / ANR, no thermal stop — the
first model whose 15-case performance run never left thermal status 0–1.

| S20, repack ON, tethered, thermal 0 at start | Gemma 3 1B | Ministral 3 3B | Qwen3-4B-2507 |
|---|---|---|---|
| file | **0.81 GB** | 2.15 GB | 2.50 GB |
| model load (warm) | **1.0 s** | 4.4 s | 4.6 s |
| TTFT median / p90 (15-case run) | **8.1 / 8.5 s** | 18.1 / 18.8 s | 19.7 / 21.1 s |
| generation tok/s median | **9.5** (9.9 at thermal 0, 9.3 at 1) | 6.3 | 5.5 |
| RSS loaded / peak / PSS | **1.54 / 1.84 / 1.50 GB** | 4.74 / 4.95 / 4.72 GB | 5.62 / 5.84 / 5.57 GB |
| native heap loaded | **1.22 GB** | 2.89 GB | 3.45 GB |
| RSS after unload | 0.25–0.42 GB | 0.20 GB | 0.22 GB |
| thermal, 15 cases (≈ 4.5 min) | **0 → 1**; battery 31.8 → 33.1 °C | 0 → 2 | 0 → 2 |
| generation cut at `maxTokens` | 1 of 15 (date_003: 256 tokens, the grammar never closed) | 0 | 0 |

Footprint: ≈ 3.7–4.1 GB less resident than the two formal candidates, a third of their TTFT,
≈ 1.6× their generation speed, and no thermal climb inside a 15-case run. What it does with that
budget is §9.6.


### 10.8 Untethered battery / thermal runs (S20, 2026-09-19 night, prompt v1, repack ON)

Protocol §8.1: armed over USB, the person unplugged the cable, the run began at
`charging = false` and thermal status 0, the fixed `battery_subset.txt` sequence in its fixed order
for ≤ 10 minutes or until SEVERE (the 2-second watcher ends the run within one token), then the
app cooled down on battery and the cable was re-plugged for the pull. Screen on (the bench),
nothing else touched.

**Qwen3-4B-Instruct-2507** (`results/qwen3-4b-2507-q4km/qwen3-4b-2507-q4km-battery.*`):

| | value |
|---|---|
| start → end battery | 100 % → 96 % (95 % after the 2-minute cool-down) |
| battery delta | **4 %** in 5.5 min; charge counter −123,420 µAh (≈ 123 mAh) |
| elapsed (run) | 329.8 s = 5.50 min; ended by **SEVERE** at the start of intent 11 |
| completed intents | **10** (all 10 valid, EOG) |
| battery delta / minute | **0.73 %/min** |
| battery delta / intent | **0.40 %/intent** |
| temperature start / peak / end | 28.2 °C → 36.0 °C (36.6 °C during cool-down) → 35.5 °C at thermal 0 |
| thermal status trajectory | 0 (cases 1–4) → 1 (5–7) → 2 (8–10) → **3 at 5.5 min** → back to 0 after 2 min idle |
| TTFT first → last case | 17.3 s → 19.8 s (median 19.0 s, p90 19.8 s) |
| generation tok/s by thermal 0 / 1 / 2 | 6.33 / 5.91 / 5.51 (median 5.97) |
| RSS loaded / peak / PSS | 5.59 / 5.81 / 5.56 GB; native heap 3.45 GB |
| RSS after unload | 187 MB (native heap 0) |
| crash / ANR | none |

**Ministral 3 3B Instruct 2512** (`results/ministral3-3b-q4km/ministral3-3b-q4km-battery.*`; armed after the
phone had cooled to thermal 0 and 30.2 °C, 15 min after the Qwen run):

| | value |
|---|---|
| start → end battery | 100 % → 97 % |
| battery delta | **3 %** in 3.9 min; charge counter −76,230 µAh (≈ 76 mAh) |
| elapsed (run) | 236.0 s = 3.93 min; ended by **SEVERE** at the start of intent 9 |
| completed intents | **8** (all valid, EOG) |
| battery delta / minute | **0.76 %/min** |
| battery delta / intent | **0.375 %/intent** |
| temperature start / peak / end | 30.0 °C → 36.0 °C (36.6 °C during cool-down) → 35.1 °C at thermal 0 |
| thermal status trajectory | 0 (cases 1–3) → 1 (4–5) → 2 (6–8) → **3 at 3.9 min** → 0 after 2 min idle |
| TTFT first → last case | 15.6 s → 18.3 s (median 17.6 s, p90 18.3 s) |
| generation tok/s by thermal 0 / 1 / 2 | 7.40 / 6.93 / 6.57 (median 6.93) |
| RSS loaded / peak / PSS | 4.75 / 4.96 / 4.72 GB; native heap 2.89 GB |
| RSS after unload | 205 MB (native heap 0) |
| crash / ANR | none |

**Qwen3-4B-2507 vs Ministral 3 3B, untethered (same sequence, same order, same protocol):**

| | Qwen3-4B-Instruct-2507 | Ministral 3 3B Instruct 2512 |
|---|---|---|
| start temperature / thermal | 28.2 °C / 0 | 30.0 °C / 0 (1.8 °C warmer at start) |
| time to SEVERE | **5.5 min** | 3.9 min |
| completed intents before SEVERE | **10** | 8 |
| battery delta | 4 % | 3 % |
| battery delta / minute | **0.73 %/min** | 0.76 %/min |
| battery delta / intent | 0.40 %/intent | **0.375 %/intent** |
| charge counter / intent | 12.3 mAh | **9.5 mAh** |
| thermal rise (start → peak) | +7.8 °C in 5.5 min (1.4 °C/min) | +6.0 °C in 3.9 min (1.5 °C/min) |
| TTFT median / p90 | 19.0 / 19.8 s | **17.6 / 18.3 s** |
| tok/s at thermal 0 → 2 (throttling) | 6.33 → 5.51 (−13 %) | **7.40 → 6.57** (−11 %) |
| RSS loaded / peak | 5.59 / 5.81 GB | **4.75 / 4.96 GB** |
| after unload | 187 MB | 205 MB |
| proposals in the run (scorer) | 10 / 10 valid, executable 7 / 10, 0 major | 8 / 8 valid, executable 6 / 8, 0 major |
| crash / ANR | none | none |

Reading: per minute the two draw the same battery (≈ 0.75 %/min, i.e. the S20 at full 4-core
load); per intent Ministral is ≈ 6–23 % cheaper (fewer tokens per second of wall time is offset by
shorter TTFT) and ≈ 0.85 GB lighter, Qwen ran 1.6 min longer before SEVERE from a cooler start.
Both hit SEVERE well inside 10 minutes of continuous inference on battery: on this phone,
sustained back-to-back commands are not a usage pattern either model supports — single commands
with idle time between them are. Neither the 10-minute window nor the 50-intent sequence was
reached by either; "minutes / intents to SEVERE" is the measurement, as the brief said.


## 11. Candidates carried into the next phase

Facts only; the choice is the human's. State at 2026-09-19 19:00 JST:

- **Formal S20 performance / battery set (human decision):** Qwen3-4B-Instruct-2507 (the
  completeness / executability side) and Ministral 3 3B Instruct 2512 (the safety side). Short cold
  performance runs §10.5; **untethered battery / thermal runs done 2026-09-19 night, §10.8**.
- **Gemma 4 E2B-it:** kept as the accuracy / technical reference (fastest on the S20; 4 major, 7
  unsafe, 105 over-fills); not in the battery set.
- **Qwen3.5-4B (challenger):** technical smoke passed, thinking confirmed off, 190 cases run.
  Against Qwen3-4B-2507 it improves the safety counters by one (0 major, 0 invented facts) and
  the diary category, and it is worse on executable rate (77 vs 84 %), completeness (80 vs 89 %),
  intent (86 vs 95 %), unsafe writes (5 vs 2), over-fill (50 vs 24), dates (43 vs 73 % executable),
  and it is the heaviest and slowest on the S20 (6.0–6.3 GB, 4.8 tok/s). Under the human's rule
  for promotion ("one of executable / completeness / major-or-hallucination improved, without a
  large loss elsewhere") the improvement is one major failure and one invented fact, the losses
  are large on the executability side. Recorded for the human's decision; **not added to the
  battery set by the agent.**

## 12. Design issue carried out of Phase 0 — case C (human decision 2026-09-19)

「MemoRipple開発に『Folder対応完了』を追記して」: none of the models tried (Gemma 4 E2B, Ministral 3 3B,
Qwen3-4B-2507, Qwen3.5-4B, with prompt v1 and the one revision v2) reliably extracts
`targetName = MemoRipple開発` next to `text = Folder対応完了`; each gets the intent and the body and
either leaves the target null, asks for it, or (Qwen3-4B on v2, once) reads it. This is recorded
**as a design issue separate from model selection**: the future Resolver is not expected to
leave target extraction to the LLM alone — a deterministic parser for the 「X に … を追記 / X を開いて」
shapes, search-candidate generation from the document titles, the Resolver's own matching and an
ambiguity confirmation step are the likely complements. **Not implemented in Phase 0.**

## 13. Model characterization (human decision 2026-09-19 evening; no ranking)

| class | model | position |
|---|---|---|
| **Balanced / completeness-oriented** | Qwen3-4B-Instruct-2507 | executable 84 %, completeness 89 %, intent 95 %; 1 major, 2 unsafe; 5.6 GB, 19.7 s TTFT, 5.5 tok/s. **Phase 1 candidate.** |
| **Safety-oriented** | Ministral 3 3B Instruct 2512 | 0 major, 0 invented facts, unknown-safe 100 %; executable 75 %; 4.7 GB, 18.1 s TTFT, 6.3 tok/s. **Phase 1 candidate.** |
| **Technical / accuracy reference** | Gemma 4 E2B-it | fastest of the ≥ 2 B models (7.0 tok/s, 11 s TTFT); 4 major, 7 unsafe, 105 over-fills; 5.0 GB. Not a standard candidate. |
| **Experimental challenger** | Qwen3.5-4B | 0 major but executable 77 %, dates 43 %, 6.0 GB, 4.8 tok/s. Does not replace Qwen3-4B; no battery slot. |
| **Ultra-light runtime reference** (not a standard, fallback or low-memory operational model) | Gemma 3 1B Instruct | **Recorded strengths:** 0.81 GB file, ≈ 1.5 GB loaded / ≈ 1.8 GB peak RSS, ≈ 1 s load, ≈ 8 s TTFT, ≈ 9.5 tok/s, thermal 0 → 1, stable lifecycle, grammar / JSON generation possible. **Recorded problems:** intent 54 %, executable 22 %, strict 3 %, unknown-safe 45 %, 55 invented references, 48 invented facts, 148 major failures, 20 unsafe writes, 115 over-fills. **Verdict: light, but without the semantic reliability an IntentProposal needs.** |
| **Ultra-light operational class** | *empty* | Not filled by force. To be reconsidered when a stronger 1–2 B model, a task-specific fine-tune, a split constrained classifier + extractor, or a deterministic-parser combination is available. |

**No further models in Phase 0** (human decision): the five give the trend. Phase 1 candidates
narrow to **Qwen3-4B-Instruct-2507 and Ministral 3 3B**; which is the default is decided after
their untethered battery / thermal runs — the only remaining Phase 0 measurement, taken when a
person can unplug the S20 (§8.1). No additional S20 stress until then.

## 14. Design finding carried into the product (human decision 2026-09-19)

Gemma 3 1B made it unambiguous: **fixing the syntax with GBNF does not stop semantic
hallucination** — a grammar-valid, JSON-valid, schema-shaped proposal can still name a result
that was never shown, a date that was never said, a fact that never happened. Therefore the
product pipeline is multi-stage and **grammar-valid / JSON-valid never grants execution**:

```
LLM → IntentProposal → Semantic Validator → Resolver → Preview / Confirmation → DocumentAccess
```

The Semantic Validator checks the proposal against what the user said and what was shown (the
scorer's rules are its first specification: refs only from the shown list, tokens not dates,
required fields present, no unsupported operation, no invented text); the Resolver maps names to
documents (with the case-C helpers of §12); the Preview / Confirmation step is the user's veto
before any DocumentAccess call. None of this is implemented in Phase 0.

## 15. Phase 0 final report (2026-09-19, feature/llm-phase0)

Everything below is measured; nothing here ships. **Accuracy: Mac execution** (the same pinned
llama.cpp b11039, CPU, parity §7.2). **Performance / RAM / battery / thermal: S20 execution**
(SC-51A, Android 13, repack ON, protocol §8 / §8.1).

**1. Five-model accuracy (190 golden cases, prompt v1, GBNF; §9.4–§9.6).** Grammar / JSON / schema
100 % for the four ≥ 2 B models, 98 / 98 / 69 % for Gemma 3 1B. Intent 92 / 91 / 95 / 86 / 54 %,
executable 76 / 75 / 84 / 77 / 22 %, strict 18 / 45 / 46 / 39 / 3 %, major failures 4 / 0 / 1 / 0 / 148
(Gemma 4 E2B / Ministral 3 3B / Qwen3-4B-2507 / Qwen3.5-4B / Gemma 3 1B).

**2. Five-model memory / performance (S20; §10.5–§10.7).** Loaded RSS 5.0–5.2 / 4.7 / 5.6 / 6.0 /
1.5 GB; TTFT 11.2 / 18.1 / 19.7 / 19.1 / 8.1 s; 7.0 / 6.3 / 5.5 / 4.8 / 9.5 tok/s; every model
unloads to ≈ 0.2–0.4 GB and reloads cleanly.

**3. Qwen3-4B vs Ministral, untethered battery / thermal (§10.8).** ≈ 0.75 %/min for both;
0.40 vs 0.375 %/intent (12.3 vs 9.5 mAh/intent); SEVERE at 5.5 vs 3.9 min (10 vs 8 intents;
Ministral started 1.8 °C warmer); TTFT 19.0 vs 17.6 s; throttling −13 % vs −11 %; RSS 5.6 vs 4.7 GB.

**4. Model classes (§13):** Balanced / completeness — Qwen3-4B-Instruct-2507; Safety-oriented —
Ministral 3 3B; Technical / accuracy reference — Gemma 4 E2B-it; Experimental challenger —
Qwen3.5-4B; Ultra-light runtime reference only — Gemma 3 1B; ultra-light operational class empty.

**5. Phase 1 candidates:** **Qwen3-4B-Instruct-2507 and Ministral 3 3B**, as two characters the user
may choose between, not a single winner. On the S20 numbers Ministral is the lighter, cooler-per-
intent and faster-to-first-token of the two and the clean one on every safety counter; Qwen3-4B
returns an executable, correctly filled proposal on 84 % of cases against 75 %. A default, if
one is set, is a product decision the human takes on these facts; the agent recommends no
default here.

**6. Unresolved design issues:** case C target extraction (§12) belongs to the Resolver side —
deterministic parser, candidate generation, ambiguity confirmation; the mandatory multi-stage
pipeline (§14); sustained inference heat on the S20 (§10.8) — commands must be occasional, and a
loaded model sits at ≈ 5 GB (repack ON) or ≈ 3.7 GB (repack OFF, 2× slower prompt processing,
§10.2); the untethered runs never reached 10 minutes.

**7.** Gemma 3 1B is an ultra-light runtime reference only (§13). **8.** Qwen3.5-4B is an
experimental challenger, not a replacement for Qwen3-4B (§9.5). **9.** Case C `targetName` is a
Resolver-side design issue, not a model-selection criterion (§12). **10.** GBNF guarantees
syntax only; semantic safety comes from the validator / resolver / confirmation stages (§14).

Not in Phase 0 and not started: Chat UI integration, model download UI, production JNI,
DocumentAccess execution, automatic writes, the default choice, product use of Gemma 3 1B, new
model evaluations.
