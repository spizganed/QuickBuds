#!/usr/bin/env bash
# Take one screenshot of the egui desktop port on a private X display.
# Usage: scripts/egui-shot.sh <page> <out.png>
# The display is always :101. The script never uses :0.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
bin="$root/desktop-egui/target/debug/quickbuds-egui"
display=":101"
page_arg="${1:-}"
out="${2:-}"

die() { echo "egui-shot: $*" >&2; exit 1; }

[[ -n "$page_arg" && -n "$out" ]] || die "usage: scripts/egui-shot.sh <page> <out.png>"
[[ -x "$bin" ]] || die "binary not built: $bin"
mkdir -p "$(dirname "$out")"
out="$(cd "$(dirname "$out")" && pwd)/$(basename "$out")"

# Map a display name or a page name to the key that page_from_key() accepts.
key="$(printf '%s' "$page_arg" | tr '[:upper:]' '[:lower:]' | tr ' _' '--')"
case "$key" in
  home) key=overview ;;
  guide|setup-guide) key=setup ;;
  home-layout) key=layout ;;
  edit-preset|preset-edit) key=preset ;;
esac
case "$key" in
  overview|settings|appearance|issues|about|language|setup|layout|preset|test|feedback|update) ;;
  earbuds|eq|gestures|hearing|dual|models|dev)
    die "page '$page_arg' has no QB_PAGE key; page_from_key() needs a Rust change" ;;
  *) die "unknown page '$page_arg'" ;;
esac

# A busy display is not ours to kill.
if xdpyinfo -display "$display" >/dev/null 2>&1; then
  die "display $display is already in use"
fi

tmp="$(mktemp -d /tmp/opencode/egui-shot.XXXXXX)"
app_pid=""
xvfb_pid=""
wm_pid=""

cleanup() {
  local pid
  for pid in "$app_pid" "$wm_pid" "$xvfb_pid"; do
    [[ -n "$pid" ]] || continue
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  done
  for pid in "$app_pid" "$wm_pid" "$xvfb_pid"; do
    [[ -n "$pid" ]] || continue
    for _ in $(seq 1 20); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.1
    done
    kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
  done
  rm -rf "$tmp"
}
trap cleanup EXIT INT TERM

# The window must have its own X server.
setsid Xvfb "$display" -screen 0 420x960x24 -nolisten tcp >"$tmp/xvfb.log" 2>&1 &
xvfb_pid=$!
for _ in $(seq 1 50); do
  xdpyinfo -display "$display" >/dev/null 2>&1 && break
  sleep 0.1
done
xdpyinfo -display "$display" >/dev/null 2>&1 || die "Xvfb did not start on $display"

# Software GL: a headless Xvfb must not pick the NVIDIA driver, or the window never maps.
# QB_DEMO gives a fixed snapshot, so no earbuds are touched.
setsid env -u WAYLAND_DISPLAY DISPLAY="$display" WINIT_UNIX_BACKEND=x11 \
  LIBGL_ALWAYS_SOFTWARE=1 XDG_CONFIG_HOME="$tmp/cfg" QB_DEMO=1 QB_PAGE="$key" \
  "$bin" >"$tmp/app.log" 2>&1 &
app_pid=$!

find_window() {
  if command -v xdotool >/dev/null 2>&1; then
    DISPLAY="$display" xdotool search --name '^QuickBuds$' 2>/dev/null | head -1
  elif command -v xwininfo >/dev/null 2>&1; then
    DISPLAY="$display" xwininfo -root -tree 2>/dev/null | awk '/"QuickBuds"/ {print $1; exit}'
  fi
}

wid=""
for _ in $(seq 1 150); do
  wid="$(find_window || true)"
  [[ -n "$wid" ]] && break
  kill -0 "$app_pid" 2>/dev/null || break
  sleep 0.1
done

if [[ -z "$wid" ]]; then
  # Some X servers need a window manager before the client maps.
  for wm in openbox fluxbox; do
    command -v "$wm" >/dev/null 2>&1 || continue
    setsid "$wm" >"$tmp/wm.log" 2>&1 &
    wm_pid=$!
    sleep 1
    for _ in $(seq 1 30); do
      wid="$(find_window || true)"
      [[ -n "$wid" ]] && break
      sleep 0.1
    done
    break
  done
fi

if [[ -z "$wid" ]]; then
  echo "egui-shot: window did not map; no window manager is installed" >&2
  echo "egui-shot: capture the root window after a fixed wait" >&2
  sleep 5
elif [[ -n "$wm_pid" ]]; then
  echo "egui-shot: window mapped with a window manager" >&2
  sleep 2
else
  sleep 2
fi

if command -v import >/dev/null 2>&1; then
  DISPLAY="$display" import -window root "$out"
elif command -v xwd >/dev/null 2>&1 && command -v convert >/dev/null 2>&1; then
  DISPLAY="$display" xwd -root -silent | convert xwd:- "$out"
else
  die "need ImageMagick import, or xwd and convert"
fi

[[ -s "$out" ]] || die "screenshot is empty: $out"
echo "egui-shot: page=$key window=${wid:-none} out=$out"
