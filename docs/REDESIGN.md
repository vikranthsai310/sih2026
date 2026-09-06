# Redesign — porting *iTantra Screens v2* onto the Compose application

The design canvas is `claude.ai/design/p/2eeea7a4…`, file **`iTantra Screens v2.dc.html`**
(23 boards, 360 × 800 dp each). This document maps every board to the composable that must
change, and sequences the work in six phases.

**The rule for every task below: the design changes, the logic does not.** No engine, no
protocol, no state model, no navigation contract is edited. `OperatingState`,
`BandFMetrics`, `AppState`, `AppActions` and `Destination` keep their present shapes and
present values; only what draws them changes. Where a board shows information the state
model does not carry, that is recorded as a **gap** rather than invented.

## 0. What the canvas contains

`v2` redesigns 23 of the 31 boards in `iTantra Screens.dc.html` (v1). Eight v1 boards have
no v2 counterpart and are **out of scope for this branch**:

| v1 board | Why it is out of scope |
| --- | --- |
| 02·1 / 02·2 / 02·3 Onboarding | No v2 redesign, and no composable exists |
| 03 Permission rationale, 03·r refused | No v2 redesign; today's flow is a system dialog |
| 04 First run | No v2 redesign |
| 05 Pairing — add a unit | `PairingScreen.kt` exists and is **deliberately unreachable** — pairing is W6.11 and the key exchange does not exist (`AppNavigation.kt:53`). Restyling a screen that is not navigable buys nothing |
| 08 Floor held by a peer | No v2 redesign |
| 16·nr Metrics — not reportable | Folded into board 16's empty state |

Two support files the canvas imports were read and are **not** design content:
`support.js` is the canvas runtime, `image-slot.js` is a drag-and-drop image placeholder.
The latter matters only in that boards 01 uses an image slot rather than a shipped
photograph — see Phase 6.

## 1. The screen map

`file:line` is the entry point in the current tree.

| # | v2 board | Current composable | Where | Nature of the change |
| --- | --- | --- | --- | --- |
| 01 | Splash — cold start | *(none)* | — | **New.** No splash exists; `MainActivity` goes straight to `ItantraApp` |
| 06 | Operating — idle | `OperatingScreen` | `OperatingScreen.kt:87` | **Rebuild.** Six stacked bands → chrome header + thread + bottom dock |
| 07 | Transmitting — live | `TransmitBand`, `LevelMeter` | `OperatingScreen.kt:382,467` | Inverting band → circular dock with halos + 7-bar equaliser |
| 07·a | Floor seized, mic not open | same, `listening=false` | `OperatingScreen.kt:382` | Distinct still state — no motion, deliberately |
| 09 | Receiving — speaking aloud | `TrafficBand`, `Spoken` | `OperatingScreen.kt:605`, `Spoken.kt` | Receive card is deleted; the arriving message **is** the thread bubble |
| 10 | Phone mode — full duplex | `ModeBand`, `TransmitBand` | `OperatingScreen.kt:293,382` | Dock stops being a button; becomes a duplex state panel |
| 11 | Degraded — link down | `DegradedBanner` | `DegradedBanner.kt:46` | Inline banner above the thread; Apricot; self-recovering copy |
| 11·a | Banner set — recovers itself | `DegradedBanner` | `DegradedBanner.kt:46` | Colour moves out of the fill and into the icon only |
| 11·b | Banner set — the rest | `DegradedBanner`, `SecurityBanners` | `DegradedBanner.kt`, `SecurityBanners.kt:40,83` | All eleven reasons, one component |
| 12 | Alert compose | `AlertComposeScreen`, `TemplateButton`, `HoldToSpeakButton` | `AlertScreens.kt:63,117,138` | Six filled tiles → Paper tiles on a hairline, colour in the 32 dp icon alone |
| 13 | Confirm before sending | `AlertConfirmScreen`, `ConfirmButton` | `AlertScreens.kt:161,201` | Equal-weight targets kept; restated in the new type scale |
| 14 | Incoming alert — locked | `IncomingAlertScreen` | `AlertScreens.kt:233` | Full-bleed alert field; identical in both palettes |
| 15 | Message log | `MessageLogScreen`, `MessageRow` | `MessageLogScreen.kt:51,84` | Rows → the same thread bubbles as board 06, evidence turned up |
| 16 | Metrics | `MetricsScreen` + 5 helpers | `MetricsScreen.kt:64–258` | One type family, two accents; histogram and stage table restyled |
| 17 | Control room — behind ☰ | `MenuScreen` | `AppNavigation.kt:259` | **Rebuild.** List → one hero unit card + 2-up tiles that each show their current value |
| 18 | Language | `LanguageScreen` | `SettingsScreens.kt:108` | Own script first; speaks/recognises-only marking |
| 19 | Mode and transport | `ModeAndTransportScreen` | `SettingsScreens.kt:42` | Every choice shows its cost |
| 20 | Storage and packs | `StorageScreen` | `SettingsScreens.kt:195` | The capacity bar doubles as the legend |
| 21 | Pack import | `StorageScreen` (import section) | `SettingsScreens.kt:195` | Two ordered steps rather than one control |
| 22 | Alert self-test | `TestAlertButton` | `AlertScreens.kt:304` | Six measured steps replacing one button |
| 23 | Text size | *(none)* | `Accessibility.kt` | **New.** Tokens exist; no screen presents them |
| 24 | About and licences | `AboutScreen`, `LicenceEntry`, `LicenceTextScreen` | `SettingsScreens.kt:375,424,470` | Set as a document rather than a settings list |
| 25 | Empty and error set | scattered | — | **New.** Shared empty/error components; "absent is not zero" |

