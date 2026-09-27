# CLAUDE.md — working notes for this project

**Read this before touching anything.** It carries the state that is not derivable from the code:
the toolchain, the conventions, the mistakes already paid for, and what is currently open.

## What this project is

**QuickBuds** — an Android app that controls OnePlus / OPPO / realme earbuds directly over classic
Bluetooth **RFCOMM**, with no HeyMelody, no vendor app, no root, no Shizuku, no ADB. Kotlin.

- Package `com.spizganed.quickbuds` (final). App name **QuickBuds**. GitHub: `spizganed/QuickBuds`,
  branch `main`.
- License **GPL-3.0**. Test device: **OnePlus Buds 4** on a **Nothing Phone (3a), Android 16** (API 36, seen 2026-09-27).
- Single module, `:app`. All sources are Kotlin under `app/src/main/kotlin/`; no Java.

**The north star is parity with HeyMelody**, then this project's own improvements on top (the widget,
the wear-state display). Only after that, other earbud models.

## Read order

This file is the entry point for a new session.

| File | What it is |
|---|---|
| [ROADMAP.md](./docs/ROADMAP.md) | The live plan: next, open, questions. |
| [ROADMAP-DONE.md](./docs/ROADMAP-DONE.md) | What is finished. Move items there when done. |
| [PROTOCOL.md](./docs/PROTOCOL.md) | **The wire format, end to end.** Read before any protocol work. |
| [README.md](./README.md) | Short and user-facing: features, install, credits (CREDITS.md was folded in, 2026-09-26). New `[OSS]` sources get a line in its Credits. |
| [PACKET-CAPTURE.md](./docs/PACKET-CAPTURE.md) | How to capture a packet log, kept as a backup procedure. |
| CLAUDE.md | This file — the durable reference, loaded automatically. |

## Build and run

Plain desktop Gradle.

- **Toolchain:** Gradle **9.6.0** · Android Gradle Plugin **9.4.0** · Kotlin **2.4.0** (since 2026-09-25).
  AGP 9 compiles Kotlin itself: there is no `org.jetbrains.kotlin.android` plugin (AGP 9 rejects
  it). The root `buildscript` classpath entry for `kotlin-gradle-plugin` only pins the Kotlin version.
- **SDK levels:** `compileSdk 37`, `minSdk 26`, `targetSdk 37` (Android 17, since 2026-09-25), Java 8.
  Target 37 makes an RFCOMM `read()` return `-1` on a dropped link instead of throwing; the
  reader loop turns that into the normal "Connection lost" path. Android 17 also ignores the
  portrait lock on displays wider than 600dp (tablets, foldables).
- **Only dependency:** `androidx.core:core:1.13.1`
- `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`, **signed** with the release
  key when `local/keys/` is present (see *Signing*). **Use this one for all device testing** (`[USER]`
  2026-09-24), including unpushed work.
- `./gradlew assembleDebug` → debug-key APK. Do not install it on his phone: its signature clashes
  with the release build.
- The shell has no `JAVA_HOME`: `export JAVA_HOME=$(ls -d ~/.jdks/jbr-21* | head -1)` first.
- `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`
- Deploy with `adb install -r <apk>`. **adb over USB is the debugging path**; use `adb logcat` for
  anything the in-app logs do not show.
