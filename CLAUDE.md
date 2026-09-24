# CLAUDE.md — working notes for this project

**Read this before touching anything.** It carries the state that is not derivable from the code:
the toolchain, the conventions, the mistakes already paid for, and what is currently open.

## What this project is

**QuickBuds** — an Android app that controls OnePlus / OPPO / realme earbuds directly over classic
Bluetooth **RFCOMM**, with no HeyMelody, no vendor app, no root, no Shizuku, no ADB. Kotlin.

- Package `com.spizganed.quickbuds` (final). App name **QuickBuds**. GitHub: `spizganed/QuickBuds`,
  branch `main`.
- License **GPL-3.0**. Test device: **OnePlus Buds 4** on a **Nothing Phone (3a), Android 15**.
- Single module, `:app`. All sources are Kotlin under `app/src/main/kotlin/`; no Java.

**The north star is parity with HeyMelody**, then this project's own improvements on top (the widget,
the wear-state display). Only after that, other earbud models.

## Read order

The PC move is done — build confirmed working, wrapper generated, device reachable — as of
2026-09-20. `START-HERE.md` and `DeepSeek_CodeAssist_memory.md` served their purpose and are gone;
this file is now the sole entry point for a new session.

| File | What it is |
|---|---|
| [ROADMAP.md](./ROADMAP.md) | The live plan: next, open, questions. |
| [ROADMAP-DONE.md](./ROADMAP-DONE.md) | What is finished. Move items there when done. |
| [PROTOCOL.md](./PROTOCOL.md) | **The wire format, end to end.** Read before any protocol work. |
| [README.md](./README.md) | The accurate feature summary. |
| [CREDITS.md](./CREDITS.md) | Whose reverse-engineering this stands on. Add a row when you add a constant. |
| [PACKET-CAPTURE.md](./PACKET-CAPTURE.md) | How to capture a packet log, kept as a backup procedure. |
| CLAUDE.md | This file — the durable reference, loaded automatically. |

## Build and run

Plain desktop Gradle. There are no CodeAssist prerequisites or dependencies any more.

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

### Environment you need

Everything below is a one-time PC setup. Nothing here was recorded anywhere before the move,
because the phone side had it all built in.

| Need | Version / note |
|---|---|
| JDK | **17+**, built with JBR 21. (Gradle 8.13, used until 2026-09-25, rejected JDK 24+ with just the bare version number, e.g. `25.0.3`, as the error.) See the JDK note below if running from Android Studio. |
| Android SDK | `platforms;android-37.0` (for `compileSdk 37`) and `platform-tools` (for adb). `build-tools` matching AGP. |
| Gradle | Only to generate the wrapper (below). After that `./gradlew` is self-sufficient. |
| adb | For deploying to the phone and reading `logcat`. |
| kotlin-stdlib | **No** manual install — it comes with the Kotlin Gradle plugin. The only app dependency is `androidx.core:core:1.13.1`. |

### First run on the PC

The build files are committed, but **the wrapper scripts are not yet generated** — this project
never had a `gradlew` (CodeAssist drove the build itself), so the first step on the PC is to create
them once:

```bash
gradle wrapper --gradle-version 8.13     # writes gradlew, gradlew.bat, gradle-wrapper.jar
./gradlew assembleDebug                  # first real build
```

Set `sdk.dir` in a local `local.properties` (or export `ANDROID_HOME`). `local.properties` is
git-ignored — never commit it.

The wrapper JAR and scripts **should be committed** afterwards, so nobody needs a local Gradle
install or a matching Gradle version again. **Commit them as a follow-up, and note in the commit
that they are generated.** They are the one piece of the build that is not yet in the repo.

**The first build also downloads a lot** — Gradle, AGP, and the Kotlin plugin. That is expected,
not a stall.

**JDK gotcha, found 2026-09-20 setting this up in Android Studio's own agent terminal:** Android
Studio's *own* bundled JBR can be too new for Gradle 8.13 — this machine's was JDK 25, and running
`gradle wrapper` with it failed with just `25.0.3` as the error, no other detail. Android Studio also
keeps a **second, older JBR** at `~/.jdks/jbr-<version>` (this machine had `jbr-21.0.11`) — that's the
one Studio itself uses as the *project* JDK for Gradle sync, separate from the IDE's own runtime.
Point `JAVA_HOME` at that one, not the IDE's bundled JBR, if the IDE's own JBR turns out to be JDK 24+.

