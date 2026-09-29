# MemoRipple OSS License Inventory

MemoRippleのユーザー向けOSS表示と、Release runtime dependencyの棚卸し手順を記録する。この文書は法的判断や各ライセンス本文の代替ではない。

## Reviewed release inventory

正本は`:app:releaseRuntimeClasspath`のGradle resolution resultである。現在（B07・2026-09-04）の固定結果は**202 components（direct 19、transitive 183）**。Phase 8F時点では169（direct 17、transitive 152）だった。Build plugin、KAPT compiler、Unit Test、Android Test、debug-only toolingはRelease runtime一覧から分離し、ユーザー向け画面へ含めない。

解決済みcomponentとdirect/transitive区分は`app/src/main/assets/oss/release-runtime-components.txt`へ保存する。表示上は同じprojectかつ同じlicenseのartifact群だけをまとめ、licenseが異なるDataStore external Protocol BuffersはAndroidXと分離する。

## License provenance

各componentはローカルGradle cacheの解決済みartifactを用いて次の順で確認した。

1. component POMの`licenses` metadata
2. licenseを持たないPOMは解決済みparent POM
3. AAR/JAR内の`META-INF` license
4. Google Play services AAR内の`third_party_licenses.json`と`third_party_licenses.txt`

結果はApache License 2.0が161 components、BSD 3-Clauseが1 component、Android Software Development Kit LicenseのPOM metadataを持つGoogle Play servicesが7 componentsだった。Google Play services本体をOSSと表示せず、そのAARに同梱された第三者OSS通知だけを別entryで表示する。本文内容は保持し、改行、末尾空白、Gitが競合markerと誤認する見出しseparatorだけを決定的に正規化する。

解決済みartifactに独立した`NOTICE` fileは見つからなかったため、存在しないNOTICEは作成しない。AARに同梱されたGoogleの第三者通知bundleは専用assetへ保持する。Guava ListenableFutureはcomponent POMのparentであるGuava parent POMとsource headerを確認した。

## Offline assets

- `app/src/main/assets/oss/oss_licenses.json`: display grouping、全runtime component、provenance、非OSS SDK条件の監査情報
- `app/src/main/assets/oss/licenses/apache-2.0.txt`
- `app/src/main/assets/oss/licenses/bsd-3-clause.txt`
- `app/src/main/assets/oss/licenses/google-play-services-third-party.txt`

全assetはRelease APK/AABへ含まれ、一覧・詳細画面はnetworkやWebViewを使わず読み込む。並び順とJSON formattingは固定し、通常build/testはcommitted assetを上書きしない。

## Explicit update workflow

Dependency変更時だけ次を明示的に実行する。

```text
./gradlew :app:updateReleaseOssInventory
python3 tools/generate_oss_assets.py --write
```

生成差分、POM metadata、artifact license/noticeをhuman reviewした後、次で整合を確認する。

```text
./gradlew :app:verifyReleaseOssInventory
python3 tools/generate_oss_assets.py
```

Gradle verificationはRelease graphとcommitted inventoryのdriftを検出する。Python verificationはlocal Gradle cacheから同じcatalog/license assetを再構築し、欠落・変更・余分なlicense fileを検出する。未確認のlicense metadataやdisplay mappingが現れた場合は生成を失敗させ、推測で分類しない。

## Release gate

公開候補ではRelease graphを再解決し、生成・review・verificationを行う。copyright/notice表示、licenseの解釈、配布条件は公開前のrelease/legal reviewで最終確認する。

## Native runtime（Local AI、2026-09-27）

`libmemoripple_llm.so` と `libc++_shared.so` はGradleのgraphの外（`app/src/main/cpp`、pinned llama.cpp submodule、NDK）なので、フォントと同じく手で確認した `STATIC_LIBRARIES` の項目として表示する。

- **llama.cpp / ggml**（MIT、© 2023-2026 The ggml authors）：submodule `llmbench/src/main/cpp/llama.cpp` の revision `b11039-2-g4fea119de`（`4fea119de30f`）。CMakeは `llama-common` / `llama` / `ggml` をlinkする。リンクされた `.so` の記号で確認した中身：`nlohmann::`（nlohmann/json 3.12.0、MIT、`licenses/LICENSE-jsonhpp`）と `jinja::`（llama.cpp自身のコード）はある。`httplib::`（cpp-httplib）、`subprocess_`（sheredom）、download / HFキャッシュ、llguidance（OFF）、llamafile sgemm（`GGML_LLAMAFILE=OFF`）、KleidiAI（OFF）は**無い**。`common/base64.hpp` はpublic domain（Unlicense）で、使われていない。llama.cppの中の「MIT、他のprojectから取り込んだ」と書かれたコード：YaRN（`ggml/src/ggml-cpu/ops.cpp`、© 2023 Jeffrey Quesnelle and Bowen Peng — `rope_yarn` はリンクされている）、BPE tokenizer（`src/llama-vocab.cpp`、cmp-nct/ggllm.cpp由来、著作権者の記載なし）。表示のtext：`licenses/mit-llama-cpp.txt`（submoduleの `LICENSE` と `LICENSE-jsonhpp` をそのまま、＋ソースに書かれた帰属の文）。
- **LLVM libc++**（Apache License 2.0 with LLVM Exceptions）：NDK 29.0.14206865 の `libc++_shared.so`（`ANDROID_STL=c++_shared`）。textはNDKの `toolchains/llvm/prebuilt/darwin-x86_64/NOTICE` の先頭の「LLVM Project」の節（1–234行）。
- `OssLicenseCatalogTest.theNativeLocalAiRuntimeCarriesItsLicenseNotices` は、表示のtextがsubmoduleの `LICENSE` / `LICENSE-jsonhpp` をそのまま含むことを確かめる — submoduleを上げてライセンスの文が変われば落ちる。submoduleを上げる時は、この節の記号の確認をやり直す。
- **Unicode Character Database**（Unicode License v3、SPDX `Unicode-3.0`、2026-09-27に追加）：llama.cppの `src/unicode-data.cpp` は `scripts/gen-unicode-data.py` がUCD（`UnicodeData.txt`、空白は `PropList.txt`、NFDはPythonの `unicodedata`）から生成した表で、`libmemoripple_llm.so` にリンクされている（`unicode_ranges_flags` など）。使ったUCDの版はソースに記録がない。textはwebからではなく、NDK 29.0.14206865 の `NOTICE.toolchain` 10652–10690行のUNICODE LICENSE V3をそのまま（著作権表示の年も改変しない）。`OssLicenseCatalogTest.theUnicodeCharacterDatabaseTablesCarryTheUnicodeLicenseV3`。
- この環境では `python3` が動かず（Xcode CLTの問題）、`tools/generate_oss_assets.py` の検証は走らせていない。generatorの `STATIC_LIBRARIES` / `PASSTHROUGH_LICENSE_ASSETS` にcommitしたassetと同じ項目を足してあるので、次に動く環境で `python3 tools/generate_oss_assets.py` を実行して一致を確かめる。
