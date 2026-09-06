# Task breakdown

Every task needed to turn 37 000 words of specification into a working, measured system.

**This is not more specification.** It is the list that converts what exists into what runs.
Each task names the document that already answers *how*; none of them require a new design
decision.

## How to use this

- **ID** — `W3.7` is week 3, task 7. Stable; cite in commit messages and branch names.
- **Owner** — the role from [ROADMAP.md §3](ROADMAP.md#3-allocation), not a person's name.
- **Spec** — where the answer already is. If a task sends you to a document and the
  document does not answer it, that is a defect in the document; fix it in the same commit.
- **Done when** — the observable condition. Not "implemented" — a thing you can see.
- **Blocks** — tasks that cannot start until this one lands.

Tick a box only when *Done when* is true, not when the code compiles.

### Status legend

`[ ]` not started · `[~]` in progress · `[x]` done · `[!]` blocked · `[-]` cut

**Progress: 126 of 205 complete, 35 in progress, nothing blocked.** `./gradlew build` is
green end to end — compilation, ktlint, Android Lint and the coverage gate — and **771
tests pass** across all eight modules: CRC, frame codec, script packer, templates, replay
window, AEAD, clock sync, pre-trigger ring, energy gate, endpointer, sliding-window
decoding, normalisation, clause splitting, the latency log, the manifest, atomic pack
installation, resumable downloads, the language switch, the WER scorer, the noise mixer,
the scorecard, contextual biasing, floor control, alert delivery, relaying,
fragmentation, the epoch counter, pairing codes, the transmit key, duplex and
barge-in policy, adaptive endpointing, stabilised partials, tag-length policy,
the transport contract, the ten-language rule loader and its
fixtures, the deployment gazetteer and template profile, the critical-term scorer, the
accuracy matrix and suppression policy, the listening-panel arithmetic, the recognition
tap, the relay and memory soaks, the spoken forms behind TalkBack, and — new in week 8 —
the compression ratios, the latency stage decomposition, the report-bundle conditions and
a million-input decoder fuzz. `core-proto` holds
94.2 % line coverage.

**Week 7 is complete except for measurement.** Ten languages are enabled with vocabulary,
rules and fixtures; the two soaks that can run in memory are green; every harness that
turns audio into a number exists and is tested against a stub. What is absent is the
acoustic model, the evaluation corpus, a handset and a listening panel — so W7.7 through
W7.11, W7.14 and W7.16 carry no figures, and the code says so rather than defaulting to
one.

**The parts are now joined.** The largest gap in the project was not a missing component
but a missing seam: `EpochCounter.start()` had no caller, `TransportClass.tagBytesFor` had
no caller, and no code path anywhere turned a sentence into bytes or bytes back into a
sentence. `Session` is that path, and building it is what forced the epoch to advance, the
tag length to be chosen and the replay window to be consulted — three security properties
the documents claimed and the code did not have.

**Week 8 is complete except for measurement and rehearsal.** The four compression figures
are computed from the frame codec rather than transcribed, and a CI gate greps `docs/` for
any that disagree — it found two wrong numbers in the answers prepared for a technical
panel. The security audit closed eight of ten items with re-runnable evidence and found two
controls that were written down rather than enforced. What remains needs two handsets, a
model, and a room.

**The sherpa-onnx AAR is fetched and the whole native stack now builds.** The debug APK is
43.2 MB and the **release APK 26.3 MiB** — arm64-only, minified, resources shrunk, and with
no models in it. `aapt2` confirms it carries no location permission. It now carries
`INTERNET`, added 2026-09-06 because Android requires it to open the Wi-Fi broadcast
transport's UDP socket; constraint C2 is verified by inspection instead — see W1.15. It was
30.9 MB and **0.9 MB over the N2 installer target** until W8.7 found
that the sherpa-onnx AAR ships two native libraries this application never loads;
`libonnxruntime.so` is still 21.7 MB of it, so risk T-04 stays worth watching even though
the target is now met with room to spare.

> **Everything still open in weeks 1–6 needs the two handsets.** Not a decision, not a
> dependency — a device. The instrumented tests are written and compiling, the transport
> contract is stated, and the alert path is unit-tested end to end; what none of that can
> do is prove an alert is audible on a locked, silenced phone, because the thing under
> test there is the vendor’s audio policy rather than our code.

> **What is left in week 1 needs hardware.** The audio capture wrapper, the recogniser
> binding, the Silero and Vosk models and gate W1.G all require the target handset, and
> the sherpa-onnx distribution channel has to be resolved first (W1.23). Everything in
> week 1 that could be written and proven without a device now has been. — the build scaffold. Everything else is unstarted.

---

> ## Revised 2026-09-03 after model verification
>
> Three findings changed tasks below. **Read these before picking anything up.**
>
> 1. **No streaming acoustic model exists for the ten Indian languages.** The production
>    recogniser is `OfflineRecognizer`, not `OnlineRecognizer`, and it cannot endpoint —
>    our VAD tiers do that. Affects W1.27, W1.29, W6.2. New task W3.13 adds sliding-window
>    decoding, without which end-to-end delay is ~1330 ms. Risk T-16.
> 2. **The acoustic model is one shared ~120 MB file covering all ten languages**, not ten
>    35 MB packs. Affects W4.4, W4.5, W4.10, W7.1. Risk T-17.
> 3. **Piper publishes Odia voices**, so the expected gap probably does not exist. W4.12
>    becomes a confirmation task and moves to week 1.

## Phase 0 — Before a line of code

**Three days. Nothing below week 1 can be honestly measured until P0.2 completes.**

- [ ] **P0.1** — Fill the six owner names in [ROADMAP.md §3](ROADMAP.md#3-allocation)
  · *Every risk in [RISKS.md](RISKS.md) needs exactly one owner*
  · **Done when** no blank remains in the allocation table
  · **Blocks** every gate review
- [ ] **P0.2** — **Buy the target handset.** 4 GB RAM, entry-tier Snapdragon 4-series or
  Helio G-series, Android 11+
  · *[SETUP.md §2](SETUP.md#2-target-hardware), risk P-02*
  · **Done when** the model, SoC, RAM and Android version are written into `SETUP.md` §2
  · **Blocks** every performance figure in the project — W1.G, W3.11, W4.10, W6.12, W8.2
- [ ] **P0.3** — Second handset for the two-device loop (any Android 8+)
  · **Blocks** W2.G, W3.G
- [ ] **P0.4** — Third handset, pre-configured spare, kept in the bag
  · *Risk P-03* · **Blocks** W8.11
- [~] **P0.6** — Toolchain on every machine
  · **JDK is solved and needs no action:** `settings.gradle.kts` applies the foojay
    resolver, so Gradle provisions JDK 17 itself whatever is on the PATH. This removes the
    "six machines, three JDKs" problem the original task was worried about
  · **Remaining:** each developer installs Android Studio (Ladybug or later) and clones.
    The wrapper pins Gradle 8.11.1, AGP 8.7.3 and Kotlin 2.0.21 for everyone
  · **Done when** every machine can run `./gradlew :core-proto:test`
- [x] **P0.7** — `tools/requirements.txt` committed (onnx, onnxruntime, jiwer for WER)
- [ ] **P0.8** — USB debugging on both handsets; `adb devices` lists them
- [ ] **P0.9** — Turn this file into GitHub issues, one per task, labelled by week and owner
  · **Done when** the project board shows Phase 0 and week 1 populated
- [ ] **P0.10** — Recruit the MOS listening panel: 15 native speakers × 10 languages
  · *Risk P-05. Starts now, not week 7 — this needs people, and people need notice*
  · **Blocks** W7.8

---

## Week 1 — Skeleton, capture, recognise

**Gate W1: Hindi speech produces correct text on screen, on the target handset, offline.**

### Build system

> **The build works.** `./gradlew :core-proto:test` passes on a clean checkout, and the
> dependency guards have been observed firing. `assembleDebug` is still unrun — it needs the
> Android SDK, so it is the one part of W1.0 left open.

- [~] **W1.0** — **Make the scaffold build**
  · **Done:** Gradle wrapper 8.11.1 generated and committed; JDK 17 is **provisioned by
    Gradle itself** via the foojay resolver rather than required on the PATH, which also
    settles the "six machines, three JDKs" problem. `./gradlew :core-proto:test` passes
  · **Remaining:** run `./gradlew assembleDebug` on a machine with the Android SDK

- [x] **W1.1** — `settings.gradle.kts` with all eight modules: `:app` `:core-audio`
  `:core-asr` `:core-tts` `:core-link` `:core-proto` `:core-models` `:bench`
  · *[ARCHITECTURE.md §2](ARCHITECTURE.md#2-module-map)*
- [x] **W1.2** — `gradle/libs.versions.toml` version catalog. Every version pinned, no
  dynamic ranges
- [x] **W1.3** — Gradle wrapper **8.11.1** committed. Nobody runs a local `gradle`
  · *8.11.1 rather than 8.10.x — it was already in the local cache and AGP 8.7 accepts it*
- [x] **W1.4** — **`core-proto` is `kotlin("jvm")` only — no Android plugin, ever**
  · **Verified.** Adding `androidx.core:core-ktx` to `core-proto` fails configuration with
    the message from `ARCHITECTURE.md §2`. Gradle's own JVM/Android variant matching would
    refuse it regardless — that is the stronger guarantee; our check only makes the error
    legible. `:core-proto:test` runs with no emulator and no SDK
- [x] **W1.5** — Dependency-rule enforcement: a build failure, not a review comment
  · **Verified** by the same mechanism as W1.4
- [x] **W1.6** — ktlint applied to every subproject; `allWarningsAsErrors` in `core-proto`
- [x] **W1.7** — `.github/workflows/ci.yml`: five jobs — proto, build, manifest guard,
  licence audit, secret scan
- [x] **W1.8** — Coverage gate: Kover `minBound(90)` on `core-proto`, run in CI
- [x] **W1.9** — **Licence audit CI gate** — `tools/check_licences.py`; a catalog entry
  absent from [LICENSES.md](../LICENSES.md) fails the build
  · *Risk P-04. **It caught nine undeclared framework dependencies on first run**, now
  recorded in `LICENSES.md` §4a*
- [x] **W1.10** — Secret scan in CI: no keystore, key material or credential in the tree

### Application shell

- [x] **W1.11** — `app` module, `MainActivity`, Compose scaffold
  · *`MainActivity` now hosts `OperatingScreen` and does an activity's job: the window,
    the permissions, the hardware key. The screen itself knows nothing about Android*
- [x] **W1.12** — Monochrome high-contrast theme tokens
  · *`Tokens`. Four files had already grown their own `Color(0xFF101010)`, which is how
    a high-contrast palette becomes five slightly different greys*
  · *The contrast ratios are written down beside the values — 19.6:1 for ink on paper,
    6.4:1 for muted — because "high contrast" is a claim that drifts one hex digit at a
    time*
  · *[WIREFRAMES.md §1](WIREFRAMES.md#1-layout-system) — 8 dp grid, 64 dp minimum target*
- [x] **W1.13** — `EngineService` foreground service with persistent notification
  · *[ARCHITECTURE.md §3](ARCHITECTURE.md#3-threading-and-lifecycle)*
  · *Owns the `EngineState` machine and rejects an illegal transition rather than
    applying it. `FOREGROUND_SERVICE_TYPE_MICROPHONE`, `START_STICKY`, and a
    deliberately **silent low-importance** channel — this runs for hours, and a radio
    that pings on every state change is switched off within the first hour*
  · *The manifest had declared this class since week 1 without it existing, which
    Android Lint caught as `MissingClass`*
- [x] **W1.14** — Runtime permissions: `RECORD_AUDIO`, `BLUETOOTH_CONNECT`,
  `BLUETOOTH_SCAN` with `neverForLocation`
  · *Requested on first launch, **checked before every Bluetooth call**, and a denial is
    shown on screen with the buttons asking again rather than failing silently*
  · *Android Lint caught the original code calling `bondedDevices` with no check at all,
    which throws `SecurityException` on Android 12 and above — the likeliest reason a
    first run on real handsets would have refused to connect with no explanation*
- [x] **W1.15** — **No location permission; offline verified by inspection** — checked in
  the built APK with `aapt2 dump permissions`, not merely in the manifest source
  · *Implemented as a CI job; the manifest carries no location permission and
    `BLUETOOTH_SCAN` carries `neverForLocation`*
  · *Constraint C2. **Restated 2026-09-06.** This task used to read "No `INTERNET`, no
    location permission" and called the permission's absence the strongest form of the
    offline claim. It was a stronger claim than C2 makes, and W6.x's Wi-Fi broadcast
    transport made it impossible to keep: Android requires `INTERNET` to open any socket,
    including one that only ever addresses a broadcast address. The CI job now asserts no
    location permission, and asserts the offline property directly — no HTTP client and no
    `getByName` anywhere in `src/main`, with `WifiBroadcastLinkTest` covering
    broadcast-only addressing*
  · **Done when** CI fails if a location permission appears, if any HTTP client or
    `getByName` appears in `src/main`, or if `WifiBroadcastLinkTest` fails
- [x] **W1.16** — State machine: `INITIALISING → READY → LISTENING → …`, plus `DEGRADED`
  with a reason string
  · *[ARCHITECTURE.md §4](ARCHITECTURE.md#4-state-model)*

### Audio capture

  · *`EngineState` + `EngineTransitions`. Nine tests: transmit disabled while
    initialising (T-11) and while degraded, every reason carries an operator message,
    and degraded recovers only through Ready*
- [~] **W1.17** — `AudioRecord` wrapper, 16 kHz mono, 20 ms hops (320 samples)
- [x] **W1.18** — Pre-allocated ring buffer retaining 250 ms pre-trigger (8 000 samples, 16 kB)
  · *[ASR.md §2](ASR.md#2-endpointing) leading pad*
  · *`PreTriggerRing`. 250 ms at 16 kHz is **4 000 samples / 8 kB** — the spec said
    8 000 / 16 kB, which is 500 ms. `ASR.md` corrected*
- [~] **W1.19** — Capture thread at `THREAD_PRIORITY_URGENT_AUDIO`
  · **Done when** an allocation-tracking test shows **zero allocations** in the capture loop
  · *No logging, no string formatting. A correctness constraint, not style*
- [x] **W1.20** — Tier 0 energy gate: EMA noise floor τ = 3 s updated only on non-speech
  frames, +9 dB trigger, 3-frame open / 10-frame close hysteresis
  · *[ASR.md §1](ASR.md#1-three-tier-voice-activity-detection)*
  · *`EnergyGate`. Six tests: opens after exactly 3 frames, closes after exactly 10,
    ignores steady background, and **the floor does not move while a talker speaks** —
    the failure the class exists to prevent*
- [x] **W1.21** — Bounded queue capture → inference, depth 100 (2 s), drop-oldest with a
  counter, never blocks capture
  · *[ARCHITECTURE.md §3](ARCHITECTURE.md#3-threading-and-lifecycle) queue policy*

### Recognition

  · *`BoundedFrameQueue`. Six tests including a two-thread race; a full queue drops
    the oldest and counts it rather than blocking capture*
- [ ] **W1.22** — Bundle `silero_vad.onnx` (1.8 MB) in base assets
- [~] **W1.23** — sherpa-onnx AAR dependency; verify it loads on the target handset
  · ***The AAR is now fetched, checksum-verified and building.*** *`core-asr` and
    `core-tts` take it `compileOnly` — AGP refuses to bundle a local `.aar` into a
    library AAR because the result silently omits its classes and native libraries —
    and the app module carries it, so it is packaged exactly once*
  · *Still `[~]` for the only reason that matters: **nothing has been loaded on a
    handset**, which is the other half of this task*
  · *Open question **Q3 is answered**. sherpa-onnx is **not on Maven Central under any
    coordinates** — verified 2026-09-04 against the Central search API, which returns no
    k2-fsa artifact at all. The only sherpa artifact there is
    `com.bihe0832.android:lib-sherpa-onnx`, an unrelated third-party wrapper*
  · *The official build ships as a **GitHub release asset**: `sherpa-onnx-1.13.7.aar`,
    49 113 869 bytes. `tools/fetch_sherpa.sh` downloads it into `libs/` and **verifies
    its SHA-256 before installing** — the AAR carries native code that runs inside the
    app, so accepting whatever the network returned would be a supply-chain hole*
  · *Still `[~]`: nothing has been loaded on a handset yet, which is the other half of
    this task*
- [ ] **W1.24** — Tier 1 Silero VAD: 512-sample window, threshold 0.5, min speech 250 ms,
  min silence 100 ms. Runs only when tier 0 has opened
- [x] **W1.25** — `tools/fetch_models.py` — download, SHA-256 verify, atomic install
  · *Reads `models/manifest.json`, so there is one statement of what a pack contains.
    Resumable, because a 120 MB download over a relief-camp connection does not complete
    first try, and a server that ignores a Range request is detected rather than trusted*
  · *A placeholder hash is refused out loud. A run that printed "installed" for a pack it
    could not verify would be the worst outcome this script has*
- [ ] **W1.26** — Vosk Hindi small model fetched and loading
  · *Week-one prototype. **Schedule insurance, not a compromise** — risk T-07*
- [~] **W1.27** — **`OfflineRecognizer` binding** (NeMo-CTC), 4 threads while decoding and
  2 at idle, greedy search
  · *[ASR.md §8](ASR.md#8-reference-binding). **Not `OnlineRecognizer`** — no streaming
  Indic model exists, risk T-16. Vosk in W1.26 is streaming and may still be driven that
  way for the week-1 prototype only*
- [x] **W1.28** — Endpointing state machine `IDLE / LISTENING / FINALISING`
  · *[ASR.md §2](ASR.md#2-endpointing) — 400 ms phone, 150 ms PTT, 8 s max, 300 ms min*
  · *`Endpointer`. Fourteen tests covering both modes, the 8 s cut, the 300 ms
    discard, and that a 200 ms inter-clause pause does **not** finalise*
- [x] **W1.29** — Emit `Hypothesis(text, isFinal, confidence, tEndpoint)`
  · *An offline model yields no partials, so `isFinal` is always true until W3.13 lands
  sliding-window decoding. Do not design the UI around live partial text*
  · *`Hypothesis` + `Confidence`, carrying tMic/tEndpoint/tFinal so latency.csv needs
    no separate tracing*
- [ ] **W1.30** — Models loaded at service start and held resident; transmit disabled until
  `READY` with a visible indicator
  · *Risk T-11 — the cold-start cost must never be paid on a key press*

### Interface

- [x] **W1.31** — Operating screen shell, bands A–F
  · *Same order, same heights, every state. Band C is a `weight` rather than a height so
    rule 1's third-of-the-screen holds on a taller handset, and every band is a minimum
    so nothing truncates at 200 % text*
  · *[WIREFRAMES.md §4](WIREFRAMES.md#4-operating--push-to-talk-idle)*
- [x] **W1.32** — Recognised text in band E
  · *Rule 6, plus the partial hypothesis above band D while transmitting — the sender's
    last chance to notice a misrecognition before it goes*
- [x] **W1.33** — Stage timestamps `tMic`, `tVad`, `tFirstPartial`, `tEndpoint`, `tFinal`
  · *`UtteranceClock`, on the live path because the marks are taken at points scattered
    across the audio thread, the decoder and the link, and there is no later moment*
  · *Monotonic, not wall clock: a clock corrected backwards mid-utterance gives a negative
    latency, which in a hundred-row median silently improves the result*
  · *[ASR.md §9](ASR.md#9-instrumentation)*
- [x] **W1.34** — Latency strip band F showing real numbers
  · *An absent figure is an em dash, never a zero. A zero-millisecond stage and a stage
    nobody measured are different facts, and this is the strip a jury photographs*
  · **Permanent, not a debug view** — 20 % of the mark is latency

- [ ] **W1.G** — **GATE:** 10 Hindi utterances → correct text, on the target handset, in
  aeroplane mode. Recorded on video

---

## Week 2 — Transport and wire protocol

**`core-proto` needs no models, no handset and no Android. It can start on day 1 in
parallel with week 1.**

**Gate W2: typed text crosses two phones over RFCOMM; the decoder survives 10⁵ fuzz inputs.**

### Frame codec

- [x] **W2.1** — `Frame` type, message types, flag bits, language indices
  · *[PROTOCOL.md §2, §3, §7](PROTOCOL.md#2-message-types)*
- [x] **W2.2** — CRC-16/CCITT-FALSE — `core-proto/…/Crc16.kt`
  · **Done.** Six tests pass, including the normative `crc16("123456789") == 0x29B1` from
    the conformance checklist, plus an exhaustive single-bit-flip detection test over a
    45-byte frame (360 mutations, all detected)
- [x] **W2.3** — `encode()` / `decode()`, **10-byte header**, big-endian throughout
  · *[PROTOCOL.md §1](PROTOCOL.md#1-frame-layout)*
  · *Decode returns a typed rejection reason rather than a bare null, so every
    rule in W2.5 is tested in isolation*
- [x] **W2.4** — Property test: `decode(encode(f)) == f` over 10 000 generated frames
  · **10 000 random frames**, all round-tripping
- [x] **W2.5** — Reject: bad magic, reserved `TYPE`, `LANG` > 9, `FINAL`+`PARTIAL`,
  `PACKED`+`TEMPLATE`, `LEN` > 1024

### Script packing

  · *Also rejects a bad protocol version, and truncation at **every** cut point*
- [x] **W2.6** — Block-base table for all ten languages
  · *[PROTOCOL.md §3](PROTOCOL.md#3-language-indices)*
  · *`Language.kt`*
- [x] **W2.7** — Single-byte alphabet: `0x00–0x7F` ASCII, `0x80–0xFF` block offset
- [x] **W2.8** — **Escape `0x1B` + 24-bit codepoint** for anything outside both ranges
  · *Risk T-13. Without it the packer is silently lossy on digits, punctuation and ZWJ/ZWNJ*
  · **Risk T-13 closed.** ZWNJ costs four bytes and survives; the escape byte
    itself round trips
- [x] **W2.9** — Unicode NFC normalisation before packing
- [x] **W2.10** — Round-trip property test `unpack(pack(s, lang)) == s` over a corpus
  containing **every codepoint in each block**, ASCII, ZWJ/ZWNJ and surrogate pairs
  · *Passing for all ten languages, plus 500 random strings per language*
- [x] **W2.11** — Assert packed size ≤ UTF-8 size over benchmark transcripts; clear
  `PACKED` when packing does not reduce
  · *Verified on a distress sentence in each of the nine Indic languages;
    `isWorthPacking()` keeps `PACKED` clear for ASCII*
- [x] **W2.12** — `templates.json` loader, canonical serialisation, `profileDigest`
  · *`TemplateTable`. Canonical serialisation is order-independent; digest is
    SHA-256 truncated to 4 bytes*
- [x] **W2.13** — Fuzzy match: normalised-token Levenshtein ≥ 0.85 **and** high recogniser
  confidence. Both required
  · *[PROTOCOL.md §5.3](PROTOCOL.md#53-matching-rule)*

### Cryptography

  · *Both conditions enforced. **Finding:** at 0.85, one wrong word only survives in
    a sentence of 7+ tokens, so short sentences need near-exact recognition. Documented
    in PROTOCOL.md §5.3 — the conservative side to err on*
- [x] **W2.14** — AES-256-GCM seal / open, **AAD = the entire 10-byte header**
  · *`Aead`. AAD is the whole 10-byte header, so SRC and TYPE cannot be forged*
- [x] **W2.15** — Deterministic nonce `EPOCH ‖ SRC ‖ SEQ ‖ 0x00×5`
  · *[PROTOCOL.md §6.2](PROTOCOL.md#62-deterministic-nonce)*
  · *`Aead.nonce`, 12 bytes, never transmitted — saves 12 B on every frame*
- [x] **W2.16** — `EPOCH` persistence: increments on every `SEQ` wrap **and every service
  start**
  · **Correctness-critical. Nonce reuse destroys GCM completely — risk S-07**
  · *`EpochCounter` existed and had **no caller**. `Session` now advances it at start and
    **before** a `SEQ` wrap rather than after — after is too late, because the wrapping
    frame would go out under the old epoch with a sequence number already used*
- [x] **W2.17** — Nonce-uniqueness test over 10⁷ simulated frames including restarts and wraps
  · *Proved by **strict monotonicity** over 10 M nonces across 150+ wraps, which is
    stronger than "no duplicate seen" and costs constant memory*
- [x] **W2.18** — Tag length by transport class: 16 B on BT/Wi-Fi, 8 B on serial
  · *`TransportClass.tagBytesFor` also had no caller. `Session` selects from the transport,
    so the tag is a property of the link rather than of a call site*
  · ***SunJCE rejects a 64-bit GCM tag*** *— `Unsupported TLen value. Must be one of
    {128, 120, 112, 104, 96}`. The choice was to move the protocol to 12 bytes or to
    truncate ourselves; the protocol wins, because 8 bytes is 27 seconds of airtime on a
    300 bps link and that saving is the whole argument for truncation existing*
  · *So `Aead` truncates per NIST SP 800-38D appendix C and verifies by recomputing:
    CTR-decrypt from GCM's payload counter, re-seal, compare in constant time.
    `TruncatedTagTest` holds it to every single-byte mutation of ciphertext, tag and
    header, and to the two confusions that would matter — a full-tag frame must not open
    as truncated, and a truncated one must not open as full*
- [x] **W2.19** — Auth-failure rate limit: > 16 from one `SRC` in 60 s → `DEGRADED`
  · *Required to make an 8-byte tag defensible*
  · *`AuthFailureLimiter`, 16 failures per sender per 60 s*
- [x] **W2.20** — Replay window: 64-entry sliding, per `SRC`, keyed `(EPOCH, SEQ)`
  · *`ReplayWindow`, a 64-bit sliding mask keyed on (EPOCH, SEQ)*
- [x] **W2.21** — Mutation test: **every single-byte change to a valid frame fails
  verification**

### Robustness

  · *Every single-byte mutation of the sealed payload **and** of the header fails
    verification — 384 + 10 mutations, all rejected*
- [x] **W2.22** — `readFully` loop and `0xA1` resynchronisation
  · *[PROTOCOL.md §13](PROTOCOL.md#13-stream-framing). Risk T-08 — the most common defect
  in this class of project*
  · *`StreamFramer`. Reassembles across every cut point, skips corrupt frames,
    resynchronises on 0xA1, and stays bounded on a stream with no sentinel*
- [x] **W2.23** — Fuzz harness: truncated at every cut point, byte-interleaved frames,
  1/2/8 bit flips, `LEN` of 0/1/1023/1024/1025/65535, megabytes with no sentinel
  · **Done when** 10⁵ inputs produce no uncaught exception, no unbounded allocation, no
  socket closure, and correct recovery on the next valid frame
  · *Fuzz: 20 000 random inputs, 3 000 multi-bit-flip streams, byte-interleaved
    frames, implausible LEN — never throws, never grows*
- [x] **W2.24** — All 12 items of the conformance checklist as named tests
  · *[PROTOCOL.md §14](PROTOCOL.md#14-conformance-checklist)*

### Link layer

  · *All twelve checklist items now have named tests in `core-proto`*
- [x] **W2.25** — `Link` interface, `LinkState`, `LinkMetrics`
  · *[TRANSPORT.md §1](TRANSPORT.md#1-the-abstraction)*
  · *`Link`, `LinkState`, `LinkMetrics` in `core-link`*
- [x] **W2.26** — `LoopbackLink` in-process, for integration tests with no radio
  · *`LoopbackLink`. Delivers **seven bytes at a time** by default, so a consumer
    that assumes one write equals one read fails here rather than in the field (T-08)*
- [~] **W2.27** — `RfcommLink` — **`cancelDiscovery()` before `connect()`**, or throughput
  collapses by an order of magnitude
  · *`RfcommLink` written and compiling. `cancelDiscovery()` before `connect()`, and
    every read fed through `StreamFramer` rather than assumed whole (T-08).
    **Unverified — needs two paired phones.***
- [x] **W2.28** — Reconnection: exponential backoff 1 s → 30 s with jitter, reset on success
  · *`Backoff`. 1 s to 30 s with jitter; a test asserts the exact schedule and that
    jitter actually spreads retries*
- [x] **W2.29** — `HEARTBEAT` every 2 s; three misses mark the peer offline
  · *`Heartbeat` payload codec, 12 bytes. Carries the epoch (S-07) and the template
    digest (S-06) — the two values other safety properties depend on*
- [~] **W2.30** — Outbox in Room: store-and-forward, capped 500 frames / 24 h, flush in
  order on reconnect
  · *`Outbox` holds the policy — 500 frames, 24 hours, in-order flush — and drops the
    **oldest** when full, the opposite of `Reassembler` and for the opposite reason: that
    queue is an attacker's to fill, this one is entirely our own and the newest message is
    the one most likely to still be true*
  · *The day's cap exists so a returning link does not deliver a flood about a morning at
    nightfall*
  · **Blocked:** *the Room binding. Durability sits behind an `Outbox.Store` interface, the
    same split as `EpochCounter`; Room needs KSP wiring the app module does not have*
- [x] **W2.31** — Temporary debug text field to send typed text — **delete in W3.12**
  · *Bring-up screen with a text field, Listen/Connect buttons and a byte counter.
    Deleted by W3.12 once speech replaces typing*
- [x] **W2.32** — Byte counter in band F

  · *Byte counter shows the frame size and the ratio against 96 000 B of audio*
- [ ] **W2.G** — **GATE:** typed text on A appears on B over RFCOMM; CI green; fuzz clean

---

## Week 3 — Close the loop · THE HINGE

> **This is the week that decides the project.** A working loop with mediocre models can be
> improved under any time pressure. Excellent models with no loop cannot be demonstrated.
> If any week-4 task threatens this gate, the week-4 task is cut. Risk P-01.

**Gate W3: speech spoken on device A is heard on device B.**

- [ ] **W3.1** — espeak-ng data (~10 MB) in base assets; Piper Hindi voice fetched
- [~] **W3.2** — `OfflineTts` binding, 2 threads
  · *`SherpaSynthesiser`. Compiles against the real AAR; unrun until a voice and a
    handset exist*
  · *[TTS.md §6](TTS.md#6-reference-binding)*
- [x] **W3.3** — Normalisation engine + `normalise.json` loader
  · *[TTS.md §1](TTS.md#1-text-normalisation)*
  · *`TextNormaliser` — an ordered rule engine; adding a language needs a rule set,
    not a code change*
- [x] **W3.4** — Hindi rules: `time-24h`, `unit-km`, `callsign`, `long-num`, `cardinal`
  · *The quantity-versus-identifier rule is where the perceived quality lives*
  · *`HindiRules` + `HindiNumerals`. **All 100 values below a hundred are irregular
    in Hindi**, so the table is exhaustive by necessity. Indian scale throughout —
    लाख and करोड़, not hundred-thousand*
- [x] **W3.5** — Hindi normalisation fixtures, ≥ 60 cases, asserted in CI at **100 %**
  · *32 tests. Every value 0–1000 asserted to leave **no surviving digit**, plus ten
    operational sentences with units and call-sign markers*
- [x] **W3.6** — Clause splitter: punctuation → conjunctions → hard split; min 8 phonemes,
  max 12 words
  · *`ClauseSplitter`. Splits on the Devanagari danda as well as Western marks, and
    merges a fragment too short to synthesise alone — an audible gap mid-sentence is
    worse than a little extra initial latency*
- [~] **W3.7** — Streaming playout: `AudioTrack` `WRITE_BLOCKING`, playback starts on chunk 1
  · *Built on `generateWithCallback`, which emits audio **as it is produced** — the
    mechanism that makes starting on chunk 1 possible at all rather than a matter of
    buffering. `MODE_STREAM`, buffer 4× the minimum, and `stop()` rather than
    `flush()` on the way out so the last word is not clipped*
  · **Done when** time-to-first-audio ≤ 250 ms, measured
  · *Underrun policy: synthesise the remainder as one block. **Never a gap mid-sentence***
- [~] **W3.8** — Synthesis thread at `THREAD_PRIORITY_AUDIO`
  · *`speakOnAudioThread`. Descheduled synthesis is an audible gap, not merely slow*
- [x] **W3.9** — Wire the receive path end to end: `Link → decode → CRC → AEAD → replay
  check → unpack/template → normalise → phonemise → TTS → AudioTrack`
- [x] **W3.10** — Clock sync: four `HEARTBEAT` round trips, median offset, so end-to-end
  latency is measured rather than stopwatched
  · *[EVALUATION.md §4](EVALUATION.md#4-latency--20--of-the-mark)*
  · *`ClockSync` in `core-proto`. Tested against a simulated pair of handsets with a
    known offset, so the instrument itself is verified: a 47 s clock difference that
    would report 47 900 ms recovers the true 900 ms*
  · *Median, not mean — a test holds a single 200 ms radio stall to ≤ 1 ms of error*
  · *Refuses to return an offset before four round trips complete. Silently returning
    zero would make every latency figure wrong in a way nobody would notice*
- [x] **W3.11** — `latency.csv` writer, every stage boundary, every utterance
  · *`LatencyLog` + `UtteranceTrace` + `LatencySummary` in `bench`, wired into `app`
    so every utterance is logged on the live path — the only way to reach the 100
    utterances the reporting rules demand*
  · *A stage that did not happen is written **empty, never zero**: a zero would be
    averaged into the results as if it were a measurement*
  · *A row whose arity does not match the header is refused rather than written —
    the classic way a results file becomes quietly wrong*
  · *Receiver-side stages have the clock offset removed before any subtraction*
- [x] **W3.12** — Delete the debug text field from W2.31
  · *Deleted, not hidden behind a flag. An application whose fastest route to sending a
    message is to type it is the one thing this project exists not to be, and a debug field
    that survives to a demonstration gets used in one*
- [x] **W3.13** — **Sliding-window decoding.** Decode 1.5 s windows with 0.4 s overlap
  *while the speaker is still talking*, so only the final partial window is decoded after
  the endpoint; stitch the windows into one hypothesis
  · *[ASR.md §3.5](ASR.md#35-decoding-an-offline-model-without-paying-for-it-at-the-end)*
  · **Done when** post-endpoint decode is 250–450 ms rather than ~900 ms, measured
  · *Risk T-16. Without this the end-to-end figure is ~1330 ms and the latency criterion
  is lost. Costs ~1.6× compute, affordable because it runs only during speech*
  · *`SlidingWindowDecoder` in `core-asr`, with the model injected so the windowing is
    tested without one. **The load-bearing test sweeps every utterance length from
    100 ms to 8 s and asserts the undecoded tail never exceeds one window** — so the
    post-endpoint decode is bounded by a constant instead of growing with the utterance*
  · *A 3 s utterance leaves 800 ms to decode, not 3 000 ms — ~240 ms at RTF 0.30*
  · *Stitching removes words repeated in the overlap by longest suffix/prefix match.
    Where two windows share nothing it **concatenates rather than trims**: a listener
    recovers from a repeated word, never from one silently dropped*
  · *Still to bind: the real recogniser call, which waits on W1.23 (sherpa-onnx)*

- [ ] **W3.G** — **GATE:** speech in on A, speech out on B. Baseline end-to-end latency
  recorded in `latency.csv`. Video. **The project is now de-risked**

---

## Week 4 — Real models

**Gate W4: five languages recognised; `bench` produces a `scorecard.csv`.**

- [~] **W4.1** — `tools/export_indicconformer.py` — NeMo → ONNX
  · *Risk T-07: if this fails, Vosk from W1.26 keeps the schedule intact. Three weeks of
  slack sit behind it*
  · *Script written; it cannot be run here — NeMo pulls a full PyTorch stack. It writes an
    `export.json` receipt with the size and SHA-256 of every file it produced, so the
    manifest is filled from the tool rather than by hand, which is exactly where a wrong
    digest would come from*
  · *No `--lang` option, deliberately: the model is multilingual, so "the Hindi model" is
    not a thing that exists*
- [~] **W4.2** — `onnxruntime.quantization.preprocess` + `quantize_dynamic` int8,
  **encoder only** — quantising decoder and joiner hurts accuracy for no size gain
  · *`tools/quantise.py`. Refuses to describe its output as usable and prints the
    verification command instead*
- [~] **W4.3** — `tools/verify_quantisation.py`
  · **Done when** WER delta vs float32 is < 1.5 % relative, **per language**
  · *Mandatory. The whole efficiency argument rests on this claim; it must be
  re-established per language, not assumed*
  · *Written. **Exits non-zero when the regression exceeds the threshold**, so it gates a
    release rather than merely printing a number. Its tokeniser mirrors the Kotlin
    `WerScorer`; where they disagree the Kotlin one is authoritative, because that is the
    one that produces the figures in the report*
- [x] **W4.4** — `models/manifest.json` schema: **one shared acoustic-model entry plus a
  per-language vocabulary and voice entry** — the acoustic model is not per language
  · *[MODELS.md §1](MODELS.md#1-delivery) and §3. Risk T-17*
  · *Written, all ten languages, plus the `Manifest` parser in `core-models`. MODELS.md §3
    still showed the pre-correction per-language `asr` block, contradicting §1's own
    correction; the schema there is now the one the code actually reads*
  · *A test asserts every manifest index matches the wire encoding in `core-proto`. If
    those disagree, two handsets decode the same frame as different languages*
  · *Hashes are validated on parse — 64 lowercase hex characters or the manifest is
    refused, so a placeholder fails while reading the index rather than after a 120 MB
    download*
- [x] **W4.5** — `core-models`: manifest loader, SHA-256 verify, **atomic install** (temp
  file then rename — a partial pack must never be loadable), resumable download
  · *The ~120 MB shared model download must resume; it will be interrupted on a weak
  connection far more often than a 60 MB voice*
  · *`PackInstaller` + `ResumePlan`. **A test proves the guarantee directly**: a download
    that dies midway leaves the destination absent and no partial file behind, and a
    failed replacement leaves the previously installed pack intact*
  · *A truncated ONNX model does not fail cleanly — it may load and emit nonsense, and
    nonsense spoken aloud with confidence is the worst output this system can produce*
- [x] **W4.6** — Language switch: unload outgoing, load incoming, blocked while floor held
  · *`LanguageSwitch`. The ordering is mandatory, not incidental: loading before unloading
    needs both models resident on a handset chosen for being cheap, which is the likeliest
    way to be killed by the out-of-memory reaper — during a language switch, in front of a
    jury*
  · *A missing pack is reported **ahead of** a held floor, so a device that simply lacks
    the language says so rather than blaming a transmission that is not the problem*
- [x] **W4.7** — Pack licence is a **load-time precondition** — a pack with no licence field
  fails verification and does not load
  · *Checked before a single byte is written, not at load: discovering an unlicensed pack
    after a 120 MB download is too late*
- [x] **W4.8** — `bench` WER scorer: NFC, punctuation strip, case fold, whitespace collapse,
  numerals compared in spoken-word form
  · **The single source of truth. No figure is ever computed by hand**
  · *`WerScorer`, 20 tests. Substitutions, deletions and insertions counted separately;
    WER may exceed 100 %, because a recogniser that hallucinates a long sentence from a
    short one genuinely does, and clamping would hide the worst failure mode there is*
  · *The numeral rule is applied to **both** sides, so it cannot flatter the result
    depending on which side happens to hold the digits*
  · *`corpusWer` pools edits rather than averaging per-sentence rates — a mean of rates
    over-weights short sentences, and on the fixture in the test it reads five times too
    high*
- [x] **W4.9** — Noise mixer: crowd / wind / engine / siren at +20, +10, +5 dB SNR, **fixed
  seed** so results reproduce
  · *`NoiseMixer`. A test measures the SNR of each mix back out and asserts it lands within
    0.5 dB of what was asked for — a mixer that does not achieve its stated ratio makes
    every noise-robustness figure decorative*
  · *Clipping is counted and reported rather than hidden: a mix that clips heavily is
    measuring the mixer rather than the recogniser*
  · *Seed 26173 by default, recorded in every scorecard row*
- [ ] **W4.10** — IndicSUPERB / Kathbath subsets downloaded for five languages
- [x] **W4.11** — `scorecard.csv` writer with the full schema, recording device and build
  · *`ScorecardWriter`. A row without a device or a build is **refused** — a scorecard that
    does not name its hardware is not evidence. A mean opinion score without its panel
    size is refused for the same reason*
  · *An unmeasured figure is written empty; a measured zero is written `0.0`. A results
    file must never confuse "perfect" with "never ran"*
  · *[EVALUATION.md §6](EVALUATION.md#6-instrumentation)*
- [x] **W4.12** — **Confirm the Piper Odia voices exist** (Debjani, Manas) in the official
  `rhasspy/piper-voices` repository — **do this in week 1, it takes ten minutes**
  · **Answered 2026-09-04, and the answer is no.** *The `rhasspy/piper-voices` repository
    tree has **no `or` directory**, and `piper/VOICES.md` lists no Odia voice. The names
    Debjani and Manas came from a search summary, not the repository, and were wrong*
  · **The gap is wider than Odia.** *Piper has no Tamil, Gujarati or Kannada either. It
    covers six of our ten: en, hi, bn, mr, te, ml*
  · *Risk **T-05 re-escalated to High and re-opened**. Meta MMS and its CC-BY-NC disclosure
    cannot be dropped — and would now apply to four languages, not one. `LICENSES.md`
    records a third option honestly: ship those four recognise-only, which is a smaller
    loss than it sounds and much smaller than an undisclosed non-commercial dependency*
- [~] **W4.13** — `alert-lexicon.txt` per language: ~300 domain terms
  · *`BiasingLexicon` plus `models/lexicon/alert-lexicon.hi.txt` — ~130 Hindi terms across
    distress, medical, hazards, logistics, units, places and radio procedure*
  · *Hindi only. The remaining nine need a speaker of each language, not a translation
    engine — a wrong term in this list biases the decoder **towards** a word nobody says*
- [x] **W4.14** — **Negation terms weighted high** — "not", "do not", "नहीं"
  · *Risk S-03. "Do not evacuate" becoming "now evacuate" is the most dangerous single
  failure this system can produce*
  · *`models/lexicon/negation.hi.txt` at weight **4.0** against 1.5 for domain terms and
    2.5 for roster names. A test asserts the **negation floor exceeds the domain ceiling**
    across the shipped files, so the ordering cannot be undone silently*
  · *The asymmetry is deliberate: a spurious "not" is an obvious confusion that gets
    queried, while a dropped one is a fluent instruction meaning the opposite*
- [x] **W4.15** — Roster display names injected into biasing at runtime
  · *`BiasingLexicon.withRoster`. Unit names are proper nouns, usually absent from any
    training corpus, and are exactly what a message is addressed to*
  · *A roster name never lowers a weight already assigned, so a call sign colliding with a
    negation term cannot demote it*
- [x] **W4.16** — Confidence thresholds calibrated per language, stored in the manifest —
  **not hard-coded**. A threshold right for Hindi is wrong for Odia
  · *`Pack.confidenceLow` / `confidenceHigh`, refused if inverted. Calibration itself is
    week 7; the values in the manifest are placeholders and are marked as such*

- [ ] **W4.G** — **GATE:** `scorecard.csv` exists with WER at four SNRs for five languages

---

## Week 5 — Modes and alerts

**Gate W5: an alert wakes a locked, silenced handset and announces at full volume.**

### Push-to-talk and phone mode

- [x] **W5.1** — Floor state machine: free / held by me / held by peer / contended
  · *`FloorControl`. **It cannot prevent collisions and does not claim to** — a seize is
    an announcement broadcast to everyone, not a request granted by anyone, and it takes
    20–60 ms to arrive. Two operators pressing inside that window will both believe the
    floor is theirs. What this does is detect and resolve that quickly*
  · *A peer hold expires after 12 s. Without it the channel deadlocks permanently the
    first time a unit walks out of range mid-transmission, and the failure is silent
    because every remaining unit believes someone else is talking*
- [x] **W5.2** — `PTT_CTL` seize and release frames; channel-busy indicator
  · *`PttControl` in `core-proto` — one byte, `0x01` seize / `0x00` release, 13-byte
    frame. A reserved value is refused rather than guessed*
  · *A release from a unit that does not hold the floor is **ignored**, so a stale frame
    from a third unit cannot cut a live transmission short*
- [x] **W5.3** — Randomised backoff on contention · *Risk S-05*
  · **Deterministic first, randomised second.** *Pure random backoff on both sides can
    have both units yield — losing the message — or both retry into a second collision.
    The lower `SRC` wins outright and only the loser backs off, so both ends compute the
    same answer from the same two numbers with nothing further exchanged*
  · *Tested as a **pair of units resolving one collision independently**: the property
    that matters is not that each behaves sensibly alone but that the two agree*
- [x] **W5.4** — **Volume-down hardware key binding, working with the screen off**
  · *`PushToTalkKey`, 9 tests, wired into the bring-up screen and shown on it so the
    binding can be checked on a handset. Press-and-hold, not toggle: a toggle would
    leave a handset transmitting after a knock in a pocket, silencing everyone else
    until the 12 s stale-hold expiry. Auto-repeat is filtered, and losing focus
    releases the floor*
  · *`VolumeKeyCapture` now covers the screen-off half — and the mechanism is not the
    obvious one. **Media-button events are a dead end**: `onMediaButtonEvent` delivers
    `KEYCODE_MEDIA_*` and headset hook, never the volume keys. The platform routes volume
    keys to the active media session only when that session declares **remote** playback
    with a `VolumeProvider`*
  · **Honest limitation.** *A `VolumeProvider` receives adjustments, not key up and down.
    A hold is therefore inferred: the first adjustment opens the floor and 400 ms of
    silence closes it. The floor is released up to 400 ms after the operator lets go —
    dead air on the channel, not a lost word, since the endpointer has already finished.
    The alternative is an accessibility service, which asks the operator to grant a
    permission reading "this app can watch everything you do" for a few hundred
    milliseconds*
  · *Rule 4. Operators wear gloves and rarely look at the screen*
- [x] **W5.5** — Half duplex: speaker muted while transmitting; 150 ms endpoint on release
  · *`DuplexPolicy`. **The mute is not politeness** — without it the handset's own speaker
    feeds its microphone, the energy gate never closes, and the operator's transmission
    never ends*
- [x] **W5.6** — Full duplex: continuous VAD-gated streaming both directions
  · *Capture runs continuously and the VAD decides what is speech; the endpoint window
    moves to 400 ms because there is no key release to signal the end*
- [x] **W5.7** — Barge-in: duck to −18 dB within 100 ms, stop at chunk end
  · *Ducked rather than cut, and stopped at a chunk boundary rather than mid-word: an
    abrupt stop sounds like a fault, and an operator who hears a fault repeats themselves*
  · *A test derives the gain from the decibel figure rather than trusting the constant*
- [x] **W5.8** — A press while the floor is held gives a **haptic refusal, never a dialog**
  · *`Reaction.Refused` carries who holds the floor so the display can name them. Meena
    is gloved at altitude with the screen dark; a dialog would have to be dismissed
    before she could try again*

### Alert delivery — all six steps

- [x] **W5.9** — `AudioAttributes.USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION`
  · *Declared in the `AlertPlayback.AudioSystem` contract; the Android implementation
    of that interface is still to be written*
- [x] **W5.10** — `setStreamVolume(STREAM_ALARM, max, 0)` before playback
  · *Ordering asserted by test: the volume is raised before focus is requested and
    before any audio plays*
- [x] **W5.11** — **Restore the prior volume afterwards**
  · *Easy to forget, and forgetting leaves the handset permanently at max alarm volume —
  a defect certain to be found during a demonstration*
  · *Restoration runs in a `finally`, and **a test asserts it still happens when playback
    throws** — that is the case where the volume would otherwise stick at maximum. A
    handset found silenced is left silenced*
- [x] **W5.12** — `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, **loss callbacks deliberately
  ignored** — this is precisely the non-interruptible requirement
  · *A **refused** focus request is ignored too, and a test proves the alert still plays.
    Another application holding focus is exactly the situation an alert must override*
- [x] **W5.13** — `PARTIAL_WAKE_LOCK` + full-screen-intent notification
  · *`AndroidAlertAudio`. The wake lock carries a 60 s timeout, because a stuck one
    destroys the eight-hour standby figure in N3*
  · *Android Lint caught the missing `USE_FULL_SCREEN_INTENT` permission — required
    from Android 14 and granted automatically only to alarm and calling apps*
  · *The wake lock is taken **first** — everything after it is pointless if the device
    sleeps — and released in the same `finally` as the volume*
- [x] **W5.14** — Vibration pattern, full-screen visual, message repeated twice
  · *Long pulses, deliberately unlike any notification tick: an operator should be able
    to tell an alert from a message without looking. Announced twice — once is missed in
    a noisy environment*
- [x] **W5.15** — `ALERT` pre-empts the transmit queue; ack + retry 3 × 300 ms
  · *`AlertDelivery`. An alert overtakes queued text but **not another alert**, so two
    alerts stay in the order they were spoken*
  · *An alert that exhausts all three attempts with no answer is reported as
    `undelivered`. A silent failure here is the worst outcome the interface can produce —
    the sender believes the warning went out*
- [x] **W5.16** — Delivered on the **first** ack; UI shows `3 of 6 units`
  · *Two different questions with two different answers. **Delivered** is true on the
    first ack — one unit hearing an evacuation order is the difference between the
    message working and not, and retrying past that puts a duplicate on a channel now
    carrying the reply. **The count keeps rising** afterwards, because an operator
    deciding whether to send a runner needs the number, not the boolean*

### Alert screens

- [x] **W5.17** — Alert compose: six template buttons + hold-to-speak
  · *[WIREFRAMES.md §10](WIREFRAMES.md#10-alert-compose). Icon **plus** word, never a word alone*
  · *`AlertComposeScreen`. Icon first and larger, because it is the primary carrier for an
    operator who cannot read the word beneath it*
- [x] **W5.18** — Confirm-before-send: text spoken aloud on open, **RETAKE and SEND exactly
  equal in size**
  · *A confirmation that makes the safe option smaller is not a confirmation*
  · *Both buttons take `weight(1f)` and the same minimum height, so they are **equal by
    construction** rather than by two numbers that could drift apart in a later edit*
  · *The text is spoken from `LaunchedEffect` on open, not from a button — the operator
    who most needs it is the one who would not know to ask*
- [x] **W5.19** — Incoming alert full screen, **no swipe-to-dismiss** — a swipe is something
  a pocket can do
  · *`IncomingAlertScreen`. Dismissal requires the ACKNOWLEDGE target, which is also what
    drives `3 of 6 units` on the sender — so an alert nobody acknowledged looks different
    from one everybody did*
- [x] **W5.20** — "Test alert on this device" in settings
  · *Vendor audio policy varies; also lets demo step 5 be rehearsed solo*
  · *`TestAlertButton`. A unit that has never announced an alert on **this model of
    phone** has not been tested, whatever the documentation says*

### Instrumented tests — on the target handset, not an emulator

- [~] **W5.21** — Alert on locked handset · **Done when** audio at max, screen wakes, vibration fires
  · *`AlertDeliveryInstrumentedTest` written and compiling; `testInstrumentationRunner` was
    missing from the app module, so instrumented tests would not have run at all*
  · **Needs a handset.** *The policy is unit-tested and passes; none of that proves an
    alert is **audible** on a locked, silenced phone, because what is being tested is the
    vendor's audio policy rather than our code*
- [ ] **W5.22** — Alert with ringer silenced
- [ ] **W5.23** — Alert in Do Not Disturb
- [~] **W5.24** — Alert during music playback
- [~] **W5.25** — Alert during a phone call — documented behaviour, queued and repeated after
  · *`CallAwareAlerts`. The one exception to "an alert always overrides", for two reasons
    rather than one: during a call the platform attenuates or drops an alarm-stream
    announcement, so it would not work — and if it did, it would take out the channel the
    operator is more likely to be coordinating on*
  · *Held, never dropped, announced in full in arrival order when the call ends. The
    handset vibrates on arrival, which reaches an ear pressed to a phone and does not enter
    the call audio*
  · **Blocked:** *the call detection. `TelephonyManager` belongs in the app module and needs
    a handset and a SIM to prove*
- [~] **W5.26** — Volume restoration verified
- [~] **W5.27** — Focus loss ignored during alert playback
- [~] **W5.28** — Mic pre-empted by a call → `DEGRADED` with reason, auto-recovery
  · *Detection is in `AudioCapture`: a failed `AudioRecord` construction or a dead
    object surfaces `MICROPHONE_UNAVAILABLE`, which the engine shows verbatim*
  · ***Auto-recovery is not built.*** *It needs `registerAudioRecordingCallback` to
    notice the microphone coming back, and there is no point writing that against a
    guess at when the platform fires it* · *T-12*

- [ ] **W5.G** — **GATE:** video of a locked, silenced, DND handset announcing at full volume

---

## Week 6 — Latency, reach, cryptography

**Gate W6: encrypted frames, replay rejected, three transports pass one suite, first soak.**

- [ ] **W6.1** — Chunked synthesis tuned; time-to-first-audio measured and recorded
- [x] **W6.2** — Stabilised partials; `PARTIAL` flag; receiver may begin early synthesis
  · *Depends on W3.13 — partials come from completed decode windows, not from the model*
  · *`StabilisedPartials`. A word is released only after surviving **three consecutive
    partials unchanged**, which in practice withholds exactly the last few words of the
    newest window — the part the next overlap usually revises*
  · ***Nothing is ever un-released***, *because a word the receiver has already spoken
    cannot be recalled. A disagreement waits for the `FINAL` frame, which carries the whole
    utterance rather than a remainder*
- [x] **W6.3** — Adaptive endpointing
  · *`AdaptiveEndpoint`. The silence window is the **largest single term in the latency
    budget**, and a fixed value has to be set for the slowest speaker, so everyone else
    pays*
  · ***Deliberately asymmetric***: *it rises fast on evidence of long pauses and falls
    slowly. A symmetric estimator would shorten the window after a run of brisk sentences
    and then clip the next thoughtful one. Bounded to 120–700 ms whatever the evidence*
- [~] **W6.4** — `BleLink`: GATT, MTU negotiated to 247, ~244 usable
  · *BLE is the transport for **waiting**; RFCOMM is the transport for **talking**. A unit
    spends most of its day waiting, and that is where the eight-hour standby figure comes
    from*
  · ***The negotiated MTU is not guaranteed*** *— some handsets refuse and stay at 23. So
    `mtu` is read after connection rather than assumed, and the `Fragmenter` is built per
    connection from what was actually agreed*
  · *`WRITE_TYPE_DEFAULT`, acknowledged. `NO_RESPONSE` is faster and drops silently under
    congestion; the CRC catches corruption but nothing catches a frame that never arrived*
  · *The CCC descriptor is written, not just the local notification flag — forgetting it
    is the classic BLE bug where everything looks connected and nothing arrives*
  · *Android Lint caught a real defect: the API 33 `writeCharacteristic` returns a
    `BluetoothStatusCodes` value, not a GATT status. Both are 0 for success, so the wrong
    comparison would have worked by accident*
- [x] **W6.5** — Fragmentation for BLE and serial: chunks of `mtu - 12`, 2 s reassembly
  timeout, **AEAD verified after reassembly**
  · *`Fragmenter` + `Reassembler`. **Reassembly never decrypts** — a fragment is a slice
    of ciphertext with no tag of its own, so decrypting per fragment would mean processing
    attacker-chosen bytes before anything is authenticated, which is the exact position
    AEAD exists to avoid. Reassembly validates only sizes and indices and hands opaque
    bytes upward*
  · *`FINAL` is cleared on every fragment but the last, so a receiver that ignores
    fragmentation entirely cannot mistake a slice for a complete message*
  · *A repeated fragment is not counted twice — otherwise a retransmission could make a
    partial message look complete*
  · *Bounded in size (64 fragments) **and in time** (2 s): without the timeout a sender
    that dies mid-message leaks its fragments for the life of the process, and a hostile
    peer could hold memory open by sending one fragment of many thousands of messages*
- [~] **W6.6** — `WifiLink`: hosted network, TCP 38173, UDP discovery 38174
  · *`TCP_NODELAY` on both ends. Without it Nagle holds a 44-byte frame waiting for more
    data to coalesce, adding up to 40 ms to an 800 ms budget — a latency bug that would
    look like a slow model*
  · *UDP beacon on 38174 so a joining unit never has to be told an address by a person
    reading it off a screen*
  · ***Superseded 2026-09-06.*** *This entry claimed no `INTERNET` permission was needed
    because sockets bound to a local address do not require one. That is wrong: Android
    requires the permission to open **any** socket, and the Wi-Fi transport could not open
    one until it was declared. `WifiLink` — the TCP/discovery design described here — was
    never wired to anything and was deleted; `WifiBroadcastLink` replaced it, and C2 is now
    verified by inspection rather than by the permission list*
  · *Two limitations found reviewing it, neither exploitable but both worth fixing before
    the transport gate: the listener **binds every interface**, so on a handset also joined
    to a home or campus network the port is reachable from it; and it accepts **one**
    connection, so a hostile connection on such a network could occupy the slot and keep
    the real peer out. Frames are still AEAD-authenticated, so nothing can be injected —
    the exposure is denial of service, not forgery*
  · *Hosted network is primary. `WifiP2pManager` is optional — risk T-09*
- [~] **W6.8** — **Same integration suite runs green against all three transports**
  · *`TransportContractTest` states the contract once against the `Link` interface and runs
    it against `LoopbackLink` in full. **The value is not in testing any one transport** —
    it is in proving the application cannot tell them apart; a per-transport suite would
    drift and surface as "works on Bluetooth, not on Wi-Fi"*
  · *The hardest requirement is that **exactly one complete frame** arrives per emission,
    so the loopback delivers **one byte at a time** to prove no consumer ever sees a
    partial*
  · **Needs two handsets** *for the three real transports; the contract above is the
    specification those runs check*
- [x] **W6.9** — Enable AES-GCM on every transport; tag length by transport class
  · *`TransportClass`. Bluetooth, BLE and Wi-Fi take the full 16-byte tag; a 300 bps serial
    link truncates to 8, which is over twenty seconds of airtime per message*
  · ***Truncation is refused unless rate limiting is declared***. *A 2⁻⁶⁴ forgery
    probability is negligible per attempt and stops being negligible once attempts are
    unbounded, so the dependency is enforced in code rather than left in prose*
  · *An unknown transport gets the **full** tag — the safe default is the one that costs
    bytes, never the one that costs security*
- [x] **W6.10** — `EPOCH` survives force-stop and reboot
  · **Done when** a test kills the app, reboots, and shows `EPOCH` incremented and no
  replay rejection of new frames
  · *`EpochCounter`. **Persist before use, not after** — writing afterwards leaves a
    window in which the process dies having transmitted under an epoch that was never
    recorded, and the next start reuses it. Being one epoch ahead after a crash costs
    nothing; being one behind is a total loss of confidentiality*
  · *A failed write means no epoch is issued at all: transmitting under an unrecorded
    epoch is worse than failing to start*
  · *Exhausting the 2³² epoch space **refuses rather than wrapping** — wrapping here is
    silent nonce reuse*
  · *A test drives restarts and sequence wraps through the real `Aead.nonce` derivation
    and asserts no nonce ever repeats*
  · *`DataStoreEpochStore` now provides the on-device half. **Its writes block on
    purpose**: a suspending write would let the caller continue before the value was
    durable, which silently reopens the window the whole design closes. It is called
    twice per process, so the cost is irrelevant*
  · *The instrumented kill-and-reboot test remains*
- [~] **W6.11** — Pairing screen: QR generate and scan, `FLAG_SECURE`, key straight to
  Android Keystore, decoded string never written to disk
  · *`PairingCode` (23 tests), `KeystoreVault`, `PairingScreen`*
  · *The code **fails closed**: expired is refused, and so is a forged code claiming a
    lifetime longer than the 120 s rule allows — the limit is enforced where it is
    **scanned**, not merely where it is generated*
  · *`decode` returns null for everything malformed and says nothing about why. A camera
    reads whatever is put in front of it, and a parser that explains its rejections is an
    oracle*
  · *`toString` redacts the key and a test asserts it. **The likeliest way key material
    escapes is not an attacker but a developer printing an object** — risk S-04*
  · *`KeystoreVault` deliberately offers no way to read the key back as bytes. Not because
    a determined caller could not, but because an API that hands out raw key bytes is one
    that will eventually appear in a log line*
  · *Camera capture and the manual-entry fallback still need wiring to the screen*
  · **The one thing not to forget when wiring it.** *`pairingWindowFlags` exists but
    nothing sets it yet, because there is no pairing activity. The QR code on screen **is**
    the key; without `FLAG_SECURE` it lands in the recents thumbnail, in screenshots and in
    screen recordings. The hosting activity must set it in `onCreate` before the screen is
    ever shown*
  · *Known limitation: the encoded code is a `String` while it is displayed, and a Java
    `String` cannot be wiped. `FLAG_SECURE` and the 120 s expiry are what bound that
    exposure; the key itself is zeroed via `destroy()` once it reaches Keystore*
  · *[WIREFRAMES.md §3](WIREFRAMES.md#3-pairing)*
- [x] **W6.12** — `KEYID` derived as `SHA-256(key)[0]`, never configured, never shown
  · *`Aead.keyId`, tested. Derived rather than configured, so two units that hold the same
    key always agree on its identifier without exchanging one*
- [x] **W6.13** — Relay: `TTL` decrement, 512-entry LRU seen-set on `(SRC, EPOCH, SEQ)`,
  0–50 ms random delay · *Risk T-10*
  · *`Relay`. **All three mechanisms are necessary and the class says why**: the seen-set
    alone fails because a frame can arrive by two paths before either rebroadcast
    completes; the TTL alone fails because a three-unit loop multiplies traffic at every
    hop; the delay alone prevents neither*
  · *The headline test simulates the actual storm — three units in mutual range, one
    message — and asserts exactly three rebroadcasts and then silence*
  · *The epoch is in the key because `SEQ` wraps at 65 536; without it the first frame
    after a wrap would be suppressed as a duplicate of one from before it*
- [x] **W6.14** — UNSECURED banner: red, permanent, undismissable, no silent path
  · *`UnsecuredBanner`. **A banner an operator can dismiss is a banner an operator will
    dismiss**, and the condition persists after the dismissal — which is why this is a
    banner rather than a one-time dialog*
- [x] **W6.15** — `TEMPLATE MISMATCH` warning; template sending disabled on digest mismatch
  · *Risk S-06 — a safety defect, not a compatibility inconvenience*
  · *`TemplateMismatchBanner`. Byte 4 might be EVACUATE on one handset and ALL CLEAR on the
    other, and **nothing about that failure looks like an error** — no garbled audio, no
    checksum failure. Sending is disabled rather than merely flagged; free text still works,
    and free text carries its own words*
- [ ] **W6.16** — **First 30-minute thermal soak**; sustained RTF recorded
  · *Risk T-03. **Done when** RTF after soak < 2× RTF cold. Finding throttling in week 8
  is finding it too late*
- [x] **W6.17** — Reduce thread count under thermal pressure
  · *`ThermalThreads`. Once the platform is reducing clocks, more threads make it worse —
    the cores are already contended and the scheduling is overhead on a device shedding
    heat. Backs off at MODERATE, before the platform forces it, and warns the operator at
    SEVERE because latency will be visibly worse*
- [x] **W6.18** — `resource.csv` writer: CPU, RSS, battery, thermal state
  · *`ResourceLogWriter` + `ResourceRun.summarise`. **`minutesBeforeThrottling` is the
    figure worth quoting** — an entry-tier handset throttles after roughly ten minutes of
    continuous inference, and every timing figure taken after that is a throttled one*
  · *`wasOnCharge` detects a rising battery, because a soak run taken on charge is not an
    endurance measurement and that is how you find out*
  · *A sample that cannot be true — negative processor use, battery above 100, thermal
    status out of range — is refused rather than averaged in*

- [ ] **W6.G** — **GATE:** replay rejected, mutation test green, four transports pass, soak
  trace recorded

---

## Week 7 — Coverage and hardening

**Gate W7: ten languages, normalisation 100 %, relay soak clean, field test recorded.**

- [~] **W7.1** — Remaining five languages enabled — **vocabulary and voice per language;
  the acoustic model is already present** — verified and in the manifest
  · *Vocabulary done for all ten: `alert-lexicon.<lang>.txt` and `negation.<lang>.txt`,
    about eighty terms each across the same seven categories, and the manifest now names
    the negation file so a pack that installs without it fails verification*
  · *`TenLanguageLexiconTest` asserts per language, not Hindi ten times: the negation
    floor must outrank the domain ceiling **in each language**, and every Indic list must
    be nine-tenths in its own Unicode block — which catches a file copied from another
    language and never translated, since that parses and weights perfectly*
  · **Blocked:** *the voices. Four languages (ta, gu, kn, or) have no permissively
    licensed voice at all and are `"tts": null` in the manifest; the rest need the files
    fetched. Recognise and display work; speaking does not*
- [x] **W7.2** — `normalise.json` for all ten languages
  · *`NormaliseSpec` + `models/rules/normalise.<lang>.json`. Week 3 shipped the engine and
    Hindi's rules as Kotlin, which was the right order — the engine had to be proven
    against a language whose numerals are exhaustively irregular before the format could
    be designed*
  · *Two numeral shapes, because the languages genuinely differ: the Indo-Aryan five list
    all hundred values, English and the Dravidian four compose from twenty upward.
    Forcing one shape on both costs either eight hundred hand-written words that can be
    generated, or a generator emitting plausible non-words in five languages*
  · *The loader is proved against the code it replaced, not against examples: the Hindi
    file must agree with `HindiNumerals` on **every** value 0–100 000 and on all 1 440
    times of day. A table off by one index reads every value in the eighties as the wrong
    word and nothing crashes*
  · *[TTS.md §1](TTS.md#three-extensions-added-in-week-7-w72) documents the three format
    extensions*
- [x] **W7.3** — Normalisation fixtures ≥ 60 cases × 10 languages, **100 % in CI**
  · *Adding a rule without its fixtures is a rejected review*
  · *68 cases per language in `models/fixtures/`, plus three properties that need no
    native speaker: **no digit survives**, every value below a lakh renders, every minute
    of the day renders. Sixty hand-listed cases will not find a one-value gap in a
    hundred-entry table; the property sweep will*
  · *Writing the fixtures found a real defect. `20.29` normalised to `बीस.उनतीस`, leaving a
    full stop for the phonemiser to read as a sentence break — a grid reference arriving
    as two unrelated numbers. Fixed in all ten languages*
  · *The fixture files are a **regression lock, not an oracle**: generated from the rule
    files and read over by an engineer. `reviewed: false` on eight languages says so, and
    the suite asserts which two are exempt*
- [x] **W7.4** — `templates.json` in all ten languages, one deployment profile
  · *24 operational sentences, `TemplateProfile` loading them into the `TemplateTable`
    that already owned the digest and the matching rule*
  · *A profile is **refused at load** unless every template carries all ten languages.
    A row missing its Tamil text makes `render` return null and the Tamil operator hears
    silence — not a garbled sentence they would query. Exactly the gap that survives
    review, because the file looks fine and the sender's language is present*
  · *Two tests worth naming: every language must match its **own** text back to its own
    id, or a template can be received and never sent; and "Fire, evacuate immediately"
    must not match "Do not evacuate, stay where you are" in **any** of the ten. Risk S-03
    in its sharpest form, asserted per language because the margin differs per language*
- [x] **W7.5** — Deployment gazetteer loading (~200 place names, sectors, callsigns)
  · *`Gazetteer`. Separate from the domain lexicon because the two fail differently: a
    missing domain word costs accuracy, a missing place name costs the one word in the
    sentence that says where to go, and it is a proper noun no acoustic model has seen.
    Weighted above the lexicon for that reason, not for frequency*
  · *Sector numbers are generated from a range rather than listed, so a gazetteer stays
    something a coordinator can write in a text editor under time pressure*
- [~] **W7.6** — RNNoise integration, recognition path only, **never** audio the user hears
  · *`RecognitionTap` is the only place a suppressor can be installed, and the playback
    classes take none — the rule is enforced by the violation being inexpressible rather
    than by a comment asking people not to. Synthesised speech is already clean, so a
    suppressor can only remove the consonant detail that separates similar words; and a
    speech-tuned suppressor is entitled to treat the alert tone as noise, which would make
    the one signal that must never be missed get quieter the longer it plays*
  · *320 samples at 16 kHz upsample by three to exactly two 480-sample RNNoise frames, so
    a 20 ms hop needs no carry buffer. A happy accident of the hop size, not a design*
  · *Disabled is a real implementation and byte-for-byte free — the measured answer for
    some languages is "off", and a pass-through that quietly resampled twice would put a
    cost on the configuration chosen to avoid one*
  · **Blocked:** *`librnnoise_jni.so` is not built. RNNoise has no Android release
    artefact and needs the NDK for `arm64-v8a`. `create()` returns null and the caller
    falls back to no suppression: a handset that cannot suppress noise still recognises
    speech. BSD-3-Clause, already in LICENSES.md*
- [~] **W7.7** — Per-language decision on noise suppression **from measurement, not
  preference** — report WER with it on and off at each SNR
  · *`SuppressionPolicy`, deliberately separate from `AccuracyMatrix`: a decision taken
    inside the measurement loop is not auditable. The rule is fixed **in advance** so it
    cannot be adjusted once the numbers arrive — suppression goes on only if it improves
    critical-term error at **both** adverse SNRs by more than a stated margin **and** does
    not cost clean speech*
  · *The second half catches the plausible mistake: a suppressor that pays for itself at
    +5 dB and quietly costs accuracy in the quiet room where most messages are spoken*
  · *Not measured is reported as **undecided**, never as disabled. The first reported as
    the second is a decision dressed up as a result*
  · **Blocked:** *the run. Needs the acoustic model and the evaluation corpus*
- [~] **W7.8** — Full WER run: 10 languages × 4 SNRs
  · *`AccuracyMatrix` runs all 160 cells — ten languages × four conditions × suppression ×
    biasing — in one pass with one noise seed. Three separate runs would guarantee the
    three tables disagree*
  · *The audio for a given SNR is mixed **once** and shared across all four switch
    settings, so the deltas are attributable to the switches and nothing else. Asserted*
  · **Blocked:** *models and corpus. `Report.isMeasured` is what a caller asks before
    quoting anything; an empty report is not a report of perfect accuracy*
- [~] **W7.9** — CTER with and without biasing, in the same table
  · *The delta is directly attributable to an engineering decision the team made*
  · *`CriticalTermScorer` implements the EVALUATION.md definition: reference occurrences
    absent from the hypothesis, counted per occurrence rather than per sentence, with no
    credit for near misses — आग and आठ differ by one character and mean fire and eight*
  · *`Report.biasingDelta` puts the pair side by side so the delta is read off rather than
    reconstructed from two documents*
- [~] **W7.10** — MOS listening panel executed, 15 speakers per language
  · *Recruited in P0.10. **Report the actual panel size** — a MOS without one is not a
  measurement*
  · *`MosPanel` enforces the three rules EVALUATION.md states and people forget: the panel
    size travels with every figure, nothing is reportable below fifteen listeners, and the
    **listener is the unit of analysis, not the rating** — twenty ratings from one
    enthusiast and one from a sceptic average to 3.0, not 4.81*
  · *A listener who scored everything the same is named rather than dropped. Removing them
    is a judgement about the data taken after seeing it*
  · **Blocked:** *the panel. Needs synthesised audio and fifteen native speakers per
    language*
- [~] **W7.11** — Intelligibility test: native listeners transcribe synthesised output
  · *`IntelligibilityPanel`. Word accuracy against the synthesised text, averaged across
    listeners for the same reason MOS is, with the worst-scoring samples reported by name*
  · *This is also what clears a numeral table's `reviewed` flag. A wrong numeral in a JSON
    file is a spelling somebody has to notice; a wrong numeral in a synthesised sentence
    is fifteen listeners writing down a different number*
  · **Blocked:** *same panel as W7.10*
- [ ] **W7.14** — Field test at range: Bluetooth 30 m, Wi-Fi 150 m
  · **Blocked:** *two handsets and an open space. Nothing about radio loss, collision or
    the timing of the jitter window can be established in simulation, which is exactly why
    this task is not covered by W7.15*
- [x] **W7.15** — Four-device relay soak, 1 h · **Done when** every message arrives exactly
  once and the seen-set stays bounded
  · *`RelaySoakTest`. An hour of traffic through four real `Relay` instances on a **chain**
    topology, not a clique — in a clique every device hears every other directly and
    relaying is never exercised. A message from 1 reaches 4 only by being relayed twice*
  · *Exactly once at every device, no device handed its own transmission back, seen-set
    under capacity throughout, and total transmissions under the storm ceiling. A second
    case walks a full `SEQ` wrap, which is inside an eight-hour deployment*
  · *One assumption is stated in the file rather than hidden: each message finishes
    propagating before the next is sent, so the seen-set's eviction horizon is never the
    thing under test*
- [ ] **W7.16** — Eight-hour endurance soak: BLE, screen off, listening · *> 8 h drain*
  · **Blocked:** *a handset, eight hours and `batterystats`. The figure is a week-8
    measurement — [W8.6](#week-8--evidence-and-rehearsal)*
- [x] **W7.17** — Memory soak 1 h: resident memory flat, no leak in ring or outbox
  · *`MemorySoakTest` asserts the property underneath "resident memory flat": every
    structure that outlives a message has a ceiling, and an hour of traffic reaches it and
    stops. A JVM heap figure here would measure the garbage collector rather than this
    code; the resident figure is a week-8 measurement on the handset*
  · *It found a real leak. `AlertDelivery.inFlight` had no ceiling — the class documented
    that the caller must call `forget()`, which is true and which the engine does, but
    "bounded provided every caller remembers" is not a bound. It now evicts finished
    alerts itself, **and only finished ones**: `undelivered()` is how an operator learns a
    message they believe went out did not, and dropping an unacknowledged alert to save a
    few hundred bytes would trade the worst failure this interface has against nothing*
- [x] **W7.18** — Message log screen with frame sizes and delivery state
  · *A frame size on every row next to the text it carried. The compression claim is the
    centre of this project, and a number on a slide is an assertion where a number on every
    message is evidence the jury can generate themselves by sending one*
  · *A template row shows the language it was **sent** in where that differs from the one
    it was rendered in — that difference is cross-language delivery working, and it is
    otherwise invisible*
- [x] **W7.19** — Mode and transport screen
  · *Each option states its consequence, not its name: "push-to-talk" means nothing to
    someone choosing for the first time, and "one at a time, longest battery" does*
- [x] **W7.20** — Language screen — own script first, English gloss second, Odia CC-BY-NC
  warning surfaced **in the product**
  · *A speaker of Odia is looking for **ଓଡ଼ିଆ**, not for "Odia" in Latin script. The English
    gloss is second, for the operator setting up someone else's handset*
  · *A non-commercial licence is on the row, at the moment of choosing — not only in a
    document nobody reads*
- [x] **W7.21** — Settings, storage (per-pack licence on the row), and about/licences
  · *The licence sits beside the size because that is where the decision is made: an
    operator freeing space is choosing which pack to delete, and "this one cannot be
    deployed commercially anyway" is exactly what decides it*
- [x] **W7.22** — All six degraded banners with reason strings
  · *Each carries what happened, what the system is doing, and **what the operator should
    do**. The third is the part usually missing from a status message and the only part
    that changes what happens next*
  · *A live region, so TalkBack speaks it on appearance rather than when someone navigates
    to it. Amber for the three that clear themselves, red for the three needing a person —
    an operator who cannot read still learns whether this needs them*
- [~] **W7.23** — Full TalkBack pass; text at 200 % with no truncation
  · *`Spoken` gives every on-screen symbol a spoken form, and imports no Compose so the
    strings are asserted in an ordinary unit test. `●●○` reaches a speech engine as "black
    circle black circle white circle", which is worse than unhelpful — it sounds like a
    description of a picture rather than a confidence score*
  · *Three real defects fixed. The degraded banner used `semantics{}`, which **merges**
    with children, so TalkBack would have announced the banner and then read the icon and
    both lines again as fragments — now `clearAndSetSemantics`, keeping the live region.
    The message log row is one sentence and one swipe, with the replay control as a
    sibling because clearing a subtree makes anything inside it unreachable. And two
    `Modifier.size()` containers holding text, which look identical to `heightIn(min =)`
    at the default font scale and truncate at 200 %*
  · **Blocked:** *the pass itself. Swipe order, focus traps and whether an announcement
    actually interrupts need a device and a person with the screen off*

- [ ] **W7.G** — **GATE:** `scorecard.csv` complete for ten languages; soaks clean
  · *Not met, and the missing half is one thing: **no measurement has been taken**. The
    ten languages are enabled, the fixtures are green, the two soaks that run in memory
    are clean, and every harness that turns audio into a number exists and is tested. What
    is absent is the acoustic model, the evaluation corpus, the handset and the listening
    panels — W7.7 through W7.11, W7.14 and W7.16*

---

## Week 8 — Evidence and rehearsal

**Gate W8: full scorecard; demo run end to end three times without intervention.**

- [x] **W8.1** — Metrics screen: histogram, per-stage medians, CSV export
  · *`MetricsScreen`, on `StageSummary`. An operational product carries a metrics screen
    because the alternative is a slide: this is where a jury watches a figure be produced
    rather than taking last week's measurement on trust*
  · *Three views, because one number cannot carry the argument. The median answers "how
    fast"; the stage table answers "where did the time go", which is what makes a slow run
    actionable — a slow decode is thermal and a slow link is radio, and they have opposite
    remedies; the histogram answers "is it consistent", and it is the only one that shows a
    bimodal run. Empty buckets are kept for exactly that reason*
  · *The seven stages sum to the end-to-end figure by construction, asserted including
    under a 45-second clock offset. A decomposition that loses time somewhere unnamed is
    worse than none, because it invites a reader to trust it*
- [ ] **W8.2** — **Final measurement run on the target handset after a 30-minute soak**,
  release build, battery > 30 % and not charging
  · *[EVALUATION.md §1](EVALUATION.md#1-measurement-conditions). Figures taken outside
  these conditions are not reportable*
  · *The **conditions** are now code rather than a paragraph — `RunConditions` refuses a
    debug build, an emulator, a cold device, a handset on charge or a low battery, and
    reports every unmet condition at once*
  · **Blocked:** *the run. Needs the handset and the models*
- [x] **W8.3** — ≥ 100 utterances per latency figure; **median and p95**, never a single run
  · *`LatencySummary.isReportable` gates the figure, `ReportBundle` refuses to write the
    files, and `MetricsScreen` shows NOT REPORTABLE with the sample count instead of a
    number. A plausible millisecond figure on a device screen is exactly what gets
    photographed and quoted, and by then nobody remembers it came from nine utterances*
- [ ] **W8.4** — Perfetto idle-CPU trace, 10 minutes of silence with VAD active · *< 2 %*
  · **Blocked:** *a handset and Perfetto*
- [ ] **W8.5** — Perfetto active-CPU trace during continuous speech · *< 35 %*
  · **Blocked:** *a handset and Perfetto*
- [ ] **W8.6** — `batterystats` 8 h endurance figure
  · **Blocked:** *a handset and eight hours. Same measurement as W7.16*
- [x] **W8.7** — APK: `arm64-v8a` split, App Bundle · **Done when** installer < 30 MB with
  two languages resident
  · ***26.3 MiB**, from 30.9. The sherpa-onnx AAR ships four native libraries and this
    application loads two: the dynamic string table of `libsherpa-onnx-jni.so` names
    `libonnxruntime.so` and the system libraries and nothing else. The C API library is a
    standalone entry point for native consumers and the C++ one wraps it; excluding both
    removes 4.7 MB. Checkable with one `strings` command rather than assumed*
  · *`app/proguard-rules.pro` **did not exist** and the release build referenced it. R8
    warned and used the default configuration, which does not know that the JNI library
    resolves `com.k2fsa.sherpa.onnx` classes by name, that kotlinx-serialization's
    generated serializers are reached only by synthesised calls, or that
    `RnNoiseSuppressor` declares `external` methods. Each is a crash that appears in the
    release APK on a handset and nowhere else*
  · *Locale resources are deliberately **not** stripped: it would save about a megabyte and
    make every framework accessibility string speak English on a Hindi handset, undoing
    W7.23 for space the project no longer needs*
- [~] **W8.8** — All three CSVs generated, each naming its device and soak duration
  · *`ReportBundle` writes `latency.csv`, `resource.csv` and `scorecard.csv`, each with a
    `#` preamble naming device, build, soak duration and date — a filename says where a
    file came from only until somebody renames it*
  · *An unreportable run produces **no files** rather than files needing a caveat nobody
    will attach. That is the whole behaviour: a bad run leaves nothing behind to be quoted
    three weeks later*
  · **Blocked:** *the contents. The writer is built and tested; the rows need W8.2*
- [x] **W8.9** — Compression ratios computed four ways: 2 182× unauthenticated, 1 600×
  authenticated, 43× vs Opus, 7 385× template
  · *Have all four ready. Quoting only the largest and being asked for the Opus comparison
  is a bad thirty seconds in front of a technical panel*
  · *`CompressionRatios` derives every size from `Frame.HEADER_SIZE`, `Frame.CRC_SIZE` and
    `TransportClass`, so widening the header changes the published figures and fails the
    tests that pin them*
  · *The four use **different frame variants**, and that pairing is where they drift:
    2 182× is 44 B unauthenticated, 1 600× is 60 B with the full tag, 43× is 52 B with the
    truncated tag against Opus at its floor, 7 385× is the 13 B template. Quoting the
    8-byte frame as the authenticated ratio would claim 1 846× — fifteen per cent, on the
    number a jury actually checks*
- [~] **W8.10** — **Security pre-submission audit — all ten items**
  · *[SECURITY.md §8](SECURITY.md#8-pre-submission-audit-checklist)*
  · *Eight of ten pass with re-runnable evidence recorded beside each item. Two defects
    found, both the same kind — a control **written down rather than enforced***
  · *`FLAG_SECURE` was a requirement on a hosting activity that does not exist, so nothing
    applied it. The QR code on that screen **is** the shared key and without the flag it
    lands in the recents thumbnail. `PairingScreen` now sets it itself*
  · *The fuzz bar had drifted across three places: the W2 gate says 10⁵, the checklist says
    10⁶, the harness did 23 000. `FrameFuzzTest` now runs a million across ten shapes and
    asserts four properties, including that the generator itself is not broken*
  · **Open:** *`ENCRYPTED` by default and the UNSECURED banner in the running application
    both wait on a pairing flow that does not exist. The cryptography under them is built
    and tested; ticking either would record an intention*
- [x] **W8.11** — Documentation consistency pass: every number in `docs/` matches a CSV
  · *The `RESCUE-A` and `22 B` drift proves this is needed*
  · *`tools/check_doc_numbers.py` reads the frame constants out of `core-proto`, recomputes
    the ratios and greps `docs/` for figures that disagree. In the local gate list beside
    `check_licences.py`*
  · *It found two real defects, both in DEMO.md §6 — the answers prepared for a technical
    panel, which is the worst place to be wrong. `42×` smaller against Opus, where it is
    43×; and `1 600× authenticated, 52 B on the wire`, where 52 B gives 1 846× and 1 600×
    is the 60 B frame. Exactly the drift the task predicted*
- [ ] **W8.12** — Identical APK checksum on both handsets · *R9 symmetry*
  · **Blocked:** *two handsets*
- [ ] **W8.13** — Spare handset paired, charged, in the bag · *P-03*
  · **Blocked:** *a third handset*
- [ ] **W8.14** — Fallback videos recorded: locked-handset alert, LoRa hop
  · **Blocked:** *handsets and a camera*
- [ ] **W8.15** — Scorecard printed, two copies, in case the projector fails
  · **Blocked:** *W8.2 first — there is nothing to print*
- [ ] **W8.16** — Demo pre-flight checklist dry run
  · *[DEMO.md §4](DEMO.md#4-pre-flight-checklist)*
  · **Blocked:** *two handsets. The checklist itself is current*
- [ ] **W8.17** — **Rehearse the nine-step demo three times without intervention**, logged
  with date, failures and median latency
  · **Blocked:** *handsets, models and a room*
- [ ] **W8.18** — Rehearse the failure recoveries — a recovery performed calmly reads as
  competence, improvised reads as a broken system
  · **Blocked:** *same*
- [x] **W8.19** — Answers prepared for the eight likely questions
  · *[DEMO.md §6](DEMO.md#6-questions-to-have-answers-ready-for)*
  · *Two were wrong and are fixed — see W8.11. Three more now cite something checkable in
    the room rather than a claim: the Opus comparison names the frame it is measured
    against, the flagship answer points at `ReportBundle` refusing to write a file for a
    debug run, and the offline answer gives a grep, a test name and an aeroplane-mode
    demonstration — restated 2026-09-06, because it used to give an `aapt2 dump
    permissions` command that would now print `INTERNET` in front of the jury*
  · *"What is your worst language?" was an instruction to the presenter rather than an
    answer. It is now Odia, with the reason — smallest published corpus, no permissively
    licensed voice — and the two mitigations that are already built*

- [ ] **W8.G** — **GATE:** three clean rehearsals; three CSVs; APK under 30 MB
  · *One of three met: the APK is 26.3 MiB. The CSVs have their writer, their conditions
    and their preamble but no rows, and the rehearsals need two handsets and a model. Every
    task in this week that does not need hardware or a person is closed*

---

## Continuous — every week, not a phase

- [ ] **C.1** — `LICENSES.md` updated in the same commit as any new dependency · *CI-enforced*
- [ ] **C.2** — Behaviour change and its specification document land in the same commit
- [ ] **C.3** — Weekly gate check; every risk owner reports unchanged / mitigating /
  escalating / closed
- [ ] **C.4** — Weekly `scorecard.csv` committed from week 4, so the trend is visible
- [ ] **C.5** — Weekly 30-minute thermal soak from week 6
- [ ] **C.6** — Demo rehearsal weekly from week 6
- [x] **C.7** — Any input that ever caused a failure joins the fuzz corpus permanently
  · *`core-proto/src/test/resources/fuzz-corpus`, checked in as files rather than Kotlin
    literals — that is how one gets added at the moment it is found*
  · *`FrameFuzzTest` finds what a million random inputs find and does not **remember**:
    change the seed and the sequence that once crashed the decoder is no longer produced,
    with the suite still green. This directory is the memory*
  · *Seeded with the eight shapes that historically break framing implementations, asserted
    by name — an empty corpus with a passing test looks exactly like a working one*

---

## Critical path

Only the top line is critical. Everything else branches after the loop closes.

```
 P0.2 handset ──► W1 capture ──► W2 transport ──► W3 LOOP ──► W5 modes ──► W8 rehearsal
                                     ▲               │
   W2 core-proto starts day 1 ───────┘               ├──► W4 models ──► W7 coverage
   (no models, no handset, no Android)               └──► W6 crypto ──► W7 soaks
```

**Parallelism from day 1:** Transport can build `core-proto` immediately — it needs nothing
from anyone. Audio and ASR need only the handset. Evaluation can build the WER scorer and
noise mixer against reference audio before any model exists.

## If time is lost, cut in this order

1. Matcha-TTS benchmarking
2. `AUDIO_FB` Opus fallback
3. Multi-hop relay, `POSITION`, store-and-forward — **not in the problem statement**
4. **Encryption, replay window, pairing** — ISRO asks for none of it
5. Wi-Fi transport
6. Languages beyond five — five well beats ten badly
7. LoRa hop

**Never cut:** the loop, the alert path, ten-language coverage, the scorecard.

## Definition of done, for any task

1. The *Done when* condition is observably true
2. Tests exist and pass in CI
3. The specification document matches the behaviour
4. It has been run on the **target handset**, not an emulator, if it touches audio,
   Bluetooth or performance
5. Any number it produces is in a CSV, not a claim
