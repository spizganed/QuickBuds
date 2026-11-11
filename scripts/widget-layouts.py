#!/usr/bin/env python3
# Generates the widget layouts: the two page layouts and the seven resizable shapes, plus the level picker grids, in the three styles.
# Edit here, run `python3 scripts/widget-layouts.py` from the repo root, commit the XML with it.
# Classic, Nothing and Material share one structure; they differ in fonts, spacing (GEO), text sizes and, at runtime, boxes.
import os
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "layout") + os.sep
IMG = 'android:scaleType="fitXY" android:importantForAccessibility="no"'
# Every clickable view gets it: pushed down while held (res/animator/widget_press.xml).
PRESS = 'android:stateListAnimator="@animator/widget_press"'
# Style: False = Classic (boxed panels), True = Nothing (tighter inset, no boxes, Ndot digits; widget_*_n layouts).
# The boxes themselves are painted at runtime (QuickBudsWidget.paint / panelColor).
N = False
# The layout family: "c" Classic, "n" Nothing (N), "md" Material (Classic fonts, Nothing's spacing, no wear text, bigger numbers).
S = "c"
# The 2x2 battery page's spacing in dp, per style; QuickBudsWidget.Geo mirrors it, keep them equal. side / vert: page
# padding (4dp more above and below, 2026-09-28: fill the gaps a little, not at the sides); gap between boxes;
# ring panel paddings top, outer (widget side), inner (the other ring's side), bottom; row: the case row's padding.
# Nothing: no boxes, rings 6dp from the sides and apart. Classic: boxed panels, each ring centred in its box.
# pm: the percentage's top margin. Classic is tighter above and below, so its case row gets room next to the wear text
# (2026-09-28: make the case row bigger on Classic too).
# Material: one background (no boxes), so the rings grow toward the edges as on Nothing.
GEO = {"n": dict(side=3, vert=7, gap=4, top=3, outer=3, inner=1, bottom=4, row=3, pm=5),
       "c": dict(side=6, vert=8, gap=6, top=4, outer=7, inner=7, bottom=3, row=4, pm=2),
       "md": dict(side=5, vert=8, gap=4, top=3, outer=3, inner=1, bottom=3, row=3, pm=3)}
# The percentages' size: 2x2, 4x2.
PCT = {"n": (17, 20), "c": (16, 18), "md": (21, 24)}
def g(k): return GEO[S][k]
def pad(): return f"{g('side')}dp"
def gap(): return f"{g('gap')}dp"
def page_pad(): return f'android:paddingTop="{g("vert")}dp" android:paddingBottom="{g("vert")}dp" android:paddingStart="{pad()}" android:paddingEnd="{pad()}"'

def img(i, src="widget_panel"):
    return f'<ImageView android:id="@+id/{i}" android:layout_width="match_parent" android:layout_height="match_parent" android:src="@drawable/{src}" {IMG} />'

def text(i, sp, bold=False, extra="", height=None):
    # Dot matrix and Material: an ImageView the renderer fills with the text drawn in Doto or Google Sans Flex (launchers
    # ignore @font/ in widget XML); its height is 1.2 x the text size, and the bitmap scales to it. height="wrap_content":
    # the renderer sets its own size (the Connect chip picks 14sp or 18sp), so the bitmap's own height decides.
    if N or S == "md":
        h = f'"{height}"' if height else f'"{round(sp * 1.2, 2):g}dp"'
        return (f'<ImageView android:id="@+id/{i}" android:layout_width="wrap_content" android:layout_height={h} '
                f'android:adjustViewBounds="true" android:scaleType="fitCenter" {extra}/>')
    bold_font = 'android:fontFamily="@font/doto" android:textStyle="bold"' if N else 'android:fontFamily="sans-serif" android:textStyle="bold"'
    font = bold_font if bold or N else 'android:fontFamily="sans-serif-medium"'
    # No font padding: it left visible gaps under the percentages (2026-09-28).
    pad_off = 'android:includeFontPadding="false" '
    return (f'<TextView android:id="@+id/{i}" android:layout_width="wrap_content" android:layout_height="wrap_content" {pad_off}'
            f'android:textSize="{sp}sp" {font} android:maxLines="1" android:ellipsize="end" android:gravity="center" {extra}/>')