**Android Studio's own sync may have already done most of this setup for you** — check before
reinstalling anything. On the machine this was verified on, Studio's sync had already: written a
correct `local.properties`, installed the matching SDK platform/build-tools, and even pre-downloaded
the exact Gradle 8.13 distribution into `~/.gradle/wrapper/dists/`, which can be pointed at directly
(`<dist>/gradle-8.13/bin/gradle.bat wrapper --gradle-version 8.13`) to generate the wrapper without
any network access at all.

`app/module.toml` and `.platform/` — CodeAssist's project model and cache/settings from the mobile
era — are git-ignored and were never committed, so **a fresh clone on the PC will never have them at
all.** They only ever existed on the original phone's working copy, which was intentionally left
alone during the move so the phone build kept working as a fallback. If you're working from a fresh
clone, there is nothing here to clean up — this note is historical, kept only so nobody goes looking
for files that were correctly never carried across git.

### Signing — release key since v2.0.0

**Releases are signed** with the key in `local/keys/quickbuds-release.jks` (PKCS12, alias
`quickbuds`, RSA 4096, valid 100 years), created 2026-09-23 at his request. Its passwords are in
`local/keys/keystore.properties`, which `app/build.gradle.kts` reads. Both are git-ignored and
PC-only. **He must keep a backup of both files**: the in-app updater can only install over an app
signed with the same key, and losing it means every user has to uninstall first. Never commit
them and never print the password.

Without that file (a fresh clone), `assembleRelease` falls back to an unsigned APK, as before.
Device testing uses `assembleRelease` too. **Debug and release builds cannot be installed over
each other**, and switching needs an uninstall.
v1.1.0 was signed by CodeAssist with a different key, so moving from 1.1.0 to 2.0.0 also needs one.

### Versioning — `build.gradle.kts` defaultConfig is the single source

`versionCode` / `versionName` are set **only** in `app/build.gradle.kts` `defaultConfig`.
**Current: versionCode 3 / versionName 2.0.0.**

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
this PC, so the release page itself is made in the browser.

## History: mobile → PC

Everything up to and including **v1.1.0** (commit `2875262`) was written on a **mobile-only
workflow** — Nothing Phone (3a), CodeAssist IDE, Termux, GitHub mobile. Work has since moved to
**Claude Code on a PC**, with Gradle and adb. Attribution: DeepSeek chat (~15% — first codebase,
first RE steps), the CodeAssist agent via OpenRouter on DeepSeek v4.1-fast (~80% of the current
code), Kimi / Gemini / Grok for small tasks, then Claude Code from here on.

**Some habits below are CodeAssist artefacts. They are marked, and they do not apply on PC.**

## Protocol work — the rules that were paid for

Use [PROTOCOL.md](./PROTOCOL.md) as the reference; it tags every claim `[VENDOR]` / `[OSS]` /
`[CAPTURE]` / `[GUESS]`. The short version of what bites:

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

`OpoProtocol` builders · `AncEventParser` (buds→app names) · `LogDecoder` (SET-table log names) ·
`BudsConnectionManager` (`sendAnc*`, `lastAncLevelSent`) · `BudsService` routing ·
`WidgetStateStore` (state + `*IsActive()`) · `AncWidgetProvider` segments ·
`WidgetActionReceiver` · `MainActivity` circles · `AncTileService`.

## UI conventions and traps

- **Theming is attribute-based.** Colours are read from theme attributes during inflation
  (`ThemeRes`), not applied by walking the view tree afterwards. `ThemeRes.select(this)` must run
  **before** `super.onCreate` in every activity.
- **Never add `android:theme` to `<application>` in the manifest.** It makes the framework resolve a
  theme during Activity attach, which breaks `applyOverrideConfiguration()` — the app then crashes
  on launch. Per-activity theming is the only supported path here.
- **Prefs file name lives in one place**: `ThemeRes.PREFS_NAME` (`"QuickBudsPrefs"`). `QuickBudsApp`
  and `DevToolsActivity` used to hardcode the literal — keep all three in sync or prefs split in two.
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
- **The download folders are `QuickBudsCrash/`, `QuickBudsLogs/`, `QuickBudsShot/`**, written through
  MediaStore. Do not write to public Downloads with a plain `File` — it is scoped-storage-blocked on
  API 29+, and the failure is silent if the result is not checked.

## Working with the developer

- **He handles device testing and design decisions.** The agent handles reverse-engineering, parsers,
  protocol work and code — and, since the PC move, git operations including commit and push. The
  phone-era rule reserving all git operations for him was a CodeAssist limitation, not a standing
  preference — it does **not** carry forward. **The one thing that does carry forward: ask before
  every push, every time**, whether he says "commit" or the agent proposes it — a standing "yes" to
  push once is not a standing "yes" forever.
