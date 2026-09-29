# Branding

The **source code** in this repository is open source under the
[Apache License 2.0](LICENSE). You are welcome to fork it, change it, and use it,
including commercially, under the terms of that license.

The **MemoRipple brand** is a separate matter. It is not part of the
Apache-2.0 grant.

## What the license does not cover

- **The name "MemoRipple".** The Apache License 2.0 does not grant permission
  to use the name (see section 6 of the license, "Trademarks").
- **The official logo and app icon**, including the "言葉のしずく" (drop of words)
  icon, and **the official original and promotional artwork**. These are
  All Rights Reserved.

The official icon artwork is **not included** in this repository. The icon
resources you find here (`app/src/main/res/drawable/ic_launcher_foreground.xml`,
`ic_launcher_monochrome.xml`, `ic_notification_overlay.xml` and the
`brand_icon_*` colours) are neutral placeholders so that the project builds.

Third-party components (libraries, fonts such as Noto Sans JP / Noto Serif JP
and Kosugi Maru, llama.cpp, and others) keep their own licenses; see
[NOTICE](NOTICE).

## If you publish a fork

Please make it clear that your app is yours. Use your own:

- **app name** — not "MemoRipple", and not a name that could be mistaken for it;
- **applicationId** (`app/build.gradle.kts`) — not `io.github.cragcoffee.memoripple`;
- **icons, logos and artwork**;
- **signing key** — your own upload / release key;
- **Google OAuth configuration** — your own Google Cloud project and Android
  OAuth client (see the README).

Please don't present a fork as the official MemoRipple app, or suggest that it
is endorsed by or affiliated with the MemoRipple project, unless you have been
given permission.

Saying that your app is "based on the MemoRipple source code" is fine — that is
accurate attribution, and the Apache License asks you to keep the existing
copyright and license notices.

## Names that remain in the source

The word "MemoRipple" still appears in the source: the Kotlin package
(`io.github.cragcoffee.memoripple`), class names, some user-visible strings,
file-format identifiers (for example the backup identifier and export folder
names), and the documentation. These are kept so that the code stays identical
to the released app and its file formats stay compatible. When you publish a
fork, change at least the user-visible name (`app_name` and the strings shown
in the UI), the applicationId, and the icons.

This page is a plain-language explanation, not legal advice. If something is
unclear, ask before you publish.
