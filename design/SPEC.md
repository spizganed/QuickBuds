# UI Revision Spec — Kotlin + XML Views

Visual reference: the mockup screenshots in `design/`. Rebuild everything in native XML layouts and vector drawables. Do not port any HTML.

**Hard rule:** do not change any protocol, RFCOMM, packet, or wear-state logic. This spec is UI only. Every setting already auto-applies and saves on finger lift, so keep that behavior exactly as it is.

Mockup pixels map 1:1 to `dp`, and text sizes map 1:1 to `sp` (the mockups are drawn at 390 dp width).

---

## 1. Color system (locked: 6 tokens)

Every color in the app comes from these 6 tokens. No hardcoded colors anywhere else.

| Token | OLED Black | Classic Dark | White |
|---|---|---|---|
| `background` | `#000000` | `#121214` | `#F2F2F4` |
| `card` | `#1C1C1E` | `#232327` | `#FFFFFF` |
| `accent` | `#D71920` | `#D71920` | `#D71920` |
| `text` | `#FFFFFF` | `#FFFFFF` | `#111113` |
| `textSecondary` | `#9B9B9B` | `#A0A0A5` | `#6B6B70` |
| `outline` | `#2E2E30` | `#34343A` | `#DDDDE0` |

### Runtime theming (required because of custom presets)
XML themes are compiled, so custom user colors cannot be theme overlays. Use a runtime palette for **all** presets, including the built-in ones:

- `data class Palette(val id: String, val name: String, val builtIn: Boolean, val background: Int, val card: Int, val accent: Int, val text: Int, val textSecondary: Int, val outline: Int)`
- `PaletteRepository` stores the custom presets as JSON in DataStore or SharedPreferences. There are at most **3 custom presets**; they can be created, renamed, duplicated, and deleted. It also stores the active preset id.
- `PaletteManager` exposes the active palette (StateFlow/LiveData). Each screen applies it in `onCreate`/`onViewCreated` and again when it changes: backgrounds via `setBackgroundColor`, or `GradientDrawable.setColor`/`setStroke` for cards; icons via `ImageViewCompat.setImageTintList`; text via `setTextColor`; switches via tint lists.
- Tip: write a small `PaletteApplier` that walks a view tree and styles views by tag (`android:tag="card"`, `"icon_accent"`, `"text_secondary"`, …). That avoids per-screen boilerplate.
- Set the status bar and navigation bar colors to `background`, and pick light or dark bar icons from `background` luminance.

### Derived colors (not user-editable, computed from tokens)
- Toggle track: `outline`, slightly lightened on dark themes (mockup: `#3A3A3C`)
- Toggle thumb, off: `text` at ~90% alpha. Toggle thumb, on: `accent`.
- Disabled or empty elements (empty battery ring, placeholder icons): `textSecondary` at ~50% alpha
- Expanded row highlight: `card` blended ~5% toward `text`

### Contrast warning (Edit preset screen)
Use the WCAG 2 contrast ratio:
- `text` and `textSecondary` against `card` and against `background`: warn below **4.5:1**
- `accent` against `background`: warn below **3:1**

Show the warning inline under the offending token row, for example "⚠ 3.9:1 on Card · may be hard to read". It is a warning only and never blocks saving.

---

## 2. Shared components

| Component | Spec |
|---|---|
| Screen | `background` fill, padding 20dp top/bottom and 16dp sides, vertical gap 10–16dp |
| Screen title | 24sp bold, `text`, 4dp start inset |
| Section label | 14sp regular, `textSecondary`, 4dp start inset, 8–10dp above its card |
| Card | `card` fill, 1dp `outline` stroke, 24dp corner radius, `clipToOutline` so rows are clipped |
| Row | 72dp tall (62dp in the color list), 16dp horizontal padding, 14dp gap. 24dp accent icon, then title (16sp semibold `text`) with subtitle (13sp `textSecondary`), then a trailing slot. 1dp `outline` divider between rows, none after the last. **The whole row is clickable** (ripple). |
| Trailing slot | Fixed **52dp** wide, content aligned to the end, so every toggle and chevron shares one right edge |
| Chevron | 22dp, **accent**, no circle and no outline |
| Toggle | `MaterialSwitch` (or `SwitchMaterial`) styled with the derived tints above, inside the 52dp slot |
| Icon button | 44×44dp, `card` fill, 1dp `outline` stroke, 14dp radius, 22dp accent icon, `contentDescription` required |
| Status chip | 44dp tall, 22dp radius, `card` fill, 1dp `outline` stroke, 14dp horizontal padding, 10dp dot + 14sp semibold label |

Touch targets must be at least 44dp everywhere.

---

## 3. Screens