def panel(side, first, pct, label, fit=True, last=False):
    m = '' if first else f'android:layout_marginStart="{gap()}"'
    # 2x2: the ring takes the panel's free height. 3x2 / 3x3: the renderer sizes the ring from the
    # widget's real size, and ring + texts sit centred as one group.
    # Nothing: the ring is as tall as it is wide (the bitmap is square, so the ratio is exact), and ring + percentage
    # sit centred as one group: a ring filling the free height left a gap above the percentage (2026-09-28).
    ring = ('android:layout_width="match_parent" android:layout_height="wrap_content" android:adjustViewBounds="true" android:scaleType="fitCenter"' if fit
            else 'android:layout_width="wrap_content" android:layout_height="wrap_content" android:scaleType="center"')
    # Square sizes: panels as tall as ring + texts; the case row below takes the rest.
    h = "wrap_content" if fit else "match_parent"
    # Nothing: the sides facing the other ring get 1dp, so the rings grow into the gap between them (2026-09-28:
    # 16dp was too wide) and sit 6dp from the edges, the same as the gap. Classic: each ring centred in its box (GEO).
    s, e = (g("outer"), g("inner")) if first else (g("inner"), g("outer"))
    inner_pad = (f'android:paddingTop="{g("top")}dp" android:paddingStart="{s}dp" android:paddingEnd="{e}dp" android:paddingBottom="{g("bottom")}dp"'
                 if fit else
                 # Nothing 4x2: the same 6dp margins and gaps; the renderer sizes the rings to fit (QuickBudsWidget.ringDp).
                 f'android:paddingTop="3dp" android:paddingBottom="3dp" android:paddingStart="{3 if first else 1}dp" android:paddingEnd="{3 if last else 1}dp"'
                 if N else 'android:padding="4dp"')
    return f'''<FrameLayout android:id="@+id/w_panel_{side}" android:layout_width="0dp" android:layout_height="{h}" android:layout_weight="1" {m}>
{img(f"w_panel_{side}_bg")}
{img(f"w_panel_{side}_stroke", "widget_panel_stroke")}
<LinearLayout android:layout_width="match_parent" android:layout_height="{h}" android:orientation="vertical" android:gravity="center" {inner_pad}>
<ImageView android:id="@+id/w_ring_{side}" {ring} android:importantForAccessibility="no" />
{text(f"w_pct_{side}", pct, True, f'android:layout_marginTop="{g("pm") if fit else 5 if N else 2}dp" ')}
{text(f"w_label_{side}", label)}
</LinearLayout>
</FrameLayout>'''

def case_bar(sp):
    side, mg = g("row"), 6
    # The row takes all the height the rings leave, so only the paddings separate them (2026-09-28: no
    # gaps); the case icon fills its height and the renderer sizes the bar from it (QuickBudsWidget.caseRowDp).
    # The level is drawn inside the bar (QuickBudsWidget.bar); w_pct_case stays hidden.
    bar = f'''<FrameLayout android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1" android:layout_marginStart="{mg}dp">
<ImageView android:id="@+id/w_case_bar" android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_gravity="center" android:adjustViewBounds="true" android:scaleType="fitCenter" android:importantForAccessibility="no" />
{text("w_pct_case", sp, True, 'android:layout_gravity="center" ')}
</FrameLayout>'''
    icon_box = 'android:layout_width="wrap_content" android:layout_height="match_parent" android:adjustViewBounds="true"'
    return f'''<FrameLayout android:id="@+id/w_bar" android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:layout_marginTop="{gap()}" android:paddingTop="{side}dp" android:paddingBottom="{side}dp">
{img("w_bar_bg")}
{img("w_bar_stroke", "widget_panel_stroke")}
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center_vertical" android:paddingStart="{side}dp" android:paddingEnd="{side}dp">
<ImageView android:id="@+id/w_case_icon" {icon_box} android:src="@drawable/ic_case" android:scaleType="fitCenter" android:importantForAccessibility="no" />
{bar}
</LinearLayout>
</FrameLayout>'''

