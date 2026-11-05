#!/usr/bin/env bash
# Builds the desktop release files (Tauri app, desktop-tauri/) into desktop-tauri/dist/:
#   QuickBuds<version>-windows-x64.zip   quickbuds.exe only (portable, static runtime)
#   QuickBuds-linux-x64.AppImage         WebKitGTK inside. No version in the name: releases/latest/download/ always finds it.
# Usage: scripts/desktop-dist.sh <version>   (e.g. 5.7.0). QB_APPIMAGE_ONLY=1 builds the AppImage only.
# Needs: podman (unless QB_NATIVE=1), zip. Windows also needs cargo-xwin, clang, lld, llvm and the msvc rustup target.
# Linux builds in Ubuntu 24.04 (glibc 2.39): its WebKitGTK renders the transparent window cleanly (the 22.04 one ghosts text).
# The podman image (scripts/desktop-build.Containerfile) is built once.
set -euo pipefail
ver=${1:?version}
root=$(cd "$(dirname "$0")/.." && pwd)
app=$root/desktop-tauri
dist=$app/dist
rm -rf "$dist" && mkdir -p "$dist"

# Windows, in the background while Linux builds; its output goes to a log.
if [ -z "${QB_APPIMAGE_ONLY:-}" ]; then
  winlog=$app/target/windows-build.log
  mkdir -p "$app/target"
  (
    cd "$app"
    XWIN_ACCEPT_LICENSE=1 RUSTFLAGS="-C target-feature=+crt-static -C link-arg=libucrt.lib -C link-arg=libvcruntime.lib" \
      cargo xwin build --release --target x86_64-pc-windows-msvc
    cd target/x86_64-pc-windows-msvc/release && zip -q "$dist/QuickBuds$ver-windows-x64.zip" quickbuds.exe
  ) >"$winlog" 2>&1 &
  winpid=$!
fi

# Linux. QB_NATIVE=1 builds in place, for CI, which already runs in Ubuntu 24.04 with the packages of the Containerfile.
build='cargo tauri build --bundles appimage'
if [ -n "${QB_NATIVE:-}" ]; then
  (cd "$app" && CARGO_TARGET_DIR=target/ubuntu22 APPIMAGE_EXTRACT_AND_RUN=1 NO_STRIP=1 $build)
else
  img=quickbuds-build:tauri
  want=$(sha256sum "$root/scripts/desktop-build.Containerfile" | cut -c1-12)
  [ "$(podman image inspect -f '{{index .Labels "cf"}}' "$img" 2>/dev/null)" = "$want" ] ||
    podman build -q -t "$img" --label "cf=$want" -f "$root/scripts/desktop-build.Containerfile" "$root/scripts" >/dev/null
  podman run --rm --userns=keep-id -v "$root:/repo" -v quickbuds-cargo:/cargo-reg -w /repo/desktop-tauri --user root \
    -e CARGO_HOME=/cargo-reg -e CARGO_TARGET_DIR=/repo/desktop-tauri/target/ubuntu22 -e APPIMAGE_EXTRACT_AND_RUN=1 -e NO_STRIP=1 \
    "$img" bash -c '
      set -e
      export PATH=/cargo/bin:$PATH
      '"$build"'
      chown -R '"$(id -u):$(id -g)"' /repo/desktop-tauri/target/ubuntu22 /cargo-reg'
fi
cp "$app"/target/ubuntu22/release/bundle/appimage/*.AppImage "$dist/QuickBuds-linux-x64.AppImage"
if [ -n "${winpid:-}" ]; then wait "$winpid" || { cat "$winlog"; exit 1; }; tail -2 "$winlog"; fi
ls -la "$dist"
