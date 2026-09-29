# Test Infrastructure Memory Leak Audit (2026-09-18)

> **Status (2026-09-18 midday):** fix B approved by the human and applied on the branch
> (HANDOFF §16.25) — `LaunchedEffect(darkTheme)` posting `enableEdgeToEdge` to the main thread,
> guarded by `!isFinishing && !isDestroyed`. Tripwires 9 / 9 GREEN; measurements in §16.25 and
> `docs/TEST_INFRASTRUCTURE.md`. The rest of this document is the audit as written before the fix.

Branch `feature/test-memory-audit`, base main `0fe540c`. No product feature in this round. The
instrumentation process (`io.github.cragcoffee.memoripple` hosting `MemoRippleTestRunner`) had
been growing its Java heap by ≈ 1 MB per test until one `am instrument` run of the whole suite
died with `OutOfMemoryError` near the 320th test (HANDOFF §16.20, `docs/TEST_INFRASTRUCTURE.md`).
This document records how the leak was reproduced, what retained the memory, the cause, the
options, and what it means for the device gate.

**Verdict in one paragraph.** The leak is real and test-process-wide, but the retained memory is
not a product data structure: every `MainActivity` the tests launch stays alive after its
`ActivityScenario` closes because the first frame of `MainActivity` attaches its
`AndroidComposeView` **twice** and detaches it **once**. Two Compose statics assume attach /
detach symmetry — the compose-root registry `AndroidComposeView.composeViews` and the snapshot
apply-observer list behind `OwnerSnapshotObserver.startObserving` — and each keeps one handle per
lifetime, and through it the whole destroyed activity (≈ 1 MB per test). The double attach is
triggered by `MainActivity`'s `SideEffect { enableEdgeToEdge(...) }` running inside the first
traversal. Moving the call to a `LaunchedEffect` is *not* enough: under the Compose test host the
frame clock runs effects inside that same traversal, so the screen tests (all on
`createAndroidComposeRule<MainActivity>()`) still leak. Posting the call to the main thread
(`window.decorView.post { enableEdgeToEdge(...) }`) takes it out of the traversal in both shapes:
one attach, one detach, registry flat. A test-side guard that pruned the registry was tried and
**rejected** (registry empty, heap slope unchanged — the second static still held the activity).
The fix therefore has to stop the double attach, which is a two-line product change in
`MainActivity.kt` — **proposed below, not applied on this branch**; its effect was measured in a
throw-away worktree.

## 1. 再現条件 (reproduction)

- Pixel_10 emulator, API 36 (`google/sdk_gphone64_arm64/emu64a:16/BE4B.251210.005`, sdk_full 36.1),
  `dalvik.vm.heapgrowthlimit` 192 MB, no `largeHeap`.
- Compose BOM 2025.12.01 → `androidx.compose.ui:ui-android` **1.10.0**;
  `androidx.activity:activity-compose` 1.12.2; `androidx.test:core` `ActivityScenario`.
- Any test that launches `MainActivity` — `createAndroidComposeRule<MainActivity>()` in the screen
  tests, `ActivityScenario.launch(MainActivity::class.java)` in the audit test — leaks one
  activity per test. Tests without an activity (unit-style instrumentation, Room, backup) do not.
- Minimal reproduction: `ComposeViewRegistryAuditTest.theStaticComposeViewListDoesNotGrowAcrossActivityLifetimes`:
  four launch / close rounds leave `[1, 2, 3, 4]` entries in the registry
  (logcat `round N: during=N+2 after=N+1`, every `after` entry detached).

## 2. Heap 推移 (heap over a run)

`dumpsys meminfo` Java Heap PSS sampled every 30 s beside `am instrument` (`pm clear` first).

| run | build | Java heap |
|---|---|---|
| whole suite, one process (322) | `711161c` journal branch, 2026-09-17 | 9 → 82 → 108 → 146 → 196 → 203 MB, OOM at test 319 |
| `MainActivityNavigationTest` (73) | main `bb75c9d` | 27 → 107 MB |
| `MainActivityNavigationTest` (73) | `711161c` | 26 → 100 MB |
| `OutlinerScreenInstrumentationTest` (30) | `711161c` | 22 → 39 MB |
| per group, this branch, unguarded | `0fe540c` | small (51): flat; overlay (10): 19 MB; outliner (44): 20 → 50 MB; nav (73): 24 → 110 MB |
| `MainActivityNavigationTest` (73), registry pruner (rejected) | `0fe540c` + test code | 26 → 44 → 82 → 100 MB — unchanged |
| `MainActivityNavigationTest` (73), plain `LaunchedEffect` (scratch worktree, insufficient) | `0fe540c` + `LaunchedEffect` | 30 → 44 → 94 → 98 MB — unchanged |
| `MainActivityNavigationTest` (73), **fix B (posted call), scratch worktree** | `0fe540c` + `LaunchedEffect` + `post` | 22 → 24 → 23 → 20 → 23 → 27 MB through test 44, then 47 → 61 → 68 → 59 MB (peak 68, falling again by test 67) |
| whole suite, one process, **fix B (posted call), scratch worktree** | `0fe540c` + `LaunchedEffect` + `post` | **OK (334), 4 assumption skips, 45 → 66 MB, flat (samples 45–66 MB across all 330 finished tests), no OOM** |

