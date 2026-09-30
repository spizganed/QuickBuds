# CLAUDE.md — working notes for QuickBuds

The state the code does not show: toolchain, conventions, decisions, and mistakes already paid for.
`[USER]` = the developer's decision or report; do not re-litigate those.

## Project

**QuickBuds**: an Android app (Kotlin, single module `:app`, sources in `app/src/main/kotlin/`) that
controls OnePlus / OPPO / realme earbuds over classic Bluetooth **RFCOMM**. No vendor app, root, Shizuku
or ADB needed. Package `com.spizganed.quickbuds`, GitHub `spizganed/QuickBuds` (branch `main`),
GPL-3.0. Test setup: OnePlus Buds 4 on a Nothing Phone (3a), Android 16.

**Goal order:** parity with HeyMelody (only features HeyMelody shows for a real model), then our own
extras (widgets, wear display), then other models.

| Doc | Use |
|---|---|
| [docs/ROADMAP.md](./docs/ROADMAP.md) | Live plan. Finished items move to [ROADMAP-DONE.md](./docs/ROADMAP-DONE.md). No release versions on items. |
| [docs/PROTOCOL.md](./docs/PROTOCOL.md) | **The wire format.** Read before any protocol work. |
| [docs/PACKET-CAPTURE.md](./docs/PACKET-CAPTURE.md) | Capture guide (app log, HCI snoop, HeyMelody). |
| [docs/CONTRIBUTING.md](./docs/CONTRIBUTING.md) | Contributor build quickstart and rules. |
| [docs/TOOLCHAIN.md](./docs/TOOLCHAIN.md) | Public write-up of the phone build setup. Keep in step with Build › Phone. |
| [README.md](./README.md) | User-facing: features, install, credits. New `[OSS]` sources get a Credits line. |

Root holds only `README.md`, `LICENSE` (verbatim GPL-3.0) and this file; other docs live in `docs/`.
Do not create notes folders or session-plan files: durable knowledge goes here or in PROTOCOL.md.

## Build

- Gradle 9.6.0, AGP 9.4.0, Kotlin 2.4.0. AGP 9 compiles Kotlin itself: never add the
  `org.jetbrains.kotlin.android` plugin; the root `kotlin-gradle-plugin` classpath only pins the version.
- `compileSdk 37`, `targetSdk 37`, `minSdk 26`, Java 8. Target 37: an RFCOMM `read()` returns `-1` on a
  dropped link (handled as "Connection lost"); portrait lock is ignored above 600dp.
- Only dependency: `androidx.core:core:1.13.1`.
- **Device testing always uses `./gradlew assembleRelease`** `[USER]`, never the debug APK (signature
  clash; switching needs an uninstall). Install with `adb install -r`. Use `adb logcat` for what in-app
  logs miss.
- **Versions live only in `app/build.gradle.kts` `defaultConfig`** (current: versionCode 21 / 3.9.2).
  Android ignores them on `<application>`. Verify with `aapt2 dump badging <apk>`.
- `local.properties` (`sdk.dir=...`) is git-ignored.
- PC: `export JAVA_HOME=$(ls -d ~/.jdks/jbr-21* | head -1)` first. Output in `app/build/outputs/`.
- Wireless adb: `adb pair <ip:port> <code>` right after reading the code (stale = `protocol fault`),
  then `adb connect` the **other** ip:port from the main Wireless debugging screen.

### Signing and releases

- Release key: `local/keys/quickbuds-release.jks` + `local/keys/keystore.properties` (read by
  `build.gradle.kts`). Git-ignored, on the PC and the phone only. **Never commit them or print the
  password.** He must keep backups: the in-app updater only installs over the same key. Without the
  key, `assembleRelease` builds unsigned.
- Release: `./gradlew assembleRelease bundleRelease`, name `QuickBuds<version>.apk` / `.aab`, then
  `gh release create v<version> <apk> <aab> --target <full sha> --title "QuickBuds <version>"`.
  The updater needs the `.apk` asset. No local copies. Android tags are `v*`, desktop tags `desktop-v*`. **Notes cover every user-visible change since
  the last tag**: read `git log v<previous>..HEAD` first.

