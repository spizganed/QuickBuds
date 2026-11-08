# QuickBuds — Roadmap

The parked and unverified work. Finished work leaves this file. Protocol findings go in [PROTOCOL.md](./PROTOCOL.md).

**The north star is parity with HeyMelody.** Our own improvements come next, other models after that.

## Planned

- **Confirm more models.** The goal is about 100 models, or 80% of the list, confirmed. Today 42 are. Fix what the
  model tests and the issues report. A missing feature needs a snoop log and a screen recording
  ([PACKET-CAPTURE.md](./PACKET-CAPTURE.md#send-a-snoop-log-for-a-missing-feature)).
- **Protocol: merge each verified finding into the one layer.** The layer is `protocol/`. Build a facts ledger. It lists
  every command, push and feature against every model, with the gaps marked. Then add each verified fact to the layer and
  to `docs/PROTOCOL.md`.
- **Desktop UI rewrite (Tauri).** The code is in `desktop-tauri/`. It builds the release files. The old Slint desktop is on the branch `slint-archive`.
- **Captures wanted:**
  - Buds push `0x050E` and answer `0x012C` (realme Air6, Air7 Pro and Air8): the layouts are unknown, so QuickBuds does
    not use them.
  - realme Buds Air8: announcement and tap sounds. `0x0132` belongs to the device manager. `0x0131` reads the AI
    translation setting (`0x0428`).
  - Find my phone (issue #24): the frame the buds send for the tap action.

## Parked

- **Other brands**, in this order. Start after about 100 models (80% of the list) are confirmed. Each brand is a
  separate protocol and needs snoop logs and the earbuds in hand.
  1. Nothing.
  2. Google.
  3. Xiaomi, Samsung, Sony, Huawei and other brands. They need research first.
  4. Anker (Soundcore, issue #32).
- **Generic fallback (issue #22):** battery and connection state from Fast Pair and the standard battery service, for
  earbuds that QuickBuds does not know. Experimental, after the other brands.
- **3D audio and high-quality audio tiles:** move the rules that turn one off when the other turns on out of the main
  screen first, so the tiles and the app share them.
- **realme Link `0x0403` extras** (vocal enhance, `0x0403 09`; PROTOCOL.md §11): no data
  says which realme models have them. Start when a realme owner can test them (issue #8).

## Unverified

These work in code but no owner has confirmed them. They count as working until an issue says otherwise.

- realme Link data (noise control, gestures, wind noise) on the realme models that are not confirmed.
- The realme built-in EQ presets (Pure Raw, Deep Bass, Pure Voice, Pure Bass) on the realme models that are not confirmed.
- Personalized ANC, the dual-connection device manager and the preferred device.

## Decided against — do not suggest again

- **Any feature that needs a server.** Exception: the problem report's free Cloudflare Worker
  (`report-worker/`).
- **Firmware updates:** a failed flash can brick the buds. The firmware row points to HeyMelody.
- **Right-to-left languages:** they need a mirrored layout and a check of every custom-drawn view.
- **Widgets:** a lock-screen widget; a widget on/off switch; a widget larger than the launcher's padding
  (the host clips it).
- **Removed controls:** the fixed-level hold.
- **Smartspacer and other launcher plugins:** the notification, the widgets and the Quick Settings tiles cover
  quick access. A finished, tested pull request from a fork is welcome.
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
