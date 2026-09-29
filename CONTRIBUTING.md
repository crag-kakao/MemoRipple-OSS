# Contributing to MemoRipple OSS

Thank you for your interest in improving MemoRipple. Bug fixes, small
improvements, documentation, and changes brought back from a fork are all
welcome.

## Before you start

- The source code is licensed under the [Apache License 2.0](LICENSE).
- The official MemoRipple branding (name, logos, icons, artwork) is separate and
  is not part of that license — see [BRANDING.md](BRANDING.md). Please don't add
  official branding assets to a pull request.
- If you are building your own app on this code, use your own branding,
  applicationId, signing key and OAuth configuration (see the README's
  *Forking* section).
- For a large change — a new feature area, an architectural change, a new
  dependency or permission, or anything that touches stored data — please open an
  issue first so we can agree on the approach before you spend time on it.

## Development setup

```bash
git clone https://github.com/crag-kakao/MemoRipple-OSS.git
cd MemoRipple-OSS
git submodule update --init
```

Then follow the [Build section of the README](README.md#build) for the
requirements (JDK, Android SDK, NDK, CMake) and the build commands.

Never commit machine-local or secret files: `local.properties`, keystores,
signing passwords, OAuth client files, or model files. The `.gitignore` already
covers the usual ones.

## Branches

Short, descriptive branch names help, for example:

- `fix/outliner-fold-after-undo`
- `feature/diary-export-option`
- `docs/build-instructions`

This is a convention, not a rule.

## Pull requests

Please describe in your pull request:

- **what** changed;
- **why** it is needed (link the issue if there is one);
- **how you tested** it;
- **before / after screenshots** if the UI changes;
- whether the **Room schema** changed;
- whether the **backup format** changed.

The pull request template asks for these. Smaller, focused pull requests are
easier to review than large ones.

## Tests

Run what fits your change:

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:lintDebug           # lint
./gradlew :app:assembleDebug       # debug build
```

For UI or database changes, the instrumentation tests on a device or emulator
may also be relevant; see `docs/TEST_INFRASTRUCTURE.md`.

Please don't make a flaky test pass by adding sleeps, stretching timeouts or
deleting assertions — mention the flake in the pull request instead. Visual
regression goldens define the expected look; changing one is a design decision,
not a way to make a test pass.

## Database and backup compatibility

People keep years of writing in this app, so compatibility matters more than
convenience. Please be careful with anything that is stored or exchanged:

- **Room schema** — a schema change needs a real migration and a version bump.
  Never use a destructive migration.
- **Backup format** — older backup files must keep restoring. Change the format
  version only when the data contract really changes, and keep the restore path
  for older versions.
- **Persisted identifiers** — preference keys, stored enum values, file names
  and folder names in exports.
- **JSON schemas** — the backup file, the template file and the portable export.
- **Navigation routes** — documents and screens are opened through them, so a
  route change has to update every caller together.

In particular, the backup identifier **`memorripple_backup`** (with a double
`r`) is a historical identifier that existing backup files depend on. It looks
like a typo, but please do not "fix" it.

If your change needs to touch any of these, say so in the pull request and
describe how existing data keeps working.

## Local AI / LLM changes

Local AI is optional and runs on the device. For changes in this area:

- **Keep it local-first.** Don't send user content (memos, diaries, chat,
  backups) to an external service.
- **Never commit model files** (`*.gguf`). Models are downloaded by the user at
  run time.
- **Keep the safety pipeline.** A model's output is a proposal: it is validated
  and resolved by the app, and every write waits for the user's confirmation.
  Changes must not let model output skip validation or confirmation.
- **Mind compatibility.** Prompts, grammars and the structured intent format are
  versioned and checked by tests — change them deliberately, as a new version,
  not in place.
- **Avoid model-specific hacks.** Prefer changes that work for any compatible
  model over special cases for one model.

The design notes in `docs/` (for example `docs/AI_SAFE_INTENT_PIPELINE.md`)
explain the current design.

## Licensing

By submitting a contribution, you agree that it is licensed under the project's
license, the [Apache License 2.0](LICENSE), as described in section 5 of the
license. There is no separate contributor license agreement.

Please only submit work you have the right to contribute, and keep third-party
code under its original license and notices.
