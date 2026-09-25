#!/usr/bin/env bash
# Retakes the README screenshots into docs/screenshots/ over adb.
# Needs: the phone on adb with the buds connected in QuickBuds, and Python with Pillow
# (`pip install pillow`) for cropping. For widget.png, the QuickBuds widget has to be on the
# home screen page that HOME opens; only the widget's own bounds are kept.
# Usage: scripts/readme-screenshots.sh [adb-serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1  # Git Bash would rewrite /sdcard/... into a Windows path
cd "$(dirname "$0")/.."

ADB_BIN=$(command -v adb || echo "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe")
SERIAL=${1:-}
adb() { if [ -n "$SERIAL" ]; then "$ADB_BIN" -s "$SERIAL" "$@"; else "$ADB_BIN" "$@"; fi; }
OUT=docs/screenshots
mkdir -p "$OUT"

# Centre of the first node whose text or content-desc is exactly $1.
tap() {
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null
    local b
    b=$(adb exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep -E "(text|content-desc)=\"$1\"" | head -1 \
        | grep -oE 'bounds="[^"]+"' | grep -oE '[0-9]+' | tr '\n' ' ')
    [ -n "$b" ] || { echo "not on screen: $1" >&2; exit 1; }
    set -- $b
    adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
    sleep 1.5
}

# Screenshot cropped to the box "l t r b". Without a box: the status bar is painted over in the
# page's own background (cutting it would leave titles flush with the edge) and the nav bar is cut.
shot() {
    local box=${2:-"0 0 $W $BOTTOM"}
    adb exec-out screencap -p > "$OUT/$1.png"
    python -c "
import sys
from PIL import Image
f, top, box = sys.argv[1], int(sys.argv[2]), tuple(map(int, sys.argv[3:]))
im = Image.open(f).convert('RGB')
# ponytail: icons sit in the top 3/4 of the inset and the main header pills reach into the rest;
# measure the icons' bottom edge if another device clips something.
if top: im.paste(im.getpixel((0, top)), (0, 0, im.width, top * 3 // 4))
im.crop(box).save(f, optimize=True)" "$OUT/$1.png" "$([ -z "${2:-}" ] && echo "$TOP" || echo 0)" $box
    echo "$OUT/$1.png"
}

# Bar heights from the window insets, so the crop follows the device.
insets=$(adb shell dumpsys window)
TOP=$(grep -m1 -oE 'type=statusBars frame=\[0,0\]\[[0-9]+,[0-9]+\]' <<<"$insets" | grep -oE '[0-9]+' | tail -1)
BOTTOM=$(grep -m1 -oE 'type=navigationBars frame=\[0,[0-9]+\]' <<<"$insets" | grep -oE '[0-9]+' | tail -1)
W=$(adb shell wm size | grep -oE '[0-9]+x' | tr -d x | tail -1)

# mobile-mcp's device server holds UiAutomation, which makes `uiautomator dump` die with
# "already registered". mobile-mcp starts it again on its next call.
adb shell pkill -f com.mobilenext.mobilecli || true

adb shell am start -W -n com.spizganed.quickbuds/.ui.MainActivity >/dev/null
sleep 2
shot main
tap "Equalizer";     shot eq
tap "Edit preset";   shot eq-curve
adb shell input keyevent BACK; sleep 1
adb shell input keyevent BACK; sleep 1
tap "Earbud settings"; tap "Earbud gestures"; shot controls
adb shell input keyevent BACK; sleep 1
adb shell input keyevent BACK; sleep 1

# Widget: union of every node that belongs to our RemoteViews on the home screen.
adb shell input keyevent HOME; sleep 2
adb shell uiautomator dump /sdcard/ui.xml >/dev/null
box=$(adb exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep 'resource-id="com.spizganed.quickbuds:id/' \
    | grep -oE 'bounds="[^"]+"' | grep -oE '[0-9]+' | paste - - - - \
    | awk 'NR==1{l=$1;t=$2;r=$3;b=$4} {if($1<l)l=$1; if($2<t)t=$2; if($3>r)r=$3; if($4>b)b=$4} END{if(NR)print l-16,t-16,r+16,b+16}' || true)
if [ -n "$box" ]; then shot widget "$box"; else echo "widget not on the home screen, widget.png skipped" >&2; fi
