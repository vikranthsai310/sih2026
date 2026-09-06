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

**Audited 2026-09-04 against `app-release-unsigned.apk`, R8 minified. Re-audited
2026-09-06 against a fresh release build, now 27.1 MiB: items 1 and 3 restated (the Wi-Fi
broadcast transport added `INTERNET` and the first logging) and item 10 downgraded to
PARTIAL, because its evidence turned out to be a check that passes trivially under
minification.** Seven of ten pass with evidence recorded below, one is partial, and two
cannot be closed on this machine and say why. An item is not ticked because somebody
believes it; it is ticked because the line beside it can be re-run — and item 10 is the
reminder that a line which can be re-run is worth nothing if it does not test what it
appears to.

| # | Item | Result | Evidence |
| --- | --- | --- | --- |
| 1 | No key material reaches any log, crash report, `toString`, or DataStore | **PASS** | Keys live in the AndroidKeyStore and `KeystoreVault` returns a `SecretKey` handle, never bytes. `DataStoreEpochStore` persists an epoch counter and nothing else. **Evidence restated 2026-09-06:** this line used to read "there is no logging in shipped code at all", and that stopped being true when the Wi-Fi and BLE broadcast transports landed. `grep -rn "android.util.Log\|println(" app/src/main core-*/src/main` now returns four files. Every call in them was read: they log frame sizes, port numbers, RSSI, language codes, node ids and drop reasons. No key, no plaintext message body, no location. The item passes on what it actually says — no *key material* is logged — rather than on the stronger claim that nothing is logged at all |
| 2 | `FLAG_SECURE` set on the provisioning screen | **FIXED** | Was failing. `pairingWindowFlags` existed and **nothing applied it** — the requirement was a comment addressed to an activity that does not exist yet. `PairingScreen` now applies it itself through `SecureWindow`, so any host gets it |
| 3 | No network request is made at runtime | **PASS — restated 2026-09-06** | This item used to read "the shipped manifest declares no `INTERNET` permission", verified by that permission's absence. It is now declared, and the item is restated rather than failed, because the absence was always a stronger claim than constraint C2 makes — and it made a transport ISRO's own description asks for impossible to build. Android requires `INTERNET` to open *any* socket, including one that only ever addresses a broadcast address on the local subnet. The property is now verified three ways, each re-runnable: (a) `grep -rnE "HttpURLConnection\|okhttp\|retrofit\|java\.net\.URL\|WebSocket\|openConnection" app/src/main core-*/src/main` returns nothing, so there is no HTTP client in the application and no dependency that supplies one; (b) `grep -rn "getByName" app/src/main core-*/src/main` returns nothing, so no name is ever resolved — the limited broadcast is built from its four bytes; (c) `WifiBroadcastLinkTest` asserts that every address the Wi-Fi transport sends to is a broadcast address and nothing else. `WifiLink`, the one class that dialled an outbound TCP socket, was dead code and was deleted on 2026-09-06 so that (a) holds without a caveat |
| 4 | No location permission; BLE scan is `neverForLocation` | **PASS** | `aapt2 dump permissions` on the release APK, run 2026-09-06, prints fourteen lines: thirteen `android.permission.*` entries plus `org.itantra.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature-level permission AndroidX declares and uses for its own not-exported receivers — it is not a platform permission and no other app can hold it. Neither `ACCESS_FINE_LOCATION` nor `ACCESS_COARSE_LOCATION` is among them, and `BLUETOOTH_SCAN` carries `usesPermissionFlags='neverForLocation'`. This is the check item 3 used to be paired with, and it is unaffected by the `INTERNET` change: the platform count went from eleven to thirteen when `INTERNET` and `CHANGE_WIFI_MULTICAST_STATE` were added for the Wi-Fi broadcast transport, and no location permission was added at any point |
| 5 | `ENCRYPTED` is set by default for every new pairing | **OPEN** | Cannot be closed: the pairing flow that establishes a session does not exist yet, so there is no default to inspect. `Flags.ENCRYPTED` and the AEAD path are built and tested. Blocked on the same work as item 2's host activity |
| 6 | The UNSECURED banner appears whenever the units are paired without encryption | **PASS (component)** | `UnsecuredBanner` exists and is rendered from the security-state composable. That it appears *in the running application* depends on the same absent pairing flow as item 5 |
| 7 | Every single-byte mutation of a valid frame fails AEAD verification | **PASS** | `SecurityTest`: `any single-byte change to the ciphertext or tag fails verification` and `any single-byte change to the header fails verification`, both exhaustive over position and bit |
| 8 | `EPOCH` survives a force-stop and a reboot, and increments across a `SEQ` wrap | **PASS** | `EpochCounterTest`: `restarting never reuses an epoch`, `a sequence wrap advances the epoch`, `the new epoch is persisted before it is handed out`, `a crash straight after persisting wastes an epoch rather than reusing it`. The store writes synchronously for exactly this reason |
| 9 | The frame decoder fuzz corpus runs clean at 10⁶ inputs | **FIXED** | Was short. W2.23 ran 23 000 inputs inside the framing test, which closed the week-2 gate at 10⁵ and did not meet this one. `FrameFuzzTest` now runs 10⁶ across ten input shapes and asserts four properties: never throws, never allocates past `MAX_PAYLOAD`, always terminates under a deadline, and recovers on the next valid frame after a megabyte of rubbish |
| 10 | No debug or bench build is present in the release APK | **PARTIAL — restated 2026-09-06** | The original evidence was `strings` over `classes.dex` finding no `org/itantra/bench` reference, read as "R8 removed the whole module". That check cannot show what it was read as showing: R8 **renames** classes, so a package path is absent from a minified dex whether the code ships or not, and the check passes trivially. Re-audited by searching for **string constants**, which minification preserves. Result: `AccuracyMatrix`, `ListeningPanel`, `NoiseMixer` and `Scorecard` are genuinely absent, but **`ReportBundle` ships** — its `latency.csv`, `resource.csv` and `scorecard.csv` literals are in `classes.dex`, and they exist nowhere else in the source. Cause, verified: nothing references `ReportBundle`, but it has a `companion object`, and `app/proguard-rules.pro` carries `-keepclassmembers class org.itantra.** { *** Companion; }`, which keeps every `org.itantra` class that has one. `LatencySummary`, `StageSummary`, `UtteranceClock` and `UtteranceTrace` also ship, deliberately: `MessageEngine` times the live path with them, exactly as the original note anticipated. So by this item's own successor criterion the failure is one class, `ReportBundle`, and it is dead code rather than a debug build |

### The two that are open, and why that is the honest state

**Item 5** and the running half of **item 6** both wait on the same thing: a pairing flow.
The cryptography beneath them is built and tested — sealing, the tag-length policy, the
replay window, the epoch counter — but nothing yet establishes a session, so there is no
default to inspect and no state for the banner to react to. Ticking either would be
recording an intention.

### What the audit found

Two defects, both of the same kind: a control that was **written down rather than
enforced**.

`FLAG_SECURE` was documented as a requirement on the hosting activity and no activity
hosted the screen, so the requirement had nothing to attach to. The QR code on that screen
*is* the shared key, and without the flag it lands in the recents thumbnail — a place a key
outlives the pairing that produced it. Moving the flag into the screen removes the
dependency on a future author reading a comment.

The fuzz bar had drifted between two documents: the week-2 gate says 10⁵, this checklist
says 10⁶, and the harness did 23 000. All three numbers were written by people who believed
the harness was adequate. A million inputs takes a few seconds.

