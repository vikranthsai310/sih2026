# Interface and interaction

The justification for this project is that it serves people who cannot type. **An interface
that assumes literacy would refute its own premise.** Every rule below descends from that
sentence.

> This document specifies interaction — modes, policy, rules. Every screen is drawn at low
> fidelity in [WIREFRAMES.md](WIREFRAMES.md), including the state variants (transmitting,
> floor busy, receiving, degraded) that a static list of screens cannot show, and rendered
> properly in [`iTantra Screens.html`](iTantra%20Screens.html) — open it in a browser to
> see the interface rather than read it.

## 1. The operating screen

```
 ┌──────────────────────────────────────────────┐
 │  BASE · node 01      6 units      ● LINK OK  │
 ├──────────────────────────────────────────────┤
 │   PTT · ALL UNITS                 हिन्दी  ▾   │
 ├──────────────────────────────────────────────┤
 │                                              │
 │                                              │
 │              P U S H   T O   T A L K         │
 │                                              │
 │                  (≥ ⅓ of screen)             │
 │                                              │
 ├──────────────────────────────────────────────┤
 │      ALERT      │        POSITION            │
 ├──────────────────────────────────────────────┤
 │  Ravi     हमें तुरंत मदद चाहिए          2 s  │
 │  Base     टीम भेज रहे हैं                8 s  │
 ├──────────────────────────────────────────────┤
 │  STT 210 ms  ·  LINK 40 ms  ·  TTS 180 ms    │
 │  TOTAL 780 ms  ·  RTF 0.22  ·  CPU 1.8 %     │
 └──────────────────────────────────────────────┘
```

**The instrumentation strip is permanent, not a debug view.** It is the single most
persuasive element on the screen in front of a jury scoring 20 % on latency, and hiding it
behind a developer toggle wastes it.

## 2. The two modes

| Aspect | Push-to-talk — walkie-talkie | Released — telephone |
| --- | --- | --- |
| Duplex | Half. One speaker holds the channel | Full. Both directions stream continuously |
| Capture | Live only while the key is held | Always live, gated by VAD |
| Endpoint | Key release, 150 ms confirmation | 400 ms trailing silence |
| Speaker | Muted while transmitting | Active, with barge-in ducking |
| Floor control | `PTT_CTL` frames announce and release the floor; a busy indicator prevents collisions | Not applicable |
| Power | Lowest — no idle inference | Higher — continuous VAD |
| Latency | 800–1200 ms | 1050–1500 ms |

The transmit control is bound both to a large on-screen target **and to the volume-down
hardware key**, because operators wear gloves and rarely look at the screen. Releasing the
key is an explicit end-of-utterance signal, which is why push-to-talk mode records lower
latency than telephone mode — worth demonstrating live rather than explaining.

### Floor states

| State | Indicator | Behaviour |
| --- | --- | --- |
| Free | Neutral | Transmit permitted |
| Held by me | Transmit control lit, haptic on seize | Speaker muted |
| Held by peer | Busy, peer's name shown | Transmit blocked; a press produces a short haptic refusal, never a dialog |
| Contended | Both seized within the collision window | Randomised backoff, both told to retry (risk S-05) |

## 3. Alert delivery

The requirement (R8) is that alerts are "announced at highest volume, non-interruptible".
On Android this is a specific and verifiable sequence, and it is the whole of it — each
step exists because omitting it produces a silent alert in some real configuration.

