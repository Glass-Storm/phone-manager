# Design

The visual system for the `phone-manager` hub app. `PRODUCT.md` is the strategy;
this is how it looks. It extends — never replaces — the generated Lumo kit
(`ui/Theme.kt`, `ui/Color.kt`, `ui/Typography.kt`, `ui/components/**`,
`ui/foundation/**`), which is **generated code and must not be hand-edited**.

## The one structural decision: a token override, not a kit edit

The Lumo plugin generates a neutral Swiss palette (primary = black, surface =
grey). The product needs **calm, bright, assistant**. Because the kit is
generated, the palette is re-pointed by a **new** file, `ui/HubTheme.kt`, which
wraps `AppTheme` and re-provides `LocalColors` (plus the selection/text colour
locals so the caret and content colour follow the new palette too). No generated
file is touched; the kit's components, type scale, shapes and ripple are reused
unchanged.

Consequence: every kit component (`Button`, `Card`, `Surface`, `Text`,
`OutlinedTextField`, …) automatically adopts the new palette through
`AppTheme.colors`. Screens read semantic tokens only — **never a raw
`Color(0x…)`** — so the theme stays single-sourced.

## Colour

Strategy: **Restrained** — tinted cool neutrals, one blue accent, semantic state
colours used only for state. All pairs meet **WCAG 2.1 AA** (verified by
`HubPaletteContrastTest`, not by eye).

### Light

| Token                                 | Hex             | Role                                                      |
| ------------------------------------- | --------------- | --------------------------------------------------------- |
| `background`                          | `#EEF4F9`       | app canvas (cool, bright)                                 |
| `surface`                             | `#FFFFFF`       | cards / raised content                                    |
| `onSurface` · `text` · `onBackground` | `#0F1A24`       | primary ink                                               |
| `textSecondary`                       | `#4A5A68`       | supporting text (≥4.5:1 on both white and canvas)         |
| `textDisabled`                        | `#8A97A3`       | disabled copy                                             |
| `primary`                             | `#0E6FBA`       | primary action, current selection                         |
| `onPrimary`                           | `#FFFFFF`       | ink on primary (5.25:1)                                   |
| `secondary`                           | `#33475B`       | secondary action                                          |
| `onSecondary`                         | `#FFFFFF`       | ink on secondary (9.6:1)                                  |
| `tertiary`                            | `#29ABE2`       | brand sky-blue accent + **in-progress** state (fill only) |
| `onTertiary`                          | `#05293D`       | ink on tertiary                                           |
| `success`                             | `#1B7A46`       | live / connected state                                    |
| `onSuccess`                           | `#FFFFFF`       |                                                           |
| `error`                               | `#B3261E`       | failure state                                             |
| `onError`                             | `#FFFFFF`       |                                                           |
| `disabled`                            | `#E3EAF1`       | disabled container                                        |
| `onDisabled`                          | `#6B7A88`       | disabled ink                                              |
| `outline`                             | `#7C8C9B`       | control boundaries (≥3:1 on white **and** canvas)         |
| `elevation`                           | `#33475B`       | shadow tint                                               |
| `scrim`                               | `#000000` @ 32% |                                                           |

### Dark

| Token                                 | Hex             |
| ------------------------------------- | --------------- |
| `background`                          | `#0D151E`       |
| `surface`                             | `#16202B`       |
| `onSurface` · `text` · `onBackground` | `#E7EDF3`       |
| `textSecondary`                       | `#A3B2C0`       |
| `textDisabled`                        | `#6B7A88`       |
| `primary`                             | `#63B8F6`       |
| `onPrimary`                           | `#04263F`       |
| `secondary`                           | `#C4D2E0`       |
| `onSecondary`                         | `#16202B`       |
| `tertiary`                            | `#5CC8F5`       |
| `onTertiary`                          | `#04263F`       |
| `success`                             | `#7ED9A4`       |
| `onSuccess`                           | `#05301B`       |
| `error`                               | `#FFB4AB`       |
| `onError`                             | `#450A08`       |
| `disabled`                            | `#23303D`       |
| `onDisabled`                          | `#7C8A97`       |
| `outline`                             | `#8FA3B5`       |
| `elevation`                           | `#000000`       |
| `scrim`                               | `#000000` @ 72% |

### Status colour → meaning (never colour alone)

| State                 | Colour token    | Icon (Material core) | Label (zh)      |
| --------------------- | --------------- | -------------------- | --------------- |
| Live / connected / ok | `success`       | `CheckCircle`        | 運作中 · 已連線 |
| Working / connecting  | `tertiary`      | `Refresh` (static)   | 連線中 · 辨識中 |
| Idle / stopped        | `textSecondary` | `Info`               | 已停止 · 待機   |
| Failure               | `error`         | `Warning`            | 無法使用        |
| Action / play         | `primary`       | `PlayArrow`          | 開始            |

Every status chip renders **icon + label + colour**; the label alone is
sufficient to understand it (greyscale-safe).

## Typography

The kit's scale is fixed (it is generated) and is used as-is — no new fonts:

- `h1` 24/32 Bold · `h2` 20/28 Bold · `h3` 16/24 Bold · `h4` 16/24 SemiBold
- `body1` 16/24 · `body2` 14/20 · `body3` 12/16
- `label1` 14/20 · `label2` 12/16 · `label3` 10/12 — `button` 14/20 (+1 sp tracking)
- `input` 16/24