### Phone (Termux, often reached over SSH from the PC)

Repo at `~/projects/QuickBuds`; all phone-specific setup lives outside the repo.

- `pkg install openjdk-21 aapt2 android-tools`; `JAVA_HOME` and `ANDROID_HOME=$HOME/android-sdk` in
  `~/.bashrc`. SDK: cmdline-tools **13114758** (newer ones wrap an x86 binary; `termux-fix-shebang`
  its `bin/`), `platforms;android-37.0`, `build-tools;37.0.0` with `aapt2` symlinked to Termux's.
- `~/.gradle/gradle.properties`: `android.aapt2FromMavenOverride=$PREFIX/bin/aapt2`, configuration
  cache, bigger heaps. **Never put the aapt2 override in the repo** (breaks the PC build).
- `~/.gradle/init.d/quickbuds-phone.gradle.kts` moves output to `~/qb-build/` and disables
  `lintVital*`. **Phone APK: `~/qb-build/_app/outputs/apk/release/app-release.apk`.** The daemon's
  "Unable to set daemon's environment variables" warning is harmless.
- `core.filemode` is false; git keeps `gradlew` at 755. SDK shell scripts need `java -jar`.
- **adb tests** `[USER]`: never leave auto-rotate on (`settings put system accelerometer_rotation 0`
  after every test); launch with `am start -n`, never `monkey`. Bring Termux to the front when done,
  except over SSH. The user-level Stop hook does this unless `$SSH_CONNECTION` is set.

## Working with the developer

- Commits authored as `spizganed <spizganed@gmail.com>` (set `git config user.name/email` if the
  session differs); keep the `Co-Authored-By` trailer.
- **Ask before every push, every time.** A past "yes" does not carry over.
- He does device testing and design decisions; the agent does protocol, parsers and code, including
  commits. He is the only source of on-device results: ask for the exact log line you need.
- **Revert first, reason after** when he reports a regression and asks for a revert.
- Docs change in the same commit as the code; protocol changes always with their PROTOCOL.md entry.
- Releases happen when he decides, not on a schedule.

## Protocol rules that were paid for

Details and evidence in PROTOCOL.md (tags `[VENDOR]` / `[OSS]` / `[CAPTURE]` / `[GUESS]`).

- **HeyMelody is the `[VENDOR]` source for bytes**, studied for interoperability only. The repo carries
  only commands, payloads and per-model facts; **never vendor class, method or file names, code, or
  how-to notes** (those stay in private memory).
- **Never guess a payload.** A guessed write fails silently. **A wrong command number fails silently
  too** (`0x0402` vs the real gesture write `0x0401`), so writes are read back and diffed.
- SET and NOTIFY ANC encodings are different tables. Adaptive's SET bit is **11** (`01 01 00 08`),
  not 8. Take bits from the model's `protocolIndex`, never a list position.
- `0x01F0` / `0x01F2` are not commands (command byte + Seq). Battery `0x0106`, wearing `0x0109`.
- **`0x0205` is count-first:** the app sends `03 01 02 03` (+ `04` / `08` / `0B` when listed). Never
  shorten it; without `03`, bud-side ANC changes are invisible. `01 01 02 02` is a misread.
- TotalLen is standard LEB128 (`buildPacket()` writes, `OppoPacketFramer` normalises); payload starts at
  index 9 (`payloadOf()`).
