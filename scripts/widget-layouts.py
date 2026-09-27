#!/usr/bin/env python3
# Generates the three widget page layouts (2x2 widget_pages, 3x2 widget_pages_m, 3x3 widget_pages_l) as one
# family. Edit here, run `python3 scripts/widget-layouts.py` from the repo root, commit the XML with it.
# widget_grid.xml and widget_disconnected.xml are hand-kept.
import os
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "layout") + os.sep
IMG = 'android:scaleType="fitXY" android:importantForAccessibility="no"'

def img(i, src="widget_panel"):
    return f'<ImageView android:id="@+id/{i}" android:layout_width="match_parent" android:layout_height="match_parent" android:src="@drawable/{src}" {IMG} />'

def text(i, sp, bold=False, extra=""):
    font = 'android:fontFamily="sans-serif" android:textStyle="bold"' if bold else 'android:fontFamily="sans-serif-medium"'
    return (f'<TextView android:id="@+id/{i}" android:layout_width="wrap_content" android:layout_height="wrap_content" '
            f'android:textSize="{sp}sp" {font} android:maxLines="1" android:ellipsize="end" android:gravity="center" {extra}/>')

def swap(i, w, h, extra=""):
    return (f'<FrameLayout android:id="@+id/{i}" android:layout_width="{w}" android:layout_height="{h}" {extra}>\n'
            f'<ImageView android:id="@+id/{i}_icon" android:layout_width="16dp" android:layout_height="16dp" android:layout_gravity="center" android:src="@drawable/ic_swap_page" android:importantForAccessibility="no" />\n'
            '</FrameLayout>')

def panel(side, first, pct, label, fit=True):
    m = '' if first else 'android:layout_marginStart="6dp"'
    # 2x2: the ring takes the panel's free height. 3x2 / 3x3: the renderer sizes the ring from the
    # widget's real size, and ring + texts sit centred as one group.
    ring = ('android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:scaleType="centerInside"' if fit
            else 'android:layout_width="wrap_content" android:layout_height="wrap_content" android:scaleType="center"')
    return f'''<FrameLayout android:id="@+id/w_panel_{side}" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" {m}>
{img(f"w_panel_{side}_bg")}
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:gravity="center" android:padding="4dp">
<ImageView android:id="@+id/w_ring_{side}" {ring} android:importantForAccessibility="no" />
{text(f"w_pct_{side}", pct, True, 'android:layout_marginTop="2dp" ')}
{text(f"w_label_{side}", label)}
</LinearLayout>
</FrameLayout>'''

def button(i, height, weight, inner, top=True):
    h = f'android:layout_height="0dp" android:layout_weight="1"' if weight else f'android:layout_height="{height}"'
    m = 'android:layout_marginTop="6dp"' if top else ''
    return f'''<FrameLayout android:id="@+id/{i}" android:layout_width="match_parent" {h} {m}>
{img(i + "_bg")}
{img(i + "_stroke", "widget_panel_stroke")}
{inner}
</FrameLayout>'''

def name_strip(height, sp):
    return f'''<LinearLayout android:layout_width="match_parent" android:layout_height="{height}" android:orientation="horizontal" android:gravity="center_vertical" android:paddingStart="10dp">
<TextView android:id="@+id/w_name" android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1" android:textSize="{sp}sp" android:fontFamily="sans-serif-medium" android:maxLines="1" android:ellipsize="end" />
{swap("w_swap_b", "40dp", "match_parent")}
</LinearLayout>'''

def case_bar(height, icon_w, icon_h, sp, with_swap):
    sw = swap("w_swap_b", "28dp", "match_parent", 'android:layout_marginEnd="-8dp"') + "\n" if with_swap else ""
    return f'''<FrameLayout android:id="@+id/w_bar" android:layout_width="match_parent" android:layout_height="{height}" android:layout_marginTop="6dp">
{img("w_bar_bg")}
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center_vertical" android:paddingStart="12dp" android:paddingEnd="12dp">
<ImageView android:id="@+id/w_case_icon" android:layout_width="{icon_w}dp" android:layout_height="{icon_h}dp" android:src="@drawable/ic_case" android:scaleType="fitCenter" android:importantForAccessibility="no" />
<ImageView android:id="@+id/w_case_bar" android:layout_width="0dp" android:layout_height="6dp" android:layout_weight="1" android:layout_marginStart="8dp" android:layout_marginEnd="8dp" {IMG} />
{text("w_pct_case", sp, True)}
{sw}</LinearLayout>
</FrameLayout>'''