Rules: one screen title in `h2`; card/section titles in `h4`; body copy `body1`
or `body2`; metadata/speaker tags `label2` (letter-spacing 0.5). Never set a
display size above `h1`. **Chinese body copy uses `body1`/`body2` and never
`label3` (10 sp is too small for Han glyphs).** Type is always in `sp`.

## Spacing & shape

- **Spacing scale:** 4 / 8 / 12 / 16 / 24 / 32 dp. Screen padding **16 dp**;
  card inner padding **16 dp**; item gaps **8 dp**, section gaps **24 dp**. No
  off-scale literals.
- **Radius:** the kit's `12 dp` (cards, buttons). Chips are **pill**
  (`RoundedCornerShape(50)`). Never exceed 16 dp on a card.
- **Elevation:** cards use a single soft shadow (≤ 2 dp) and **no** border —
  never border + wide shadow together. The canvas tint plus the shadow is what
  separates a card from the page.
- **Touch targets:** ≥ 48 dp for every hand-authored control (nav items,
  chips-with-action). Kit buttons are 44 dp min and are left as generated.

## Components (the shared vocabulary)

New hand-authored, screen-level components live in `ui/hub/` (kept out of the
generator-owned `ui/components/**` tree), each a PascalCase file matching its
primary composable, with a `…Defaults` object for its tokens and a `@Preview`:

- **`StatusChip`** — pill: leading `Icon` + label, coloured by state. The one way
  to show a status anywhere in the app.
- **`SectionCard`** — `Card` + `h4` title + optional trailing slot + content
  column. The one card with a header.
- **`InfoRow`** — `label` (left, `textSecondary`) ⟷ `value` (right, `text`)
  with a `copy` affordance for secrets. The one key/value row.
- **`StatTile`** — big `h1` numeral + `label2` caption, used for relay counters.
- **`EmptyState`** — icon + one-line title + one-line hint. The one empty state.
- **`ScreenHeader`** — `h2` title + optional `label1` subtitle, the one screen
  top.

Rules: reuse these (or `Button`/`Card`/`Text`) before writing a bespoke layout;
never restyle a one-off clone; every interactive component is considered in its
default / pressed / disabled / loading / error states.

## Layout

Single-column, scrollable, **max content width 560 dp** centred (so the phone
layout does not stretch on a tablet/foldable). Bottom navigation is
icon + label; the selected item uses `primary` for icon + label **and** a
`primary` indicator, never colour alone (the label is always visible).

## Motion

- Micro-interactions and state changes: **150–250 ms**, `ease-out`
  (`FastOutSlowInEasing`). No bounce, no elastic.
- Animate **state** (status colour, chip contents, counters) — not layout. Use
  `animateColorAsState` / `AnimatedContent` on the status region so a change is
  perceptible and calm.
- No page-load choreography, no entrance animation on scroll.
- Every animation respects **`prefers-reduced-motion` equivalent**: honour the
  system animator duration scale (`MotionDurationScale`); when motion is
  disabled, snap instead of tween.

## Screens (blueprints)

The app is a three-page console: 總覽, 即時 and 設定. Every screen derives its
availability from a tri-state read: `null` renders a quiet `檢查中` placeholder,
`true` renders content, and only a resolved `false` renders the calm `無法使用`
line. The placeholder must never flash an error while a read is still in flight.

- **總覽 (Dashboard)**: `ScreenHeader` ("系統總覽" / "硬體中樞狀態"), then a
  _中樞_ `SectionCard`: a status `StatusChip` (運作中 / 已停止 / 檢查中 / 無法使用),
  the bound port as a `StatTile` (未啟動 before the hub runs), and the 啟動中樞 /
  停止中樞 actions (primary `Button` + `SecondaryOutlined`). A second _熱點_
  `SectionCard` reports the user's manually-enabled hotspot: `熱點已開啟` /
  `熱點未開啟` / `檢查中` / `無法使用`, and when detected, only the gateway address
  as an `InfoRow`. Credentials are owned by Android settings and never appear
  here: there is no copy affordance and no hotspot control. When the hotspot is
  off, the copy tells the user to enable it in Android settings and come back.
- **即時 (Stream)**: `ScreenHeader` ("即時轉送" / "連線裝置與辨識結果"). _連線裝置_
  is a `StatusChip` (`已連線` only while the hub runs and at least one peer is
  bound, otherwise `待機`) plus one `TagChip` per live peer (role · device id);
  no peers renders `EmptyState` ("尚無裝置"). _轉送統計_ is five `StatTile`s in
  the fixed set 音訊 / 影像 / 手勢 / 字幕 / H.264 (audio frames, JPEG-still/image
  frames, gesture results, transcripts and offered H.264 NALs). _最新字幕_ shows
  the speaker tag as a `TagChip` over the transcript in `h1` with a
  `最後更新 HH:mm:ss` line, or `EmptyState` ("尚無字幕"). _最新手勢_ shows the
  gesture in `h2` with the same `最後更新` line, or `EmptyState` ("尚無手勢").
  Results are cleared when the hub stops or the peer list empties, so a stale
  value is never presented as live.
- **設定 (Settings)**: `ScreenHeader` ("系統設定" / "中樞與連線偏好"). _電池_ is a
  `SectionCard` with the Doze exemption `InfoRow` and the 要求排除電池最佳化
  action, or `無法使用` when the read fails. _端點_ is a `SectionCard` with the
  protocol `InfoRow` and the port when bound. Hotspot and speech-to-text
  configuration are absent, because neither is configurable at runtime.
