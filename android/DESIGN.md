---
version: alpha
name: "Attention Guard"
description: "An Android attention layer for visible campus group-chat events, shaped by the Orbit Relay identity."
colors:
  primary: "#136B5A"
  background: "#F5F6F7"
  surface: "#FFFFFF"
  ink: "#1D2927"
  secondary: "#586963"
  border: "#DCE3E0"
  graphite: "#151615"
  mint: "#63D7C8"
  peach: "#F4AC91"
  danger: "#B53F36"
  danger_soft: "#FCEDEA"
  warning: "#865E15"
  warning_soft: "#FFF3D9"
  info: "#315DA8"
  info_soft: "#EDF2FB"
typography:
  sans:
    fontFamily: "sans-serif"
  mono:
    fontFamily: "monospace"
rounded:
  DEFAULT: "0.5rem"
  sm: "0.25rem"
  md: "0.5rem"
  lg: "0.5rem"
spacing:
  page-gutter: "20px"
  section-gap: "24px"
  control-min: "48px"
  content-max: "680px"
components:
  app-shell: { backgroundColor: "{colors.background}", textColor: "{colors.ink}" }
  button: { backgroundColor: "{colors.primary}", textColor: "{colors.surface}", rounded: "{rounded.md}", height: "48px" }
  button-secondary: { backgroundColor: "{colors.surface}", textColor: "{colors.primary}", rounded: "{rounded.md}", height: "48px" }
  card: { backgroundColor: "{colors.surface}", textColor: "{colors.ink}", rounded: "{rounded.md}", padding: "16px" }
  field: { backgroundColor: "{colors.surface}", textColor: "{colors.ink}", rounded: "{rounded.md}", height: "56px" }
  label: { backgroundColor: "{colors.surface}", textColor: "{colors.secondary}" }
  divider: { backgroundColor: "{colors.border}", height: "1px" }
  brand-mark: { backgroundColor: "{colors.graphite}", textColor: "{colors.mint}" }
  brand-terminal: { backgroundColor: "{colors.peach}", textColor: "{colors.graphite}" }
  priority-p0: { backgroundColor: "{colors.danger_soft}", textColor: "{colors.danger}" }
  priority-p1: { backgroundColor: "{colors.warning_soft}", textColor: "{colors.warning}" }
  priority-p2: { backgroundColor: "{colors.info_soft}", textColor: "{colors.info}" }
  overlay: { backgroundColor: "{colors.surface}", textColor: "{colors.ink}", rounded: "{rounded.md}", padding: "8px" }
---

# Attention Guard Design System

> The `px` and `rem` units in this document are the design-token serialization
> used by the audit tooling. Android runtime values are expressed as `dp` and
> `sp` in `ag_tokens.xml` and resolved through `GuardUi`.

## Overview

### Creative North Star

**Orbit Relay**: a small relay station that receives noisy signals, selects what
deserves attention, and hands the next action back to the person. The mark uses a
graphite field, two intersecting silver and mint tracks, and one restrained peach
terminal. The same language appears in the app as short handoffs, clear status
changes, and a quiet event ledger.

### Product context and register

- **Audience and primary job:** Students and staff who need to keep up with visible
  campus group-chat notices without reading every message as an interruption.
- **Target market and evidence:** Mainland China Android usage is the current
  delivery target. The only supported chat surface is visible WeChat content;
  the app does not depend on a chat provider API.
- **Locale and language policy:** `zh-CN` is the product language for this release.
  Dates use the device locale and timezone. English is reserved for the brand name,
  endpoint names, and technical model identifiers.
- **Usage scene:** A phone held one-handed while switching between a chat app and a
  short, dense task list. The UI must remain readable with a large system font and
  a software keyboard visible.
- **Register:** Product utility with a compact, technical brand signature. It is
  not a marketing landing page and does not use decorative hero composition.
- **Memorable signature:** The Orbit Relay mark and the one-shot relay motion: a
  screen enters with a short handoff and a completed event settles with a restrained
  rebound.
- **Restraint:** No permanent animation, no luminous dashboard background, no
  oversized cards, and no UI that resembles an auto-reply assistant.
- **Anti-references:** Shield-and-eye security clip-art, generic purple AI panels,
  chat bubbles that imply sending, and glassmorphism that reduces text contrast.
- **Token ownership/runtime mapping:** Existing Android resources remain canonical.
  `app/src/main/res/values/ag_tokens.xml` owns colors, dimensions, and type sizes;
  `app/src/main/java/com/attentionguard/app/ui/GuardUi.kt` is the shared adapter used by
  activities and the overlay. This file mirrors those values and is checked by the
  premium audit; it is not a generated Android resource.

## Colors

The light surface is intentionally near-neutral (`#F5F6F7`) so event priority can
carry meaning. `#136B5A` is the action and focus color, never the only signal for a
state. P0 uses danger red, P1 uses brown-gold warning, and P2 uses blue information
tones, each with a pale surface and a text label. Graphite, mint, and peach are
reserved for the Orbit Relay mark and small brand accents. Focus uses the brand
color with a visible ripple; high-contrast system colors remain system controlled.

## Typography

The app uses Android `sans-serif` with `sans-serif-medium` for headings and actions.
The effective scale is 27sp title, 18sp heading, 15sp body, 13sp label, and 12sp
caption. Body text uses generous line spacing and high-quality line breaking so
Chinese, Latin, and numeric content can share a row. Technical identifiers such as
the DeepSeek endpoint use the same readable sans family rather than a code-heavy
display treatment.