def battery(size):
    # 2x2 and 3x3: two bud panels over the case bar, 4x2 (wide): three
    # panels in a row.
    if size == "m":
        # Nothing: no wear text, so the percentages grow into the room (2026-09-28).
        pct = PCT[S][1]
        panels = "\n".join([panel("left", True, pct, 12, False), panel("case", False, pct, 12, False), panel("right", False, pct, 12, False, True)])
        return f'''<FrameLayout android:id="@+id/w_page0" android:layout_width="match_parent" android:layout_height="match_parent">
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="horizontal" {page_pad()}>
{panels}
</LinearLayout>
</FrameLayout>'''
    # 2x2: the rings at the top, the case row takes the rest (case_bar). QuickBudsWidget.caseRowDp
    # counts every padding and text here (pct 17 / 16sp, Classic's wear label 11.5sp).
    panels = "\n".join([panel("left", True, PCT[S][0], 11.5), panel("right", False, PCT[S][0], 11.5)])
    bottom = case_bar(PCT[S][0])
    row_h = 'android:layout_height="wrap_content"'
    return f'''<LinearLayout android:id="@+id/w_page0" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" {page_pad()}>
<LinearLayout android:layout_width="match_parent" {row_h} android:orientation="horizontal">
{panels}
</LinearLayout>
{bottom}
</LinearLayout>'''

def quick(size):
    """Controls page (2026-09-28; Classic too): ANC (opens the level picker), T, A, LL as four equal buttons,
    a 2x2 grid on the square sizes, one row on the 4x2."""
    # Nothing: the dot icon fills the free height. Classic: a vector at a fixed size.
    icon = ('android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:scaleType="centerInside"' if N
            else f'android:layout_width="{36 if S == "md" else 30}dp" android:layout_height="{36 if S == "md" else 30}dp" android:scaleType="fitCenter"')
    def btn(k, first, row_first):
        m = '' if first else f' android:layout_marginStart="{gap()}"'
        return f'''<FrameLayout android:id="@+id/w_q{k}" android:layout_width="0dp" android:layout_height="match_parent" android:layout_weight="1"{m} {PRESS}>
{img(f"w_q{k}_bg")}
{img(f"w_q{k}_stroke", "widget_panel_stroke")}
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:gravity="center" android:padding="{4 if S == "md" else 8}dp">
<ImageView android:id="@+id/w_q{k}_icon" {icon} android:importantForAccessibility="no" />
{text(f"w_q{k}_label", 17 if S == "md" else 15, extra='android:layout_marginTop="4dp" ')}
</LinearLayout>
</FrameLayout>'''
    row = lambda ks, top: f'''<LinearLayout android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:orientation="horizontal"{'' if top else f' android:layout_marginTop="{gap()}"'}>
{chr(10).join(btn(k, i == 0, top) for i, k in enumerate(ks))}
</LinearLayout>'''
    rows = row((0, 1, 2, 3), True) if size == "m" else row((0, 1), True) + "\n" + row((2, 3), False)
    return f'''<FrameLayout android:id="@+id/w_page1" android:layout_width="match_parent" android:layout_height="match_parent">
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" {page_pad()}>
{rows}
</LinearLayout>
</FrameLayout>'''

def grid(icon, sp, shape="grid"):
    # Nothing: the icon fills the cell's free height, as on the mode button (2026-09-28: too much dead space).
    cell_box = ('android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" android:scaleType="centerInside"' if N
                else f'android:layout_width="{icon}dp" android:layout_height="{icon}dp"')
    def cell(k, first):
        if shape == "col":
            m = '' if first else f' android:layout_marginTop="{gap()}"'
            return f'''<FrameLayout android:id="@+id/w_cell{k}" android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1"{m} {PRESS}>
{img(f"w_cell{k}_bg")}
{img(f"w_cell{k}_stroke", "widget_panel_stroke")}
<LinearLayout android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="vertical" android:gravity="center" android:padding="2dp">
<ImageView android:id="@+id/w_cell{k}_icon" {cell_box} android:importantForAccessibility="no" />
{text(f"w_cell{k}_label", sp, extra='android:layout_marginTop="2dp" ')}
</LinearLayout>
</FrameLayout>'''
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
    if shape in ("row", "col"):
        # The strip's and the column's level picker: every level in one row or one column. w_grid_row1 stays
        # as an empty view, so the renderer sets the same ids on every grid.
        orient = "horizontal" if shape == "row" else "vertical"
        return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Mode list in one {"row" if shape == "row" else "column"}, included by the strip and column layouts' list page. Generated by
     scripts/widget-layouts.py: colours, icons and texts are set at runtime by widget/AncWidgetProvider.kt. -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/w_grid" android:layout_width="match_parent" android:layout_height="match_parent" android:orientation="{orient}">
{row(range(6))}
<FrameLayout android:id="@+id/w_grid_row1" android:layout_width="0dp" android:layout_height="0dp" />
</LinearLayout>
'''
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Mode list grid, included by the widget page layouts' list page. Generated by
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

def chip():
    # The Connect chip over the battery page while disconnected (the renderer shows it and hides it again on connect).
    return f'''<FrameLayout android:id="@+id/w_conn" android:layout_width="wrap_content" android:layout_height="56dp" android:layout_gravity="center" android:visibility="gone" {PRESS}>
