#!/usr/bin/env python3
# Generates the three widget page layouts (2x2 widget_pages, 4x2 widget_pages_m, 3x3 widget_pages_l) as one
# family. The 3x3 is the 2x2 layout, scaled up ([USER] 2026-09-27). Edit here, run `python3 scripts/widget-layouts.py` from the repo root, commit the XML with it.
# Also the mode list grids (widget_grid for 2x2 / 4x2, widget_grid_l for 3x3) and widget_disconnected.
import os
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "layout") + os.sep
IMG = 'android:scaleType="fitXY" android:importantForAccessibility="no"'
# Every clickable view gets it: pushed down while held (res/animator/widget_press.xml).
PRESS = 'android:stateListAnimator="@animator/widget_press"'
# Style: False = Classic (boxed panels), True = Nothing (tighter inset, no boxes, Ndot digits; widget_*_n layouts).
# The boxes themselves are painted at runtime (QuickBudsWidget.paint / panelColor).
N = False
def pad(): return "3dp" if N else "6dp"
def gap(): return "4dp" if N else "6dp"

def img(i, src="widget_panel"):
    return f'<ImageView android:id="@+id/{i}" android:layout_width="match_parent" android:layout_height="match_parent" android:src="@drawable/{src}" {IMG} />'

def text(i, sp, bold=False, extra=""):
    # Nothing: every text in NDot57All, Nothing OS's own family (/system/etc/ntfonts.xml); elsewhere it falls back to the default font.
    bold_font = 'android:fontFamily="NDot57All"' if N else 'android:fontFamily="sans-serif" android:textStyle="bold"'
    font = bold_font if bold or N else 'android:fontFamily="sans-serif-medium"'
    return (f'<TextView android:id="@+id/{i}" android:layout_width="wrap_content" android:layout_height="wrap_content" '
            f'android:textSize="{sp}sp" {font} android:maxLines="1" android:ellipsize="end" android:gravity="center" {extra}/>')

def swap(i, w, h, extra=""):
    return (f'<FrameLayout android:id="@+id/{i}" android:layout_width="{w}" android:layout_height="{h}" {PRESS} {extra}>\n'
            f'<ImageView android:id="@+id/{i}_icon" android:layout_width="16dp" android:layout_height="16dp" android:layout_gravity="center" android:src="@drawable/ic_swap_page" android:importantForAccessibility="no" />\n'
            '</FrameLayout>')

def panel(side, first, pct, label, fit=True):
    m = '' if first else f'android:layout_marginStart="{gap()}"'
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

def button(i, height, inner):
    return f'''<FrameLayout android:id="@+id/{i}" android:layout_width="match_parent" android:layout_height="{height}" android:layout_marginTop="{gap()}" {PRESS}>
{img(i + "_bg")}
{img(i + "_stroke", "widget_panel_stroke")}
{inner}
</FrameLayout>'''

def case_bar(height, icon_w, icon_h, sp):
    sw = swap("w_swap_b", "28dp", "match_parent", 'android:layout_marginEnd="-8dp"') + "\n"
    return f'''<FrameLayout android:id="@+id/w_bar" android:layout_width="match_parent" android:layout_height="{height}" android:layout_marginTop="{gap()}">
{img("w_bar_bg")}
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center_vertical" android:paddingStart="12dp" android:paddingEnd="12dp">
<ImageView android:id="@+id/w_case_icon" android:layout_width="{icon_w}dp" android:layout_height="{icon_h}dp" android:src="@drawable/ic_case" android:scaleType="fitCenter" android:importantForAccessibility="no" />
<ImageView android:id="@+id/w_case_bar" android:layout_width="0dp" android:layout_height="{"wrap_content" if N else "6dp"}" android:layout_weight="1" android:layout_marginStart="8dp" android:layout_marginEnd="8dp" {IMG.replace("fitXY", "fitCenter") if N else IMG} />
{text("w_pct_case", sp, True)}
{sw}</LinearLayout>
</FrameLayout>'''

def battery(size):
    # 2x2 and 3x3: two bud panels over the case bar, which ends in the swap button. 4x2 (wide): three
    # panels in a row, the swap button in the top-end corner as on the controls page.
    if size == "m":
        panels = "\n".join([panel("left", True, 18, 12, False), panel("case", False, 18, 12, False), panel("right", False, 18, 12, False)])
        return f'''<FrameLayout android:id="@+id/w_page0" android:layout_width="match_parent" android:layout_height="match_parent">
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" android:padding="{pad()}">
{panels}
</LinearLayout>
{swap("w_swap_b", "44dp", "44dp", 'android:layout_gravity="top|end"')}
</FrameLayout>'''
    if size == "s":
        panels = "\n".join([panel("left", True, 16, 11.5), panel("right", False, 16, 11.5)])
        # Nothing: the narrower Ndot digits leave room, so the case icon and percentage match the buds'.
        bottom = case_bar("42dp", 35, 28, 16) if N else case_bar("42dp", 27, 22, 14)
    else:
        panels = "\n".join([panel("left", True, 22, 15), panel("right", False, 22, 15)])
        bottom = case_bar("58dp", 48, 39, 22) if N else case_bar("58dp", 38, 31, 19)
    return f'''<LinearLayout android:id="@+id/w_page0" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:padding="{pad()}">
<LinearLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="horizontal">
{panels}
</LinearLayout>
{bottom}
</LinearLayout>'''

