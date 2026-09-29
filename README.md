# MemoRipple OSS

The open-source code of **MemoRipple**, an Android app for notes, a diary and
notebooks, where what you wrote comes back to you as flowing comments.

MemoRipple（メモリップル）は、メモ・日記・ノートを書き、読み返した自分のコメントを
画面に流せる Android アプリです。このリポジトリはそのソースコードです。

- **Notes, outlines, a diary and notebooks** — memos with photos in the text,
  an outliner, a calendar with a diary, notebooks of episodes, folders and tags.
- **Flowing comments** — comments on a memo drift across the page, the stage or
  an overlay above other apps.
- **Read aloud** — text-to-speech of memos and comments.
- **Backup** — a local backup file, portable Markdown / ZIP export and import,
  and an optional Google Drive backup.
- **Chat with on-device Local AI** — ask in plain words or use templates; an
  optional language model runs entirely on the phone.

## Principles

- **Local-first.** Your writing lives on your device.
- **No ads, no analytics.** The app contains no advertising or analytics SDK.
- **Local AI runs on-device.** No text is sent to a server for AI. The AI is
  optional; templates and everything else work without it.
- **No model is bundled.** GGUF model files are not in this repository or in
  the app; the user downloads one from Hugging Face inside the app, if they
  want one.
- **Google Drive is optional.** Nothing leaves the device unless the user
  turns on Drive backup.
- **Open source** under the [Apache License 2.0](LICENSE). The MemoRipple name
  and official artwork are not included in that license — see
  [BRANDING.md](BRANDING.md).

## Building

### Requirements

- Android Studio (a recent stable version) or the command line
- JDK 17 or newer
- Android SDK with platform 36
- Android NDK `29.0.14206865` and CMake `3.31.6` (install both from the SDK
  Manager; the build names these exact versions)
- Git, to fetch the llama.cpp submodule

The app targets **arm64-v8a** devices running Android 10 (API 29) or newer.
Local AI additionally needs a CPU with the ARMv8.2 dot-product extension; on
other devices the rest of the app works and Local AI is shown as unavailable.

### Steps

```bash
git clone https://github.com/crag-kakao/MemoRipple-OSS.git
cd MemoRipple-OSS
git submodule update --init
./gradlew :app:assembleDebug
```

Point Gradle at your Android SDK with `ANDROID_HOME` (or a `local.properties`
file containing `sdk.dir=...`; that file is machine-local and git-ignored).

Useful tasks:

```bash
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:lintDebug              # lint
./gradlew :app:assembleRelease        # unsigned release build (R8 on)
```

The instrumentation tests (`./gradlew :app:connectedDebugAndroidTest`) need a
device or an emulator; see `docs/TEST_INFRASTRUCTURE.md`.

`llmbench/` and `tools/llm-eval/` are a non-shipping evaluation harness for
local models. They are not part of the app build unless you pass
`-PllmBench=true`; `llmbench/` also holds the llama.cpp submodule the app's
native library is compiled from.

### Release signing

Release builds are signed only when all four of these environment variables are
set in the shell that runs Gradle:

```
MEMORIPPLE_UPLOAD_STORE_FILE
MEMORIPPLE_UPLOAD_STORE_PASSWORD
MEMORIPPLE_UPLOAD_KEY_ALIAS
MEMORIPPLE_UPLOAD_KEY_PASSWORD
```

Without them the release artifact is left **unsigned** — there is deliberately
no fallback to the debug key. `./gradlew :app:verifyReleaseSigning` tells you
whether signing is configured, without printing any value. Use your own key;
never commit a keystore or a password.

## Forking

You are free to fork and publish your own app from this code under the
Apache License 2.0. Before you publish:

1. **Change the applicationId** in `app/build.gradle.kts`
   (`io.github.cragcoffee.memoripple` belongs to the official app). You can
   leave the Kotlin `namespace` as it is.
2. **Replace the branding**: the app name (`app_name` in
   `app/src/main/res/values/strings.xml` and the name shown in the UI), the
   icons (the placeholders in `app/src/main/res/drawable/`) and any artwork.
   See [BRANDING.md](BRANDING.md).
3. **Use your own signing key** (see *Release signing* above).
4. **Set up your own Google OAuth client** if you want Google Drive backup
   (see below).
5. **Local AI models** are downloaded by the user from Hugging Face at pinned
   revisions, verified by SHA-256 (`data/ai/models/ModelCatalog.kt`). They are
   not redistributed by the app; each model keeps its own license.

### Google Drive backup and OAuth

Drive backup uses Google's Identity `AuthorizationClient` with the Drive
**appData** scope. The repository contains **no OAuth client ID and no client
secret**. Google identifies the app by its **package name and signing
certificate**: an *Android* OAuth client registered in a Google Cloud project
for exactly that package and certificate fingerprint.

For a fork this means:

- The official OAuth configuration does not work for your build — your package
  name and signing certificate are different.
- Create your own Google Cloud project, enable the Google Drive API, configure
  the OAuth consent screen, and create an Android OAuth client for your
  applicationId and the SHA-1 of each signing certificate you use (debug,
  release, and Play App Signing if you publish on Google Play).
- Drive's appData folder is private to each Cloud project, so **backups made by
  the official MemoRipple app are not visible to a fork**, and the reverse. Use
  the local backup file or the portable export to move data between apps.

Without an OAuth client the app builds and runs normally; only Drive backup
cannot be authorized.

## Documentation

`docs/` holds the design notes written while the app was developed —
architecture, data model migrations, the Local AI safety pipeline, the chat and
templates, and test infrastructure. They describe decisions as they were made.

Some source comments and design notes still refer to the private development
history this public snapshot was taken from — for example `HANDOFF §16.29` —
and to internal notes that are not published. Those references are left as
they were; the code and the published design notes are complete without them.

## License

- Source code: [Apache License 2.0](LICENSE)
- MemoRipple name, logos, icons and official artwork: All Rights Reserved —
  see [BRANDING.md](BRANDING.md)
- Third-party components: their own licenses — see [NOTICE](NOTICE)

Contact: cragcoffee96@gmail.com
