# Test infrastructure notes

## The full device gate: one process (since 2026-09-18)

**Normal gate — the whole suite in one instrumentation process** on the Pixel_10 / API 36
emulator, `pm clear` first, the current app and test APKs installed with `adb -s` (never a
Gradle `install*` / `connected*` task with phones attached):

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm clear io.github.cragcoffee.memoripple
adb -s emulator-5554 shell am instrument -w -r \
  io.github.cragcoffee.memoripple.test/io.github.cragcoffee.memoripple.MemoRippleTestRunner
```

Pass = `OK (n tests)` with only the known assumption skips (the two visual goldens on a
2424-px-tall emulator, the two speech-voice assumptions). Known Compose test-host flakes are
classified by an isolated rerun ×3, never by loosening the test (the project's test rules).

**Diagnostics / fallback — the split script.** `scripts/device-suite.sh` runs every test class
in two halves by sorted fully-qualified class name (the first ⌈n/2⌉ in A, the rest in B), each
half in its own instrumentation process, `pm clear` before each; it refuses a physical phone
unless `ALLOW_PHONE=1`. It is **no longer the normal gate**. Use it when a single-process run
dies or misbehaves as a whole — an `OutOfMemoryError`, a crash that takes the process with it, a
hang — to tell a process-wide problem from a failing test: if both halves are green the fault is
in the process lifetime (a new leak, an exhausted resource), not in a test.

```bash
scripts/device-suite.sh emulator-5554        # A.log, B.log, summary.txt under build/device-suite
```

### History: the test-process heap leak (2026-09-17 → 2026-09-18)

The instrumentation process used to grow its Java heap by ≈ 1 MB per test and never give it
back within one `am instrument` run; its growth limit on the emulator is ≈ 192 MB
(`dalvik.vm.heapgrowthlimit`, no `largeHeap`), so a single run of the whole suite died with
`OutOfMemoryError` around the 319th test once the suite held ≈ 320 tests. The split script was
the interim gate from 2026-09-17 (HANDOFF §16.20 / §16.21).

The audit (`docs/TEST_MEMORY_AUDIT.md`, HANDOFF §16.24) found the cause: the first frame of
`MainActivity` attached its `AndroidComposeView` twice and detached it once, because
`enableEdgeToEdge(...)` ran inside the first traversal from a `SideEffect`; two Compose statics
that assume one attach per detach (`AndroidComposeView.composeViews`, the snapshot
apply-observer list) then kept every finished activity alive. The fix (HANDOFF §16.25) posts the
system-bar update to the main thread from a `LaunchedEffect(darkTheme)`. Tripwires that fail if
the asymmetry returns: `ComposeViewRegistryAuditTest`, `ComposeRuleRegistryTripwireTest`,
`EdgeToEdgeAttachProbeTest`.

`dumpsys meminfo` Java Heap PSS every 30 s:

| run | build | Java heap |
|---|---|---|
| whole suite, one process (322) | `711161c`, before the fix | 9 → 82 → 108 → 146 → 196 → 203 MB, OOM at test 319 |
| `MainActivityNavigationTest` (73) | main `bb75c9d`, before | 27 → 107 MB, never falling |
| `MainActivityNavigationTest` (73) | fix on `feature/test-memory-audit` | 18 → 27 → 18 MB through test 44, then a 56 → 67 → 61 MB working set that falls again |
| whole suite, one process (334) | fix on `feature/test-memory-audit` | **OK (334), 4 assumption skips, 47 → 68 MB flat, no OOM** |
| whole suite, one process (334) | main `c946ae4` after the merge | 334 run, 4 assumption skips, 1 known test-host flake (isolated ×3 green), 47–68 MB flat, no OOM — the split retired from the normal gate |

## Other rules (unchanged)

The project's device rules still apply: one API 36 emulator, `adb -s <serial>` only, never a Gradle
`install*` / `connected*` task with phones attached, no `Thread.sleep` / timeout stretching /
assertion deletion to "fix" a flake, goldens are a product decision.
