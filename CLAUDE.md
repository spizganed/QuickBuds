# CLAUDE.md — working notes for QuickBuds

Rules and facts the code does not show. `[USER]` = the developer's decision. Do not argue it again.

## Writing rules

These apply to docs, commits, issue replies, release notes and chat.

- Use ASD-STE100 style. One idea per sentence, 20 words at most. Use active voice and present tense.
  Write instructions as commands.
- Use one term for one thing. Do not change between synonyms.
- Write the current fact, not the story. A past mistake gets one line: "Was: X. Is: Y." Add the reason
  only if it stops a wrong change.
- Keep evidence only where it prevents a repeat mistake ("tested, fails", `[USER]`). Git holds the history.
- No dates, except on decisions and open items. No release versions on roadmap items.
- No filler words and no hedging.

## Project

**QuickBuds** is an Android app (Kotlin, one module `:app`, sources in `app/src/main/kotlin/`). It
controls OnePlus / OPPO / realme earbuds over classic Bluetooth **RFCOMM**. No vendor app, root,
Shizuku or ADB. Package `com.spizganed.quickbuds`, GitHub `spizganed/QuickBuds` (branch `main`),
GPL-3.0. Test device: OnePlus Buds 4 on a Nothing Phone (3a), Android 16. The desktop app is in
`desktop/`.

**Goal order:** parity with HeyMelody (only features HeyMelody shows for a real model), then our own
extras, then other models.

| Doc | Use |
|---|---|
| [docs/ROADMAP.md](./docs/ROADMAP.md) | Live plan, current state, "decided against" list. Finished items move to ROADMAP-DONE.md. |
| [docs/PROTOCOL.md](./docs/PROTOCOL.md) | **The wire format.** Read it before protocol work. |
| [docs/PACKET-CAPTURE.md](./docs/PACKET-CAPTURE.md) | Capture guide. |
| [docs/CONTRIBUTING.md](./docs/CONTRIBUTING.md) | Contributor build quickstart. |
| [docs/TOOLCHAIN.md](./docs/TOOLCHAIN.md) | Phone (Termux) build setup. |
| [README.md](./README.md) | User-facing. A new `[OSS]` source gets a Credits line. |
| `design/SPEC.md` | UI source of truth. Section numbers are cited in code. |

The root holds only `README.md`, `LICENSE` (verbatim GPL-3.0) and this file. No notes folders and no
session-plan files.

## Build

- Gradle 9.6.0, AGP 9.4.0, Kotlin 2.4.0. AGP 9 compiles Kotlin itself: never add the
  `org.jetbrains.kotlin.android` plugin. The root `kotlin-gradle-plugin` classpath only pins the version.
- The only dependency is `androidx.core:core`. On target 37, an RFCOMM `read()` returns `-1` on a
  dropped link, and portrait lock is ignored above 600dp.
- **Device tests use `./gradlew assembleRelease`** `[USER]`, never debug (signature clash).
  Install with `adb install -r`. Use `adb logcat` for what the in-app log misses.
- **The app version is set only in `app/build.gradle.kts` `defaultConfig`.**
- **PC (CachyOS, fish shell):** `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`, `ANDROID_HOME=~/Android/Sdk`.
  `sudo pacman` needs no password. Other sudo commands are for the developer to run.
