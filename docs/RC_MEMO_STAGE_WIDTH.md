# MemoRipple 1.0 RC — Memo Comment Stage full width

Date: 2026-09-06 (JST). Base: `9666e2702a85e0287bde38c970f60113ad874377`.
Status: local UI-only change; no commit or push.

## Scope

The Memo editor's outer content column has no horizontal padding. Title / metadata,
the body for each display mode, and the unchanged bottom controls own their existing
24dp gutters. Only the STAGE frame takes the entire available viewport width, with
its existing 16:9 ratio. The former over-measure / negative-placement layout modifier
is removed. Note Reader's implementation is unchanged.

No change to comment speed, the 4-second crossing, size rules, lane allocation,
collision avoidance, global / per-comment speed, long-comment readability, Room,
Backup, DataStore, permissions, Overlay, or Note Reader behavior.

## Reproduction and evidence

The reported side gaps did not reproduce on the tested baseline: the old bleed
modifier already rendered edge to edge on SC-51A in both the normal and compact
portrait viewports. This is the explicitly requested structural correction, not
evidence that the original symptom was reproduced and eliminated.

Physical evidence uses SC-51A / Android 13 / API 33, 420dpi, and a disposable
`io.github.cragcoffee.memoripple.stageqa` debug package. The suffix is injected by
a repository-external Gradle init script; applicationId / namespace in the project
remain unchanged. The installed Play app and its data are not test targets.

Normal viewport: 1080×2400. Compact viewport: 1080×1400 (temporary display-size
override, restored after capture). Light and Dark both pass the stage-edge,
16:9, reading-gutter, and playback assertions before and after the change.

| Physical normal viewport bounds (px) | Before | After |
| --- | --- | --- |
| Stage left / right | 0 / 1080 | 0 / 1080 |
| Stage top / bottom | 345 / 953 | 345 / 953 |
| Title left / right | 63 / 1017 | 63 / 1017 |
| Body left / right | 63 / 1017 | 63 / 1017 |
| Body top / bottom | 974 / 2211 | 974 / 2211 |
| Playback settings bounds | (652,2263)–(778,2389) | (652,2263)–(778,2389) |

All recorded bounds also match in the compact viewport. Normal Light / Dark idle
screens match pixel for pixel in the app-content crop. The gray background reaches
both x=0 and x=1079. Flow captures use the Compose test clock; screenshots of moving
frames can differ by capture timing and are not golden images.

Evidence and the final verification report are delivered outside the repository in
the requesting task's `outputs/stage-width/` directory.

## Validation

- `testDebugUnitTest`: 731 tests, 0 failures / errors / skips.
- `assembleDebug`, `compileDebugAndroidTestKotlin`, `lintDebug`: PASS. Lint reports
  0 errors, 29 warnings, 1 hint. No release artifact was regenerated.
- New Light / Dark stage-width tests: PASS on physical SC-51A, both normal and
  compact viewports, before and after. Final restored implementation: PASS again.
- A temporary padded-stage mutation fails with `Stage left edge expected 0.0 but
  was 63.0`; the mutation was removed. The test guards the reported failure mode.
- Physical OverlayFoundationInstrumentationTest: 6/6 PASS with special access on
  the disposable QA package. Production permissions are unchanged.
- Full API 36 run in the visual-reference viewport (1080×2400, 420dpi, Japanese,
  fontScale 1, animations off): 221 total; 216 pass, 3 fail, 2 permission skips.
  The two permission-dependent overlay tests passed separately on the physical device.

The three reference-viewport failures were reproduced on the unmodified baseline:

1. `ToolbarOrderInstrumentationTest.theTwoRowBarNamesItsRowsAndAUnitCrossesBetweenThem`:
   lower heading is not composed at the queried scroll position in the shorter
   2400px viewport. It passes at the emulator's native 2424px height. This is a
   pre-existing viewport-sensitive test, not a Memo editor regression.
2. `VisualRegressionTest.flowExpressionMatchesLightGolden`.
3. `VisualRegressionTest.fixedExpressionMatchesDarkGolden`.

In isolated before / after runs, the golden failures have identical diff images:
38,338 differing pixels for Light and 40,120 for Dark, out of 2,332,800. The initial
full run had additional transient system UI in its captures. Neither the goldens
nor the tested expression sheet were changed. A green normal suite does not mean
these reference-only comparisons are green; baseline golden reconciliation remains
separate work.

Final clean native-viewport suite (1080×2424): **221 total, 217 pass, 0 fail,
4 intentional skips** (2 overlay permission preconditions, 2 non-reference goldens).
The real overlay cases passed separately on SC-51A. The reference-only golden
failures above remain known baseline discrepancies, not silently counted as passes.

