# QuickBuds

> Open-source HeyMelody alternative for Android. Control OnePlus / OPPO / realme earbuds over
> Bluetooth, no root: battery, noise cancellation (ANC), equalizer, hearing profile, gestures, wear
> detection, fit test, find my earbuds, dual connection, low latency mode and home screen widgets.

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](./LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208%2B-3DDC84.svg)]()
[![Release](https://img.shields.io/github/v/release/spizganed/QuickBuds)](https://github.com/spizganed/QuickBuds/releases/latest)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support-FF5E5B?logo=kofi&logoColor=white)](https://ko-fi.com/spizganed)

QuickBuds does everything the vendor app does for your earbuds, in a fast, clean app with a real
home-screen widget.

QuickBuds is purely a passion and hobby project. If you like it and want to help out, feel free to
donate on [Ko-fi](https://ko-fi.com/spizganed).

> **Desktop app for Windows and Linux.** A small native app in [`desktop/`](./desktop) with a tray icon,
> no drivers and no background services: battery, noise control, low latency, the equalizer, gestures,
> hearing profile, dual connection, earbud settings and a packet log. Download it from the [latest release](https://github.com/spizganed/QuickBuds/releases/latest):
> `windows-x64.zip` (portable, just unzip and run) or `linux-x64.tar.gz` (needs BlueZ, GTK 3 and
> libayatana-appindicator; see its README). Pair the buds in your system's Bluetooth settings first. With
> Dual connection on, the phone app and the desktop app work at the same time. From 4.3.1 it updates itself
> from App settings (a packaged install shows the release page instead). Progress is in
> [ROADMAP.md](./docs/ROADMAP.md).
>
> **Arch Linux:** install it as a package (it comes to the AUR as `quickbuds-bin` once AUR sign-ups reopen):
>
> ```bash
> git clone https://github.com/spizganed/QuickBuds && cd QuickBuds/desktop/aur && makepkg -si
> ```

## Screenshots

<!-- Retaken with scripts/readme-screenshots.sh classic, then dot-matrix; see CLAUDE.md. -->

| Main screen | Earbud settings | Equalizer | Hearing profile |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/main.png" width="200"> | <img src="docs/screenshots/earbuds.png" width="200"> | <img src="docs/screenshots/eq.png" width="200"> | <img src="docs/screenshots/hearing-profile.png" width="200"> |

Every other screen (gestures, themes, fit test, settings and more) is in [docs/screenshots](docs/screenshots).

### Widgets

Every size has a battery page and a controls page; switch with a double tap. 

| 2x2 | 4x2 |
| :---: | :---: |
| <img src="docs/screenshots/widget-2x2-battery.png" width="180"> | <img src="docs/screenshots/widget-4x2-battery.png" width="300"> |

### Dot matrix style

An optional style for the app and the widgets (Themes, colors & styles > Style): a dot font, no cards,
dot-matrix graphics. Every screen in this style is in [docs/screenshots/dot-matrix](docs/screenshots/dot-matrix).

| Main screen | 2x2 widget |
| :---: | :---: |
| <img src="docs/screenshots/dot-matrix/main.png" width="200"> | <img src="docs/screenshots/dot-matrix/widget-2x2-battery.png" width="220"> |

### Desktop

Windows and Linux (shown: KDE Plasma). The tray menu shows the battery; on Windows the tray opens quick controls.

<img src="docs/screenshots/desktop/window.png" width="640">

Every page, and the Dot matrix style, is in [docs/screenshots/desktop](docs/screenshots/desktop).

| Tray icon | Tray menu |
| :---: | :---: |
| <img src="docs/screenshots/desktop/tray.png" width="400"> | <img src="docs/screenshots/desktop/tray-menu.png" width="320"> |

## Features

- **Knows your earbuds:** reads the model from the earbuds and matches it against HeyMelody's own
  list of OnePlus, OPPO and realme models, then shows only what that model supports. You can also
  pick the model yourself.
- **Battery and wear status** for each bud and the case, live.
- **Noise control:** Off, Noise cancelling (Low / Medium / High / Smart), Adaptive and Transparency. Changes
  made on the earbuds show up instantly.
- **Equalizer:** built-in presets, up to 3 custom 6-band presets you draw on a curve, and bass boost.
  Saved on the earbuds.
- **High-quality audio (LHDC), 3D audio and Low latency mode.**
- **Hearing profile:** a hearing test (ear scan, then 6 tones per ear) that tunes the sound to your
  ears, run in the app. Profiles are kept on the phone, one tap applies one, and a graph per ear shows
  what it does. Profiles made in HeyMelody show up too.
- **Earbud fit test:** checks that the ear tips seal well.
- **Gestures** per bud: taps, slide and hold, plus the on-call gestures.
- **Wear detection**, **Dual connection**, **Find my earbuds**, the earbuds' prompt volume and their
  firmware version.
- **Whatever else your model has:** 3D audio with head tracking, game sound effects, vocal enhancement,
  smart / adaptive volume, power saving and more, each shown only where the earbuds support it.
- **Home-screen widgets** in two sizes (2×2, 4×2). Each has a battery page and a noise
  control + Low latency page, in your theme's colours.
- **Two styles:** Classic, or a dot-matrix look for the app and the widgets.
- **Themes:** OLED Black, Classic Dark and White, or match the system's light / dark setting. Your own
  accent colour and up to 3 custom colour presets. Reorder or hide the home screen rows.
- **27 languages**, switchable inside the app.
- **Update check** from inside the app, straight from GitHub releases.

## Install

1. Pair your earbuds in Android's Bluetooth settings first. QuickBuds connects to paired earbuds; it
   does not pair them.
2. Download `QuickBuds<version>.apk` from the
   [latest release](https://github.com/spizganed/QuickBuds/releases/latest) and install it.
3. Allow the Bluetooth permission when asked.

> **"App blocked" or "Unknown developer" from Play Protect?** Tap **Install anyway**. QuickBuds is a new,
> small app that Google has not seen much yet, so the warning is normal for it. It goes away in time.

> **Coming from v1.1.0?** Uninstall it first: v2.0.0 and later are signed with a new key.

> **Got the APK somewhere else?** The only official downloads are the GitHub releases. A copy from
> another site is genuine only if it is signed with this certificate (SHA-256), which
> `apksigner verify --print-certs <apk>` shows:
> `9ae0ea888079cbd86af5a24391cd351af4a81fc04ebe9bd8429074c736249f61`

## Supported earbuds

QuickBuds works on **any Android 8+ phone** (Samsung, Google Pixel, Xiaomi, Nothing, Motorola, OnePlus…),
not only on OnePlus / OPPO phones. It knows every model in HeyMelody's own list, 127 earbuds in all,
and shows only the features each one has.

| | Status |
| --- | --- |
| **OnePlus Buds 4** | **Fully working, confirmed**: every feature tested on a Nothing Phone (3a), Android 16. |
| Every other model | Detected, and its features are built from HeyMelody's own data, but **not confirmed yet**: some may not work. |

**Own other buds? Please tell us what works.** Open an [issue](https://github.com/spizganed/QuickBuds/issues)
with your model, what works and what does not, and a log: Dev tools › Export (the file lands in
`Download/QuickBuds/`), or a Bluetooth HCI snoop log / `adb logcat`. Each report lets the next release fix
that model. The app asks the same once, on first launch.

<details><summary><b>OnePlus</b> (29 models)</summary>

OnePlus Bullets Wireless Z2, OnePlus Bullets Wireless Z2 ANC, OnePlus Bullets Wireless Z3, OnePlus Buds, OnePlus Buds Z, OnePlus Buds Pro, OnePlus Buds Z2, OnePlus Nord Buds, OnePlus Buds N, OnePlus Nord Buds CE, OnePlus Buds Pro 2, OnePlus Nord Buds 2, OnePlus Buds Ace, OnePlus Nord Buds 2r, OnePlus Buds Pro 2R, OnePlus Buds 3, OnePlus Buds Pro 3, OnePlus Nord Buds 3 Pro, OnePlus Buds V, OnePlus Buds Ace 2, OnePlus Nord Buds 3, OnePlus Buds 4, OnePlus Nord Buds 3r, OnePlus Open Buds, OnePlus Buds 3V, OnePlus Buds Ace 3, OnePlus Nord Buds 4 Pro, OnePlus Nord Buds 4, OnePlus Flow Buds.

</details>

<details><summary><b>OPPO</b> (52 models)</summary>

OPPO Enco Quiet, OPPO Enco M31, OPPO Enco M32, OPPO Enco M33, OPPO Enco Free, OPPO O-Free, OPPO Enco W31, OPPO Enco W51, OPPO Enco W11, OPPO Enco X, OPPO Enco Air, OPPO Enco Play, OPPO Enco Free2, OPPO Enco Buds, OPPO Enco Air Lite, OPPO Enco W31 Lite, OPPO Enco R, OPPO Enco Air2, OPPO Enco Air2 Pro, OPPO Enco X2, OPPO Enco Free2i, OPPO Enco Air2i, OPPO Enco Buds2, OPPO Enco Air3, OPPO Enco R Pro, OPPO Enco R2, OPPO Enco Air3 Pro, OPPO Enco Free3, OPPO Enco X3i, OPPO Enco Air3i, OPPO Enco X3, OPPO Enco Air3s, OPPO Enco Air4 Pro, OPPO Enco Buds2 Pro, OPPO Enco R3, OPPO Enco Air 3i, OPPO Enco Free4, OPPO Enco Air4, OPPO Enco Air4i, OPPO Enco R3 Pro, OPPO Enco Buds3 Pro+, OPPO Enco Buds3, OPPO Enco Buds3 Pro, OPPO Enco R4, OPPO Enco R5, OPPO Enco Clip, OPPO Enco X3s, OPPO Enco Air5 Pro, OPPO Enco Air5s, OPPO Enco Air5, OPPO Enco Clip2, OPPO Enco Air4s.

</details>

<details><summary><b>realme</b> (43 models)</summary>

realme Buds Wireless 2S, realme Buds Wireless 3, realme Buds Wireless 3 Neo, realme Buds Wireless 5 ANC, realme Buds Wireless 6 Neo, realme Buds Wireless 6, realme Buds Wireless 6 ANC, realme Buds Air 3, realme Buds Q2s, realme Buds Air 3S, realme Buds T100, realme Buds Air 3 Neo, realme Buds Air 5 Pro, realme Buds Air 5, realme Buds T300, realme Buds T110, realme Buds Air6 Pro, realme Buds Air6, realme Buds N1 Pro, realme Buds T310, realme Buds N1, realme Buds T01, realme Buds Air7, realme Buds Air7 Pro, realme Buds T200 Lite, realme Buds T200, realme Buds T200x, realme Buds Clip, realme TechLife Buds, realme Buds T500 Pro, realme Buds Air8, realme Buds Air8 Pro, realme Buds T500, realme Buds T500 Pro Harry Potter Edition, realme Buds Air 2 Neo, realme Buds Air, realme Buds Q2, realme Buds Air Pro, realme Buds Air 2, realme Buds Wireless 2, realme Buds Wireless 2 Neo, realme Buds Wireless Pro, realme Buds Air Neo.

</details>

<details><summary><b>DIZO</b> (3 models)</summary>

DIZO Wireless, DIZO GoPods D, DIZO GoPods.

</details>

## For developers

- [CONTRIBUTING.md](./docs/CONTRIBUTING.md): build quickstart (Android and desktop), where things are, the rules that matter.
- [PACKET-CAPTURE.md](./docs/PACKET-CAPTURE.md): capturing the earbuds' traffic, HeyMelody's included.
- [TOOLCHAIN.md](./docs/TOOLCHAIN.md): building and testing on the phone itself, over SSH from a PC.
- [PROTOCOL.md](./docs/PROTOCOL.md): the wire format, every claim tagged with its source.
- [CLAUDE.md](./CLAUDE.md): conventions and decisions. The plan is in [ROADMAP.md](./docs/ROADMAP.md).

## Credits

The earbud protocol was never publicly documented. QuickBuds stands on the people who worked it out
first; PROTOCOL.md marks every fact taken from them `[OSS]`.

- [**Zhaoyi-ya/OppoPodsManager**](https://github.com/Zhaoyi-ya/OppoPodsManager): the main
  reference. Frame layout and length encoding, most of the command table, the noise control tables,
  the gesture entry shape and the feature IDs.
- [**Star-ZER0/Pods-Protocol-Reverse-Engineering**](https://github.com/Star-ZER0/Pods-Protocol-Reverse-Engineering)
  (CC-BY-SA-4.0, used as documentation): independent confirmation of the framing, and the broadcast
  codes that proved the noise control subscription fix.
- [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods): early protocol work and part of
  the framing knowledge.
- [Zhaoyi-ya/OPPO-Pods-Win](https://github.com/Zhaoyi-ya/OPPO-Pods-Win): which features each earbud
  model has.
- [ORION2809/DevPods](https://github.com/ORION2809/DevPods): a second implementation to cross-check
  against.

Everything else was captured on real hardware and tested on the device: the gesture values, the
equalizer writes, the hold's noise control cycle and more (see PROTOCOL.md).

The desktop app is built with [Slint](https://slint.dev) (used under the GPLv3) and
[tray-icon](https://github.com/tauri-apps/tray-icon).

The Dot matrix style's font is [Doto](https://github.com/oliverlalan/Doto) (SIL Open Font License 1.1,
bundled with its license in `app/src/main/assets/Doto-OFL.txt`).

## License

Copyright (C) 2026 spizganed

QuickBuds is free software under the GNU General Public License v3.0 or later, distributed without
any warranty; see [LICENSE](./LICENSE). The protocol sources above were used as documentation; check
each one's license before copying text from it.

QuickBuds is an independent project, not affiliated with, endorsed by or sponsored by OnePlus, OPPO,
realme, HeyTap or any of their affiliates. OnePlus, OPPO, realme, HeyMelody and the earbud model names
are trademarks of their respective owners and are used here only to say which devices the app works
with. The protocol was studied only to make these earbuds work with this app.
