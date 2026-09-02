# Wireframes

Every screen in the application, at low fidelity. These are layout and hierarchy
decisions, not visual design — the palette is high-contrast monochrome
([UX.md §4](UX.md#4-inclusive-design-rules) rule 5) and there is no branding to place.

> ## Rendered canvas
>
> **[`iTantra Screens.html`](iTantra%20Screens.html)** is the same seventeen screens drawn
> properly — a self-contained Claude Design canvas, openable in any browser, no build step
> and no network. Use it to *look at* the interface; use this document to *build* it.
>
> The canvas numbers the screens **01–17**. This document numbers its sections 1–20, with
> §1 the layout system and §19–20 the navigation map and build order, so **canvas `NN` is
> section `NN + 1` here**. Screen content is identical; where the two ever disagree, this
> document wins and the canvas is regenerated.
>
> The canvas shows `Redmi A3` on the metrics screen. That is an illustrative device name,
> not a decision — the real target handset is still to be acquired and recorded in
> [SETUP.md §2](SETUP.md#2-target-hardware) (risk P-02).

**Reference viewport:** 360 × 800 dp, the entry-tier target handset in portrait. Each box
below is that viewport. Annotations to the right of a box give sizes and the rule each
element satisfies.

**Script note.** Message text is shown romanised in these wireframes so the boxes stay
legible in a monospaced document. The application renders the selected script —
Devanagari, Tamil, Odia and the rest — and every text container is sized for the taller
line box that Indic conjuncts and matras require. Assume 1.4× the Latin line height
everywhere text appears.

---

## 1. Layout system

| Token | Value | Rationale |
| --- | --- | --- |
| Grid | 8 dp | |
| Screen margin | 16 dp | |
| Minimum touch target | 64 dp | Rule 7. Larger than the 48 dp platform minimum, because operators wear gloves |
| Transmit control | ≥ 33 % of screen height | Rule 1 |
| Secondary action height | 72 dp | ALERT and POSITION |
| Body text | 16 sp, scales to 200 % without truncation | Rule 9 |
| Status text | 14 sp | |
| Instrumentation strip | 12 sp, always visible | Never a debug view |
| Icon size | 32 dp minimum | Rule 3 — icons carry primary meaning |

### Persistent regions

Every operating screen has the same five bands, in the same order, at the same heights.
An operator who has learned one screen has learned all of them.

```
 ┌──────────────────────────────────────────────┐
 │  A   status bar          56 dp               │
 ├──────────────────────────────────────────────┤
 │  B   mode + language     48 dp               │
 ├──────────────────────────────────────────────┤
 │                                              │
 │  C   primary action      flexible, ≥ 33 %    │
 │                                              │
 ├──────────────────────────────────────────────┤
 │  D   secondary actions   72 dp               │
 ├──────────────────────────────────────────────┤
 │  E   recent traffic      flexible            │
 ├──────────────────────────────────────────────┤
 │  F   instrumentation     40 dp               │
 └──────────────────────────────────────────────┘
```

---

## 2. First run

One screen. No decision to make: nothing to create, nothing to join.

```
 ┌──────────────────────────────────────────────┐
 │                                              │
 │                  i T a n t r a               │   app mark
 │       Speech in. Speech out. No network.     │   14 sp
 │                                              │
 ├──────────────────────────────────────────────┤
 │  Your name     [ Base                  ]     │   64 dp, local only
 │  Language      [ हिन्दी              ▾ ]      │   48 dp
 ├──────────────────────────────────────────────┤
 │            [    S T A R T    ]               │   96 dp
 └──────────────────────────────────────────────┘
```

Pressing START generates a key, takes node 01, and goes straight to the operating screen.
The unit is now usable on its own — there is nothing to set up and nothing to join.

The language selector is on this screen deliberately: the first interaction must be
possible for someone who does not read English. A name is offered but not required.

---

## 3. Pairing

**Replaces the old create/join pair.** Every unit shows this same screen, always. It
displays its own code and can scan another's; whoever points the camera is the one who
joins.

```
 ┌──────────────────────────────────────────────┐
 │  ‹  ADD A UNIT                               │
 ├──────────────────────────────────────────────┤
 │                                              │
 │        ████ ██  ████  ██ ████ ██             │
 │        ██ ████ ██  ████ ██  ████             │
 │        ████  ██ ████ ██  ██ ██               │   QR, 240 dp
 │        ██  ████  ██  ████ ████ ██            │   FLAG_SECURE
 │        ████ ██ ████  ██ ██  ██               │
 │                                              │
 │        Show this to the other unit           │
 │        Code refreshes in 1:47                │   120 s
 │                                              │
 ├──────────────────────────────────────────────┤
 │                     or                       │
 ├──────────────────────────────────────────────┤
 │      ┌────────────────────────────────┐      │
 │      │   ┌──┐                  ┌──┐   │      │
 │      │                                │      │   camera preview
 │      │      POINT AT ANOTHER UNIT     │      │   with reticle
 │      │                                │      │
 │      │   └──┘                  └──┘   │      │
 │      └────────────────────────────────┘      │
 ├──────────────────────────────────────────────┤
 │  PAIRED                                      │
 │  ● Ravi        node 02        just now       │   updates live
 │  ● Meena       node 03        12 s ago       │
 ├──────────────────────────────────────────────┤
 │  ENTER CODE MANUALLY                         │   fallback, text
 └──────────────────────────────────────────────┘
```

Both halves are on one screen because that is what removes the decision. Two handsets on a
table: one person points at the other. Neither operator has to know, or be told, which of
them is the "creator".

`FLAG_SECURE` is set — no screenshots, no recents thumbnail. The QR carries the AES-256
shared key, and the key must never cross the radio medium
([SECURITY.md §5](SECURITY.md#5-provisioning)).

The manual-entry fallback exists for a cracked camera or a failed scan on stage. It is
deliberately the least prominent element on the screen — it requires literacy, so it can
never be the primary path.

**On a successful scan** the screen does not navigate anywhere. The `PAIRED` list gains a
row, the handset gives a haptic pulse and speaks the new unit count aloud — confirmation by
haptics and voice, not by a dialog (rule 2). Pairing is a state of this screen, not a
journey through three of them.

---

## 4. Operating — push-to-talk, idle

The main screen. This is the one the jury looks at for six of the seven minutes.

```
 ┌──────────────────────────────────────────────┐
 │  ☰   BASE · node 01   6 units     ● LINK OK  │   A · 56 dp
 ├──────────────────────────────────────────────┤
 │  PTT  ·  ALL UNITS      [▾ हिन्दी         ]   │   B · 48 dp
 ├──────────────────────────────────────────────┤
 │                                              │
 │        ┌──────────────────────────┐          │
 │        │                          │          │
 │        │           ((•))          │          │   C
 │        │                          │          │   ≥ 33 % height
 │        │      PUSH  TO  TALK      │          │   rule 1
 │        │                          │          │
 │        │                          │          │
 │        └──────────────────────────┘          │
 │                                              │
 ├──────────────────────────────────────────────┤
 │      ⚠  ALERT        │       ⌖  POSITION     │   D · 72 dp
 ├──────────────────────────────────────────────┤
 │  Ravi     need help now, two injured    2 s  │   E
 │  Base     sending a team                8 s  │
 │  Meena    position secure              41 s  │
 ├──────────────────────────────────────────────┤
 │  STT 210 · LINK 40 · TTS 180 ms              │   F · 40 dp
 │  TOTAL 780 ms · RTF 0.22 · CPU 1.8 %         │   permanent
 └──────────────────────────────────────────────┘
```

**Band F is not a debug view.** It is the single most persuasive element on the screen for
a rubric that weights latency at 20 %, and hiding it behind a developer toggle wastes it.

**Band C responds to the hardware volume-down key**, so the whole screen is operable with
the display off (rule 4).

---

## 5. Operating — transmitting

```
 ┌──────────────────────────────────────────────┐
 │  ☰   BASE · node 01   6 units     ● LINK OK  │
 ├──────────────────────────────────────────────┤
 │  PTT  ·  ALL UNITS      [▾ हिन्दी         ]   │
 ├──────────────────────────────────────────────┤
 │                                              │
 │        ████████████████████████████          │
 │        ██                        ██          │   inverted fill
 │        ██        ▂▄▆█▆▄▂▁▂▄▆     ██          │   live level meter
 │        ██                        ██          │
 │        ██      T R A N S M I T   ██          │
 │        ██                        ██          │   haptic on seize
 │        ████████████████████████████          │
 │                                              │
 ├──────────────────────────────────────────────┤
 │  "need help now, two inj…"          ●●●○     │   partial + conf
 ├──────────────────────────────────────────────┤
 │  Ravi     need help now, two injured    2 s  │
 │  Base     sending a team                8 s  │
 ├──────────────────────────────────────────────┤
 │  LISTENING …                     CPU 22 %    │
 └──────────────────────────────────────────────┘
```

The partial hypothesis is shown live so a literate operator can see what the machine heard
(rule 6) — but the design never *requires* reading it. The waveform and the inverted fill
carry the same information non-textually (rule 3).

The confidence dots `●●●○` are the two `CONFIDENCE` bits from
[PROTOCOL.md §7](PROTOCOL.md#7-flag-bits), rendered before transmission.

---

## 6. Operating — floor held by a peer

```
 ┌──────────────────────────────────────────────┐
 │  ☰   BASE · node 01   6 units     ● LINK OK  │
 ├──────────────────────────────────────────────┤
 │  PTT  ·  ALL UNITS      [▾ हिन्दी         ]   │
 ├──────────────────────────────────────────────┤
 │  ▓▓▓▓▓▓▓▓  MEENA IS SPEAKING  ▓▓▓▓▓▓▓▓▓▓▓▓   │   busy band
 ├──────────────────────────────────────────────┤
 │                                              │
 │        ┌ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┐           │
 │                                              │
 │        │       C H A N N E L     │           │   dimmed, dashed
 │                    B U S Y                   │   press → haptic
 │        │                         │           │   refusal, no
 │                                              │   dialog (rule 2)
 │        └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘           │
 │                                              │
 ├──────────────────────────────────────────────┤
 │      ⚠  ALERT        │       ⌖  POSITION     │   ALERT stays live
 ├──────────────────────────────────────────────┤
 │  Ravi     need help now, two injured    2 s  │
 ├──────────────────────────────────────────────┤
 │  FLOOR HELD BY node 03 · 3 s                 │
 └──────────────────────────────────────────────┘
```

`ALERT` remains enabled while the floor is held. Alert frames pre-empt the transmit queue
([PROTOCOL.md §12](PROTOCOL.md#12-reliability)) — an emergency must never wait for someone
else to stop talking.

---

## 7. Operating — receiving and speaking

```
 ┌──────────────────────────────────────────────┐
 │  ☰   BASE · node 01   6 units     ● LINK OK  │
 ├──────────────────────────────────────────────┤
 │  PTT  ·  ALL UNITS      [▾ हिन्दी         ]   │
 ├──────────────────────────────────────────────┤
 │                                              │
 │   ┌────────────────────────────────────┐     │
 │   │  ((  RAVI  ))                      │     │   speaker card
 │   │                                    │     │
 │   │  need help now, two injured        │     │   text shown
 │   │                                    │     │   alongside audio
 │   │  ▶ ▁▃▅▇▅▃▁▃▅▇▅▃▁▃▅▇▅▃▁             │     │   playback bar
 │   │                              ⟲     │     │   replay 64 dp
 │   └────────────────────────────────────┘     │
 │                                              │
 ├──────────────────────────────────────────────┤
 │      ⚠  ALERT        │       ⌖  POSITION     │
 ├──────────────────────────────────────────────┤
 │  Base     sending a team                8 s  │
 ├──────────────────────────────────────────────┤
 │  RX 44 B · unpacked 20 ch · TTS 180 ms       │   byte counter
 └──────────────────────────────────────────────┘
```

The byte counter in band F is what demonstration step 3 points at. Showing `44 B` next to
`96 000 B equivalent audio` converts the compression claim from a slide into a measurement.

---

## 8. Operating — phone mode

Released push-to-talk. The primary action becomes a state display rather than a control.

```
 ┌──────────────────────────────────────────────┐
 │  ☰   BASE · node 01   6 units     ● LINK OK  │
 ├──────────────────────────────────────────────┤
 │  PHONE  ·  ALL UNITS    [▾ हिन्दी         ]   │   mode + language
 ├──────────────────────────────────────────────┤
 │                                              │
 │        ┌──────────────────────────┐          │
 │        │        ((( )))           │          │
 │        │                          │          │   open channel
 │        │      O P E N   L I N E   │          │   VAD-gated
 │        │                          │          │
 │        │    ▂▄▆█▆▄▂  listening    │          │
 │        └──────────────────────────┘          │
 │                                              │
 │            [  ⏸  HOLD  ]  [  ✕  END  ]       │   64 dp each
 ├──────────────────────────────────────────────┤
 │      ⚠  ALERT        │       ⌖  POSITION     │
 ├──────────────────────────────────────────────┤
 │  Ravi     need help now, two injured    2 s  │
 ├──────────────────────────────────────────────┤
 │  FULL DUPLEX · TOTAL 940 ms · CPU 31 %       │   higher than PTT
 └──────────────────────────────────────────────┘
```

Band F shows a visibly higher total than PTT mode — 940 ms against 780 ms — because the
400 ms endpoint window replaces the key release. That difference is a designed trade-off
and showing it honestly is better than hiding it.

---

## 9. Low-confidence confirmation

Shown before transmission when recogniser confidence is low, and **always** for
alert-class messages (risk S-03).

```
 ┌──────────────────────────────────────────────┐
 │  ⚠  CHECK BEFORE SENDING                     │
 ├──────────────────────────────────────────────┤
 │                                              │
 │   ┌────────────────────────────────────┐     │
 │   │                                    │     │
 │   │   do not evacuate sector seventeen │     │   24 sp, large
 │   │                                    │     │
 │   │   confidence  ●○○○   low           │     │
 │   └────────────────────────────────────┘     │
 │                                              │
 │            [  ▶  HEAR IT BACK  ]             │   72 dp
 │                                              │
 │   Spoken aloud automatically on open, so     │
 │   this screen works without reading.         │
 │                                              │
 ├──────────────────────────────────────────────┤
 │                      │                       │
 │      ✕  RETAKE       │       ✓  SEND         │   96 dp each,
 │                      │                       │   equal size
 └──────────────────────────────────────────────┘
```

Two rules govern this screen. The text is **spoken aloud on open**, so the confirmation is
usable by a non-literate operator (rule 2). And RETAKE and SEND are **exactly equal in
size** — a confirmation dialog that makes the safe option smaller is not a confirmation.

This is the one place the system deliberately adds latency, because an alert is the only
message type that can cause physical harm if it is wrong.

---

## 10. Alert compose

```
 ┌──────────────────────────────────────────────┐
 │  ‹   SEND ALERT                              │
 ├──────────────────────────────────────────────┤
 │  Alerts are announced at full volume on      │
 │  every unit, even locked and silenced.       │
 ├──────────────────────────────────────────────┤
 │  ┌──────────────────┬───────────────────┐    │
 │  │   ✚  MEDICAL     │   ▲  FIRE         │    │   template codes
 │  ├──────────────────┼───────────────────┤    │   96 dp each,
 │  │   ≈  FLOOD       │   ⌂  EVACUATE     │    │   icon-primary
 │  ├──────────────────┼───────────────────┤    │   (rule 3)
 │  │   ⌖  EXTRACT     │   ✓  ALL CLEAR    │    │
 │  └──────────────────┴───────────────────┘    │
 ├──────────────────────────────────────────────┤
 │        ┌──────────────────────────┐          │
 │        │   ●  HOLD TO SPEAK ALERT │          │   free-form
 │        └──────────────────────────┘          │   alert
 ├──────────────────────────────────────────────┤
 │  ☐  Attach my position                       │
 ├──────────────────────────────────────────────┤
 │  Template alert = 21 B · reaches every unit  │
 │  in its own language                         │
 └──────────────────────────────────────────────┘
```

The six template buttons are the fastest path and the smallest frame — one byte of payload,
21 B on the wire authenticated. They are also the **cross-language** path: a template sent
here is announced in whatever language each receiver has selected
([PROTOCOL.md §5.1](PROTOCOL.md#51-cross-language-delivery)).

Every button is an icon plus a word, never a word alone.

---

## 11. Incoming alert — locked handset

The full-screen intent. This is what demonstration step 5 produces on a locked, silenced
phone.

```
 ┌──────────────────────────────────────────────┐
 │██████████████████████████████████████████████│
 │██                                          ██│
 │██                  ⚠                       ██│   96 dp icon
 │██                                          ██│
 │██            A L E R T                     ██│   inverted,
 │██                                          ██│   full bleed
 │██        FROM  RAVI  ·  node 02            ██│
 │██                                          ██│
 │██  ┌────────────────────────────────────┐  ██│
 │██  │                                    │  ██│
 │██  │  MEDICAL ASSISTANCE NEEDED         │  ██│   32 sp
 │██  │                                    │  ██│   receiver's own
 │██  │  20.29 N  85.82 E                  │  ██│   language
 │██  └────────────────────────────────────┘  ██│
 │██                                          ██│
 │██     ▶ repeating · 2 of 2                 ██│   spoken twice
 │██                                          ██│
 │██      [    ACKNOWLEDGE    ]               ██│   96 dp
 │██                                          ██│
 │██████████████████████████████████████████████│
 └──────────────────────────────────────────────┘
```

Behind this screen, all six steps of
[UX.md §3](UX.md#3-alert-delivery) are running: alarm-stream routing, forced volume,
exclusive focus with loss callbacks ignored, wake lock, vibration, and a repeat. Dismissal
requires the ACKNOWLEDGE target — there is no swipe-away, because a swipe is something a
pocket can do.

---

## 12. Message log

```
 ┌──────────────────────────────────────────────┐
 │  ‹   MESSAGES                  last 24 h     │
 ├──────────────────────────────────────────────┤
 │  ────────────  today 02:14  ────────────     │
 │                                              │
 │  Ravi                                  2 s   │
 │  ┌────────────────────────────────────┐      │
 │  │ need help now, two injured      ⟲  │      │   replay 64 dp
 │  └────────────────────────────────────┘      │
 │  44 B · ●●●○ · हिन्दी                         │
 │                                              │
 │                                  me   8 s    │
 │       ┌────────────────────────────────┐     │
 │       │ sending a team              ⟲  │     │
 │       └────────────────────────────────┘     │
 │                          32 B · ✓✓ delivered │
 │                                              │
 │  ⚠ ALERT  Meena                       41 s   │
 │  ┌────────────────────────────────────┐      │
 │  │ MEDICAL ASSISTANCE NEEDED       ⟲  │      │
 │  └────────────────────────────────────┘      │
 │  21 B template · ✓✓ · sent in தமிழ்           │
 │                                              │
 │  ○ queued   position secure          pending │   store & forward
 └──────────────────────────────────────────────┘
```

Every message shows its frame size, and template messages show the language they were
*sent* in versus rendered in. Delivery state — pending, sent, delivered — is the
store-and-forward state from [PROTOCOL.md §12](PROTOCOL.md#12-reliability).

Retained 24 h, then deleted ([SECURITY.md §6](SECURITY.md#6-privacy)). Captured audio is
never stored; the replay control re-synthesises from text.

---

## 13. Mode and transport

```
 ┌──────────────────────────────────────────────┐
 │  ‹   MODE AND TRANSPORT                      │
 ├──────────────────────────────────────────────┤
 │  MODE                                        │
 │  ┌────────────────────┬───────────────────┐  │
 │  │  ● PUSH TO TALK    │  ○ PHONE          │  │   96 dp
 │  │    half duplex     │    full duplex    │  │
 │  │    500–800 ms      │    750–1100 ms    │  │   honest numbers
 │  │    lowest power    │    higher power   │  │
 │  └────────────────────┴───────────────────┘  │
 ├──────────────────────────────────────────────┤
 │  TRANSPORT                                   │
 │  ● Bluetooth      ▮▮▮▯   30 m    default     │
 │  ○ Bluetooth LE   ▮▮▯▯   50 m    standby     │
 │  ○ Wi-Fi          ▯▯▯▯   150 m   not joined  │
 │  ○ Radio · LoRa   ▮▮▮▮   2–15 km  paired     │
 ├──────────────────────────────────────────────┤
 │  Switching transport keeps the pairing and   │
 │  reconnects automatically.                   │
 └──────────────────────────────────────────────┘
```

**There is no address section.** Every transmission reaches every paired unit, exactly as
a walkie-talkie does — see [PROTOCOL.md §8](PROTOCOL.md#8-addressing). The unit count in
the status bar is the whole of the roster: if it says `6 units`, six handsets will hear
you.

The transport list is the demonstration's step 8 control: switching from Bluetooth to
Radio · LoRa is a single tap, and nothing above `core-link` changes
([TRANSPORT.md §1](TRANSPORT.md#1-the-abstraction)).

---

## 14. Language

```
 ┌──────────────────────────────────────────────┐
 │  ‹   LANGUAGE                                │
 ├──────────────────────────────────────────────┤
 │  ● हिन्दी          Hindi         ✓ installed  │   72 dp rows
 │  ○ English        English       ✓ installed  │
 │  ○ বাংলা          Bengali       ✓ installed  │
 │  ○ தமிழ்           Tamil         ✓ installed  │
 │  ○ मराठी           Marathi       ↓ 62 MB     │   tap to fetch
 │  ○ తెలుగు          Telugu        ↓ 61 MB     │
 │  ○ ગુજરાતી         Gujarati      ↓ 58 MB     │
 │  ○ ಕನ್ನಡ            Kannada       ↓ 63 MB     │
 │  ○ മലയാളം          Malayalam     ↓ 64 MB     │
 │  ○ ଓଡ଼ିଆ            Odia          ↓ 66 MB  ⚠  │   non-commercial
 ├──────────────────────────────────────────────┤
 │  ⚠ Odia uses a non-commercial voice model.   │
 │    Fine for evaluation; not for deployment.  │
 ├──────────────────────────────────────────────┤
 │  Downloads happen once, at setup.            │
 │  The app never uses the network while        │
 │  operating.                                  │
 └──────────────────────────────────────────────┘
```

Each language is written **in its own script first**, English gloss second — the list must
be usable by someone who cannot read the others.

The Odia warning is the CC-BY-NC disclosure from
[LICENSES.md §6](../LICENSES.md#6-restrictive-licences--disclosed-not-discovered) surfaced
in the product, not buried in a file. If the Coqui training path completes (risk T-05) the
warning disappears.

---

## 15. Settings

```
 ┌──────────────────────────────────────────────┐
 │  ‹   SETTINGS                                │
 ├──────────────────────────────────────────────┤
 │  UNITS                                       │
 │  This unit — Base · node 01              ›   │
 │  Add a unit — show QR                    ›   │
 │  Rotate shared key                       ›   │   re-provision
 ├──────────────────────────────────────────────┤
 │  LANGUAGE AND PACKS                          │
 │  Active language          हिन्दी          ›   │
 │  Storage                  412 MB         ›   │
 ├──────────────────────────────────────────────┤
 │  AUDIO                                       │
 │  Test alert on this device               ›   │   vendor policy
 │  Noise suppression        ● on               │   check
 │  Voice per sender         ● on               │
 ├──────────────────────────────────────────────┤
 │  MEASUREMENT                                 │
 │  Live metrics             ● shown            │
 │  Export scorecard                        ›   │
 ├──────────────────────────────────────────────┤
 │  ABOUT                                       │
 │  Version · build                         ›   │
 │  Open-source licences                    ›   │
 └──────────────────────────────────────────────┘
```

**Test alert on this device** exists because vendor audio policy varies enough that alert
delivery must be verifiable on each specific handset — and because it lets demonstration
step 5 be rehearsed without a second operator.

---

## 16. Packs and storage

```
 ┌──────────────────────────────────────────────┐
 │  ‹   STORAGE                                 │
 ├──────────────────────────────────────────────┤
 │  ████████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   │
 │  412 MB used · 11.2 GB free                  │
 ├──────────────────────────────────────────────┤
 │  Application + runtime + VAD        25 MB    │
 ├──────────────────────────────────────────────┤
 │  हिन्दी   Hindi                               │
 │  ASR 34 MB · voice 26 MB · rules 1 MB   [🗑]  │   61 MB
 │  IndicConformer · Piper · permissive         │
 ├──────────────────────────────────────────────┤
 │  English                                     │
 │  ASR 33 MB · voice 24 MB · rules 1 MB   [🗑]  │   58 MB
 ├──────────────────────────────────────────────┤
 │  বাংলা   Bengali                             │
 │  ASR 35 MB · voice 27 MB · rules 1 MB   [🗑]  │   63 MB
 ├──────────────────────────────────────────────┤
 │  ଓଡ଼ିଆ   Odia                             ⚠   │
 │  ASR 36 MB · voice 29 MB · rules 1 MB   [🗑]  │   66 MB
 │  MMS · CC-BY-NC · non-commercial             │
 └──────────────────────────────────────────────┘
```

Per-pack licence is shown on the row. A jury asking "how big is it really" gets an answer
on screen rather than an estimate, and the licence disclosure is impossible to miss.

---

## 17. Metrics

```
 ┌──────────────────────────────────────────────┐
 │  ‹   METRICS               142 utterances    │
 ├──────────────────────────────────────────────┤
 │  END TO END                                  │
 │       │                                      │
 │   30  │        ▂▆█▆▃                         │
 │       │      ▁▄████▇▃▁                       │   histogram
 │       │   ▁▂▆███████▆▄▂▁                     │
 │     0 └──────────────────────────────────    │
 │       400   600   800  1000  1200 ms         │
 │                                              │
 │  median 780 ms · p95 1040 ms                 │   never a single
 ├──────────────────────────────────────────────┤
 │  BY STAGE                          median    │
 │  capture                              32 ms  │
 │  endpoint window                     150 ms  │
 │  decode after endpoint               178 ms  │
 │  frame · encrypt · tx                 40 ms  │
 │  normalise                             6 ms  │
 │  first synthesis chunk               182 ms  │
 │  output                               48 ms  │
 ├──────────────────────────────────────────────┤
 │  RTF asr 0.22 · tts 0.16 · CPU 1.8 % idle    │
 │  soak 34 min · Redmi A3 · release build      │   device named
 ├──────────────────────────────────────────────┤
 │  [ EXPORT latency.csv · resource.csv ]       │   72 dp
 └──────────────────────────────────────────────┘
```

Median **and** p95 over 142 utterances, with the device and soak duration named on screen.
A single best-case number is not a measurement, and
[EVALUATION.md §7](EVALUATION.md#7-reporting-rules) forbids reporting one.

---

## 18. Degraded states

Every degraded state is a banner on the operating screen, with a reason string. A system
that silently stops working is worse than one that says it has stopped.

```
 ┌──────────────────────────────────────────────┐
 │  ⚠  LINK DOWN — reconnecting, 12 s      [i]  │   amber
 └──────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────┐
 │  ⚠  MICROPHONE IN USE BY A CALL         [i]  │   amber · T-12
 └──────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────┐
 │  ⚠  THERMAL — reduced to 1 thread       [i]  │   amber · T-03
 └──────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────┐
 │  ⚠  TEMPLATE MISMATCH — node FE         [i]  │   amber · S-06
 └──────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────┐
 │  ⛔  UNSECURED — no encryption          [i]  │   red, permanent
 │      on this pairing                         │   no silent path
 └──────────────────────────────────────────────┘

 ┌──────────────────────────────────────────────┐
 │  ⏳  LOADING MODELS — 1.4 s                  │   transmit
 └──────────────────────────────────────────────┘   disabled · T-11
```

The UNSECURED banner is red, permanent, and cannot be dismissed
([PROTOCOL.md §6.5](PROTOCOL.md#65-unauthenticated-operation)). The loading banner disables
the transmit control until state is `READY`, so the cold-start cost is never paid on a key
press (risk T-11).

---

## 19. Navigation map

Rule 8: no screen is more than two taps from the operating screen, and nothing operational
is more than one.

```
                FIRST RUN
                    │
                  START            one path in, no branch
                    ▼
             ┌──────────────┐
             │  OPERATING   │   ◄── the application lives here
             └──────────────┘
              │      │      │
              │      │      └──────────► ALERT COMPOSE
              │      │                          │
              │      └────► LANGUAGE            ▼
              │                           CONFIRM SEND
              └───────────► MESSAGES

                        (1 tap each)

   SETTINGS ── MODE & TRANSPORT · STORAGE · ADD A UNIT · METRICS · LICENCES
   (via ☰)                (2 taps from operating)

   INCOMING ALERT interrupts any screen, including the lock screen

   No roster and no address book. The unit count in the status bar is the
   roster, and every message goes to every unit.
```

---

## 20. Build order

Wireframes map onto the roadmap so the interface is never further ahead than the engine
that feeds it.

| Week | Screens |
| --- | --- |
| 1 | 4 (static), a text field for recognised output |
| 2 | 13 transport section, a text field to send |
| 3 | 4, 5, 7 — the loop is visible end to end |
| 5 | 6, 8, 9, 10, 11 — modes and alerts |
| 6 | 3, 18 — pairing and degraded states |
| 7 | 12, 14, 15, 16 — coverage |
| 8 | 17, 2 — metrics and first run |

Screen 17 is late deliberately: it displays measurements, and there is nothing to display
until the harness produces them.

**Nine distinct screens**, five of them states of the operating screen rather than pages of
their own. That is the entire application. Every screen removed — the roster, the address
book, the create/join branch, the scan-confirmation page — is a screen nobody has to build,
in an eight-week schedule that has not started.
