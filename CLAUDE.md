# CLAUDE.md — working notes for QuickBuds

What the code does not show: toolchain, conventions, decisions, mistakes already paid for.
`[USER]` = the developer's decision or report; do not re-litigate those.

## Project

**QuickBuds**: Android app (Kotlin, single module `:app`, sources in `app/src/main/kotlin/`) that controls
OnePlus / OPPO / realme earbuds over classic Bluetooth **RFCOMM**. No vendor app, root, Shizuku or ADB.
Package `com.spizganed.quickbuds`, GitHub `spizganed/QuickBuds` (branch `main`), GPL-3.0. Test setup:
OnePlus Buds 4 on a Nothing Phone (3a), Android 16. Desktop app in `desktop/` (see Desktop).

**Goal order:** parity with HeyMelody (only features HeyMelody shows for a real model), then our own
extras, then other models.

| Doc | Use |
|---|---|
| [docs/ROADMAP.md](./docs/ROADMAP.md) | Live plan + "decided against" list. Finished items move to ROADMAP-DONE.md. No release versions on items. |
| [docs/PROTOCOL.md](./docs/PROTOCOL.md) | **The wire format.** Read before any protocol work. |
| [docs/PACKET-CAPTURE.md](./docs/PACKET-CAPTURE.md) | Capture guide. |
| [docs/CONTRIBUTING.md](./docs/CONTRIBUTING.md) | Contributor build quickstart. |
| [docs/TOOLCHAIN.md](./docs/TOOLCHAIN.md) | Phone (Termux) build setup. Keep in step with Build › Phone. |
| [README.md](./README.md) | User-facing. New `[OSS]` sources get a Credits line. |
| `design/SPEC.md` | UI source of truth, except §1's view-tree PaletteApplier (theming here is attribute-based). |

The only docs at the root are `README.md`, `LICENSE` (verbatim GPL-3.0) and this file. No notes folders or
session-plan files: durable knowledge goes here or in PROTOCOL.md.

## Build

- Gradle 9.6.0, AGP 9.4.0, Kotlin 2.4.0. AGP 9 compiles Kotlin itself: never add the
  `org.jetbrains.kotlin.android` plugin; the root `kotlin-gradle-plugin` classpath only pins the version.
- `compileSdk`/`targetSdk` 37, `minSdk` 26, Java 8. Only dependency: `androidx.core:core`. On target 37 an
  RFCOMM `read()` returns `-1` on a dropped link ("Connection lost"); portrait lock is ignored above 600dp.
- **Device testing always uses `./gradlew assembleRelease`** `[USER]`, never debug (signature clash).
  `adb install -r`; `adb logcat` for what in-app logs miss.
- **Android app version only in `app/build.gradle.kts` `defaultConfig`** (ignored on `<application>`).
- **PC (CachyOS, fish shell):** `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`, `ANDROID_HOME=~/Android/Sdk`.
  `sudo pacman` needs no password; other sudo commands are his.