- **Gestures:** `fn` values are measured; do not re-derive them. `writeGestureBinding(side, ...)` writes
  every slot the table has for that action except `BUTTON_ON_CALL` (`0x06`); **never hardcode a button
  group** (the table's shape changes per bud). Menus per model come from `GestureModel`
  (`models.json`); a new option needs its `supportBit` in `GestureAction` and `ORDER`.
  `KeyFunctionParser.HEADER_SIZE = 2`. The hold's cycle is `0x0404 02 …`, not the `fn` byte. The diff
  baseline is in prefs `QuickBudsKeyFnDiff`; an empty reply never overwrites it. The table prints in
  `RX:`, `KEYFN:` and `LogDecoder`, all ending `RAW=[...]`.
- **Adding an ANC mode touches all of:** `AncModes`, `OpoProtocol.anc(bit)`, `BudsConnectionManager`
  (`sendAnc`, `lastAncLevelSent`), `BudsService` routing, `WidgetStateStore`, `WidgetSettings.MODES`,
  `WidgetActionReceiver`, `MainActivity` segments.
- **Settled, do not raise again:** `0x0500` time request is not answered; case lid has no lasting state
  and case charging is not shown; undecoded families (PROTOCOL.md §12) are not guessed from a few
  samples.

## Connection

`bluetooth/BudsConnectionManager.kt`, `BudsService`, `KeepAliveReceiver`.

- **Which buds:** `BudsDevice.find()`: saved `budsAddress`, else the first bonded device with the
  `079A` / `1107` UUID or a `models.json` name. **Never hardcode an address.**
- UUID `079A` first (the one Buds 4 answers), `1107` second.
- **Auto-connect follows audio:** RFCOMM connects when A2DP or HFP reports connected (logcat
  `KeepAlive: audio profile state=2`); ACL sends a delayed (8 s) fallback. Manager retries 3x5 s.
- After `Connection lost`, `reconnectAfterLoss()` retries 5x with growing delays; a deliberate
  disconnect cancels it. A lid close (all-zero wear push) logs `Case closed` and does not retry.
- **Only a user connect asks Android for phone audio** (`EXTRA_WITH_AUDIO`); automatic connects leave
  A2DP to the system. Exception: after a write that restarts the buds (power saving, codec), the
  reconnect asks once (`audioAfterRestart`).
- The buds serve one control app at a time. For a HeyMelody capture, stop our service from Quick
  Settings › Active apps (`am force-stop` gets undone by our reconnect).
- **Background service** pref (`BudsService.PREF_BACKGROUND`, default on). Off: receivers do not start
  the service, `QuickBudsApp` stops it when no activity is visible, `MainActivity` unbinds in `onStop`.
  `BudsService.onDestroy` closes the link.
- Nothing is polled after connect; everything is pushed.

## UI

Source of truth for the UI revision: `design/SPEC.md` (+ `design/*.png`), except SPEC §1's view-tree
PaletteApplier: theming here is attribute-based. UI work never touches protocol or wear logic.

### Theming

- `ThemeRes.select(this)` runs **before** `super.onCreate` in every activity. **Never put
  `android:theme` on `<application>`** (crashes on launch).
- Palette: six attributes in `values/themes.xml` (`appColorBg/Card/Accent/TextPrimary/TextSecondary/
  Outline`), one style per built-in preset. `Palette.kt` = `Palette` + `PaletteStore` (active id, up to
  3 custom presets as JSON). A custom preset (or a built-in with an accent override,
  `paletteAccent_<id>`) uses a built-in style plus `ThemeRes.PaletteFactory`, which swaps
  `?attr/appColor*` at inflation. **No XML shapes with `?attr` colours**: build them in code
  (`ThemeRes.card/chip/iconButton/sheet`).
- Match system (`paletteAuto`): White in light mode, `paletteAutoDark` in dark. Never add `uiMode` to
  `configChanges`.
- Check colour changes on White and on a light custom preset. Contrast warnings (`Palette.contrast`)
  never block saving.
- Prefs file name only via `ThemeRes.PREFS_NAME` (`QuickBudsPrefs`).
- One font family in Classic: `sans-serif` (set in `Theme.App.Base`, and on widget TextViews). Code uses
  `ThemeRes.regular / medium / bold / headline`, never `DEFAULT_BOLD` or `sans-serif-medium` directly.
- **Portrait only**: every `<activity>` needs `android:screenOrientation="portrait"` by hand.
- Red accent is the default (`?attr/appColorAccent`) across icons, EQ curve, sliders, rings, noise
  highlight.

### Styles: Classic / Dot matrix (one switch for the app and the widgets)

`ThemeRes.nothing` (pref `styleNothing`, default off = Classic). **User-visible name: "Dot matrix"**
`[USER]`: never "Nothing" (trademark) or "Pixel". Code keeps the `nothing` identifiers and `_n` layouts.
A change recreates open screens (part of the activity signature).

- **Font: bundled Doto** (`res/font/doto.ttf`, SIL OFL, static instance wght 900 / ROND 100, license in
  `assets/Doto-OFL.txt`), on every phone `[USER]`. `ThemeRes.dotFont`, `ThemeOverlay.App.Nothing` for
  theme-set text. Monospaced and wide: long labels wrap.
- Cards are drawn in dots too `[USER]` 2026-09-30 (home battery card and feature list, `ThemeRes.group()`; settings screens,
  `SettingRowFactory.card`): a grey dot outline. Earlier "no cards" (2026-09-28) is reversed. Dialogs, sheets, buttons, chips, header buttons and text fields are
  `DotArt.Box` (outline one cell of dots, fill dots inside; `solid` = smooth fill under a dot outline, used by
  sheets and dialogs). `ThemeRes.card / iconButton / chip / sheet / pill` pick it, so never build a
  `GradientDrawable` for a button by hand.
- Home: `BudsStatusView` draws the widget's `dotRing`, numbers without `%`, no wear label (the glyph's
  shade says it). `AncSegmentedView` draws `QuickBudsWidget.modeIcon` at a whole-pixel pitch
  (~1.15dp), 72dp tall.
