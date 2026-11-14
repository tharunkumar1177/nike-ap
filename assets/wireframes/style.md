<!-- assets/wireframes/style.md: visual rules every PadLink wireframe must follow. -->

# Wireframe style guide

Wireframes are high-fidelity, dp-accurate SVG drawings of what the Compose code renders. They are not sketches: every size, color, and string comes from the app source or from the Material 3 component defaults it uses.

## Source of truth

| Concern | Source |
|---|---|
| Brand and trackpad colors | `android/app/src/main/kotlin/dev/padlink/ui/theme/Theme.kt` (`PadColors`, `LightColors`) |
| Every visible string | `android/app/src/main/res/values/strings.xml`, copied verbatim |
| Layout | `MouseScreen.kt`, `TrackpadSurface.kt`, `PenCanvasScreen.kt`, `SettingsSheet.kt`, `ConnectionCards.kt` |
| Component defaults | Compose Material 3 1.3.1 (Compose BOM `2024.12.01`) |

The theme overrides only a few color roles. Every role it does not set falls back to the Material 3 baseline scheme, so the baseline values below are what the device really shows. When the code changes, redraw the affected wireframe; never draw intended UI that the code does not produce.

## Canvas and frame

- **Units**: 1 SVG unit = 1 dp. Snap edges to whole or half units.
- **Portrait screens**: `390 x 844`, `viewBox="0 0 390 844"`.
- **Landscape-locked screens** (activities declared `sensorLandscape`, such as the pen canvas): the same device rotated, `844 x 390`.
- **Borderless**: the UI fills the canvas edge to edge. No device bezel, shadow, margin, or outer border.
- **System bars** (edge-to-edge activities):
  - Status bar: `y 0..24`, transparent over the app surface. Clock `12:30` at `x 16`, Roboto 14 regular, baseline `16.8`. Wi-Fi, signal, and battery glyphs at 16 dp, right-aligned ending at `x 374`, vertically centered on `y 12`.
  - Gesture navigation: bottom 24 dp. Handle `108 x 4`, radius 2, centered horizontally, center `y = height - 12`.
  - Icons and handle use `#1D1B20` in the light theme.
- **Immersive screens** (pen canvas) draw no system bars.
- **Display cutout**: not drawn.

## Theme

Draw the **light theme**. Dark theme is drawn only when a task asks for it, using `DarkColors` from `Theme.kt`.

### Color tokens

| Token | Hex | Origin | Used for |
|---|---|---|---|
| `surface` / `background` | `#F6F8FB` | theme | Screen background, top app bar, tab row container |
| `onSurface` | `#1D1B20` | baseline | Primary text, status bar glyphs, dropdown arrow |
| `onSurfaceVariant` | `#49454F` | baseline | Header subtitle, app bar action icons, unselected tabs, unselected chip labels, drag handle (40%) |
| `primary` | `#00658F` | theme | Selected tab, tab indicator, slider active track and thumb, switch on track, outlined button label |
| `onPrimary` | `#FFFFFF` | theme | Switch on thumb |
| `primaryContainer` | `#CFE5FF` | theme | Connection guidance card |
| `secondaryContainer` | `#E8DEF8` | baseline | Selected filter chip, slider inactive track, Samsung hint card |
| `onSecondaryContainer` | `#1D192B` | baseline | Selected filter chip label |
| `surfaceContainerLow` | `#F7F2FA` | baseline | Modal bottom sheet |
| `surfaceContainerHighest` | `#E6E0E9` | baseline | Switch off track |
| `outline` | `#79747E` | baseline | Switch off border and thumb, outlined button border |
| `outlineVariant` | `#CAC4D0` | baseline | Dividers, unselected chip border |
| `scrim` | `#000000` at 32% | baseline | Behind modal sheets and dialogs |
| `PadColors.Trackpad` | `#004B70` | `PadColors` | Trackpad, scroll strip |
| `PadColors.Button` | `#3A7CA5` | `PadColors` | Mouse buttons |
| `PadColors.ButtonPressed` | `#2A6189` | `PadColors` | Pressed mouse button |
| `PadColors.OnTrackpad` | `#CFE5FF` | `PadColors` | Glyphs and hint on the trackpad, ink trail, tablet frame (50%), scroll line (60%) |
| `PadColors.PenCanvas` | `#001E30` | `PadColors` | Pen canvas background, status pill (70%) |
| `PadColors.Letterbox` | `#000000` at 20% | `PadColors` | Area outside the active tablet rectangle |

