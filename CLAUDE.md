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
- A new user-visible string needs all 26 locales. Lint does not catch a missing one;
  `scripts/check-locales.py` does.
- Device tests use `./gradlew assembleRelease`. A debug build cannot install over it.
- adb tests: launch with `am start -n`, never `monkey`. Turn auto-rotate off after each test.
- Never commit logs or debug documents. Keep a capture's filtered, decoded transcript in
  `local/logs/` with a dated name (`heymelody_hold_oncall_20260922.log.txt`). Delete the bugreport
  and the raw btsnoop file.
- Gesture button groups come from a data table. Never hardcode them.
- Roadmap items carry no release version.
- Desktop UI tests run on the invisible screen (`scripts/desktop-vscreen.sh`), never on his desktop.

## Release

- Version: `versionName` and `versionCode` in `app/build.gradle.kts`, and `desktop/Cargo.toml`. The latest release is
  the newest `v*` git tag.
- Build and test: `scripts/release-build.sh`. It runs the Build workflow's checks and tests on this PC and puts
  the APK, the AAB and the desktop files (zip, tarball, AppImage) in `local/release/<version>/`. Release only
  after it passes.
- From the phone: `gh workflow run Build`, then `gh run watch`, then `gh run download <run id>`. Same files.
- Notes cover every user-visible change since the last tag (`git log v<previous>..HEAD`).
- Publish: `gh release create v<version> QuickBuds<version>.apk QuickBuds<version>.aab <desktop
  files> --target <full sha> --title "QuickBuds <version>"`. Both updaters look for these file
  names. The AppImage name has no version, so the README's install link always works. A `desktop-v*` tag
  is a desktop-only fix.
- The report Worker reads `models.json`. After a change to it, run `npx wrangler deploy` in
  `report-worker/`, or the Worker rejects reports for the new model.
- Never commit the release key or print its password.