Repeated-run fixture isolation: the first native-viewport rerun inherited
`週報のかたち` templates saved by the preceding suite in QA-only DataStore. The
existing `settingsListsTheTemplates` test clears Room but expects the separate
template store to be empty; it timed out. The known fixture text was confirmed
in the disposable QA package. That contaminated run was stopped, the QA-only
package data was cleared, and a fresh native-viewport suite passed. No
production DataStore code or existing assertion was changed.

Cleanup: the disposable QA app and test packages were removed from both devices;
only generated fixture data was deleted, with screenshots / logs preserved outside
the repository. Physical display size and emulator display / animation settings were
restored to their recorded initial values. The regular debug app / test APKs were
rebuilt with the unchanged production applicationId (no QA suffix). Final diff check
and added-file secret-pattern review pass. Only the editor, new width test, and this
record are dirty; the index is empty and HEAD is unchanged.

## Audit findings and KEEP decision (2026-09-06)

**DECISION = KEEP (human-approved). PRODUCTION_CHANGE_JUSTIFIED = YES — as a
structural correction, not as a fix for the reported side-gap symptom.** The
change must never be recorded as "fixed the Memo Stage side margins": on every
build since `c420093` the full-screen stage already rendered edge to edge.

**History.** Before `c420093` (2026-09-01 00:21 JST, "The stage takes its frame:
16:9, edge to edge") the stage was a padded two-fifths-height panel with visible
side gutters — any screenshot from before that commit shows the reported gaps.
`c420093` achieved edge-to-edge with a negative-bleed layout modifier: the stage
was measured at `maxWidth + 2×screenHorizontalPadding` and placed at `-bleed/2`,
overhanging its padded parent on both sides. This change removes that hack and
instead lifts the horizontal padding off the outer column, so the stage owns the
full width naturally while title / metadata, each display mode's body, and the
bottom controls keep their existing gutters individually.

**Split-mode structural rationale.** `SplitWorkspaceScaffold`'s weighted panes
are plain unclipped `Box`es. Compose draws past layout bounds unless clipped, so
in the horizontal (side-by-side) split with STAGE mode active, the old bleed drew
the stage background ~24dp past the pane edge on both sides — over the divider
and into the reference pane. The new structure constrains the stage to
`fillMaxWidth` inside its pane, making overdraw impossible by construction; a
static check confirms no `layout {` / negative-placement code remains in
`MemoEditorScreen.kt`. (In the vertical split and full screen, the old bleed
happened to land exactly on the window edge, which is why the main path is
pixel-identical.) No new large product test was added for this; the shipped
`MemoStageWidthInstrumentationTest` guards the full-screen edge-to-edge contract
implementation-agnostically.

**Golden classification.** `GOLDEN_MISMATCH_CAUSED_BY_STAGE_CHANGE = NO`. The
two failing goldens exercise `CommentExpressionSheet` via `setContent`, never
touching the Memo editor's layout; their diffs are identical before and after
this change. The baselines were recorded 2026-08-30 (`74d5cba`), and the sheet's
live preview renders through `CommentRenderer`, which received several visual
changes afterwards (`cf9c46f` bold letters with a soft edge, `dfed2d3` long
comment readability, `2712af2` playback smoothness). The mismatch went unnoticed
because the reference-environment guard (API 36, 1080×2400, 420dpi, ja) never
holds in routine emulator runs, so the comparisons skip silently. The goldens were
NOT updated in the stage change itself; the refresh ran as its own reviewed step.

**Golden refresh (2026-09-06): GOLDEN_BASELINE_REFRESH_PENDING = NO,
GOLDEN_BASELINE_REFRESH_COMPLETE = YES.** The human reviewed old/current/diff
renders and approved the current rendering (stable mismatches of 38,338 and
40,120 px, every differing region traced to intended commits: `cf9c46f` bold
letters with a soft edge, `8c406a3` gray palette addition, and the related
size-glyph restyle; no clipping, no layout shift, Light/Dark contrast normal).
The two baselines under `visual-baselines/api36-1080x2400-420dpi-ja/` were then
replaced with renders produced in the genuine reference environment, and both
tests were re-run there with the guard satisfied: PASS by actual pixel
comparison, proven by the failing comparison in the identical environment
immediately before the replacement. Threshold, reference guard, and test code
are unchanged; the causal relation to the stage change remains NONE.

**Version note.** This checkpoint does not change versionCode (still 2 /
1.0.0). The next Play upload bumps to versionCode 3 in the separate Closed Test
RC build preparation phase.

## 1.1 candidate — record only

Drag-and-drop memo reorder is a candidate for MemoRipple 1.1. It is not implemented
or included in this 1.0 RC change. Gesture behavior, accessible alternatives, and any
persistence contract require a separate design / implementation decision.
