# QuickBuds — Roadmap

The parked and unverified work. Finished work leaves this file. Protocol findings go in [PROTOCOL.md](./PROTOCOL.md).

**The north star is parity with HeyMelody.** Our own improvements come next, other models after that.

## Planned

- **Protocol: merge each verified finding into the one layer.** The layer is `protocol/`. Build a facts ledger. It lists
  every command, push and feature against every model, with the gaps marked. Then add each verified fact to the layer and
  to `docs/PROTOCOL.md`.

- **Material style on the desktop, next passes.** The main window has the style choice: QuickBuds, Pixel art,
  Material. Material is the default for everyone without a saved style. The window also has the system accent and M3 cards. Tested on Xvfb only: check the tray panel (Windows), Windows and a real Linux accent.
- **Fixes from the 4.12.0 reports** (the rest wait for a retest or for realme Link data):
  - Crash on startup (RMX3890, Android 15): `KeepAliveReceiver` starts `BudsService` from the background and
    ColorOS refuses `startForeground`. The service stops itself now. Test on a device.
  - realme Buds T310 game mode (report #16): the buds ack the write but push no `GAME EVT`. The model
    entry now sets `gameConfirm: read`, so the status reply confirms the write. Retest on a T310.
  - realme Buds T500 Pro (report #21): the buds do not answer the `0x040E` tones of the hearing test.
    Needs the realme Link command.
  - realme Buds Air8 (report #20): no known command for announcement and tap sounds. `0x0132`
    belongs to the device manager. `0x0131` reads the AI translation setting (`0x0428`). Needs the
    realme Link command.
- **Desktop UI rewrite (egui).** The code is in desktop-egui/ on main: layout with a sidebar, the four styles
  including Glass with real blur, a frameless rounded window, the tray icon with the Linux menu and the Windows
  quick panel. Missing there: the Feedback, Model test and Update pages, and a bundled UI font. The released
  desktop stays on Slint until then.
- **Desktop setup guide and Known issues page.** The phone has a ten-page guide. The desktop has the Known issues dialog and
  the reply dialog only.
- **Captures wanted.** Buds push `0x050E` and answer `0x012C` (realme Air6, Air7 Pro and Air8 owners): the layouts are
  unknown, so QuickBuds does not use them. A Detailed log of these frames, with a note of what you did, decodes them.

## Parked

- **realme Link `0x0403` extras** (vocal enhance, `0x0403 09`; PROTOCOL.md §11): no data
  says which realme models have them. Start when a realme owner can test them (issue #8).

## Unverified

These work in code but no owner has confirmed them. They count as working until an issue says otherwise.

- realme Link data (noise control, gestures, wind noise) on 27 realme models. Only the Air7 Pro is
  confirmed (5 neckbands with `oneButton`, 5 with `bothHold`).
- The realme built-in EQ presets (Pure Raw, Deep Bass, Pure Voice, Pure Bass) on 28 realme models.
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
- **HeyMelody features that need OPPO software:**
  - Voice wakeup (`0x14`, Breeno).
  - Voice commands (`0x19`, Chinese only).
  - Incoming-call voice control (`0x39`).
  - Neck health and spine health (`0x22`-`0x24`, `0x0421`).
  - Meeting assistant (`0x34`).
  - Zen mode and white noise (server sound packs).
  - The tap camera shutter.
  - AI translation, summary and clear call (ColorOS only).
- **Multi-conversation** (`0x042E`) and the **LE Audio action** (`0x0424`).
- **HeyMelody features no model uses:** conversation mode (`smartCall`, `0x011D`), Spotify Tap
  (`spyTap`) and fall detection (`deviceLostRemind`, Enco Clip2 only).
- **HeyMelody pages with no value here:** Earphones Lab, Connection info (the status chip shows it),
  realme's "More functions", skins, guides, tutorials and diagnostics.
- **3D buds view** (the Aman-Kumar-Yadava fork's flat-drawn buds and case) and its Samsung / OnePlus
  look-alike styles: judged a poor prototype. Do not suggest it again.
- **Custom UI styles as a file** (import / export): built-in styles only.
- **A log on the main screen:** Dev tools owns logging.