## Layout

The main shell is a centered native column capped at 680dp, with 20dp page gutters,
24dp section rhythm, and 48dp controls. The bottom navigation is owned by
`BottomNavigationView`; scroll content is a single `ScrollView` per screen. Detail
and list states preserve the selected tab, filters, query, and scroll position when
the activity is recreated. System bars, cutouts, and the IME are applied through
`GuardUi.install` so content is never hidden behind them.

## Elevation & Depth

Static event rows are flat: a white surface, one border, and no shadow. The overlay
is the only routine surface with elevation (`6dp`) because it floats over WeChat.
Snackbar feedback is transient and app-owned. No blur, translucent full-screen
layers, or decorative shadows are used on the main pages.

## Shapes

The default radius is 8dp for fields, buttons, cards, and the overlay. Badges use a
4dp radius to read as labels rather than pills. Dividers are 1dp in `#DCE3E0`.
Icon buttons are square 48dp touch targets with a transparent ripple. Event
cards separate the detail target from completion and archive icon actions.

## Components

### Foundational visual states

Every action has a 48dp minimum target. Default controls use the surface and border
tokens; primary actions use brand with white text. Disabled controls retain their
geometry and reduce contrast without changing layout. Busy work is shown by a
stable label (`正在连接 DeepSeek…`, `整理中`) and disabled duplicate activation.
Success, warning, error, empty, and completed states always include text; color is
supporting information only.

### Buttons and actions

`GuardUi.button` is the canonical button owner. Solid brand buttons are reserved for
the primary action in a screen. Outlined buttons are used for secondary actions.
Buttons have no default Material elevation. `GuardUi.navigationRow` owns flat
two-line navigation rows; the label, state and chevron are one accessible target.
`GuardUi.metric` uses separate numeric and caption roles for the home counters.
Icon-only controls are used for navigation, close, search-clear, settings,
overlay movement and history pause/stop; each has a Chinese content description
and tooltip.

### Navigation and data display

The four destinations are **注意力**, **观测簿**, **来源**, and **我的**. Event rows
show priority, status, title, due time, and capture origin before the source group.
The ledger has a horizontally scrollable archived filter. The detail
screen adds the timeline, action, consequence, and an explicitly expandable
original-evidence section. Sources list only groups that have produced a real or
currently selected demo event.

### Forms and overlays

`GuardUi.field` is the shared field owner. API keys are masked by default and use an
accessible show/hide affordance. Settings are explicit-save: leaving dirty fields
opens an app-owned confirmation dialog. The DeepSeek test dialog states that only a
built-in sample is sent and that the request may incur provider cost. The overlay
is read-only, draggable with a reset affordance, and never edits or sends chat text.

### Capture and history workspace

`CaptureActivity` retains the same neutral unframed scroll layout. Date-range
preparation, raw-message records and diagnosis have native tabs, with one visible
scroll owner. The in-chat entry opens the history tab; the profile diagnosis entry
opens diagnostics. History uses a native date picker, a default-off
automatic-scroll switch, explicit prepare/confirm and in-chat start controls.
The overlay shows the actual conversation name and a text state with pause/stop
controls; it never suggests that all history was recovered. Message rows separate
source, day confidence and raw text; OCR results carry a verification label.
Progress counts observed pages and reports gaps rather than inventing a percent.
The event-monitoring overlay collapses to a 248dp by 56dp toolbar with five
48dp controls: move, mark current conversation, enter intent mode,
history/pause/resume and expand. History pause remains reachable when collapsed.
Intent mode uses a compact move / return to event monitoring / refresh / expand
toolbar and a persistent segmented mode control when expanded. Entering intent
mode expands the card once; automatic context updates do not reopen it.
Only the background opacity is adjustable (0-100%); text and icons stay opaque.
Changing state updates the existing window instead of removing and remounting it.
The in-chat and app-wide custom analysis results both display importance and
heuristic context confidence alongside context and source; no conversation name is
required. The app-wide custom page uses one multiline text field and a fixed
primary action. Its result has a manual-input source label and no event-card styling.

### Iconography

Lucide-derived vector icons live in `app/src/main/res/drawable/` with a consistent
stroke treatment. The launcher and in-app brand mark use the Orbit Relay bitmap at
`app/src/main/res/drawable-nodpi/ag_brand_orbit.png`; the old shield-eye mark is not
the launcher foreground.

### Motion

Motion is short, interruptible, and state-driven. Screen entry is 180ms with an
8dp vertical handoff. Completion acknowledgement is 360ms and uses a small
scale rebound. `GuardMotion` cancels on detachment and returns immediately when
system animations are disabled. There is no looping ambient animation.

### Content and data visualization

Copy names what the user can control: “标记完成”, “恢复原状态”, “测试连接”,
“清空搜索”. Errors describe the recovery action and never expose raw HTTP bodies,
keys, or captured chat text. Event scores are secondary; priority and status remain
the primary decision signals.

## Do's and Don'ts

- **Do:** Keep every event traceable to a visible message and a named source group.
- **Do:** Preserve local records when a read, parse, network, or save operation fails.
- **Don't:** Present a static strategy list as if the app had scanned background groups.
- **Don't:** imply that DeepSeek or Attention Guard can send messages on the user's behalf.