- **Wireless adb also works, confirmed 2026-09-22** — used to pull an `adb bugreport` for a
  Wireshark/tshark capture with no cable. Phone: Developer options → Wireless debugging → "Pair
  device with pairing code" gives an `IP:port` + 6-digit code; `adb pair <that ip:port> <code>`
  (code is one-shot and expires in well under a minute, so run the pair command immediately after
  reading it off screen — a stale code fails as `protocol fault (couldn't read status message)`,
  which looks like a network problem but usually isn't one). Then `adb connect` the **different**
  `IP:port` shown on the main Wireless debugging screen (not the pairing one) to actually attach.
  Phone and PC do not need to be on the same LAN in the traditional sense — this was verified over a
  Tailscale link (a `100.64.0.0/10` address), so a VPN mesh between them is enough as long as the
  pairing/connect ports are reachable.
- `local.properties` must contain `sdk.dir=...`; it is git-ignored and must not be committed.

## Phone sessions (Termux, reached over SSH from the PC)

Since 2026-09-27 the repo is also built on the phone itself (Termux, aarch64), at
`~/projects/QuickBuds` (Termux home, moved off shared storage 2026-09-27). The repo is the same for both setups; every
phone-specific piece lives outside it.

- `pkg install openjdk-21 aapt2 android-tools`. `JAVA_HOME=$PREFIX/lib/jvm/java-21-openjdk` and
  `ANDROID_HOME=$HOME/android-sdk` are exported in `~/.bashrc`.
- SDK in `~/android-sdk`: cmdline-tools **13114758** (newer ones wrap an x86 `android` binary that
  cannot run on arm64; run `termux-fix-shebang` on its `bin/`), `platforms;android-37.0`,
  `build-tools;37.0.0` with its x86 `aapt2` replaced by a symlink to Termux's.
- `~/.gradle/gradle.properties` has `android.aapt2FromMavenOverride=$PREFIX/bin/aapt2` (AGP downloads
  an x86 aapt2 otherwise). User-level on purpose: never put it in the repo's `gradle.properties`, it
  would break the PC build.
- `local.properties`: `sdk.dir=/data/data/com.termux/files/home/android-sdk`.
- `./gradlew` works. `core.filemode` is false (a leftover from shared storage), so git keeps
  `gradlew` at 755 whatever the local bits are. SDK shell scripts need `java -jar .../lib/<tool>.jar` (no `/bin/bash`).
- The release key is in `local/keys/` here too, so `./gradlew assembleRelease` signs as on the PC.
- **Phone build tweaks, all outside the repo** (2026-09-27): `~/.gradle/init.d/quickbuds-phone.gradle.kts`
  moves build output to `~/qb-build/` (set up while the repo was on slow FUSE shared storage) and disables the `lintVital*` tasks;
  `~/.gradle/gradle.properties` turns on the configuration cache and raises heaps (Gradle 3g, Kotlin 2g).
  **The phone's APK is `~/qb-build/_app/outputs/apk/release/app-release.apk`**, not `app/build/...`. Timed:
  clean build (no build cache) 147 s → 83 s; rebuild after a one-line edit 43 s → 3 s. The daemon's
  "Unable to set daemon's environment variables" warning is harmless on Termux.

## UI revision (design/SPEC.md)
- design/SPEC.md is the source of truth for the UI work; design/*.png are visual references.
- **Theming exception:** SPEC.md section 1 suggests a view-tree PaletteApplier. That is wrong for this codebase. Keep the attribute-based ThemeRes approach and extend it so custom presets apply at inflation time too. Propose the design in the plan step before coding.
- UI work never touches protocol, RFCOMM, packet parsing or wear-state logic.
- Completed SPEC steps: 1 (palette), 2 (shared components), 3 (home), 4 (disconnect dialog, EQ header), 5 (settings screen), 6 (theme & colors, edit preset), 7 (haptics). All SPEC steps done and shipped (3.1.0).
- **Palette (step 1):** six token attributes in `values/themes.xml` (`appColorBg/Card/Accent/TextPrimary/TextSecondary/Outline`),
  one style per built-in preset. `Palette.kt` holds `Palette` (tokens + derived colours) and `PaletteStore`
  (active id + up to 3 custom presets as JSON in `QuickBudsPrefs`; the old `theme` int migrates once).
  A custom preset uses the built-in style with the matching light/dark window plus `ThemeRes.PaletteFactory`,
  which swaps `?attr/appColor*` in inflated XML. Shape drawables are built in code (`ThemeRes.card/chip/iconButton/sheet`);
  do not add XML shapes with `?attr` colours, the factory cannot see inside them. `QuickBudsApp` recreates any
  activity whose preset changed, on resume. Light presets have their own derived switch colours
  (`Palette.track` / `thumbOff`), and `onAccent` prefers the lighter colour when it reaches 3:1.
- **Match system** (`paletteAuto`, [USER] 2026-09-27): `PaletteStore.activeId` returns White in light mode
  and `paletteAutoDark` (OLED or Classic Dark) in dark mode. Picking a dark built-in while it is on sets the
  dark half; picking White or a custom preset turns it off. Activities recreate on the uiMode change by
  themselves (never add `uiMode` to `configChanges`); `QuickBudsApp.onConfigurationChanged` repaints widgets.
- **Compact sizing ([USER] 2026-09-26):** everything ~10-15% shorter than SPEC so home fits without scrolling:
  rows 62dp (SPEC 72), colour rows 54dp, rings 90dp, segments 56dp, level pills 36dp, screen padding 16dp,
  tile gaps 12dp. Touch targets stay >= 44dp (icon buttons, status chip).
- Decisions ([USER] 2026-09-26): no Material Components (plain Switch/Dialog/EditText, custom rings); the
  status chip follows SPEC (accent dot when connected, grey ring + grey "Connect" when not); the battery glyphs keep
  their traced SVG ratio inside SPEC's 42x56 / 58x42 boxes; the percentage is always `text`, never red at low levels ([USER] 2026-09-27); the Dev tools
  button toggle defaults ON.
- **Shared components (step 2)** live in `SettingRowFactory`: `screen` (padding + system-bar insets; the app
  is edge to edge on target 35+), `title`, `sectionLabel`, `card`, `build` (row with 52dp trailing slot and an
  optional `value` before it), `buildSwitch`, `buildChevron`, `buildDivider`, `iconButton`. New screens use
  these, not their own copies. SPEC section 5 icons are in `res/drawable` under the SPEC names.
- **Confirm dialogs (step 4)** go through `ConfirmDialog.show()` (SPEC 3.4 style): Disconnect from the status
  chip, EQ preset delete, and the preset delete in 3.8. EQ editor Duplicate reuses the import path
  (`pendingImport` + `createCustomEq`); a long press on it still copies the preset as text.
- **Settings (step 5)** is `SettingsActivity`, opened by the header cog (the old cog bottom sheet is gone).
  Sections: Appearance, General (language, haptics, background service, Dev tools button; the Developer
  section was folded in, [USER] 2026-09-27), App. The Language screen has its title again.
  Prefs: `haptics` (default on), `backgroundService` (see Connection robustness) and `devToolsButton` (default
  on, read in `MainActivity.onResume`).
- **About** is `AboutActivity` (2026-09-26): icon, version, tagline, GitHub and Ko-fi buttons (default browser;
  the Ko-fi URL is a placeholder until his page exists), license and a pointer to the credits.
- **App update** (2026-09-26): `UpdateChecker` (GitHub latest release via `org.json`, `.apk` asset, streamed
  download with progress) is shared by `UpdateActivity` (installed vs latest side by side, one action pill,
  progress bar, release notes; checks on open) and the check on start (`updateAutoCheck`, default on; at most
  every 12 h, silent on failure, one `ConfirmDialog` per new tag, never again for the same tag).
- **Home layout** (`HomeLayoutActivity`, [USER] 2026-09-26): the main screen's own layout in an edit mode.
  Battery and noise control show live but inert; each sound settings row (low latency, Hi-Res, 3D audio,
  EQ, Dual connection, Earbud settings) is held and dragged to move (platform `startDragAndDrop`, reordered
  live) and has an eye button (hidden rows greyed). The check applies, X/Back discards. Prefs `homeRowOrder`
  / `homeRowHidden` by row key; `MainActivity.layoutFeatureRows()` applies them on create and resume. A new
  row needs a key in `buildFeatureRows()` AND in `HomeLayoutActivity.ROWS` (icon, title, subtitle).
- **Presets (step 6)**: `ThemeActivity` (3.7, rebuilt in `onResume`) and `PresetEditActivity` (3.8).
  `PalettePreviewView` draws a mini home in ANY palette (tile or detailed). Colour edits update the preview
  live and save on commit (slider lift, hex done/focus loss, swatch tap); the colour rows are rebuilt
  then, never mid-drag. The picker is `ColorPickerView`: hue, saturation and brightness `ColorSliderView`s
  (white, black and greys need the last two, [USER] 2026-09-26), hex field, quick swatches.
- **Built-in accent** ([USER] 2026-09-26): Theme & colors shows an "Accent color" row under the built-in
  tiles while a built-in is active. `PaletteStore.setAccentOverride` stores it per preset
  (`paletteAccent_<id>`; the style's own red removes it) and `builtIn()` applies it. A built-in with an
  override goes through the custom path in `ThemeRes.select` (its own style + `PaletteFactory`). Quick swatches are `@color/swatch_*` (picker choices, not app colours). Contrast
  warnings use `Palette.contrast` (WCAG 2) and never block saving. This is the first path that makes
  `ThemeRes.PaletteFactory` run, so a custom preset is where an inflation bug would show first.
- **Haptics (step 7)**: `Haptics.commit(view)` (CONFIRM, VIRTUAL_KEY below API 30, gated on `haptics`). Hooked
  once per kind of control: `SettingRowFactory.buildSwitch` (its `performClick`, user taps only),
  `LevelSliderView` / `EqCurveView` / `ColorSliderView` release, `AncSegmentedView` tap, ANC level pills, EQ
  preset rows, `BottomSheetDialog` item selection, preset apply and preset colour commits. Do not add
  it to programmatic state changes. Widget taps use `Haptics.tick(context)` in `WidgetActionReceiver.onReceive`
  (the vibrator, usage HARDWARE_FEEDBACK: the default TOUCH usage is dropped for a background app,
  `ignored_background` in `dumpsys vibrator_manager`).

### Environment you need

One-time setup.

| Need | Version / note |
|---|---|
| JDK | **17+**, built with JBR 21. Point `JAVA_HOME` at Android Studio's `~/.jdks/jbr-21*`, not the IDE's own bundled JBR. |
| Android SDK | `platforms;android-37.0` (for `compileSdk 37`) and `platform-tools` (for adb). `build-tools` matching AGP. |
| Gradle | None — the committed `./gradlew` wrapper downloads it. |
| adb | For deploying to the phone and reading `logcat`. |
| kotlin-stdlib | **No** manual install — it comes with the Kotlin Gradle plugin. The only app dependency is `androidx.core:core:1.13.1`. |

### Signing — release key since v2.0.0

**Releases are signed** with the key in `local/keys/quickbuds-release.jks` (PKCS12, alias
`quickbuds`, RSA 4096, valid 100 years), created 2026-09-23 at his request. Its passwords are in
`local/keys/keystore.properties`, which `app/build.gradle.kts` reads. Both are git-ignored and
on the PC and the phone only (never in git). **He must keep a backup of both files**: the in-app updater can only install over an app
signed with the same key, and losing it means every user has to uninstall first. Never commit
them and never print the password.

Without that file (a fresh clone), `assembleRelease` falls back to an unsigned APK, as before.
Device testing uses `assembleRelease` too. **Debug and release builds cannot be installed over
each other**, and switching needs an uninstall.
v1.1.0 was signed with a different key, so moving from 1.1.0 to 2.0.0 also needs one.

### Versioning — `build.gradle.kts` defaultConfig is the single source

`versionCode` / `versionName` are set **only** in `app/build.gradle.kts` `defaultConfig`.
**Current: versionCode 9 / versionName 3.4.0.**

They used to be on `<application>` in the manifest. **Android ignores them there**, so every PC build
up to 2026-09-23 shipped with no version at all (`aapt2 dump badging` showed `versionCode=''`),
and `bundleRelease` failed with "Version code not found in manifest". Verify with
`aapt2 dump badging <apk>`, not by reading a source file. `UpdateActivity` still reads the installed
version through `PackageManager`, and `buildConfig` stays off.

### Release flow

`./gradlew assembleRelease bundleRelease` gives the signed APK and AAB. Name them
`QuickBuds<version>.apk` / `.aab` (copies kept in `local/release/v<version>/`) and attach **both** to a
GitHub release tagged `v<version>`. The in-app updater compares the tag against the installed
version and needs the **`.apk`** asset; the `.aab` alone is invisible to it. `gh` is not installed on
the PC (release page in the browser there); on the phone `gh` is logged in, so
`gh release create v<version> <apk> <aab> --target <full sha> --title "QuickBuds <version>"` works (v3.4.0).

## Protocol work — the rules that were paid for

Use [PROTOCOL.md](./docs/PROTOCOL.md) as the reference; it tags every claim `[VENDOR]` / `[OSS]` /
`[CAPTURE]` / `[GUESS]`. The short version of what bites:

- **HeyMelody's own code is the `[VENDOR]` source for bytes** (2026-09-27). Pull the APK with adb
  (`pm path com.heytap.headset`), decompile with JADX 1.5.6 (the release zip runs on Termux:
  , a few minutes). Names are obfuscated
  but JADX keeps the original file names in `compiled from:` comments. Every SET is in
  HeyMelody as `a(address, <cmd decimal>, <payload>)`
  (`1028` = `0x0404`);  () is the capability bit -> command table;
   holds Poll / Request / Notification managers. The per-model list
  () is downloaded and AES-GCM encrypted, not in the APK. Keep the decompile in
  the scratchpad, never in the repo.
- **Never guess a payload.** Confirming a read is cheap; a guessed write to the buds fails
  silently. This is the single most expensive mistake in the project's history — see PROTOCOL.md's
  "History of Getting This Wrong".
- **The SET and NOTIFY ANC encodings are different tables** and are not supposed to agree.
- **Adaptive's SET mask is `0x0800` (bit 11), not bit 8.** Its payload is `01 01 00 08`. The value
  `8` produced `01 01 00 01`, a different mode. Derive an index from the mask, never from a mode's
  position in the vendor's list.
- **`0x01F0` / `0x01F2` are not commands.** They are the command byte welded to its SEQ value. The
  real numbers are **`0x0106`** (battery) and **`0x0109`** (wearing). Searching logs for `0x01F0`
  finds nothing because no such command exists.
- **`0x0205`'s payload is count-first**, ids after: `[count][eventId...]`. `01 01 02 02` is a
  misread — it means "count=1, battery only", which the firmware ACKs while silently never pushing
  wear. **The app sends `03 01 02 03`** — count=3, then battery `01`, wearing `02`, **ANC `03`**
  (`OpoProtocol.registerNotifications()`). Omitting `03` makes bud-side ANC gestures look silent,
  which is exactly how that bug hid for three captures. Never shorten this list.
- **The gesture write is `0x0401`, not `0x0402`.** `0x0402` is ignored in total silence and cost a
  session. **A wrong command number fails silently**, which is why every gesture write re-reads the
  table and diffs it.
- **`TotalLen` is LEB128.** All frames the app builds today are under 127 bytes so a single-byte
  writer is correct, but `OpoProtocol.buildPacket()` does not implement real LEB128 — a much larger
  key-function table would need it.
- **Payload always starts at byte index 9** (`BudsConnectionManager.payloadOf()`).

### Gesture configuration (the newest, most delicate code)

- The `function` values are **MEASURED, not guessed** — see `GestureAction.functionByte` and
  PROTOCOL.md §6. **Do not re-derive them**; a previous session declared the theory refuted on the
  strength of one frame that turned out not to be the same kind of event at all.
- **`writeGestureBinding(side, ...)` takes NO button list from our code.** It writes every slot the
  bud actually has for that action, excluding only the `BUTTON_ON_CALL_GUESS` (`0x06`) group.
  **Never reintroduce a hardcoded button group** — the bud's slot layout differs per bud and
  normalises itself between writes, so any fixed group is wrong about half the time. This broke
  slide twice, in opposite directions.
- `KeyFunctionParser.HEADER_SIZE = 2` — the reply is `<status> <count> <entries>`. The OSS source
  models one entry, not the envelope, and taking `payload[0]` as the count cost an off-by-one.
- **The hold's function byte does not control the ANC cycle.** Clearing it to `0x00` does not stop
  the cycle. The cycle's membership is a *separate* command, `setSupportNoiseReduction`
  (`0x0404`), read back with `0x010C` payloads `02 01` / `02 03` / `02 04`. Not wired yet.
- Baseline for the diff is persisted in its own prefs (`QuickBudsKeyFnDiff`) because the experiment
  needs a reconnect that can restart the process. **An empty reply must never overwrite a good
  baseline.**
- The reply is printed in three places, so a capture is readable even if the parse is wrong: the raw
  `RX:` line, `BudsConnectionManager`'s `KEYFN:` line, and `LogDecoder` — **all end with `RAW=[...]`**.

### Where ANC lives

Adding an ANC mode means touching all of these, or the surfaces drift apart:

`AncModes` (per-model bits from `assets/models.json`, both directions; PROTOCOL.md §5) ·
`OpoProtocol.anc(bit)` · `BudsConnectionManager` (`sendAnc(mode)`, `lastAncLevelSent`) · `BudsService` routing ·
`WidgetStateStore` (state + `*IsActive()`) · `WidgetSettings.MODES` (widget modes) ·
`WidgetActionReceiver` · `MainActivity` segments. (The Quick Settings tile was removed, [USER] 2026-09-26.)

## UI conventions and traps

- **Theming is attribute-based.** Colours are read from theme attributes during inflation
  (`ThemeRes`), not applied by walking the view tree afterwards. `ThemeRes.select(this)` must run
  **before** `super.onCreate` in every activity.
- **Never add `android:theme` to `<application>` in the manifest.** It makes the framework resolve a
  theme during Activity attach, which breaks `applyOverrideConfiguration()` — the app then crashes
  on launch. Per-activity theming is the only supported path here.
- **Prefs file name lives in one place**: `ThemeRes.PREFS_NAME` (`"QuickBudsPrefs"`). `QuickBudsApp`
  and `DevToolsActivity` used to hardcode the literal — keep all three in sync or prefs split in two.
- **One font: `sans-serif`.** `Theme.App.Base` sets `android:fontFamily` because DeviceDefault text follows
  the OEM font (Nothing's differs) while code-set typefaces (`DEFAULT_BOLD`, `sans-serif-medium`) and canvas
  text are `sans-serif`, so screens mixed two ([USER] 2026-09-27). Widget TextViews set it too (the launcher's
  theme applies there). Use weights of `sans-serif` only; never another family.
- **Portrait only, no rotation** — `[USER]` 2026-09-22. Every `<activity>` in the manifest carries
  `android:screenOrientation="portrait"`; there is no `<application>`-level equivalent, so a new
  activity needs the attribute added by hand or it will rotate. No screen in this app has a landscape
  layout, so an allowed rotation would only stretch the portrait one, not show a real design.
- **Widget icon sizing:** `android:width`/`android:height` in a vector must be the **layout** size,
  never the source viewBox. A 1024dp intrinsic size once built 10752px bitmaps → 462 MB →
  "RemoteViews exceeds maximum bitmap memory usage", crashing on connect.
- **Never size these icons with `wrap_content` + `adjustViewBounds`.** Intrinsic size is stored in
  whole pixels at density, so the ratio drifts and the artwork is clipped at the box bounds. Set the
  box explicitly.
- **All icons in a set must share units-per-dp and layout height**, with widths from each icon's own
  ratio. The battery icon misalignment was a **fill fraction** difference (~100% vs ~79%), not an
  aspect-ratio bug — every measured box and ratio was correct.
- **Never put user-visible output on a packet-listener path.** Status events on the main screen are
  silent by design; several call sites fire once per packet and a toast there storms the UI.
- **A `when` on UI string keys with no `else` is a silent-failure generator.** The Transparency bug
  was `"Trans"` vs `"Transparency"` and hid for weeks. Also check for a state write with no matching
  refresh call — that was a widget one-way sync.
- **The download folders are `QuickBudsCrash/` and `QuickBudsLogs/`**, written through
  MediaStore. Do not write to public Downloads with a plain `File` — it is scoped-storage-blocked on
  API 29+, and the failure is silent if the result is not checked.

## Working with the developer

- **He handles device testing and design decisions.** The agent handles reverse-engineering, parsers,
  protocol work and code, including git commits. **Ask before every push, every time**, whether he
  says "commit" or the agent proposes it — a standing "yes" to
  push once is not a standing "yes" forever.
- **Revert first, reason after.** When he reports a regression and asks for a revert, do the revert,
  then investigate. Arguing has cost a whole session before.
- **He is the only source of on-device results.** Ask for the specific log line or measurement you
  need; one sentence from him has replaced an entire diagnostic loop.
- **Docs are updated in the same commit as the change**, and protocol changes always land with
  their PROTOCOL.md entry.
- **Do not mark roadmap items with release versions.** Releases happen when he feels the app is
  ready, not on a schedule.

## Dev tools inside the app

Dev Tools is the live packet log (Human-readable / Raw hex, long press copies), Clear, Export
(`Download/QuickBudsLogs/`), Reconnect, Disconnect and Crash test (confirm first). Built in code like Settings
(no layout XML): an `AncSegmentedView` with no icons is the tab switch (text-only, 48dp), the actions are one
card of icon + label columns, and TX / RX are accent-coloured spans in the log card. The layout report, screenshot-to-text and
widget reports were deleted 2026-09-27 ([USER]): adb covers them (`uiautomator dump`, `screencap`, `logcat`).
They are in git history before that date if ever needed.

**Crash logger:** `QuickBudsApp.attachBaseContext` installs the handler before anything else runs (writes
`Download/QuickBudsCrash/`, plus two private copies for the next-launch dialog). Dev Tools' **Crash test**
button arms `devCrashOnLaunch`; the next launch throws once there, which proves an early crash is caught
(verified on device 2026-09-27).

## Current open items

- **Slide up vs slide down** — settled, nothing to do: the firmware maps the two directions itself
  when the slide is set through our app, as with HeyMelody ([USER] 2026-09-26).
- **Light theme** — replaced by the **White** preset (UI revision step 1, 2026-09-26; redesigned
  2026-09-27). Check screens on White and on a light custom preset when changing colours.
- **Case lid state** — settled 2026-09-25: no lasting lid state exists (PROTOCOL.md §8); a close only
  stops the reconnect retries. `ic_case.xml` stays (the status view uses it). Case charging is only
  reported with the lid open, so it is **not shown, by decision** (PROTOCOL.md §7).
- **Localisation** (2026-09-26, [USER]: the Asian OPPO / OnePlus markets): `values-zh-rCN`, `values-zh-rTW`,
  `values-hi`, `values-in` (Indonesian), `values-vi`, `values-th`; EU (2026-09-27, [USER]): `values-de`, `-fr`,
  `-es`, `-it`, `-pl`, `-nl`, `-pt`, `-ro`; more (2026-09-27, [USER] "all of them"): `values-ru`, `-uk`, `-tr`, `-ja`,
  `-ko`, `-ms`, `-fil` (3-letter qualifier; verified on device), `-bn`, `-cs`, `-hu`, `-el`, `-sv`. Machine-drafted,
  marked as such in each file. No review pass: users report wording on GitHub ([USER] 2026-09-27). A new user-facing string needs all 26 (lint does not
  stop a missing one; it falls back to English).
  `generateLocaleConfig` (build.gradle.kts + `res/resources.properties`) lists them for Android 13+'s per-app
  language, which Android's own per-app screen uses. Constant strings are `translatable="false"`. The language row opens
  `LanguageActivity` (our own list, `ThemeRes.LANGUAGES`, native names; the chosen row has an accent label and check like
  the EQ list; `configChanges="locale|layoutDirection"` so a pick rebuilds it in place, recreating it flickered): `LocaleManager` on 13+, below that an
  `appLanguage` pref applied in `ThemeRes.select` via `Resources.updateConfiguration` (widget and notification
  stay in the system language there). A new locale needs its line in `ThemeRes.LANGUAGES`. Dev Tools labels
  stay English-only by design; the crash and permission dialogs were moved into `strings.xml`.
- **`0x0500` time request / `0x0501`** — **skipped by decision** (2026-09-25): no feature depends on
  them and no OSS client answers them (PROTOCOL.md §9). Do not raise again.
- **Undecoded families** — broadcast codes `0x04`/`0x08`/`0x0B`, the recurring
  `F1` family (`AA 0D 00 00 04 02 FF 06 00 F1 01 01 XX YY 02`), and `02 01 08 0C 02` /
  `02 01 07 0B 02` (these carry non-multiples of ten — possibly a fine-grained battery/case field).
  Also `0x0510`, a Spatial Audio notify. **Do not guess any of these from a couple of samples.**
  See PROTOCOL.md §12.

### Connection robustness — known rough edges

From `bluetooth/BudsConnectionManager.kt`.

- The **UUID `00001107-...` never connects** on Buds 4 (~5 s timeout), so `0000079A-...` is tried
  first since 2026-09-25 — as HeyMelody does (bugreport 2026-09-24). `1107` stays second for other models.
- **The buds serve one control app at a time.** With QuickBuds connected, HeyMelody hangs on
  "Connecting…", and QuickBuds' reconnect grabs the link back whenever HeyMelody cycles it. For a
  HeyMelody capture, stop our service from Quick Settings → Active apps (a plain `am force-stop`
  can be undone by our own reconnect). HeyMelody also greys out Earbud controls unless both buds are out.
- `Connection reset by peer` / `Broken pipe` appear during long sessions.
- **A lid close is not a lost link.** The buds push all-zero wear just before dropping; the manager
  logs `Case closed` and does not retry (2026-09-25).
- **Auto-connect follows the audio link (2026-09-26, `[USER]`-requested, confirmed by him 2026-09-27).**
  `KeepAliveReceiver` connects RFCOMM when A2DP or HFP reports `STATE_CONNECTED`, not on the bare ACL
  link (the buds are not ready then, which meant a failed attempt and 5 s retries). ACL still sends a
  FORCE_CONNECT with `EXTRA_DELAY_MS` = 8 s as a fallback; it does nothing if already connected. The
  manager's 3x5 s retry stays as the failure fallback. Confirm with logcat `KeepAlive: audio profile state=2`.
- **Background service switch** (`BudsService.PREF_BACKGROUND`, default on, Settings › General). Off:
  `KeepAliveReceiver` and the widget receiver do not start the service, and `QuickBudsApp` calls
  `stopService` when no activity is visible. `MainActivity` unbinds in `onStop` (and binds again in `onStart`) while it
  is off, because a bound main screen kept the link up after Home, and Back no longer destroys it on Android 12+
  (found on device 2026-09-27).
  `BudsService.onDestroy` now closes the RFCOMM link. Turning it off asks first (widget warning).
- **Only a user connect (pill, Dev Tools) asks Android for phone audio** (`EXTRA_WITH_AUDIO`). Automatic
  connects (ACL receiver, retries, reconnect after loss) leave A2DP to the system: asking for it while
  the system auto-connects left audio stuck on auto-connect. Fixed 2026-09-25, confirmed by him.
- **Reconnect after a lost link — FIXED 2026-09-23, confirmed by him.** Nothing used to retry after
  `Connection lost` unless Android fired ACL_CONNECTED (a codec switch drops our RFCOMM 2-3 times while
  the link stays up) — likely also the old "14-minute gap". `reconnectAfterLoss()` retries 5x with
  growing delays; a deliberate disconnect cancels it.

## Main screen structure — SPEC 3.1-3.3 (UI revision step 3, 2026-09-26)

Header (48dp: model list button (`btnModel`), then the status chip = Connect/Disconnect button (min 128dp), then `headerTitle` (an empty spacer here;
Home layout's title), dev-tools icon, settings cog; the device name sits under the rings in `batteryCard`, [USER] 2026-09-27)
over a `ScrollView` named `mainScroll` holding `tiles`. Each tile is an include layout whose ROOT id is
its stable id: `batteryCard` (tile_battery), `ancRow` (tile_noise), `featureList` (tile_settings), in that
fixed order. The rows inside `featureList` are ordered and hidden one by one (see Home layout above).

1. `batteryCard` — `BudsStatusView`: three 90dp rings (outline track, accent arc from 12 o'clock),
   glyphs at their SVG ratio inside 42x56 / 58x42 boxes (scaled with the ring), percentage 21sp (always `text`), label "Left · In ear" / "Out of ear" / "In case" (shrinks to fit). In case is only the grey
   glyph and the label; the SPEC's case badge was tried and removed ([USER] 2026-09-26).
   Disconnected: same size, track only, disabled glyphs, "—", bare names.
   The device name under the rings is `ModelCatalog.current()` (detected by product id + Bluetooth
   name, or picked by hand); the header's `btnModel`, before the connect pill, opens `ModelActivity`.
2. `ancRow` — "Noise control" label, `AncSegmentedView` (4 icon+label segments, accent fill slides; -1 =
   neutral) and `ancLevels`, the Low/Medium/High pills shown only in ANC. They replaced the strength bottom
   sheet and the caption. The ANC segment applies the last level seen (`homeAncLevel`, default Medium).
3. `featureList` — rows built by `MainActivity.buildFeatureRows()`: low latency, Hi-Res, 3D audio, EQ,
   **Dual connection** (home screen only, not in the hub, [USER] 2026-09-26) and **Earbud settings** (`ic_bud_left`; the SPEC's
   `ic_earbud` rendered broken and was dropped), which opens `EarbudSettingsActivity`, the hub for the buds
   themselves. New firmware settings go in the hub, not on the main card.

**Disconnected (`setConnectedUi`)**: nothing collapses. Every tile but the battery goes to alpha 0.35 and
is disabled recursively; switches are set neutral **quietly** (`syncingFeatures`, which the game-mode
listener also honours, or the neutral state would send a write) and repainted from the buds on connect.

**The main screen carries no log**, by design — Dev Tools owns logging. `appendStatus()` only
appends to a bounded in-memory tail. See the note above about not putting user-visible output on a
packet-listener path.

## Widgets (redesigned 2026-09-27, design/widgets/WIDGETS.md)

One provider per size in `widget/AncWidgetProvider.kt`, one renderer (`QuickBudsWidget.build`), layouts
generated as one family (`widget_pages` 2x2, `widget_pages_m` 4x2, `widget_pages_l` 3x3, plus the mode list grids
`widget_grid` / `widget_grid_l` (3x3, larger icons and labels) and `widget_disconnected`; all but the last come from
`scripts/widget-layouts.py`, edit it and rerun, never the XML):
**2x2** (`BatteryWidgetProvider`), **3x3** (`LargeWidgetProvider`, the 2x2 layout scaled up) and **4x2**
(`AncWidgetProvider`, 3x2 until 2026-09-27) ([USER] 2026-09-27: no more sizes for now). All fixed size,
`resizeMode="none"`. The old class names are kept so placed widgets survive; the 4x1 strip and the 2x2 controls
widget (`SmallWidgetProvider`) are gone. No model name on any widget ([USER] 2026-09-27).
**Every size has two pages, battery and controls** ([USER] 2026-09-27): the page is
stored per widget id (`widgetPage_<id>`, battery first) and swapped by the
`w_swap` buttons (top-right corner, except the 2x2 / 3x3 battery page: end of the case bar) or, with `widgetDoubleTap`, a double tap: every tap then carries `EXTRA_PAGE` and the receiver waits 200 ms ([USER] 2026-09-28; 400 ms felt slow, and instant buttons left no room to double tap on the controls page)
for a second one before running it (`WidgetActionReceiver.doubleTap`). Battery pages: 2x2 and 3x3 = two bud
panels + case bar, 4x2 = three panels sized from `getAppWidgetOptions` (`ringDp`).
**The mode button has two copies** (`w_mode_fills` / `w_mode_flip`, [USER] 2026-09-27: better switching
animations): a mode change fills the hidden copy and slides to it. `WidgetSettings.modeSlot` stores the copy and the mode per widget; the flips
follow the same rule as the pages below. The mode list is filled on every update, so a pick slides out lit. All use the ACTIVE palette: white shapes tinted with `ImageView.setColorFilter` (every API level), ring
and case-bar bitmaps drawn per update, so a palette change calls `refreshAll` (PaletteStore does).
**Disconnected, every size shows only the main screen's Connect chip** ([USER] 2026-09-27); it sends
FORCE_CONNECT with audio, or opens the app when the background service is off. A new id in a widget layout needs
its line in the renderer, or RemoteViews fails at apply time ("Can't load widget"). Check a widget change with
`adb logcat` while the widget updates.

- **Settings** (`WidgetSettings`, screen `WidgetSettingsActivity` under Settings > Appearance): tap = Next mode
  (default) or Open list, the ordered checked modes (2 to 6: the list has six cells and there are seven modes since Smart; same list for both), Low latency button (default on),
  Open app on tap (default **off**: a background tap does nothing). Every setter calls `refreshAll`.
- **Slides only, no fades** ([USER] 2026-09-27), 280 ms, `anim/widget_enter_*` / `widget_exit_*`. The pages move like
  a carousel: battery enters and leaves on the left, controls on the right, so battery -> controls moves left and
  back moves right. The mode list and the mode button enter from the left and leave to the right.
  A RemoteViews flipper cannot change its animation at runtime, so each page has its own flipper.
- **Two nested `ViewFlipper`s** (2026-09-27): outer `w_pages` holds
  `w_content` and the mode list `w_page2`; inside `w_content`, `w_slide0` holds an empty view then the
  battery page `w_page0`, and `w_slide1` an empty view then the controls page `w_page1` (child 1 = shown). The host reapplies a same-layout update onto its views,
  so a flipper animates when its child changes. The price: **every state `build()` sets must be set both ways**
  (visibility, click intents, null included), or the last update's value sticks. Both pages are filled on every
  update so the page sliding out shows current values. `setDisplayedChild` replays the animation even for the same
  child, and the service resends the whole cached views on every update (partial ones too, so
  `partiallyUpdateAppWidget` does not help). So each flipper child gets its visibility set directly every time,
  and each flipper's `setDisplayedChild` goes out only in the update that changes its child (`widgetChild_<id>`:
  0 battery, 1 controls, 2 list). Sending it every time made every widget flash ([USER] 2026-09-27). Never an Activity. `WidgetActionReceiver` stamps `widgetListAt_<id>`, and a plain 5 s main-thread handler closes it if
  the stamp is unchanged; `build()` treats a stamp older than 5 s as closed in case the process died. **Never
  `goAsync` for that wait**: it holds the receiver, broadcasts queue behind it, and a pick lagged up to 5 s.
- **No automatic page change** ([USER] 2026-09-27): after a mode pick or Low latency toggle the widget stays on
  the controls page; the user swaps back (tried and dropped: sliding back to battery after the change).
- Widget taps use the existing `ANC_SELECT` / `GAME_TOGGLE` path (optimistic store write, service read-back
  corrects). Next mode is computed in the receiver and sent as an `ANC_SELECT`.

### Widget tap flow

```
Widget tap
  -> AncWidgetProvider PendingIntent
  -> WidgetActionReceiver
  -> WidgetStateStore.write
  -> broadcast ACTION_WIDGET_COMMAND (short action name)
  -> BudsService.widgetCommandReceiver
  -> executeWidgetCommand
  -> manager.sendAncXxx()
```

The action travels as the **short** name; a full-string-vs-short-name mismatch here was a real bug
(use short names as extras). The send executor was also reworked to a fresh thread per send, because
a shared one hung.

## Settled design decisions — do not re-litigate

- **Red accent across the app** (`?attr/appColorAccent`, `[USER]` 2026-09-23): settings-row icons, the
  EQ curve and sliders, the status rings, the noise-control highlight. (The Connect pill is no longer
  red when disconnected: SPEC 3.2 wins, [USER] 2026-09-26.) The EQ curve / level slider / status card / noise pill share one drawn visual language.
- **The case icon keeps its LED dot**, and the lid cut stays full width — no hinge bulge or opening.
- **Icons keep their SVG's true ratio** (buds 176x272, case 496x400). `BudsStatusView` fits each into
  its ring by that ratio; the widget still uses its own sized boxes.
- **Launcher and notification icon** ([USER] 2026-09-27): the app's own bud glyphs (`ic_bud_right` on the
  left facing left, `ic_bud_left` on the right facing right), 4 units apart, as large as the adaptive safe
  circle allows (`ic_launcher_foreground.xml` has the measurement). `ic_stat_buds` is the same pair. minSdk 26
  means only the adaptive icon is used; the legacy PNG mipmaps were deleted. Find my earbuds shows no bud icons.

## Repo hygiene

- **`local/` holds two folders now**: `logs/` (packet captures cited as evidence by PROTOCOL.md) and
  `svgs/` (the source SVGs the wear icons were traced from, named in the drawables' own headers).
  **`local/` is local-only (PC and phone) — git-ignored, never committed or pushed** (`[USER]` 2026-09-22; it was
  tracked until then and still sits in older commits' history). Doc references to `local/logs/`
  point at the developer's machine, not the repo.
- **`local/notes/` and `local/NEXT-SESSION.md` are gone, on purpose** — all superseded, and their
  surviving content was folded into this file, PROTOCOL.md, ROADMAP.md and PACKET-CAPTURE.md.
  **Do not recreate a notes folder or a session-plan file.** Scattered per-purpose notes are exactly
  the drift that CLAUDE.md exists to stop — put new durable knowledge here, or in PROTOCOL.md if it
  is about the wire format.
- Runtime `*.log` files written by the app are ignored — they are regenerated every run. Handed-over
  captures (`*.log.txt`) are evidence and **are** tracked.
- **README screenshots** live in `docs/screenshots/` and are retaken with
  `scripts/readme-screenshots.sh [adb-serial]` (buds connected, phone in English, Pillow installed; on Termux `pkg install python-pillow`). It
  opens every screen by visible text (never toggles anything), so renaming a row or screen label breaks it. It stops mobile-mcp's
  device server first, because that holds UiAutomation and `uiautomator dump` then dies with exit 137.
  Widgets: each placed size on the LAST home screen page is cropped to `widget-<size>.png`.
- Root holds only `README.md`, `LICENSE` and `CLAUDE.md` (it must stay in root to load automatically).
  Every other doc lives in `docs/`: `ROADMAP.md`, `ROADMAP-DONE.md`, `PROTOCOL.md`,
  `PACKET-CAPTURE.md` (`[USER]` 2026-09-25). `LICENSE` is the verbatim GPL-3.0 text; the copyright
  notice lives in the README.
