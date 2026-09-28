# QuickBuds

> Open-source HeyMelody alternative for Android. Control OnePlus / OPPO / realme earbuds over
> Bluetooth, no root: battery, noise cancellation (ANC), equalizer, gestures, wear detection, find my
> earbuds, dual connection, low latency mode and home screen widgets.

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](./LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208%2B-3DDC84.svg)]()
[![Release](https://img.shields.io/github/v/release/spizganed/QuickBuds)](https://github.com/spizganed/QuickBuds/releases/latest)

QuickBuds does everything the vendor app does for your earbuds, in a fast, clean app with a real
home-screen widget.

## Screenshots

<!-- Retaken with scripts/readme-screenshots.sh; see CLAUDE.md. -->

| Main screen | Model | Equalizer | Curve editor |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/main.png" width="200"> | <img src="docs/screenshots/models.png" width="200"> | <img src="docs/screenshots/eq.png" width="200"> | <img src="docs/screenshots/eq-edit.png" width="200"> |

| Earbud settings | Earbud gestures | Wear detection | Find my earbuds |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/earbuds.png" width="200"> | <img src="docs/screenshots/gestures.png" width="200"> | <img src="docs/screenshots/wear.png" width="200"> | <img src="docs/screenshots/find.png" width="200"> |

| Settings | Theme & colors | Edit preset | Home layout |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/settings.png" width="200"> | <img src="docs/screenshots/theme.png" width="200"> | <img src="docs/screenshots/preset.png" width="200"> | <img src="docs/screenshots/home-layout.png" width="200"> |

| Dual connection | Widget settings | App update | About |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/dual.png" width="200"> | <img src="docs/screenshots/widget-settings.png" width="200"> | <img src="docs/screenshots/update.png" width="200"> | <img src="docs/screenshots/about.png" width="200"> |

### Widgets

Every size has a battery page and a controls page; switch with the swap button or a double tap.

| 2x2 | 3x3 | 4x2 |
| :---: | :---: | :---: |
| <img src="docs/screenshots/widget-2x2-battery.png" width="180"> | <img src="docs/screenshots/widget-3x3-controls.png" width="220"> | <img src="docs/screenshots/widget-4x2-battery.png" width="300"> |

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
- **Gestures** per bud: taps, slide and hold, plus the on-call gestures.
- **Wear detection**, **Dual connection**, **Find my earbuds**, the earbuds' prompt volume and their
  firmware version.
- **Home-screen widgets** in three sizes (2×2, 3×3, 4×2). Each has a battery page and a noise
  control + Low latency page, in your theme's colours.
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

> **Coming from v1.1.0?** Uninstall it first: v2.0.0 and later are signed with a new key.

**Tested on:** OnePlus Buds 4 with a Nothing Phone (3a), Android 16. Other OnePlus / OPPO / realme
earbuds use the same protocol and are detected, but are untested: reports on GitHub are welcome.

## For developers

Start with [CLAUDE.md](./CLAUDE.md) (toolchain and conventions) and
[PROTOCOL.md](./docs/PROTOCOL.md) (the wire format, every claim tagged with its source). The plan is
in [ROADMAP.md](./docs/ROADMAP.md). `./gradlew assembleDebug` builds a debug APK.

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

## License

Copyright (C) 2026 spizganed

QuickBuds is free software under the GNU General Public License v3.0 or later, distributed without
any warranty; see [LICENSE](./LICENSE). The protocol sources above were used as documentation; check
each one's license before copying text from it.
