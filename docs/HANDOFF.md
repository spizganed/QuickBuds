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

Vendor data, read-only dump made on 2026-10-11 from Termux storage:
- Sources: `~/realmelink-decompile/x/` (realme Link 5.5.514.11421, configs in `assets/headsetRusConfig/`)
  and `~/heymelody-decompile/opm/DeviceModels.json` (HeyMelody 116.9.0 server list cache).
- Dump: `~/projects/QuickBuds/local/vendor-eq-dump/`: `realme.json` (34 configs keyed by model id,
  plus `_common` wire facts), `heymelody.json` (21 entries), `keys.txt` (202 key paths, use counts).

**realme findings (all 34 configs):**
- Built-in EQ: `0x0406 <id>`. Four presets: Pure Raw `00`, Deep Bass `01`, Pure Voice `02`, Pure Bass `03`.
- Custom EQ: `0x0418 <action> <min> <max> <eqId> <nameLen> <name> <bands> [freq u16 LE, gain s8]...`.
  Action `01` add, `02` modify, `03` delete. Six bands: 62, 250, 1000, 4000, 8000, 16000 Hz.
  Gain -6 to +6 dB. At most 3 custom presets.
- 061C12 (Air 5 Pro) and 063012 (Air6 Pro) use other bands: 100, 170, 400, 4300, 9500, 12000 Hz.
- Bass engine: `0x041B <min> <max> <value> [type 1-3]`. No config names a BassWave item.
- Multi-connect: `0x040B 01 <MAC> <action>`. No action values and no preferred-device option in the data.
- Personalized ANC: `0x0412 <value>`. Ear scan: `0x0415`.
- No config has an in-call gesture option or a codec picker list.
- Air8 (066812) and Air8 Pro (066C12): EQ page, bass row, 3-device connect, fit test, Hi-Res. No
  personalized ANC. Air8 adds Dolby Atmos. Air8 Pro adds Golden Hearing.
- Conflict: the `<min> <max>` bytes in `0x0418` and `0x041B` differ from PROTOCOL.md §9 (`FA 06`,
  `FB 05`). Probably the same fields. Confirm with a capture before any write.
- Still to do: a per-model table from `realme.json` (which of the 34 have the bass row, multi-connect,
  ear scan, personalized ANC). Map the 34 config ids to the realme entries below.

**OPPO / OnePlus / DIZO findings:** none of the 21 HeyMelody entries has `equalizer`, `equalizerMode`,
`customEqualizer` or `bassEngineSupport`. Our missing EQ on them matches HeyMelody: no fix needed.
The 9 id-and-name-only entries carry only battery, fast discovery and Dolby Atmos flags in HeyMelody,
no gestures. Our `control` data on the other 12 matches. The only keys we drop are vendor metadata
(`brand`, `uuid`, `rssi`, `minVersion`, `podsVersion`, `supportSpp`, `type`, `defaultColor`).

Gap list from `main` (`models.json`, 137 entries):

**realme, no EQ data:** 050812 Wireless 3, 050C12 Wireless 3 Neo, 051412
Wireless 5 ANC, 051812 Wireless 6 Neo, 051C52 Wireless 6, 052052 Wireless 6 ANC, 061012 Air 3S,
061C12 Air 5 Pro, 062012 Air 5, 062412 T300, 062812 T110, 063012 Air6 Pro, 063412 Air6, 063812 N1 Pro,
063C12 T310, 064012 N1, 064412 T01, 064812 Air7, 064C12 Air7 Pro (confirmed), 065052 T200 Lite,
065452 T200, 065852 T200x, 065C12 Clip, 066012 TechLife Buds, 066452 T500 Pro, 066812 Air8,
066C12 Air8 Pro, 067052 T500, 067452 T500 Pro Harry Potter Edition.

**realme, id and name only:** 050412 Wireless 2S, 060812 Air 3, 060C12 Q2s, 061412 T100, 061812 Air 3
Neo, 1B5374 Air 2 Neo, 3D59CF Air, 693878 Q2, 8CD10F Air Pro, BA5D56 Air 2, C1E2B1 Wireless 2,
C70A6D Wireless 2 Neo, E3009F Wireless Pro, F85450 Air Neo. Speakers (49074B Cobble, A5FD66 Brick,
C70A75 Pocket) stay without data.

**OPPO / OnePlus, no EQ (matches HeyMelody):** 050410 Enco M31, 060410 Enco Free, 060414 OnePlus
Buds, 060810 Enco W31, 060814 OnePlus Buds Z, 060C10 Enco W51, 061810 Enco Air, 061C10 Enco Play,
062410 Enco Buds, 062810 Enco Air Lite, 068414 OnePlus Buds, 068814 OnePlus Buds Z.

**OPPO / OnePlus / DIZO, id and name only (matches HeyMelody):** 050010 Enco Quiet, 050414 OnePlus
Bullets Wireless Z2, 050810 Enco M32, 060412 O-Free, 061010 Enco W11, 062C10 Enco W31 Lite,
C70A6E DIZO Wireless, C70A6F DIZO GoPods D, C70A70 DIZO GoPods.

Model flags that gate a row (the app hides the row without them): `equalizer` / `equalizerMode` /
`customEqualizer` (EQ, also desktop `protocol.rs:376-389`), `bassEngineSupport` (BassWave),
`multiConnect` (device manager, preferred device), `personalNoise`, `highAudio` (codec picker),
`earScan`, `callControl` (in-call gestures), `spatialTypes`, `tapLevelSetting`. The realme entries carry
none of them.

Design idea for the developer (not decided): when a model has no EQ data but its command bitmap lists
`0x0122` / `0x0418`, read the custom presets from the buds instead of hiding EQ.

Next steps (realme only):
1. Build the per-model table from `realme.json`. Copy only values present in it. Never guess an id.
2. Fill `models.json` on a new branch: `equalizerMode` presets 00-03, custom EQ bands, the bass row,
   multi-connect, ear scan where the config has them. Facts only, never vendor class or file names.
3. If a new key or command appears, update `OpoProtocol`, `protocol.rs` and PROTOCOL.md in the same commit.
4. Run `./gradlew assembleRelease` and the checks. Ask the developer for an Air8 test, and for a
   capture that settles the `0x0418` / `0x041B` min-max bytes.
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