Heap dumps of the test process during `MainActivityNavigationTest` (`am dumpheap`, converted with
`hprof-conv`): unguarded **early** after 6 tests = 64 MB, **late** after 60 tests = 142 MB; with
the (rejected) registry pruner, early = 71 MB, late after 40 tests = 98 MB; with the plain
`LaunchedEffect`, early = 67 MB, late after 40 tests = 104 MB (46 `MainActivity`, 46 `ComposeView`,
46 `PhoneWindow`, 5 `ViewRootImpl`).

## 3. main / feature 比較

The slope is identical on main and on every feature branch measured (27 → 107 vs 26 → 100 MB
over the same 73 tests), on classes the branches did not touch, and it is proportional to the
number of activity launches, not to the feature under test. The Journal round merely pushed one
process lifetime (317 → 322 tests) past the growth limit. **Not a feature regression.**

## 4. Retained object 候補 (what the late dump holds)

Instance counts in the unguarded late dump (after 60 tests, 61 `ActivityScenario`s so far):

| class | count | note |
|---|---|---|
| `MainActivity` | 67 | one live, the rest destroyed |
| `AndroidComposeView` | 69 | |
| `androidx.compose.runtime.Recomposer` | 61 | one per retained activity |
| `ActivityScenario` | 61 | |
| `ComposeRootRegistry` (test host) | 61 | |
| `LifecycleRegistry` | 273 | |
| `PhoneWindow` / `DecorView` | 68 / 62 | windows retained |
| `ViewRootImpl` | **5** | windows *were* removed — the retention is not a live window |

Shortest strong paths from a GC root to a destroyed activity (reverse BFS over the dump, class
statics included, `WeakReference.referent` edges skipped). Unguarded dump:

```
class androidx.compose.ui.platform.AndroidComposeView
  --static composeViews--> androidx.collection.MutableObjectList --content--> Object[]
  --> AndroidComposeView (detached) --mContext--> MainActivity (destroyed)
```

With that registry pruned after every activity destruction (the rejected guard), the next path
appears — 45 destroyed activities still reachable after 40 tests:

```
class androidx.compose.runtime.snapshots.SnapshotKt
  --static applyObservers--> ArrayList --> Object[]
  --> SnapshotStateObserver$$ExternalSyntheticLambda1 --f$0--> SnapshotStateObserver
  --onChangedExecutor--> AndroidComposeView$snapshotObserver$1 --this$0--> AndroidComposeView (detached)
  --mContext--> MainActivity (destroyed)
```

With the plain `LaunchedEffect` build (the screen tests still leaking), one shortest path per
destroyed activity, grouped: **41 of 45** through `AndroidComposeView.composeViews` again
(`--mContext-->`), 4 through the same registry via `_rootView → DecorView → PhoneWindow.mCallback`,
the 46th being the live activity held by the running `ActivityScenarioRule`. In every dump the
root is the attach / detach asymmetry; nothing else holds a destroyed activity.

Everything else the suspects list named — `NavHostController`, view models, DataStore / Room
collectors, coroutine scopes, overlay service, bitmaps, `TestRule` / `ActivityScenario`, the
Compose test host's `ComposeRootRegistry` — hangs *below* that activity; none of them is
reachable from a root on its own. The overlay service and the outliner fold state were checked
and are not roots.

## 5. 原因候補 → 原因 (cause)

