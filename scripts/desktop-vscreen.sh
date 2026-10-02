#!/usr/bin/env bash
# Drives the desktop app on an invisible X screen (Xvfb :99), so tests never touch the real desktop.
#   desktop-vscreen.sh start [config-dir]   start Xvfb and the debug build (config-dir = XDG_CONFIG_HOME)
#   desktop-vscreen.sh click X Y            click at window pixels (physical, as in a shot)
#   desktop-vscreen.sh shot FILE.png        screenshot of the screen
#   desktop-vscreen.sh stop                 stop the app and Xvfb
set -euo pipefail
export DISPLAY=:99
unset WAYLAND_DISPLAY
root="$(cd "$(dirname "$0")/.." && pwd)"

case "${1:-}" in
  start)
    pgrep -f "Xvfb :99" >/dev/null || { Xvfb :99 -screen 0 1400x900x24 -nolisten tcp & sleep 1; }
    pkill -f "target/debug/quickbuds" || true
    cfg="${2:-${XDG_CONFIG_HOME:-$HOME/.config}}"
    XDG_CONFIG_HOME="$cfg" "$root/desktop/target/debug/quickbuds" >/dev/null 2>&1 &
    sleep 4
    ;;
  click)
    w=$(xdotool search --name '^QuickBuds$' | head -1)
    xdotool mousemove --window "$w" "$2" "$3" click 1
    sleep 0.5
    ;;
  shot) import -window root "$2" ;;
  stop) pkill -f "target/debug/quickbuds" || true; pkill -f "Xvfb :99" || true ;;
  *) sed -n 2,7p "$0"; exit 1 ;;
esac
