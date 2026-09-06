# Design

**The visual system.** [UX.md](UX.md) settles how the interface *behaves*;
[WIREFRAMES.md](WIREFRAMES.md) settles where things *sit*. Neither says what the product
*looks like*, and both say so explicitly — `WIREFRAMES.md` opens with "these are layout and
hierarchy decisions, not visual design". This document is the missing third: colour,
type, shape, depth, motion, iconography, a component library, and a visual specification
for every screen in the application.

It is written **prompt-first**. Every component and every screen ends with a fenced
`PROMPT` block that can be pasted, unedited, into a design tool — Claude Design, Stitch,
v0, or a designer's brief — and will produce that surface at the correct fidelity. §11
carries a single master prompt that generates the whole system in one paste.

> **The theme is a colourful pastel spectrum on a light ground, and that is a change.**
> `UX.md` rule 5 currently reads "high-contrast monochrome palette, legible in direct
> sunlight". That rule is not repealed — it is satisfied by a second palette. See §1.3 and
> §10. If you read only one section before designing anything, read §1.3.

---

## Contents

| § | |
| --- | --- |
| [0](#0-how-to-use-this-document) | How to use this document |
| [1](#1-design-thesis) | Design thesis — why pastel, and why Field Mode |
| [2](#2-colour) | Colour — the nine families, tokens, contrast audit |
| [3](#3-typography) | Typography — family, scale, the Indic line box |
| [4](#4-space-shape-and-depth) | Space, shape and depth |
| [5](#5-motion-and-haptics) | Motion and haptics |
| [6](#6-iconography) | Iconography |
| [7](#7-component-library) | Component library — 28 components |
| [8](#8-the-screens) | **The screens — all twenty-five** |
| [9](#9-accessibility-conformance) | Accessibility conformance |
| [10](#10-field-mode) | Field Mode |
| [11](#11-hand-off) | Hand-off — tokens, Compose skeleton, master prompt |
| [12](#12-screen-index-and-navigation-map) | Screen index and navigation map |

**Reference viewport:** 360 × 800 dp, portrait, entry-tier handset. Every wireframe in §8
is drawn at that size. Every dp figure is a real dp figure, not an illustration.

---

## 0. How to use this document

### 0.1 The PROMPT blocks

A `PROMPT` block is self-contained. It restates the tokens it needs rather than assuming
the reader has §2 in front of them, because a design tool given one screen at a time has
no memory of the others. That redundancy is deliberate; do not "clean it up".

Paste one block per screen. Do not paste two — the tool will merge them into a single
canvas and both will be wrong.

Each block follows the same shape:

```
PROMPT — how to read one

  Design a <surface> for <product one-liner>.
  Theme    <palette, in hex>
  Type     <family and sizes>
  Layout   <exact dp>
  Content  <every string, verbatim>
  States   <each variant>
  Rules    <the non-negotiables from UX.md>
```

### 0.2 The order to build in

Follow `WIREFRAMES.md` §20 (build order), not this document's numbering. The interface is
never to be further ahead than the engine feeding it. In design terms that means:

| First | Then | Last |
| --- | --- | --- |
| §2 colour, §3 type, §4 shape — the tokens | §7 components | §8 screens |

A screen designed before its components exist produces a one-off, and the codebase already
demonstrates what that costs: eleven of thirteen UI files re-declare their own private
copy of `Color(0xFF101010)`, and one of them has already drifted to a different red.

### 0.3 What this document does not settle

- **Interaction.** Modes, floor control, the alert-delivery sequence, pairing policy —
  [UX.md](UX.md).
- **Layout and hierarchy.** Band heights, what sits above what — [WIREFRAMES.md](WIREFRAMES.md).
- **Copy that carries a technical claim.** Byte counts, latency figures and licence
  strings come from [PROTOCOL.md](PROTOCOL.md), [EVALUATION.md](EVALUATION.md) and
  [LICENSES.md](../LICENSES.md). Where this document quotes one it is illustrative.

Where this document and `UX.md` disagree on behaviour, `UX.md` wins. Where they disagree on
appearance, this one does.

---

## 1. Design thesis

### 1.1 What the product is, in one paragraph

Two people hold two ordinary phones with no SIM, no tower and no cloud. One speaks Tamil;
the other hears Tamil. What crosses the link is not audio — the speech is recognised on the
sending handset, the *meaning* is packed into thirteen to sixty-one bytes, broadcast, and
re-synthesised on the receiving handset in whatever language that handset is set to. The
users are disaster-relief operators: gloved, in sunlight, sometimes unable to read, often
unable to look at the screen at all.

### 1.2 What "premium" has to mean here

Premium in this product is **not** thin type, generous whitespace and a muted palette. Every
one of those things makes the product worse for Asha, who cannot read, and for Meena, who is
wearing gloves in the dark. The premium signals available to us are the ones that survive
the constraints:

| Signal | How it is expressed here |
| --- | --- |
| **Confidence** | Large, unhesitating targets. The transmit control is a third of the screen and looks like it means it |
| **Calm** | A warm off-white ground, soft tinted shadows, no hairline greys fighting for attention |
| **Precision** | The instrumentation strip is a designed object — monospaced, aligned, tabular — not a debug afterthought |
| **Colour with a job** | Nine pastel families, each owning exactly one meaning. Nothing is coloured because it looked nice |
| **Restraint in motion** | Nothing moves that is not reporting a state change |
| **Honesty** | Empty is drawn as empty. A number that has not been measured is drawn as `—`, never `0` |

The last row is the one that separates this product from a template. The application already
refuses to show a median below one hundred utterances and prints `NOT REPORTABLE` instead.
The design must make that refusal look intentional and expensive, not like a bug.

### 1.3 Why pastel, and why that does not break rule 5

`UX.md` §4 rule 5 says: *"High-contrast monochrome palette, legible in direct sunlight.
Colour is used only for state (link, alert, floor), never as the sole carrier of
information."*

That rule exists for a real reason and is not being softened. It is being **split into two
palettes that share every other token**:

| | **Spectrum** — the default | **Field** — one tap away |
| --- | --- | --- |
| Ground | Warm off-white `#FDFCFB` | Pure white `#FFFFFF` |
| Surfaces | Nine pastel families | None — everything is ground |
| Text | Family ink, 6.8–9.4 : 1 | `#101010`, 19.0 : 1 |
| Depth | Soft tinted shadows | Hairline rules only |
| State colour | Family signal colours | `Ok` / `Warn` / `Alert` only |
| Legible in direct sun | Adequate | **Designed for it** |
| Who it is for | Everyone else, and the jury | The operator standing in a flood at noon |

Both palettes drive the *same* geometry, the *same* type scale, the *same* icons and the
*same* component anatomy. Switching is a colour swap and nothing else — no reflow, no
control moves, no muscle memory lost. That is the whole design constraint on Field Mode and
it is why the two palettes are specified together rather than one being bolted on later.

Three further guards keep rule 5's *intent* intact even in Spectrum:

1. **Colour is never the only carrier.** Every coloured state also has a glyph and a word.
   Delete all colour from any screen in §8 and its meaning must survive. That is testable:
   render greyscale and read it.
2. **Ink is family ink, not pastel-on-pastel.** Text sits at 6.8 : 1 or better in every
   case (§2.5). The pastel is the *surface*; the ink on it is nearly as dark as
   monochrome ink.
3. **The alert path does not get softer.** Incoming alerts stay a saturated full-bleed red
   in both palettes. An alert is the only message type that can cause physical harm if it
   is missed, and it is not a place for a pastel.

### 1.4 The five design laws

Normative. A review may reject a design for violating any of these, exactly as `UX.md` §4
allows a review to reject a change.

1. **Greyscale test.** Any screen whose meaning collapses when colour is removed has failed.
   `UX.md` rule 3 says the same thing about text; this is its colour twin.
2. **One family, one job.** A pastel family means the same thing on every screen. Mint is
   never decorative. Blush is never "just a nice pink".
3. **Two palettes, one geometry.** No layout, size, position or component anatomy may differ
   between Spectrum and Field.
4. **Nothing shrinks under stress.** Confirmation, refusal and error states never make the
   safe target smaller than the unsafe one, and never reduce a touch target below 64 dp.
5. **Absent is not zero.** A value that has not been measured is `—`. An empty list is a
   drawn empty state, never a blank region.

---

## 2. Colour

### 2.1 The spectrum

Nine families. Each is a triple — **surface**, **tint**, **signal** — plus an **ink** that
is guaranteed legible on both the surface and the ground.

| Family | Surface | Tint | Signal | Ink | Owns |
| --- | --- | --- | --- | --- | --- |
| **Periwinkle** | `#E8EAFF` | `#C7CEFF` | `#4F5BD5` | `#2F3A8F` | Primary. Transmit, focus, the app's own voice |
| **Mint** | `#E3F7EC` | `#B8ECD0` | `#1B7F3B` | `#14532D` | Good. Link up, delivered, all-clear, verified |
| **Sky** | `#E4F3FE` | `#BEE3FB` | `#0369A1` | `#0C4A6E` | Information. Metrics, instrumentation, evidence |
| **Aqua** | `#DFF7F7` | `#B5EDEC` | `#0E7C7B` | `#0F4C4C` | Incoming. Receiving, speaking aloud, replay |
| **Lilac** | `#F0EBFE` | `#D7C9FC` | `#7C3AED` | `#4C1D95` | Language and identity. Packs, units, node names |
| **Orchid** | `#FBEAFB` | `#F0CDF1` | `#A21CAF` | `#701A75` | Templates and cross-language delivery |
| **Butter** | `#FEF6DC` | `#FBE7A6` | `#B45309` | `#713F12` | Attention. Pending, queued, thermal, not-yet |
| **Apricot** | `#FFEEDF` | `#FFD5B0` | `#C2410C` | `#7C2D12` | Degraded, self-recovering |
| **Blush** | `#FFE7EA` | `#FFC7CE` | `#BE123C` | `#9F1239` | Alert, failure, unsecured, destructive |

Read the triple as: **surface** fills a region, **tint** draws its border or its secondary
fill, **signal** draws the dot, icon or bar that must be seen from arm's length, and **ink**
is the text.

### 2.2 Neutrals

| Token | Value | Note |
| --- | --- | --- |
| `Paper` | `#FDFCFB` | Warm off-white. Pure white shimmers under direct sun; `Tokens.kt` already avoids pure black for the same reason |
| `Ink` | `#101010` | Unchanged from `Tokens.kt`. 18.6 : 1 on Paper |
| `Muted` | `#5F5F5F` | Secondary text. 6.2 : 1 on Paper |
| `Rule` | `#E7E4E0` | Hairlines and dividers, warm to match Paper |
| `InkPaper` | `#FFFFFF` | Foreground on an inverted or signal-filled surface |
| `Scrim` | `#101010` at 48 % | Behind the full-screen alert only |

### 2.3 The semantic map

This is the normative table. A colour used outside this map is a defect.

| Meaning in the product | Family | Where it appears |
| --- | --- | --- |
| Transmit control, idle | Periwinkle surface + tint border | Band C |
| Transmit control, live | Periwinkle signal fill, `InkPaper` text | Band C inverted |
| Floor held by a peer | Butter surface, dashed Butter tint border | Band C busy |
| Link up · delivered `✓✓` · ALL CLEAR | Mint | Band A pill, message marks, alert tile |
| Link down · no peers | Blush | Band A pill |
| Instrumentation · metrics · byte counts | Sky | Band F, metrics screen, evidence lines |
| Receiving · speaking aloud · replay `⟲` | Aqua | Band C receive card, log replay |
| Language rows · unit names · node IDs | Lilac | Band B, language screen, pairing list |
| Template-coded message · cross-language render | Orchid | Log evidence line, alert compose tiles |
| Queued `○` · pending · thermal · loading | Butter | Log rows, banners, splash |
| Degraded, recovers itself | Apricot | Banner |
| Degraded, needs a person · unsecured · delete | Blush | Banner, destructive rows |
| Alert, outgoing and incoming | Blush signal `#BE123C`, full-bleed `#C62828` | Band D, alert screens |

Two rules on top of the map:

- **A screen shows at most three families**, plus neutrals. Four is a defect; the reader
  stops being able to tell which colour was the important one.
- **Blush and Apricot are never decorative.** If a surface is Blush, something is wrong.

### 2.4 Where colour is forbidden

| Never coloured | Why |
| --- | --- |
| Body copy | Ink or Muted only. Coloured running text is unreadable at 200 % scale in sun |
| Whole-screen backgrounds | Except the full-bleed alert. Paper is the ground everywhere else |
| Icons that do not carry state | A back chevron is Ink. A link dot is Mint or Blush |
| Anything using colour alone | Law 1 |
| The recognised-text container | The operator is verifying what the machine heard; tinting it implies a judgement the system has not made |

### 2.5 Contrast audit

Computed, not estimated. WCAG 2.1 relative luminance. AA body needs 4.5 : 1, AA large
(≥ 18.66 px bold or ≥ 24 px) needs 3 : 1, non-text UI needs 3 : 1.

**Ink on its own surface, and on Paper:**

| Family | Ink on surface | Ink on Paper | Verdict |
| --- | --- | --- | --- |
| Periwinkle | **8.31 : 1** | 9.65 : 1 | AAA |
| Mint | **8.15 : 1** | 8.89 : 1 | AAA |
| Sky | **8.35 : 1** | 9.23 : 1 | AAA |
| Aqua | **8.70 : 1** | 9.49 : 1 | AAA |
| Lilac | **9.40 : 1** | 10.69 : 1 | AAA |
| Orchid | **8.72 : 1** | 9.79 : 1 | AAA |
| Butter | **8.02 : 1** | 8.46 : 1 | AAA |
| Apricot | **8.28 : 1** | 9.14 : 1 | AAA |
| Blush | **6.82 : 1** | 7.82 : 1 | AAA |

**Signal colours — dots, bars, icons, borders, and as a fill under white text:**

| Family | Signal on Paper | White on signal | Verdict |
| --- | --- | --- | --- |
| Periwinkle `#4F5BD5` | 5.41 : 1 | 5.54 : 1 | AA both ways |
| Mint `#1B7F3B` | 4.94 : 1 | 5.07 : 1 | AA both ways |
| Sky `#0369A1` | 5.79 : 1 | 5.93 : 1 | AA both ways |
| Aqua `#0E7C7B` | 4.89 : 1 | 5.01 : 1 | AA both ways |
| Lilac `#7C3AED` | 5.56 : 1 | 5.70 : 1 | AA both ways |
| Orchid `#A21CAF` | 6.17 : 1 | 6.32 : 1 | AA both ways |
| Butter `#B45309` | 4.90 : 1 | 5.02 : 1 | AA both ways |
| Apricot `#C2410C` | 5.05 : 1 | 5.18 : 1 | AA both ways |
| Blush `#BE123C` | 6.13 : 1 | 6.29 : 1 | AA both ways |

**Neutrals:** `Ink` on Paper 18.57 : 1 · `Muted` on Paper 6.23 : 1 · `Rule` on Paper
1.24 : 1 (a hairline, non-informational, exempt).

**The one number to watch.** Surfaces sit at 1.05–1.50 : 1 against Paper. That is
deliberate — a pastel surface that met 3 : 1 against the ground would not be a pastel. It
also means **a pastel surface may never be the only thing distinguishing two regions**.
Every pastel surface in §7 and §8 is paired with either a tint border, a signal element, or
a change in type weight. Butter is the weakest at 1.20 : 1 and therefore always carries a
1 dp Butter-tint border.

### 2.6 The palette this replaces

`app/src/main/kotlin/org/itantra/app/ui/Tokens.kt` today defines nine colours. They are not
discarded — they become the Field palette (§10). The four state colours (`Ok #1B7F3B`,
`Warn #F2B705`, `Alert #B3261E`, `AlertField #C62828`) survive into Spectrum too: `Ok` *is*
Mint signal, and `AlertField` *is* the full-bleed alert.

There is one live defect to fix while doing this. `DegradedBanner.kt:175` declares
`Danger = Color(0xFFEF6C60)` — a different red from the `0xFFB3261E` every other file uses.
That is exactly the drift `Tokens.kt`'s own KDoc warns about, and it is why §11 requires a
single token object with no private duplicates.

---

## 3. Typography

### 3.1 The families

Three, and no more. Every one of them must ship in the APK or already exist on the device,
because the installer budget is 30 MB (`REQUIREMENTS.md` N2) and 27.1 MB of it is already
spent.

| Role | Family | Why |
| --- | --- | --- |
| **UI** | **Inter** — variable, Latin subset only, ~180 KB | Tall x-height survives sunlight and 200 % scale. Its tabular figures are what makes the instrumentation strip align. If the budget will not carry it, the system default (Roboto) is an acceptable fallback and nothing else in this document changes |
| **Indic** | **Noto Sans Devanagari / Bengali / Tamil / Telugu / Gujarati / Kannada / Malayalam / Oriya** — whatever the platform already has | **Never bundled.** Ten scripts of Noto is far over budget. The platform fallback chain resolves these; the design's job is to size the line box for them, not to ship them |
| **Numeric** | **JetBrains Mono**, or the platform monospace | Byte counts, millisecond figures, hashes, node IDs. Anything a jury will read a number off |

`FontFamily.Monospace` is already in use in five files for exactly this purpose. That
instinct was right; this formalises it.

### 3.2 The scale

Extends the five sizes in `Tokens.kt` to nine. Sizes are `sp` so system font scaling applies
(rule 9).

| Token | Size | Weight | Line height | Tracking | Used for |
| --- | --- | --- | --- | --- | --- |
| `Display` | 40 sp | 700 | 1.15 | −0.02 em | Alert screen headline, first-run mark |
| `Headline` | 28 sp | 700 | 1.20 | −0.01 em | Confirmation text, alert body |
| `Title` | 22 sp | 700 | 1.25 | 0 | Screen headings, PTT label |
| `Subtitle` | 18 sp | 600 | 1.30 | 0 | Language native script, section leads |
| `Body` | 16 sp | 400 | 1.45 | 0 | Message text, running copy |
| `Label` | 15 sp | 600 | 1.30 | +0.01 em | Buttons, tiles, choice rows |
| `Status` | 14 sp | 500 | 1.35 | 0 | Band A, ages, secondary rows |
| `Caption` | 13 sp | 400 | 1.40 | 0 | Notes, licence lines, helper text |
| `Instrument` | 12 sp | 500 mono | 1.30 | +0.02 em | Band F, evidence lines, byte counts |

Nothing below 12 sp exists. `MetricsScreen.kt` currently uses 11 sp for one label; that is
the floor being broken and it should move to `Caption`.

### 3.3 The Indic line box — the rule that is currently declared and never applied

`Tokens.kt:122` declares `INDIC_LINE_HEIGHT = 1.4f`. A grep for it returns exactly one hit:
its own declaration. No `lineHeight` is set on any `Text` in the application. That means
Devanagari matras, Malayalam conjuncts and Odia's above-baseline marks are relying on the
default line box, and clipping.

**Normative.** Every text container that can hold a non-Latin script MUST reserve
`1.4 ×` the Latin line height, and MUST size by `heightIn(min = …)` rather than a fixed
height. In practice:

| Text | Latin line box | Indic line box |
| --- | --- | --- |
| `Body` 16 sp | 23 sp | **32 sp** |
| `Subtitle` 18 sp | 23 sp | **36 sp** |
| `Headline` 28 sp | 34 sp | **56 sp** |
| `Display` 40 sp | 46 sp | **80 sp** |

The languages that break first are Malayalam (stacked conjuncts) and Odia (above-baseline
marks). Test with `ଓଡ଼ିଆ` and `മലയാളം`, not with `हिन्दी` — Devanagari is the forgiving one.

### 3.4 Script-first, gloss-second

Every language is written **in its own script first, English gloss second**, at a size
difference large enough that the script is the thing being read.

```
  ┌──────────────────────────────────────┐
  │  ଓଡ଼ିଆ                    Subtitle 18 │   ← the operator reads this
  │  Odia · voice CC-BY-NC     Caption 13 │   ← the reviewer reads this
  └──────────────────────────────────────┘
```

`SettingsScreens.kt:108` already does this at 20 sp / 13 sp. Standardise on 18 / 13.

### 3.5 Numbers

Everything a jury reads a number off is monospaced, tabular-figured and right-aligned in its
column. That covers band F, the metrics stage table, the log evidence lines and the storage
sizes.

```
  STT   210 ms  ·  LINK    40 ms  ·  TTS  180 ms
  TOTAL 780 ms  ·  RTF   0.22     ·  CPU   1.8 %
        ^^^^^^          ^^^^             ^^^^^
        columns align because the figures are tabular
```

Proportional figures make `1` narrower than `8`, the columns drift, and the strip stops
looking measured. It is a small thing that does a large amount of the persuasive work.

---

## 4. Space, shape and depth

### 4.1 Space

Unchanged from `WIREFRAMES.md` §1. Restated so this document is usable alone.

| Token | Value |
| --- | --- |
| `Grid` | 8 dp — every dimension is a multiple |
| `ScreenMargin` | 16 dp |
| `GutterTight` / `Gutter` / `GutterWide` | 8 / 16 / 24 dp |
| `TouchTarget` | **64 dp** — above the 48 dp platform minimum, because gloves |
| `ConfirmTarget` | **96 dp** — alert tiles, RETAKE/SEND, ACKNOWLEDGE, START |
| `StatusBand` (A) | 56 dp |
| `ModeBand` (B) | 48 dp |
| `SecondaryAction` (D) | 72 dp |
| `InstrumentBand` (F) | 40 dp |
| Transmit (C) | ≥ 33 % of screen height |

Sizes are always `heightIn(min = …)`, never `size(…)`. The codebase has already been bitten
by this once: a fixed `size(64.dp)` clipped the replay glyph at 200 % text scale.

### 4.2 Shape

Today the app uses 6 dp, 8 dp and 12 dp radii chosen at random across five files. One ramp
replaces all three.

| Token | Radius | Applied to |
| --- | --- | --- |
| `RadiusSharp` | 4 dp | Chips, pills, the level meter segments, tags |
| `RadiusBase` | 10 dp | Rows, list cards, banners, choice rows, message cards |
| `RadiusLarge` | 20 dp | The transmit panel, alert tiles, the receive card, primary buttons |
| `RadiusFull` | 50 % | Link dot, confidence dots, avatar-less unit marks |

Nothing is a square corner except the full-bleed alert, which has no corners at all.

### 4.3 Depth

The current app has **no elevation anywhere** — no `shadow()`, no `Card`, no elevation
parameter. On a monochrome ground that works, because a 1 dp rule is enough separation. On
a pastel ground it does not: two pastel surfaces at 1.1 : 1 against the ground need
something to say which one is on top.

Two steps, and both are *tinted* shadows — the shadow takes the family's ink at low alpha,
never neutral black, because a grey shadow under a pastel reads as dirt.

| Token | Spec | Applied to |
| --- | --- | --- |
| `Flat` | no shadow, 1 dp `Rule` or family-tint border | Rows, list items, banners, the instrument strip |
| `Raised` | `y 2 dp · blur 8 dp · family ink @ 8 %` | Message cards, language rows, alert tiles, pack rows |
| `Lifted` | `y 6 dp · blur 20 dp · family ink @ 12 %` | The transmit panel, the receive card, the confirmation card |

That is the entire depth vocabulary. There is no third step and there are no overlays,
because the application has no dialogs and no bottom sheets by design — `SecurityBanners.kt`
argues the point directly: a dialog "is acknowledged once and forgotten" where the condition
persists.

### 4.4 Borders

| Weight | Meaning |
| --- | --- |
| 1 dp `Rule` | Neutral separation |
| 1 dp family tint | A pastel surface that needs an edge (mandatory on Butter, see §2.5) |
| 2 dp family signal | **Selected.** The current language, the current mode, the current transport |
| 3 dp family signal | The transmit panel only |
| 2 dp dashed family tint | Unavailable / blocked. The busy transmit panel |

Selection is 2 dp signal **and** a filled radio glyph **and** a surface change. Three
carriers, because rule 3 and law 1 both apply.

---

## 5. Motion and haptics

### 5.1 Durations and easing

| Token | Duration | Curve | Used for |
| --- | --- | --- | --- |
| `Instant` | 0 ms | — | Floor seize, alert display. Never animated |
| `Quick` | 120 ms | `easeOut` | Press feedback, dot state, chip change |
| `Standard` | 200 ms | `easeInOut` | Banner in/out, panel fill, screen transition |
| `Considered` | 320 ms | `easeInOut` | Palette swap, first-run to operating |
| `Continuous` | — | linear | Level meter, progress, the alert repeat pulse |

**Nothing exceeds 320 ms.** On an entry-tier handset under thermal load, a longer animation
reads as the app having hung — and thermal load is a state this product is expected to be in.

### 5.2 The transmit choreography

This is the most important motion in the product and the one place where getting it wrong
loses words. `transmitting` and `listening` are **separate booleans in `OperatingState` and
the distinction is load-bearing**: a press seizes the floor instantly, but the microphone
takes a moment to open, and anything said in that gap is lost.

| Step | Visual | Timing | Haptic |
| --- | --- | --- | --- |
| Finger down | Panel scales to 0.98, border 3 dp Periwinkle signal | `Quick` | — |
| Floor seized | Panel fills Periwinkle signal, label → `opening the microphone…` at `Body` | `Instant` | Single sharp tick |
| Microphone open | Label → `S P E A K   N O W` at `Title`, level meter appears | `Quick` | Double tick |
| Speaking | 12-segment meter tracks input, partial hypothesis fades in below | `Continuous` | — |
| Release | Panel returns to Periwinkle surface over `Standard`, partial freezes then clears | `Standard` | Soft release thud |
| Refused (floor held) | Panel does **not** change. A 3-frame horizontal shake, 8 dp, 120 ms | `Quick` | Short double buzz — a refusal, never a dialog |

**Do not collapse the first two rows into one.** Inverting the panel on press alone tells the
operator the microphone is open when it is not, which is precisely the opposite of the truth.
The codebase learned this the hard way and the design must not undo it.

### 5.3 The level meter

Twelve segments, `RadiusSharp`, 4 dp gap. Fill from the left in Periwinkle signal on the
inverted panel, or `InkPaper` at 90 % — whichever the panel state calls for.

Attack is immediate; release decays over 180 ms so the meter looks like it is measuring
rather than flickering. Peak-hold segment stays 400 ms at 60 % alpha. Below −40 dBFS, one
segment stays lit — a completely dark meter is indistinguishable from a broken microphone.

### 5.4 Banners

Degraded banners slide down from under band B over `Standard` and push content, never
overlay it. An overlaid banner covers the transmit control, and the transmit control is what
the operator is reaching for while the banner is telling them something is wrong.

They leave the same way. The `UNSECURED` banner never leaves.

### 5.5 The alert

No entrance animation at all. The full-bleed alert appears on the frame it is triggered. It
then pulses its border between `#C62828` and `#9F1239` at 1 Hz for the duration of the two
spoken repeats — the only continuous decorative motion in the product, justified because
`UX.md` §3 step 5 requires reinforcement across redundant channels for a noisy environment
and a hearing-impaired operator.

### 5.6 Haptics

`UX.md` rule 2: every state change is confirmed by haptics **and** a spoken cue, never by a
text dialog alone.

| Event | Pattern |
| --- | --- |
| Floor seized | One 20 ms sharp tick |
| Microphone open | Two 15 ms ticks, 60 ms apart |
| Transmit released | One 40 ms soft thud |
| Floor refused | Two 30 ms buzzes, 80 ms apart |
| Message delivered | One 12 ms tick |
| Alert sent | Three 40 ms pulses |
| Alert received | The `UX.md` §3 vibration pattern, continuous until acknowledged |
| Pairing succeeded | One 20 ms tick, then the new unit count spoken aloud |
| Destructive action | One 60 ms buzz before the action, not after |

### 5.7 Reduced motion

When the system reduced-motion setting is on: no scale, no shake, no pulse. Everything
becomes an opacity cross-fade at `Quick`. The alert border pulse becomes a static 3 dp
border. Haptics are **not** reduced — they are the non-visual channel and are load-bearing
for exactly the users most likely to have reduced motion enabled.

---

## 6. Iconography

### 6.1 The situation today

Every icon in the application is a Unicode glyph rendered inside a `Text`: `☰` `‹` `((•))`
`▾` `⟲` `⚠` `●` `○` `✓✓` `✕` `✚` `▲` `≈` `⌂` `⌖` `🎤` `⏻` `⇢` `⛔` `⇄` `🌡` `▤` `⤓`.

That was a defensible call — it costs nothing in APK size and there is no
`material-icons-extended` dependency to carry. It has two problems: the glyphs render
differently on every vendor's font stack, and the emoji ones (`🎤` `🌡`) render in full
colour, which breaks the palette outright.

### 6.2 The set

Draw twenty-four icons as vector drawables. At 24 dp each they cost roughly 20 KB total,
which the budget carries.

| Icon | Replaces | Family |
| --- | --- | --- |
| Transmit `((•))` | `((•))` | Periwinkle |
| Open line | `((( )))` | Periwinkle |
| Menu | `☰` | Ink |
| Back | `‹` | Ink |
| Chevron | `›` | Muted |
| Caret | `▾` | Lilac |
| Replay | `⟲` | Aqua |
| Speaking | new — sound arcs | Aqua |
| Link up | `●` | Mint signal |
| Link down | `○` | Blush signal |
| Delivered | `✓✓` | Mint signal |
| Sent | `✓` | Muted |
| Queued | `○` | Butter signal |
| Failed | `✕` | Blush signal |
| Alert | `⚠` | Blush signal |
| Medical | `✚` | Blush |
| Fire | `▲` | Blush |
| Flood | `≈` | Blush |
| Evacuate | `⌂` | Blush |
| Extract | `⌖` | Blush |
| All clear | `✓` | Mint |
| Microphone | `🎤` | Apricot |
| Thermal | `🌡` | Apricot |
| Storage | `▤` | Butter |
| Bluetooth | `⇄` | Sky |
| Lock open | `⛔` | Blush |
| Download | `⤓` | Lilac |
| Delete | `🗑` | Blush |

### 6.3 Drawing rules

| Rule | Value |
| --- | --- |
| Grid | 24 × 24 dp, 2 dp safe margin |
| Stroke | 2 dp, round cap, round join |
| Optical size | 32 dp minimum on screen (`UX.md` rule 3 — icons carry primary meaning) |
| Fill | Stroke-only, except state dots and the alert glyph, which are solid |
| Corner radius | 2 dp on any drawn corner — matches `RadiusSharp` at icon scale |
| Colour | One family signal per icon. Never two colours in one icon |
| Emoji | **Never.** No emoji in any surface, including notifications |

### 6.4 The pastel chip

The recurring container pattern. An icon on a family surface, 40 dp, `RadiusBase`, no border
unless the family is Butter.

```
   ┌──────┐
   │  ◈   │   40 dp · family surface · RadiusBase
   └──────┘   icon 24 dp in family signal, optically centred
```

This is what makes settings rows, degraded banners, alert tiles and pack rows feel like one
system. Where it appears is specified per component in §7.

### 6.5 The app mark

The launcher icon already exists and is good: a filled dot with four stroked arcs forming a
`((•))` transmit mark, adaptive, with a `<monochrome>` layer for Android 13 themed icons.
`app/src/main/res/drawable/ic_launcher_foreground.xml`.

**One change.** The background is currently `#FFFFFF`. Make it Periwinkle surface `#E8EAFF`,
and keep the foreground at `Ink #101010`. That is the whole of the brand expression, and the
monochrome layer is untouched so themed icons still work.

```
PROMPT — icon set

  Draw a 28-icon set for an offline emergency walkie-talkie app used by
  disaster-relief operators in gloves and direct sunlight.

  Grid     24 x 24 dp, 2 dp safe margin
  Stroke   2 dp, round cap, round join, 2 dp corner radius
  Style    stroke-only line icons; only state dots and the alert triangle are filled
  Colour   single flat colour per icon, no gradients, no two-tone, NO EMOJI
  Weight   optically consistent — the transmit icon and the delete icon must
           look like they were drawn by the same hand on the same day

  Icons: transmit (a dot with two concentric broadcast arcs each side),
  open line (three nested arcs), menu, back chevron, forward chevron,
  dropdown caret, replay (circular arrow), speaking (dot with sound arcs),
  link up (filled dot), link down (hollow dot), delivered (double tick),
  sent (single tick), queued (hollow circle), failed (cross),
  alert (triangle with exclamation), medical (cross), fire (flame),
  flood (three waves), evacuate (house with arrow), extract (crosshair),
  all clear (tick in circle), microphone, thermometer, storage (stacked discs),
  bluetooth, unlocked padlock, download (arrow into tray), delete (bin).

  Deliver as a single sheet, 24 dp cells, labelled, on #FDFCFB.
```

---

## 7. Component library

Twenty-eight components. Each one gives anatomy, every state it has, the tokens it uses, and
its accessibility contract. Build these before building any screen in §8.

Throughout: **S** = family surface, **T** = family tint, **G** = family signal, **I** =
family ink.

---

### 7.1 Link pill

Band A, right. The single most-looked-at status in the product.

```
  ┌─────────────────┐        ┌─────────────────┐
  │ ●  LINK OK      │        │ ○  NO LINK      │
  └─────────────────┘        └─────────────────┘
    Mint S, Mint T border      Blush S, Blush T border
    dot Mint G, text Mint I    dot Blush G, text Blush I
```

Height 32 dp · `RadiusFull` · padding 12/8 dp · dot 10 dp · text `Status` 14 sp 600.

| State | Surface | Dot | Text |
| --- | --- | --- | --- |
| Link up | Mint S | Mint G, filled | `LINK OK` |
| Link down | Blush S | Blush G, hollow 2 dp ring | `NO LINK` |
| Reconnecting | Apricot S | Apricot G, filled, 1 Hz opacity 40→100 % | `RECONNECTING` |

**A11y:** one sentence — `"Link is up"` / `"No link. Reconnecting."` The dot is decorative.

---

### 7.2 Unit and node identity

Band A, left of the pill. `BASE · node 01`, `Status` 14 sp. Unit name in Lilac I 600, the
`· node 01` in Muted 400. Never truncates; the pill shrinks first.

---

### 7.3 Unit-count chip

`6 units` · `6 units · 2 queued`.

Height 28 dp · `RadiusFull` · Lilac S · Lilac I · `Status` 14 sp 600. When `queued > 0` the
queued half is a second chip in Butter S / Butter I, so the two facts are separably readable.

**A11y:** `"Six units in range, two messages queued"` — words, never digits and symbols.

---

### 7.4 Language selector — compact

Band B, right. The only overlay in the entire application.

```
  ┌────────────────────────┐
  │  हिन्दी              ▾  │   48 dp · Lilac S · Lilac T 1 dp
  └────────────────────────┘   script Subtitle 18 sp · caret Lilac G
```

Open, it becomes a dropdown of the ten languages: native script `Subtitle` 18 sp over
English gloss `Caption` 13 sp, current row carries a Lilac G tick and Lilac S fill. Row
height 64 dp min. The menu is `Raised` with Lilac ink shadow.

---

### 7.5 Language row — full

Used on the LANGUAGE screen. Taller, and carries availability.

```
  ┌──────────────────────────────────────────────┐
  │  ●  ଓଡ଼ିଆ                            ⤓ 66 MB  │  72 dp min
  │     Odia · voice CC-BY-NC                    │  Lilac S when selected
  └──────────────────────────────────────────────┘  2 dp Lilac G border
```

| State | Radio | Surface | Trailing |
| --- | --- | --- | --- |
| Selected, installed | Lilac G filled | Lilac S, 2 dp Lilac G | — |
| Installed, not selected | Lilac G ring | Paper | — |
| Not installed | Muted ring | Paper | `⤓ 66 MB` in Lilac G |
| Text-only (no voice) | Muted ring | Paper | `Text only` chip, Butter S |
| Non-commercial voice | as above | as above | `CC-BY-NC` chip, Blush S |

The last two are not decoration. `canSpeak` and `recognition` fail **independently** — a
language can be understood but not spoken back, and four of the ten currently are. The row
must say which.

---

### 7.6 Transmit panel — the primary control

The largest object in the product. ≥ 33 % of screen height, `RadiusLarge`, `Lifted`.

```
  IDLE                          LIVE                        BUSY
  ┌────────────────────┐        ┏━━━━━━━━━━━━━━━━━━━━┓      ┌ ─ ─ ─ ─ ─ ─ ─ ─ ┐
  │                    │        ┃                    ┃
  │       ((•))        │        ┃       ((•))        ┃      │     ((•))       │
  │                    │        ┃  ▂▄▆█▆▄▂▁▂▄▆       ┃        dimmed 40 %
  │   PUSH TO TALK     │        ┃  S P E A K  N O W  ┃      │  CHANNEL BUSY   │
  │                    │        ┃                    ┃         MEENA
  └────────────────────┘        ┗━━━━━━━━━━━━━━━━━━━━┛      └ ─ ─ ─ ─ ─ ─ ─ ─ ┘
  Periwinkle S                  Periwinkle G fill            Butter S
  3 dp Periwinkle G             InkPaper text                2 dp dashed Butter T
  icon 64 dp Periwinkle G       icon 64 dp InkPaper          icon 64 dp Butter G @ 60 %
  label Title 22 sp             label Title 22 sp            label Title 22 sp Butter I
```

Five states, and the middle two must not be merged:

| State | Icon | Label | Fill |
| --- | --- | --- | --- |
| Idle | `((•))` Periwinkle G | `PUSH TO TALK` `Title` | Periwinkle S |
| Pressing | scales 0.98 | `PUSH TO TALK` | Periwinkle S, 3 dp G |
| **Floor seized, mic not open** | `((•))` InkPaper | `opening the microphone…` `Body` 16 sp | **Periwinkle G** |
| **Live** | `((•))` InkPaper + meter | `S P E A K   N O W` `Title` | **Periwinkle G** |
| Busy (peer holds floor) | dimmed | `CHANNEL BUSY` + peer name | Butter S, dashed |
| Loading models | dimmed | `LOADING MODELS · 1.4 s` | Butter S |

**A11y:** press-and-hold is unreachable via TalkBack. The panel MUST carry a custom
`onClick(label = "Send")` action in addition to the gesture. It is a polite live region so
the label change is announced.

---

### 7.7 Level meter

12 segments · 8 dp wide · 24 dp tall · 4 dp gap · `RadiusSharp`. Filled segments `InkPaper`
at 90 %, unfilled `InkPaper` at 20 %. Peak-hold segment at 60 % for 400 ms.

Semantics **cleared** — twelve blocks announced individually is noise, and the meter's
information is already carried by the label change.

---

### 7.8 Confidence dots

```
   ●●●○   high        ●●○○   fair        ●○○○   low
```

Four dots, 8 dp, 6 dp gap. Filled in the family signal of the surface they sit on. These are
the two `CONFIDENCE` bits from [PROTOCOL.md §7](PROTOCOL.md#7-flag-bits), rendered before
transmission.

**A11y:** `"high confidence"` — never `"three of four dots"`.

---

### 7.9 Partial hypothesis strip

Appears only while transmitting, between bands C and D.

```
  ┌──────────────────────────────────────────────┐
  │  "need help now, two inj…"          ●●●○     │  Sky S, Sky T 1 dp
  └──────────────────────────────────────────────┘  Body 16 sp Ink, RadiusBase
```

The text is Ink, not Sky I — the operator is verifying what the machine heard and the
container must not tint the judgement (§2.4). Sky is the container only. Polite live region.

---

### 7.10 Speech note

One muted line under the transmit panel explaining why recognition produced nothing.

`Caption` 13 sp Muted, with a 16 dp Butter G dot leading. Examples, verbatim from the engine:
`No speech model on this handset. Sent a template.` · `Loading the हिन्दी model…`

Never silent. A system that substitutes a canned sentence for what you said must say so.

---

### 7.11 Alert button

Band D, full width, 72 dp, `RadiusLarge`.

```
  ┌──────────────────────────────────────────────┐
  │            ⚠   A L E R T                     │  Blush S · 2 dp Blush T
  └──────────────────────────────────────────────┘  icon 32 dp + Label 15 sp, Blush I
```

Pressed: fills Blush G, text `InkPaper`. **Stays enabled while the floor is held by a peer** —
alert frames pre-empt the transmit queue and an emergency must never wait for someone else
to stop talking.

---

### 7.12 Alert template tile

Six of them, 2 × 3 grid, 96 dp each, `RadiusLarge`, `Raised`.

```
  ┌──────────────────┬──────────────────┐
  │   ✚              │   ▲              │  icon 40 dp, top-left
  │   MEDICAL        │   FIRE           │  Label 15 sp 600
  └──────────────────┴──────────────────┘  Blush S · Blush I · Blush T 1 dp
```

`ALL CLEAR` is the one exception — Mint S / Mint I / Mint T, because it is the only one of
the six that is good news and colouring it as an emergency would be a lie.

Icon **and** word, always. Never a word alone.

---

### 7.13 Traffic row

Band E. Three columns: sender (72 dp), text (flexible), age (48 dp right).

```
  Ravi      need help now, two injured        2 s  ← spoken
  Base      MEDICAL ASSISTANCE NEEDED         8 s  ← template
```

Sender `Status` 14 sp 600 Lilac I · text `Body` 16 sp Ink, one line, ellipsised · age
`Instrument` 12 sp mono Muted.

A 3 dp left rail carries the origin: **Aqua G** for spoken, **Orchid G** for template. That
distinction is deliberate and must survive any redesign — when recognition fails the system
sends a canned sentence instead, and a UI that cannot tell you which of the two just
happened is not demonstrating anything.

Empty: see 7.27.

---

### 7.14 Message card

The log's row. Received cards align left, sent cards align right and inset 32 dp.

```
  Ravi                                    2 s
  ┌────────────────────────────────────┐        Aqua S · Aqua T 1 dp
  │ need help now, two injured      ⟲  │        RadiusBase · Raised
  └────────────────────────────────────┘        Body 16 sp Ink
  44 B · ●●●○ · हिन्दी                            evidence line, 7.16
```

| Kind | Surface | Rail |
| --- | --- | --- |
| Received, spoken | Aqua S | Aqua G |
| Received, template | Orchid S | Orchid G |
| Sent by me | Periwinkle S | Periwinkle G |
| Alert (either direction) | Blush S | Blush G, plus `⚠ ALERT` header in Blush I |
| Queued | Butter S at 60 %, 1 dp dashed Butter T | Butter G |

---

### 7.15 Replay control

64 dp min, `RadiusFull`, Aqua S, `⟲` 24 dp Aqua G. Sits inside the message card, bottom
right. `heightIn(min = 64.dp)` — **not** `size(64.dp)`; the fixed version clipped the glyph
at 200 % text scale, and that is a bug the codebase has already hit once.

**A11y:** `"Replay, need help now two injured"` — the action and its object.

---

### 7.16 Evidence line

The monospaced fact strip under a message card. `Instrument` 12 sp mono, Sky I.

```
  52 B template · ✓✓ delivered · sent in हिन्दी · heard in தமிழ்
```

This is where the product's central claim becomes checkable, so it is a designed object, not
a caption: tabular figures, `·` separators at 8 dp, and the byte count always first.

**A11y:** `"52 bytes on the air, template, delivered, sent in Hindi, heard in Tamil"`.

---

### 7.17 Delivery mark

| Mark | Meaning | Colour |
| --- | --- | --- |
| `○` | queued | Butter G |
| `✓` | sent | Muted |
| `✓✓` | delivered | Mint G |
| `✕` | NOT DELIVERED | Blush G |

Spoken in full words. `✓✓` is `"delivered"`, never `"double tick"`.

---

### 7.18 Instrumentation strip

Band F, 40 dp, permanent. **Not a debug view** — it is the single most persuasive element on
the screen for a rubric that weights latency at 20 %, and hiding it behind a developer
toggle wastes it.

```
  ┌──────────────────────────────────────────────┐
  │  STT 210 · LINK  40 · TTS 180 ms             │  Sky S
  │  TOTAL 780 ms · RTF 0.22 · CPU 1.8 % · 44 B  │  Instrument 12 sp mono Sky I
  └──────────────────────────────────────────────┘  1 dp Sky T top border
```

Two lines, tabular figures, columns aligned. An unmeasured value is `—`, never `0`.

**A11y:** one spoken sentence — `"Speech to text 210 milliseconds, link 40, text to speech
180. Total 780 milliseconds."`

---

### 7.19 Degraded banner

Slides under band B, pushes content. 1 dp tint border, `RadiusBase`, chip + two lines.

```
  ┌──────────────────────────────────────────────┐
  │ ┌───┐  LINK DOWN — reconnecting, 12 s        │  Apricot S · Apricot T
  │ │ ⇄ │  Move closer to the other unit.        │  title Label 15 sp Apricot I
  │ └───┘                                        │  advice Caption 13 sp Muted
  └──────────────────────────────────────────────┘
```

**Apricot when it recovers itself. Blush when it needs a person.** All nine reasons:

| Reason | Icon | Family |
| --- | --- | --- |
| `LINK_DOWN` | bluetooth | Apricot |
| `MICROPHONE_UNAVAILABLE` | microphone | Apricot |
| `THERMAL` | thermometer | Apricot |
| `BLUETOOTH_OFF` | bluetooth | Blush |
| `NO_PEERS` | link down | Blush |
| `PERMISSION_DENIED` | microphone | Blush |
| `STORAGE_FULL` | storage | Blush |
| `TEMPLATE_MISMATCH` | alert | Blush |
| `MODELS_MISSING` | download | Blush |

Every banner carries three things: what happened, what the system is doing about it, what
the operator should do. Assertive live region.

---

### 7.20 Unsecured banner

```
  ┌──────────────────────────────────────────────┐
  │ ┌───┐  UNSECURED                             │  Blush G FILL, InkPaper text
  │ │ ⛔│  Messages are not encrypted. Anyone     │  not a pastel — this one
  │ └───┘  in range can read them.               │  is saturated on purpose
  └──────────────────────────────────────────────┘
```

Permanent, undismissable, and the one banner that is a full signal fill rather than a
surface. There is no silent path to unencrypted operation.

**This component currently has zero `contentDescription` in `SecurityBanners.kt`** — TalkBack
reads the glyph as "warning sign" and then two text fragments. It MUST be `clearAndSetSemantics`
into one sentence, like `DegradedBanner` already is.

---

### 7.21 Section heading

`Label` 15 sp 600, letterspaced +0.06 em, uppercase, Muted. 24 dp above, 8 dp below. No rule
under it — the space does the separating.

---

### 7.22 Choice row

Mode and transport selection. 96 dp for mode, 72 dp for transport.

```
  ┌──────────────────────────────────────────────┐
  │ ●  PUSH TO TALK                              │  selected:
  │    half duplex · 800–1200 ms · lowest power  │  Periwinkle S, 2 dp Periwinkle G
  └──────────────────────────────────────────────┘  radio filled Periwinkle G
  ┌──────────────────────────────────────────────┐
  │ ○  PHONE                                     │  unselected:
  │    full duplex · 1050–1500 ms · higher power │  Paper, 1 dp Rule
  └──────────────────────────────────────────────┘
```

The consequence line is not optional. Both numbers are shown honestly — phone mode really
is slower, because the 400 ms endpoint window replaces the key release, and showing that is
better than hiding it.

Transport rows add a 4-segment strength bar in Sky G and a range figure.

---

### 7.23 Pack row

```
  ┌──────────────────────────────────────────────┐
  │ ┌───┐  हिन्दी   Hindi                    61 MB │
  │ │ ⤓ │  ASR 34 · voice 26 · rules 1 MB        │  Lilac S chip
  │ └───┘  IndicConformer · Piper · permissive   │  licence Caption 13 sp
  │                                          🗑  │  delete Blush G
  └──────────────────────────────────────────────┘
```

Licence text turns Blush I when restrictive (GPL-3.0, CC-BY-NC). A jury asking "how big is
it really" gets an answer on screen rather than an estimate, and the licence disclosure is
impossible to miss.

---

### 7.24 Storage meter

```
  ████████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
  412 MB used · 11.2 GB free
```

16 dp tall, `RadiusFull`. Used portion is a left-to-right gradient across the **language
families in install order** — Lilac, Aqua, Orchid, Mint — so the bar is also a legend. Free
portion `Rule`. Figures `Instrument` 12 sp mono.

---

### 7.25 Histogram

Hand-drawn bars, no charting library — the APK budget will not carry one and the current
implementation is already sized `Box`es.

Bars Sky T with the modal bar in Sky G. 14 dp tall each, 2 dp gap, `RadiusSharp`. Axis
labels `Instrument` 12 sp mono Muted. A dashed 1 dp Butter G vertical marks the p95.

---

### 7.26 Figure tile

```
  ┌──────────┬──────────┬──────────┐
  │  MEDIAN  │   P95    │  WORST   │  label Caption 13 sp Muted upper
  │  780 ms  │ 1040 ms  │ 1180 ms  │  value Headline 28 sp mono Sky I
  └──────────┴──────────┴──────────┘  Sky S · RadiusBase
```

Median **and** p95, never one alone. A single best-case number is not a measurement and
[EVALUATION.md §7](EVALUATION.md#7-reporting-rules) forbids reporting one.

Below 100 utterances the whole row is replaced by the `NOT REPORTABLE` panel: Butter S,
2 dp Butter T, `NOT REPORTABLE` at `Title` in Butter I, and underneath
`42 of 100 utterances. A median over fewer is not a median.`

---

### 7.27 Empty state

Never a blank region and never a bare sentence.

```
        ┌──────┐
        │  ◈   │   64 dp family chip, family S, icon family G @ 60 %
        └──────┘
     No traffic yet          Subtitle 18 sp Ink
   Hold the button to speak  Caption 13 sp Muted
     ┌──────────────────┐
     │   DO THE THING   │    optional action, 64 dp, family S
     └──────────────────┘
```

The five that exist today as bare strings and need this treatment:

| Where | Family | Action |
| --- | --- | --- |
| Band E traffic | Periwinkle | — |
| Message log | Aqua | — |
| Storage, no packs | Lilac | `INSTALL A LANGUAGE PACK` |
| Metrics, no data | Sky | — |
| Licence text missing | Muted | — |

---

### 7.28 Back header

`SubScreen`'s chrome. 56 dp row: a `‹` in a 64 dp target, then the screen title at `Title`
22 sp Ink, then a 1 dp `Rule` divider. No elevation, no colour — the screen below it carries
the colour.

**A11y:** `"Back from Messages"`, not `"Back"`.

```
PROMPT — component library

  Design a 28-component mobile UI kit for iTantra, an offline emergency
  walkie-talkie used by disaster-relief operators wearing gloves in sunlight.

  Palette — light theme, warm off-white ground #FDFCFB, ink #101010,
  muted #5F5F5F, hairline #E7E4E0. Nine pastel families, each surface/tint/
  signal/ink:
    Periwinkle #E8EAFF #C7CEFF #4F5BD5 #2F3A8F   primary, transmit
    Mint       #E3F7EC #B8ECD0 #1B7F3B #14532D   good, delivered
    Sky        #E4F3FE #BEE3FB #0369A1 #0C4A6E   metrics, evidence
    Aqua       #DFF7F7 #B5EDEC #0E7C7B #0F4C4C   incoming, replay
    Lilac      #F0EBFE #D7C9FC #7C3AED #4C1D95   language, identity
    Orchid     #FBEAFB #F0CDF1 #A21CAF #701A75   templates
    Butter     #FEF6DC #FBE7A6 #B45309 #713F12   pending, thermal
    Apricot    #FFEEDF #FFD5B0 #C2410C #7C2D12   degraded, recovers
    Blush      #FFE7EA #FFC7CE #BE123C #9F1239   alert, failure

  Type      Inter. 40/28/22/18/16/15/14/13 sp; 12 sp monospace for all numbers,
            tabular figures. Indic scripts get 1.4x line height.
  Shape     radii 4 / 10 / 20 / full. Depth: flat, or y2 blur8 @8%, or y6 blur20 @12%,
            shadow tinted with the family ink — never neutral grey.
  Targets   64 dp minimum, 96 dp for confirmations.

  Components: link pill (up/down/reconnecting), unit identity, unit-count chip,
  compact language selector, full language row (5 states), transmit panel
  (idle/pressing/floor-seized/live/busy/loading), 12-segment level meter,
  4-dot confidence indicator, partial-hypothesis strip, speech note,
  alert button, alert template tile, traffic row, message card (5 kinds),
  replay control, monospace evidence line, delivery marks, instrumentation
  strip, degraded banner (amber + red), unsecured banner (saturated fill),
  section heading, choice row, pack row, storage meter, histogram,
  figure tile, empty state, back header.

  Rules: every coloured state also carries a glyph AND a word — the kit must
  stay readable in greyscale. No emoji. No gradients except the storage meter.
  Nothing below 12 sp. Present on a single artboard, grouped and labelled.

---

## 8. The screens

Twenty-five surfaces in five groups. Seventeen are already specified behaviourally in
`WIREFRAMES.md` §2–§18; eight are new and are marked **new**.

| | Entry | | Operating | | Alerts |
| --- | --- | --- | --- | --- | --- |
| 01 | Splash **new** | 06 | Operating — idle | 12 | Alert compose |
| 02 | Onboarding **new** | 07 | Operating — transmitting | 13 | Confirm before sending |
| 03 | Permissions **new** | 08 | Operating — floor held | 14 | Incoming alert, locked |
| 04 | First run | 09 | Operating — receiving | | |
| 05 | Pairing | 10 | Operating — phone mode | | |
| | | 11 | Degraded states | | |

| | Review | | Settings |
| --- | --- | --- | --- |
| 15 | Message log | 17 | Settings |
| 16 | Metrics | 18 | Language |
| | | 19 | Mode and transport |
| | | 20 | Storage and packs |
| | | 21 | Pack import **new** |
| | | 22 | Alert self-test **new** |
| | | 23 | Appearance **new** |
| | | 24 | About and licences |
| | | 25 | Empty and error set **new** |

Every wireframe below is 360 × 800 dp. Annotations to the right of a box give the family and
the size. Message text is romanised in the boxes so they stay legible in a monospaced
document; the application renders the selected script, and every container is sized for the
1.4 × line box Indic conjuncts require (§3.3).

---

### 01 · Splash — cold start **new**

**Why it exists.** The ASR graph is 130–190 MB and takes 1–2 seconds to preload. Risk T-11
says that cost must never be paid on a key press. Today the app goes straight to the
operating screen with the transmit control live and nothing loaded behind it.

```
 ┌──────────────────────────────────────────────┐
 │                                              │
 │                                              │
 │                                              │
 │                 ┌────────┐                   │  96 dp Periwinkle S chip
 │                 │ ((•))  │                   │  RadiusLarge
 │                 └────────┘                   │  icon 56 dp Periwinkle G
 │                                              │
 │                 i T a n t r a                │  Display 40 sp Ink
 │      Speech in. Speech out. No network.      │  Caption 13 sp Muted
 │                                              │
 │                                              │
 │       ▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░           │  4 dp bar, Periwinkle G
 │                                              │  on Periwinkle T
 │            Loading हिन्दी · 1.4 s             │  Caption 13 sp Muted
 │                                              │
 │                                              │
 │  ────────────────────────────────────────    │
 │   100 % offline · no SIM · no cloud          │  Instrument 12 sp Sky I
 └──────────────────────────────────────────────┘
```

Families: Periwinkle, Sky. Ground Paper.

| State | Bar | Line |
| --- | --- | --- |
| Loading | animates | `Loading हिन्दी · 1.4 s` |
| No pack installed | Butter G, static at 100 % | `No language pack. Transmit sends a template.` |
| Ready | fills, then `Considered` cross-fade to 06 | `Ready` |

**Never longer than 2.5 s.** If preload has not finished, hand off to 06 with the transmit
control in its `LOADING MODELS` state (§7.6) — a splash that outstays the load is worse than
no splash.

```
PROMPT — 01 splash

  Design a 360x800 dp Android splash screen, light theme.
  Ground #FDFCFB. Centred column.
  A 96 dp rounded-square chip in #E8EAFF, radius 20 dp, holding a 56 dp
  line icon in #4F5BD5: a filled dot with two concentric broadcast arcs
  either side.
  Below: wordmark "iTantra", Inter 40 sp bold, #101010, letterspacing -0.02em.
  Below that: "Speech in. Speech out. No network." 13 sp, #5F5F5F.
  Below that, with 48 dp gap: a 4 dp progress bar, full width minus 64 dp
  margins, track #C7CEFF, fill #4F5BD5, radius full, filled to 60%.
  Under the bar: "Loading Hindi (in Devanagari) · 1.4 s", 13 sp #5F5F5F.
  Pinned to the bottom above a hairline #E7E4E0: "100 % offline · no SIM ·
  no cloud", 12 sp monospace, #0C4A6E.
  Calm, spacious, premium. No gradients, no illustration, no emoji.
```

---

### 02 · Onboarding **new**

**Why it exists.** The product's justification is that it serves people who cannot type, and
an interface that assumes literacy would refute its own premise. So onboarding must be
comprehensible with every word deleted. Three cards, swipe or tap, skippable.

```
 ┌──────────────────────────────────────────────┐
 │                                    SKIP      │  Label 15 sp Muted, 64 dp
 ├──────────────────────────────────────────────┤
 │                                              │
 │        ┌──────────────────────────┐          │
 │        │                          │          │  illustration 240 dp
 │        │      ((•))  ⟶  ((•))     │          │  Periwinkle S ground
 │        │       you      them      │          │  RadiusLarge
 │        │                          │          │
 │        └──────────────────────────┘          │
 │                                              │
 │            Hold. Speak. Let go.              │  Title 22 sp Ink
 │                                              │
 │     They hear you in their own language.     │  Body 16 sp Muted
 │     No SIM. No tower. No internet.           │
 │                                              │
 │                                              │
 │              ●  ○  ○                         │  8 dp dots, Periwinkle G
 ├──────────────────────────────────────────────┤
 │        ┌──────────────────────────┐          │
 │        │          NEXT            │          │  96 dp, Periwinkle G fill
 │        └──────────────────────────┘          │  InkPaper Label 15 sp
 └──────────────────────────────────────────────┘
```

The three cards:

| # | Illustration | Words (secondary) | Family |
| --- | --- | --- | --- |
| 1 | Two handsets, a `((•))` arc between them | *Hold. Speak. Let go.* / They hear you in their own language | Periwinkle |
| 2 | A waveform collapsing into a small block labelled `52 B` | *Meaning is small.* / Speech becomes fifty-two bytes, so it crosses a radio a voice could never cross | Sky |
| 3 | A phone face-down with a red halo | *An alert always gets through.* / Full volume on every handset, even locked and silenced | Blush |

Each illustration is line art in the family signal on the family surface, 2 dp stroke, no
fill, no faces, no people. Faces date badly and imply a demographic the product does not have.

Card 3's `NEXT` becomes `START`.

```
PROMPT — 02 onboarding

  Design three 360x800 dp Android onboarding cards, light theme, ground #FDFCFB.

  Shared layout: a 64 dp "SKIP" text button top-right in #5F5F5F 15 sp;
  a 240 dp square illustration panel centred, radius 20 dp; a headline in
  Inter 22 sp bold #101010; two lines of body 16 sp #5F5F5F; a row of three
  8 dp page dots; and a 96 dp full-width primary button at the bottom with
  16 dp margins, radius 20 dp, white 15 sp bold label.

  Card 1 — panel #E8EAFF, line art #4F5BD5: two phone outlines facing each
  other with concentric broadcast arcs between them. Headline "Hold. Speak.
  Let go." Body "They hear you in their own language. No SIM. No tower.
  No internet." Button #4F5BD5 "NEXT".

  Card 2 — panel #E4F3FE, line art #0369A1: a wide audio waveform on the left
  funnelling into a small solid rounded square on the right labelled "52 B".
  Headline "Meaning is small." Body "Speech becomes fifty-two bytes, so it
  crosses a radio link a voice could never cross." Button #4F5BD5 "NEXT".

  Card 3 — panel #FFE7EA, line art #BE123C: a phone lying face-down with
  three expanding halo arcs. Headline "An alert always gets through."
  Body "Full volume on every handset, even locked and silenced."
  Button #4F5BD5 "START".

  Illustrations are 2 dp stroke line art only — no fills, no gradients,
  no people, no faces, no emoji. The cards must be understandable with all
  text removed.
```

---

### 03 · Permission rationale **new**

**Why it exists.** Today permissions are requested cold in `onCreate`, and a refusal
produces only a red banner on a screen the operator has not learned yet. Two permissions,
each explained before the system dialog, each with a recovery path.

```
 ┌──────────────────────────────────────────────┐
 │  ‹                                           │
 ├──────────────────────────────────────────────┤
 │                                              │
 │        ┌──────┐                              │
 │        │  🎤  │                              │  64 dp Apricot S chip
 │        └──────┘                              │  icon 32 dp Apricot G
 │                                              │
 │        The microphone                        │  Title 22 sp Ink
 │                                              │
 │        iTantra listens only while you are    │  Body 16 sp Muted
 │        holding the transmit button.          │
 │                                              │
 │        ┌──────────────────────────────────┐  │
 │        │ ✓  Audio never leaves the phone  │  │  Mint S, RadiusBase
 │        │ ✓  Nothing is recorded or stored │  │  Body 16 sp Mint I
 │        │ ✓  Nothing is ever sent to a     │  │  ticks 20 dp Mint G
 │        │    network                       │  │
 │        └──────────────────────────────────┘  │
 │                                              │
 ├──────────────────────────────────────────────┤
 │        ┌──────────────────────────┐          │
 │        │         ALLOW            │          │  96 dp Periwinkle G
 │        └──────────────────────────┘          │
 │              Not now                        │  64 dp text, Muted
 └──────────────────────────────────────────────┘
```

The second card is Nearby devices, with the same shape: Sky chip, bluetooth icon, and the
three assurances become *Used only to reach the other handsets · Never used for location ·
The app declares `neverForLocation`*.

**Refused state.** If the operator declines, the card does not vanish. It becomes:

```
 │  ┌────────────────────────────────────────┐  │
 │  │ ⛔  Microphone refused                  │  │  Blush S, 2 dp Blush T
 │  │     You can still receive and hear      │  │
 │  │     messages. You cannot send.          │  │
 │  │     ┌──────────────────────────┐        │  │
 │  │     │   OPEN ANDROID SETTINGS  │        │  │  64 dp Blush S
 │  │     └──────────────────────────┘        │  │
 │  └────────────────────────────────────────┘  │
```

Naming what still works is the point. A refusal that says only "denied" invites the operator
to conclude the app is broken.

```
PROMPT — 03 permission rationale

  Design a 360x800 dp Android permission-rationale screen, light theme,
  ground #FDFCFB, 16 dp margins.

  Top: a 56 dp row with a back chevron in a 64 dp tap target, #101010.
  A 64 dp rounded chip, radius 10 dp, background #FFEEDF, containing a 32 dp
  line-art microphone icon in #C2410C.
  Headline "The microphone", Inter 22 sp bold #101010.
  Body "iTantra listens only while you are holding the transmit button.",
  16 sp #5F5F5F.
  A reassurance card: background #E3F7EC, radius 10 dp, 16 dp padding, three
  rows each with a 20 dp tick in #1B7F3B and 16 sp text in #14532D —
  "Audio never leaves the phone", "Nothing is recorded or stored",
  "Nothing is ever sent to a network".
  Bottom: a 96 dp full-width button, radius 20 dp, fill #4F5BD5, white 15 sp
  bold "ALLOW"; under it a 64 dp plain text button "Not now" in #5F5F5F.

  Then a second variant of the same screen showing the refused state: replace
  the reassurance card with a #FFE7EA card, 1 dp #FFC7CE border, an unlocked
  padlock icon in #BE123C, heading "Microphone refused" in #9F1239, body
  "You can still receive and hear messages. You cannot send.", and a 64 dp
  button "OPEN ANDROID SETTINGS".

  No emoji, no gradients, line icons only.
```

---

### 04 · First run

One screen. Nothing to create, nothing to join, no decision to make. Pressing START generates
a key, takes node 01, and goes straight to the operating screen — the unit is usable alone.

```
 ┌──────────────────────────────────────────────┐
 │                                              │
 │                 ┌────────┐                   │  80 dp Periwinkle S chip
 │                 │ ((•))  │                   │
 │                 └────────┘                   │
 │                 i T a n t r a                │  Display 40 sp Ink
 │      Speech in. Speech out. No network.      │  Caption 13 sp Muted
 │                                              │
 ├──────────────────────────────────────────────┤
 │  YOUR NAME                                   │  section heading
 │  ┌────────────────────────────────────────┐  │
 │  │  Base                                  │  │  64 dp, Paper,
 │  └────────────────────────────────────────┘  │  1 dp Rule, focus 2 dp
 │  Shown on other handsets. Optional.          │  Periwinkle G
 │                                              │
 │  LANGUAGE                                    │
 │  ┌────────────────────────────────────────┐  │
 │  │  हिन्दी    Hindi                    ▾   │  │  64 dp Lilac S
 │  └────────────────────────────────────────┘  │  1 dp Lilac T
 │  You will speak and hear in this language.   │
 ├──────────────────────────────────────────────┤
 │        ┌──────────────────────────┐          │
 │        │        S T A R T         │          │  96 dp Periwinkle G
 │        └──────────────────────────┘          │  InkPaper Title 22 sp
 └──────────────────────────────────────────────┘
```

Families: Periwinkle, Lilac.

**The language selector is on this screen deliberately.** The first interaction must be
possible for someone who does not read English, so it comes before anything else and shows
native script first. The name field is offered, never required — `START` is enabled from the
first frame.

```
PROMPT — 04 first run

  Design a 360x800 dp Android first-run setup screen, light theme,
  ground #FDFCFB, 16 dp margins.

  Header block, centred: an 80 dp rounded-square chip #E8EAFF radius 20 dp
  holding a 48 dp line icon in #4F5BD5 (a dot with concentric broadcast arcs);
  wordmark "iTantra" Inter 40 sp bold #101010; tagline "Speech in. Speech out.
  No network." 13 sp #5F5F5F.

  Then a hairline #E7E4E0 divider.

  Field 1 — uppercase letterspaced section label "YOUR NAME" 15 sp #5F5F5F;
  a 64 dp text field, radius 10 dp, white fill, 1 dp #E7E4E0 border,
  placeholder "Base" 16 sp #101010; helper "Shown on other handsets. Optional."
  13 sp #5F5F5F.

  Field 2 — section label "LANGUAGE"; a 64 dp selector row, radius 10 dp,
  fill #F0EBFE, 1 dp #D7C9FC border, showing Devanagari "हिन्दी" at 18 sp
  #4C1D95 with "Hindi" at 13 sp #5F5F5F beside it and a dropdown caret in
  #7C3AED on the right; helper "You will speak and hear in this language."

  Bottom: a 96 dp full-width button, radius 20 dp, fill #4F5BD5, label
  "S T A R T" white 22 sp bold with wide letterspacing.

  Generous vertical rhythm, 8 dp grid. No emoji, no gradients.
```

---

### 05 · Pairing — add a unit

**There is no group to create and none to join.** Every unit shows this same screen: it
displays its own code and can scan another's. Whoever points the camera is the one who joins.
Both halves are on one screen because that is what removes the decision.

```
 ┌──────────────────────────────────────────────┐
 │  ‹   ADD A UNIT                              │  back header
 ├──────────────────────────────────────────────┤
 │      ┌────────────────────────────────┐      │
 │      │  ████ ██  ████  ██ ████ ██     │      │  QR 240 dp
 │      │  ██ ████ ██  ████ ██  ████     │      │  Ink on Paper
 │      │  ████  ██ ████ ██  ██ ██       │      │  in a Lilac S card
 │      │  ██  ████  ██  ████ ████ ██    │      │  RadiusLarge, Raised
 │      └────────────────────────────────┘      │  FLAG_SECURE
 │        Show this to the other unit           │  Body 16 sp Ink
 │        Code refreshes in 1:47                │  Instrument 12 sp Lilac I
 ├──────────── ───── or ───── ──────────────────┤  Rule + Caption
 │      ┌────────────────────────────────┐      │
 │      │  ┌──┐                  ┌──┐    │      │  camera 96 dp tall
 │      │      POINT AT ANOTHER UNIT     │      │  Periwinkle G reticle
 │      │  └──┘                  └──┘    │      │  corners
 │      └────────────────────────────────┘      │
 ├──────────────────────────────────────────────┤
 │  PAIRED                                      │  section heading
 │  ● Ravi        node 02        just now       │  Mint G dot, Lilac I name
 │  ● Meena       node 03         12 s ago      │
 │  ○ Arun        node 04          4 m ago      │  Muted dot when offline
 ├──────────────────────────────────────────────┤
 │  ENTER CODE MANUALLY                         │  64 dp, Muted, text only
 └──────────────────────────────────────────────┘
```

Families: Lilac, Mint, Periwinkle.

`FLAG_SECURE` is set — no screenshots, no recents thumbnail. The QR carries the AES-256
shared key and the key must never cross the radio medium.

**Manual entry is deliberately the least prominent element on the screen.** It requires
literacy, so it can never be the primary path. It is a text-only row at the very bottom, no
surface, no border.

**On a successful scan the screen does not navigate anywhere.** The PAIRED list gains a row
with a `Standard` slide-in, the handset gives a single haptic tick, and the new unit count is
spoken aloud. Confirmation by haptics and voice, not by a dialog. Pairing is a state of this
screen, not a journey through three of them.

```
PROMPT — 05 pairing

  Design a 360x800 dp Android pairing screen, light theme, ground #FDFCFB.

  Back header: 56 dp row, "‹" chevron in a 64 dp target, title "ADD A UNIT"
  Inter 22 sp bold #101010, hairline #E7E4E0 under.

  Upper half: a card, fill #F0EBFE, radius 20 dp, soft shadow y6 blur20 at
  12% #4C1D95, containing a 240 dp square QR code drawn in #101010 on white.
  Under the card, centred: "Show this to the other unit" 16 sp #101010, and
  "Code refreshes in 1:47" 12 sp monospace #4C1D95.

  A divider row: hairline, hairline, with the word "or" in 13 sp #5F5F5F
  centred in a gap.

  Lower: a 96 dp tall camera-preview panel, radius 20 dp, dark neutral #2A2A2A
  placeholder, with four 24 dp L-shaped reticle corners in #4F5BD5 and the
  caption "POINT AT ANOTHER UNIT" centred in white 15 sp bold.

  Then an uppercase letterspaced section heading "PAIRED" 15 sp #5F5F5F, and
  three 64 dp rows: a 10 dp status dot (#1B7F3B filled for online, #5F5F5F
  hollow for offline), a name in #4C1D95 15 sp semibold, "node 02" in 14 sp
  #5F5F5F, and a right-aligned age in 12 sp monospace #5F5F5F.

  Bottom: a plain 64 dp text row "ENTER CODE MANUALLY" in 15 sp #5F5F5F,
  no fill and no border — it must read as the least important thing on screen.

  No emoji, no gradients.
```

---

### The operating screen — bands A to F

Screens 06 to 11 are all the same screen. They share six fixed vertical bands, in the same
order, at the same heights, in every state. An operator who has learned one has learned all
of them, and **nothing may move between states** — only fills, labels and colours change.

```
 ┌──────────────────────────────────────────────┐
 │  A   status bar          56 dp               │  Paper, hairline under
 ├──────────────────────────────────────────────┤
 │  B   mode + language     48 dp               │  Paper
 ├──────────────────────────────────────────────┤
 │                                              │
 │  C   transmit panel      ≥ 33 %              │  Periwinkle / Butter
 │                                              │
 ├──────────────────────────────────────────────┤
 │  C′  partial hypothesis  transient           │  Sky, only while live
 ├──────────────────────────────────────────────┤
 │  D   ALERT               72 dp               │  Blush
 ├──────────────────────────────────────────────┤
 │  E   recent traffic      flexible            │  Paper, Aqua/Orchid rails
 ├──────────────────────────────────────────────┤
 │  F   instrumentation     40 dp               │  Sky, permanent
 └──────────────────────────────────────────────┘
```

Band D is a single full-width ALERT control. The POSITION button drawn in
`WIREFRAMES.md` §4 was cut — it needed a location permission for something ISRO never asked
for, and this application declares no location permission at all.

---

### 06 · Operating — push-to-talk, idle

The main screen. The one a jury looks at for six of the seven minutes.

```
 ┌──────────────────────────────────────────────┐
 │ ☰  BASE · node 01   [6 units]   [● LINK OK]  │  A · 56 dp
 ├──────────────────────────────────────────────┤  ☰ Ink · name Lilac I
 │  PTT · ALL UNITS        [ हिन्दी         ▾ ]  │  B · 48 dp
 ├──────────────────────────────────────────────┤  Lilac S selector
 │                                              │
 │     ┌────────────────────────────────┐       │
 │     │                                │       │  C · ≥ 33 %
 │     │            ((•))               │       │  Periwinkle S
 │     │                                │       │  3 dp Periwinkle G
 │     │       PUSH  TO  TALK           │       │  RadiusLarge · Lifted
 │     │                                │       │  icon 64 dp Periwinkle G
 │     └────────────────────────────────┘       │  label Title 22 sp
 │                                              │
 ├──────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────┐  │  D · 72 dp
 │  │            ⚠   A L E R T               │  │  Blush S · 2 dp Blush T
 │  └────────────────────────────────────────┘  │
 ├──────────────────────────────────────────────┤
 │ ▎Ravi     need help now, two injured    2 s  │  E
 │ ▎Base     sending a team                8 s  │  Aqua rail = spoken
 │ ▎Meena    MEDICAL ASSISTANCE NEEDED    41 s  │  Orchid rail = template
 ├──────────────────────────────────────────────┤
 │  STT 210 · LINK  40 · TTS 180 ms             │  F · 40 dp · Sky S
 │  TOTAL 780 ms · RTF 0.22 · CPU 1.8 % · 44 B  │  Instrument mono Sky I
 └──────────────────────────────────────────────┘
```

Families: Periwinkle (primary), Blush (alert), Sky (instrument). Lilac and Mint appear only
as small chips in band A/B, which keeps the screen inside the three-family rule.

**Band F is not a debug view.** It is permanent and it is designed. Latency is 20 % of the
jury rubric; hiding the measurement behind a developer toggle wastes it.

**Band C responds to the hardware volume-down key**, so the whole screen is operable with the
display off. Nothing in the visual design may imply that touching the screen is required.

```
PROMPT — 06 operating, idle

  Design the main screen of iTantra, a 360x800 dp Android offline walkie-talkie
  for disaster-relief operators. Light theme, ground #FDFCFB, 16 dp margins,
  8 dp grid.

  Six fixed horizontal bands, top to bottom:

  A — 56 dp status bar: a 32 dp hamburger icon #101010; then "BASE" in
  #4C1D95 14 sp semibold followed by "· node 01" in #5F5F5F; then a 28 dp
  pill "6 units" fill #F0EBFE text #4C1D95; then right-aligned a 32 dp pill
  with a 10 dp filled dot #1B7F3B and "LINK OK" in #14532D 14 sp semibold on
  fill #E3F7EC with a 1 dp #B8ECD0 border. Hairline #E7E4E0 under.

  B — 48 dp: left, "PTT · ALL UNITS" 14 sp #5F5F5F. Right, a 48 dp selector
  chip, fill #F0EBFE, 1 dp #D7C9FC, radius 10 dp, showing Devanagari "हिन्दी"
  18 sp #4C1D95 and a dropdown caret #7C3AED.

  C — the hero. A panel occupying at least a third of the screen height,
  radius 20 dp, fill #E8EAFF, 3 dp border #4F5BD5, soft shadow y6 blur20 at
  12% of #2F3A8F. Centred: a 64 dp line icon in #4F5BD5 — a filled dot with
  two concentric broadcast arcs each side — above the label "PUSH TO TALK"
  in Inter 22 sp bold #2F3A8F, letterspaced.

  D — 72 dp full-width button, radius 20 dp, fill #FFE7EA, 2 dp border
  #FFC7CE, containing a 32 dp warning-triangle icon in #BE123C and the word
  "ALERT" in 15 sp bold #9F1239, letterspaced, centred together.

  E — a list of three message rows, each 56 dp: a 3 dp coloured left rail
  (#0E7C7B for spoken, #A21CAF for template), a 72 dp sender column in
  #4C1D95 14 sp semibold, the message in #101010 16 sp truncated to one line,
  and a right-aligned age in 12 sp monospace #5F5F5F.

  F — a 40 dp strip pinned to the bottom, fill #E4F3FE, 1 dp #BEE3FB top
  border, two centred lines of 12 sp monospace #0C4A6E with tabular figures
  and aligned columns:
    "STT 210 · LINK  40 · TTS 180 ms"
    "TOTAL 780 ms · RTF 0.22 · CPU 1.8 % · 44 B"

  Calm, confident, premium. Pastel surfaces with dark legible ink, never
  pastel text. No emoji, no gradients, no drop shadows on text.
```

---

### 07 · Operating — transmitting

Band C inverts. Bands A, B, D, E, F do not move.

```
 ┌──────────────────────────────────────────────┐
 │ ☰  BASE · node 01   [6 units]   [● LINK OK]  │  unchanged
 ├──────────────────────────────────────────────┤
 │  PTT · ALL UNITS        [ हिन्दी         ▾ ]  │  unchanged
 ├──────────────────────────────────────────────┤
 │     ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓       │
 │     ┃                                ┃       │  C · Periwinkle G FILL
 │     ┃            ((•))               ┃       │  icon 64 dp InkPaper
 │     ┃                                ┃       │
 │     ┃   ▂▄▆█▆▄▂▁▂▄▆                  ┃       │  meter, InkPaper 90 %
 │     ┃                                ┃       │
 │     ┃      S P E A K   N O W         ┃       │  Title 22 sp InkPaper
 │     ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛       │
 ├──────────────────────────────────────────────┤
 │  "need help now, two inj…"          ●●●○     │  C′ · Sky S · Sky T
 ├──────────────────────────────────────────────┤  text Ink, dots Sky G
 │  ┌────────────────────────────────────────┐  │
 │  │            ⚠   A L E R T               │  │  D unchanged
 │  └────────────────────────────────────────┘  │
 ├──────────────────────────────────────────────┤
 │ ▎Ravi     need help now, two injured    2 s  │  E unchanged
 ├──────────────────────────────────────────────┤
 │  LISTENING …                    CPU  22 %    │  F · Sky S
 └──────────────────────────────────────────────┘
```

**The state before this one.** On press, the panel fills Periwinkle G immediately but the
label reads `opening the microphone…` at `Body` 16 sp — *not* `S P E A K   N O W`. The
microphone takes a moment to open and anything said in that gap is lost. Two distinct states,
two distinct labels; do not merge them.

The partial hypothesis is shown live so a literate operator can see what the machine heard,
but the design never *requires* reading it — the fill and the meter carry the same
information non-textually.

```
PROMPT — 07 operating, transmitting

  Take the iTantra main screen (360x800 dp, light theme, ground #FDFCFB,
  six bands A-F) and draw the transmitting state. Bands A, B, D, E keep
  exactly the same geometry and content as the idle state.

  Band C inverts: the hero panel is now a solid #4F5BD5 fill, radius 20 dp,
  no border, shadow y6 blur20 at 12% #2F3A8F. Inside, stacked and centred:
  a 64 dp broadcast icon in white; a 12-segment horizontal level meter —
  each segment 8 dp wide, 24 dp tall, 4 dp gap, radius 4 dp, seven segments
  filled white at 90% and five at 20%, with one peak-hold segment at 60%;
  and the label "S P E A K   N O W" in Inter 22 sp bold white, widely
  letterspaced.

  A new strip appears directly under band C: full width, fill #E4F3FE,
  1 dp #BEE3FB border, radius 10 dp, 48 dp tall, holding the live partial
  transcript in quotes — "need help now, two inj…" — at 16 sp #101010 on the
  left, and four 8 dp confidence dots on the right, three filled #0369A1 and
  one hollow.

  Band F changes to a single line: "LISTENING …" on the left and "CPU  22 %"
  right-aligned, both 12 sp monospace #0C4A6E on #E4F3FE.

  Also draw a second variant, identical, except the panel label reads
  "opening the microphone…" in 16 sp regular white and the level meter is
  absent — this is the moment after the floor is seized but before the
  microphone is open, and it must look different from the live state.

  No emoji, no gradients.
```

---

### 08 · Operating — floor held by a peer

Half-duplex. One speaker holds the channel; a press by anyone else produces a short haptic
refusal, never a dialog.

```
 ┌──────────────────────────────────────────────┐
 │ ☰  BASE · node 01   [6 units]   [● LINK OK]  │
 ├──────────────────────────────────────────────┤
 │  PTT · ALL UNITS        [ हिन्दी         ▾ ]  │
 ├──────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────┐  │  busy band
 │  │  ((•))  MEENA IS SPEAKING              │  │  Butter S · Butter T
 │  └────────────────────────────────────────┘  │  Label 15 sp Butter I
 ├──────────────────────────────────────────────┤
 │     ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐          │
 │                                              │  C · Butter S
 │     │          ((•))               │         │  2 dp DASHED Butter T
 │              dimmed 40 %                     │  icon + label at 40 %
 │     │     C H A N N E L  B U S Y   │         │  Title 22 sp Butter I
 │                                              │
 │     └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘          │
 ├──────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────┐  │
 │  │            ⚠   A L E R T               │  │  D · FULLY LIVE
 │  └────────────────────────────────────────┘  │  unchanged, undimmed
 ├──────────────────────────────────────────────┤
 │ ▎Ravi     need help now, two injured    2 s  │
 ├──────────────────────────────────────────────┤
 │  FLOOR HELD BY node 03 · 3 s                 │  F · Butter S
 └──────────────────────────────────────────────┘
```

Families: Butter (blocked), Blush (alert). The screen drops to two families because
everything except the alert is in one state.

**ALERT is not dimmed and is not disabled.** Alert frames pre-empt the transmit queue — an
emergency must never wait for someone else to stop talking. Dimming it here would be a design
error with a safety consequence.

Pressing the busy panel: an 8 dp horizontal shake over 120 ms, a double haptic buzz, and
nothing else. No dialog, no toast, no snackbar.

```
PROMPT — 08 operating, floor busy

  Take the iTantra main screen (360x800 dp, light theme, ground #FDFCFB) and
  draw the state where another operator holds the channel.

  Insert a busy band directly under band B: full width, 40 dp, fill #FEF6DC,
  1 dp #FBE7A6 border, radius 10 dp, holding a 24 dp broadcast icon in
  #B45309 and the text "MEENA IS SPEAKING" in 15 sp bold #713F12, letterspaced.

  Band C: the hero panel keeps its exact size and position but becomes
  fill #FEF6DC with a 2 dp DASHED #FBE7A6 border, radius 20 dp, no shadow.
  Its 64 dp broadcast icon and its label "C H A N N E L  B U S Y"
  (Inter 22 sp bold #713F12) are both drawn at 40% opacity.

  Band D — the ALERT button — is drawn at FULL opacity and full contrast,
  exactly as in the idle state: 72 dp, fill #FFE7EA, 2 dp #FFC7CE border,
  warning triangle #BE123C, "ALERT" 15 sp bold #9F1239. It must be visibly
  the most available control on the screen.

  Band F reads "FLOOR HELD BY node 03 · 3 s" in 12 sp monospace #713F12 on
  a #FEF6DC strip.

  Bands A, B and E are unchanged from the idle state.
  No emoji, no gradients.
```

---

### 09 · Operating — receiving and speaking

The panel becomes a card showing who is speaking, what they said, and playback.

```
 ┌──────────────────────────────────────────────┐
 │ ☰  BASE · node 01   [6 units]   [● LINK OK]  │
 ├──────────────────────────────────────────────┤
 │  PTT · ALL UNITS        [ हिन्दी         ▾ ]  │
 ├──────────────────────────────────────────────┤
 │   ┌────────────────────────────────────┐     │
 │   │  ((  R A V I  ))                   │     │  C · Aqua S
 │   │                                    │     │  2 dp Aqua T
 │   │  need help now, two injured        │     │  RadiusLarge · Lifted
 │   │                                    │     │  name Title 22 sp Aqua I
 │   │  ▶ ▁▃▅▇▅▃▁▃▅▇▅▃▁▃▅▇▅▃▁             │     │  text Body 16 sp Ink
 │   │                              ⟲     │     │  wave Aqua G
 │   └────────────────────────────────────┘     │  replay 64 dp Aqua G
 ├──────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────┐  │
 │  │            ⚠   A L E R T               │  │  D unchanged
 │  └────────────────────────────────────────┘  │
 ├──────────────────────────────────────────────┤
 │ ▎Base     sending a team                8 s  │  E
 ├──────────────────────────────────────────────┤
 │  RX  44 B · unpacked 20 ch · TTS 180 ms      │  F · Sky S
 └──────────────────────────────────────────────┘
```

Families: Aqua (incoming), Blush, Sky.

**The byte counter in band F is what the demonstration points at.** Showing `44 B` next to
`96 000 B equivalent audio` converts the compression claim from a slide into a measurement,
so band F on this state gets the widest tracking and the largest permitted `Instrument` size.

The message text is Ink on the Aqua surface, not Aqua ink — it is the content, not the
chrome. The playback waveform animates left to right in Aqua G for the duration of synthesis
and freezes when done, leaving `⟲` as the only live control.

```
PROMPT — 09 operating, receiving

  Take the iTantra main screen (360x800 dp, light theme, ground #FDFCFB) and
  draw the receiving-and-speaking state. Bands A, B, D, E keep their geometry.

  Band C becomes a card: fill #DFF7F7, 2 dp border #B5EDEC, radius 20 dp,
  shadow y6 blur20 at 12% #0F4C4C, 16 dp padding. Inside, stacked:
  the sender "(( R A V I ))" in Inter 22 sp bold #0F4C4C, letterspaced;
  the received message "need help now, two injured" in 16 sp #101010;
  a playback row — a 20 dp filled play triangle in #0E7C7B followed by a
  32 dp tall waveform of thin vertical bars in #0E7C7B, filled for the played
  portion and at 30% opacity for the rest; and bottom-right a 64 dp circular
  replay control, fill #DFF7F7, radius full, with a 24 dp circular-arrow icon
  in #0E7C7B.

  Band F reads "RX  44 B · unpacked 20 ch · TTS 180 ms" in 12 sp monospace
  #0C4A6E on #E4F3FE, tabular figures, wide letterspacing — this line is the
  product's central evidence and should read as a precision instrument.

  No emoji, no gradients.
```

---

### 10 · Operating — phone mode

Released push-to-talk. The primary action becomes a state display rather than a control.

```
 ┌──────────────────────────────────────────────┐
 │ ☰  BASE · node 01   [6 units]   [● LINK OK]  │
 ├──────────────────────────────────────────────┤
 │  PHONE · ALL UNITS      [ हिन्दी         ▾ ]  │  B · mode word changes
 ├──────────────────────────────────────────────┤
 │     ┌────────────────────────────────┐       │
 │     │           ((( )))              │       │  C · Periwinkle S
 │     │                                │       │  2 dp Periwinkle T
 │     │       O P E N   L I N E        │       │  Title 22 sp Periwinkle I
 │     │                                │       │
 │     │    ▂▄▆█▆▄▂   listening         │       │  meter Periwinkle G
 │     └────────────────────────────────┘       │  Caption 13 sp Muted
 │                                              │
 │      [  ⏸  HOLD  ]      [  ✕  END  ]         │  64 dp each
 │        Butter S           Blush S            │
 ├──────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────┐  │
 │  │            ⚠   A L E R T               │  │  D unchanged
 │  └────────────────────────────────────────┘  │
 ├──────────────────────────────────────────────┤
 │ ▎Ravi     need help now, two injured    2 s  │
 ├──────────────────────────────────────────────┤
 │  FULL DUPLEX · TOTAL 940 ms · CPU 31 %       │  F · Sky S
 └──────────────────────────────────────────────┘
```

Band F shows a visibly higher total than PTT mode — 940 ms against 780 ms — because the
400 ms endpoint window replaces the key release. **That difference is a designed trade-off
and the interface shows it honestly.** Do not normalise the two numbers.

The panel border drops from 3 dp to 2 dp and from signal to tint, because in this mode it is
not a control and must not invite a press.

```
PROMPT — 10 operating, phone mode

  Take the iTantra main screen (360x800 dp, light theme, ground #FDFCFB) and
  draw the full-duplex "phone" mode.

  Band B's left label reads "PHONE · ALL UNITS" instead of "PTT · ALL UNITS".

  Band C is a state display, not a button: fill #E8EAFF, 2 dp border #C7CEFF
  (thinner and lighter than the PTT state's 3 dp #4F5BD5, so it does not
  invite a press), radius 20 dp. Inside: a 64 dp icon of three nested open
  arcs in #4F5BD5; the label "O P E N   L I N E" in Inter 22 sp bold #2F3A8F;
  and below it a small 8-segment level meter in #4F5BD5 with the word
  "listening" in 13 sp #5F5F5F beside it.

  Directly under the panel, a row of two 64 dp buttons with a 16 dp gap:
  "⏸ HOLD" — fill #FEF6DC, 1 dp #FBE7A6, icon and label #713F12; and
  "✕ END" — fill #FFE7EA, 1 dp #FFC7CE, icon and label #9F1239.
  Both radius 10 dp, 15 sp bold labels.

  Band F reads "FULL DUPLEX · TOTAL 940 ms · CPU 31 %" in 12 sp monospace
  #0C4A6E on #E4F3FE.

  Bands A, D and E unchanged. No emoji, no gradients.
```

---

### 11 · Degraded states

Every degraded state is a banner on the operating screen with a reason string. A system that
silently stops working is worse than one that says it has stopped.

Banners slide in under band B and **push** content — they never overlay, because an overlay
would cover the transmit control the operator is reaching for.

```
 APRICOT — recovers itself, transmit still available
 ┌──────────────────────────────────────────────┐
 │ ┌───┐  LINK DOWN — reconnecting, 12 s        │  Apricot S
 │ │ ⇄ │  Move closer to the other unit.        │  1 dp Apricot T
 │ └───┘                                        │  title Label Apricot I
 └──────────────────────────────────────────────┘  advice Caption Muted

 ┌──────────────────────────────────────────────┐
 │ ┌───┐  MICROPHONE IN USE BY A CALL           │
 │ │ 🎤│  End the call to start listening again. │
 │ └───┘  Receiving still works.                │
 └──────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────┐
 │ ┌───┐  THERMAL — reduced to 1 thread         │
 │ │ 🌡│  The handset is hot and running slower. │
 │ └───┘  Get it out of the sun.                │
 └──────────────────────────────────────────────┘

 BLUSH — needs a person, transmit may be blocked
 ┌──────────────────────────────────────────────┐
 │ ┌───┐  NEARBY DEVICES PERMISSION REFUSED     │  Blush S
 │ │ 🎤│  Allow Nearby devices in Settings,      │  2 dp Blush T
 │ └───┘  Apps, iTantra, Permissions.           │
 └──────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────┐
 │ ┌───┐  LANGUAGE PACK MISSING OR CORRUPT      │
 │ │ ⤓ │  Re-install the pack in Settings.      │
 │ └───┘                            [ FIX IT ]  │  64 dp action
 └──────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────┐
 │ ┌───┐  TEMPLATE MISMATCH — node FE           │
 │ │ ⚠ │  Speak your message instead. Template  │
 │ └───┘  alerts are disabled.                  │
 └──────────────────────────────────────────────┘

 BLUSH SIGNAL FILL — permanent, undismissable
 ┌──────────────────────────────────────────────┐
 │ ┌───┐  UNSECURED                             │  Blush G FILL #BE123C
 │ │ ⛔│  Messages are not encrypted. Anyone     │  InkPaper text
 │ └───┘  in range can read them.               │  no dismiss control
 └──────────────────────────────────────────────┘

 BUTTER — transient, transmit disabled
 ┌──────────────────────────────────────────────┐
 │ ┌───┐  LOADING MODELS — 1.4 s                │  Butter S
 │ │ ⏳│  ▓▓▓▓▓▓▓▓░░░░░░░░░░░░                   │  2 dp progress
 │ └───┘                                        │
 └──────────────────────────────────────────────┘
```

All nine `Degraded.Reason` values, plus unsecured and loading, are covered. Each carries
three things: **what happened**, **what the system is doing**, **what you should do**.
Every one is an assertive live region.

The `UNSECURED` banner is the only saturated fill in the set and the only one with no
dismiss affordance. There is no silent path to unencrypted operation.

```
PROMPT — 11 degraded banners

  Design a set of eight status banners for an offline emergency walkie-talkie
  app. Light theme, ground #FDFCFB, each banner full width at 360 dp minus
  16 dp margins, radius 10 dp, 12 dp padding.

  Anatomy, identical for every banner: a 40 dp rounded chip on the left
  holding a 24 dp line icon, then a stack of two text lines — a bold
  uppercase title at 15 sp and an advice line at 13 sp in #5F5F5F.

  Amber group — fill #FFEEDF, 1 dp border #FFD5B0, chip #FFD5B0, icon and
  title #7C2D12:
   1. bluetooth icon — "LINK DOWN — RECONNECTING, 12 S" /
      "Move closer to the other unit."
   2. microphone icon — "MICROPHONE IN USE BY A CALL" /
      "End the call to start listening again. Receiving still works."
   3. thermometer icon — "THERMAL — REDUCED TO 1 THREAD" /
      "The handset is hot and running slower. Get it out of the sun."

  Red group — fill #FFE7EA, 2 dp border #FFC7CE, chip #FFC7CE, icon and
  title #9F1239:
   4. microphone icon — "NEARBY DEVICES PERMISSION REFUSED" /
      "Allow Nearby devices in Settings, Apps, iTantra, Permissions."
   5. download icon — "LANGUAGE PACK MISSING OR CORRUPT" /
      "Re-install the pack in Settings." plus a 64 dp right-aligned
      "FIX IT" button, fill #FFC7CE, text #9F1239.
   6. warning icon — "TEMPLATE MISMATCH — NODE FE" /
      "Speak your message instead. Template alerts are disabled."

  Saturated — solid fill #BE123C, no border, chip white at 20%, icon and all
  text WHITE, no dismiss control anywhere on it:
   7. unlocked-padlock icon — "UNSECURED" /
      "Messages are not encrypted. Anyone in range can read them."

  Amber-yellow — fill #FEF6DC, 1 dp #FBE7A6, chip #FBE7A6, icon/title #713F12:
   8. hourglass icon — "LOADING MODELS — 1.4 S" with a 4 dp progress bar
      instead of an advice line, track #FBE7A6 fill #B45309 at 60%.

  Present all eight stacked on one artboard with 16 dp gaps.
  Line icons only, 2 dp stroke. No emoji, no gradients.
```

---

### 12 · Alert compose

Six template tiles are the fastest path and the smallest frame — one byte of payload, 21 B
on the wire authenticated. They are also the cross-language path: a template sent here is
announced in whatever language each receiver has selected.

```
 ┌──────────────────────────────────────────────┐
 │  ‹   SEND ALERT                              │  back header
 ├──────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────┐  │
 │  │ Alerts are announced at full volume on │  │  Butter S · Butter T
 │  │ every unit, even locked and silenced.  │  │  Body 16 sp Butter I
 │  └────────────────────────────────────────┘  │
 ├──────────────────────────────────────────────┤
 │  ┌─────────────────┬──────────────────────┐  │
 │  │  ✚              │  ▲                   │  │  96 dp each
 │  │  MEDICAL        │  FIRE                │  │  Blush S · Blush T
 │  ├─────────────────┼──────────────────────┤  │  icon 40 dp Blush G
 │  │  ≈              │  ⌂                   │  │  label Label 15 sp
 │  │  FLOOD          │  EVACUATE            │  │  Blush I
 │  ├─────────────────┼──────────────────────┤  │  RadiusLarge · Raised
 │  │  ⌖              │  ✓                   │  │
 │  │  EXTRACT        │  ALL CLEAR           │  │  ← this one is MINT
 │  └─────────────────┴──────────────────────┘  │
 ├──────────────────────────────────────────────┤
 │     ┌────────────────────────────────┐       │
 │     │   ((•))  HOLD TO SPEAK ALERT   │       │  96 dp Periwinkle S
 │     └────────────────────────────────┘       │  3 dp Periwinkle G
 ├──────────────────────────────────────────────┤
 │  ☐  Attach my position                       │  64 dp · Muted
 ├──────────────────────────────────────────────┤
 │  Template alert = 21 B · reaches every unit  │  Orchid S strip
 │  in its own language                         │  Instrument 12 sp Orchid I
 └──────────────────────────────────────────────┘
```

Families: Blush (five tiles), Mint (ALL CLEAR), Periwinkle (free-form), Orchid (the
cross-language claim), Butter (the warning).

That is five families, which breaks the three-family rule — deliberately, and this is the
only screen permitted to. Each tile is a *different emergency* and the operator must be able
to hit the right one in a second without reading. The rule exists to stop decorative colour;
here every colour is doing load-bearing work.

**Every tile is an icon plus a word, never a word alone.** `ALL CLEAR` is Mint because it is
the only one of the six that is good news, and colouring it as an emergency would be a lie.

The Orchid footer is where the product's most surprising claim lives — a Hindi speaker's
alert reaches a Tamil speaker *in Tamil*, with no translation model — so it gets a surface
of its own rather than being caption text.

```
PROMPT — 12 alert compose

  Design a 360x800 dp Android "send alert" screen, light theme, ground
  #FDFCFB, 16 dp margins.

  Back header: 56 dp, "‹" chevron in a 64 dp target #101010, title
  "SEND ALERT" Inter 22 sp bold #101010, hairline #E7E4E0 under.

  A warning card: fill #FEF6DC, 1 dp #FBE7A6, radius 10 dp, 12 dp padding,
  text "Alerts are announced at full volume on every unit, even locked and
  silenced." 16 sp #713F12.

  A 2x3 grid of 96 dp tiles, 8 dp gaps, each radius 20 dp with a soft shadow
  y2 blur8 at 8%. Each tile has a 40 dp line icon top-left and an uppercase
  label at 15 sp bold below it.
    MEDICAL (cross), FIRE (flame), FLOOD (three waves),
    EVACUATE (house with arrow), EXTRACT (crosshair)
      — all five: fill #FFE7EA, 1 dp #FFC7CE, icon #BE123C, label #9F1239.
    ALL CLEAR (tick in circle)
      — fill #E3F7EC, 1 dp #B8ECD0, icon #1B7F3B, label #14532D.

  Below the grid: a 96 dp full-width button, radius 20 dp, fill #E8EAFF,
  3 dp border #4F5BD5, containing a 32 dp broadcast icon and the label
  "HOLD TO SPEAK ALERT" in 15 sp bold #2F3A8F.

  Below that: a 64 dp row with an unchecked 24 dp square checkbox in #5F5F5F
  and the label "Attach my position" at 16 sp #101010.

  Pinned to the bottom: a strip, fill #FBEAFB, radius 10 dp, two lines of
  12 sp monospace #701A75 — "Template alert = 21 B · reaches every unit"
  / "in its own language".

  Line icons, 2 dp stroke, no emoji, no gradients.
```

---

### 13 · Confirm before sending

Shown when recogniser confidence is low, and **always** for alert-class messages. This is
the one place the system deliberately adds latency, because an alert is the only message
type that can cause physical harm if it is wrong.

```
 ┌──────────────────────────────────────────────┐
 │  ⚠   CHECK BEFORE SENDING                    │  Blush S header
 ├──────────────────────────────────────────────┤
 │                                              │
 │   ┌────────────────────────────────────┐     │
 │   │                                    │     │
 │   │   do not evacuate sector seventeen │     │  Headline 28 sp Ink
 │   │                                    │     │  Paper fill, 2 dp Rule
 │   │   ────────────────────────────     │     │  RadiusLarge · Lifted
 │   │   confidence  ●○○○   low           │     │  dots + word, Butter G
 │   └────────────────────────────────────┘     │
 │                                              │
 │        ┌──────────────────────────┐          │
 │        │   ▶  HEAR IT BACK        │          │  72 dp Aqua S
 │        └──────────────────────────┘          │  Aqua T 1 dp
 │                                              │
 │   Spoken aloud automatically on open, so     │  Caption 13 sp Muted
 │   this screen works without reading.         │
 │                                              │
 ├──────────────────────────────────────────────┤
 │  ┌──────────────────┬───────────────────┐    │
 │  │                  │                   │    │
 │  │   ✕  RETAKE      │    ✓  SEND        │    │  96 dp each
 │  │                  │                   │    │  EXACTLY equal width
 │  └──────────────────┴───────────────────┘    │
 │     Paper, 2 dp Rule    Blush G fill         │
 │     Ink label           InkPaper label       │
 └──────────────────────────────────────────────┘
```

Families: Blush, Aqua, Butter. The recognised text sits on **Paper, not a pastel** — §2.4.
The operator is verifying what the machine heard, and tinting that container implies a
judgement the system has not made.

Two rules govern this screen and both are visual:

1. **The text is spoken aloud on open**, so the confirmation is usable by a non-literate
   operator. `HEAR IT BACK` repeats it. That button is a real target, not a link.
2. **RETAKE and SEND are exactly equal in size.** A confirmation that makes the safe option
   smaller is not a confirmation. Equal width, equal height, equal type. Only the fill
   differs — and SEND is the filled one because it is the action, not because it is
   preferred.

```
PROMPT — 13 confirm before sending

  Design a 360x800 dp Android confirmation screen, light theme, ground
  #FDFCFB, 16 dp margins.

  Header: 56 dp strip, fill #FFE7EA, a 24 dp warning triangle in #BE123C and
  "CHECK BEFORE SENDING" in 22 sp bold #9F1239.

  Centre: a card, WHITE fill (deliberately not tinted), 2 dp border #E7E4E0,
  radius 20 dp, shadow y6 blur20 at 12% #101010 at low opacity, 24 dp padding.
  Inside: the recognised sentence "do not evacuate sector seventeen" in Inter
  28 sp regular #101010 with generous line height; a hairline #E7E4E0; and a
  confidence row — the word "confidence" 13 sp #5F5F5F, four 8 dp dots with
  only the first filled in #B45309, and the word "low" 13 sp bold #713F12.

  Below the card: a 72 dp button, fill #DFF7F7, 1 dp #B5EDEC, radius 10 dp,
  a 24 dp play triangle in #0E7C7B and the label "HEAR IT BACK" 15 sp bold
  #0F4C4C.

  Under it, 13 sp #5F5F5F, two lines: "Spoken aloud automatically on open,
  so this screen works without reading."

  Pinned to the bottom, a row of exactly two buttons of IDENTICAL width and
  96 dp height, 8 dp gap, radius 20 dp:
    left  — "✕ RETAKE", white fill, 2 dp #E7E4E0 border, label #101010 15 sp bold
    right — "✓ SEND",  fill #BE123C, no border, label WHITE 15 sp bold

  The two buttons must be visually equal in weight and size — this is a
  safety requirement, not a stylistic one. Do not enlarge or emphasise SEND.

  No emoji, no gradients.
```

---

### 14 · Incoming alert — locked handset

The full-screen intent. What a locked, silenced phone shows when an alert arrives.

```
 ┌──────────────────────────────────────────────┐
 │██████████████████████████████████████████████│
 │██                                          ██│
 │██                   ⚠                      ██│  96 dp icon, InkPaper
 │██                                          ██│
 │██             A  L  E  R  T                ██│  Display 40 sp InkPaper
 │██                                          ██│  wide tracking
 │██        FROM  RAVI  ·  node 02            ██│  Label 15 sp @ 80 %
 │██                                          ██│
 │██  ┌────────────────────────────────────┐  ██│
 │██  │                                    │  ██│
 │██  │  MEDICAL ASSISTANCE NEEDED         │  ██│  Headline 28 sp Ink
 │██  │                                    │  ██│  on Paper card
 │██  │  20.29 N   85.82 E                 │  ██│  RadiusLarge
 │██  │                                    │  ██│  Instrument 12 sp mono
 │██  └────────────────────────────────────┘  ██│
 │██                                          ██│
 │██        ▶  repeating · 2 of 2             ██│  InkPaper @ 80 %
 │██                                          ██│
 │██   ┌────────────────────────────────┐     ██│
 │██   │        ACKNOWLEDGE             │     ██│  96 dp, Paper fill
 │██   └────────────────────────────────┘     ██│  Alert-red label
 │██                                          ██│
 │██████████████████████████████████████████████│
 └──────────────────────────────────────────────┘
```

**Full bleed `#C62828`, in both palettes.** This screen does not get a pastel. It is
identical in Spectrum and Field, and it is the one place where the design deliberately shouts.

The message renders in the **receiver's own** language, from the shared template table, with
no translation model involved.

The border pulses between `#C62828` and `#9F1239` at 1 Hz while the two spoken repeats run —
the only continuous decorative motion in the product, justified by the requirement for
redundant channels in a noisy environment and for a hearing-impaired operator.

**Dismissal requires the ACKNOWLEDGE target. There is no swipe-away, because a swipe is
something a pocket can do.** No back gesture, no scrim tap, no outside dismiss.

Behind this screen all six steps of the alert-delivery sequence are running: alarm-stream
routing, forced volume with the prior level restored afterwards, exclusive audio focus with
loss callbacks deliberately ignored, a wake lock, a vibration pattern, and a repeat.

```
PROMPT — 14 incoming alert, locked handset

  Design a 360x800 dp Android full-screen alert that appears over a locked,
  silenced phone.

  The entire screen is a solid saturated red #C62828, edge to edge, no
  margins, no status bar chrome. A 6 dp inner border in #9F1239 inset 8 dp.

  Centred column, generous spacing:
   - a 96 dp warning-triangle line icon in WHITE, 3 dp stroke
   - "A  L  E  R  T" in Inter 40 sp bold WHITE, very wide letterspacing
   - "FROM  RAVI  ·  node 02" in 15 sp semibold white at 80% opacity,
     letterspaced
   - a white card, radius 20 dp, 24 dp padding, full width minus 32 dp:
       the message "MEDICAL ASSISTANCE NEEDED" in 28 sp bold #101010,
       and under a hairline, "20.29 N   85.82 E" in 12 sp monospace #5F5F5F
   - "▶ repeating · 2 of 2" in 15 sp white at 80%
   - a 96 dp full-width button, radius 20 dp, WHITE fill, label
     "ACKNOWLEDGE" in 22 sp bold #C62828, widely letterspaced

  There must be no close icon, no X, no swipe indicator and no scrim — the
  acknowledge button is the only way out.

  Maximum contrast and urgency. No pastels anywhere on this screen. No emoji,
  no gradients.
```

---

### 15 · Message log

Everything sent and received in the last 24 hours, replayable. Retained 24 h, then deleted.
Captured audio is never stored — the replay control re-synthesises from text.

```
 ┌──────────────────────────────────────────────┐
 │  ‹   MESSAGES                    last 24 h   │  back header
 ├──────────────────────────────────────────────┤
 │  ──────────  today 02:14  ──────────         │  Caption Muted, rules
 │                                              │
 │  Ravi                                  2 s   │  Lilac I · mono age
 │  ┌────────────────────────────────────┐      │
 │ ▎│ need help now, two injured      ⟲  │      │  Aqua S · Aqua rail
 │  └────────────────────────────────────┘      │  replay 64 dp Aqua G
 │  44 B · ●●●○ · हिन्दी                         │  evidence, Sky I mono
 │                                              │
 │                                  me    8 s   │  right-aligned
 │       ┌────────────────────────────────┐     │
 │       │ sending a team              ⟲  │▎    │  Periwinkle S
 │       └────────────────────────────────┘     │  inset 32 dp left
 │                          32 B · ✓✓ delivered │  Mint G tick
 │                                              │
 │  ⚠ ALERT  Meena                       41 s   │  Blush I header
 │  ┌────────────────────────────────────┐      │
 │ ▎│ MEDICAL ASSISTANCE NEEDED       ⟲  │      │  Blush S · Blush rail
 │  └────────────────────────────────────┘      │
 │  21 B template · ✓✓ · sent in தமிழ்           │  Orchid I — the
 │                                              │  cross-language proof
 │  ○ queued   position secure          pending │  Butter S @ 60 %
 │                                              │  dashed Butter T
 └──────────────────────────────────────────────┘
```

Families: Aqua (received), Periwinkle (sent), Blush (alert), Butter (queued), Orchid and Sky
in the evidence lines only. The evidence lines are `Instrument` 12 sp, so the family count at
body scale is still three.

**Every message shows its frame size**, and template messages show the language they were
*sent* in versus rendered in. That row — `21 B template · ✓✓ · sent in தமிழ்` — is the single
most persuasive line in the application, and it is typographically designed rather than
tucked into a caption.

Delivery state is the store-and-forward state: `○ queued`, `✓ sent`, `✓✓ delivered`,
`✕ NOT DELIVERED`, each with its own colour *and* its own glyph *and* its own word.

```
PROMPT — 15 message log

  Design a 360x800 dp Android message-log screen, light theme, ground
  #FDFCFB, 16 dp margins.

  Back header: 56 dp, "‹" chevron, "MESSAGES" 22 sp bold #101010, and
  right-aligned "last 24 h" 14 sp #5F5F5F.

  A day divider: two hairlines #E7E4E0 with "today 02:14" 13 sp #5F5F5F
  centred between them.

  Four message entries, each a stack of sender line + bubble + evidence line:

  1. RECEIVED, spoken. Sender "Ravi" 14 sp semibold #4C1D95, right-aligned
     age "2 s" 12 sp monospace #5F5F5F. Bubble: fill #DFF7F7, radius 10 dp,
     3 dp left rail #0E7C7B, shadow y2 blur8 8%, text "need help now, two
     injured" 16 sp #101010, and a 64 dp circular replay control on the right
     with a 24 dp circular arrow in #0E7C7B. Evidence line under, 12 sp
     monospace #0C4A6E: "44 B · ●●●○ · हिन्दी" with three dots filled.

  2. SENT. Aligned right, inset 32 dp from the left. Sender "me". Bubble:
     fill #E8EAFF, 3 dp right rail #4F5BD5, text "sending a team".
     Evidence right-aligned: "32 B · ✓✓ delivered" with the double tick
     in #1B7F3B.

  3. ALERT. Header line "⚠ ALERT  Meena" with the triangle and "ALERT" in
     #BE123C 14 sp bold, then the name in #4C1D95. Bubble: fill #FFE7EA,
     3 dp left rail #BE123C, text "MEDICAL ASSISTANCE NEEDED" 16 sp #101010.
     Evidence: "21 B template · ✓✓ · sent in தமிழ்" in 12 sp monospace
     #701A75 — give this line slightly more presence than the others.

  4. QUEUED. Bubble at 60% opacity, fill #FEF6DC, 1 dp DASHED #FBE7A6,
     text "position secure", with a leading hollow circle in #B45309 and a
     right-aligned "pending" in 12 sp monospace #713F12.

  Tabular monospace figures throughout the evidence lines. No emoji,
  no gradients.
```

---

### 16 · Metrics

Median **and** p95 over a named number of utterances, with the device and soak duration on
screen. A single best-case number is not a measurement and reporting one is forbidden.

```
 ┌──────────────────────────────────────────────┐
 │  ‹   METRICS              142 utterances     │  back header
 ├──────────────────────────────────────────────┤
 │  END TO END                                  │  section heading
 │  ┌──────────┬──────────┬──────────┐          │
 │  │  MEDIAN  │   P95    │  WORST   │          │  Sky S tiles
 │  │  780 ms  │ 1040 ms  │ 1180 ms  │          │  Headline 28 sp mono
 │  └──────────┴──────────┴──────────┘          │  Sky I
 │                                              │
 │   30 │        ▂▆█▆▃                          │  bars Sky T
 │      │      ▁▄████▇▃▁          ┊             │  modal bar Sky G
 │      │   ▁▂▆███████▆▄▂▁        ┊p95          │  p95 marker Butter G
 │    0 └──────────────────────────────         │  dashed
 │      400   600   800  1000  1200 ms          │  Instrument mono Muted
 ├──────────────────────────────────────────────┤
 │  BY STAGE                        median      │
 │  capture                          32 ms      │  rows 48 dp
 │  endpoint window                 150 ms      │  label Body Ink
 │  decode after endpoint           178 ms      │  value Instrument mono
 │  frame · encrypt · tx             40 ms      │  right-aligned
 │  normalise                         6 ms      │
 │  first synthesis chunk           182 ms      │
 │  output                           48 ms      │
 │  ─────────────────────────────────────       │
 │  thermal derate                  212 ms  ⚠   │  over budget → Blush I
 ├──────────────────────────────────────────────┤
 │  RTF asr 0.22 · tts 0.16 · CPU 1.8 % idle    │  Sky S strip
 │  soak 34 min · SM-S947B · release build      │  Instrument mono Sky I
 ├──────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────┐  │
 │  │  ⤓  EXPORT  latency.csv · resource.csv │  │  72 dp Sky S
 │  └────────────────────────────────────────┘  │  Sky T 1 dp
 └──────────────────────────────────────────────┘
```

Families: Sky throughout, Butter for the p95 marker, Blush for an over-budget stage. One
family plus two accents — the most restrained screen in the product, which is right for the
one that is nothing but numbers.

**The refusal state.** Below 100 utterances the figure tiles are replaced entirely:

```
 │  ┌────────────────────────────────────────┐  │
 │  │  NOT REPORTABLE                        │  │  Butter S
 │  │                                        │  │  2 dp Butter T
 │  │  42 of 100 utterances.                 │  │  Title 22 sp Butter I
 │  │  A median over fewer is not a median.  │  │  Body 16 sp Muted
 │  │  ▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░              │  │  progress to 100
 │  └────────────────────────────────────────┘  │
```

That refusal must look deliberate and expensive, not like a bug. It is the screen's best
argument for the trustworthiness of every other number on it.

```
PROMPT — 16 metrics

  Design a 360x800 dp Android metrics screen, light theme, ground #FDFCFB,
  16 dp margins. This screen is nothing but numbers and must read like a
  precision instrument.

  Back header: "‹", "METRICS" 22 sp bold #101010, right-aligned
  "142 utterances" 14 sp #5F5F5F.

  Section "END TO END" — uppercase letterspaced 15 sp #5F5F5F.
  A row of three equal tiles, radius 10 dp, fill #E4F3FE, 16 dp padding:
  each has a 13 sp uppercase label #5F5F5F over a value in 28 sp monospace
  bold #0C4A6E — "MEDIAN 780 ms", "P95 1040 ms", "WORST 1180 ms".

  Below, a histogram: 18 vertical bars in #BEE3FB, radius 4 dp, 2 dp gaps,
  forming a right-skewed distribution; the tallest bar in #0369A1; a dashed
  1 dp vertical line in #B45309 near the right labelled "p95" in 12 sp
  monospace. Y axis "30" and "0", x axis "400 600 800 1000 1200 ms", all
  12 sp monospace #5F5F5F.

  Section "BY STAGE" with a right-aligned "median" caption. Eight rows,
  48 dp each, hairline #E7E4E0 between: a label in 16 sp #101010 on the left
  and a right-aligned value in 12 sp monospace #0C4A6E — capture 32 ms,
  endpoint window 150 ms, decode after endpoint 178 ms, frame · encrypt · tx
  40 ms, normalise 6 ms, first synthesis chunk 182 ms, output 48 ms, and a
  final over-budget row "thermal derate 212 ms" with the value in #9F1239
  and a small warning triangle after it.

  A summary strip, fill #E4F3FE, radius 10 dp, two lines of 12 sp monospace
  #0C4A6E: "RTF asr 0.22 · tts 0.16 · CPU 1.8 % idle" and
  "soak 34 min · SM-S947B · release build".

  Bottom: a 72 dp button, fill #E4F3FE, 1 dp #BEE3FB, radius 10 dp, a 24 dp
  download icon in #0369A1 and the label "EXPORT  latency.csv · resource.csv"
  in 15 sp bold #0C4A6E.

  Then draw a SECOND variant where the three figure tiles are replaced by a
  single card: fill #FEF6DC, 2 dp #FBE7A6, radius 10 dp, heading
  "NOT REPORTABLE" 22 sp bold #713F12, body "42 of 100 utterances. A median
  over fewer is not a median." 16 sp #5F5F5F, and a 4 dp progress bar at 42%,
  track #FBE7A6 fill #B45309.

  Tabular figures everywhere. Columns must align. No emoji, no gradients.
```

---

### 17 · Settings

The list behind `☰`. Not a drawer — a full page, reached in one tap, with everything else two
taps from the operating screen.

```
 ┌──────────────────────────────────────────────┐
 │  ‹   SETTINGS                                │  back header
 ├──────────────────────────────────────────────┤
 │  THIS UNIT                                   │  section heading
 │  ┌────┐                                      │
 │  │ ◈  │  Base · node 01                   ›  │  72 dp · Lilac chip
 │  └────┘  Name shown on other handsets        │  title Body 16 sp Ink
 │  ┌────┐                                      │  blurb Caption Muted
 │  │ ▣  │  Add a unit                       ›  │
 │  └────┘  Show a code, or scan another's      │
 ├──────────────────────────────────────────────┤
 │  LANGUAGE AND PACKS                          │
 │  ┌────┐                                      │
 │  │ ⌨  │  Language                हिन्दी    ›  │  Lilac chip
 │  └────┘  Speak and hear in this language     │
 │  ┌────┐                                      │
 │  │ ▤  │  Storage                 412 MB   ›  │  Butter chip
 │  └────┘  Four packs installed                │
 ├──────────────────────────────────────────────┤
 │  RADIO                                       │
 │  ┌────┐                                      │
 │  │ ⇄  │  Mode and transport    PTT · BT   ›  │  Sky chip
 │  └────┘  Half duplex, Bluetooth Classic      │
 ├──────────────────────────────────────────────┤
 │  AUDIO                                       │
 │  ┌────┐                                      │
 │  │ ⚠  │  Test alert on this device        ›  │  Blush chip
 │  └────┘  Sounds here only. Nothing is sent   │
 │        Noise suppression              [ ●]   │  toggle, Mint G on
 │        Voice per sender               [ ●]   │
 ├──────────────────────────────────────────────┤
 │  MEASUREMENT                                 │
 │  ┌────┐                                      │
 │  │ ▦  │  Metrics             142 samples  ›  │  Sky chip
 │  └────┘                                      │
 ├──────────────────────────────────────────────┤
 │  APPEARANCE                                  │
 │  ┌────┐                                      │
 │  │ ◐  │  Theme               Spectrum    ›   │  Periwinkle chip
 │  └────┘  Switch to Field for direct sunlight │
 ├──────────────────────────────────────────────┤
 │  ABOUT                                       │
 │        Version · build 1.0 (27.1 MB)      ›  │
 │        Open-source licences               ›  │
 └──────────────────────────────────────────────┘
```

The pastel chip (§6.4) carries the colour; the row itself is Paper. That is what stops a
long settings list turning into a rainbow — nine families appear on this screen, but only as
40 dp chips, and the eye reads a calm white list with coloured markers rather than a
patchwork.

**Test alert on this device** exists because vendor audio policy varies enough that alert
delivery must be verifiable on each specific handset, and because it lets the demonstration
be rehearsed without a second operator.

```
PROMPT — 17 settings

  Design a 360x800 dp Android settings list, light theme, ground #FDFCFB,
  16 dp margins, scrollable.

  Back header: "‹" in a 64 dp target, "SETTINGS" 22 sp bold #101010.

  Seven groups, each introduced by an uppercase letterspaced 15 sp #5F5F5F
  section heading with 24 dp above and 8 dp below.

  Every row is 72 dp on a WHITE ground with a hairline #E7E4E0 between rows.
  Each row: a 40 dp rounded chip (radius 10 dp) on the left holding a 24 dp
  line icon, then a two-line stack — title 16 sp #101010 and a blurb 13 sp
  #5F5F5F — then a right-aligned value in 14 sp #5F5F5F and a "›" chevron.

  Chip colours: identity/language rows #F0EBFE with icons #7C3AED; storage
  #FEF6DC with #B45309; radio and metrics #E4F3FE with #0369A1; alert test
  #FFE7EA with #BE123C; theme #E8EAFF with #4F5BD5.

  Rows, in order:
   THIS UNIT — "Base · node 01" / "Name shown on other handsets";
     "Add a unit" / "Show a code, or scan another's"
   LANGUAGE AND PACKS — "Language" value "हिन्दी"; "Storage" value "412 MB"
     / "Four packs installed"
   RADIO — "Mode and transport" value "PTT · BT" / "Half duplex, Bluetooth
     Classic"
   AUDIO — "Test alert on this device" / "Sounds here only. Nothing is sent";
     then two rows with no chip and a pill toggle on the right, ON, track
     #1B7F3B, knob white — "Noise suppression", "Voice per sender"
   MEASUREMENT — "Metrics" value "142 samples"
   APPEARANCE — "Theme" value "Spectrum" / "Switch to Field for direct sunlight"
   ABOUT — two chip-less rows, "Version · build 1.0 (27.1 MB)" and
     "Open-source licences"

  The list must read as calm and white with small colour markers, never as a
  patchwork. Line icons, 2 dp stroke. No emoji, no gradients.
```

---

### 18 · Language

Each language is written **in its own script first, English gloss second** — the list must be
usable by someone who cannot read the others.

```
 ┌──────────────────────────────────────────────┐
 │  ‹   LANGUAGE                                │
 ├──────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────┐  │
 │  │ ●  हिन्दी                                │  │  selected:
 │  │    Hindi · speaks and hears             │  │  Lilac S
 │  └────────────────────────────────────────┘  │  2 dp Lilac G
 │  ○  English                                  │  72 dp rows
 │     English · speaks and hears               │
 │  ○  বাংলা                                    │
 │     Bengali · speaks and hears               │
 │  ○  தமிழ்                          [Text only]│  Butter S chip
 │     Tamil · hears you, cannot speak back     │
 │  ○  मराठी                              ⤓ 62 MB│  Lilac G
 │     Marathi · not installed                  │
 │  ○  తెలుగు                              ⤓ 61 MB│
 │     Telugu · not installed                   │
 │  ○  ગુજરાતી                        [Text only]│
 │     Gujarati · hears you, cannot speak back  │
 │  ○  ಕನ್ನಡ                           [Text only]│
 │     Kannada · hears you, cannot speak back   │
 │  ○  മലയാളം                            ⤓ 64 MB │
 │     Malayalam · not installed                │
 │  ○  ଓଡ଼ିଆ                            [CC-BY-NC]│  Blush S chip
 │     Odia · voice is non-commercial only      │
 ├──────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────┐  │
 │  │ ⚠  Odia uses a non-commercial voice    │  │  Blush S
 │  │    model. Fine for evaluation, not for │  │  1 dp Blush T
 │  │    deployment.                         │  │
 │  └────────────────────────────────────────┘  │
 ├──────────────────────────────────────────────┤
 │  Downloads happen once, at setup. The app    │  Caption Muted
 │  never uses the network while operating.     │
 └──────────────────────────────────────────────┘
```

Families: Lilac (the list), Butter (text-only), Blush (non-commercial).

**`canSpeak` and `recognition` fail independently.** A language can be understood but not
spoken back, and four of the ten currently are. `Text only` is not a warning about quality —
it is a statement that messages in that language arrive as text and nothing is said aloud.
The row must carry it.

The Odia warning is a licence disclosure surfaced in the product rather than buried in a
file. If a permissive voice ever lands, the chip and the card both disappear.

**Indic line boxes.** Every row is `heightIn(min = 72.dp)` and the script line reserves
1.4 × its Latin height. Odia and Malayalam are the two that clip first — test with those.

```
PROMPT — 18 language

  Design a 360x800 dp Android language-selection list, light theme, ground
  #FDFCFB, 16 dp margins.

  Back header: "‹", "LANGUAGE" 22 sp bold #101010.

  Ten rows, each at least 72 dp tall with extra vertical room so Indic
  matras and conjuncts never clip. Each row: a 24 dp radio on the left, then
  a two-line stack — the language in its OWN SCRIPT at 18 sp, and beneath it
  the English gloss plus a status phrase at 13 sp #5F5F5F — then a
  right-aligned trailing element.

  The first row is selected: fill #F0EBFE, 2 dp border #7C3AED, radius 10 dp,
  radio filled #7C3AED, script "हिन्दी" in #4C1D95, gloss "Hindi · speaks and
  hears". All other rows sit on white with a hairline #E7E4E0 between and a
  hollow #7C3AED radio.

  Rows and their trailing elements:
   English  / "English · speaks and hears"           — none
   বাংলা     / "Bengali · speaks and hears"           — none
   தமிழ்     / "Tamil · hears you, cannot speak back"  — a small pill
              "Text only", fill #FEF6DC, text #713F12
   मराठी    / "Marathi · not installed"               — "⤓ 62 MB" in #7C3AED
   తెలుగు    / "Telugu · not installed"                — "⤓ 61 MB"
   ગુજરાતી   / "Gujarati · hears you, cannot speak back" — "Text only" pill
   ಕನ್ನಡ     / "Kannada · hears you, cannot speak back"  — "Text only" pill
   മലയാളം   / "Malayalam · not installed"             — "⤓ 64 MB"
   ଓଡ଼ିଆ     / "Odia · voice is non-commercial only"    — a pill "CC-BY-NC",
              fill #FFE7EA, text #9F1239

  Below the list, a disclosure card: fill #FFE7EA, 1 dp #FFC7CE, radius
  10 dp, a 24 dp warning triangle in #BE123C, text "Odia uses a
  non-commercial voice model. Fine for evaluation, not for deployment."
  16 sp #9F1239.

  Footer, 13 sp #5F5F5F: "Downloads happen once, at setup. The app never
  uses the network while operating."

  The native script must always be the largest and most prominent thing in
  its row. No emoji, no gradients.
```

---

### 19 · Mode and transport

**There is no address section.** Every transmission reaches every paired unit, exactly as a
walkie-talkie does. The unit count in band A is the whole of the roster: if it says
`6 units`, six handsets will hear you.

```
 ┌──────────────────────────────────────────────┐
 │  ‹   MODE AND TRANSPORT                      │
 ├──────────────────────────────────────────────┤
 │  MODE                                        │
 │  ┌────────────────────────────────────────┐  │
 │  │ ●  PUSH TO TALK                        │  │  96 dp
 │  │    half duplex · 800–1200 ms           │  │  Periwinkle S
 │  │    lowest power                        │  │  2 dp Periwinkle G
 │  └────────────────────────────────────────┘  │
 │  ┌────────────────────────────────────────┐  │
 │  │ ○  PHONE                               │  │  96 dp
 │  │    full duplex · 1050–1500 ms          │  │  Paper, 1 dp Rule
 │  │    higher power                        │  │
 │  └────────────────────────────────────────┘  │
 ├──────────────────────────────────────────────┤
 │  TRANSPORT                                   │
 │  ● Bluetooth        ▮▮▮▯   30 m    default   │  72 dp rows
 │  ○ Bluetooth LE     ▮▮▯▯   50 m    standby   │  Sky S when selected
 │  ○ Wi-Fi            ▯▯▯▯  150 m  not joined  │  bars Sky G
 │  ○ Radio · LoRa     ▮▮▮▮  2–15 km   paired   │  Muted when unavailable
 ├──────────────────────────────────────────────┤
 │  Switching transport keeps the pairing and   │  Caption Muted
 │  reconnects automatically.                   │
 ├──────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────┐  │
 │  │ Every message reaches every unit.      │  │  Mint S
 │  │ There is no address book.              │  │  Body 16 sp Mint I
 │  └────────────────────────────────────────┘  │
 └──────────────────────────────────────────────┘
```

Families: Periwinkle (mode), Sky (transport), Mint (the reassurance).

**Both latency figures are shown honestly.** Phone mode really is slower, and the design does
not hide it — the numbers are the argument that the trade-off was measured rather than
guessed.

The transport list is the demonstration's switch: Bluetooth to Radio · LoRa is a single tap
and nothing above the link layer changes.

```
PROMPT — 19 mode and transport

  Design a 360x800 dp Android settings sub-screen, light theme, ground
  #FDFCFB, 16 dp margins.

  Back header: "‹", "MODE AND TRANSPORT" 22 sp bold #101010.

  Section "MODE" — two 96 dp choice cards, radius 10 dp, 8 dp apart.
   Selected: fill #E8EAFF, 2 dp border #4F5BD5, a filled 24 dp radio in
   #4F5BD5, title "PUSH TO TALK" 15 sp bold #2F3A8F, and two consequence
   lines at 13 sp #5F5F5F — "half duplex · 800–1200 ms" and "lowest power".
   Unselected: white fill, 1 dp #E7E4E0, hollow radio, title "PHONE" #101010,
   lines "full duplex · 1050–1500 ms" and "higher power".

  Section "TRANSPORT" — four 72 dp rows. Each: a 24 dp radio, a name at
  16 sp, a 4-segment strength bar (8x14 dp segments, radius 4 dp, filled
  #0369A1 and empty #BEE3FB), a right-aligned range in 12 sp monospace,
  and a status word in 13 sp #5F5F5F.
   Bluetooth      3 of 4 bars   30 m    default   — selected, fill #E4F3FE,
                                                    2 dp #0369A1
   Bluetooth LE   2 of 4        50 m    standby
   Wi-Fi          0 of 4       150 m    not joined — whole row at 50% opacity
   Radio · LoRa   4 of 4      2–15 km   paired

  Footer note 13 sp #5F5F5F: "Switching transport keeps the pairing and
  reconnects automatically."

  A closing reassurance card: fill #E3F7EC, radius 10 dp, text "Every
  message reaches every unit. There is no address book." 16 sp #14532D.

  No emoji, no gradients.
```

---

### 20 · Storage and packs

Per-pack size and licence on the row. A jury asking "how big is it really" gets an answer on
screen rather than an estimate.

```
 ┌──────────────────────────────────────────────┐
 │  ‹   STORAGE                                 │
 ├──────────────────────────────────────────────┤
 │  ████████▓▓▓▓▓▒▒▒▒▒░░░░░░░░░░░░░░░░░░░░░░░   │  16 dp bar
 │  412 MB used · 11.2 GB free                  │  segments = families
 ├──────────────────────────────────────────────┤  Instrument mono
 │  ┌────────────────────────────────────────┐  │
 │  │  ⤓  INSTALL A LANGUAGE PACK            │  │  72 dp Lilac S
 │  └────────────────────────────────────────┘  │  2 dp Lilac G
 │  Put the files in a folder on this phone,    │  Caption Muted
 │  then pick that folder. No internet needed.  │
 ├──────────────────────────────────────────────┤
 │  Application + runtime + VAD          25 MB  │  Muted row
 ├──────────────────────────────────────────────┤
 │  हिन्दी   Hindi                        61 MB  │  Lilac I script
 │  ASR 34 · voice 26 · rules 1 MB          🗑  │  Instrument mono
 │  IndicConformer · Piper · permissive         │  Caption Muted
 ├──────────────────────────────────────────────┤
 │  English                              58 MB  │
 │  ASR 33 · voice 24 · rules 1 MB          🗑  │
 │  IndicConformer · Piper · permissive         │
 ├──────────────────────────────────────────────┤
 │  বাংলা   Bengali                       63 MB  │
 │  ASR 35 · voice 27 · rules 1 MB          🗑  │
 ├──────────────────────────────────────────────┤
 │  ଓଡ଼ିଆ   Odia                    ⚠     66 MB  │  Blush chip
 │  ASR 36 · voice 29 · rules 1 MB          🗑  │
 │  MMS · CC-BY-NC · non-commercial             │  Blush I
 └──────────────────────────────────────────────┘
```

Families: Lilac (packs), Blush (restrictive licence), Butter (the meter's used portion).

The delete control is Blush G and asks nothing. Deleting a pack is reversible — the files can
be re-imported — so a confirmation dialog here would be friction without a safety return, and
the application has no dialogs by design. A 60 ms haptic buzz fires *before* the action.

```
PROMPT — 20 storage and packs

  Design a 360x800 dp Android storage screen, light theme, ground #FDFCFB,
  16 dp margins.

  Back header: "‹", "STORAGE" 22 sp bold #101010.

  A 16 dp tall usage bar, radius full, spanning the width. The used portion
  (about 4%) is split into four coloured segments left to right — #7C3AED,
  #0E7C7B, #A21CAF, #1B7F3B — so the bar doubles as a legend; the remainder
  is #E7E4E0. Under it, "412 MB used · 11.2 GB free" in 12 sp monospace
  #5F5F5F.

  A primary action: 72 dp full-width button, fill #F0EBFE, 2 dp border
  #7C3AED, radius 10 dp, a 24 dp download-into-tray icon in #7C3AED and the
  label "INSTALL A LANGUAGE PACK" 15 sp bold #4C1D95. Helper text under it,
  13 sp #5F5F5F: "Put the files in a folder on this phone, then pick that
  folder. No internet needed."

  Then a plain row "Application + runtime + VAD" with a right-aligned
  "25 MB" in 12 sp monospace #5F5F5F.

  Then four pack blocks separated by hairlines #E7E4E0. Each block is three
  lines plus a trailing control:
   line 1 — the native script at 18 sp #4C1D95 followed by the English name
            at 16 sp #101010, and a right-aligned total in 12 sp monospace
   line 2 — "ASR 34 · voice 26 · rules 1 MB" in 12 sp monospace #5F5F5F,
            with a 40 dp bin icon in #BE123C right-aligned
   line 3 — the licence, 13 sp #5F5F5F
  Packs: हिन्दी Hindi 61 MB "IndicConformer · Piper · permissive";
         English 58 MB same; বাংলা Bengali 63 MB same;
         ଓଡ଼ିଆ Odia 66 MB with a small warning triangle beside the name and
         the licence line "MMS · CC-BY-NC · non-commercial" rendered in
         #9F1239 instead of grey.

  No emoji, no gradients. Line icons only.
```

---

### 21 · Language pack import **new**

**Why it exists.** The models are ~189 MB each, the installer budget is 30 MB, and this
application never fetches one — it holds no HTTP client and opens no outbound network
connection, and the `INTERNET` permission it does declare exists only so the Wi-Fi
transport can open a broadcast socket. So packs arrive via the operator's browser into
`Download/`, and the app imports them through the Storage Access
Framework — which needs no permission at all. A file called `model.int8.onnx` could be any
of ten languages; SHA-256 says which. Today that whole flow is one status string.

Four states on one screen.

```
 IDLE                              VERIFYING
 ┌──────────────────────────────┐  ┌──────────────────────────────┐
 │  ‹   INSTALL A PACK          │  │  ‹   INSTALL A PACK          │
 ├──────────────────────────────┤  ├──────────────────────────────┤
 │   ┌────┐  1                  │  │  ▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░  8/32  │
 │   │ ⤓  │  Download the pack  │  │                              │
 │   └────┘  in your browser    │  │  Checking model.int8.onnx    │
 │                              │  │  SHA-256                     │
 │   ┌────┐  2                  │  │  a3f9…c2e1                   │
 │   │ ▤  │  Put the files in   │  │                              │
 │   └────┘  one folder         │  │  ┌────────────────────────┐  │
 │                              │  │  │ ✓ हिन्दी  ASR    34 MB  │  │
 │   ┌────┐  3                  │  │  │ ✓ हिन्दी  voice  26 MB  │  │
 │   │ ✓  │  Pick that folder   │  │  │ ⟳ हिन्दी  rules        │  │
 │   └────┘  below              │  │  │ ✕ unknown file skipped │  │
 │                              │  │  └────────────────────────┘  │
 │  ┌────────────────────────┐  │  │                              │
 │  │  PICK A FOLDER         │  │  │        [  CANCEL  ]          │
 │  └────────────────────────┘  │  └──────────────────────────────┘
 │                              │
 │  No internet is used. Files  │   Lilac chips · Mint ✓ · Blush ✕
 │  are checked by SHA-256      │   Butter ⟳ · progress Lilac G
 │  before anything is copied.  │
 └──────────────────────────────┘

 DONE                              FAILED
 ┌──────────────────────────────┐  ┌──────────────────────────────┐
 │        ┌──────┐              │  │        ┌──────┐              │
 │        │  ✓   │              │  │        │  ✕   │              │
 │        └──────┘  Mint S      │  │        └──────┘  Blush S     │
 │                              │  │                              │
 │    हिन्दी installed           │  │   Nothing was installed      │
 │    61 MB · speaks and hears  │  │                              │
 │                              │  │   4 files did not match any  │
 │    2 files skipped —         │  │   known pack. A truncated    │
 │    not part of a known pack  │  │   download is refused rather │
 │                              │  │   than installed as silence. │
 │  ┌────────────────────────┐  │  │  ┌────────────────────────┐  │
 │  │        DONE            │  │  │  │      TRY AGAIN         │  │
 │  └────────────────────────┘  │  │  └────────────────────────┘  │
 └──────────────────────────────┘  └──────────────────────────────┘
```

The per-file list is the point. "Copying…" tells the operator nothing; a list that says
`✓ ASR 34 MB · ✕ unknown file skipped` tells them the importer is checking rather than
trusting, which is exactly what it does.

**The failure copy names the guarantee, not the error.** "A truncated download is refused
rather than installed as silence" is a promise about correctness; "checksum mismatch" is a
log line.

```
PROMPT — 21 language pack import

  Design four states of a 360x800 dp Android import screen, light theme,
  ground #FDFCFB, 16 dp margins. Back header "‹ INSTALL A PACK", 22 sp bold.

  STATE 1, idle — three numbered instruction rows, each 72 dp: a 40 dp chip
  #F0EBFE with a 24 dp line icon #7C3AED, a large step numeral in 22 sp bold
  #4C1D95, and instruction text 16 sp #101010.
    1 download icon — "Download the pack in your browser"
    2 storage icon  — "Put the files in one folder"
    3 tick icon     — "Pick that folder below"
  Then a 96 dp button, fill #7C3AED, white 15 sp bold "PICK A FOLDER",
  radius 20 dp. Helper 13 sp #5F5F5F: "No internet is used. Files are checked
  by SHA-256 before anything is copied."

  STATE 2, verifying — a 4 dp progress bar, track #D7C9FC fill #7C3AED, at
  25%, with "8/32" right-aligned in 12 sp monospace. Under it "Checking
  model.int8.onnx" 16 sp #101010 and "SHA-256  a3f9…c2e1" in 12 sp monospace
  #5F5F5F. Then a bordered list card, radius 10 dp, 1 dp #E7E4E0, of four
  rows in 14 sp: "✓ हिन्दी ASR 34 MB" and "✓ हिन्दी voice 26 MB" with green
  ticks #1B7F3B; "⟳ हिन्दी rules" with an amber spinner #B45309;
  "✕ unknown file skipped" with a red cross #BE123C and the row at 60%
  opacity. A 64 dp outlined "CANCEL" button at the bottom.

  STATE 3, done — centred: an 80 dp circular chip #E3F7EC with a 40 dp tick
  in #1B7F3B; "हिन्दी installed" 22 sp bold #101010; "61 MB · speaks and hears"
  16 sp #5F5F5F; "2 files skipped — not part of a known pack" 13 sp #5F5F5F;
  a 96 dp button fill #1B7F3B white "DONE".

  STATE 4, failed — centred: an 80 dp circular chip #FFE7EA with a 40 dp
  cross in #BE123C; "Nothing was installed" 22 sp bold #101010; body 16 sp
  #5F5F5F "4 files did not match any known pack. A truncated download is
  refused rather than installed as silence."; a 96 dp button, white fill,
  2 dp #FFC7CE border, label "TRY AGAIN" #9F1239.

  Line icons only. No emoji, no gradients.
```

---

### 22 · Alert self-test **new**

**Why it exists.** Vendor audio policy varies enough that alert delivery must be verifiable
on the specific handset in hand, and it lets the demonstration be rehearsed without a second
operator. Today the component is written and placed nowhere.

```
 ┌──────────────────────────────────────────────┐
 │  ‹   TEST ALERT                              │
 ├──────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────┐  │
 │  │  This sounds on THIS handset only.     │  │  Mint S
 │  │  Nothing is transmitted.               │  │  Body 16 sp Mint I
 │  └────────────────────────────────────────┘  │
 ├──────────────────────────────────────────────┤
 │  WHAT IS BEING TESTED                        │  section heading
 │  ✓  Routed to the alarm stream               │  Mint G ticks
 │  ✓  Volume forced to maximum, then restored  │  Body 16 sp Ink
 │  ✓  Audio focus held exclusively             │
 │  ✓  Device woken, full-screen intent shown   │
 │  ✓  Vibration pattern fired                  │
 │  ✓  Message spoken twice                     │
 ├──────────────────────────────────────────────┤
 │        ┌──────────────────────────┐          │
 │        │   ⚠   RUN THE TEST       │          │  96 dp Blush S
 │        └──────────────────────────┘          │  2 dp Blush T
 │                                              │
 │   Your ringer volume will be restored        │  Caption Muted
 │   afterwards.                                │
 ├──────────────────────────────────────────────┤
 │  LAST RESULT                                 │
 │  ┌────────────────────────────────────────┐  │
 │  │ ✓  Passed · 2 m ago                    │  │  Mint S
 │  │    Announced at 100 % · woke the screen│  │
 │  │    Volume restored to 40 %             │  │  Instrument mono
 │  └────────────────────────────────────────┘  │
 └──────────────────────────────────────────────┘
```

Families: Mint (what passed), Blush (the action).

Listing the six steps is the design decision here. It turns an opaque "test" button into a
checklist a reviewer can follow while the alert is firing, and it makes the *restore volume*
step visible — a step easy to forget in implementation, and one that leaves a handset
permanently at maximum alarm volume if it is missed.

```
PROMPT — 22 alert self-test

  Design a 360x800 dp Android settings sub-screen, light theme, ground
  #FDFCFB, 16 dp margins. Back header "‹ TEST ALERT" 22 sp bold #101010.

  A reassurance card: fill #E3F7EC, radius 10 dp, 16 dp padding, text
  "This sounds on THIS handset only. Nothing is transmitted." 16 sp #14532D.

  Section heading "WHAT IS BEING TESTED" uppercase letterspaced 15 sp #5F5F5F.
  Six 48 dp rows, each a 20 dp tick in #1B7F3B followed by 16 sp #101010 text:
   "Routed to the alarm stream"
   "Volume forced to maximum, then restored"
   "Audio focus held exclusively"
   "Device woken, full-screen intent shown"
   "Vibration pattern fired"
   "Message spoken twice"

  A 96 dp full-width button, fill #FFE7EA, 2 dp border #FFC7CE, radius 20 dp,
  a 32 dp warning triangle in #BE123C and the label "RUN THE TEST" 15 sp bold
  #9F1239. Helper under it, 13 sp #5F5F5F: "Your ringer volume will be
  restored afterwards."

  Section heading "LAST RESULT", then a card: fill #E3F7EC, radius 10 dp,
  a 20 dp tick #1B7F3B, "Passed · 2 m ago" 16 sp bold #14532D, and two lines
  of 12 sp monospace #14532D — "Announced at 100 % · woke the screen" and
  "Volume restored to 40 %".

  No emoji, no gradients.
```

---

### 23 · Appearance — Field Mode **new**

The one screen where the two palettes are shown side by side, so the choice is made by
looking rather than by reading.

```
 ┌──────────────────────────────────────────────┐
 │  ‹   APPEARANCE                              │
 ├──────────────────────────────────────────────┤
 │  THEME                                       │
 │  ┌─────────────────────┬────────────────────┐│
 │  │  ● SPECTRUM         │  ○ FIELD           ││  128 dp each
 │  │                     │                    ││  live previews
 │  │  ┌───────────────┐  │  ┌──────────────┐  ││
 │  │  │ ▪ LINK OK   ▪ │  │  │ ● LINK OK    │  ││  miniature of
 │  │  │┌─────────────┐│  │  │┌────────────┐│  ││  screen 06
 │  │  ││  ((•))      ││  │  ││  ((•))     ││  ││
 │  │  │└─────────────┘│  │  │└────────────┘│  ││
 │  │  │ ▪ ALERT       │  │  │  ALERT       │  ││
 │  │  └───────────────┘  │  └──────────────┘  ││
 │  │                     │                    ││
 │  │  Colourful, calm    │  Maximum contrast  ││  Caption Muted
 │  │  Indoors, briefings │  Direct sunlight   ││
 │  └─────────────────────┴────────────────────┘│
 │   Periwinkle S, 2 dp G      Paper, 1 dp Rule │
 ├──────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────┐  │
 │  │ Nothing moves when you switch. Only    │  │  Sky S
 │  │ the colours change — every control     │  │  Body 16 sp Sky I
 │  │ stays exactly where it is.             │  │
 │  └────────────────────────────────────────┘  │
 ├──────────────────────────────────────────────┤
 │  Switch automatically in bright light  [ ○]  │  toggle, off
 │  Uses the ambient light sensor. No camera.   │  Caption Muted
 ├──────────────────────────────────────────────┤
 │  TEXT SIZE                                   │
 │  A ──────●────────────── A                   │  Periwinkle G
 │  100 %                                       │  slider
 │  Follows the Android system setting.         │
 └──────────────────────────────────────────────┘
```

The reassurance card is the important part. Law 3 says the two palettes share one geometry,
and the interface should say so out loud — an operator who fears the layout will change
under them will never press the toggle, and the toggle exists for the moment they most need
it.

```
PROMPT — 23 appearance

  Design a 360x800 dp Android appearance settings screen, light theme,
  ground #FDFCFB, 16 dp margins. Back header "‹ APPEARANCE" 22 sp bold.

  Section "THEME" — two side-by-side 128 dp selection cards, 8 dp gap,
  radius 10 dp. Each contains a radio at top-left, a title, a MINIATURE
  PREVIEW of the app's main screen rendered inside it, and two caption lines.

  Left card, SELECTED: fill #E8EAFF, 2 dp border #4F5BD5, filled radio,
  title "SPECTRUM" 15 sp bold #2F3A8F. Its preview is a small phone-shaped
  rectangle showing a pastel main screen — a mint status pill, a periwinkle
  rounded hero panel with a broadcast icon, a pink alert bar, a pale blue
  strip at the bottom. Captions "Colourful, calm" and "Indoors, briefings"
  13 sp #5F5F5F.

  Right card, UNSELECTED: white fill, 1 dp #E7E4E0, hollow radio, title
  "FIELD" 15 sp bold #101010. Its preview is the SAME layout in pure
  black-on-white — identical shapes and positions, no colour except a small
  green dot and a red alert bar. Captions "Maximum contrast" and "Direct
  sunlight".

  The two previews must be recognisably the same screen. That is the point
  of the comparison.

  Below: a card, fill #E4F3FE, radius 10 dp, text "Nothing moves when you
  switch. Only the colours change — every control stays exactly where it is."
  16 sp #0C4A6E.

  Then a 72 dp toggle row: "Switch automatically in bright light" 16 sp
  #101010 with an OFF pill toggle on the right (track #E7E4E0, knob white),
  and helper "Uses the ambient light sensor. No camera." 13 sp #5F5F5F.

  Section "TEXT SIZE": a slider with a small "A" and a large "A" at each end,
  track #C7CEFF, active #4F5BD5, knob 24 dp; the value "100 %" in 12 sp
  monospace below, and helper "Follows the Android system setting."

  No emoji, no gradients.
```

---

### 24 · About and licences

Two screens. The licence list is the one place in the product where a legal obligation is
also a design problem: the disclosures must be impossible to miss without dominating.

```
 ABOUT + LICENCES                  LICENCE TEXT
 ┌──────────────────────────────┐  ┌──────────────────────────────┐
 │  ‹   LICENCES                │  │  ‹   GPL-3.0                 │
 ├──────────────────────────────┤  ├──────────────────────────────┤
 │ ┌──────────────────────────┐ │  │ GNU GENERAL PUBLIC LICENSE   │
 │ │ ⚠ This build contains    │ │  │ Version 3, 29 June 2007      │
 │ │   GPL-3.0 code. Source   │ │  │                              │
 │ │   must be offered with   │ │  │ Copyright (C) 2007 Free      │
 │ │   any distribution.      │ │  │ Software Foundation, Inc.    │
 │ └──────────────────────────┘ │  │ <https://fsf.org/>           │
 │        Blush S · Blush T     │  │                              │
 ├──────────────────────────────┤  │ Everyone is permitted to     │
 │  IN THIS BUILD               │  │ copy and distribute verbatim │
 │  sherpa-onnx                 │  │ copies of this license       │
 │  Apache-2.0                › │  │ document, but changing it is │
 │  ────────────────────────────│  │ not allowed.                 │
 │  espeak-ng                   │  │                              │
 │  GPL-3.0                   › │  │ …                            │
 │  Phonemisation only          │  │                              │
 │  ────────────────────────────│  │  11 sp monospace, Muted      │
 │  Meta MMS voices             │  │  scrollable, verbatim        │
 │  CC-BY-NC                  › │  └──────────────────────────────┘
 │  Odia only · non-commercial  │
 │  ────────────────────────────│
 │  CONSIDERED, NOT USED        │
 │  Vosk · Apache-2.0           │
 │  Heavier at equal accuracy   │
 └──────────────────────────────┘
```

Restrictive licences (GPL-3.0, CC-BY-NC) render their licence string in Blush I; permissive
ones in Muted. A `›` appears only when the full text is bundled — a chevron that leads
nowhere is worse than no chevron.

The licence text screen is the one deliberate three-taps-deep surface in the application.
`Instrument` 11 sp is permitted here and only here, because the text is verbatim and
reflowing it would alter a legal document.

```
PROMPT — 24 licences

  Design two 360x800 dp Android screens, light theme, ground #FDFCFB.

  SCREEN A — back header "‹ LICENCES" 22 sp bold #101010.
  A disclosure card at the top: fill #FFE7EA, 1 dp #FFC7CE, radius 10 dp,
  a 24 dp warning triangle #BE123C, text "This build contains GPL-3.0 code.
  Source must be offered with any distribution." 16 sp #9F1239.
  Section heading "IN THIS BUILD" uppercase letterspaced 15 sp #5F5F5F.
  Entries separated by hairlines #E7E4E0, each three lines plus an optional
  "›" chevron on the right:
    "sherpa-onnx" 16 sp #101010 / "Apache-2.0" 12 sp monospace #5F5F5F / —
    "espeak-ng" / "GPL-3.0" in 12 sp monospace #9F1239 / "Phonemisation only"
    "Meta MMS voices" / "CC-BY-NC" in #9F1239 / "Odia only · non-commercial"
  Then a second heading "CONSIDERED, NOT USED" with one entry:
    "Vosk" / "Apache-2.0" #5F5F5F / "Heavier at equal accuracy".
  A restrictive licence string is red; a permissive one is grey. The chevron
  appears only on rows whose full text is bundled.

  SCREEN B — back header "‹ GPL-3.0". A single scrolling block of verbatim
  licence text in 11 sp monospace #5F5F5F on white, 16 dp margins, generous
  line height, no reflow and no styling of any kind. It should look like a
  document, not a UI.

  No emoji, no gradients.
```

---

### 25 · Empty and error states **new**

The five empty states that exist today as bare sentences, drawn properly. None of them is a
blank region, and none is a shrug.

```
 BAND E — no traffic          MESSAGE LOG — nothing yet
 ┌──────────────────────┐     ┌──────────────────────┐
 │        ┌──────┐      │     │        ┌──────┐      │
 │        │((•)) │      │     │        │  ✉   │      │
 │        └──────┘      │     │        └──────┘      │
 │   Periwinkle S       │     │    Aqua S            │
 │   No traffic yet     │     │   Nothing received   │
 │   Hold the button    │     │   yet                │
 │   to speak           │     │   Messages appear    │
 └──────────────────────┘     │   here for 24 hours  │
                              └──────────────────────┘

 STORAGE — no packs           METRICS — no data
 ┌──────────────────────┐     ┌──────────────────────┐
 │        ┌──────┐      │     │        ┌──────┐      │
 │        │  ▤   │      │     │        │  ▦   │      │
 │        └──────┘      │     │        └──────┘      │
 │   Lilac S            │     │    Sky S             │
 │  No language packs   │     │  Nothing to plot yet │
 │  on this handset     │     │  Send or receive     │
 │  Transmit sends a    │     │  100 utterances for  │
 │  template, and       │     │  a reportable median │
 │  nothing is spoken   │     └──────────────────────┘
 │  aloud.              │
 │ ┌──────────────────┐ │      LICENCE — not bundled
 │ │ INSTALL A PACK   │ │     ┌──────────────────────┐
 │ └──────────────────┘ │     │  This licence text   │
 └──────────────────────┘     │  is not bundled with │
                              │  this build.         │
                              │  Muted, no icon —    │
                              │  a fact, not a state │
                              └──────────────────────┘
```

Anatomy, identical in all five: a 64 dp family chip with the family icon at 60 % opacity, a
`Subtitle` 18 sp Ink line naming what is absent, a `Caption` 13 sp Muted line saying what
would fill it, and an optional 64 dp action in the family surface.

**The storage one is the only one with an action**, because it is the only one the operator
can do something about from where they are standing. Adding a button to the others would be
a false affordance.

**The licence one has no icon and no chip.** It is a statement of fact about the build, not a
state the operator can leave, and dressing it up as an empty state would imply otherwise.

```
PROMPT — 25 empty states

  Design five empty-state blocks for an Android app, light theme, ground
  #FDFCFB. Four share one anatomy; the fifth deliberately does not.

  Shared anatomy, centred: a 64 dp rounded-square chip, radius 10 dp, in the
  family surface colour, holding a 32 dp line icon in the family signal
  colour at 60% opacity; below it a headline in Inter 18 sp semibold #101010;
  below that one or two lines of 13 sp #5F5F5F; and, only where specified,
  a 64 dp button.

  1. chip #E8EAFF, broadcast icon #4F5BD5 — "No traffic yet" /
     "Hold the button to speak". No button.
  2. chip #DFF7F7, envelope icon #0E7C7B — "Nothing received yet" /
     "Messages appear here for 24 hours". No button.
  3. chip #F0EBFE, storage icon #7C3AED — "No language packs on this handset"
     / "Transmit sends a template, and nothing is spoken aloud." WITH a 64 dp
     button, fill #F0EBFE, 2 dp #7C3AED, label "INSTALL A PACK" #4C1D95.
  4. chip #E4F3FE, chart icon #0369A1 — "Nothing to plot yet" / "Send or
     receive 100 utterances for a reportable median". No button.
  5. NO chip and NO icon — just one line of 13 sp #5F5F5F, left-aligned,
     "This licence text is not bundled with this build."

  Present all five on one artboard, labelled, with 32 dp gaps.
  Line icons only. No emoji, no gradients, no illustrations of people.
```

---

## 9. Accessibility conformance

### 9.1 The ten rules, mapped

`UX.md` §4 is normative and a review may reject a change for violating any of it. This table
says how each rule is discharged *visually*, so a design review can check them without
reading code.

| # | Rule | How this design satisfies it | Where |
| --- | --- | --- | --- |
| 1 | Transmit target ≥ ⅓ of screen, one-handed with gloves | Band C is `weight 1.0` against traffic's `0.9`, never below 33 % | §7.6, screen 06 |
| 2 | Every state change confirmed by haptics **and** a spoken cue, never a dialog alone | Nine haptic patterns; no dialogs, sheets or toasts exist anywhere in the design | §5.6 |
| 3 | Icons and colour carry primary meaning; text is secondary | 28 icons at 32 dp minimum; onboarding is comprehensible with all words deleted | §6, screen 02 |
| 4 | Full operation with the screen off | Nothing in band C implies touch is required; hardware-key parity is stated on the splash | §7.6 |
| 5 | High-contrast, sunlight-legible; colour never the sole carrier | Field Mode (§10); law 1 greyscale test; every state has glyph + word + colour | §1.3, §10 |
| 6 | Recognised text shown alongside spoken output | Partial strip (§7.9), confirm card (screen 13), message text in every card | §7.9 |
| 7 | Minimum touch target 64 dp | `TouchTarget = 64 dp`, `ConfirmTarget = 96 dp`, no exceptions in §7 or §8 | §4.1 |
| 8 | No screen more than two taps from operating; nothing operational more than one | Nav map §12. Licence text is the one deliberate exception at three | §12 |
| 9 | Text scales to 200 % without truncation or overlap | Every container `heightIn(min = …)`, never `size(…)`; 1.4 × Indic line box | §3.3, §4.1 |
| 10 | Every control has a content description; the operating screen is TalkBack-navigable | Per-component a11y contract in §7; reading order in §9.3 | §7, §9.3 |

### 9.2 The three gaps this design closes

These are live, in the current code, and the design must not reproduce them.

| Gap | Where | Fix |
| --- | --- | --- |
| `INDIC_LINE_HEIGHT = 1.4f` is declared and **never applied** — no `lineHeight` is set on any `Text`, so Odia and Malayalam marks rely on the default line box | `Tokens.kt:122` | §3.3 makes the 1.4 × box normative and gives the four computed values |
| `UnsecuredBanner` and `TemplateMismatchBanner` have **zero** `contentDescription` — TalkBack reads `⚠` as "warning sign" then two orphan text fragments | `SecurityBanners.kt` | §7.20 requires `clearAndSetSemantics` into one sentence, as `DegradedBanner` already does |
| Emoji glyphs `🎤` `🌡` `🗑` `⏳` render in full colour and break the palette outright | `DegradedBanner.kt`, `SettingsScreens.kt` | §6.2 replaces all of them with vector line icons; §6.3 forbids emoji anywhere, including notifications |

### 9.3 TalkBack reading order — the operating screen

The screen the operator will navigate blind, in order. Each item is **one** sentence, not a
pile of fragments.

| Focus | Announced |
| --- | --- |
| 1 | `"Settings"` |
| 2 | `"This unit, Base, node one"` |
| 3 | `"Six units in range, two messages queued"` |
| 4 | `"Link is up"` |
| 5 | `"Push to talk mode, sending to all units"` |
| 6 | `"Language, Hindi. Double tap to change."` |
| 7 | `"Push to talk. Double tap to send."` — custom action, because press-and-hold is unreachable via TalkBack |
| 8 | *(skipped)* — the level meter is `clearAndSetSemantics {}`; twelve blocks announced individually is noise |
| 9 | `"Alert. Double tap to send an alert to every unit."` |
| 10… | `"Ravi. Need help now, two injured. Spoken. Two seconds ago."` — one message row, one sentence |
| last | `"Speech to text 210 milliseconds, link 40, text to speech 180. Total 780 milliseconds. Real time factor 0.22."` |

Banners are **assertive** live regions and pre-empt this order. The partial hypothesis strip
and the transmit label are **polite** live regions, so a state change is announced without
interrupting whatever is being read.

### 9.4 Symbols are spoken as words

Never as glyph names, never as digit sequences.

| On screen | Spoken |
| --- | --- |
| `✓✓` | "delivered" |
| `✓` | "sent" |
| `○` | "queued" |
| `✕` | "not delivered" |
| `●●●○` | "high confidence" |
| `●○○○` | "low confidence" |
| `⚠` | "alert" |
| `44 B` | "44 bytes on the air" |
| `RTF 0.22` | "real time factor 0.22" |
| `⟲` | "replay" plus the message it replays |
| `—` | "not measured" |

That last one matters. `—` spoken as "dash" tells a blind operator nothing; "not measured"
tells them the same thing the dash tells a sighted one.

### 9.5 The 200 % proof

Three screens are the stress cases. A design that survives these survives the rest.

| Screen | What breaks first | Requirement |
| --- | --- | --- |
| 06 operating | Band A — unit name, count chip and link pill on one 56 dp row | The chip drops its `queued` half before anything truncates; the pill loses its word before its dot; the unit name never truncates |
| 13 confirm | The `Headline` 28 sp recognised text, which is already large | The card grows; RETAKE and SEND stay 96 dp and stay equal |
| 18 language | Indic script at 18 sp × 2 plus a gloss, plus a trailing chip | Rows grow past 72 dp; the trailing chip wraps below the gloss rather than squeezing the script |

**Nothing may respond to 200 % by shrinking type.** The layout grows and scrolls. Band F is
the single exception — it may drop to one line, because a two-line instrument strip at 200 %
would take a fifth of the screen from the transmit control.

### 9.6 Colour-vision deficiency

The nine families were chosen so that no two families carrying *opposite* meanings are
confusable under deuteranopia or protanopia.

| Pair | Risk | Mitigation |
| --- | --- | --- |
| Mint (good) vs Blush (bad) | **High** — the classic red/green failure | Different glyph (`●` vs `○`), different word (`LINK OK` vs `NO LINK`), and a large luminance gap between the two signals |
| Butter (pending) vs Apricot (degraded) | Medium | Both amber-family and both mean "attention"; confusing them is harmless |
| Periwinkle (primary) vs Lilac (identity) | Low | Never adjacent at the same size |
| Aqua (received) vs Mint (delivered) | Medium | Never on the same element — one is a card surface, the other a 16 dp tick |

Law 1 covers the rest: render any screen greyscale and it must still read.

---

## 10. Field Mode

### 10.1 What it is

One toggle. Everything that carries colour drops to `Ink` on pure white, plus the four state
colours already in `Tokens.kt`. Nothing moves.

### 10.2 The swap table

Total — every Spectrum token has a Field equivalent, and there are no orphans.

| Spectrum | Field | Note |
| --- | --- | --- |
| `Paper #FDFCFB` | `#FFFFFF` | Pure white for maximum luminance |
| `Ink #101010` | `#101010` | Unchanged |
| `Muted #5F5F5F` | `#5F5F5F` | Unchanged |
| `Rule #E7E4E0` | `#CFCFCF` | Darker; hairlines must survive glare |
| Any family **surface** | `#FFFFFF` | Surfaces vanish |
| Any family **tint** | `#CFCFCF` | Borders become neutral hairlines |
| Any family **ink** | `#101010` | All text is one ink |
| Periwinkle signal | `#101010` | The transmit panel inverts to black fill, white text |
| Mint signal | `Ok #1B7F3B` | Link up, delivered |
| Butter signal | `Warn #F2B705` | Pending, thermal, floor busy |
| Apricot signal | `Warn #F2B705` | Merges with Butter |
| Blush signal | `Alert #B3261E` | Alert, failure, unsecured |
| Sky signal | `#101010` | Instrumentation is ink |
| Aqua signal | `#101010` | Receiving is ink |
| Lilac signal | `#101010` | Identity is ink |
| Orchid signal | `#101010` | Templates are ink |
| Full-bleed alert `#C62828` | `#C62828` | **Unchanged.** Alerts never soften |
| `Raised` / `Lifted` shadows | none | Replaced by a 1 dp `#CFCFCF` border |

Six colours survive: `#FFFFFF`, `#101010`, `#5F5F5F`, `#1B7F3B`, `#F2B705`, `#B3261E`, plus
`#C62828` on the alert screen. That is exactly today's `Tokens.kt`, which is the point —
Field Mode is not new work, it is the palette the product already has, kept.

### 10.3 What must not change

Law 3, restated as a checklist a reviewer can run by flipping the toggle and watching:

- No element moves by a single dp.
- No element changes size.
- No text reflows or re-wraps.
- No icon changes shape.
- No control gains or loses a border **position** — a 2 dp signal border becomes a 2 dp
  neutral border, not a 1 dp one.
- No animation changes duration.
- No haptic changes.

If anything on that list moves, the swap has been implemented as two designs rather than one
design with two palettes, and it will drift.

### 10.4 Where the toggle lives

Three places, because the moment an operator needs Field Mode is the moment they cannot find
a settings screen:

1. **Settings → Appearance** (screen 23) — the deliberate choice, with the side-by-side preview.
2. **Automatic**, off by default, driven by the ambient light sensor. Off by default because
   an interface that changes appearance on its own, in a product where recognising the screen
   at a glance is a safety property, must be opted into.
3. **Long-press the `☰` button** on the operating screen — a 600 ms hold toggles the palette,
   with a haptic tick and the spoken cue "Field mode on". Undiscoverable by design; it is for
   the operator who has been told about it, not a feature to advertise.

### 10.5 Field Mode is not dark mode

There is no dark theme in this product and this document does not introduce one. Two reasons,
both practical:

- The operating environment is daylight and glare, not a dim room. A dark theme optimises for
  the wrong failure.
- The application has no `res/values-night/` and no `MaterialTheme` wrapper today. Adding a
  third palette triples the swap table and the review burden for a case no requirement asks
  for.

If a night patrol case ever appears, it is a third palette in the same table, not a
re-architecture — which is the whole argument for specifying the swap as a table in the first
place.

---

## 11. Hand-off

### 11.1 The target token file

One object. No private duplicates anywhere. This replaces
`app/src/main/kotlin/org/itantra/app/ui/Tokens.kt` and the eleven private colour blocks
scattered through the other UI files.

```kotlin
// One palette family. Surface fills, tint borders, signal marks, ink writes.
@Immutable
data class Family(
    val surface: Color,
    val tint: Color,
    val signal: Color,
    val ink: Color,
)

@Immutable
data class Palette(
    val paper: Color,
    val ink: Color,
    val muted: Color,
    val rule: Color,
    val inkPaper: Color,
    val periwinkle: Family,   // primary — transmit, focus
    val mint: Family,         // good — link up, delivered
    val sky: Family,          // information — metrics, evidence
    val aqua: Family,         // incoming — receiving, replay
    val lilac: Family,        // language and identity
    val orchid: Family,       // templates, cross-language
    val butter: Family,       // attention — pending, thermal
    val apricot: Family,      // degraded, self-recovering
    val blush: Family,        // alert, failure, destructive
    val alertField: Color,    // the full-bleed alert. Never softens.
)

val Spectrum = Palette(
    paper      = Color(0xFFFDFCFB),
    ink        = Color(0xFF101010),
    muted      = Color(0xFF5F5F5F),
    rule       = Color(0xFFE7E4E0),
    inkPaper   = Color(0xFFFFFFFF),
    periwinkle = Family(Color(0xFFE8EAFF), Color(0xFFC7CEFF), Color(0xFF4F5BD5), Color(0xFF2F3A8F)),
    mint       = Family(Color(0xFFE3F7EC), Color(0xFFB8ECD0), Color(0xFF1B7F3B), Color(0xFF14532D)),
    sky        = Family(Color(0xFFE4F3FE), Color(0xFFBEE3FB), Color(0xFF0369A1), Color(0xFF0C4A6E)),
    aqua       = Family(Color(0xFFDFF7F7), Color(0xFFB5EDEC), Color(0xFF0E7C7B), Color(0xFF0F4C4C)),
    lilac      = Family(Color(0xFFF0EBFE), Color(0xFFD7C9FC), Color(0xFF7C3AED), Color(0xFF4C1D95)),
    orchid     = Family(Color(0xFFFBEAFB), Color(0xFFF0CDF1), Color(0xFFA21CAF), Color(0xFF701A75)),
    butter     = Family(Color(0xFFFEF6DC), Color(0xFFFBE7A6), Color(0xFFB45309), Color(0xFF713F12)),
    apricot    = Family(Color(0xFFFFEEDF), Color(0xFFFFD5B0), Color(0xFFC2410C), Color(0xFF7C2D12)),
    blush      = Family(Color(0xFFFFE7EA), Color(0xFFFFC7CE), Color(0xFFBE123C), Color(0xFF9F1239)),
    alertField = Color(0xFFC62828),
)

// Field: the palette this product already had. Six colours, plus the alert.
private val FieldInk    = Family(Color(0xFFFFFFFF), Color(0xFFCFCFCF), Color(0xFF101010), Color(0xFF101010))
private val FieldOk     = FieldInk.copy(signal = Color(0xFF1B7F3B))
private val FieldWarn   = FieldInk.copy(signal = Color(0xFFF2B705))
private val FieldAlert  = FieldInk.copy(signal = Color(0xFFB3261E))

val Field = Spectrum.copy(
    paper = Color(0xFFFFFFFF), rule = Color(0xFFCFCFCF),
    periwinkle = FieldInk, sky = FieldInk, aqua = FieldInk,
    lilac = FieldInk, orchid = FieldInk,
    mint = FieldOk, butter = FieldWarn, apricot = FieldWarn, blush = FieldAlert,
)

val LocalPalette = staticCompositionLocalOf { Spectrum }

@Composable
fun ItantraTheme(field: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides if (field) Field else Spectrum, content = content)
}
```

Sizes stay where they are — the existing `Tokens` grid, touch targets and band heights are
correct and this document changes none of them. It adds four type sizes (§3.2), one radius
ramp (§4.2) and two elevation steps (§4.3), and it makes `INDIC_LINE_HEIGHT` load-bearing
instead of decorative (§3.3).

### 11.2 The migration this implies

Not part of this document's scope to perform, but stated so nobody is surprised:

| Step | Files |
| --- | --- |
| Delete the private colour blocks | `AlertScreens.kt:50`, `MessageLogScreen.kt:194`, `MetricsScreen.kt:279`, `PairingScreen.kt:186`, `SettingsScreens.kt:481`, `SecurityBanners.kt:136`, `DegradedBanner.kt:174` |
| Replace `Color.White` / `Color.Black` | `SecurityBanners.kt`, `DegradedBanner.kt` |
| Fix the drifted red `#EF6C60` → the token | `DegradedBanner.kt:175` |
| Wrap `setContent` in `ItantraTheme` | `MainActivity.kt` |
| Apply `lineHeight` at 1.4 × on every Indic-capable `Text` | all 13 UI files |
| Replace emoji glyphs with vector drawables | `DegradedBanner.kt`, `SettingsScreens.kt` |
| Collapse the three radii to the ramp | `OperatingScreen.kt`, `AlertScreens.kt`, `PairingScreen.kt`, `MessageLogScreen.kt`, `SettingsScreens.kt` |

A lint rule forbidding `Color(0x…)` outside the token file is worth more than any of the
individual fixes, because it is what stops the drift recurring.

### 11.3 The master prompt

One paste. Generates the whole system.

```
PROMPT — master

  Design a complete mobile design system and screen set for iTantra: an
  offline, 100% on-device emergency walkie-talkie for Android. One handset
  recognises speech locally, packs the MEANING into 13-61 bytes, broadcasts
  it over Bluetooth or a LoRa radio, and the receiving handset re-synthesises
  it aloud IN THE RECEIVER'S OWN LANGUAGE. Ten Indian languages. No SIM, no
  cloud, no network of any kind. Used by disaster-relief operators
  who wear gloves, stand in direct sunlight, often cannot look at the screen,
  and may not be able to read.

  ── THEME ──────────────────────────────────────────────────────────────
  Light, warm, colourful-pastel, premium. Ground #FDFCFB. Ink #101010.
  Muted #5F5F5F. Hairline #E7E4E0.

  Nine pastel families — surface / tint / signal / ink — each owning exactly
  one meaning. Never use one decoratively:
    Periwinkle #E8EAFF #C7CEFF #4F5BD5 #2F3A8F  primary, transmit, focus
    Mint       #E3F7EC #B8ECD0 #1B7F3B #14532D  link up, delivered, all clear
    Sky        #E4F3FE #BEE3FB #0369A1 #0C4A6E  metrics, byte counts, evidence
    Aqua       #DFF7F7 #B5EDEC #0E7C7B #0F4C4C  incoming, speaking, replay
    Lilac      #F0EBFE #D7C9FC #7C3AED #4C1D95  language, identity, packs
    Orchid     #FBEAFB #F0CDF1 #A21CAF #701A75  templates, cross-language
    Butter     #FEF6DC #FBE7A6 #B45309 #713F12  pending, queued, thermal, busy
    Apricot    #FFEEDF #FFD5B0 #C2410C #7C2D12  degraded but self-recovering
    Blush      #FFE7EA #FFC7CE #BE123C #9F1239  alert, failure, destructive
  Plus one saturated #C62828 used ONLY for the full-screen incoming alert.

  Surface fills a region, tint draws its border, signal draws the dot or icon
  that must be seen from arm's length, ink writes the text. Text is ALWAYS
  the ink — never pastel-on-pastel. Maximum three families per screen (the
  alert-compose screen is the one exception).

  ── TYPE ───────────────────────────────────────────────────────────────
  Inter. 40 / 28 / 22 / 18 / 16 / 15 / 14 / 13 sp. All numbers in 12 sp
  monospace with TABULAR figures so columns align. Nothing below 12 sp.
  Indic scripts (Devanagari, Bengali, Tamil, Telugu, Gujarati, Kannada,
  Malayalam, Odia) get 1.4x line height so matras and conjuncts never clip.
  A language is always written in its own script first, English gloss second.

  ── SHAPE, DEPTH, TARGETS ──────────────────────────────────────────────
  Radii 4 / 10 / 20 / full. Two shadow steps only: y2 blur8 at 8%, and
  y6 blur20 at 12% — both TINTED with the family ink, never neutral grey.
  Minimum touch target 64 dp (gloves). Confirmations 96 dp. 8 dp grid,
  16 dp screen margins. Reference viewport 360 x 800 dp portrait.

  ── THE MAIN SCREEN: SIX FIXED BANDS ───────────────────────────────────
  A 56 dp status  ·  B 48 dp mode + language  ·  C transmit, >= 33% of screen
  D 72 dp ALERT   ·  E flexible traffic list  ·  F 40 dp instrumentation
  Nothing moves between states. Only fills, labels and colours change.
  Band F is PERMANENT and is a designed object, not a debug view.

  ── DELIVER ────────────────────────────────────────────────────────────
  1. A colour and type sheet.
  2. A 28-component kit: link pill, unit chip, language selector and row,
     transmit panel (idle / floor-seized / live / busy / loading), level
     meter, confidence dots, partial-transcript strip, alert button, alert
     template tile, traffic row, message card, replay control, evidence
     line, delivery marks, instrument strip, degraded banner, unsecured
     banner, choice row, pack row, storage meter, histogram, figure tile,
     empty state, back header.
  3. Twenty-five screens: splash; three onboarding cards; permission
     rationale; first run; pairing; the main screen in five states (idle,
     transmitting, floor busy, receiving, phone mode); eight degraded
     banners; alert compose; confirm-before-sending; full-screen incoming
     alert; message log; metrics; settings; language; mode and transport;
     storage; pack import in four states; alert self-test; appearance;
     licences; and five empty states.
  4. A "Field Mode" variant of the main screen: the SAME geometry with all
     colour dropped to #101010 on #FFFFFF, keeping only green #1B7F3B,
     amber #F2B705 and red #B3261E for state. Nothing moves by one pixel.

  ── NON-NEGOTIABLE ─────────────────────────────────────────────────────
  · Every coloured state also carries a GLYPH and a WORD. Render any screen
    in greyscale and it must still be readable. This is a safety rule.
  · Icons carry primary meaning; text is the secondary channel. Any screen
    whose meaning collapses when the text is removed has failed.
  · NO EMOJI anywhere. Line icons, 2 dp stroke, single flat colour each.
  · No dialogs, no bottom sheets, no toasts, no snackbars — the product has
    none by design. Persistent conditions get persistent banners.
  · Confirmation screens make the safe option EXACTLY as large as the unsafe
    one. Never emphasise "send".
  · An unmeasured number renders as an em dash, never as zero. An empty list
    renders as a drawn empty state, never as blank space.
  · The incoming-alert screen is full-bleed saturated red with no close
    control and no swipe-to-dismiss.
  · No gradients (one exception: the storage usage bar). No glassmorphism,
    no neumorphism, no blur, no photography, no illustrations of people.

  Calm, confident, precise. It should look like a measuring instrument that
  happens to be beautiful, not like a consumer chat app.
```

---

## 12. Screen index and navigation map

### 12.1 Every screen

| # | Screen | Depth from operating | State in code |
| --- | --- | --- | --- |
| 01 | Splash | before | **not built** |
| 02 | Onboarding | before | **not built** |
| 03 | Permission rationale | before | **not built** |
| 04 | First run | before | **not built** — spec'd in `WIREFRAMES.md` §2 |
| 05 | Pairing | 2 taps | written, deliberately unwired until key exchange exists |
| 06 | Operating — idle | — | built |
| 07 | Operating — transmitting | — | built |
| 08 | Operating — floor busy | — | built |
| 09 | Operating — receiving | — | built |
| 10 | Operating — phone mode | — | mode switch not wired |
| 11 | Degraded banners | — | built; unsecured + template-mismatch unmounted |
| 12 | Alert compose | 1 tap | written, unwired — ALERT fires immediately today |
| 13 | Confirm before sending | 2 taps | written, unwired |
| 14 | Incoming alert | interrupts anything | written, unwired |
| 15 | Message log | 1 tap | built |
| 16 | Metrics | 2 taps | built; CSV export is a no-op |
| 17 | Settings | 1 tap | built |
| 18 | Language | 1 tap | built |
| 19 | Mode and transport | 2 taps | built; both callbacks are no-ops |
| 20 | Storage and packs | 2 taps | built; delete is a no-op |
| 21 | Pack import | 3 taps | one status string today |
| 22 | Alert self-test | 2 taps | component written, placed nowhere |
| 23 | Appearance | 2 taps | **not built** |
| 24 | Licences | 2 taps (text: 3) | built |
| 25 | Empty states | — | five bare strings today |

Nine of the twenty-five are new or unbuilt. That is the honest state of it, and a design
document that pretended otherwise would send a designer chasing screens that do not exist
and skipping ones that do.

### 12.2 The map

Rule 8: no screen is more than two taps from the operating screen, and nothing operational is
more than one.

```
   SPLASH ──► ONBOARDING ──► PERMISSIONS ──► FIRST RUN
                (first launch only, all skippable but the last)
                                                   │
                                                 START
                                                   ▼
                                          ┌────────────────┐
                       ALERT COMPOSE ◄────│                │
                            │             │   OPERATING    │────► MESSAGES
                            ▼             │  the app lives │
                       CONFIRM SEND       │      here      │────► LANGUAGE
                                          └────────────────┘
                                                   │ ☰
                                                   ▼
                                             ┌──────────┐
                                             │ SETTINGS │
                                             └──────────┘
                                                   │
      ┌──────────┬──────────┬────────┬────────┬────┴────┬──────────┐
      ▼          ▼          ▼        ▼        ▼         ▼          ▼
   ADD A UNIT  MODE &    STORAGE   METRICS  TEST     APPEARANCE  LICENCES
   (pairing)  TRANSPORT     │               ALERT                    │
                            ▼                                        ▼
                      PACK IMPORT                             LICENCE TEXT
                                                          (the one 3-tap screen)

   INCOMING ALERT interrupts any screen, including the lock screen.
   There is no roster and no address book — the unit count in band A is the
   roster, and every message goes to every unit.
```

### 12.3 Build order for the design

Match `WIREFRAMES.md` §20 so the interface is never further ahead than the engine feeding it.

| Stage | Design |
| --- | --- |
| 1 | §2 colour, §3 type, §4 shape — the tokens, and screen 06 static |
| 2 | §7 components, and screen 19's transport section |
| 3 | Screens 06, 07, 09 — the loop is visible end to end |
| 4 | Screens 08, 10, 12, 13, 14 — modes and alerts |
| 5 | Screens 05, 11 — pairing and degraded states |
| 6 | Screens 15, 17, 18, 20, 24, 25 — coverage |
| 7 | Screens 16, 21, 22, 23 — metrics, import, test, appearance |
| 8 | Screens 01, 02, 03, 04 — the entry sequence, last |

The entry sequence is last deliberately. It is the least-seen part of the product and the
most tempting to polish first; screen 06 is what a jury looks at for six of the seven
minutes.

---

<div align="center">
<sub>Team <b>Taraketu</b> · Smart India Hackathon 2026 · Problem Statement 26173</sub>
</div>
