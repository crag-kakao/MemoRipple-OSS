# Security Policy

## Reporting a vulnerability

Please **do not** describe a security issue in a public GitHub issue, pull
request or discussion.

Instead, email **cragcoffee96@gmail.com** with the subject
**MemoRipple Security Report**.

Helpful information to include:

- the affected version or commit;
- steps to reproduce;
- the impact — what an attacker could do;
- the device and Android version;
- logs, if they contain nothing personal;
- a proof of concept, if appropriate.

Please **do not** send passwords, access tokens, signing keys, private backup
files, or real memo, diary or chat content. Sample data you made up for the
report is enough.

You will get an acknowledgement as soon as possible. Please give us a
reasonable amount of time to fix the issue before disclosing it publicly.

## Supported versions

| Version | Supported |
| --- | --- |
| Latest public OSS snapshot (`main`) | Yes |
| Older snapshots | Best effort |

## Scope

In scope:

- the Android app in this repository;
- the Local AI integration (model download and verification, the on-device
  runtime, and how model output is handled);
- backup and restore, and the portable export / import;
- the Google Drive integration;
- how user data is stored and handled on the device;
- the native library integration (llama.cpp and the JNI bridge).

Vulnerabilities in a third-party service or library itself — for example Google
Drive, Hugging Face, or an upstream library — should be reported to that
service or project. If such an issue affects MemoRipple in a specific way, you
are welcome to tell us about that too.
