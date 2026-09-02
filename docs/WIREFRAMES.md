# Wireframes

Every screen in the application, at low fidelity. These are layout and hierarchy
decisions, not visual design — the palette is high-contrast monochrome
([UX.md §4](UX.md#4-inclusive-design-rules) rule 5) and there is no branding to place.

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
 │  B   channel + language  48 dp               │
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

Shown once, before any group exists. Three cards, no prose.

```
 ┌──────────────────────────────────────────────┐
 │                                              │
 │                  i T a n t r a               │   app mark
 │                                              │
 │       Speech in. Speech out. No network.     │   14 sp
 │                                              │
 ├──────────────────────────────────────────────┤
 │                                              │
 │   ┌────────────────────────────────────┐     │
 │   │  [+]   CREATE A GROUP              │     │   96 dp
 │   │        You become the base station │     │
 │   └────────────────────────────────────┘     │
 │                                              │
 │   ┌────────────────────────────────────┐     │
 │   │  [#]   JOIN A GROUP                │     │   96 dp
 │   │        Scan the QR on another unit │     │
 │   └────────────────────────────────────┘     │
 │                                              │
 ├──────────────────────────────────────────────┤
 │  Language        [ हिन्दी          ▾ ]        │   48 dp
 │  Packs ready     Hindi · English             │
 └──────────────────────────────────────────────┘
```

The language selector is on this screen deliberately: the first interaction must be
possible for someone who does not read English.

---

## 3. Provisioning — create

```
 ┌──────────────────────────────────────────────┐
 │  ‹  CREATE GROUP                             │
 ├──────────────────────────────────────────────┤
 │  Group name    [ RESCUE-A              ]     │   64 dp field
 │  Your name     [ Base                  ]     │   64 dp field
 │  Profile       [ Flood relief        ▾ ]     │   template table
 ├──────────────────────────────────────────────┤
 │                                              │
 │        ████ ██  ████  ██ ████ ██             │
 │        ██ ████ ██  ████ ██  ████             │
 │        ████  ██ ████ ██  ██ ██               │   QR, 240 dp
 │        ██  ████  ██  ████ ████ ██            │
 │        ████ ██ ████  ██ ██  ██               │
 │                                              │
 │        Scan this on every other unit         │
 │        Expires in 1:47                       │   120 s countdown
 │                                              │
 ├──────────────────────────────────────────────┤
 │  JOINED                                      │
 │  ● Ravi        node 02        just now       │
 │  ● Meena       node 03        12 s ago       │
 ├──────────────────────────────────────────────┤
 │            [    D O N E    ]                 │   72 dp
 └──────────────────────────────────────────────┘
```

`FLAG_SECURE` is set on this screen — no screenshots, no recents thumbnail. The QR carries
the AES-256 group key, and the key must never cross the radio medium
([SECURITY.md §5](SECURITY.md#5-provisioning)).

The joined list updates live so the creator knows when to stop displaying the code, and the
countdown makes the 120 s expiry visible rather than surprising.

---

## 4. Provisioning — join

```
 ┌──────────────────────────────────────────────┐
 │  ‹  JOIN GROUP                               │
 ├──────────────────────────────────────────────┤
 │                                              │
 │      ┌────────────────────────────────┐      │
 │      │                                │      │
 │      │   ┌──┐                  ┌──┐   │      │
 │      │   │                        │   │      │   camera preview
 │      │                                │      │   with reticle
 │      │            [ scan ]            │      │
 │      │                                │      │
 │      │   │                        │   │      │
 │      │   └──┘                  └──┘   │      │
 │      └────────────────────────────────┘      │
 │                                              │
 │        Point at the QR on the other unit     │
 │                                              │
 ├──────────────────────────────────────────────┤
 │  Your name     [ Ravi                  ]     │   local only
 ├──────────────────────────────────────────────┤
 │  ENTER CODE MANUALLY                         │   fallback, text
 └──────────────────────────────────────────────┘
```

The manual-entry fallback exists for a cracked camera or a failed scan on stage. It is
deliberately the least prominent element — it requires literacy, so it can never be the
primary path.

---

## 5. Operating — push-to-talk, idle

The main screen. This is the one the jury looks at for six of the seven minutes.

```
 ┌──────────────────────────────────────────────┐
 │  ☰   RESCUE-A        6 units      ● LINK OK  │   A · 56 dp
 ├──────────────────────────────────────────────┤
 │  [▾ ALL UNITS        ]  [▾ हिन्दी         ]   │   B · 48 dp
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

## 6. Operating — transmitting

```
 ┌──────────────────────────────────────────────┐
 │  ☰   RESCUE-A        6 units      ● LINK OK  │
 ├──────────────────────────────────────────────┤
 │  [▾ ALL UNITS        ]  [▾ हिन्दी         ]   │
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

## 7. Operating — floor held by a peer

```
 ┌──────────────────────────────────────────────┐
 │  ☰   RESCUE-A        6 units      ● LINK OK  │
 ├──────────────────────────────────────────────┤
 │  [▾ ALL UNITS        ]  [▾ हिन्दी         ]   │
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

## 8. Operating — receiving and speaking

```
 ┌──────────────────────────────────────────────┐
 │  ☰   RESCUE-A        6 units      ● LINK OK  │
 ├──────────────────────────────────────────────┤
 │  [▾ ALL UNITS        ]  [▾ हिन्दी         ]   │
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
 │  RX 45 B · unpacked 20 ch · TTS 180 ms       │   byte counter
 └──────────────────────────────────────────────┘
```

The byte counter in band F is what demonstration step 3 points at. Showing `45 B` next to
`96 000 B equivalent audio` converts the compression claim from a slide into a measurement.

---

## 9. Operating — phone mode

Released push-to-talk. The primary action becomes a state display rather than a control.

```
 ┌──────────────────────────────────────────────┐
 │  ☰   RESCUE-A        6 units      ● LINK OK  │
 ├──────────────────────────────────────────────┤
 │  [▾ RAVI             ]  [▾ हिन्दी         ]   │   1:1 or group
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

## 10. Low-confidence confirmation

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

## 11. Alert compose

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
 │  Template alert = 22 B · reaches every unit  │
 │  in its own language                         │
 └──────────────────────────────────────────────┘
```

The six template buttons are the fastest path and the smallest frame — one byte of payload,
22 B on the wire authenticated. They are also the **cross-language** path: a template sent
here is announced in whatever language each receiver has selected
([PROTOCOL.md §5.1](PROTOCOL.md#51-cross-language-delivery)).

Every button is an icon plus a word, never a word alone.

---

## 12. Incoming alert — locked handset

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

## 13. Roster

```
 ┌──────────────────────────────────────────────┐
 │  ‹   RESCUE-A                    6 units     │
 ├──────────────────────────────────────────────┤
 │  ●  Base          node 01     ▮▮▮▮  now      │   72 dp rows
 │     creator · हिन्दी           88 %           │
 ├──────────────────────────────────────────────┤
 │  ●  Ravi          node 02     ▮▮▮▯  2 s      │
 │     हिन्दी                     64 %           │
 ├──────────────────────────────────────────────┤
 │  ●  Meena         node 03     ▮▮▯▯  41 s     │
 │     தமிழ்                      71 %           │
 ├──────────────────────────────────────────────┤
 │  ○  Arun          node 04     ▯▯▯▯  4 min    │   offline: 3
 │     relayed via node 02       — —            │   missed beats
 ├──────────────────────────────────────────────┤
 │  ⚠  Gateway       node FE     ▮▮▮▯  now      │
 │     LoRa relay · template mismatch           │   S-06 warning
 ├──────────────────────────────────────────────┤
 │  Tap a unit to address it directly           │
 └──────────────────────────────────────────────┘
```

Everything on this screen comes from the `HEARTBEAT` payload
([PROTOCOL.md §9](PROTOCOL.md#9-heartbeat-payload)): battery, link quality, state and
language. The template-mismatch warning on node FE is risk S-06 surfaced — that node's
template table differs, so template sending to it is disabled.

---

## 14. Message log

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
 │  45 B · ●●●○ · हिन्दी                         │
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
 │  22 B template · ✓✓ · sent in தமிழ்           │
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

## 15. Channel and mode

```
 ┌──────────────────────────────────────────────┐
 │  ‹   CHANNEL                                 │
 ├──────────────────────────────────────────────┤
 │  MODE                                        │
 │  ┌────────────────────┬───────────────────┐  │
 │  │  ● PUSH TO TALK    │  ○ PHONE          │  │   96 dp
 │  │    half duplex     │    full duplex    │  │
 │  │    500–800 ms      │    750–1100 ms    │  │   honest numbers
 │  │    lowest power    │    higher power   │  │
 │  └────────────────────┴───────────────────┘  │
 ├──────────────────────────────────────────────┤
 │  ADDRESS                                     │
 │  ● ALL UNITS            broadcast  0xFF      │
 │  ○ Ravi                 node 02              │
 │  ○ Meena                node 03              │
 ├──────────────────────────────────────────────┤
 │  TRANSPORT                                   │
 │  ● Bluetooth      ▮▮▮▯   30 m    default     │
 │  ○ Bluetooth LE   ▮▮▯▯   50 m    standby     │
 │  ○ Wi-Fi          ▯▯▯▯   150 m   not joined  │
 │  ○ Radio · LoRa   ▮▮▮▮   2–15 km  paired     │
 ├──────────────────────────────────────────────┤
 │  Switching transport keeps the group and     │
 │  reconnects automatically.                   │
 └──────────────────────────────────────────────┘
```

The transport list is the demonstration's step 8 control: switching from Bluetooth to
Radio · LoRa is a single tap, and nothing above `core-link` changes
([TRANSPORT.md §1](TRANSPORT.md#1-the-abstraction)).

---

## 16. Language

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

## 17. Settings

```
 ┌──────────────────────────────────────────────┐
 │  ‹   SETTINGS                                │
 ├──────────────────────────────────────────────┤
 │  GROUP                                       │
 │  RESCUE-A · node 01 · 6 units            ›   │
 │  Add a unit — show QR                    ›   │
 │  Rotate group key                        ›   │   re-provision
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

## 18. Packs and storage

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

## 19. Metrics

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

## 20. Degraded states

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
 │  ⛔  UNSECURED — this group is not      [i]  │   red, permanent
 │      encrypted                               │   no silent path
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

## 21. Navigation map

Rule 8: no screen is more than two taps from the operating screen, and nothing operational
is more than one.

```
                    FIRST RUN
                    /        \
              CREATE          JOIN
                    \        /
                     \      /
                 ┌──────────────┐
     ┌───────────│  OPERATING   │───────────┐
     │           └──────────────┘           │
     │            /     │     \             │
   ROSTER   MESSAGES  CHANNEL  LANGUAGE  ALERT COMPOSE
     │                   │                   │
     │              (1 tap each)        CONFIRM SEND
     │
   SETTINGS ── STORAGE · PROVISIONING · METRICS · LICENCES
   (via ☰)         (2 taps from operating)

   INCOMING ALERT interrupts any screen, including the lock screen
```

---

## 22. Build order

Wireframes map onto the roadmap so the interface is never further ahead than the engine
that feeds it.

| Week | Screens |
| --- | --- |
| 1 | 5 (static), a text field for recognised output |
| 2 | 15 transport section, a text field to send |
| 3 | 5, 6, 8 — the loop is visible end to end |
| 5 | 9, 11, 12, 7 — modes and alerts |
| 6 | 3, 4, 20 — provisioning and degraded states |
| 7 | 13, 14, 16, 17, 18 — coverage |
| 8 | 19, 2 — metrics and first run |

Screen 19 is late deliberately: it displays measurements, and there is nothing to display
until the harness produces them.