- `DotArt`: live views as dots, pitch 2.2dp rounded to whole px, drawn without antialiasing so every dot of
  a shape is one shade: `LevelSliderView`, `EqCurveView`,
  `ColorSliderView`, switch track/thumb (`DotArt.Part`, thumb as tall as the track).
  Swatches and small discs are `DotArt.disc` (a fixed cell pattern, like the knob). Dot outlines are the
  shape filled in the outline colour with the fill a cell inside, never a thin stroke (it skips cells).
  **Every knob is `DotArt.knob`** `[USER]`: one fixed 7x7 dot ring snapped to the grid (scaled circles came out a
  different shape at every position). Never draw a dot-style knob with `drawCircle`.
- Icons: `ThemeRes.tint` returns a `DotArt.Icon` (1.2dp, solid dots). The small action icons (tap x1/x2/x3,
  hold, close, check, pencil, bin, cog) are `DotArt.Pattern`s drawn from a rule, not sampled from the vector,
  so every dot is the same and shapes are symmetric `[USER]`; their vectors (simple, filled or bold
  strokes) are the Classic look. Row dividers are one row of dots.

### Shared components and screens

- `SettingRowFactory`: `screen`, `title`, `sectionLabel`, `card`, `build`, `buildSwitch`,
  `buildChevron`, `buildDivider`, `iconButton`. New screens use these. SPEC §5 icons are in
  `res/drawable`.
- Confirm dialogs go through `ConfirmDialog.show()`.
- **Selection is an outline, never a checkmark** `[USER]`: `ThemeRes.selectedBorder()` as the row's or
  tile's foreground (dots in the dot style). `ic_check` stays only on Done buttons.
- **Motion helpers** `[USER]` (reuse, do not write new animators): `Motion.slide(view, open)` grows or folds a view
  (height, alpha and the card gap; a shut view ends GONE); `SelectionSlider(host, key?)` is the selected-row outline as
  one overlay that slides between rows (and across a recreate with a `key`; call `moveTo` after each render);
  `ThemeRes.recreateFaded(activity)` is `recreate()` with a cross-fade (`QuickBudsApp` fades the new screen in);
  `ThemeRes.sinkOnPress(view)` is the press-down effect for buttons (pair it with `Haptics.commit`).
