# Widgets & Wear-State Spec

Visual reference: the `w1`–`w7` PNGs in this folder. The mockups use a magenta accent; real widgets use the active palette's `accent`. When a PNG and this file disagree, this file wins. Mockup px = dp, and text px = sp.

Same hard rule as SPEC.md: this is UI only. Use the existing protocol and repository calls to read state and to send mode and low-latency changes. Do not change packet or RFCOMM logic.

---

## 1. Wear-state colors (app AND widgets) — `w1`

Only the earbud **icon** inside the battery ring changes. **The ring never changes**: it is always `outline` for the track and `accent` for the progress.

| State | Earbud icon | Label under the % |
|---|---|---|
| In ear | `text` | "In ear", `text`, semibold |
| Out of ear | `textSecondary` | "Out of ear", `textSecondary` |
| In case | `textSecondary` at 45% alpha | "In case", `textSecondary` |

The percentage text is always `text`. Use no new colors. Apply this on the app's home battery tile too, not only the widgets.

**Icon centering.** The earbud vector has uneven empty space around it, so its bounding box must be trimmed to the actual shape (the mockups use viewBox `6.5 4.5 43.5 70` in the original 60×80 coordinates). Center the trimmed drawable in the ring with gravity center. The right bud is the same drawable mirrored with `scaleX = -1`, which must stay centered.

## 2. Shared widget styling

- Outer radius 26dp (28dp on 3×3), **inner padding 6dp**, 6dp gap between all inner elements.
- Inner panels and buttons use a 20dp radius (22dp on 3×3), which keeps their corners concentric with the outer corner.
- Colors come from the active palette at update time. Set them via `RemoteViews.setColorStateList` / `setInt(..., "setBackgroundColor" | "setColorFilter", ...)` on API 31+, with a sensible fallback for older APIs.
  - widget background = `card`
  - battery panel = `card` lightened ~4% toward `text`
  - unselected button = the panel color with a 1dp `outline` stroke
  - selected button = `accent`, with white icon and text
- Battery ring: track `outline`, progress `accent`, round cap, starts at 12 o'clock.

## 3. Widgets

The three widgets below replace the previous widget designs.

### 3.1 Battery widget, 2×2 — `w2`
- Top area: two equal panels (Left bud, Right bud). Each panel holds a 56dp ring with the earbud icon, then the percentage (16sp bold), then the wear-state label (11.5sp).
- Bottom: a 42dp bar with the case icon, a 6dp progress bar in `accent`, and the percentage (14sp bold).
- Disconnected: empty rings, "—" instead of percentages, labels "Left" / "Right", and the bar reads "Not connected".