### Typography

Font: `font-family="Roboto, 'Segoe UI', Arial, sans-serif"`. Weights: 400 regular, 500 medium.

| Style | Size / line height | Weight | Baseline offset from line-box top |
|---|---|---|---|
| `titleLarge` | 22 / 28 | 400 | 21.5 |
| `titleSmall` | 14 / 20 | 500 | 14.8 |
| `labelLarge` | 14 / 20 | 500 | 14.8 |
| `bodyLarge` | 16 / 24 | 400 | 17.5 |
| `bodyMedium` | 14 / 20 | 400 | 14.8 |
| `bodySmall` | 12 / 16 | 400 | 12.1 |

Baseline offset = `(lineHeight - 1.172 * size) / 2 + 0.928 * size` (Roboto ascent 0.928 em, descent 0.244 em). Text that a layout centers vertically is centered by its line box, not by its glyphs. Multi-line text wraps at the container width and steps by the line height.

### Shapes

| Element | Radius |
|---|---|
| Trackpad, scroll strip, mouse button | 18 |
| Pen canvas status pill | 12 |
| Card | 12 |
| Modal bottom sheet (top corners only) | 28 |
| Filter chip | 8 |
| Outlined button, switch track, drag handle | fully rounded |

No drop shadows. Material 3 1.3.1 sheets and cards here use tonal color, not shadow.

## Components

All sizes in dp.

- **Top app bar** (small): 64 tall below the status bar, `surface` fill. Title column is centered vertically (title 28 + subtitle row 24 = 52, top inset 6) and starts at `x 16`. Subtitle row: `bodyMedium` `onSurfaceVariant` text `"<target>  <status>"` (two spaces) followed by a 24 dp `ArrowDropDown` in `onSurface`. Actions are 48 dp icon buttons with 24 dp icons, the last ending 4 dp from the right edge.
- **Progress slot**: 4 tall under the app bar. Empty unless the status is connecting, in which case it shows a linear progress indicator inset 4 dp per side.
- **Content area**: 4 dp padding on all sides, 8 dp vertical gaps; the trackpad row uses 8 dp horizontal gaps.
- **Trackpad**: `PadColors.Trackpad`, radius 18. Centered column: 44 dp `Mouse` outlined icon, 8 gap, `bodySmall` hint, both `OnTrackpad`.
- **Scroll strip**: 44 wide, radius 18, `Trackpad` fill. Up and down arrows in 48 dp slots at each end; between them a 1 dp vertical line in `OnTrackpad` at 60%.
- **Mouse buttons**: 80 tall row, equal widths, 8 gap, radius 18, `Button` fill, `titleSmall` white label centered.
- **Tablet frame** (pen canvas, tablet mode): the largest centered rectangle of the target aspect inside the trackpad, scaled by the tablet-area percentage. `Letterbox` outside it, 1 dp `OnTrackpad` 50% stroke centered on its edge, all clipped to the trackpad shape. Bluetooth and unknown hosts use 16:9.
- **Ink trail**: `OnTrackpad` round-cap segments, width `1.5 + 4.5 * pressure`, opacity falling linearly to 0 over the oldest 700 ms of the stroke; predicted tail at 50%.
- **Modal bottom sheet**: `surfaceContainerLow`, top radius 28, full width. Drag handle `32 x 4` in `onSurfaceVariant` at 40%, inside a 48 tall slot. Content gets the 24 dp bottom navigation inset.
- **Tab row**: 48 tall, `surface` container, four equal fixed tabs, `titleSmall` labels (`primary` when selected, `onSurfaceVariant` otherwise), 2 dp `primary` indicator across the full selected tab, 1 dp `outlineVariant` divider at the bottom edge under the indicator.
- **Settings content**: 20 dp horizontal and 12 dp vertical padding, 10 dp gap between items.
- **Labeled slider**: label row (`bodyLarge` left, `bodyMedium` value right, top-aligned), then a 48 tall slider. Track 16 tall, fully rounded at the outer ends and 2 dp at the inner ends. Thumb `4 x 44`, radius 2, `primary`. 6 dp gap between the thumb and each track part. Active track `primary`, inactive `secondaryContainer`, 4 dp `primary` stop dot centered 8 dp from the inactive end. The thumb center travels from `x + 2` to `x + width - 2`.
- **Switch row**: 48 tall, `bodyLarge` label, switch right-aligned. Track `52 x 32`. On: `primary` track, 24 dp `onPrimary` thumb centered 16 dp from the right. Off: `surfaceContainerHighest` track with 2 dp `outline` border, 16 dp `outline` thumb centered 16 dp from the left.
- **Filter chip group**: `bodyLarge` label, 4 gap, then chips in a flow row (8 horizontal gap, rows stacked with no gap). Chip slot 48 tall with a 32 tall visible chip centered. Width = label width + 32. Unselected: transparent with 1 dp `outlineVariant` border, `onSurfaceVariant` label. Selected: `secondaryContainer` fill, no border, `onSecondaryContainer` label, no check icon. Label is `labelLarge`.
- **Help text**: `bodySmall` `onSurface`, wrapped at the content width.
- **Divider**: 1 dp `outlineVariant`, full content width.
- **Outlined button**: 48 tall slot, visible 40 tall, fully rounded, 1 dp `outline` border, 24 dp horizontal padding, `labelLarge` `primary` label.
- **Card**: radius 12, 16 dp padding, 6 dp gaps, `titleSmall` semibold title and `bodyMedium` body; actions are right-aligned text buttons.

