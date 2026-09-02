# Security

## 1. Why this matters more here than in a typical hackathon project

iTantra exists to raise alarms. A system that can wake a locked, silenced handset and
announce at maximum volume is, if it accepts unauthenticated input, a weapon: the ability
to inject a fraudulent evacuation order into a disaster zone.

Address filtering is a **convention, not a control** — any device can set any destination
byte. Everything in this document follows from that sentence.

## 2. Threat model

### Assets

| Asset | Why it matters |
| --- | --- |
| Message integrity | A fraudulent or altered instruction can cause physical harm |
| Message authenticity | The receiver acts on who sent it |
| Message confidentiality | Operational traffic reveals position, casualty state and intent |
| Group key material | Compromise defeats all three above |
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
relay hardware, and every other device until it proves group membership by producing a
valid authentication tag.

**A relay node is not trusted.** It forwards sealed frames it cannot read.

## 3. Controls

| ID | Risk | Control | Verification |
| --- | --- | --- | --- |
| S-01 | Fraudulent alert injected by an unauthorised transmitter, played at maximum volume | AES-256-GCM with a per-group pre-shared key. The **entire header is associated data**, so `SRC`, `DST`, `TYPE` and `FLAGS` are bound into the tag and cannot be altered. Frames failing authentication are discarded silently | Unit test: every single-byte mutation of a valid frame fails verification. Field test: a non-group device transmits a well-formed `ALERT`; the target must not speak |
| S-02 | Recorded alert replayed later | 64-entry sliding replay window per sender keyed on `(EPOCH, SEQ)`; `EPOCH` increments on every `SEQ` wrap and every service start | Unit test: a captured frame replayed immediately, after 1 000 frames, and after a restart is rejected in all three cases |
| S-03 | Recognition error inverts meaning — "do not evacuate" becomes "now evacuate" | Confidence transmitted with every frame; recognised text shown to the sender **before** transmission; explicit confirmation required for alert-class messages; negation terms weighted in the biasing lexicon | Normalisation and biasing regression suites; a specific negation fixture set per language |
| S-04 | Key material exposed on a captured device | Keys held in Android Keystore, never in DataStore, a file, a log, or a crash report. Group re-keying supported; a lost device is removed by rotating the key at the next provisioning | Code review gate: no key type may be passed to any logging or serialisation API. Static check in CI |
| S-05 | Two operators transmit simultaneously on a half-duplex channel, garbling both | `PTT_CTL` floor announcement, channel-busy indicator, randomised backoff before retry | Two-device manual test with deliberate simultaneous key presses |

## 4. Cryptographic detail

Full construction is normative in [PROTOCOL.md §6](PROTOCOL.md#6-cryptography). The
decisions that matter, and why:

**The whole header is authenticated, not just the payload.** If `SRC` were outside the
authenticated region, any group member could impersonate any other. If `TYPE` were outside
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

Users are not asked to enter identifiers. One device creates the group and generates a
random channel identifier, a 256-bit AES key, a template profile, and node assignments; it
displays a QR code. Joining devices scan it, receive the key, and claim the next free node
identifier.

| Property | Decision |
| --- | --- |
| Key transfer | Optical only. The key never crosses the radio medium, at any point, ever |
| QR contents | Version, group ID, key, profile ID, profile digest, tag length, creator node ID |
| QR lifetime | The creator stops displaying it after 120 s or after the expected number of joins, whichever is first |
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
- [ ] `ENCRYPTED` is set by default for every newly created group
- [ ] The UNSECURED banner appears whenever any configured group is unauthenticated
- [ ] Every single-byte mutation of a valid frame fails AEAD verification
- [ ] `EPOCH` survives a force-stop and a reboot, and increments across a `SEQ` wrap
- [ ] The frame decoder fuzz corpus runs clean at 10⁶ inputs
- [ ] No debug or bench build is present in the release APK
