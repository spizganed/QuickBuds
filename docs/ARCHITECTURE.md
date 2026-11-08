# Architecture

QuickBuds has four parts. This file shows how they connect and why. The wire format is in
[PROTOCOL.md](./PROTOCOL.md). The setting engine is in [FEATURE-SYNC.md](./FEATURE-SYNC.md).

## The parts

| Part | Folder | Language | Role |
|---|---|---|---|
| Core | `core/` | Rust | Frames, typed messages, commands, the setting engine. No I/O, no dependencies. |
| Android app | `app/` | Kotlin | Phone UI, Bluetooth link, service, widgets, tiles, notification. |
| Desktop app | `desktop-tauri/` | Rust, web UI | Windows and Linux. The link thread and protocol live in `desktop-tauri/src`. The Tauri window shows the UI from `desktop-tauri/ui/`. |
| Report Worker | `report-worker/` | JavaScript | Cloudflare Worker: problem reports, the daily count, the update check, the Discord digest. |

The core has no Bluetooth code. Each platform shell owns the RFCOMM socket and passes bytes in and out.
This keeps the core testable without a device.

The Android app does not call the core. `app/.../protocol/` holds a Kotlin copy of the same rules
(`Wire.kt`, `Commands.kt`, `Messages.kt`, `FeatureSync.kt`). A protocol change updates both copies and PROTOCOL.md.
The desktop app calls the core directly (`desktop-tauri/src/main.rs` re-exports `cmd`, `msg` and `wire`).
`core/src/ffi.rs` offers a C interface. Nothing uses it yet.

`desktop-tauri/src/protocol.rs` is the legacy decoder.

## The model data

`app/src/main/assets/models.json` lists every model. Each entry has the id, the name, the commands the buds accept,
and per-model flags (for example `seqless`, `gameConfirm`, `noFind`). Code reads a flag from this file. Code never
tests a model name. A new model needs only a new entry ([ADD-A-MODEL.md](./ADD-A-MODEL.md)). The Worker imports the
same file to accept a report only for a known model.

## From a button to the buds and back

1. The user taps a control. The UI calls `requestSetting(key, writes, confirm)` on the connection manager
   (Android) or sends a `Cmd` to the link thread (desktop).
2. The engine in `FeatureSync` takes the operation. It shows "Applying" and sends the first write.
   The control does not move.
3. The buds ack each write with a status. The next write goes out after status 0.
4. The engine confirms the setting by one of three ways: a status read, a push from the buds, or the ack alone.
   The model entry in `models.json` can change the way.
5. The buds' report decides. The engine emits `changed`, and the UI shows the new value.
   A refusal or a missing answer shows a toast that names the setting.

The buds are the only source of truth. The app is a remote control.

## Main flows

### Connect (Android)

1. `KeepAliveReceiver` sees an audio profile connect. `BudsDevice` picks the paired device that is the buds.
2. `BudsConnectionManager.connect` opens the RFCOMM socket with the OPPO SPP UUID.
3. `onLinkUp` starts the init sequence on its own thread: handshake, product id, broadcast codes, then the
   notification registration.
4. Reads follow, and only for commands that the model lists (`Capabilities`): status, ANC, battery, wearing, gestures,
   spatial type, codec, game sound. Models with the batch command get these reads in one frame.
5. Replies arrive as frames. `Decode` and `Messages` turn them into typed values. The manager tells its listeners:
   `BudsService` (notification, chip), the activities, the widgets and the tiles.
6. A link that drops without our request reconnects with growing delays. The service parks when the link is idle.

The desktop link thread (`desktop-tauri/src/session.rs`) runs the same sequence and the same rules.

### A setting change

See "From a button to the buds and back". A write that restarts the link (Hi-Res, power saving, codec)
waits for the first report after the reconnect.

### A problem report

1. The user fills the form (`ReportActivity` on Android, the Feedback page on desktop).
2. `ProblemReport` builds the log: a header, the packet log and the app's own warnings. It removes Bluetooth
   addresses and device names before the log leaves the phone.
3. The app posts JSON to `/report` on the Worker.
4. The Worker checks the model, the description and the log, and limits one sender to 5 reports an hour.
   It stores the report in D1.
5. A cron runs every 15 minutes. It puts each new report on a queue. The queue consumer posts one Discord message
   for each report.
6. The developer reads the report on the Worker page with the read key and sets its status in D1.

### The daily count and replies

Once a day the app posts its platform, version and random install ID to `/ping`. The Worker counts the install.
If the developer wrote a reply to a report of that install, the answer carries it. The app stores it, shows it once,
and sends its id in `ack` on the next ping. The Worker marks the reply as seen only then.
The user can turn the count off in Settings.

### The update check

1. The app asks `/latest` on the Worker, not GitHub. This saves the 60 requests an hour that GitHub gives one IP.
2. The Worker answers from the edge cache for 5 minutes. On a miss it asks GitHub and keeps the fields the apps need.
   It stores the last good answer in KV, and writes KV only when the release changes.
3. If the Worker fails, the app asks GitHub directly.
4. Android needs the `.apk` asset in the release. It downloads the file and opens the system installer through a FileProvider. The user confirms.
5. Desktop picks the file for its system (`-windows-x64.zip` or `-linux-x64.AppImage`),
   checks `SHA256SUMS` and replaces its own program. It never writes into a folder it cannot write to.
   It opens the release page instead.

## Rules that keep the parts in step

- A protocol change updates `core/`, the Kotlin copy and PROTOCOL.md together.
- Docs change in the same commit as the code.
- A new user-visible string needs all 26 locales. `scripts/check-locales.py` checks this.
- A change to `models.json` needs `npx wrangler deploy` in `report-worker/`. Else the Worker rejects reports for the new model.