1. **Where the entries come from.** Compose UI 1.10.0 `AndroidComposeView` (bytecode read from
   the Gradle cache): `onAttachedToWindow` → `Companion.addNotificationForSysPropsChange` →
   `composeViews.add(view)` (SDK > 28), and `snapshotObserver.startObserving()`;
   `onDetachedFromWindow` → `composeViews.remove(view)` and `stopObserving()`. The add is **not
   guarded against duplicates** (same in 1.10.1 and 1.12.0 — a BOM bump does not help, and BOM
   2026.08.00 needs compileSdk 37 anyway). `SnapshotStateObserver.start()` does
   `applyUnsubscribe = Snapshot.registerApplyObserver(…)`, so a second start **overwrites the
   first handle** and `stop()` disposes only the last one; the first observer stays in the static
   `SnapshotKt.applyObservers` list. Any other per-attach registration behaves the same way.
2. **Why there are two attaches.** Tracing every attach of `MainActivity`'s `AndroidComposeView`
   (`ComposeViewRegistryAuditTest.mainActivityAttachAndDetachCountsOverOneLifetime`): two
   `onViewAttachedToWindow`, both from `ViewRootImpl.performTraversals` (line 3667 in this build,
   the first-frame block that dispatches `host.dispatchAttachedToWindow`), then one
   `onViewDetachedFromWindow` at `handleDestroyActivity`. The framework re-ran the first-frame
   attach dispatch.
3. **What triggers the re-run.** Bisected with a bare `ComponentActivity` (test-only,
   `EdgeToEdgeAttachProbeTest`), content installed before the window exists like `MainActivity`:

   | content | attach / detach | registry after close |
   |---|---|---|
   | plain `FrameLayout`, with or without `enableEdgeToEdge()` in onCreate | 1 / 1 | 0 |
   | bare Compose `Text`, with or without `enableEdgeToEdge()` in onCreate | 1 / 1 | 0 |
   | `enableEdgeToEdge()` called twice in onCreate | 1 / 1 | 0 |
   | Compose + `MemoRippleTheme` | 1 / 1 | 0 |
   | Compose + `LaunchedEffect { enableEdgeToEdge(style, style) }` | 1 / 1 | 0 |
   | Compose + **`SideEffect { enableEdgeToEdge(style, style) }`** | **2 / 1** | **1 (leaks)** |
   | `SideEffect` with only one of its steps (decorFits, status / nav bar colour, contrast flags, light bars, cutout mode, a `setAttributes` no-op, `decorView.addView`) | 1 / 1 | 0 |

   `MainActivity.kt` does exactly the leaking shape: `enableEdgeToEdge()` in `onCreate`, then
   `SideEffect { enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style) }` so the
   bar icons follow the in-app 外観. The `SideEffect` runs during the first composition, which
   happens inside the first traversal (the `ComposeView` composes in its own `onAttachedToWindow`);
   `enableEdgeToEdge` on API 35+ (`EdgeToEdgeApi35`) then changes decor-fits, adds its scrim
   views to the decor and flips the contrast / appearance flags in one go, and the framework
   dispatches the attach pass a second time. No single one of those steps does it alone.
4. **Why the product never notices.** In the app there is one `MainActivity` per process; the
   extra entries cost one stale activity until the process ends. Only a process that creates and
   destroys hundreds of activities — the instrumentation run — accumulates.

5. **Why `LaunchedEffect` is not enough.** Under a plain `ActivityScenario` the effect runs
   from the `AndroidUiDispatcher` frame callback after the traversal and the root attaches once
   (`EdgeToEdgeAttachProbeTest`). Under `createAndroidComposeRule` the Compose test host drives the
   `Recomposer` with its own frame clock and pumps it while the first traversal is still on the
   stack, so the effect body — and `enableEdgeToEdge` — again runs inside the traversal: the
   `ComposeRuleRegistryProbeTest` trace in the scratch worktree shows two attaches from
   `performTraversals` per lifetime with the `LaunchedEffect` build, one with the posted build, one
   with the call removed. Posting to the main thread (`window.decorView.post`) is a separate
   `Handler` message and cannot run inside a traversal under either clock.

