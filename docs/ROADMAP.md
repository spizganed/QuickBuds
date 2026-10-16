# QuickBuds — Roadmap

The parked and unverified work. Finished work leaves this file. Protocol findings go in [PROTOCOL.md](./PROTOCOL.md).

**The north star is parity with HeyMelody.** Our own improvements come next, other models after that.

## Planned

- **Material style on the desktop**, as on the phone: M3 shapes, sliders, switches and segmented
  lists, with Material as the default style. The desktop has QuickBuds and Pixel art now.
  The desktop style is one on/off value (`Dots.on` in `ui/app.slint`). Change it to a style choice first.
  Colours come from the system accent. The first pass covers the main window only.
- **Glass style as the fourth style, on every platform.** Source: the fork
  [ayush04-op/QuickBuds-Glass](https://github.com/ayush04-op/QuickBuds-Glass) (GPL-3.0). Keep its credit.
  - Android screens: take `LiquidGlassHelper.kt` and its small hooks in `ThemeRes`, `SettingRowFactory`,
    `BottomSheetDialog` and `MainActivity`. Make glass one style choice, not a separate mode. Check it with
    Material colours, wallpaper colours and OLED Black. Check the blur API level (Android 12 or newer?) and
    hide or fall back on older phones. Add the style name in all 26 locales.
  - Widgets: the fork has none. Draw a translucent tinted panel with a bright edge, the same way the
    Material widgets draw their one background. A launcher blurs nothing behind a widget.
  - Desktop: Slint has no blur behind a window. Draw a translucent tinted card with a highlight edge.
    Build it together with the desktop Material work, on the same style choice.
- **Fixes from the 4.12.0 reports:**
  - Crash on startup (RMX3890, Android 15): `BudsService.startForeground` is refused in the background.
    The service now stops itself. Find what starts it in the background, and test on a device.
  - realme Buds T310: game mode does nothing (report #16). Compare the command with HeyMelody's.
  - realme Buds T500 Pro: the sound profile plays no melody and cannot be adjusted (report #21).
  - realme Buds T100: high-quality audio, 3D audio, dual connection and wear detection fail (report #7).
    The model list may show features the buds do not have.
  - realme Buds Air8: a question about turning off the audio announcements and tap sounds (report #20).
    Check whether HeyMelody has a command for it. Reply to the reporter either way.

## Parked

- **realme Link `0x0403` extras** (dynamic bass, vocal enhance; PROTOCOL.md §9): no data
  says which realme models have them. Start when a realme owner can test them (issue #8).

## Unverified

These work in code but no owner has confirmed them. They count as working until an issue says otherwise.

- realme Link data (noise control, gestures, wind noise) on 27 realme models. Only the Air7 Pro is
  confirmed (5 neckbands with `oneButton`, 5 with `bothHold`).
- The realme built-in EQ presets (Pure Raw, Deep Bass, Pure Voice, Pure Bass) on 27 realme models.
  The Buds Air8 and Buds Air 5 Pro reports asked for them. T110 and T01 still need a capture.
- Personalized ANC, the dual-connection device manager and the preferred device.

## Decided against — do not suggest again

- **Other brands** (Xiaomi, Samsung, Sony, Huawei, Soundcore): each is a separate protocol that one
  person cannot test.
- **Any feature that needs a server.** Exception: the problem report's free Cloudflare Worker
  (`report-worker/`).
- **Firmware updates:** a failed flash can brick the buds. The firmware row points to HeyMelody.
- **Right-to-left languages:** they need a mirrored layout and a check of every custom-drawn view.
- **Widgets:** a lock-screen widget; a widget on/off switch; a widget larger than the launcher's padding
  (the host clips it).
- **Removed controls:** the Quick Settings tile and the fixed-level hold.
- **Removing the foreground-service notification:** Android 15 requires it for a `connectedDevice` service.
- **Slide up vs slide down:** the firmware maps the direction itself, as with HeyMelody.
- **Hearing profile:** the before / after preview (`0x040E 01` / `02`) and renaming (the date labels it).
- **HeyMelody features that need OPPO software:** voice wakeup (`0x14`, Breeno), voice commands
  (`0x19`, Chinese only), incoming-call voice control (`0x39`), neck health (`0x22`-`0x24`), meeting
  assistant (`0x34`), Zen mode and white noise (server sound packs), the tap camera shutter, and
  AI translation, summary and clear call (ColorOS only).
- **HeyMelody features no model uses:** conversation mode (`smartCall`, `0x011D`), Spotify Tap
  (`spyTap`) and fall detection (`deviceLostRemind`, Enco Clip2 only).
- **HeyMelody pages with no value here:** Earphones Lab, Connection info (the status chip shows it),
  realme's "More functions", skins, guides, tutorials and diagnostics.
- **Custom UI styles as a file** (import / export): built-in styles only.
- **A log on the main screen:** Dev tools owns logging.