def battery(size):
    # 2x2 and 3x3: two bud panels over the case bar. 3x2 (wide and short): three panels in a row.
    if size == "m":
        panels = "\n".join([panel("left", True, 18, 12, False), panel("case", False, 18, 12, False), panel("right", False, 18, 12, False)])
        bottom = name_strip("34dp", 13)
    elif size == "s":
        panels = "\n".join([panel("left", True, 16, 11.5), panel("right", False, 16, 11.5)])
        bottom = case_bar("42dp", 27, 22, 14, True)
    else:
        panels = "\n".join([panel("left", True, 22, 13, False), panel("right", False, 22, 13, False)])
        bottom = case_bar("50dp", 33, 27, 17, False) + "\n" + name_strip("40dp", 14)
    return f'''<LinearLayout android:id="@+id/w_page0" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:padding="6dp">
<LinearLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="horizontal">
{panels}
</LinearLayout>
{bottom}
</LinearLayout>'''

def controls(size):
    hint = '<ImageView android:id="@+id/w_mode_hint" android:layout_width="16dp" android:layout_height="16dp" android:layout_marginStart="6dp" android:importantForAccessibility="no" />'
    if size == "m":
        # Horizontal: icon, then caption over name + hint.
        mode = f'''<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center" android:padding="8dp">
<ImageView android:id="@+id/w_mode_icon" android:layout_width="44dp" android:layout_height="44dp" android:importantForAccessibility="no" />
<LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content" android:orientation="vertical" android:layout_marginStart="14dp">
{text("w_mode_caption", 12)}
<LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content" android:orientation="horizontal" android:gravity="center_vertical">
{text("w_mode_name", 18, True)}
{hint}
</LinearLayout>
</LinearLayout>
</LinearLayout>'''
        ll_h, ll_icon, ll_sp = "50dp", 20, 15
    else:
        icon, name, cap = (40, 14.5, None) if size == "s" else (60, 21, 13)
        caption = "" if cap is None else text("w_mode_caption", cap, extra='android:layout_marginTop="8dp" ') + "\n"
        top = "6dp" if cap is None else "2dp"
        mode = f'''<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:gravity="center" android:padding="8dp">
<ImageView android:id="@+id/w_mode_icon" android:layout_width="{icon}dp" android:layout_height="{icon}dp" android:importantForAccessibility="no" />
{caption}<LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content" android:orientation="horizontal" android:gravity="center_vertical" android:layout_marginTop="{top}">
{text("w_mode_name", name, True)}
{hint}
</LinearLayout>
</LinearLayout>'''
        ll_h, ll_icon, ll_sp = ("48dp", 18, 14) if size == "s" else ("58dp", 22, 16)
    ll = f'''<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center" android:padding="8dp">
<ImageView android:id="@+id/w_ll_icon" android:layout_width="{ll_icon}dp" android:layout_height="{ll_icon}dp" android:importantForAccessibility="no" />
{text("w_ll_label", ll_sp, extra='android:layout_marginStart="8dp" ')}
</LinearLayout>'''
    corner = "36dp" if size == "s" else "44dp"
    return f'''<FrameLayout android:id="@+id/w_page1" android:layout_width="match_parent" android:layout_height="match_parent">
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:padding="6dp">
{button("w_mode", None, True, mode, top=False)}
{button("w_ll", ll_h, False, ll)}
</LinearLayout>
{swap("w_swap", corner, corner, 'android:layout_gravity="top|end"')}
</FrameLayout>'''

TITLE = {"s": "2x2", "m": "3x2", "l": "3x3"}
for size, name in (("s", "widget_pages"), ("m", "widget_pages_m"), ("l", "widget_pages_l")):
    out = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {TITLE[size]} widget (design/widgets/WIDGETS.md 3). Generated as one family with the other sizes. Outer ViewFlipper
     w_pages (cross-fade): w_content, then the mode list w_page2. Inside w_content, ViewFlipper w_slide (slide): the
     battery page w_page0 and the controls page w_page1. Colours, icons and texts are set at runtime by
     widget/AncWidgetProvider.kt; a view added here needs its line in the renderer. -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/w_root" android:layout_width="match_parent" android:layout_height="match_parent">
{img("w_bg", "widget_bg")}
<ViewFlipper android:id="@+id/w_pages" android:layout_width="match_parent" android:layout_height="match_parent" android:inAnimation="@anim/widget_fade_in" android:outAnimation="@anim/widget_fade_out" android:animateFirstView="false">
<FrameLayout android:id="@+id/w_content" android:layout_width="match_parent" android:layout_height="match_parent">
<ViewFlipper android:id="@+id/w_slide" android:layout_width="match_parent" android:layout_height="match_parent" android:inAnimation="@anim/widget_slide_in" android:outAnimation="@anim/widget_slide_out" android:animateFirstView="false">
{battery(size)}
{controls(size)}
</ViewFlipper>
</FrameLayout>
<FrameLayout android:id="@+id/w_page2" android:layout_width="match_parent" android:layout_height="match_parent" android:padding="6dp">
<include layout="@layout/widget_grid" />
</FrameLayout>
</ViewFlipper>
</FrameLayout>
'''
    out = "\n".join(l.replace("  />", " />").replace(" >", ">") if l.startswith("<") else l for l in out.split("\n"))
    open(RES + name + ".xml", "w").write(out)
print("ok")