So: the *trigger* is in product code (`MainActivity`'s in-composition `enableEdgeToEdge`), the
*retention* is in Compose statics that assume attach / detach symmetry, and the *asymmetry* is
framework behaviour on this emulator image. It is a test-infrastructure problem with a
product-side root; no user data or product state is involved.

## 6. 修正案 (options)

**A. Test-side guard — tried, rejected, removed.** A test-only `ActivityLifecycleCallbacks`
that removed detached views from `AndroidComposeView.composeViews` after each destruction
emptied the registry (the tripwire went green) but the nav-class heap slope was unchanged
(26 → 100 MB), because the snapshot apply-observer list still held every activity. Extending
the guard there would mean reflecting into Compose runtime's private observer list and
disposing other components' handles — too fragile and too deep for test code, and it would
mask the tripwire that shows the asymmetry. Not kept.

**B. Product-side fix — proposed, not applied (needs the human's decision).**
In `MainActivity.kt`, the in-composition bar-style update

```kotlin
SideEffect {
    val style = if (darkTheme) SystemBarStyle.dark(TRANSPARENT) else SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}
```

becomes

```kotlin
LaunchedEffect(darkTheme) {
    val style = …same…
    window.decorView.post { enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style) }
}
```

(import `LaunchedEffect` instead of `SideEffect`; the call is posted to the main thread). Measured
in a scratch worktree carrying exactly this diff, under both the plain `ActivityScenario` and the
`createAndroidComposeRule` shape: the compose root attaches once, the registry and the observer
list are symmetric, and the heap rows marked "fix B (posted call)" in §2 are the result. The
plain `LaunchedEffect` without the `post` was measured too and is **insufficient** (§5 item 5).
Behavioural difference: the in-app bar style is applied on the next main-thread message after
the composition that computed it instead of during that composition; the first frame still
carries the `onCreate` `enableEdgeToEdge()` styles (OS night mode), exactly as today, and on a
theme change the update follows recomposition by one message. `post` on the decor view is
dropped if the window is already gone, which is the right thing for a destroyed activity. No visible change is expected; one glance
at the status / navigation bar icons on the S20 in Light-on-dark-OS and Dark-on-light-OS after
the change is the only manual check. No Room / Backup / manifest / dependency impact.

**C. Library — not available.** Compose UI 1.10.1 and 1.12.0 still add unguarded and
`SnapshotStateObserver.start` still overwrites its handle; nothing to bump to. An upstream
report (double `dispatchAttachedToWindow` of the first frame after `enableEdgeToEdge` inside
the first composition on API 36; Compose statics assuming symmetry) would be the long-term fix.

**Tests on this branch (test code only).** `ComposeViewRegistryAuditTest` — four `MainActivity`
lifetimes under `ActivityScenario` must leave the registry where it started, plus a logged
attach / detach count for one lifetime; **RED on the current product** (`[1, 2, 3, 4]` left
behind). `ComposeRuleRegistryTripwireTest` — three `createAndroidComposeRule<MainActivity>()`
tests must all see the same registry size (the shape the screen tests use; RED today, and it
stays RED with a plain `LaunchedEffect`). `EdgeToEdgeAttachProbeTest` — bare Compose content and
the posted shape attach once (asserted); the plain `LaunchedEffect` and `SideEffect` shapes are
logged. All GREEN with the posted fix in the scratch worktree. Together ≈ 20 s.

## 7. Gate への影響 (impact on the gate)

- Until B is applied, nothing changes: the two-process `scripts/device-suite.sh` stays the
  gate, and `ComposeViewRegistryAuditTest` is a known RED on this branch (the RED half of the
  fix's TDD pair, not a flake — it must not be rerun into green).
- With B applied, one process lifetime no longer accumulates activities (§2, fix-B rows). The
  gate's evidence quality is otherwise unchanged: the same tests, no timeouts or assertions
  touched.
- Cost: nine short tests added as tripwires (≈ 20 s).

## 8. 2プロセス分割を継続すべきか (keep the two-process split?)

**Keep it until the fix lands on main; retire it right after.** With the posted fix the whole
suite (334 tests) ran in one process at 45–66 MB of Java heap, a third of the 192 MB growth
limit, with the same 4 assumption skips and no OOM — the split exists only for this leak. Proposed
sequence: the human approves the `MainActivity` change → it lands on this branch as the GREEN
half of the tripwires' TDD pair → merge → one single-process run on main as the gate evidence →
`docs/TEST_INFRASTRUCTURE.md` and the project's test rules go back to "one process, all green" and
`scripts/device-suite.sh` stays in the tree as a fallback (it still works; it just is no longer
required). If the fix is declined, the split stays and Chat / LLM tests will push each half
toward the limit in turn (≈ 160 tests of headroom per half today).

## Tooling used (scratch, not committed)

- `dump-session.sh`: runs the nav class, `am dumpheap` early / late, pulls and converts.
- `HprofHisto.java`: class histogram and referrer histogram of an HPROF; `HprofPath.java`: reverse
  BFS from GC roots (including class statics) to instances of a class, skipping `.referent` edges.
- `meminfo-sampler.sh`: Java Heap PSS every 30 s beside `am instrument`.
These live in the session scratchpad; the Android Studio Profiler was not needed.
