#!/usr/bin/env python3
# Generates the three widget page layouts (2x2 widget_pages, 4x2 widget_pages_m, 3x3 widget_pages_l) as one
# family. The 3x3 is the 2x2 layout, scaled up ([USER] 2026-09-27). Edit here, run `python3 scripts/widget-layouts.py` from the repo root, commit the XML with it.
# Also the level picker grids (widget_grid for 2x2 / 4x2, widget_grid_l for 3x3) and widget_disconnected.
# Classic and Nothing share one structure ([USER] 2026-09-28); they differ in fonts, spacing (GEO) and, at runtime, boxes.
import os, re
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "layout") + os.sep
IMG = 'android:scaleType="fitXY" android:importantForAccessibility="no"'
# Every clickable view gets it: pushed down while held (res/animator/widget_press.xml).
PRESS = 'android:stateListAnimator="@animator/widget_press"'
# Style: False = Classic (boxed panels), True = Nothing (tighter inset, no boxes, Ndot digits; widget_*_n layouts).
# The boxes themselves are painted at runtime (QuickBudsWidget.paint / panelColor).
N = False
# The 2x2 battery page's spacing in dp, per style; QuickBudsWidget.Geo mirrors it, keep them equal. side / vert: page
# padding (4dp more above and below, [USER] 2026-09-28: fill the gaps a little, not at the sides); gap between boxes;
# ring panel paddings top, outer (widget side), inner (the other ring's side), bottom; row: the case row's padding.
# Nothing: no boxes, rings 6dp from the sides and apart. Classic: boxed panels, each ring centred in its box.
# pm: the percentage's top margin. Classic is tighter above and below, so its case row gets room next to the wear text
# ([USER] 2026-09-28: make the case row bigger on Classic too).
GEO = {True: dict(side=3, vert=7, gap=4, top=3, outer=3, inner=1, bottom=4, row=3, pm=5),
       False: dict(side=6, vert=8, gap=6, top=4, outer=7, inner=7, bottom=3, row=4, pm=2)}
def g(k): return GEO[N][k]
def pad(): return f"{g('side')}dp"
def gap(): return f"{g('gap')}dp"
def page_pad(): return f'android:paddingTop="{g("vert")}dp" android:paddingBottom="{g("vert")}dp" android:paddingStart="{pad()}" android:paddingEnd="{pad()}"'

def img(i, src="widget_panel"):
    return f'<ImageView android:id="@+id/{i}" android:layout_width="match_parent" android:layout_height="match_parent" android:src="@drawable/{src}" {IMG} />'

def text(i, sp, bold=False, extra=""):
    # Dot matrix: an ImageView the renderer fills with the text drawn in Doto (launchers ignore @font/ in widget XML);
    # its height is Doto's line height, 1.2 x the text size, and the bitmap scales to it.
    if N:
        return (f'<ImageView android:id="@+id/{i}" android:layout_width="wrap_content" android:layout_height="{round(sp * 1.2, 2):g}dp" '
                f'android:adjustViewBounds="true" android:scaleType="fitCenter" {extra}/>')
    bold_font = 'android:fontFamily="@font/doto" android:textStyle="bold"' if N else 'android:fontFamily="sans-serif" android:textStyle="bold"'
    font = bold_font if bold or N else 'android:fontFamily="sans-serif-medium"'
    # No font padding: it left visible gaps under the percentages ([USER] 2026-09-28).
    pad_off = 'android:includeFontPadding="false" '
    return (f'<TextView android:id="@+id/{i}" android:layout_width="wrap_content" android:layout_height="wrap_content" {pad_off}'
            f'android:textSize="{sp}sp" {font} android:maxLines="1" android:ellipsize="end" android:gravity="center" {extra}/>')

def swap(i, w, h, extra=""):
    return (f'<FrameLayout android:id="@+id/{i}" android:layout_width="{w}" android:layout_height="{h}" {PRESS} {extra}>\n'
            f'<ImageView android:id="@+id/{i}_icon" android:layout_width="16dp" android:layout_height="16dp" android:layout_gravity="center" android:src="@drawable/ic_swap_page" android:importantForAccessibility="no" />\n'
            '</FrameLayout>')