<ImageView android:id="@+id/w_conn_bg" android:layout_width="match_parent" android:layout_height="match_parent" android:src="@drawable/widget_panel" {IMG} />
<ImageView android:id="@+id/w_conn_stroke" android:layout_width="match_parent" android:layout_height="match_parent" android:src="@drawable/widget_panel_stroke" {IMG} />
<LinearLayout android:layout_width="wrap_content" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center_vertical" android:paddingStart="24dp" android:paddingEnd="24dp">
<ImageView android:id="@+id/w_conn_dot" android:layout_width="12dp" android:layout_height="12dp" android:src="@drawable/ic_status_dot_empty" android:scaleType="fitCenter" android:importantForAccessibility="no" />
{text("w_conn_text", 18, extra='android:layout_marginStart="10dp" ', height="wrap_content")}
</LinearLayout>
</FrameLayout>'''

def glass_chip():
    # Glass's own Connect chip: a 44dp capsule the renderer draws as a bitmap (QuickBudsWidget.glassChip), with a
    # 6dp dot, an 8dp gap and 18dp padding each side. The three other styles keep the chip above.
    return f'''<FrameLayout android:id="@+id/w_gconn" android:layout_width="wrap_content" android:layout_height="44dp" android:layout_gravity="center" android:visibility="gone" {PRESS}>