- **Lists you pick from** are split: `SettingRowFactory.splitList` + `addSplit` (one 16dp card per row, 8dp gap).
  Screens with such lists keep their row views and update them in place (see `EqActivity.Choice`); a preset just
  sent to create shows as a dimmed placeholder row that the real one adopts.
- **Compact sizing** `[USER]`: ~10-15% under SPEC so home fits without scrolling (rows 62dp, rings 90dp,
  segments 56dp, padding 16dp, gaps 12dp); touch targets stay ≥ 44dp.
- No Material Components `[USER]`.
- **Haptics:** `Haptics.commit(view)` once per kind of control, on user actions only, never on
  programmatic changes. Widget taps: `Haptics.tick(context)` (usage HARDWARE_FEEDBACK; TOUCH is dropped
  in the background).
- **Settings** (`SettingsActivity`): Appearance, General (language, haptics, background service, Dev
  tools button, default on), App. Find my earbuds and Wear detection are sheets
  (`FindBudsSheet`, `WearSheet`) opened from the Earbud settings hub, like the fit test.
- **Update check** (`UpdateChecker`, `UpdateActivity`): newest GitHub release tagged `v*` with an `.apk` asset
  (desktop releases and pre-releases are skipped); on start (switch on the
  update screen) at most every 12 h, silent on failure, one dialog per new tag.
- **About:** Ko-fi button hidden while `AboutActivity.KOFI_URL` is null.
- **Home layout** (`HomeLayoutActivity`): drag to reorder, eye to hide; prefs `homeRowOrder` /
  `homeRowHidden`. **A new home row needs its key in `buildFeatureRows()` AND
  `HomeLayoutActivity.ROWS`.**
- **Presets:** `ThemeActivity`, `PresetEditActivity`, `PalettePreviewView`, `ColorPickerView` (hue,
  saturation, brightness sliders + hex + swatches `@color/swatch_*`). Colour edits save on commit
  (slider lift, hex done, swatch tap); rows are never rebuilt mid-drag.
- **Hearing profile** (vendor "Golden Sound"; **never "Golden Sound" in a user-visible string**):
  home row `golden` with a switch; tap opens `GoldenSoundActivity` (records on the phone in
  `goldenRecords`, max 10, dated, no rename; the buds' own profile added on open; `HearingRadarView`
  one ear at a time). Test: `GoldenTestSheet` (ear scan where `models.json` has `"earScan":1`).
  Records in `protocol/GoldenSound.kt`. Fit test: `FitTestSheet`.
- **Dev Tools:** packet log (Human / Detailed / Raw; Human puts every packet the decoder does not name on
  an amber line with its payload, Detailed adds the payload line to all; the framer's discarded bytes are logged
  as `DISCARDED RX`; long press copies), Clear, Export (`Download/QuickBuds/`),
  Reconnect, Disconnect, Bridge (see Desktop), Crash test. Labels stay English-only. The crash report shows as a sheet (Copy, Share; tap outside to dismiss). The crash handler is installed in
  `QuickBudsApp.attachBaseContext` (`Download/QuickBuds/`).

### Main screen

Header (model button `btnModel`, status chip = Connect/Disconnect, spacer, dev tools, cog) over
`mainScroll` › `tiles`: `batteryCard`, `ancRow`, `featureList`, each an include whose root id is stable.

- `batteryCard`: `BudsStatusView`, three 90dp rings, glyphs at their SVG ratio in 42x56 / 58x42
  boxes, percentage always `text` colour (never red). Device name under the rings
  (`ModelCatalog.current()`).
- `ancRow`: `AncSegmentedView`, Off / ANC / Adaptive / Transparency. The ANC segment uses the widget's
  `ic_mode_anc_medium` (not the headset). Tapping ANC slides in the level picker (the buds' levels,
  Smart included; the lit level turns ANC off; auto-closes after 2 s idle). In ANC it shows "ANC L/M/H/S"
  and the level's icon.