| Step | Mechanism | Why |
| --- | --- | --- |
| 1. Route away from media | `AudioAttributes.USAGE_ALARM` with `CONTENT_TYPE_SONIFICATION` | Bypasses media volume and, when configured, Do Not Disturb |
| 2. Force volume | `setStreamVolume(STREAM_ALARM, max, 0)` before playback; the prior level is restored afterwards | A silenced handset must still announce |
| 3. Hold focus | `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, and **deliberately ignore loss callbacks** | This is the non-interruptible requirement, precisely |
| 4. Wake the device | `PARTIAL_WAKE_LOCK` plus a full-screen-intent notification | Delivery must succeed on a locked screen |
| 5. Reinforce | Vibration pattern, high-contrast full-screen visual, message repeated twice | Redundant channels for a noisy environment and a hearing-impaired operator |
| 6. Guarantee arrival | `ALERT` frames pre-empt the transmit queue and are acknowledged and retried | See [PROTOCOL.md §12](PROTOCOL.md#12-reliability) |

**Restoring the prior volume in step 2 is mandatory** and easy to forget. Leaving a
handset permanently at maximum alarm volume after one alert is a defect that will be found
during a demonstration.

### Sending an alert

Alert-class messages require **explicit confirmation** of the recognised text before
transmission (risk S-03). The confirmation is a single large button showing the recognised
text and speaking it aloud — so it works for a non-literate operator — with a cancel target
of equal size. This is the one place where the system deliberately adds latency, and the
reason is that an alert is the only message type that can cause physical harm if it is
wrong.

## 4. Inclusive design rules

Normative. A review may reject a change for violating any of these.

1. The transmit target occupies **at least a third of the screen** and is reachable
   one-handed with gloves.
2. Every state change is confirmed by **haptics and a spoken cue**, never by a text dialog
   alone.
3. **Icons and colour carry primary meaning; text is a secondary channel.** Any screen
   whose meaning collapses when the text is removed has failed this rule.
4. **Full operation with the screen off**, via the hardware key.
5. **High-contrast monochrome palette**, legible in direct sunlight. Colour is used only
   for state (link, alert, floor), never as the sole carrier of information.
6. Recognised text is displayed alongside the spoken output, so a **literate** operator can
   verify what the machine heard — without requiring literacy to use the system.
7. Minimum touch target 64 dp; the transmit control far exceeds it.
8. No screen requires more than **two taps** from the operating screen. Settings may be
   deeper; nothing operational may be.
9. Text scales to 200 % without truncation or overlap.
10. Every control has a content description, and the whole operating screen is navigable by
    TalkBack.

Rule 3 is the one that gets violated. "Add a label" is the reflex fix for an unclear
control; the correct fix is a clearer icon plus the label.

## 5. Pairing

**There is no group to create and none to join.** Users are not asked to enter
identifiers, and they are not asked to decide which kind of device they are.

Every unit does exactly the same thing: it shows its own code, and it can scan another's.

```
 EVERY UNIT, ALWAYS THE SAME SCREEN
 ──────────────────────────────────
 On first launch, with nothing to scan:
   ├ generates a 256-bit AES key        → Keystore
   ├ derives KEYID = SHA-256(key)[0]
   ├ takes node 01, template profile
   └ is immediately usable, alone

 When it scans another unit's code:
   ├ adopts that unit's key and profile
   ├ claims the next free node ID
   └ discards its own key
