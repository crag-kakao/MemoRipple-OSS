# Visual Regression

Phase 8Aでは既存のCompose UI Testと`UiAutomation.takeScreenshot()`だけを使い、Comment Expression Sheetの軽量な画像回帰を追加した。Modal Bottom Sheetは別Windowへ描画されるため、Compose nodeの`captureToImage()`ではなく画面全体を取得し、System barを除いた領域を比較する。

## Reference environment

- Android Emulator API 36
- 1080 × 2400 px
- 420 dpi
- Font scale 1.0
- Locale `ja-JP`
- Portrait
- Window / transition / animator scale 0

App localeは実行前に固定する。

```sh
adb shell cmd locale set-app-locales io.github.cragcoffee.memoripple \
  --user 0 --locales ja-JP
```

Testはこの条件を満たさない端末では`Assume`によりskipする。Samsung等の物理端末は手動Visual QAに使うが、pixel comparisonの合否基準にはしない。

Goldenは`app/src/androidTest/assets/visual-baselines/api36-1080x2400-420dpi-ja/`に置く。現在の対象は、情報量が多いLight/FLOWと条件分岐を確認できるDark/FIXEDのComment Expression Sheetである。Fixture text、Appearance、Motion、Themeを固定し、Sheet transitionがidleになった後で撮影する。現在日時、random ID、動くPlaybackには依存しない。

## Comparison

- 各channelの差が12以下のpixelは許容する。
- 変化pixel比率は0.2%以下を許容する。
- 失敗時はapp external filesの`visual-regression-diffs`へ赤いdiff PNGを出力する。
- 通常のTestはGoldenを上書きしない。

通常比較:

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.cragcoffee.memoripple.VisualRegressionTest
```

意図的にGoldenを再記録する場合だけ、review前提で次を実行する。

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.cragcoffee.memoripple.VisualRegressionTest \
  -Pandroid.testInstrumentationRunnerArguments.recordVisualGoldens=true
```

記録結果をassetsへ反映する操作は自動化しない。更新理由とdiffを確認してから明示的に置き換える。

初回から全画面×全Themeを対象にしない。Memo List、Diary Calendar、Future Reveal等への拡張は、deterministic fixtureと画面Windowを安定して固定できるものから追加する。Playback motionはprogressを完全固定できる仕組みを導入するまでGolden対象外とし、Unit Testと実機QAで担保する。

## Small-screen strategy

Phase 8Bの専用small-screen profileは同じAPI 36 Emulatorを720×1280 px、320 dpi（360×640 dp）、font scale 1.0、
ja-JP、Portrait、animation scale 0へ固定する。Font scale 1.5＋Landscape（640×360 dp相当）はAction reachabilityを確認する
high-constraint条件として使う。

small-screen GoldenはPhase 8Bでは追加しない。Comment Expression Sheetは全項目へ到達するまでscroll量が多く、Calendar／Editorは
content位置がfixtureとscroll stateに依存するため、2枚の既存Goldenよりflake要因が大きい。小画面の主目的はpixel一致ではなく、
Memo List／Editor、Comment/Future Expression、Diary、Settings、Archive/Trash、Overlay SetupのActionが消えず、scrollして到達できる
ことの確認とする。将来追加する場合は、全画面を増やさず、固定scroll stateを持つComment Expression末尾またはDiary Future actionの
1〜2画面に限定する。
