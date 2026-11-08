# Build the desktop app

The same code builds on Linux and on Windows. You need Rust (stable, from [rustup.rs](https://rustup.rs)).

- **Linux:** also BlueZ and the development packages below.
- **Windows:** rustup's default MSVC toolchain needs the Visual Studio C++ Build Tools; rustup offers them. The app needs the WebView2 runtime. Windows 11 has it.

| Distribution | Packages |
|---|---|
| Arch | `base-devel dbus gtk3 webkit2gtk-4.1 libayatana-appindicator librsvg openssl` |
| Debian / Ubuntu | `build-essential pkg-config libssl-dev libdbus-1-dev libgtk-3-dev libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev` |

```bash
git clone https://github.com/spizganed/QuickBuds.git
cd QuickBuds/desktop-tauri
cargo test
cargo install tauri-cli --version "^2" --locked
cargo tauri dev
```

Pair the earbuds in the system's Bluetooth settings first. The app connects to them once their audio is
connected. Icons, strings and `models.json` come from the Android app at build time, so the two apps never
hold separate copies.

## Release files

`scripts/desktop-dist.sh <version>` builds the Windows zip and the Linux AppImage into `desktop-tauri/dist/`, on
Linux. The Windows exe is cross-built with `cargo-xwin`. It needs `cargo install cargo-xwin`,
`rustup target add x86_64-pc-windows-msvc`, clang, lld, llvm, `zip` and podman. The Linux build runs in the image
of `scripts/desktop-build.Containerfile` (Ubuntu 24.04, tauri-cli). With `QB_NATIVE=1` it builds Linux without
podman, on the current system. With `QB_APPIMAGE_ONLY=1` it builds the AppImage only.

`cargo tauri build --bundles appimage` makes the AppImage. It has WebKitGTK inside, so the AppImage needs no distro
packages. The AppImage updates itself: the updater replaces the file in `$APPIMAGE`.
From the phone, `gh workflow run Build -f appimage_only=true` builds the AppImage only.
