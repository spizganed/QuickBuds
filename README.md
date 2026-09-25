# QuickBuds

> Control OnePlus / OPPO / realme earbuds straight over Bluetooth. No HeyMelody, no account, no root.

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](./LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208%2B-3DDC84.svg)]()
[![Release](https://img.shields.io/github/v/release/spizganed/QuickBuds)](https://github.com/spizganed/QuickBuds/releases/latest)

QuickBuds talks to your earbuds directly over a classic Bluetooth RFCOMM channel, the same link the
vendor app uses, and gives you everything HeyMelody does in a dark, fast UI with a real home-screen
widget. Every command it sends was reverse-engineered and confirmed on real hardware.

## Screenshots

<!-- Retaken with scripts/readme-screenshots.sh; see CLAUDE.md. -->

| Main screen | Equalizer | Curve editor | Earbud gestures | Widget |
| :---: | :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/main.png" width="200"> | <img src="docs/screenshots/eq.png" width="200"> | <img src="docs/screenshots/eq-curve.png" width="200"> | <img src="docs/screenshots/controls.png" width="200"> | <img src="docs/screenshots/widget.png" width="200"> |

## Features

**Noise control**
- Off, Noise cancelling (Low / Medium / High), Adaptive and Transparency
- Follows changes made on the earbuds themselves, instantly

**Sound**
- **Equalizer**
  - Built-in presets: Balanced, Clear Vocals and Bass.
  - Up to 3 custom presets, edited on a draggable 6-band curve (±6 dB). You can create, rename and delete them.
  - Bass boost with a −5…+5 level.
  - Everything is saved on the earbuds, so HeyMelody sees the same presets.
- **High-quality audio (Hi-Res LHDC) and 3D audio.** The earbuds can't run both, so switching warns you first. A codec change makes the earbuds reconnect.
- **Low latency mode** for video and games

**Earbud settings**
- **Gestures:** single, double and triple tap, slide and hold, set **per bud**
- Choose which noise modes the hold cycles through
- On-call gestures: double tap to answer or end, long hold to decline
- Read back from the earbuds on every connect, so changes made elsewhere show up
- **Wear detection:** the earbuds' own auto play/pause, or our smart auto-pause that pauses only
  when both earbuds are out and never auto-plays
- **Dual connection:** on/off, and which devices the earbuds are connected to.
- **Find my earbuds.** Plays the earbuds' own loud tone on both buds, with a warning if they're in your ears.
- **Alert sound volume** for the earbuds' own prompt tones

**Everything else**
- **Live status.** Battery for each bud and the case, plus in ear / out / in case, pushed by the earbuds in real time.
- **Home-screen widget** with battery, wear state, noise control and Low latency, working with the app closed
- **Quick Settings tile**
- **Connect / Disconnect** button, and automatic reconnect when the link drops
- **OLED black and dark themes**

## Install

1. Pair your earbuds in Android's Bluetooth settings first. QuickBuds connects to paired earbuds, it
   doesn't pair them.
2. Download `QuickBuds<version>.apk` from the [latest release](https://github.com/spizganed/QuickBuds/releases/latest) and install it.
3. Grant the Bluetooth permission when asked.

Updates can be checked from inside the app (**App update**). Nothing is checked automatically.

> **Coming from v1.1.0?** v2.0.0 is signed with a new key. Uninstall the old version first, or
> Android will refuse the update.

**Tested on:** OnePlus Buds 4 (firmware `B4.1-260810-1153`) · Nothing Phone (3a), Android 15.
Other OnePlus / OPPO / realme earbuds share the protocol and will likely work, but are untested.

## Roadmap

HeyMelody parity comes first, then this project's own ideas on top, then other earbud models. See
[ROADMAP.md](./ROADMAP.md) for the ordered plan.

## For developers

| File | What it is |
| --- | --- |
| [PROTOCOL.md](./PROTOCOL.md) | **The wire format, end to end**: frames, every command, and the mistakes already made. Read it before touching protocol code. |
| [ROADMAP.md](./ROADMAP.md) | What's next. [ROADMAP-DONE.md](./ROADMAP-DONE.md) has what's done. |
| [CLAUDE.md](./CLAUDE.md) | Toolchain, conventions and working notes. |
| [CREDITS.md](./CREDITS.md) | Exactly what came from where. |
| [PACKET-CAPTURE.md](./PACKET-CAPTURE.md) | How to capture a Bluetooth log from the phone. |

**Build:** Gradle 8.13, Android Gradle Plugin 8.13, Kotlin 2.4, JDK 17–23. The only dependency is
`androidx.core`.

```bash
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed only on the maintainer's machine; without the key they come out unsigned.

## Credits

### The protocol was reverse-engineered by others first

The OPPO / OnePlus / realme earbud protocol was never publicly documented. Everything this project
knows about it stands on people who worked it out first and published their results:

| Project | What we owe it |
| --- | --- |
| [**Zhaoyi-ya/OppoPodsManager**](https://github.com/Zhaoyi-ya/OppoPodsManager) | The single biggest source. Frame layout and LEB128 length encoding, most of the command table, the ANC set and notify tables, the key-function entry shape, feature IDs. |
| [**Star-ZER0/Pods-Protocol-Reverse-Engineering**](https://github.com/Star-ZER0/Pods-Protocol-Reverse-Engineering) | Independent confirmation of the framing, and the broadcast-codes idea that proved our ANC subscription fix was right. (CC-BY-SA-4.0 — documentation only.) |
| [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods) | Early OPPO earbud protocol work; origin of some of the framing knowledge. |
| [Zhaoyi-ya/OPPO-Pods-Win](https://github.com/Zhaoyi-ya/OPPO-Pods-Win) | Cross-check on which features exist per device model. |
| [ORION2809/DevPods](https://github.com/ORION2809/DevPods) | A second implementation of the same vendor family; useful for cross-checking. |

Sibling project for another brand, not a source for ours but worth knowing:
[elaxptr/baseus-desktop](https://github.com/elaxptr/baseus-desktop), a Windows client for Baseus earbuds.

### How it was built

Every protocol claim comes from Bluetooth captures taken on real hardware and was tested on the
device. That's what makes [PROTOCOL.md](./PROTOCOL.md) checkable.

## License

GPL-3.0. See [LICENSE](./LICENSE). Protocol references are used as documentation; check
[CREDITS.md](./CREDITS.md) for each source's license before copying text from it.