- `featureList` (`buildFeatureRows()`): low latency, Hi-Res (codec picker on `highAudio` models), 3D
  audio (Off / Fixed / Head tracking sheet on head-tracking models), Hearing profile, EQ, Dual
  connection (home only), Earbud settings (hub `EarbudSettingsActivity`; **new firmware settings go in
  the hub**).
- Disconnected: nothing collapses; tiles go to alpha 0.35, disabled, switches set neutral **quietly**
  (`syncingFeatures`) so no write goes out.
- **No log on the main screen** (the `onStatus` listener callback was removed). **Never put user-visible output on a
  packet-listener path** (fires per packet, storms the UI).

### Icons

- Row and button bud icon: **`ic_earbud`** (filled, 24dp) `[USER]`. `ic_bud_left` / `ic_bud_right`
  (true ratio 176x272; case 496x400) only where large: status rings, widgets, fit test.
- Launcher: his two bud glyphs facing each other with bolt holes; the themed layer and `ic_stat_buds`
  (notification) are one bud. Only the adaptive icon exists (minSdk 26).
- Case icon keeps its LED dot and full-width lid cut.
- Vector `width`/`height` must be the layout size, never the viewBox (a 1024dp icon once blew
  RemoteViews' bitmap limit). **Never size icons with `wrap_content` + `adjustViewBounds`** (ratio
  drift clips them); set the box. Icons in a set share units-per-dp and layout height.

### Localisation

26 locales (Asian, EU and more), machine-drafted and marked so; users report wording on GitHub. **A new
user-visible string needs all 26** (lint does not catch a missing one). Constant strings are
`translatable="false"`. `generateLocaleConfig` feeds Android 13+'s per-app language; the in-app list is
`ThemeRes.LANGUAGES` (a new locale needs its line). Below 13, pref `appLanguage` is applied in
`ThemeRes.select`. `LanguageActivity` has `configChanges="locale|layoutDirection"`.

### Traps

- A `when` on UI string keys with no `else` fails silently (`"Trans"` vs `"Transparency"` hid for
  weeks). Check every state write has its refresh call.
- Downloads go through MediaStore (`Download/QuickBuds/` holds both the crash reports and the log exports), never a plain `File`.

## Desktop (`desktop/`, in progress)

Rust, one crate; Slint for the UI (GPLv3 licence), `tray-icon` for the tray. Plan in ROADMAP.md.

- `cargo test` / `cargo build --release` in `desktop/`. `protocol.rs` mirrors `OpoProtocol` framing;
  a protocol change lands in both apps, and in PROTOCOL.md, in one commit.
- **Windows Bluetooth is Winsock** (`AF_BTH` + `BTHPROTO_RFCOMM`, `windows-sys`): connect by
  service UUID with port 0 and Windows resolves the channel over SDP. Verified 2026-09-30 on the Buds 4:
  079A connects, the init sequence and `0x8106` answer as on the phone. Never WinRT.
- Buds found among Windows' paired devices: only ones with an audio link, a `models.json` name first
  (a user Connect tries any connected one); rescan every 5 s. Never hardcode an address.
- **Shared, never copied:** `build.rs` turns `app/src/main/res/drawable/*.xml` into SVG and the listed
  `strings.xml` keys (all locales) into tables; `models.json` is `include_str!`'d. A new desktop icon or
  string = add its name to `build.rs`. Desktop-only strings are English for now (`Tr` in `ui/app.slint`).
- Files: `bt.rs` (sockets), `protocol.rs` (framing, parsers, ANC modes, capabilities, tests from captures),
  `session.rs` (link thread, init sequence, commands, packet log `LOG`), `eq.rs`, `devtools.rs` (Dev tools page),
  `main.rs` (UI + tray glue), `ui/app.slint`.
- **Software renderer** (set in `main`): ~25 MB RAM against ~130 MB with the GPU one. `SLINT_BACKEND`
  overrides it.
