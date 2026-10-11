# QuickBuds — Roadmap

The parked and unverified work. Finished work leaves this file. Protocol findings go in [PROTOCOL.md](./PROTOCOL.md).

**The north star is parity with HeyMelody.** Our own improvements come next, other models after that.

## Parked

- **realme Link `0x0403` extras** (dynamic bass, vocal enhance; PROTOCOL.md §9): no data
  says which realme models have them. Start when a realme owner can test them (issue #8).

## Unverified

These work in code but no owner has confirmed them. They count as working until an issue says otherwise.

- realme Link data (noise control, gestures, wind noise) on 27 realme models. Only the Air7 Pro is
  confirmed (5 neckbands with `oneButton`, 5 with `bothHold`).
- Personalized ANC, the dual-connection device manager and the preferred device.

## Decided against — do not suggest again

- **Other brands** (Xiaomi, Samsung, Sony, Huawei, Soundcore): each is a separate protocol that one
  person cannot test.
- **Any feature that needs a server.** Exception: the problem report's free Cloudflare Worker
  (`report-worker/`).
- **Firmware updates:** a failed flash can brick the buds. The firmware row points to HeyMelody.
- **Right-to-left languages:** they need a mirrored layout and a check of every custom-drawn view.
- **Widgets:** a resizable 2x2, 3x3, 3x2, 2x3, 4x1 or 2x1 size; a lock-screen widget; a widget
  on/off switch; a widget larger than the launcher's padding (the host clips it).
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
