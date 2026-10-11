# QuickBuds handoff to the Termux agent

Date: 2026-10-11. Repo: `spizganed/QuickBuds`. Checkout: `~/projects/QuickBuds`.
Read CLAUDE.md first. Its rules still apply: ask before every push, never guess a payload, 26 locales.

This file is a working note, not a repo doc. Keep it in `local/` and do not merge it into `main`.

## 1. State of main

- `main` is at `8366ef5`. No open pull requests. No open issues.
- `v4.11.0` is published. The tag points at `f94c939`. Assets: APK, AAB, Windows zip, Linux tarball.
- The release has **no AppImage**. The AppImage waits for a hardware test (section 2.1).
- `main` holds 8 commits after `v4.11.0` (`git log --oneline v4.11.0..main`). Not released yet:
  - Linux AppImage that updates itself (PR #13).
  - "Test my earbuds": a guided model test after the first connect to an unconfirmed model. Android
    is device-tested. The Worker change for it is deployed.
  - A model pick by hand runs all connect reads again (`9a2b344`, `8366ef5`). Device-tested.
  - Docs: CLAUDE.md, capture guide, one desktop build guide, README checked against the code.
- `versionName` 4.11.0, `versionCode` 34, `desktop/Cargo.toml` 4.11.0.
- Every remote `claude/project-thread-*` branch and `report-misuse-fix` is merged (squashed). You
  can delete them. Exception: the branch that carries this file, and any branch from section 2.2.

## 2. Unfinished work

### 2.1 Linux AppImage: test, then ship

Done: build script, updater, workflow input, README. Tested only in a cloud Ubuntu 24.04 container
(window opens on Xvfb, scripted self-swap works, `cargo test` passes).

Decision for the developer: the AppImage code is newer than `v4.11.0`. An AppImage built from `main`
carries version 4.11.0 but holds unreleased features. Option A: upload it to `v4.11.0` (his earlier
choice). Option B: ship it in 4.12.0 with "Test my earbuds". Ask him.

Next commands:

```bash
gh workflow run Build -R spizganed/QuickBuds -f appimage_only=true
gh run watch -R spizganed/QuickBuds
gh run download <run id> -R spizganed/QuickBuds
```

He then tests on a Linux PC with earbuds: tray icon and menu, connect, a few controls, the Update
section finds the AppImage asset, self-update from an older AppImage. After his OK:

```bash
gh release upload v<version> QuickBuds-linux-x64.AppImage -R spizganed/QuickBuds
```

Rules:
- The AppImage name has no version, so `releases/latest/download/QuickBuds-linux-x64.AppImage` works.
  The README install link returns 404 until a release carries it.
- Keep the Linux tarball in every release. Old installs update only from `-linux-x64.tar.gz`.
- Risks: GTK and appindicator in an AppImage are fragile. Distros without FUSE need `fuse3`.
  linuxdeploy is unpinned ("continuous"), so an upstream change can break the build.
- The phone is ARM. The proot cannot build the x86-64 AppImage. Use the Build workflow.

### 2.2 Missing EQ and other features on realme, OPPO, OnePlus, DIZO

Cause: a real user's report (realme Buds Air8, `066812`, firmware 1.1.0.104, app 4.11.0, Android 16)
says "Equalizer not available". `EqActivity.hasEq()` needs `equalizer` 1-4 or `customEqualizer` in
`app/src/main/assets/models.json`. No realme entry has EQ data. The Air8's command bitmap lists
`010F`, `0122`, `0406`, `0418`, `041B`, so the buds have EQ.

Other features hidden on realme for the same reason (flag missing in models.json):
- EQ (`0406`, `0418`): the buds have it.
- BassWave (`041B`): the buds have it.
- Device manager and preferred device (`0429`): the buds have it.
- Personalized ANC (`0412`): listed. Unsure: Buds 4 lists it without having it.
- Codec picker (`0123`): listed.
- In-call gestures: no data.
Switch rows (3D, dual connection, game mode) follow the buds' status list and work.

Vendor data: realme Link 5.5.514 (34 model configs) and HeyMelody 116.9.0 are in Termux storage.
A phone session dumped them to `~/projects/QuickBuds/local/vendor-eq-dump/`
(`realme.json`, `heymelody.json`, `keys.txt`). Check that these files exist and are complete.
It found 202 distinct keys across HeyMelody model entries.

Gap list from `main` (`models.json`, 137 entries):

**realme, no EQ data (39 with partial data):** 050812 Wireless 3, 050C12 Wireless 3 Neo, 051412
Wireless 5 ANC, 051812 Wireless 6 Neo, 051C52 Wireless 6, 052052 Wireless 6 ANC, 061012 Air 3S,
061C12 Air 5 Pro, 062012 Air 5, 062412 T300, 062812 T110, 063012 Air6 Pro, 063412 Air6, 063812 N1 Pro,
063C12 T310, 064012 N1, 064412 T01, 064812 Air7, 064C12 Air7 Pro (confirmed), 065052 T200 Lite,
065452 T200, 065852 T200x, 065C12 Clip, 066012 TechLife Buds, 066452 T500 Pro, 066812 Air8,
066C12 Air8 Pro, 067052 T500, 067452 T500 Pro Harry Potter Edition.

**realme, id and name only:** 050412 Wireless 2S, 060812 Air 3, 060C12 Q2s, 061412 T100, 061812 Air 3
Neo, 1B5374 Air 2 Neo, 3D59CF Air, 693878 Q2, 8CD10F Air Pro, BA5D56 Air 2, C1E2B1 Wireless 2,
C70A6D Wireless 2 Neo, E3009F Wireless Pro, F85450 Air Neo. Speakers (49074B Cobble, A5FD66 Brick,
C70A75 Pocket) stay without data.

**OPPO / OnePlus, no EQ (12):** 050410 Enco M31, 060410 Enco Free, 060414 OnePlus Buds, 060810 Enco
W31, 060814 OnePlus Buds Z, 060C10 Enco W51, 061810 Enco Air, 061C10 Enco Play, 062410 Enco Buds,
062810 Enco Air Lite, 068414 OnePlus Buds, 068814 OnePlus Buds Z. Unverified claim: HeyMelody shows no
EQ for them either. Check it against `heymelody.json`.

**OPPO / OnePlus / DIZO, id and name only (9):** 050010 Enco Quiet, 050414 OnePlus Bullets Wireless
Z2, 050810 Enco M32, 060412 O-Free, 061010 Enco W11, 062C10 Enco W31 Lite, C70A6E DIZO Wireless,
C70A6F DIZO GoPods D, C70A70 DIZO GoPods. (OnePlus Open Buds has full data.)

Next steps:
1. Read the three dump files. Copy only values present in them. Never guess a `0406` id.
2. Fill `models.json` on a new branch: EQ presets (ids, names), custom EQ bands, BassWave, device
   manager, personalized ANC, codec picker. Commit facts only, never vendor class or file names.
3. If a new key appears, update `OpoProtocol`, `protocol.rs` and PROTOCOL.md in the same commit.
4. Run `./gradlew assembleRelease` and the checks. Ask the developer for an Air8 device test.
5. After the merge, redeploy the Worker (section 3). It reads `models.json`.

### 2.3 Parked and open (docs/ROADMAP.md)

- realme Link `0x0403` extras (dynamic bass, vocal enhance): wait for a realme owner (issue #8).
- Confirming a model: read "Test" reports, then add `"confirmed"` to the model in `models.json` and the
  README list. Confirmed now: OnePlus Buds 4, OPPO Enco Buds2, realme Buds Air7 Pro.

## 3. Ops

### Report Worker

- URL: `https://quickbuds-report.spizganed.workers.dev` (apps post to `/report`).
- The read key is in `~/.quickbuds-report-key`. Never print, echo or log its value.
- Read reports without showing the key:

```bash
curl -s "https://quickbuds-report.spizganed.workers.dev/?key=$(cat ~/.quickbuds-report-key)&format=json"
```

  Add `&after=<id>` for newer reports only, `&limit=100` for more.
- The Worker imports `app/src/main/assets/models.json`. After any change to it, deploy.
- D1 database: `quickbuds-reports`. A sender can post 5 reports an hour.

### Deploy the Worker

Wrangler runs in the Debian proot with Node 22 at `/opt/node22/bin`. The API token is in
`~/.cloudflare-token`. Never print it.

```bash
proot-distro login debian -- bash -lc '
  export PATH=/opt/node22/bin:$PATH
  export CLOUDFLARE_API_TOKEN=$(cat /data/data/com.termux/files/home/.cloudflare-token)
  cd /data/data/com.termux/files/home/projects/QuickBuds/report-worker
  npx wrangler deploy'
```

If the token file is in the proot's `/root` instead, read it from there.

### Release from Termux

1. Bump `versionName`, `versionCode` (`app/build.gradle.kts`) and `desktop/Cargo.toml`. Ask before push.
2. Notes: every user-visible change in `git log v<previous>..HEAD`.
3. Build in GitHub Actions: `gh workflow run Build`, `gh run watch`, `gh run download <run id>`.
   The `android` artifact holds APK and AAB. The `desktop` artifact holds the zip, tarball, AppImage.
4. Publish (the updaters look for these names):

```bash
gh release create v<version> QuickBuds<version>.apk QuickBuds<version>.aab \
  QuickBuds<version>-windows-x64.zip QuickBuds<version>-linux-x64.tar.gz QuickBuds-linux-x64.AppImage \
  -R spizganed/QuickBuds --target <full sha> --title "QuickBuds <version>" --notes-file notes.md
```

A `desktop-v*` tag is a desktop-only fix. Never commit the release key or print its password.

### Termux build facts

- Packages: `pkg install git openjdk-21 aapt2 android-tools python gh proot-distro`.
- `JAVA_HOME=$PREFIX/lib/jvm/java-21-openjdk`, `ANDROID_HOME=$HOME/android-sdk`.
- `~/.gradle/gradle.properties`: `android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2`.
- Run Gradle in Termux, not in the proot. Install with wireless adb: `adb connect 127.0.0.1:<port>`;
  the port changes after each reboot.
- Desktop tests run in the proot: `cd /data/data/com.termux/files/home/projects/QuickBuds/desktop && cargo test`.

## 4. Background facts from the project notes

- Flathub forbids AI-written manifests and AI-opened submissions, and a Flatpak cannot update itself.
  So Flatpak is dropped. AUR is dropped too.
- Model test design: the dialog shows after the first connect to an unconfirmed model, behind crash,
  permissions and update dialogs, at most one launch dialog per start. "Not now" hides it per model.
  Each test report carries the log. The Worker stores it with category "Test".
- Other brands are decided against. Gadgetbridge (AGPL-3.0) has an OPPO/realme driver to compare
  protocol facts with.
- Cloudflare free plan: 100,000 requests a day, 10 ms CPU per request, 5 GB D1. No bill at the limit.

Original notes (project files, not on the phone): `appimage-handoff.md`, `build-termux.md`,
`docs-review-readme-build.md` (all findings fixed in PRs #11 and #12), `model-test-prompt.md`,
`workers-and-other-brands.md`, `roadmap-review/ROADMAP-draft.md` (merged as PR #10).