### 3.2 Controls widget, 2×2 — `w3`
- Mode button (fills the remaining height, `accent` when the mode isn't Off): the current mode's icon at 40dp, with the mode name (14.5sp bold) and a hint glyph after it. The hint is **↻** when the tap setting is *Next mode* and **⌄** when it is *Open list*.
- Low latency button: 48dp tall, lightning icon plus "Low latency". It is `accent` when on and the unselected style when off. It can be hidden in settings, in which case the mode button fills the whole widget.
- List open: the list replaces the whole widget.
  - With ≤4 modes: a 2×2 grid, each cell an icon plus a label.
  - With 5–6 modes: a 3×2 grid of **icons only**, because the labels don't fit.
- Disconnected: both controls greyed out and disabled, with "Not connected" in the mode area.

### 3.3 Combined widget, 3×2 — `w4`
- Top: three 84dp battery panels (Left, Case, Right). Each has a 42dp ring, the percentage (13.5sp bold), and the state label (10.5sp).
- Bottom: the mode button (icon, name, hint glyph, horizontal) and a 78dp-wide low latency button (icon above the label).
- List open: replaces the whole widget with a 3×2 grid, icon plus label, all 6 modes fit.

### 3.4 Combined widget, 3×3 — `w5`
- Top: three 96dp battery panels with 50dp rings.
- Middle: the mode button, with a 42dp icon and two lines of text ("Noise control" caption at 12sp, then the mode name at 17sp bold with the hint glyph).
- Bottom: a 50dp full-width low latency button.
- List open: replaces **only the controls**, and the battery row stays visible. It is a 3×2 grid with icons plus labels.

## 4. Behavior

- **Mode button tap** depends on the setting "Tapping the mode button":
  - *Next mode*: step to the next enabled mode in the user's order, wrapping around.
  - *Open list*: swap the widget to its list layout with `updateAppWidget` / `partiallyUpdateAppWidget`. Do not open an Activity.
- **List closes** when a mode is tapped (tapping the current mode just closes it) or after 5 seconds with no tap. Use a WorkManager one-shot, AlarmManager, or a delayed handler in the provider's update service, whichever fits the existing architecture.
- **Optimistic update:** highlight the new mode immediately on tap, then correct it if the read-back disagrees.
- **Low latency button:** toggles low-latency mode, again with an optimistic highlight.
- **Battery / widget background tap:** does **nothing by default**. It opens QuickBuds only when "Open app on tap" is on.
- **Disconnected:** show the disconnected layout. Controls are disabled and do nothing.
- Each size is its own `AppWidgetProviderInfo` with a sensible `minWidth`/`minHeight` and `targetCellWidth`/`targetCellHeight` (API 31+). The alternative is one provider with size-mapped RemoteViews on API 31+. Choose whichever matches the existing widget code, and explain the choice in the plan.
- Every button needs a `contentDescription`. For example, the mode button reads "Noise control: ANC Medium, tap for next mode", and a list item reads "ANC High".

## 5. Widget settings screen — `w6`

This screen follows the app's settings screen style.

- **Tapping the mode button:** a two-option segmented control, *Next mode* (↻, default) or *Open list* (grid icon).
- **Modes:** a reorderable list of Off, ANC Low, ANC Medium, ANC High, Adaptive and Transparency. Each row has a drag handle, the mode icon, the name and a checkbox. At least 2 must stay checked (disable unchecking the last two). The **same list and order** drive both the cycle and the list.
  - Default: ANC Low, ANC Medium, ANC High and Transparency checked; Adaptive and Off unchecked.
- **Low latency button:** toggle, default on.
- **Open app on tap:** toggle, **default off**.
- Settings apply to all widget instances. Changing them triggers a widget update.

## 6. Mode icons — `w7`

The icons form one family: a dot inside rings. Solid rings mean ANC (more rings means stronger), and dashed rings mean Transparency. All are 24×24 vector drawables tinted at runtime.

| Name | Shape (24×24 viewport) |
|---|---|
| `ic_mode_off` | circle r8.5 (stroke 2) + line 6,6→18,18 |
| `ic_mode_anc_low` | dot r2.2 (fill) + circle r5.2 (stroke 1.9) |
| `ic_mode_anc_medium` | dot + circles r5.2, r8.2 |
| `ic_mode_anc_high` | dot + circles r5.2, r8.2, r11 |
| `ic_mode_adaptive` | dot r2.2 + circle r5.8, both centered at 11,13, + a 4-point sparkle at 19,5.2 (path `M19 1.5l1.1 2.6 2.6 1.1-2.6 1.1L19 8.9l-1.1-2.6-2.6-1.1 2.6-1.1z`) |
| `ic_mode_transparency` | dot + **dashed** circles r5.6 (dash 2.2/2.2) and r9.8 (dash 2.6/2.6) |
| `ic_low_latency` | lightning `M13 2L4 14h7l-1 8 9-12h-7z` (fill) |
| `ic_hint_cycle` | `M20 12a8 8 0 1 1-2.3-5.7` + `M20 4v4h-4` (stroke) |
| `ic_hint_list` | chevron `M6 9l6 6 6-6` (stroke) |

Vector drawables can't do dashed strokes, so draw the Transparency rings as short arc segments, or export that one icon as a path.