- **Revert first, reason after.** When he reports a regression and asks for a revert, do the revert,
  then investigate. Arguing has cost a whole session before.
- **He is the only source of on-device results.** Ask for the specific log line or measurement you
  need; one sentence from him has replaced an entire diagnostic loop.
- **Docs are updated in the same commit as the change**, and protocol changes always land with
  their PROTOCOL.md entry.
- **Do not mark roadmap items with release versions.** Releases happen when he feels the app is
  ready, not on a schedule.

## CodeAssist-era notes — do NOT carry these forward

These were real limits of the old environment only. On PC, with Gradle and a normal source tree,
they are historical:

- `search_text` did not index Markdown, so docs had to be read with the file reader.
- `project_diagnostics` reported **false errors** on nested classes named `Result`/`State`
  (`BatteryParser`, `EarStatusParser`, `WearingStatusParser`, `WidgetStateStore.write`). The build
  was always fine; do not chase these.
- There was no `assemble` task: the only build tasks were `bundle:app:release` and
  `androidRun:app:release`. On PC, use Gradle's own tasks.
- `get_diagnostics` could report "No diagnostics" for a file that did not compile (a missing import
  across packages). After a cross-package change, always run the compiler.
- `noteUnattributed` blanket-excluded cmd `0x0204`, so an undecoded `0x0204` subType printed nothing.
  That hid the ANC push frame for three captures and produced a confident, wrong claim that ANC has
  no push event.

## Dev tools inside the app

`devtool/LayoutReport.kt` writes the laid-out view tree as text — bounds, weights, margins, padding,
gravity, text sizes, and drawable intrinsic vs actual size, plus a `SIBLING GAPS` section. It exists
because a screenshot does not show view ids or exact spacing. **If you change `collectGaps`, keep it
to direct children of each container, measured against that container's own origin and on its own
stacking axis** — two earlier versions broke that and produced confident nonsense. `!! OVERLAP` on
`status_bar_* -> status_text_*` is **by design** (the battery percentage is drawn over the bar).

`devtool/ScreenshotToText.kt` converts a picked screenshot into ascii / grid / colour / rows text.

**Both are slated to be hidden from the UI, with the logic kept** — they may be useful again. Do not
delete them, and do not treat "the agent cannot read images" as a constraint any more.

## Current open items

- **Slide up vs slide down** — both directions are written with the same action because which is
  which is not established.
- **On-call gestures** — to be added for parity (this reverses an earlier "never" decision).
- **Light theme** — either removed or left untouched until the final UI lands. It is the source of
  invisible-on-light bugs. Do not polish it.
- **Case lid state** — keep, remove or change: undecided. `ic_case.xml` stays until then.
- **Auto play/pause on wear** — add the firmware switch, plus our own layer: pause **only when both
  buds are out**, never auto-play.
- **Alert-sound volume slider** — the last remaining "easy parity" row; the rest are in.
- **Localisation** — text is in `strings.xml` but only English exists, and hardcoded strings remain in
  `MainActivity` dialogs, the Dev Tools labels and legend, and `BottomSheetDialog` callers.
- **Undecoded families** — `0x0500`/`0x0501` (they have empty payloads, so they cannot be gesture
  bindings), broadcast codes `0x04`/`0x08`/`0x0B`, the recurring
  `F1` family (`AA 0D 00 00 04 02 FF 06 00 F1 01 01 XX YY 02`), and `02 01 08 0C 02` /
  `02 01 07 0B 02` (these carry non-multiples of ten — possibly a fine-grained battery/case field).
  Also `0x0510`, a Spatial Audio notify. **Do not guess any of these from a couple of samples.**
  See PROTOCOL.md §12.

### Connection robustness — known rough edges

From `bluetooth/BudsConnectionManager.kt`.

- The **primary UUID `00001107-...` never connects** — it burns a ~5 s timeout on every attempt, and
  the `0000079A-...` fallback is the one that works. **Trying the working UUID first would save 5 s
  per connect.** Not done. HeyMelody itself connects straight to `079A` (bugreport 2026-09-24).
- **The buds serve one control app at a time.** With QuickBuds connected, HeyMelody hangs on
  "Connecting…", and QuickBuds' reconnect grabs the link back whenever HeyMelody cycles it. For a
  HeyMelody capture, stop our service from Quick Settings → Active apps (a plain `am force-stop`
  can be undone by our own reconnect). HeyMelody also greys out Earbud controls unless both buds are out.
