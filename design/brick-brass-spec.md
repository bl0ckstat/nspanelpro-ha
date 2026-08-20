# Brick & Brass — Home Assistant Dashboard Theme Spec

Loft-chic warm industrial theme for dark wall panels. Terracotta for actions, brass for on-states, warm charcoal surfaces. Approved reference mock: `Loft Themes.dc.html`, option 1a.

## 1. Color tokens

| Token | Hex | Use |
|---|---|---|
| `--bg` | `#171210` | Page/panel background |
| `--card` | `#221A15` | Card and button surfaces |
| `--raised` | `#2A211A` | Secondary buttons inside cards |
| `--border` | `#4C3C2D` | 1px border on every card and inactive button |
| `--text` | `#F5EDE0` | Primary text, large numerals |
| `--muted` | `#B5A58D` | Secondary text, inactive icons |
| `--dim` | `#8C7A63` | Tertiary labels (fan/swing/mode lines) |
| `--action` | `#B75A33` | Terracotta: primary tap actions (temp −/+) |
| `--action-text` | `#F5EDE0` | Text/icons on terracotta |
| `--on` | `#C89A4B` | Brass: anything currently ON |
| `--on-fill` | `rgba(200,154,75,0.16)` | Background of active tiles |
| `--action-fill` | `rgba(183,90,51,0.18)` | Background of active chips (e.g. fan off selected) |
| `--action-active-text` | `#D98A5F` | Icon color on `--action-fill` chips |

Rules:
- Never introduce blue, pink, or saturated yellow. Cool/HVAC states also use brass or muted text, not blue.
- Exactly two accent hues: terracotta (do something) and brass (something is on).
- Warning/unavailable: use `--dim` text, no red unless destructive.

## 2. Typography

Fonts (Google Fonts):
- **Archivo** — everything: UI labels 500–600, big temperature numerals 300.
- **IBM Plex Mono** — small uppercase metadata labels and numeric chips, 400–500.

| Style | Spec |
|---|---|
| Big temperature | Archivo 300, 46px, `--text`; unit 20px `--muted` |
| Clock (status bar) | Archivo 500, 26px, tabular-nums, letter-spacing 0.02em |
| Card title (room name) | Archivo 600, 15px, `--text` |
| Secondary value (Current 24°) | Archivo 400, 13px, `--muted` |
| State label (OFF/COOL) | Archivo 500, 13px, uppercase, letter-spacing 0.08em, `--muted` |
| Metadata line | IBM Plex Mono 500, 11px, uppercase, letter-spacing 0.1em, `--dim` |
| Numeric chips (fan 1–6) | IBM Plex Mono 500, 15px, `--muted` |

Wall-panel legibility: no text under 11px; primary values 22px+; use tabular-nums on all live numbers.

## 3. Shape, spacing, depth

- Radius: 10px on all cards, buttons, chips. No pills, no circles.
- Every card and inactive button: 1px solid `--border`. Depth comes from borders + tone, not shadows.
- Card padding 18px; status bar 12px 18px.
- Grid gap 8px between buttons/chips/tiles; 14px between cards.
- Hit targets: min 52px height for chips, 56px for square buttons, 96px for room tiles.

## 4. Components

### Status bar
Full-width card: date (`--muted`, 15px) left, clock center, weather icon + hi/lo right.

### Climate card
Two columns: left = big temp + state, room name + current temp, mono metadata line (`FAN — · SWING — · NORMAL`). Right = button grid 56px squares:
- `−` / `+`: solid `--action`, icon `--action-text`, no border.
- power / fan-auto / more: `--raised` fill, 1px `--border`, icon `--muted`.
- Active mode button (e.g. snowflake when cooling): `--on-fill` bg, 1px `--on` border, icon `--on`.

### Fan speed row
7 equal chips, 52px tall. First chip = ceiling-fan OFF: MDI `mdi:ceiling-fan` icon 22px over 8px mono `OFF` label; when selected → `--action-fill` bg, 1px `--action` border, `--action-active-text`. Chips 1–6: mono numerals, `--card` bg; selected speed uses the same active treatment.

### Room/entity tiles
96px tall, icon-only 34px (MDI, filled style when on).
- Off: `--card` bg, `--border`, icon `--muted`.
- On: `--on-fill` bg, 1px `--on` border, icon `--on`.

### Sliders (lights, blinds, color temp)
Track: `--card` with `--border`; filled portion: terracotta→brass gradient (`#B75A33` → `#C89A4B`), value chip right-aligned in IBM Plex Mono. No white handles — use a 2px `--text` line as the thumb.

### Media card
Same card chrome; source name Archivo 600 15px, state Archivo 300 22px `--muted`; volume as slider above. Unavailable devices: `--dim` text, 60% opacity card.

### Weather card
Big temp Archivo 300 46px + condition; 5-day row: mono uppercase day labels (`--dim`), icon, hi/lo Archivo 13px `--muted`.

### Remote/button pages (fan remote, etc.)
Full-width rows, 56px, icon in 40px `--raised` rounded square + Archivo 500 15px label. Same on/off treatments as tiles.

## 5. Icons

Material Design Icons (MDI — native to HA). Weight consistent, ~22px in chips/buttons, 34px in tiles, 18px inline. Ceiling fans always `mdi:ceiling-fan` / `mdi:ceiling-fan-light`, never generic `mdi:fan` (reserved for AC/extractor).

## 6. States summary

| State | Treatment |
|---|---|
| Idle/off | `--card` bg, `--border`, `--muted` icon |
| On | `--on-fill` bg, `--on` border + icon (filled icon) |
| Selected control | `--action-fill` bg, `--action` border, `--action-active-text` icon |
| Primary action | solid `--action`, `--action-text` icon |
| Unavailable | `--dim` text, 60% opacity, no border change |
| Pressed | darken bg 8%, no scale animations |

## 7. Screen mapping (current dashboard → theme)

- **Bedroom / bedside / office / front panels**: status bar + climate card + fan row + tiles as specced; kill all blue `−/+` buttons → terracotta; yellow actives → brass.
- **Kitchen panel**: blind sliders use the gradient slider; warmth (K) slider likewise with bulb toggle as a square `--raised` button.
- **Fan remote page**: remote/button rows component.
- **Desktop overview**: same tokens; 3-column layout unchanged; section headers Archivo 600 15px with 18px MDI icon, `--muted`.