<ImageView android:id="@+id/w_gconn_bg" android:layout_width="match_parent" android:layout_height="match_parent" android:src="@drawable/widget_panel" {IMG} />
<LinearLayout android:layout_width="wrap_content" android:layout_height="match_parent" android:orientation="horizontal" android:gravity="center_vertical" android:paddingStart="18dp" android:paddingEnd="18dp">
<ImageView android:id="@+id/w_gconn_dot" android:layout_width="6dp" android:layout_height="6dp" android:src="@drawable/ic_status_dot_empty" android:scaleType="fitCenter" android:importantForAccessibility="no" />
<ImageView android:id="@+id/w_gconn_text" android:layout_width="wrap_content" android:layout_height="wrap_content" android:adjustViewBounds="true" android:scaleType="fitCenter" android:layout_marginStart="8dp" />
</LinearLayout>
</FrameLayout>'''

# Resizable shapes (QuickBudsWidget.Shape): the layouts a widget takes at sizes other than the 2x2 and 4x2 pages.
# The renderer sizes the rings, numbers and tiles from the widget's real size (QuickBudsWidget.fit), so these only
# arrange. Each keeps the ids the renderer sets; a shape without some part leaves it out and the renderer skips it.
# PP: a battery panel's inner padding per style, mirrored by QuickBudsWidget.fit. GAP: between panels and tiles.
PP = {"c": 6, "n": 4, "md": 2}
GAP = 5
W1 = 'android:layout_width="0dp" android:layout_weight="1"'
H1 = 'android:layout_height="0dp" android:layout_weight="1"'
MW = 'android:layout_width="match_parent"'
MH = 'android:layout_height="match_parent"'
WH = 'android:layout_height="wrap_content"'
# Gaps as dividers, so a GONE panel or tile leaves no margin behind.
DIV = 'android:showDividers="middle" android:divider="@drawable/widget_gap"'

def dyn_text(i, extra=""):
    # A number sized at runtime: Material and dot matrix draw it as a bitmap at its real size, Classic sets the TextView's size.
    if N or S == "md":
        return f'<ImageView android:id="@+id/{i}" android:layout_width="wrap_content" {WH} android:adjustViewBounds="true" android:scaleType="fitCenter" {extra}/>'
    return (f'<TextView android:id="@+id/{i}" android:layout_width="wrap_content" {WH} android:includeFontPadding="false" '
            f'android:textSize="16sp" android:fontFamily="sans-serif" android:textStyle="bold" android:maxLines="1" android:gravity="center" {extra}/>')

def unit(side, w, h, across=False):
    # A battery panel: the ring, its number under it (across: beside it). The wear label stays hidden.
    num = 'android:layout_marginStart="8dp" ' if across else 'android:layout_marginTop="2dp" '
    return f'''<FrameLayout android:id="@+id/w_panel_{side}" {w} {h}>
{img(f"w_panel_{side}_bg")}
{img(f"w_panel_{side}_stroke", "widget_panel_stroke")}
<LinearLayout {MW} {MH} android:orientation="{"horizontal" if across else "vertical"}" android:gravity="center" android:padding="{PP[S]}dp">
<ImageView android:id="@+id/w_ring_{side}" android:layout_width="wrap_content" {WH} android:adjustViewBounds="true" android:scaleType="fitCenter" android:importantForAccessibility="no" />
{dyn_text(f"w_pct_{side}", num)}
{text(f"w_label_{side}", 11.5, extra='android:visibility="gone" ')}
</LinearLayout>
</FrameLayout>'''

def tile(k, w, h, icon):
    # A control tile; Nothing's dot icon fills the free height, the others a vector at [icon] dp.
    ic = (f'{MW} {H1} android:scaleType="centerInside"' if N
          else f'android:layout_width="{icon}dp" android:layout_height="{icon}dp" android:scaleType="fitCenter"')
    return f'''<FrameLayout android:id="@+id/w_q{k}" {w} {h} {PRESS}>
{img(f"w_q{k}_bg")}
{img(f"w_q{k}_stroke", "widget_panel_stroke")}
<LinearLayout {MW} {MH} android:orientation="vertical" android:gravity="center" android:padding="4dp">
<ImageView android:id="@+id/w_q{k}_icon" {ic} android:importantForAccessibility="no" />
{text(f"w_q{k}_label", 12, extra='android:layout_marginTop="2dp" ')}
</LinearLayout>
</FrameLayout>'''

def lin(orient, attrs, body):
    return f'<LinearLayout {attrs} android:orientation="{orient}" {DIV}>\n{body}\n</LinearLayout>'

def tiles(ks, w, h, icon):
    return "\n".join(tile(k, w, h, icon) for k in ks)

def case_row():
    # The tall 2-wide shape's case row: a fixed 32dp row (QuickBudsWidget.fit), otherwise the 2x2's.
    return case_bar(PCT[S][0]).replace('android:layout_height="0dp" android:layout_weight="1" android:layout_marginTop="' + gap() + '"',
                                       'android:layout_height="32dp"')

SHAPES = {
    # One row: the three units, then up to 4 tiles, all the same width.
    "strip": lambda: lin("horizontal", f'{MW} {MH} android:gravity="center_vertical"',
                         "\n".join(unit(s, W1, MH) for s in ("left", "case", "right")) + "\n" + tiles(range(4), W1, MH, 22)),
    # One column: the units, then up to 4 tiles, all the same height.
    "column": lambda: lin("vertical", f'{MW} {MH}',
                          "\n".join(unit(s, MW, H1) for s in ("left", "case", "right")) + "\n" + tiles(range(4), MW, H1, 22)),
    # 2 wide, 3 rows or more: the 2x2 battery page, then the controls as a 2x2 grid.
    "tall": lambda: lin("vertical", f'{MW} {MH}',
                        lin("horizontal", f'{MW} {WH}', unit("left", W1, WH) + "\n" + unit("right", W1, WH)) + "\n" + case_row() + "\n" +
                        lin("vertical", f'{MW} {H1}', lin("horizontal", f'{MW} {H1}', tiles((0, 1), W1, MH, 30)) + "\n" +
                            lin("horizontal", f'{MW} {H1}', tiles((2, 3), W1, MH, 30)))),
    # 3 wide or more: the three units in a row, then one row of 4 tiles (m1) or a 2x2 grid (m2).
    "tall_m1": lambda: lin("vertical", f'{MW} {MH}',
                           lin("horizontal", f'{MW} {WH}', "\n".join(unit(s, W1, WH) for s in ("left", "case", "right"))) + "\n" +
                           lin("horizontal", f'{MW} {H1}', tiles(range(4), W1, MH, 26))),
    "tall_m2": lambda: lin("vertical", f'{MW} {MH}',
                           lin("horizontal", f'{MW} {WH}', "\n".join(unit(s, W1, WH) for s in ("left", "case", "right"))) + "\n" +
                           lin("horizontal", f'{MW} {H1}', tiles((0, 1), W1, MH, 30)) + "\n" +
                           lin("horizontal", f'{MW} {H1}', tiles((2, 3), W1, MH, 30))),
    # Battery only, 2 wide and 3 rows or more: one row per unit, the number beside the ring.
    "list": lambda: lin("vertical", f'{MW} {MH}', "\n".join(unit(s, MW, H1, True) for s in ("left", "case", "right"))),
    # Battery only, 3 wide and 3 rows or more: both buds on top, the case below.
    "stack": lambda: lin("vertical", f'{MW} {MH}',
                         lin("horizontal", f'{MW} {H1}', unit("left", W1, MH) + "\n" + unit("right", W1, MH)) + "\n" + unit("case", MW, H1)),
}
# The level picker each shape includes.
SHAPE_GRID = {"strip": "_row", "column": "_col"}

def shape_layout(name, sfx):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Resizable widget, shape "{name}" (QuickBudsWidget.Shape). Generated by scripts/widget-layouts.py. Outer ViewFlipper
     w_pages: w_content, then the mode list w_page2. Sizes, colours, icons and texts are set at runtime by
     widget/AncWidgetProvider.kt; a view added here needs its line in the renderer. -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/w_root" {MW} {MH}>
{img("w_bg", "widget_bg")}
<ViewFlipper android:id="@+id/w_pages" {MW} {MH} android:inAnimation="@anim/widget_enter_left" android:outAnimation="@anim/widget_exit_right" android:animateFirstView="true">
<FrameLayout android:id="@+id/w_content" {MW} {MH} {page_pad()}>
{SHAPES[name]()}
</FrameLayout>
<FrameLayout android:id="@+id/w_page2" {MW} {MH} {page_pad()}>
<include layout="@layout/widget_grid{SHAPE_GRID.get(name, "")}{sfx}" />
</FrameLayout>
</ViewFlipper>
{chip()}
{glass_chip()}
</FrameLayout>
'''