```

The asymmetry that used to be a screen — creator versus joiner — is now decided by who
points the camera. Whoever scans, joins. Nobody has to know which one they are.

Display names are local to each device. Pairing is the most common point of failure in live
demonstrations, and reducing it to *point at the other phone* removes both the failure and
the decision (risk P-03). Security properties of the optical path are in
[SECURITY.md §5](SECURITY.md#5-provisioning).

### Why there are no groups, and no addresses

The `GRP` byte that used to carry a channel number is gone; the header now carries `KEYID`,
derived from the key and never shown to anyone. Channels were doing no work the key was not
already doing — a unit that does not hold the key fails the authentication tag and discards
the frame regardless of what channel byte it saw. What is left is pairing, which is
unavoidable: the key has to reach the other handset somehow.

Two teams operating in the same place still do not hear each other, because they hold
different keys. The only capability given up is one handset holding several keys and
switching between them with a dial — which no requirement asks for, and which the byte is
still on the wire to support if it is ever wanted.

The destination byte went the same way, and for the same reason. ISRO asks for something
that "should work like a walkie talkie"; a walkie-talkie has no address book, and if you
can hear the channel you hear everything on it. `DST` is gone, the header is 10 bytes, and
the roster and address-selector screens went with it. **Nine screens remain** — see
[WIREFRAMES.md](WIREFRAMES.md).

This is a one-way door for private messaging. If unit-to-unit calls are ever wanted, `DST`
returns and the protocol version increments.

## 6. Screens

| Screen | Contents | Depth from operating screen |
| --- | --- | --- |
| Operating | The screen in §1 | — |
| Message log | Last 24 h of sent and received text with delivery state, replayable as audio. Sender names here replace the roster | 1 tap |
| Mode and transport | PTT/phone toggle and transport selector. **No address selector** — every message reaches every unit | 1 tap |
| Language | Ten languages, showing which packs are installed | 1 tap |
| Settings | Packs and storage, provisioning, alert test, metrics export, about and licences | 2 taps |
| Provisioning | QR display or scan | 2 taps |
| Metrics | Latency histogram, resource graph, CSV export | 2 taps |
| Locate list | Every unit heard this run, nearest first, with signal bars | 1 tap |
| Locate | The walk to one unit: an arrow, a signal bar, a distance figure and a siren | 2 taps |

Each of these is drawn in [WIREFRAMES.md](WIREFRAMES.md), which also covers the build order
— the interface is never further ahead than the engine feeding it.

The **alert test** control in settings sends an alert to your own device. It exists so that
step 5 of the demonstration can be rehearsed without a second operator, and so that a user
can verify alert delivery works on their specific handset — vendor audio policy varies
enough that this is a real concern, not a convenience.

### The locate screen, and what the arrow is allowed to claim

A phone can measure two things about another radio: how strongly it hears it, and, with
a position on each side, which way it lies. The screen keeps the two apart. **How close**
is signal strength -- the bar, the distance figure with its spread, and the siren, which
beeps faster as the operator closes in. **Which way** is the arrow, and the caption above
it always says what the arrow means, because the same shape points at four different
things:

| Caption | What the arrow is | When |
| --- | --- | --- |
| `TO <unit>` | The bearing between the two GPS positions, against the compass | Both positions known and further apart than their combined error |
| `SIGNAL STRONGEST THIS WAY` | The direction the signal peaked in as the operator turned a circle | Indoors, or nearer than GPS can tell apart, after most of a circle |
| `NEAR · LAST KNOWN DIRECTION` | The bearing from when the positions were last far enough apart | Inside the GPS error, for ninety seconds |
| `NORTH · …` | North, so the compass can be seen to be alive | Nothing else is known yet |

The compass behind it is the gyroscope, anchored to the magnetometer only while the
magnetic field here has the strength and dip the geomagnetic model expects (`Heading`,
`HeadingFusion`). A turn of the hand is followed at once and a steel door frame is not.
When the field stops looking like the Earth's the arrow turns amber, the figures line says
`gyro`, and the note asks the operator to move a few metres. A thin ring with a north tick
turns with the compass so an operator with the sun or a map can check it. The faint fan
behind the arrow is its stated doubt: the positions' error over the distance and the
compass's own error together, wide when the units are close and a sliver when far.

## 7. States the interface must show

A system that silently stops working is worse than one that says it has stopped.

| State | Presentation |
| --- | --- |
| `INITIALISING` | Transmit disabled, "loading models" with progress, never a blank screen |
| `READY` | Transmit enabled, link indicator green |
| `DEGRADED` | Amber banner with a **reason string**: mic unavailable, link down, thermal throttling, storage full |
| `UNSECURED` | Permanent red banner whenever the units are paired without encryption. No silent path |
| `TEMPLATE MISMATCH` | Persistent warning naming the peer; template sending disabled |
| Floor held by peer | Busy indicator with the peer's name |
| Low confidence | Recognised text shown with a caution marker before transmission |
