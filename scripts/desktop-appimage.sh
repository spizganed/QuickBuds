#!/usr/bin/env bash
# Builds the Linux AppImage from the staged Linux files (quickbuds, quickbuds.desktop, quickbuds.svg), with GTK and
# libayatana-appindicator inside, so it needs no distro packages. scripts/desktop-dist.sh calls it.
# Run it in the system that built the binary: linuxdeploy copies that system's libraries.
# Usage: scripts/desktop-appimage.sh <staged folder> <output file>
# Needs: curl, file, pkg-config, libxkbcommon-x11-0, dpkg-dev (the GTK plugin) and the -dev packages of scripts/desktop-build.Containerfile.
# No FUSE: the tools run extracted.
set -euo pipefail
pkg=$(realpath "$1")
out=$(realpath -m "$2")
root=$(cd "$(dirname "$0")/.." && pwd)
tools=$root/desktop/target/appimage-tools
mkdir -p "$tools"
get() { [ -s "$tools/$1" ] || curl -fsSL -o "$tools/$1" "$2"; chmod +x "$tools/$1"; }
get linuxdeploy-x86_64.AppImage https://github.com/linuxdeploy/linuxdeploy/releases/download/continuous/linuxdeploy-x86_64.AppImage
get linuxdeploy-plugin-gtk.sh https://raw.githubusercontent.com/linuxdeploy/linuxdeploy-plugin-gtk/master/linuxdeploy-plugin-gtk.sh

# The binary opens these at run time (tray-icon, winit), so linuxdeploy does not find them alone.
libs=()
for name in libayatana-appindicator3.so.1 libxkbcommon-x11.so.0; do
  lib=$(find /usr/lib /usr/lib64 -name "$name" 2>/dev/null | head -1)
  [ -n "$lib" ] || { echo "$name not found" >&2; exit 1; }
  libs+=(-l "$lib")
done

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"
# appimagetool needs a PNG named after the desktop file's Icon= for .DirIcon; it refuses an SVG (since 2026-10-31).
# desktop/quickbuds-appimage.png is quickbuds.svg drawn at 256x256 (rsvg-convert -w 256 -h 256).
cp "$root/desktop/quickbuds-appimage.png" quickbuds.png
APPIMAGE_EXTRACT_AND_RUN=1 DEPLOY_GTK_VERSION=3 ARCH=x86_64 LDAI_OUTPUT="$out" PATH="$tools:$PATH" \
  linuxdeploy-x86_64.AppImage --appdir AppDir -e "$pkg/quickbuds" -d "$pkg/quickbuds.desktop" \
  -i quickbuds.png "${libs[@]}" --plugin gtk --output appimage >/dev/null