## Icons

- Use Material Icons path data (24 x 24 grid) inside `<defs>` as `<symbol>` or `<path id>`, placed with `translate` and `scale`. Never use emoji or font glyphs as icons.
- Size 24 dp unless the code sets another size; 44 dp for the trackpad glyph (`scale(1.8333)`).
- Outlined icons from the code (`Outlined.Settings`, `Outlined.Draw`, `Outlined.Mouse`) use their outlined geometry.
- Color follows the component: app bar actions `onSurfaceVariant`, trackpad and pen canvas icons `OnTrackpad`.

## Content and states

- Strings come verbatim from `strings.xml`. The only invented content is the sample computer name **`DESKTOP-PC`**, used wherever a target or host name appears.
- Draw settings at their `SettingsStore` defaults unless the state name says otherwise.
- **Connected** state: header status `connected`, no guidance card, empty progress slot.
- **Settings full view**: the sheet is fully expanded to the bottom of the status bar (`y 24`); the scrim covers the status bar area and system glyphs stay on top. Content that is shorter than the sheet leaves sheet color below it.

## SVG conventions

- First line `<?xml version="1.0" encoding="UTF-8"?>`, second line a header comment: `<!-- assets/wireframes/<file>.svg: <screen and state>. -->`.
- Root: `width`, `height`, and `viewBox` equal to the canvas size; set `font-family` on the root.
- Group each region in a `<g id="...">` named after the composable or region (`status-bar`, `top-app-bar`, `trackpad`, `scroll-strip`, `mouse-buttons`, `sheet`, `tab-row`, `content`, `nav-bar`).
- Use `xml:space="preserve"` on text with significant double spaces.
- No raster images, filters, external references, or annotation callouts on the canvas. Explanations belong in the blueprint, not the drawing.
- Draw in paint order: background, surfaces, content, overlays (scrim, sheet, dialog), system bars.

## Naming

`<screen>-<state>.svg` in kebab case, saved in `assets/wireframes/`.

| File | Screen |
|---|---|
| `main-connected.svg` | Mouse screen, connected over any transport |
| `pen-canvas-drawing.svg` | Pen canvas (landscape) in tablet mode with an ink trail |
| `settings-mouse.svg` | Settings sheet, Mouse tab, full view |
| `settings-pen.svg` | Settings sheet, Pen tab, full view |
| `settings-connection.svg` | Settings sheet, Connection tab, full view |
| `settings-display.svg` | Settings sheet, Display tab, full view |