### 3.1 Home — Connected
- **Header (48dp):** device name (19sp bold, single line, ellipsize end), then the status chip, the Dev tools icon button, and the Settings icon button (cog).
  - Status chip when connected: **filled `accent` dot** and the label "Connected" in `text`.
  - Tapping it opens the Disconnect dialog (3.4).
  - The Dev tools button is hidden when Settings › Dev tools button is off.
- **Battery tile:** a card with 3 columns (Left, Case, Right), each 104dp wide.
  - Ring: 104dp, ~7dp stroke, `outline` track with an `accent` progress arc starting at 12 o'clock with round caps. `CircularProgressIndicator` (Material) or a small custom `View` both work.
  - Glyph centered in the ring: earbud 42×56dp, case 58×42dp. The right bud is the left bud mirrored.
  - Below the ring: percentage (24sp semibold `text`), then a label (13sp `textSecondary`) such as "Left · In ear".
- **Noise control tile:**
  - Section label "Noise control".
  - Segmented control: a card-style container (22dp radius, 4dp padding) holding **4 equal-width segments** (Off / ANC / Adaptive / Transparency). Each segment is 64dp tall with an 18dp radius, a 22dp icon above an 11.5sp semibold label, and the full word "Transparency".
  - Selected segment: `accent` fill, with icon and label in white (or `text` if the accent is light). Unselected: `textSecondary`.
  - **Level row**, shown under the segments only when the selected mode has levels (ANC: Low / Medium / High): centered 40dp pills with a 20dp radius and 1dp `outline`. The selected pill gets a 1.5dp `accent` stroke and `text` label. This replaces the old bottom sheet.
- **Settings list tile:** Low latency mode (toggle), High-quality audio (toggle), 3D audio (toggle), Equalizer (chevron), Earbud settings (chevron, **earbud glyph icon**, not a cog).

### 3.2 Home — Disconnected
- The header keeps an empty spacer where the name was. **Do not show a device name.**
- Status chip: a **hollow ring dot** (2dp `textSecondary` stroke) and the label "Connect" in `textSecondary`. Tapping it connects.
- Battery tile at **exactly the same size** as when connected, so nothing jumps:
  - Rings show the track only.
  - Glyphs are drawn in the disabled color.
  - The percentage shows "—" and the labels are just "Left", "Case", "Right".
- Everything below the battery tile is wrapped in one container with `alpha = 0.35f`. Disable all its children recursively (`isEnabled = false`, not clickable). Icons **stay accent-colored**; they are dimmed, not greyed. Toggles and segments show a neutral state.

### 3.3 Earbud glyph states (3 states)
| State | Glyph tint | Extra |
|---|---|---|
| In ear | `text` | — |
| Out of ear | `textSecondary` | — |
| In case | `textSecondary` | **Case badge**: a 30dp `accent` circle with a 3dp `card`-colored border and a small white case glyph, placed bottom-right of the bud but inside the ring |

The text label under the ring still states the exact state.

### 3.4 Disconnect dialog
Use `MaterialAlertDialogBuilder` with a custom style: `card` background, 24dp radius, 1dp `outline` stroke.
- Title: "Disconnect OnePlus Buds 4?" (19sp bold), using the actual device name.
- Body: "Audio will stop playing on this phone." (14sp `textSecondary`).
- Buttons, 44dp pills aligned to the end: **Cancel** (outlined, `text`) and **Disconnect** (`accent` fill, white text).

### 3.5 EQ editor
Keep the existing curve editor, and change the header:
- Left: Rename (pencil icon button).
- Center: preset name.
- Right: **Done, as a checkmark icon button**, not text and not an X.
- Bottom right: Duplicate and Delete icon buttons, 48dp.
- Keep auto-apply on finger lift. **No** "saved" checkmark or toast.

### 3.6 Settings (a full screen, not a bottom sheet)
Uses the same layout pattern as Earbud settings: title "Settings", then sectioned cards.

| Section | Row | Control |
|---|---|---|
| Appearance | Theme & colors, with the active preset name as subtitle | chevron → 3.7 |
| Appearance | Home layout ("Reorder and hide tiles") | chevron |
| General | Haptic feedback ("Vibrate when a setting changes") | toggle, default ON |
| Developer | Dev tools button ("Show dev tools in the header") | toggle |
| App | App update ("Installed x.y.z") | chevron → existing screen |
| App | About ("Version, licenses, GitHub") | chevron |

The header's cog icon stays as the entry point to this screen.

### 3.7 Theme & colors
- **Built-in:** a 3-column grid of preview tiles (150dp tall, 18dp radius). Each tile is a mini home screen drawn in that preset's colors: a card with 3 small rings, a segment pill, and a row with text lines and a dot.
  - The name sits below each tile (13sp).
  - Active preset: 2dp `accent` stroke, a 24dp `accent` check badge overlapping the top-right corner, and its name in `text` semibold.
  - Tapping a tile applies that preset immediately.
