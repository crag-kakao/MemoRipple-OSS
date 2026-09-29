# メモホームのページが文字サイズの変更でノートに移る — 修正（2026-09-26）

> 状態：**実装済み、マージ前で停止。** ブランチ`fix/memo-pager-zero-width`（base：統合版候補`integration/v1-feature-freeze` `b6dd603`）。人間の決定（2026-09-26）：既知の実機の不具合として修正する — 新機能ではない。古いブランチ`claude/vigilant-wilson-9f7dbe`（2026-09-15）はマージもcherry-pickもせず、その考え方を今のコードへ最小の差分で当てた。Room 29 / Backup 23 / versionCode 4 / 1.1.0 / Compose BOM `2025.12.01`は変えていない。

## 1. 不具合

メモホーム（メモ / アウトライナー / ノートの3ページの`HorizontalPager`）で**メモ**のページを開いたまま端末の文字サイズを変えて戻ると、**ノート**のページに移っている。Galaxy S20（SC-51A、API 33、One UI 5.1）で5回中5回（2026-09-15、古いブランチの記録）。Pixel_10エミュレータでは一度も起きない。回転・ダークモードでは起きない。

## 2. 原因

S20は文字サイズの変更で作り直した画面を、本当の大きさの前に一度だけ**幅0 × 高さ0**で測る。Compose Foundation 1.10.0の`measurePager`は幅0のページを「画面外」として最後のページまで進め、復元したばかりのページを上書きする（最後のページだけが見えている、と答える）。2026-09-26の監査で、今の統合版にも同じpager・同じCompose・ページを戻し直す処理なし — 原因はそのまま残っていた。

## 3. 修正

- `MemoViewModePages.kt`：`internal fun Modifier.skipZeroWidthMeasure()` — 幅0（`constraints.maxWidth == 0`）の計算のときだけ、pagerを測らずに何も置かない（`layout(0, minHeight)`）。それ以外の計算はそのまま通す（同じconstraintsで測り、その大きさで(0, 0)に置く）。
- `MemoListScreen.kt`：メモホームの`HorizontalPager`の`modifier`に`.skipZeroWidthMeasure()`を1つ足した。他のpager・画面には使わない（汎用のutilityにしない）。
- 変わらないもの：幅のある計算と配置、選ばれているタブの意味、保存されるページ、pagerのアニメーションとスワイプ、下のナビゲーションの再タップ（§16.97）、フォルダの状態、スクロールの状態、Composeの依存。
- 古いブランチとの差：コードの行は同じ（コメントだけ違う）。テストは作り直した（§4）。古いブランチの`MemoHomeFontScaleInstrumentationTest`（shellで端末の文字サイズを変える）は採らない — エミュレータでは不具合が起きず、S20では端末の設定そのものを変えるため（人間の決定）。古いブランチのHANDOFFの記録（当時の状態）も持ち込まない。

## 4. テスト（`MemoPagerZeroWidthInstrumentationTest`）

実機の幅0の計算は作れないので、メモホームのpagerと同じ形（ページ数`MEMO_VIEW_MODES`、同じkey、同じmodifier）を300 dp → 0 dp → 300 dpで測り直す。どの端末でも決定的。

| テスト | 何もしないmodifier（RED） | 修正後 |
|---|---|---|
| メモ → 幅0 → 戻る：メモのまま | **RED**（ノートになる） | GREEN |
| アウトライナー → 幅0 → 戻る：アウトライナーのまま | **RED**（ノートになる） | GREEN |
| ノート → 幅0 → 戻る：ノートのまま | 通る（最後のページ） | GREEN |
| 通常の幅でスワイプ：メモ → アウトライナー → ノート → アウトライナー、幅0のあとも → メモ | **RED**（幅0のあとアウトライナーにいない） | GREEN |

修正後は3回とも4 / 4。近くのクラス（`MemoViewModeTabsWidthTest`、`OutlinerHomeInstrumentationTest`、`BottomNavReselectInstrumentationTest`、`FolderInstrumentationTest`、`FolderNavigatorInstrumentationTest`、`FolderNavigatorStateInstrumentationTest`、`WallTopBarColorInstrumentationTest`、`MainActivityNavigationTest`）108 / 108。

## 5. ゲートとS20

**ゲート（`zerowidth`、commit済みのclean tree `12c6cf2`）**：clean build、unit **1619 / 0**（JVMのテストは足していない）、lint **0 errors**、device suite（1プロセス）**OK 706**（693通過・13 skip・0 failures）＝統合版の702 ＋ このクラスの4。統合版の実行にあった(class, test)はすべてこの実行にもある（欠けたもの0）。Room 29 / Backup 23、versionCode 4 / 1.1.0、GGUF 0、Compose BOM・gradleの変更なし。

**S20**（在来install、DBを先にcopy、**端末の文字サイズは変えていない** — 0.9のまま）：メモホームのタブをタップ（アウトライナー → ノート → メモ、どれもそのページ）、スワイプ（左・左・右・右でメモ → アウトライナー → ノート → アウトライナー → メモ）、下のナビゲーションの再タップ（アウトライナーのページでメモ → アウトライナーのまま、QAフォルダQの一覧を下まで → メモ → 一番上、すべて › P › Qのまま）。18のテーブルすべてcopyと同じ、integrity ok。文字サイズを変える再現確認は、人間の決定により行っていない（同じS20で5 / 5の記録と、原因が残っていたことを根拠にする）。
