#!/bin/sh
# Installs QuickBuds on Linux for the current user: the AppImage, a `quickbuds` command and a menu entry.
#   curl -fsSL https://raw.githubusercontent.com/spizganed/QuickBuds/main/scripts/install-linux.sh | sh
# Install a local file instead of the release: QB_APPIMAGE=/path/QuickBuds-linux-x64.AppImage sh install-linux.sh
# Remove it again with: sh install-linux.sh uninstall
# No root needed. Needs curl and BlueZ (bluetoothctl). Updates come from the app itself.
set -eu

app="$HOME/.local/share/quickbuds"
bin="$HOME/.local/bin"
menu="$HOME/.local/share/applications"
icons="$HOME/.local/share/icons/hicolor/256x256/apps"
url="https://github.com/spizganed/QuickBuds/releases/latest/download/QuickBuds-linux-x64.AppImage"
sums_url="https://github.com/spizganed/QuickBuds/releases/latest/download/SHA256SUMS"

if [ "${1:-}" = uninstall ]; then
  rm -rf "$app" "$bin/quickbuds" "$menu/quickbuds.desktop" "$icons/quickbuds.png"
  echo "QuickBuds removed. Its settings stay in ~/.config/quickbuds."
  exit 0
fi

[ "$(uname -s)" = Linux ] && [ "$(uname -m)" = x86_64 ] || { echo "This installer needs Linux on x86_64." >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "Install curl first." >&2; exit 1; }
command -v bluetoothctl >/dev/null 2>&1 || echo "Warning: BlueZ (bluetoothctl) is missing. Install it, then pair your earbuds." >&2

mkdir -p "$app" "$bin" "$menu" "$icons"
tmp=$(mktemp)
sums=$(mktemp)
trap 'rm -f "$tmp" "$sums"' EXIT
if [ -n "${QB_APPIMAGE:-}" ]; then
  cp "$QB_APPIMAGE" "$tmp"
else
  echo "Downloading QuickBuds..."
  curl -fL --progress-bar -o "$tmp" "$url"
  curl -fsSL -o "$sums" "$sums_url"
  name=QuickBuds-linux-x64.AppImage
  want=$(awk -v n="$name" '$2 == n { print $1 }' "$sums")
  [ -n "$want" ] || { echo "SHA256SUMS has no line for $name." >&2; exit 1; }
  got=$(sha256sum "$tmp" | awk '{ print $1 }')
  [ "$got" = "$want" ] || { echo "The download does not match SHA256SUMS. Installed nothing." >&2; exit 1; }
fi
chmod +x "$tmp"
mv "$tmp" "$app/QuickBuds.AppImage"

# The icon comes out of the AppImage, which needs no FUSE for this.
work=$(mktemp -d)
( cd "$work" && APPIMAGE_EXTRACT_AND_RUN=1 "$app/QuickBuds.AppImage" --appimage-extract >/dev/null 2>&1 ) || true
icon=$(find -L "$work" -path '*256x256/apps/quickbuds.png' -type f 2>/dev/null | head -1)
if [ -n "$icon" ]; then cp "$icon" "$icons/quickbuds.png"; else echo "No icon found; the menu entry has no icon." >&2; fi
rm -rf "$work"

cat > "$bin/quickbuds" <<EOF
#!/bin/sh
# Starts QuickBuds. Without FUSE, the AppImage runs extracted.
if [ -e /dev/fuse ] && { command -v fusermount3 || command -v fusermount; } >/dev/null 2>&1; then
  exec "$app/QuickBuds.AppImage" "\$@"
fi
APPIMAGE_EXTRACT_AND_RUN=1 exec "$app/QuickBuds.AppImage" "\$@"
EOF
chmod +x "$bin/quickbuds"

cat > "$menu/quickbuds.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=QuickBuds
Comment=Control OnePlus, OPPO and realme earbuds
Exec=$bin/quickbuds
Icon=quickbuds
Categories=AudioVideo;Audio;
Terminal=false
EOF

echo "QuickBuds is installed. Start it from the menu, or run: quickbuds"
case ":$PATH:" in *":$bin:"*) ;; *) echo "Add $bin to your PATH to use the quickbuds command." ;; esac