def panel(side, first, pct, label, fit=True, last=False):
    m = '' if first else f'android:layout_marginStart="{gap()}"'
    # 2x2: the ring takes the panel's free height. 3x2 / 3x3: the renderer sizes the ring from the
    # widget's real size, and ring + texts sit centred as one group.
    # Nothing: the ring is as tall as it is wide (the bitmap is square, so the ratio is exact), and ring + percentage
    # sit centred as one group: a ring filling the free height left a gap above the percentage ([USER] 2026-09-28).
    ring = ('android:layout_width="match_parent" android:layout_height="wrap_content" android:adjustViewBounds="true" android:scaleType="fitCenter"' if fit
            else 'android:layout_width="wrap_content" android:layout_height="wrap_content" android:scaleType="center"')
    # Square sizes: panels as tall as ring + texts; the case row below takes the rest.
    h = "wrap_content" if fit else "match_parent"
    # Nothing: the sides facing the other ring get 1dp, so the rings grow into the gap between them ([USER] 2026-09-28:
    # 16dp was too wide) and sit 6dp from the edges, the same as the gap. Classic: each ring centred in its box (GEO).
    s, e = (g("outer"), g("inner")) if first else (g("inner"), g("outer"))
    inner_pad = (f'android:paddingTop="{g("top")}dp" android:paddingStart="{s}dp" android:paddingEnd="{e}dp" android:paddingBottom="{g("bottom")}dp"'
                 if fit else
                 # Nothing 4x2: the same 6dp margins and gaps; the renderer sizes the rings to fit (QuickBudsWidget.ringDp).
                 f'android:paddingTop="3dp" android:paddingBottom="3dp" android:paddingStart="{3 if first else 1}dp" android:paddingEnd="{3 if last else 1}dp"'
                 if N else 'android:padding="4dp"')
    return f'''<FrameLayout android:id="@+id/w_panel_{side}" android:layout_width="0dp" android:layout_height="{h}" android:layout_weight="1" {m}>
{img(f"w_panel_{side}_bg")}
<LinearLayout android:layout_width="match_parent" android:layout_height="{h}" android:orientation="vertical" android:gravity="center" {inner_pad}>
<ImageView android:id="@+id/w_ring_{side}" {ring} android:importantForAccessibility="no" />
{text(f"w_pct_{side}", pct, True, f'android:layout_marginTop="{g("pm") if fit else 5 if N else 2}dp" ')}
{text(f"w_label_{side}", label)}
</LinearLayout>
</FrameLayout>'''

def case_bar(sp):
    sw = swap("w_swap_b", "28dp", "match_parent", 'android:layout_marginEnd="-8dp"') + "\n"
    side, mg = g("row"), 6
    # The row takes all the height the rings leave, so only the paddings separate them ([USER] 2026-09-28: no
    # gaps); the case icon fills its height and the renderer sizes the bar from it (QuickBudsWidget.caseRowDp).
    # The level is drawn inside the bar (QuickBudsWidget.bar); w_pct_case stays hidden.
    bar = f'''<FrameLayout android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginStart="{mg}dp">
<ImageView android:id="@+id/w_case_bar" android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_gravity="center" android:adjustViewBounds="true" android:scaleType="fitCenter" android:importantForAccessibility="no" />
{text("w_pct_case", sp, True, 'android:layout_gravity="center" ')}
</FrameLayout>'''
    icon_box = 'android:layout_width="wrap_content" android:layout_height="match_parent" android:adjustViewBounds="true"'
    return f'''<FrameLayout android:id="@+id/w_bar" android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:layout_marginTop="{gap()}" android:paddingTop="{side}dp" android:paddingBottom="{side}dp">
{img("w_bar_bg")}
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center_vertical" android:paddingStart="{side}dp" android:paddingEnd="{side}dp">
<ImageView android:id="@+id/w_case_icon" {icon_box} android:src="@drawable/ic_case" android:scaleType="fitCenter" android:importantForAccessibility="no" />
{bar}
{sw}</LinearLayout>
</FrameLayout>'''

def battery(size):
    # 2x2 and 3x3: two bud panels over the case bar, which ends in the swap button. 4x2 (wide): three
    # panels in a row, the swap button in the top-end corner as on the controls page.
    if size == "m":
        # Nothing: no wear text, so the percentages grow into the room ([USER] 2026-09-28).
        pct = 20 if N else 18
        panels = "\n".join([panel("left", True, pct, 12, False), panel("case", False, pct, 12, False), panel("right", False, pct, 12, False, True)])
        return f'''<FrameLayout android:id="@+id/w_page0" android:layout_width="match_parent" android:layout_height="match_parent">
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" {page_pad()}>
{panels}
</LinearLayout>
{swap("w_swap_b", "44dp", "44dp", 'android:layout_gravity="top|end"')}
</FrameLayout>'''
    # 2x2 (the 3x3 is it scaled): the rings at the top, the case row takes the rest (case_bar). QuickBudsWidget.caseRowDp
    # counts every padding and text here (pct 17 / 16sp, Classic's wear label 11.5sp).
    panels = "\n".join([panel("left", True, 17 if N else 16, 11.5), panel("right", False, 17 if N else 16, 11.5)])
    bottom = case_bar(17 if N else 16)
    row_h = 'android:layout_height="wrap_content"'
    return f'''<LinearLayout android:id="@+id/w_page0" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" {page_pad()}>
<LinearLayout android:layout_width="match_parent" {row_h} android:orientation="horizontal">
{panels}
</LinearLayout>
{bottom}
</LinearLayout>'''

