# Build the desktop app

The same code builds on Linux and on Windows. You need Rust (stable, from [rustup.rs](https://rustup.rs)).

- **Linux:** also BlueZ and the development packages below.
- **Windows:** rustup's default MSVC toolchain needs the Visual Studio C++ Build Tools; rustup offers them.
  With mingw-w64 on `PATH` (or `WINDRES` set), the exe gets its icon. Without it, the build warns and the
  exe has no icon.

| Distribution | Packages |
|---|---|
| Arch | `base-devel dbus gtk3 libayatana-appindicator fontconfig libxkbcommon` |
| Debian / Ubuntu | `build-essential pkg-config libdbus-1-dev libgtk-3-dev libayatana-appindicator3-dev libfontconfig1-dev libxkbcommon-dev` |

```bash
git clone https://github.com/spizganed/QuickBuds.git
cd QuickBuds/desktop
cargo test
cargo run --release
```

Pair the earbuds in the system's Bluetooth settings first. The app connects to them once their audio is
connected. Icons, strings and `models.json` come from the Android app at build time, so the two apps never
hold separate copies.

## Release archives

`scripts/desktop-dist.sh <version>` builds the Windows zip, the Linux tarball and the Linux AppImage into
`desktop/dist/`, on Linux. The Windows exe is cross-built. It needs `rustup target add x86_64-pc-windows-gnu`,
mingw-w64, `zip`, `python3` and podman. With `QB_NATIVE=1` it builds the Linux files without podman, on the
current system. With `QB_APPIMAGE_ONLY=1` it builds the AppImage only.

`scripts/desktop-appimage.sh` makes the AppImage with linuxdeploy and its GTK plugin. It downloads both into
`desktop/target/appimage-tools/`. It adds GTK, libayatana-appindicator and libxkbcommon-x11, so the AppImage
needs no distro packages. The AppImage updates itself: the updater replaces the file in `$APPIMAGE`.
From the phone, `gh workflow run Build -f appimage_only=true` builds the AppImage only.