- The quick panel (tray right-click) hides when it loses focus; closing the main window hides it, Quit exits.
- **RFCOMM bridge (dev):** Dev tools › Bridge makes the Android app (`RfcommBridge`) pass raw RFCOMM bytes to
  one TCP client on `127.0.0.1:7979` (loopback only, off by default, not persisted). `QB_BRIDGE=127.0.0.1:7979`
  makes `bt.rs` use it instead of Bluetooth; it connects on a user Connect only. Both apps see every reply.
  It is for the Linux build in the phone's Ubuntu proot, which has no Bluetooth; it does not test BlueZ.
- **Distribution `[USER]`:** `.exe` installer and portable `.zip`, just the app: no drivers, no
  services, no helper or background processes. Release tags `desktop-v<version>`, built by CI.

## Widgets

`widget/AncWidgetProvider.kt`: providers **2x2** `BatteryWidgetProvider` (fixed size `[USER]` 2026-09-30: resizing gave broken 3x2 / 2x3 shapes, so the scaled 3x3
layout was removed; do not bring resizing back) and **4x2**
`AncWidgetProvider` (fixed; old class names kept so placed widgets survive), one renderer `QuickBudsWidget.build`. No more sizes for now, no model name on any widget
`[USER]`.

- **Layouts are generated** by `scripts/widget-layouts.py` (`widget_pages`, `_m`, `widget_grid`,
  `widget_disconnected`, each with a `_n` dot copy). Edit the script and rerun, never the XML.
  **A new id needs its line in the renderer**, or RemoteViews fails ("Can't load widget"). Never a
  plain `<View>`. Check changes with `adb logcat` while the widget updates.
- Script `GEO` = renderer `Geo` (keep equal).
- **Two pages per size, battery and controls** `[USER]`, stored per widget (`widgetPage_<id>`), swapped
  by a double tap (200 ms wait). No swap button, no widget settings screen `[USER]`: the Low latency
  button is always there and a tap never opens the app (a swap button, a hidden LL button and an
  open-app tap each broke the grid or the double tap). No automatic page change.
- **Controls page:** ANC (opens the level picker `w_page2`, stays open until a pick; `widgetListAt_<id>`),
  T, A (select, or Off when lit), LL (toggle). A missing feature leaves an empty cell. No cycle mode
  `[USER]`: do not bring it back.
- **Battery page:** two bud rings over the case row (icon + bar, 70% of its height, level inside).
  4x2: three rings. Disconnected: only the Connect chip (FORCE_CONNECT with audio, or opens the app when
  the background service is off).
- **Flippers:** outer `w_pages` (content / picker), inner `w_slide0` / `w_slide1`. The host reapplies
  cached views, so **every state `build()` sets must be set both ways** (visibility, intents, null
  included), and each `setDisplayedChild` goes out **only in the update that changes it**
  (`widgetChild_<id>`), or every widget flashes.
- Animations: slides only, no fades, `@integer/widget_anim_ms` 280 ms, `animateFirstView="true"`,
  press animator on every clickable. Battery enters/leaves on the left, controls on the right, picker
  from the left.
- Colours follow the active palette (tinted white shapes, bitmaps drawn per update); a palette change
  calls `refreshAll`. `WidgetSettings` holds the per-widget page state and the mode list.
- Tap flow: PendingIntent → `WidgetActionReceiver` (optimistic `WidgetStateStore.write`) → broadcast
  `ACTION_WIDGET_COMMAND` with the **short** action name → `BudsService.executeWidgetCommand` →
  `manager.sendAncXxx()` (fresh thread per send).

### Dot matrix widgets

- **Launchers ignore `@font/` in widget XML.** Every `_n` text is an ImageView the renderer fills with
  Doto text (`setText`), height `sp x 1.2` from the script. Set widget text only through `setText`.
