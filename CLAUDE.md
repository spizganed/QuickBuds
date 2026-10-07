# CLAUDE.md

QuickBuds controls OnePlus / OPPO / realme earbuds over Bluetooth RFCOMM, on Android (`app/`, Kotlin)
and on Windows and Linux (`desktop/`, Rust + Slint). Build: [docs/CONTRIBUTING.md](./docs/CONTRIBUTING.md).
Plan and decisions: [docs/ROADMAP.md](./docs/ROADMAP.md). Wire format: [docs/PROTOCOL.md](./docs/PROTOCOL.md).

## Writing

Docs, commits, issue replies, release notes and chat use ASD-STE100 style: one idea per sentence,
20 words at most, active voice, present tense, one term for one thing, no filler. Write the current
fact, not its history; a past mistake gets one line ("Was: X. Is: Y."). Keep personal details out of
the repo. A finished feature needs no doc: the code is the record.

## Work

- Ask before every push.
- The developer does device tests and design decisions. Ask for the exact log line you need.
- When he reports a regression and asks for a revert, revert first and find the cause after.
- Docs change in the same commit as the code. A protocol change updates `OpoProtocol`, `protocol.rs`
  and PROTOCOL.md together.
- Never guess a payload or a command number: a wrong write fails silently. Read every write back.
- HeyMelody is studied for interoperability only. Commit commands, payloads and per-model facts,
  never vendor class, method or file names or code.
- A new user-visible string needs all 26 locales. Lint does not catch a missing one.
- Device tests use `./gradlew assembleRelease`. A debug build cannot install over it.
- adb tests: launch with `am start -n`, never `monkey`. Turn auto-rotate off after each test.
- Desktop UI tests run on the invisible screen (`scripts/desktop-vscreen.sh`), never on his desktop.

## Release

- Version: `app/build.gradle.kts` `defaultConfig` and `desktop/Cargo.toml`. The latest release is
  the newest `v*` git tag.
- Build and test: `gh workflow run Build`, then `gh run watch`, then `gh run download <run id>`. The
  artifacts hold the APK, the AAB and both desktop archives. Release only after the run passes.
- Local build, same files: `./gradlew assembleRelease bundleRelease` and `scripts/desktop-dist.sh <version>`.
- Notes cover every user-visible change since the last tag (`git log v<previous>..HEAD`).
- Publish: `gh release create v<version> QuickBuds<version>.apk QuickBuds<version>.aab <desktop
  archives> --target <full sha> --title "QuickBuds <version>"`. Both updaters look for these file
  names. A `desktop-v*` tag is a desktop-only fix.
- Never commit the release key or print its password.
- AUR package: `desktop/aur/` (not published yet).