### Gaps the map exposes

Recorded, not invented. Each needs a decision before its phase starts:

- **G1 — Board 17 tiles show current values** (language, transport, storage used, pack
  count). `AppState` already carries `languages`, `transports`, `packs`, so most resolve.
  Storage *used* is computed inside `StorageScreen` today and is not in `AppState`.
- **G2 — Board 15 shows sender name per bubble.** `LoggedMessage` carries a node id;
  whether it carries a name needs checking before the bubble header is drawn.
- **G3 — Board 09 shows a live waveform while synthesis runs.** `OperatingState` has
  `level` for capture; there is no playback level. **Resolved in phase 2:** no waveform is
  drawn in the bubble at all. The bubble carries the 2 dp signal border and the speaking
  glyph, and the only motion stays on the dock, which is driven by `level` and therefore
  measures something. A playback waveform would have been an animation with no signal
  behind it — decoration shaped exactly like instrumentation, on the one screen whose
  whole claim is that its numbers are real.
- **G4 — Board 23 (Text size)** needs a destination in the `Destination` enum. That is a
  navigation edit, which touches `AppNavigation`. Additive only — no existing route changes.
- **G5 — Field Mode.** The v2 palette is the pastel Spectrum. The monochrome palette in
  `Tokens.kt` today is the sunlight-legible one and `docs/UX.md` rule 5 requires it. Both
  survive: Spectrum is the default, Field Mode restores monochrome, and the two share one
  geometry so no layout changes between them.

## 2. The phases

Each phase ends with `:app:compileDebugKotlin` clean and the existing unit tests green
(`OperatingScreenTest`, `SpokenTextTest`, `PushToTalkKeyTest`).

### Phase 1 — Foundation

The token layer everything else is drawn from. Nothing user-visible changes alone.

- [x] **1.1** Extend `Tokens.kt` with the Spectrum palette: seven families
      (Periwinkle, Aqua, Sky, Blush, Orchid, Mint, Apricot/Butter), each with
      tint / mid / core / deep, plus the neutrals Paper `#FDFCFB`, Ground `#F7F5F2`,
      Hairline `#E7E4E0`, Ink `#101010`, Muted `#5F5F5F`.
- [x] **1.2** Keep every existing monochrome token as the **Field Mode** value. One
      `ItantraPalette` type, two instances, no third source of truth.
- [x] **1.3** `LocalPalette` CompositionLocal + `ItantraTheme` wrapper. UI-only —
      no engine state, no new `AppState` field.
- [x] **1.4** Shape tokens: 99 (pill), 28 (dock), 20 (card), 18 (tile), 16, 14, 12, 2.
- [x] **1.5** Type scale from the canvas: display 46/29/22, title 19/17, body 16/15,
      label 13, instrument 12/11/10.5. Monospace family for every numeric readout.
