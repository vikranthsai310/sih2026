# Security

## 0. This is not a stated requirement

**ISRO does not ask for any of this.** The words *security, encryption, authentication,
privacy, tamper* and *integrity* appear nowhere in Problem Statement 26173, reproduced
verbatim in [REQUIREMENTS.md §0](REQUIREMENTS.md#0-the-problem-statement-verbatim). The
three stated criteria are Accuracy (40 %), Latency (20 %) and Efficiency (20 %). **None of
them is affected by anything in this document.**

Everything here is self-imposed. It is here anyway, for one reason, in ISRO's own words:

> *"In **alert and distress** based scenarios…"*
> *"alert type messages will be announced at **highest volume non-interruptible**"*
> *"Teams are expected to deliver a **robust, deployable** system architecture."*

We are building a device that wakes a locked, silenced handset and announces at maximum
volume on command. If it accepts unauthenticated input, anyone within radio range can
trigger that in a disaster zone. A system with that capability and no authentication is not
"robust and deployable" — it is a liability with a demo attached. That is the whole of the
justification, and it is enough.

**Three rules follow, and they are binding:**

1. **This work never competes with the scored criteria.** A week spent on AEAD that should
   have gone to word error rate trades a 40 % criterion for a 0 % one.
2. **It is on the cut list**, at position 4 of 7, above languages beyond five and above the
   LoRa hop. See [ROADMAP.md §4](ROADMAP.md#4-critical-path). If the schedule slips, this
   degrades to a fixed pre-shared key compiled into the build, with the UNSECURED banner
   kept honest.
3. **Present it as robustness, in one line.** Do not present it as a requirement, do not
   claim ISRO asked for it, and do not spend demonstration time on it. It belongs in the
   unstated 20 %, not in the 80 % that is written down.

## 1. Why this matters more here than in a typical hackathon project

iTantra exists to raise alarms. A system that can wake a locked, silenced handset and
announce at maximum volume is, if it accepts unauthenticated input, a weapon: the ability
to inject a fraudulent evacuation order into a disaster zone.

There is no destination field and no address filtering to rely on: every frame reaches
every unit in radio range. **The key is the only boundary.** Everything in this document
follows from that sentence.

## 2. Threat model

### Assets

| Asset | Why it matters |
| --- | --- |
| Message integrity | A fraudulent or altered instruction can cause physical harm |
| Message authenticity | The receiver acts on who sent it |
| Message confidentiality | Operational traffic reveals position, casualty state and intent |
| Shared key material | Compromise defeats all three above |
| Availability | A jammed or flooded channel is a denied channel |

### Adversaries

| # | Adversary | Capability | In scope |
| --- | --- | --- | --- |
| A1 | Passive listener | Receives every frame in radio range | Yes |
| A2 | Active injector | Transmits arbitrary frames on the medium | Yes |
| A3 | Replayer | Records and retransmits valid frames | Yes |
| A4 | Device capturer | Physical possession of a provisioned handset | Yes |
| A5 | Jammer | Denies the physical medium | **No** — out of scope; a radio-layer problem with no application-layer answer |
| A6 | Platform attacker | Roots the handset, attacks the OS | **No** — outside the trust boundary |

### Trust boundary

Inside: the application, its models, and Android Keystore. Outside: the radio medium, any
relay hardware, and every other device until it proves it holds the key by producing a
valid authentication tag.

**A relay node is not trusted.** It forwards sealed frames it cannot read.

## 3. Controls

| ID | Risk | Control | Verification |
| --- | --- | --- | --- |
| S-01 | Fraudulent alert injected by an unauthorised transmitter, played at maximum volume | AES-256-GCM with a pre-shared key held by every paired unit. The **entire header is associated data**, so `SRC`, `TYPE` and `FLAGS` are bound into the tag and cannot be altered. Frames failing authentication are discarded silently | Unit test: every single-byte mutation of a valid frame fails verification. Field test: a unpaired device transmits a well-formed `ALERT`; the target must not speak |
| S-02 | Recorded alert replayed later | 64-entry sliding replay window per sender keyed on `(EPOCH, SEQ)`; `EPOCH` increments on every `SEQ` wrap and every service start | Unit test: a captured frame replayed immediately, after 1 000 frames, and after a restart is rejected in all three cases |
| S-03 | Recognition error inverts meaning — "do not evacuate" becomes "now evacuate" | Confidence transmitted with every frame; recognised text shown to the sender **before** transmission; explicit confirmation required for alert-class messages; negation terms weighted in the biasing lexicon | Normalisation and biasing regression suites; a specific negation fixture set per language |
| S-04 | Key material exposed on a captured device | Keys held in Android Keystore, never in DataStore, a file, a log, or a crash report. Re-keying is supported; a lost device is removed by rotating the key at the next provisioning | Code review gate: no key type may be passed to any logging or serialisation API. Static check in CI |
| S-05 | Two operators transmit simultaneously on a half-duplex channel, garbling both | `PTT_CTL` floor announcement, channel-busy indicator, randomised backoff before retry | Two-device manual test with deliberate simultaneous key presses |

## 4. Cryptographic detail

Full construction is normative in [PROTOCOL.md §6](PROTOCOL.md#6-cryptography). The
decisions that matter, and why:

**The whole header is authenticated, not just the payload.** If `SRC` were outside the
authenticated region, any paired unit could impersonate any other. If `TYPE` were outside
it, an attacker could promote a `TEXT` frame to an `ALERT`.

**The nonce is derived, not transmitted.** `EPOCH ‖ SRC ‖ SEQ ‖ padding`. This saves 12
bytes per frame — a quarter of the payload budget on a LoRa link — at the cost of requiring
that `EPOCH` be persisted correctly. Nonce reuse under a fixed key destroys GCM completely,
so `EPOCH` persistence is a **correctness-critical** code path and is tested as such.

**Tag length is transport-dependent**: 16 bytes on Bluetooth and Wi-Fi where bytes are
free, 8 bytes on LoRa where each byte is 27 ms of airtime. A truncated 64-bit tag is only
defensible with bounded forgery attempts, so the receiver rate-limits authentication
failures — more than 16 from one sender in 60 s puts the link in `DEGRADED` and warns the
operator.

**There is no silent unauthenticated path.** `ENCRYPTED` may be cleared only in a bench
configuration, and the UI shows a permanent **UNSECURED** banner whenever it is.

## 5. Provisioning

Users are not asked to enter identifiers, and there is no group to create or join.
Every unit always displays its own code and can always scan another. The first unit to
be powered on generates a 256-bit AES key, a template profile and node 01 for itself,
and is immediately usable. Any unit that scans another receives the key and claims the
next free node identifier. Two handsets on a table: one person points, done.

| Property | Decision |
| --- | --- |
| Key transfer | Optical only. The key never crosses the radio medium, at any point, ever |
| QR contents | Version, key, `KEYID`, profile ID, profile digest, tag length, issuing node ID |
| QR lifetime | A code is valid for 120 s from display, then regenerated. Scanning an expired code fails closed |
| Storage | Key straight into Keystore on scan; the decoded QR string is never written to disk |
| Screenshots | The provisioning screen sets `FLAG_SECURE` |

Optical transfer is not a convenience choice. It is what makes the pre-shared key
meaningfully pre-shared: an attacker in radio range learns nothing, because nothing about
the key was ever radiated.

## 6. Privacy

- **No network permission**, so no telemetry, no crash reporting to a third party, no
  analytics. This is enforced by the manifest rather than by policy.
- Recognised text is retained in the local message log for 24 h and then deleted. The log
  is app-private storage.
- Captured audio is **never** persisted. Ring buffers are in-memory and overwritten
  continuously. The only exception is the bench module in a debug build, which is not
  shipped.
- Position is transmitted only when the operator sends a `POSITION` frame explicitly.
  There is no background location reporting, and no location permission for scanning.

## 7. Reporting a vulnerability

Until the project has a public presence, report to the transport and protocol owner named
in [ROADMAP.md §3](ROADMAP.md#3-allocation) directly, not in a public issue. Include the
frame bytes if the finding is protocol-level; `core-proto` is pure JVM, so any finding can
be reproduced in a unit test without a handset.

## 8. Pre-submission audit checklist

Run before the final build is signed.

- [ ] No key material reaches any log, crash report, `toString`, or DataStore
- [ ] `FLAG_SECURE` set on the provisioning screen
- [ ] The shipped manifest declares no `INTERNET` permission
- [ ] The shipped manifest declares no location permission; BLE scan is `neverForLocation`
- [ ] `ENCRYPTED` is set by default for every new pairing
- [ ] The UNSECURED banner appears whenever the units are paired without encryption
- [ ] Every single-byte mutation of a valid frame fails AEAD verification
- [ ] `EPOCH` survives a force-stop and a reboot, and increments across a `SEQ` wrap
- [ ] The frame decoder fuzz corpus runs clean at 10⁶ inputs
- [ ] No debug or bench build is present in the release APK