def mode_content(size, k):
    """The mode button's icon and texts, copy [k] (0 or 1) of the two the ticker flips between."""
    hint = f'<ImageView android:id="@+id/w_mode_hint{k}" android:layout_width="16dp" android:layout_height="16dp" android:layout_marginStart="6dp" android:importantForAccessibility="no" />'
    if size == "m":
        # Horizontal: icon, then caption over name + hint.
        return f'''<LinearLayout android:id="@+id/w_mode_content{k}" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center" android:padding="8dp">
<ImageView android:id="@+id/w_mode_icon{k}" android:layout_width="44dp" android:layout_height="44dp" android:importantForAccessibility="no" />
<LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content" android:orientation="vertical" android:layout_marginStart="14dp">
{text(f"w_mode_caption{k}", 12)}
<LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content" android:orientation="horizontal" android:gravity="center_vertical">
{text(f"w_mode_name{k}", 18, True)}
{hint}
</LinearLayout>
</LinearLayout>
</LinearLayout>'''
    icon, name = (40, 14.5) if size == "s" else (60, 20)
    return f'''<LinearLayout android:id="@+id/w_mode_content{k}" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:gravity="center" android:padding="8dp">
<ImageView android:id="@+id/w_mode_icon{k}" android:layout_width="{icon}dp" android:layout_height="{icon}dp" android:importantForAccessibility="no" />
<LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content" android:orientation="horizontal" android:gravity="center_vertical" android:layout_marginTop="6dp">
{text(f"w_mode_name{k}", name, True)}
{hint}
</LinearLayout>
</LinearLayout>'''

def mode_button(size):
    # Two copies of the button: a mode change fills the hidden one and flips to it, so the fill
    # cross-fades (w_mode_fills) while the icon and name tick up (w_mode_flip).
    fills = "\n".join(f'''<FrameLayout android:id="@+id/w_mode_fill{k}" android:layout_width="match_parent" android:layout_height="match_parent">
{img(f"w_mode_bg{k}")}
{img(f"w_mode_stroke{k}", "widget_panel_stroke")}
</FrameLayout>''' for k in (0, 1))
    return f'''<FrameLayout android:id="@+id/w_mode" android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" {PRESS}>
<ViewFlipper android:id="@+id/w_mode_fills" android:layout_width="match_parent" android:layout_height="match_parent" android:inAnimation="@anim/widget_enter_left" android:outAnimation="@anim/widget_exit_right" android:animateFirstView="true">
{fills}
</ViewFlipper>
<ViewFlipper android:id="@+id/w_mode_flip" android:layout_width="match_parent" android:layout_height="match_parent" android:inAnimation="@anim/widget_enter_left" android:outAnimation="@anim/widget_exit_right" android:animateFirstView="true">
{mode_content(size, 0)}
{mode_content(size, 1)}
</ViewFlipper>
</FrameLayout>'''

def controls(size):
    ll_h, ll_icon, ll_sp = {"s": ("48dp", 18, 14), "m": ("50dp", 20, 15), "l": ("66dp", 25, 19)}[size]
    if N: ll_icon = 22 if size == "s" else 24  # the hand-drawn dot bolt's sizes (llIconDp in the renderer)
    ll = f'''<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center" android:padding="8dp">
<ImageView android:id="@+id/w_ll_icon" android:layout_width="{ll_icon}dp" android:layout_height="{ll_icon}dp" android:importantForAccessibility="no" />
{text("w_ll_label", ll_sp, extra='android:layout_marginStart="8dp" ')}
</LinearLayout>'''
    corner = "36dp" if size == "s" else "44dp"
    return f'''<FrameLayout android:id="@+id/w_page1" android:layout_width="match_parent" android:layout_height="match_parent">
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:padding="{pad()}">
{mode_button(size)}
{button("w_ll", ll_h, ll)}
</LinearLayout>
{swap("w_swap", corner, corner, 'android:layout_gravity="top|end"')}
</FrameLayout>'''

def grid(icon, sp):
    def cell(k, first):
        m = '' if first else f' android:layout_marginStart="{gap()}"'
        return f'''<FrameLayout android:id="@+id/w_cell{k}" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1"{m} {PRESS}>
{img(f"w_cell{k}_bg")}
{img(f"w_cell{k}_stroke", "widget_panel_stroke")}
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:gravity="center" android:padding="4dp">
<ImageView android:id="@+id/w_cell{k}_icon" android:layout_width="{icon}dp" android:layout_height="{icon}dp" android:importantForAccessibility="no" />
{text(f"w_cell{k}_label", sp, extra='android:layout_marginTop="4dp" ')}
</LinearLayout>
</FrameLayout>'''
    row = lambda ks: "\n".join(cell(k, i == 0) for i, k in enumerate(ks))
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Mode list grid, included by the widget page layouts' list page (design/widgets/WIDGETS.md 3.2-3.4). Generated by
     scripts/widget-layouts.py: colours, icons and texts are set at runtime by widget/AncWidgetProvider.kt. -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/w_grid" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical">
<LinearLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="horizontal">
{row((0, 1, 2))}
</LinearLayout>
<LinearLayout android:id="@+id/w_grid_row1" android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="horizontal" android:layout_marginTop="{gap()}">
{row((3, 4, 5))}
</LinearLayout>
</LinearLayout>
'''

def disconnected():
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Every widget while disconnected: only the main screen's Connect chip ([USER] 2026-09-27) (design/widgets/WIDGETS.md 4). Generated
     by scripts/widget-layouts.py: colours, icons and texts are set at runtime by widget/AncWidgetProvider.kt; a view added here
     needs its line in the renderer. -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/w_root" android:layout_width="match_parent" android:layout_height="match_parent">
<ImageView android:id="@+id/w_bg" android:layout_width="match_parent" android:layout_height="match_parent" android:src="@drawable/widget_bg" {IMG} />
<FrameLayout android:id="@+id/w_conn" android:layout_width="wrap_content" android:layout_height="44dp" android:layout_gravity="center" {PRESS}>
<ImageView android:id="@+id/w_conn_bg" android:layout_width="match_parent" android:layout_height="match_parent" android:src="@drawable/widget_panel" {IMG} />
<ImageView android:id="@+id/w_conn_stroke" android:layout_width="match_parent" android:layout_height="match_parent" android:src="@drawable/widget_panel_stroke" {IMG} />
<LinearLayout android:layout_width="wrap_content" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center_vertical" android:paddingStart="16dp" android:paddingEnd="16dp">
<ImageView android:id="@+id/w_conn_dot" android:layout_width="10dp" android:layout_height="10dp" android:src="@drawable/ic_status_dot_empty" android:scaleType="fitCenter" android:importantForAccessibility="no" />
{text("w_conn_text", 14, extra='android:layout_marginStart="8dp" ')}
</LinearLayout>
</FrameLayout>
</FrameLayout>
'''

TITLE = {"s": "2x2", "m": "4x2", "l": "3x3"}
for N in (False, True):
    sfx = "_n" if N else ""
    open(RES + f"widget_grid{sfx}.xml", "w").write(grid(26, 12.5))
    open(RES + f"widget_grid_l{sfx}.xml", "w").write(grid(38, 15))
    open(RES + f"widget_disconnected{sfx}.xml", "w").write(disconnected())
    for size, name in (("s", "widget_pages"), ("m", "widget_pages_m"), ("l", "widget_pages_l")):
        out = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {TITLE[size]} widget (design/widgets/WIDGETS.md 3). Generated as one family with the other sizes. Outer ViewFlipper
     w_pages: w_content, then the mode list w_page2. Inside w_content, one ViewFlipper per page, each an empty
     FrameLayout then the page, so each page has its own direction: w_slide0 (the battery page w_page0, in and out on
     the left) and w_slide1 (the controls page w_page1, in and out on the right). Colours, icons and texts are set at runtime by
     widget/AncWidgetProvider.kt; a view added here needs its line in the renderer. -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/w_root" android:layout_width="match_parent" android:layout_height="match_parent">
{img("w_bg", "widget_bg")}
<ViewFlipper android:id="@+id/w_pages" android:layout_width="match_parent" android:layout_height="match_parent" android:inAnimation="@anim/widget_enter_left" android:outAnimation="@anim/widget_exit_right" android:animateFirstView="true">
<FrameLayout android:id="@+id/w_content" android:layout_width="match_parent" android:layout_height="match_parent">
<ViewFlipper android:id="@+id/w_slide0" android:layout_width="match_parent" android:layout_height="match_parent" android:inAnimation="@anim/widget_enter_left" android:outAnimation="@anim/widget_exit_left" android:animateFirstView="true">
<FrameLayout android:layout_width="match_parent" android:layout_height="match_parent" />
{battery(size)}
</ViewFlipper>
<ViewFlipper android:id="@+id/w_slide1" android:layout_width="match_parent" android:layout_height="match_parent" android:inAnimation="@anim/widget_enter_right" android:outAnimation="@anim/widget_exit_right" android:animateFirstView="true">
<FrameLayout android:layout_width="match_parent" android:layout_height="match_parent" />
{controls(size)}
</ViewFlipper>
</FrameLayout>
<FrameLayout android:id="@+id/w_page2" android:layout_width="match_parent" android:layout_height="match_parent" android:padding="{pad()}">
<include layout="@layout/{"widget_grid_l" if size == "l" else "widget_grid"}{sfx}" />
</FrameLayout>
</ViewFlipper>
</FrameLayout>
'''
        out = "\n".join(l.replace("  />", " />").replace(" >", ">") if l.startswith("<") else l for l in out.split("\n"))
        open(RES + name + sfx + ".xml", "w").write(out)
print("ok")