- [x] **1.6** Motion tokens — `halo`, `eq`, `softpulse`, `arcout`, `shimmer`, `breathe`.
      Every one under 320 ms per step and every one disabled under reduced motion.
- [x] **1.7** Icon set: port the canvas's SVG symbols to Compose `ImageVector`s
      (transmit, alert, grid, menu, plane, globe, theme, chart, qr, doc, download, play,
      lock/lockopen, …).
- [x] **1.8** Verify: existing screens still compile against the token layer unchanged.

### Phase 2 — The operating screen

The largest phase. Boards 06, 07, 07·a, 09, 10.

- [x] **2.1** Chrome header replacing bands A + B — unit, node, link dot, peer count,
      language chip, ☰.
- [x] **2.2** The thread replacing band E — received left, mine right, sender and node
      above, ticks and byte count in the bubble meta row.
- [x] **2.3** The dock replacing bands C + D — 132 dp circle, flanked by two 64 dp
      squares (ALERT, REPLAY), raised, thumb-height.
- [x] **2.4** Transmit states — idle, floor-seized-mic-closed (still), live (halos +
      equaliser), on the same geometry.
- [x] **2.5** Receiving — the arriving bubble carries the 2 dp Aqua signal border and the
      speaking indicator; no separate receive card. Resolve **G3**.
- [x] **2.6** Phone mode — the dock as a duplex panel rather than a button.
- [x] **2.7** Band F as the instrument strip under the dock; queued sends stay in the
      thread as dashed Butter bubbles rather than collapsing to a counter.
- [ ] **2.8** `OperatingScreenTest` still green; touch targets still ≥ 64 dp; 200 % type
      still does not truncate.

### Phase 3 — The alert path

Boards 12, 13, 14, 22.

- [x] **3.1** Alert compose — Paper tiles on a hairline, colour in the 32 dp icon alone,
      three carriers (icon, border, dot) so greyscale still separates.
- [x] **3.2** Dock geometry restated in Blush, so the thumb lands in the same place.
- [x] **3.3** Confirm — equal-weight targets preserved exactly.
- [x] **3.4** Incoming alert on a locked handset — full-bleed, identical in both palettes.
- [x] **3.5** Alert self-test as six measured steps.

### Phase 4 — Control room and settings

Boards 17, 18, 19, 20, 21, 23, 24.

- [ ] **4.1** Control room — hero unit card, then 2-up destination tiles showing their
      current values. Resolve **G1**.
- [ ] **4.2** Language — own script first, speaks / recognises-only marking.
- [ ] **4.3** Mode and transport — every choice shows its cost.
- [ ] **4.4** Storage — the capacity bar as its own legend.
- [ ] **4.5** Pack import — two steps, in order.
- [ ] **4.6** Text size — new screen, new `Destination`. Resolve **G4**.
- [ ] **4.7** About and licences — set as a document.

### Phase 5 — Log, metrics, banners, empty states

Boards 15, 16, 11, 11·a, 11·b, 25.

- [ ] **5.1** Message log on the Phase 2 bubble component — one bubble, two screens.
      Resolve **G2**.
- [ ] **5.2** Metrics — one family, two accents; stage table and histogram restyled;
      the not-reportable state as board 16's empty state.
- [ ] **5.3** Banner system — colour in the icon only, all eleven reasons.
- [ ] **5.4** Empty and error set — "absent is not zero"; `—` never becomes `0`.

### Phase 6 — Splash, wiring and verification

- [ ] **6.1** Splash — the −52 dp overlap, three Sky figures, load progress. The canvas
      uses a drop-in image slot; ship a drawable placeholder, not a hotlinked photo
      (the app has no network path for one).
- [ ] **6.2** Wire every new screen: `Destination`, `SubScreen`, `BackHandler`, and the
      control room tiles all reach what they claim to.
- [ ] **6.3** Full pass — `:app:assembleDebug`, unit tests, and a walk of every route
      confirming nothing is unreachable and nothing lost its action.
- [ ] **6.4** Field Mode toggle proven: both palettes, one geometry, no layout shift.

## 3. What is explicitly not touched

`core-asr`, `core-audio`, `core-link`, `core-models`, `core-proto`, `core-tts`, `bench`,
`MessageEngine`, `EngineService`, every `platform/` class, and the protocol. If a design
change appears to require an engine change, it stops and becomes a gap in §1 instead.