def dots():
    # The two-page widgets' page dots, in the page padding at the top (the renderer shows them and tints the current one).
    return '''<LinearLayout android:id="@+id/w_dots" android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_gravity="top|center_horizontal" android:layout_marginTop="2dp" android:orientation="horizontal" android:visibility="gone">
<ImageView android:id="@+id/w_dot0" android:layout_width="4dp" android:layout_height="4dp" android:src="@drawable/ic_status_dot_filled" android:importantForAccessibility="no" />
<ImageView android:id="@+id/w_dot1" android:layout_width="4dp" android:layout_height="4dp" android:layout_marginStart="3dp" android:src="@drawable/ic_status_dot_filled" android:importantForAccessibility="no" />
</LinearLayout>'''

TITLE = {"s": "2x2", "m": "4x2"}
for S in ("c", "n", "md"):
    N = S == "n"
    sfx = {"c": "", "n": "_n", "md": "_md"}[S]
    open(RES + f"widget_grid{sfx}.xml", "w").write(grid(26, 12.5))
    open(RES + f"widget_grid_row{sfx}.xml", "w").write(grid(26, 12.5, "row"))
    open(RES + f"widget_grid_col{sfx}.xml", "w").write(grid(26, 12.5, "col"))
    for name in SHAPES:
        out = shape_layout(name, sfx)
        out = "\n".join(l.replace("  />", " />").replace(" >", ">") if l.startswith("<") else l for l in out.split("\n"))
        open(RES + f"widget_{name}{sfx}.xml", "w").write(out)
    for size, name in (("s", "widget_pages"), ("m", "widget_pages_m")):
        out = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {TITLE[size]} widget. Generated as one family with the other sizes. Outer ViewFlipper
     w_pages: w_content, then the mode list w_page2. Inside w_content, one ViewFlipper per page, each an empty
     FrameLayout then the page, so each page has its own direction: w_slide0 (the battery page w_page0, in and out on
     the left) and w_slide1 (the controls page w_page1, in and out on the right). w_pages' layout animation brings the
     pages in once when the buds connect (the host inflates this layout then). Colours, icons and texts are set at runtime by
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
{dots()}
</FrameLayout>
<FrameLayout android:id="@+id/w_page2" android:layout_width="match_parent" android:layout_height="match_parent" {page_pad()}>
<include layout="@layout/widget_grid{sfx}" />
</FrameLayout>
</ViewFlipper>
{chip()}
{glass_chip()}
</FrameLayout>
'''
        out = "\n".join(l.replace("  />", " />").replace(" >", ">") if l.startswith("<") else l for l in out.split("\n"))
        open(RES + name + sfx + ".xml", "w").write(out)
print("ok")