- Wireless adb: use `~/Android/Sdk/platform-tools/adb` (Arch's has no mDNS); `adb mdns services` gives
  ip:port. New pairing: `adb pair <ip:port> <code>` right after he reads the code (stale = `protocol
  fault`), then `adb connect` the **other** ip:port from the main Wireless debugging screen.
- **adb tests** `[USER]`: never leave auto-rotate on (`settings put system accelerometer_rotation 0` after
  every test); launch with `am start -n`, never `monkey`.

### Signing and releases

- Release key: `local/keys/quickbuds-release.jks` + `keystore.properties`. **Never commit them or print the
  password.** The in-app updater only installs over the same key.
- Release: `./gradlew assembleRelease bundleRelease`, name `QuickBuds<version>.apk` / `.aab`, plus desktop
  archives from `scripts/desktop-dist.sh <version>` (into `desktop/dist/`); bump `desktop/Cargo.toml`
  `version` with the release. Then `gh release create v<version> <files> --target <full sha> --title
  "QuickBuds <version>"`. One release carries Android and desktop files `[USER]`; no local copies.
  `desktop-v*` tags are for desktop-only fixes (the phone's updater skips them; the desktop's takes both). **No GitHub Actions** `[USER]`.
- **Notes cover every user-visible change since the last tag**: read `git log v<previous>..HEAD` first.

### Phone (Termux, reached over SSH)

Setup is in TOOLCHAIN.md and lives outside the repo. Repo at `~/projects/QuickBuds`; APK at
`~/qb-build/_app/outputs/apk/release/app-release.apk`. **Never put the aapt2 override in the repo** (breaks
the PC build). Desktop on the phone (TOOLCHAIN.md): `cargo test` in a Debian proot; release archives cross-built
there (Windows) and in an Ubuntu 22.04 proot (Linux x64, glibc 2.35). Both proots were removed after
4.4.0 to free space: set them up again from TOOLCHAIN.md when needed. After an adb test on the phone
itself (not over SSH), bring Termux to the front.

## Working with the developer

- Commits authored as `spizganed <328350196+spizganed@users.noreply.github.com>` `[USER]` (never the gmail
  one); keep the `Co-Authored-By` trailer.
- **Ask before every push, every time.**
- He does device testing and design decisions; the agent does protocol, parsers, code and commits. Ask
  him for the exact log line you need.
- **Revert first, reason after** when he reports a regression and asks for a revert.
- Docs change in the same commit as the code; protocol changes with their PROTOCOL.md entry.
- Releases happen when he decides.

## Protocol rules

Details and evidence in PROTOCOL.md.

- **HeyMelody is the `[VENDOR]` source for bytes**, studied for interoperability only. The repo carries
  only commands, payloads and per-model facts; **never vendor class, method or file names, code, or
  how-to notes**.
- **Never guess a payload.** Guessed writes and wrong command numbers fail silently: read writes back and
  diff.
- Gestures: `fn` values are measured, do not re-derive. **Never hardcode a button group** (the table is
  per bud). A new gesture option needs its `supportBit` in `GestureAction` and `ORDER`. The diff baseline
  (prefs `QuickBudsKeyFnDiff`) is never overwritten by an empty reply.
- **Adding an ANC mode touches all of:** `AncModes`, `OpoProtocol.anc(bit)`, `BudsConnectionManager`
  (`sendAnc`, `lastAncLevelSent`), `BudsService` routing, `WidgetStateStore`, `WidgetSettings.MODES`,
  `WidgetActionReceiver`, `MainActivity` segments.
- **Behave like HeyMelody toward the buds** (some drop the link otherwise, issue #2): answer their
  requests in `answerFor` / `answer_for` (PROTOCOL.md §9); subscribe only to offered events (§4).
- **Settled, do not raise again:** case lid has no lasting state and case charging is not shown;
  undecoded families (PROTOCOL.md §12) are not guessed from a few samples.
- A battery report that leaves a part out keeps its last level; Settings › "Hide old battery levels"
  (`clearMissingBattery` / desktop `clear_battery`, default off) clears it instead `[USER]` (issue #5).

## Connection

- **Which buds:** `BudsDevice.find()`; desktop: the vendor service `079A`/`1107` in the system's cached list,
  or an address that answered before (`settings.json` "buds"), the name last. **Which model:** HeyMelody's
  order, name and id, then name, then id (PROTOCOL.md §4; some buds reuse another model's id,
  issue #5). **Never hardcode an address** (phone or desktop).
- Auto-connect follows audio (A2DP / HFP connected; ACL gives an 8 s fallback). A deliberate disconnect
  cancels `reconnectAfterLoss()`; a lid close (all-zero wear push) does not retry.
- **Only a user connect asks Android for phone audio** (`EXTRA_WITH_AUDIO`). Exception: the reconnect
  after a write that restarts the buds (power saving, codec) asks once (`audioAfterRestart`).
- The buds serve one control app per connected device (Dual connection: phone and desktop both work). For
  a HeyMelody capture, stop our service from Quick Settings › Active apps (`am force-stop` gets undone by
  our reconnect).
- Background service pref `BudsService.PREF_BACKGROUND` (default on).

## UI

UI work never touches protocol or wear logic. No Material Components `[USER]`.

### Theming

- `ThemeRes.select(this)` runs **before** `super.onCreate` in every activity. **Never put `android:theme`
  on `<application>`** (crashes on launch). Never add `uiMode` to `configChanges`.
- Palette = six `?attr/appColor*` attributes (`values/themes.xml`); custom presets swap them through
  `ThemeRes.PaletteFactory`. **No XML shapes with `?attr` colours**: build them in code
  (`ThemeRes.card/chip/iconButton/sheet/pill`), never a hand-made `GradientDrawable` for a button.
- Check colour changes on White and on a light custom preset. Contrast warnings never block saving.
- Prefs file name only via `ThemeRes.PREFS_NAME`.
- Fonts via `ThemeRes.regular / medium / bold / headline`, never `DEFAULT_BOLD` or `sans-serif-medium`.
- **Portrait only**: every `<activity>` needs `android:screenOrientation="portrait"`.
- Red accent default (`?attr/appColorAccent`); battery percentage is always `text` colour, never red.

### Dot matrix style

`ThemeRes.nothing` (pref `styleNothing`), one switch for app and widgets. **User-visible name: "Dot
matrix"** `[USER]`, never "Nothing" (trademark) or "Pixel"; code keeps `nothing` / `_n`.

- Font: bundled Doto on every phone `[USER]` (`ThemeRes.dotFont`). Wide: long labels wrap.
- Cards are dot outlines `[USER]` 2026-09-30 (`ThemeRes.group()`, `SettingRowFactory.card`).
- `DotArt` draws without antialiasing, pitch 2.2dp rounded to whole px. Dot outlines = shape filled in the
  outline colour with the fill a cell inside, never a thin stroke. **Every knob is `DotArt.knob`** `[USER]`,
  never `drawCircle`. Small action icons are rule-drawn `DotArt.Pattern`s `[USER]`, not sampled vectors.

### Shared components and screens

- New screens use `SettingRowFactory`. Confirm dialogs use `ConfirmDialog.show()`.
- **Selection is an outline, never a checkmark** `[USER]` (`ThemeRes.selectedBorder()`).
- **Motion helpers** `[USER]`, never new animators: `Motion.slide`, `SelectionSlider` (call `moveTo` after
  each render), `ThemeRes.recreateFaded`, `ThemeRes.sinkOnPress` (pair with `Haptics.commit`).
- Lists you pick from: `SettingRowFactory.splitList` + `addSplit`; update rows in place (see
  `EqActivity.Choice`).
- **Compact sizing** `[USER]`: ~10-15% under SPEC so home fits without scrolling; touch targets ≥ 44dp.
- Haptics: `Haptics.commit(view)` on user actions only, never programmatic changes. Widgets:
  `Haptics.tick(context)`.
- **New firmware settings go in the Earbud settings hub** (`EarbudSettingsActivity`).
- **Rows and pages show only what the buds have** (`rowSupported`, desktop `Has`); a new one is gated in both apps.
- **A new home row needs its key in `buildFeatureRows()` AND `HomeLayoutActivity.ROWS`.**
- Hearing profile is the vendor's "Golden Sound": **never "Golden Sound" in a user-visible string.**
- Colour edits save on commit; rows are never rebuilt mid-drag.
- Dev Tools labels stay English-only.
- Disconnected main screen: switches set neutral **quietly** (`syncingFeatures`) so no write goes out.
- **No log on the main screen. Never put user-visible output on a packet-listener path** (storms the UI).

### Icons

- Row and button bud icon: **`ic_earbud`** `[USER]`; `ic_bud_left` / `ic_bud_right` only where large.
- Vector `width`/`height` = layout size, never the viewBox (a 1024dp icon blew RemoteViews' limit). **Never
  size icons with `wrap_content` + `adjustViewBounds`**; set the box.

### Localisation

26 locales, machine-drafted. **A new user-visible string needs all 26** (lint does not catch a missing
one). Constant strings are `translatable="false"`. A new locale needs its line in `ThemeRes.LANGUAGES`.
No right-to-left languages `[USER]`.

### Traps

- A `when` on UI string keys with no `else` fails silently. Check every state write has its refresh call.
- Downloads go through MediaStore (`Download/QuickBuds/`), never a plain `File`.

## Desktop (`desktop/`)

Rust, one crate, Slint UI, `tray-icon`. `cargo test` / `cargo build --release`. Windows check from Linux:
`cargo build --release --target x86_64-pc-windows-gnu` (Bluetooth needs a real Windows).

- **A protocol change lands in `protocol.rs`, `OpoProtocol` and PROTOCOL.md in one commit.**
- **Shared, never copied:** `build.rs` turns `app/src/main/res/drawable/*.xml` into SVG and listed
  `strings.xml` keys into tables; `models.json` is `include_str!`'d. A new desktop icon or string = add
  its name to `build.rs`. Desktop-only strings are English for now (`Tr` in `ui/app.slint`).
- **Windows Bluetooth is Winsock** (`AF_BTH`, connect by UUID, port 0). Never WinRT.
- **Linux Bluetooth is BlueZ without bluetoothd profiles** `[USER]`: D-Bus `GetManagedObjects`, one SDP
  request over L2CAP, kernel RFCOMM socket. No async runtime, no `bluer`. Hardware check:
  `cargo test live_battery -- --ignored --nocapture`.
- Software renderer by default (~25 MB vs ~130 MB RAM).
- **Updater** (`update.rs`): GitHub releases via `curl`, unpacked with `tar`, binary renamed to `.old` and
  replaced, new one started. Never touches a binary in a folder it cannot write (opens the release page).
  Archive names (`QuickBuds<ver>-windows-x64.zip` / `-linux-x64.tar.gz`) are what it looks for: keep them.
  Test it on the invisible screen with a binary built as an older version, outside the repo.
- Settings: `settings.json` in `%APPDATA%\QuickBuds` / `~/.config/quickbuds` (`save_setting`). Dot matrix
  shapes are images from `dots.rs` (port of `DotArt`) through the `Dots` global; a dot icon is drawn at the
  size it is shown (`svg_at`), a scaled one blurs.
- **Linux tray is a menu** `[USER]` (AppIndicator has no clicks). **The quick panel exists only on
  Windows** (on Wayland a never-shown window sat in the taskbar).
- **Agent UI tests run on an invisible screen** (`scripts/desktop-vscreen.sh start|click|shot|stop`, Xvfb
  `:99`, scale 1), never on his desktop: clicks sent to his Wayland session do not arrive. The app there
  still connects to his real buds: click no control that writes unless the test needs it.
- `QB_BRIDGE=127.0.0.1:7979` talks to the buds through the Android app's Dev tools › Bridge; it does not
  test BlueZ.
- **Distribution `[USER]`:** Windows portable `.zip` (only the `.exe`), Linux `.tar.gz`. AUR `quickbuds-bin`
  in `desktop/aur/`: per release `_ver`, `_tag`, `sha256sums`, `pkgrel=1`, `makepkg --printsrcinfo >
  .SRCINFO`, test with `makepkg` + `namcap`, push to `ssh://aur@aur.archlinux.org/quickbuds-bin.git`
  (his account, **not published yet**). No Flatpak / AppImage / `.deb` until asked. No drivers, services
  or helper processes. `desktop-dist.sh` builds Linux in an Ubuntu 22.04 podman container (glibc 2.35).
  On the phone `makepkg` cannot run (root in proot, arm64): edit `.SRCINFO` by hand to match the PKGBUILD,
  and run `makepkg --printsrcinfo`, `makepkg` and `namcap` on the PC before the AUR push.

## Widgets

2x2 `BatteryWidgetProvider` and 4x2 `AncWidgetProvider` in `widget/AncWidgetProvider.kt` (old class
names kept so placed widgets survive), one renderer `QuickBudsWidget.build`.

- **Layouts are generated** by `scripts/widget-layouts.py`: edit the script and rerun, never the XML.
  Script `GEO` = renderer `Geo`. **A new id needs its line in the renderer** ("Can't load widget"). Never
  a plain `<View>`. Check with `adb logcat` while the widget updates.
- The host reapplies cached views: **every state `build()` sets must be set both ways** (null included),
  and each `setDisplayedChild` goes out **only in the update that changes it** (`widgetChild_<id>`), or
  every widget flashes.
- **Launchers ignore `@font/` in widget XML**: dot-style text is an ImageView filled by `setText`.
- Tap flow: `WidgetActionReceiver` (optimistic `WidgetStateStore.write`) → `ACTION_WIDGET_COMMAND` with
  the **short** action name → `BudsService.executeWidgetCommand`.
- Palette change calls `refreshAll`.
- **Decided `[USER]`, do not bring back:** resizing or more sizes; model name on a widget; a swap button,
  widget settings screen, hidden LL button or open-app tap (two pages swap by double tap only, no
  automatic change); cycle mode. Dot style: hollow or hidden glyphs, a lit box around bar digits, a split
  pill, the bar as tall as the icon, a third ring on the 2x2.

## Repo hygiene

- **`local/` is local-only, never committed** `[USER]` (`keys/`, `logs/`, `svgs/`).
- **README screenshots are retaken after every big UI change**, same push `[USER]`:
  `scripts/readme-screenshots.sh classic|dot-matrix [adb-serial] [widgets]` (buds connected, phone in
  English). **Run dot-matrix last** (it leaves the style set). It opens screens by visible text: renaming
  a label breaks it. Desktop shots (`docs/screenshots/desktop/`): the pages on the invisible
  screen (window 1100x760, `env -u WAYLAND_DISPLAY`), his PC's name masked in a throwaway build; the
  tray by hand with `spectacle`.

## Current state (2026-10-06)

- Latest release v4.4.0 (2026-10-06: model lookup name-first, "Hide old battery levels"), built entirely on
  the phone (TOOLCHAIN.md). AUR files bumped; `makepkg` / `namcap` not run (needs x86_64). The Windows zip is
  cross-built and has never run on Windows.
- **Next:** ROADMAP.md step 1 (PC version): his Windows zip test, the desktop Dot matrix font.
- **Waiting:** issue #2 owners testing 4.3.x (read their logs before changing anything); AUR account.
- **To decide on the PC (issue #5):** the reporter confirmed `0x010D` (our 23-id status query) is what
  drops the Enco Buds2 link (desktop; Android untested). Their ideas: a remote `exceptions.json` (no: server
  rule) or a `"skipStatus"` flag in `models.json` (fragile: vendor list, and these buds report another
  model's id). Agent's proposal: learn it per device in both apps; if the link drops within a few seconds of
  `0x010D`, remember that address and skip `0x010D` from then on (one loop before it settles; those buds
  then show no Hi-Res / 3D / low latency state, features they lack anyway). Open: does HeyMelody send
  `0x010D` to them, or a shorter id list (needs the decompile recreated)? No reply on #5 until he decides.