- `Connection reset by peer` / `Broken pipe` appear during long sessions.
- **Reconnect after a lost link — FIXED 2026-09-23, confirmed by him.** Nothing used to retry after
  `Connection lost` unless Android fired ACL_CONNECTED (a codec switch drops our RFCOMM 2-3 times while
  the link stays up) — likely also the old "14-minute gap". `reconnectAfterLoss()` retries 5x with
  growing delays; a deliberate disconnect cancels it.

## Main screen structure — redesigned 2026-09-23 (`[USER]`: "make it your own")

Header (device name + Connect/Disconnect pill + dev-tools icon + settings cog) over a `ScrollView`
named `mainScroll`. Inside it, top to bottom:

1. `batteryCard` — ONE card holding `BudsStatusView` (custom-drawn, added to `statusSlot` in
   code): left bud, case, right bud, each inside a red battery ring that animates to the level, with
   the percentage (red at <= 20%) and "Left · In ear" style label under it. In ear = white icon,
   out = grey, in case = dimmed (no longer hidden, so nothing moves). Still collapses while
   disconnected (`setCardsVisible`). The old two-card layout, `SegmentedBarDrawable` and the icon
   fade/slide animations are gone.
2. `ancRow` — "Noise control" label, `AncSegmentedView` (custom-drawn pill, red highlight slides to
   the active mode; added to `ancSlot`) and `ancCaption` spelling the mode out, including the ANC
   strength. Tapping ANC opens `showAncChooser` as before; the tap names go through
   `onAncCircleTapped` via `ANC_SEGMENTS`.
3. `featureList` — the settings card, `@drawable/app_card_outline_bg`, filled at runtime by
   `MainActivity.buildFeatureRows()`. Declared empty in XML on purpose: six near-identical row
   layouts in XML would be six places to edit, and the icons need a themed tint that XML cannot
   apply to a vector drawable. `addRow()` inserts the hairline divider before every row but the first.

**The main screen carries no log**, by design — Dev Tools owns logging. `appendStatus()` only
appends to a bounded in-memory tail. See the note above about not putting user-visible output on a
packet-listener path.

## Widget flow

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
  EQ curve and sliders, the status rings, the noise-control highlight, the Connect pill when
  disconnected. The EQ curve / level slider / status card / noise pill share one drawn visual language.
- **The case icon keeps its LED dot**, and the lid cut stays full width — no hinge bulge or opening.
- **Icons keep their SVG's true ratio** (buds 176x272, case 496x400). `BudsStatusView` fits each into
  its ring by that ratio; the widget still uses its own sized boxes.
- The 2026 icon work is done for the app and the launcher; **the widget preview
  (`drawable/widget_preview_buds.xml`) is a separate copy** and must be updated alongside
  `ic_launcher_foreground.xml`.

## Repo hygiene

- **`local/` holds two folders now**: `logs/` (packet captures cited as evidence by PROTOCOL.md) and
  `svgs/` (the source SVGs the wear icons were traced from, named in the drawables' own headers).
  **`local/` is PC-only — git-ignored, never committed or pushed** (`[USER]` 2026-09-22; it was
  tracked until then and still sits in older commits' history). Doc references to `local/logs/`
  point at the developer's machine, not the repo. `local/commits/` (the v1.1.0 commit-message files) is
  gone — obsolete once commits started being made on the PC directly.
- **`local/notes/` and `local/NEXT-SESSION.md` are gone, on purpose** — all superseded, and their
  surviving content was folded into this file, PROTOCOL.md, ROADMAP.md and PACKET-CAPTURE.md.
  **Do not recreate a notes folder or a session-plan file.** Scattered per-purpose notes are exactly
  the drift that CLAUDE.md exists to stop — put new durable knowledge here, or in PROTOCOL.md if it
  is about the wire format.
- Runtime `*.log` files written by the app are ignored — they are regenerated every run. Handed-over
  captures (`*.log.txt`) are evidence and **are** tracked.
- There are no committed screenshots.
- Root docs: `README.md`, `ROADMAP.md`, `ROADMAP-DONE.md`, `CLAUDE.md`, `PROTOCOL.md`, `CREDITS.md`, `PACKET-CAPTURE.md`,
  `LICENSE`. The mobile→PC move's two temporary files, `START-HERE.md` and
  `DeepSeek_CodeAssist_memory.md`, served their purpose and are gone — this file is the sole entry
  point for a new session now.