def quick(size):
    """Controls page ([USER] 2026-09-28; Classic too): ANC (opens the level picker), T, A, LL as four equal buttons,
    a 2x2 grid on the square sizes, one row on the 4x2."""
    # Nothing: the dot icon fills the free height. Classic: a vector at a fixed size.
    icon = ('android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:scaleType="centerInside"' if N
            else 'android:layout_width="30dp" android:layout_height="30dp" android:scaleType="fitCenter"')
    def btn(k, first, row_first):
        m = '' if first else f' android:layout_marginStart="{gap()}"'
        return f'''<FrameLayout android:id="@+id/w_q{k}" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1"{m} {PRESS}>
{img(f"w_q{k}_bg")}
{img(f"w_q{k}_stroke", "widget_panel_stroke")}
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:gravity="center" android:padding="8dp">
<ImageView android:id="@+id/w_q{k}_icon" {icon} android:importantForAccessibility="no" />
{text(f"w_q{k}_label", 15, extra='android:layout_marginTop="4dp" ')}
</LinearLayout>
</FrameLayout>'''
    row = lambda ks, top: f'''<LinearLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="horizontal"{'' if top else f' android:layout_marginTop="{gap()}"'}>
{chr(10).join(btn(k, i == 0, top) for i, k in enumerate(ks))}
</LinearLayout>'''
    rows = row((0, 1, 2, 3), True) if size == "m" else row((0, 1), True) + "\n" + row((2, 3), False)
    corner = "36dp" if size == "s" else "44dp"
    return f'''<FrameLayout android:id="@+id/w_page1" android:layout_width="match_parent" android:layout_height="match_parent">
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" {page_pad()}>
{rows}
</LinearLayout>
{swap("w_swap", corner, corner, 'android:layout_gravity="top|end"')}
</FrameLayout>'''

def grid(icon, sp):
    # Nothing: the icon fills the cell's free height, as on the mode button ([USER] 2026-09-28: too much dead space).
    cell_box = ('android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:scaleType="centerInside"' if N
                else f'android:layout_width="{icon}dp" android:layout_height="{icon}dp"')
    def cell(k, first):
        m = '' if first else f' android:layout_marginStart="{gap()}"'
        return f'''<FrameLayout android:id="@+id/w_cell{k}" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1"{m} {PRESS}>
{img(f"w_cell{k}_bg")}
{img(f"w_cell{k}_stroke", "widget_panel_stroke")}
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:gravity="center" android:padding="4dp">
<ImageView android:id="@+id/w_cell{k}_icon" {cell_box} android:importantForAccessibility="no" />
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

# The 3x3 is the 2x2 scaled by K, every dp and sp ([USER] 2026-09-28: literally the same, scaled up). 257.5 / 164.6dp,
# the two widgets' sizes on the Nothing launcher; QuickBudsWidget.LARGE_SCALE is the same number, keep them equal.
K = 1.5645
def scaled(xml):
    return re.sub(r'(-?\d+(?:\.\d+)?)(dp|sp)"', lambda m: f'{round(float(m.group(1)) * K, 2):g}{m.group(2)}"', xml)

TITLE = {"s": "2x2", "m": "4x2", "l": "3x3"}
for N in (False, True):
    sfx = "_n" if N else ""
    open(RES + f"widget_grid{sfx}.xml", "w").write(grid(26, 12.5))
    open(RES + f"widget_grid_l{sfx}.xml", "w").write(scaled(grid(26, 12.5)))
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
{quick(size)}
</ViewFlipper>
</FrameLayout>
<FrameLayout android:id="@+id/w_page2" android:layout_width="match_parent" android:layout_height="match_parent" {page_pad()}>
<include layout="@layout/{"widget_grid_l" if size == "l" else "widget_grid"}{sfx}" />
</FrameLayout>
</ViewFlipper>
</FrameLayout>
'''
        out = "\n".join(l.replace("  />", " />").replace(" >", ">") if l.startswith("<") else l for l in out.split("\n"))
        if size == "s": small = out
        if size == "l":
            out = scaled(small).replace("<!-- 2x2 widget", f"<!-- 3x3 widget: the 2x2 scaled by {K}").replace(f"@layout/widget_grid{sfx}\"", f"@layout/widget_grid_l{sfx}\"")
        open(RES + name + sfx + ".xml", "w").write(out)
print("ok")