- **Custom · N of 3:** a card of rows. Each row has a 3×2 swatch grid (42dp wide) showing the preset's 6 colors, the preset name, and an accent pencil in the trailing slot that opens 3.8. Tapping the row itself applies the preset.
- The last row is "New preset" (plus icon), with subtitle "Starts from the current theme · N slots left". When N = 0, disable the row with the subtitle "Maximum 3 presets".
- Footer (13sp `textSecondary`): "A preset sets 6 colors: background, card, accent, text, secondary text and outline."

### 3.8 Edit preset
- **Header:** title "Edit preset", then Duplicate and Delete icon buttons (44dp). Delete asks for confirmation using the same dialog style as 3.4. Deleting the active preset falls back to OLED Black.
- **Name:** a `TextInputEditText` in a card-styled box (52dp, 16dp radius). The name saves on focus loss or IME done.
- **Preview:** a live mini home screen drawn in the **preset's** colors (background, battery card with rings, one toggle row). The screen around it stays in the **active** theme.
- **Colors card:** 6 rows (62dp). Each has a 30dp swatch circle with a 1dp stroke, the token name, the hex value in `textSecondary` with tabular numbers, and an accent chevron.
  - Tapping a row expands an inline picker (chevron flips down, row background uses the expanded highlight). The picker has a hue slider, a hex `EditText`, and 5 quick swatches (red `#D71920`, orange `#E8742A`, green `#22A06B`, blue `#3B82F6`, purple `#8B5CF6`).
  - Only one row is expanded at a time.
  - Changes apply to the preview live and auto-save, the same pattern as the EQ.
- Contrast warnings as in section 1.

---

## 4. Behavior rules

- **Haptics:** call `performHapticFeedback(HapticFeedbackConstants.CONFIRM)` (API 30+, fall back to `VIRTUAL_KEY`) when a setting is committed (finger lift, toggle, selection). Gate it on the Settings toggle.
- **Home tiles:** each tile is its own include layout with a stable id. Tile order and visibility are persisted. **The battery and noise control tiles cannot be hidden.** The Home layout screen UI is not part of this pass; just make sure tiles are built as independent, reorderable units.
- **Wear detection:** keep the **two separate toggles**, one for the firmware feature and one for the app's "pause only when both are out". No change.
- **Find my earbuds:** unchanged; the firmware rings both buds at once.
- **Disconnected state:** nothing below the battery tile is interactive.

## 5. Vector drawables needed

All icons use a 24×24 viewport, stroke width 2.2, round caps and joins, tinted at runtime (`accent` unless noted).

| Name | pathData (stroke unless noted) |
|---|---|
| `ic_chevron_right` | `M9,6l6,6 -6,6` |
| `ic_chevron_down` | `M6,9l6,6 6,-6` |
| `ic_check` | `M5,12.5l4.5,4.5L19,7` |
| `ic_plus` | `M12,5v14M5,12h14` |
| `ic_pencil` | `M4,20h4L19,9l-4,-4L4,16z M13.5,6.5l4,4` |
| `ic_delete` | `M4,7h16M9,7V4h6v3M6,7l1,13h10l1,-13` |
| `ic_noise_off` | circle r8.5 at 12,12 + `M6,6l12,12` |
| `ic_anc` | `M4,15v-3a8,8 0,0 1,16 0v3` + two rounded rects (3.5,14.5 and 16.5,14.5; 4×6) |
| `ic_adaptive` | `M12,3l1.9,5.1L19,10l-5.1,1.9L12,17l-1.9,-5.1L5,10l5.1,-1.9z` |
| `ic_transparency` | `M3,11v2M7,8v8M11,4v16M15,8v8M19,10v4` |
| `ic_earbud` (fill) | circle r5.5 at 10,7.5 + rounded rect 7.6,8 5×14 + circle r3.8 at 14,9.5 with a thin `card` stroke |
| `ic_layout` | rects at 3.5,3.5 7×7 · 13.5,3.5 7×7 · 3.5,13.5 17×7, radius 2 |
| `ic_update` | `M12,4v11M7,10l5,5 5,-5M4,20h16` |
| `ic_warning` | `M12,3l10,18H2z M12,10v5 M12,18h0.01` |

Convert the rest (palette, haptics, info, copy) from any standard icon set to the same stroke style so they match.

## 6. Suggested implementation order

1. `Palette`, `PaletteRepository`, `PaletteManager`, `PaletteApplier`. Migrate all existing hardcoded colors to the tokens.
2. Shared components: card and row styles, the trailing slot, the switch style, icon buttons, the status chip.
3. Home: connected, then disconnected, then the earbud glyph states.
4. Disconnect dialog and EQ editor header.
5. Settings screen, replacing the old bottom sheet.
6. Theme & colors, then Edit preset.
7. Haptics hook-up.

Work one step at a time and build-check after each step.