- Wireless adb: use `~/Android/Sdk/platform-tools/adb` (Arch's has no mDNS). Pairing: TOOLCHAIN.md §5.
- **adb tests** `[USER]`: set `settings put system accelerometer_rotation 0` after every test. Launch
  with `am start -n`, never `monkey`.
- **Phone builds** (Termux over SSH): setup in TOOLCHAIN.md. Repo `~/projects/QuickBuds`, APK
  `~/qb-build/_app/outputs/apk/release/app-release.apk`. **Never put the aapt2 override in the repo.**
  After an adb test on the phone itself, bring Termux to the front.

### Signing and releases

- Release key: `local/keys/quickbuds-release.jks` + `keystore.properties`. **Never commit them or print
  the password.** The in-app updater installs only over the same key.
- Build: `./gradlew assembleRelease bundleRelease`. Name the files `QuickBuds<version>.apk` / `.aab`.
  The updater needs the `.apk`.
- Desktop archives: `scripts/desktop-dist.sh <version>` (into `desktop/dist/`). Bump `desktop/Cargo.toml`
  `version` with the release.
- Publish: `gh release create v<version> <files> --target <full sha> --title "QuickBuds <version>"`.
  One release carries the Android and desktop files `[USER]`. Keep no local copies.
- `desktop-v*` tags are for desktop-only fixes. The phone updater skips them; the desktop updater takes both.
- **No GitHub Actions** `[USER]`.
- **Release notes cover every user-visible change since the last tag.** Read `git log v<previous>..HEAD` first.

## Working with the developer

- Commits are authored as `spizganed <328350196+spizganed@users.noreply.github.com>` `[USER]`, never
  the gmail address. Keep the `Co-Authored-By` trailer.
- **Ask before every push.**
- He does device tests and design decisions. The agent does protocol, parsers, code and commits. Ask
  him for the exact log line you need.
- **When he reports a regression and asks for a revert: revert first, find the cause after.**
- Docs change in the same commit as the code.
- He decides when to release.

## Protocol rules

Details and evidence are in PROTOCOL.md.

- **HeyMelody is the `[VENDOR]` source for bytes**, studied for interoperability only. realme Link is
  the source for realme models that HeyMelody lists without data (PROTOCOL.md §5). The repo holds only
  commands, payloads and per-model facts. **Never vendor class, method or file names, code, or how-to notes.**
- **Never guess a payload.** A guessed write or a wrong command number fails silently. Read every write
  back and diff it.
- Gestures: `fn` values are measured. Do not derive them again. **Never hardcode a button group** (the
  table is per bud). A new gesture option needs its `supportBit` in `GestureAction` and `ORDER`. An
  empty reply never overwrites the diff baseline (prefs `QuickBudsKeyFnDiff`).
- **A new ANC mode touches all of:** `AncModes`, `OpoProtocol.anc(bit)`, `BudsConnectionManager`
  (`sendAnc`, `lastAncLevelSent`), `BudsService` routing, `WidgetStateStore`, `WidgetSettings.MODES`,
  `WidgetActionReceiver`, `MainActivity` segments.
- **Behave like HeyMelody toward the buds** (some drop the link otherwise). Answer their requests in
  `answerFor` / `answer_for` (§9). Subscribe only to offered events (§4).
- **Settled:** the case lid has no lasting state. Case charging is not shown. Undecoded families (§12)
  are not guessed from a few samples.
- A battery report that leaves a part out keeps its last level. Settings › "Hide old battery levels"
  (`clearMissingBattery` / desktop `clear_battery`, default off) clears it instead `[USER]`.

## Connection

- **Never hardcode an address** (phone or desktop). Model lookup: PROTOCOL.md §4.
- Auto-connect follows audio (A2DP / HFP connected). ACL gives an 8 s fallback. A deliberate disconnect
  cancels `reconnectAfterLoss()`. A lid close (all-zero wear push) does not retry.
- **Only a user connect asks Android for phone audio** (`EXTRA_WITH_AUDIO`). Exception: the reconnect
  after a write that restarts the buds (power saving, codec) asks once (`audioAfterRestart`).
- The buds serve one control app per connected device. With Dual connection, phone and desktop both work.
- For a HeyMelody capture, stop our service from Quick Settings › Active apps. `am force-stop` is undone
  by our reconnect.

## UI

UI work never touches protocol or wear logic. No Material Components `[USER]`.

### Theming

- `ThemeRes.select(this)` runs **before** `super.onCreate` in every activity. **Never put
  `android:theme` on `<application>`** (crash on launch). Never add `uiMode` to `configChanges`.
- The palette is six `?attr/appColor*` attributes (`values/themes.xml`). Custom presets swap them through
  `ThemeRes.PaletteFactory`. **No XML shapes with `?attr` colours.** Build them in code
  (`ThemeRes.card/chip/iconButton/sheet/pill`). Never make a `GradientDrawable` by hand for a button.
- Check colour changes on White and on a light custom preset. Contrast warnings never block saving.
- Prefs file name only through `ThemeRes.PREFS_NAME`.
- Fonts only through `ThemeRes.regular / medium / bold / headline`. Never `DEFAULT_BOLD` or `sans-serif-medium`.
- **Portrait only:** every `<activity>` needs `android:screenOrientation="portrait"`.
- The accent is red by default (`?attr/appColorAccent`). Battery percentage always uses `text` colour, never red.

### Dot matrix style

`ThemeRes.nothing` (pref `styleNothing`), one switch for the app and the widgets. **The user-visible
name is "Dot matrix"** `[USER]`, never "Nothing" (trademark) or "Pixel". Code keeps `nothing` / `_n`.

- Font: bundled Doto on every phone `[USER]` (`ThemeRes.dotFont`). It is wide: long labels wrap.
- Cards are dot outlines `[USER]` (`ThemeRes.group()`, `SettingRowFactory.card`).
- A dot outline is the shape filled in the outline colour with the fill one cell inside, never a
  thin stroke.
- **Every knob is `DotArt.knob`** `[USER]`, never `drawCircle`. Small action icons are rule-drawn
  `DotArt.Pattern`s `[USER]`, not sampled vectors.

### Shared components and screens

- New screens use `SettingRowFactory`. Confirm dialogs use `ConfirmDialog.show()`.
- **Selection is an outline, never a checkmark** `[USER]` (`ThemeRes.selectedBorder()`).
- **Motion: use the helpers, never new animators** `[USER]`: `Motion.slide`, `SelectionSlider` (call
  `moveTo` after each render), `ThemeRes.recreateFaded`, `ThemeRes.sinkOnPress` (pair it with `Haptics.commit`).
- Pick lists: `SettingRowFactory.splitList` + `addSplit`. Update rows in place (see `EqActivity.Choice`).
- **Compact sizing** `[USER]`: about 10-15% under SPEC, so home fits without scrolling. Touch targets ≥ 44dp.
- Haptics: `Haptics.commit(view)` on user actions only, never on programmatic changes. Widgets:
  `Haptics.tick(context)`.
- **New firmware settings go in the Earbud settings hub** (`EarbudSettingsActivity`).
- **Rows and pages show only what the buds have** (`rowSupported`, desktop `Has`). Gate a new one in both apps.
- **A new home row needs its key in `buildFeatureRows()` AND `HomeLayoutActivity.ROWS`.**
- The hearing profile is the vendor's "Golden Sound". **Never put "Golden Sound" in a user-visible string.**
- Colour edits save on commit. Never rebuild rows during a drag.
- Dev Tools labels stay English-only.
- Disconnected main screen: switches go to neutral **quietly** (`syncingFeatures`), so no write goes out.
- **No log on the main screen. Never put user-visible output on a packet-listener path** (it floods the UI).

### Icons

- The bud icon for rows and buttons is **`ic_earbud`** `[USER]`. Use `ic_bud_left` / `ic_bud_right`
  only where large.
- Vector `width`/`height` = the layout size, never the viewBox (a 1024dp icon broke the RemoteViews
  limit). **Never size icons with `wrap_content` + `adjustViewBounds`.** Set the box.

### Localisation

26 locales, machine-drafted. **A new user-visible string needs all 26.** Lint does not catch a missing
one. Constant strings are `translatable="false"`. A new locale needs its line in `ThemeRes.LANGUAGES`.
No right-to-left languages `[USER]`.

### Traps

- A `when` on UI string keys with no `else` fails silently. Check that every state write has its refresh call.
- Downloads go through MediaStore (`Download/QuickBuds/`), never a plain `File`.

## Desktop (`desktop/`)

Rust, one crate, Slint UI, `tray-icon`. `cargo test` / `cargo build --release`. Windows check from Linux:
`cargo build --release --target x86_64-pc-windows-gnu` (Bluetooth needs real Windows).

- **A protocol change lands in `protocol.rs`, `OpoProtocol` and PROTOCOL.md in one commit.**
- **Shared, never copied:** `build.rs` turns `app/src/main/res/drawable/*.xml` into SVG and listed
  `strings.xml` keys into tables. `models.json` is `include_str!`'d. A new desktop icon or string: add its
  name to `build.rs`. Desktop-only strings are English (`Tr` in `ui/app.slint`).
- **Windows Bluetooth is Winsock** (`AF_BTH`, connect by UUID, port 0). Never WinRT.
- **Linux Bluetooth is BlueZ without bluetoothd profiles** `[USER]`: D-Bus `GetManagedObjects`, one SDP
  request over L2CAP, kernel RFCOMM socket. No async runtime, no `bluer`. Hardware check:
  `cargo test live_battery -- --ignored --nocapture`.
- Keep the software renderer (about 25 MB RAM against 130 MB).
- **Updater** (`update.rs`) looks for `QuickBuds<ver>-windows-x64.zip` / `-linux-x64.tar.gz`: keep those
  names. Test it on the invisible screen with a binary built as an older version, outside the repo.
- Draw a dot icon at the size it is shown (`svg_at`). A scaled one blurs.
- **The Linux tray is a menu** `[USER]` (AppIndicator has no clicks). **The quick panel exists only on
  Windows** (on Wayland a hidden window stayed in the taskbar).
- **Agent UI tests run on the invisible screen** (`scripts/desktop-vscreen.sh start|click|shot|stop`,
  Xvfb `:99`, scale 1), never on his desktop. Clicks sent to his Wayland session do not arrive. The app
  there still connects to his real buds: do not click a control that writes, unless the test needs it.
- `QB_BRIDGE=127.0.0.1:7979` talks to the buds through the Android app's Dev tools › Bridge. It does not
  test BlueZ.
- **Distribution `[USER]`:** Windows portable `.zip` (only the `.exe`), Linux `.tar.gz`. No Flatpak,
  AppImage or `.deb` until asked. No drivers, services or helper processes. `desktop-dist.sh` builds
  Linux in an Ubuntu 22.04 podman container (glibc 2.35).
- **AUR** `quickbuds-bin` in `desktop/aur/`. Per release: set `_ver`, `_tag`, `sha256sums`, `pkgrel=1`.
  Run `makepkg --printsrcinfo > .SRCINFO`, then test with `makepkg` + `namcap` on the PC. Push to
  `ssh://aur@aur.archlinux.org/quickbuds-bin.git` (his account, not published yet).

## Widgets

2x2 `BatteryWidgetProvider` and 4x2 `AncWidgetProvider` in `widget/AncWidgetProvider.kt` (old class
names, so placed widgets survive). One renderer: `QuickBudsWidget.build`.

- **Layouts are generated** by `scripts/widget-layouts.py`. Edit the script and run it again, never the
  XML. Script `GEO` = renderer `Geo`. **A new id needs its line in the renderer** (else "Can't load
  widget"). Never a plain `<View>`. Check with `adb logcat` while the widget updates.
- The host reapplies cached views. **Every state that `build()` sets must be set both ways** (null
  included). Send each `setDisplayedChild` **only in the update that changes it** (`widgetChild_<id>`),
  or every widget flashes.
- **Launchers ignore `@font/` in widget XML.** Dot-style text is an ImageView filled by `setText`.
- Tap flow: `WidgetActionReceiver` (optimistic `WidgetStateStore.write`) → `ACTION_WIDGET_COMMAND` with
  the **short** action name → `BudsService.executeWidgetCommand`.
- A palette change calls `refreshAll`.
- **Decided `[USER]`, do not bring back:** resizing or more sizes; the model name on a widget; a swap
  button, a widget settings screen, a hidden LL button or an open-app tap (pages swap by double tap
  only); cycle mode. Dot style: hollow or hidden glyphs, a lit box around bar digits, a split pill, a bar
  as tall as the icon, a third ring on the 2x2.

## Repo hygiene

- **`local/` is local-only, never committed** `[USER]` (`keys/`, `logs/`, `svgs/`).
- **Take the README screenshots again after every big UI change, in the same push** `[USER]`:
  `scripts/readme-screenshots.sh classic|dot-matrix [adb-serial] [widgets]` (buds connected, phone in
  English). **Run dot-matrix last** (it leaves the style set). The script opens screens by visible
  text: a renamed label breaks it.
- Desktop shots (`docs/screenshots/desktop/`): the pages on the invisible screen (window 1100x760,
  `env -u WAYLAND_DISPLAY`), his PC's name masked in a throwaway build. The tray by hand with `spectacle`.

## Current state

See ROADMAP.md. Latest release: v4.5.0 (2026-10-06).
