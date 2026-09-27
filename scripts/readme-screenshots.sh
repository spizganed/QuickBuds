#!/usr/bin/env bash
# Retakes the README screenshots into docs/screenshots/ over adb: every screen, then every widget.
# Needs: the phone on adb with the buds connected in QuickBuds and the phone in English, and
# Python with Pillow (`pip install pillow`) for cropping. It only OPENS screens: nothing is
# toggled, so the buds and the app settings are left as they were.
# Widgets: each placed QuickBuds widget on the LAST home screen page is cropped to its own file
# (widget-battery / -controls / -combined / -large); sizes that are not placed are skipped.
# Usage: scripts/readme-screenshots.sh [adb-serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1  # Git Bash would rewrite /sdcard/... into a Windows path
cd "$(dirname "$0")/.."

ADB_BIN=$(command -v adb || echo "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe")
SERIAL=${1:-}
# </dev/null: adb reads stdin, which would eat the widget list the loop at the end reads.
adb() { if [ -n "$SERIAL" ]; then "$ADB_BIN" -s "$SERIAL" "$@" </dev/null; else "$ADB_BIN" "$@" </dev/null; fi; }
OUT=docs/screenshots
mkdir -p "$OUT"

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb exec-out cat /sdcard/ui.xml; }

# Centre of the first node whose text or content-desc is exactly $1; scrolls down once if needed.
# A second argument "optional" skips (returns 1) instead of stopping the script.
tap() {
    local b try q
    q=$(printf '%s' "$1" | sed 's/&/\&amp;/g')  # the dump escapes & as &amp;
    for try in 1 2; do
        b=$(dump | tr '>' '\n' | grep -E "(text|content-desc)=\"$q\"" | head -1 \
            | grep -oE 'bounds="[^"]+"' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
        [ -n "$b" ] && break
        adb shell input swipe $((W / 2)) $((BOTTOM * 3 / 4)) $((W / 2)) $((BOTTOM / 4)) 300; sleep 1
    done
    if [ -z "$b" ]; then
        [ "${2:-}" = optional ] && { echo "skipped, not on screen: $1" >&2; return 1; }
        echo "not on screen: $1" >&2; exit 1
    fi
    set -- $b
    adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
    sleep 1.5
}
back() { adb shell input keyevent BACK; sleep 1; }

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

# NEW_TASK | CLEAR_TOP: back to the main screen even if a sub-screen was left open. Not -S: a force
# stop would drop the buds.
adb shell am start -W -f 0x14000000 -n com.spizganed.quickbuds/.ui.MainActivity >/dev/null
sleep 2
shot main
tap "Equalizer";        shot eq
tap "Edit preset";      shot eq-edit;  back; back
tap "Dual connection";  shot dual;     back
tap "Earbud settings";  shot earbuds
tap "Earbud gestures";  shot gestures; back
tap "Wear detection";   shot wear;     back
tap "Find my earbuds";  shot find;     back; back
tap "Settings";         shot settings
tap "Theme & colors";   shot theme
if tap "Edit preset" optional; then shot preset; back; fi
back
tap "Home layout";      shot home-layout;     back
tap "Widget settings";  shot widget-settings; back
tap "App update";       shot update;          back
tap "About";            shot about;           back; back

# Widgets: HOME, then swipe to the last page; one file per placed size, found by the ids only
# that size has (see widget/AncWidgetProvider.kt).
adb shell input keyevent HOME; sleep 1
for _ in 1 2 3 4 5 6 7 8; do adb shell input swipe $((W * 9 / 10)) $((BOTTOM / 2)) $((W / 10)) $((BOTTOM / 2)) 150; done
sleep 2
dump > "$OUT/.ui.xml"
python - "$OUT/.ui.xml" <<'PY' | while read -r name l t r b; do shot "$name" "$l $t $r $b"; done
import re, sys
import xml.etree.ElementTree as ET
ids = lambda n: {e.get("resource-id", "").split("/")[-1] for e in n.iter()}
for n in ET.parse(sys.argv[1]).iter("node"):
    if not n.get("resource-id", "").endswith(":id/w_root"):
        continue
    have = ids(n)
    name = ("widget-battery" if "w_bar" in have else "widget-large" if "w_mode_caption" in have
            else "widget-combined" if "w_panel_case" in have else "widget-controls" if "w_mode" in have else None)
    if name:
        l, t, r, b = map(int, re.findall(r"\d+", n.get("bounds")))
        print(name, l - 16, t - 16, r + 16, b + 16)
PY
rm -f "$OUT/.ui.xml"