- Graphics as dot matrices (`matrix()`, sampled at 8x): rings `RING_CELLS` = 42, mode icons on
  `MODE_GRID` = 31 (`modeIcon()`), fixed dot counts on every size (the 2x2 is the baseline).
  Unlit dots use `dim()`.
- Wear shade (`nothingTint`): in ear `text`, out `textSecondary` 65%, in case 17%. Glyph fill 1.18
  (buds) / 1.22 (case).
- Case icon dotted at the ring pitch; `clearLed` always clears the LED dot and lid-cut row. The bar's
  digits are Doto's own 5x7 digits (`GLYPHS`) in `text`, like the buds' percentages `[USER]`.
- Numbers without `%` (Classic keeps `%`). Box radius 19dp (`widget_bg_n`), inner 16dp.
- Tried and rejected `[USER]`: hollow or hidden glyphs, a lit box around bar digits, a split pill, the
  bar as tall as the icon, a third ring on the 2x2.

## Repo hygiene

- **`local/` is local-only, never committed** `[USER]`: `keys/`, `logs/` (captures PROTOCOL.md
  cites), `svgs/`. Doc references to `local/` point at the developer's machines.
- Runtime `*.log` are ignored; handed-over captures (`*.log.txt`) are tracked.
- **README screenshots are retaken after every big UI change**, in the same push `[USER]`:
  `scripts/readme-screenshots.sh classic|dot-matrix [adb-serial]` (buds connected, phone in English,
  Pillow). Classic to `docs/screenshots/`, Dot matrix to `docs/screenshots/dot-matrix/`. It leaves the
  style set, so **run his style (dot-matrix) last**. It opens screens by visible text, so renaming a
  label breaks it. Widgets on the first and last home page are cropped to `widget-<size>.png`; a third
  argument `widgets` (`... dot-matrix <serial> widgets`) sets the style and shoots only the widgets. The README shows
  only: Classic main, Earbud settings, Equalizer, Hearing profile, two widgets, Dot matrix main and
  2x2 widget.

## TEMPORARY handoff (2026-09-30, delete this section once done)

State: v3.9.2 released. The desktop app (`desktop/`, Rust + Slint) runs on Windows with Overview, Equalizer and
the tray; the Android updater fix (skips `desktop-v*`) is committed but not in a release yet, and must ship
before the first desktop release.

- **Next:** ROADMAP.md step 2, "Next, in order": ANC level slide and Dev tools are built (waiting for his
  check); phone-UI reuse started (Overview, Equalizer restyled; check on the buds), continue with it. EQ writes and ANC / low latency from the desktop
  are untested on the buds: ask him for the result.
- **Linux VM** (VirtualBox 7.2.20 at `C:\Program Files\Oracle\VirtualBox`, not on PATH): VM
  `QuickBuds-Linux` (4 GB, 4 CPUs, 40 GB disk, USB 3 filter for the ASUS USB-BT400 dongle, SSH forward
  127.0.0.1:2222). He turned Memory integrity off 2026-09-30; VirtualBox now runs on AMD-V (VBox.log
  "HMR3Init: AMD-V w/ nested paging"). The unattended install (user `qb`, password in private memory,
  hostname `qb-linux`, post-install adds openssh-server, bluez, build-essential, libdbus-1-dev) was rerun
  and was still running at the end of the session: check `VBoxManage showvminfo QuickBuds-Linux` and
  `ssh -p 2222 qb@127.0.0.1`; if it failed, rerun `VBoxManage unattended install` with the same values.
  Next: pair the buds in the VM (then re-pair in Windows), BlueZ spike via the `bluer` profile API.
- Screenshots of the desktop app without moving his cursor: PrintWindow + DPI-aware PowerShell, clicks by
  window message (WM_LBUTTONDOWN/UP); the scripts are not in the repo.
- **Waiting:** issue #1 (pratstick's other-model logs); read them before changing anything.
- The phone has `~/tapt.sh <text>` (prints tap coordinates of a visible label from uiautomator); outside the repo.
