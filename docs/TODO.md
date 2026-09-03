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

**Progress: 23 of 209 complete, 1 in progress.** `core-proto` compiles and has **35 passing tests at 94.7 % line coverage** — CRC, frame codec and script packer. — the build scaffold. Everything else is unstarted.

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
- [ ] **P0.5** — Order LoRa hardware: 2 × ESP32 + SX1276/SX1278, 865.5 MHz, ~₹1 500
  · **Order now — two-week lead time will otherwise eat the differentiator**
  · **Blocks** W7.12
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

- [ ] **W1.11** — `app` module, `MainActivity`, Compose scaffold
- [ ] **W1.12** — Monochrome high-contrast theme tokens
  · *[WIREFRAMES.md §1](WIREFRAMES.md#1-layout-system) — 8 dp grid, 64 dp minimum target*
- [ ] **W1.13** — `EngineService` foreground service with persistent notification
  · *[ARCHITECTURE.md §3](ARCHITECTURE.md#3-threading-and-lifecycle)*
- [ ] **W1.14** — Runtime permissions: `RECORD_AUDIO`, `BLUETOOTH_CONNECT`,
  `BLUETOOTH_SCAN` with `neverForLocation`
- [x] **W1.15** — **Manifest guard in CI: no `INTERNET`, no location permission**
  · *Implemented as a CI job; the manifest is written and carries neither*
  · *Constraint C2. This is the strongest form of the offline claim — platform-enforced,
  not asserted*
  · **Done when** CI fails if either permission appears
- [ ] **W1.16** — State machine: `INITIALISING → READY → LISTENING → …`, plus `DEGRADED`
  with a reason string
  · *[ARCHITECTURE.md §4](ARCHITECTURE.md#4-state-model)*

### Audio capture

- [ ] **W1.17** — `AudioRecord` wrapper, 16 kHz mono, 20 ms hops (320 samples)
- [ ] **W1.18** — Pre-allocated ring buffer retaining 250 ms pre-trigger (8 000 samples, 16 kB)
  · *[ASR.md §2](ASR.md#2-endpointing) leading pad*
- [ ] **W1.19** — Capture thread at `THREAD_PRIORITY_URGENT_AUDIO`
  · **Done when** an allocation-tracking test shows **zero allocations** in the capture loop
  · *No logging, no string formatting. A correctness constraint, not style*
- [ ] **W1.20** — Tier 0 energy gate: EMA noise floor τ = 3 s updated only on non-speech
  frames, +9 dB trigger, 3-frame open / 10-frame close hysteresis
  · *[ASR.md §1](ASR.md#1-three-tier-voice-activity-detection)*
- [ ] **W1.21** — Bounded queue capture → inference, depth 100 (2 s), drop-oldest with a
  counter, never blocks capture
  · *[ARCHITECTURE.md §3](ARCHITECTURE.md#3-threading-and-lifecycle) queue policy*

### Recognition

- [ ] **W1.22** — Bundle `silero_vad.onnx` (1.8 MB) in base assets
- [ ] **W1.23** — sherpa-onnx AAR dependency; verify it loads on the target handset
- [ ] **W1.24** — Tier 1 Silero VAD: 512-sample window, threshold 0.5, min speech 250 ms,
  min silence 100 ms. Runs only when tier 0 has opened
- [ ] **W1.25** — `tools/fetch_models.py` — download, SHA-256 verify, atomic install
- [ ] **W1.26** — Vosk Hindi small model fetched and loading
  · *Week-one prototype. **Schedule insurance, not a compromise** — risk T-07*
- [ ] **W1.27** — **`OfflineRecognizer` binding** (NeMo-CTC), 4 threads while decoding and
  2 at idle, greedy search
  · *[ASR.md §8](ASR.md#8-reference-binding). **Not `OnlineRecognizer`** — no streaming
  Indic model exists, risk T-16. Vosk in W1.26 is streaming and may still be driven that
  way for the week-1 prototype only*
- [ ] **W1.28** — Endpointing state machine `IDLE / LISTENING / FINALISING`
  · *[ASR.md §2](ASR.md#2-endpointing) — 400 ms phone, 150 ms PTT, 8 s max, 300 ms min*
- [ ] **W1.29** — Emit `Hypothesis(text, isFinal, confidence, tEndpoint)`
  · *An offline model yields no partials, so `isFinal` is always true until W3.13 lands
  sliding-window decoding. Do not design the UI around live partial text*
- [ ] **W1.30** — Models loaded at service start and held resident; transmit disabled until
  `READY` with a visible indicator
  · *Risk T-11 — the cold-start cost must never be paid on a key press*

### Interface

- [ ] **W1.31** — Operating screen shell, bands A–F
  · *[WIREFRAMES.md §4](WIREFRAMES.md#4-operating--push-to-talk-idle)*
- [ ] **W1.32** — Recognised text in band E
- [ ] **W1.33** — Stage timestamps `tMic`, `tVad`, `tFirstPartial`, `tEndpoint`, `tFinal`
  · *[ASR.md §9](ASR.md#9-instrumentation)*
- [ ] **W1.34** — Latency strip band F showing real numbers
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
- [ ] **W2.12** — `templates.json` loader, canonical serialisation, `profileDigest`
- [ ] **W2.13** — Fuzzy match: normalised-token Levenshtein ≥ 0.85 **and** high recogniser
  confidence. Both required
  · *[PROTOCOL.md §5.3](PROTOCOL.md#53-matching-rule)*

### Cryptography

- [ ] **W2.14** — AES-256-GCM seal / open, **AAD = the entire 10-byte header**
- [ ] **W2.15** — Deterministic nonce `EPOCH ‖ SRC ‖ SEQ ‖ 0x00×5`
  · *[PROTOCOL.md §6.2](PROTOCOL.md#62-deterministic-nonce)*
- [ ] **W2.16** — `EPOCH` persistence: increments on every `SEQ` wrap **and every service
  start**
  · **Correctness-critical. Nonce reuse destroys GCM completely — risk S-07**
- [ ] **W2.17** — Nonce-uniqueness test over 10⁷ simulated frames including restarts and wraps
- [ ] **W2.18** — Tag length by transport class: 16 B on BT/Wi-Fi, 8 B on serial
- [ ] **W2.19** — Auth-failure rate limit: > 16 from one `SRC` in 60 s → `DEGRADED`
  · *Required to make an 8-byte tag defensible*
- [ ] **W2.20** — Replay window: 64-entry sliding, per `SRC`, keyed `(EPOCH, SEQ)`
- [ ] **W2.21** — Mutation test: **every single-byte change to a valid frame fails
  verification**

### Robustness

- [ ] **W2.22** — `readFully` loop and `0xA1` resynchronisation
  · *[PROTOCOL.md §13](PROTOCOL.md#13-stream-framing). Risk T-08 — the most common defect
  in this class of project*
- [ ] **W2.23** — Fuzz harness: truncated at every cut point, byte-interleaved frames,
  1/2/8 bit flips, `LEN` of 0/1/1023/1024/1025/65535, megabytes with no sentinel
  · **Done when** 10⁵ inputs produce no uncaught exception, no unbounded allocation, no
  socket closure, and correct recovery on the next valid frame
- [ ] **W2.24** — All 12 items of the conformance checklist as named tests
  · *[PROTOCOL.md §14](PROTOCOL.md#14-conformance-checklist)*

### Link layer

- [ ] **W2.25** — `Link` interface, `LinkState`, `LinkMetrics`
  · *[TRANSPORT.md §1](TRANSPORT.md#1-the-abstraction)*
- [ ] **W2.26** — `LoopbackLink` in-process, for integration tests with no radio
- [ ] **W2.27** — `RfcommLink` — **`cancelDiscovery()` before `connect()`**, or throughput
  collapses by an order of magnitude
- [ ] **W2.28** — Reconnection: exponential backoff 1 s → 30 s with jitter, reset on success
- [ ] **W2.29** — `HEARTBEAT` every 2 s; three misses mark the peer offline
- [ ] **W2.30** — Outbox in Room: store-and-forward, capped 500 frames / 24 h, flush in
  order on reconnect
- [ ] **W2.31** — Temporary debug text field to send typed text — **delete in W3.12**
- [ ] **W2.32** — Byte counter in band F

- [ ] **W2.G** — **GATE:** typed text on A appears on B over RFCOMM; CI green; fuzz clean

---

## Week 3 — Close the loop · THE HINGE

> **This is the week that decides the project.** A working loop with mediocre models can be
> improved under any time pressure. Excellent models with no loop cannot be demonstrated.
> If any week-4 task threatens this gate, the week-4 task is cut. Risk P-01.

**Gate W3: speech spoken on device A is heard on device B.**

- [ ] **W3.1** — espeak-ng data (~10 MB) in base assets; Piper Hindi voice fetched
- [ ] **W3.2** — `OfflineTts` binding, 2 threads
  · *[TTS.md §6](TTS.md#6-reference-binding)*
- [ ] **W3.3** — Normalisation engine + `normalise.json` loader
  · *[TTS.md §1](TTS.md#1-text-normalisation)*
- [ ] **W3.4** — Hindi rules: `time-24h`, `unit-km`, `callsign`, `long-num`, `cardinal`
  · *The quantity-versus-identifier rule is where the perceived quality lives*
- [ ] **W3.5** — Hindi normalisation fixtures, ≥ 60 cases, asserted in CI at **100 %**
- [ ] **W3.6** — Clause splitter: punctuation → conjunctions → hard split; min 8 phonemes,
  max 12 words
- [ ] **W3.7** — Streaming playout: `AudioTrack` `WRITE_BLOCKING`, playback starts on chunk 1
  · **Done when** time-to-first-audio ≤ 250 ms, measured
  · *Underrun policy: synthesise the remainder as one block. **Never a gap mid-sentence***
- [ ] **W3.8** — Synthesis thread at `THREAD_PRIORITY_AUDIO`
- [ ] **W3.9** — Wire the receive path end to end: `Link → decode → CRC → AEAD → replay
  check → unpack/template → normalise → phonemise → TTS → AudioTrack`
- [ ] **W3.10** — Clock sync: four `HEARTBEAT` round trips, median offset, so end-to-end
  latency is measured rather than stopwatched
  · *[EVALUATION.md §4](EVALUATION.md#4-latency--20--of-the-mark)*
- [ ] **W3.11** — `latency.csv` writer, every stage boundary, every utterance
- [ ] **W3.12** — Delete the debug text field from W2.31
- [ ] **W3.13** — **Sliding-window decoding.** Decode 1.5 s windows with 0.4 s overlap
  *while the speaker is still talking*, so only the final partial window is decoded after
  the endpoint; stitch the windows into one hypothesis
  · *[ASR.md §3.5](ASR.md#35-decoding-an-offline-model-without-paying-for-it-at-the-end)*
  · **Done when** post-endpoint decode is 250–450 ms rather than ~900 ms, measured
  · *Risk T-16. Without this the end-to-end figure is ~1330 ms and the latency criterion
  is lost. Costs ~1.6× compute, affordable because it runs only during speech*

- [ ] **W3.G** — **GATE:** speech in on A, speech out on B. Baseline end-to-end latency
  recorded in `latency.csv`. Video. **The project is now de-risked**

---

## Week 4 — Real models

**Gate W4: five languages recognised; `bench` produces a `scorecard.csv`.**

- [ ] **W4.1** — `tools/export_indicconformer.py` — NeMo → ONNX
  · *Risk T-07: if this fails, Vosk from W1.26 keeps the schedule intact. Three weeks of
  slack sit behind it*
- [ ] **W4.2** — `onnxruntime.quantization.preprocess` + `quantize_dynamic` int8,
  **encoder only** — quantising decoder and joiner hurts accuracy for no size gain
- [ ] **W4.3** — `tools/verify_quantisation.py`
  · **Done when** WER delta vs float32 is < 1.5 % relative, **per language**
  · *Mandatory. The whole efficiency argument rests on this claim; it must be
  re-established per language, not assumed*
- [ ] **W4.4** — `models/manifest.json` schema: **one shared acoustic-model entry plus a
  per-language vocabulary and voice entry** — the acoustic model is not per language
  · *[MODELS.md §1](MODELS.md#1-delivery) and §3. Risk T-17*
- [ ] **W4.5** — `core-models`: manifest loader, SHA-256 verify, **atomic install** (temp
  file then rename — a partial pack must never be loadable), resumable download
  · *The ~120 MB shared model download must resume; it will be interrupted on a weak
  connection far more often than a 60 MB voice*
- [ ] **W4.6** — Language switch: unload outgoing, load incoming, blocked while floor held
- [ ] **W4.7** — Pack licence is a **load-time precondition** — a pack with no licence field
  fails verification and does not load
- [ ] **W4.8** — `bench` WER scorer: NFC, punctuation strip, case fold, whitespace collapse,
  numerals compared in spoken-word form
  · **The single source of truth. No figure is ever computed by hand**
- [ ] **W4.9** — Noise mixer: crowd / wind / engine / siren at +20, +10, +5 dB SNR, **fixed
  seed** so results reproduce
- [ ] **W4.10** — IndicSUPERB / Kathbath subsets downloaded for five languages
- [ ] **W4.11** — `scorecard.csv` writer with the full schema, recording device and build
  · *[EVALUATION.md §6](EVALUATION.md#6-instrumentation)*
- [ ] **W4.12** — **Confirm the Piper Odia voices exist** (Debjani, Manas) in the official
  `rhasspy/piper-voices` repository — **do this in week 1, it takes ten minutes**
  · *Risk T-05, downgraded to Low by verification. If confirmed, drop Meta MMS entirely and
  remove its CC-BY-NC disclosure from `LICENSES.md`. Only if it is absent does the old plan
  apply: MMS as a disclosed stopgap, then a Coqui VITS voice on IIT Madras data (~2 GPU-days)*
- [ ] **W4.13** — `alert-lexicon.txt` per language: ~300 domain terms
- [ ] **W4.14** — **Negation terms weighted high** — "not", "do not", "नहीं"
  · *Risk S-03. "Do not evacuate" becoming "now evacuate" is the most dangerous single
  failure this system can produce*
- [ ] **W4.15** — Roster display names injected into biasing at runtime
- [ ] **W4.16** — Confidence thresholds calibrated per language, stored in the manifest —
  **not hard-coded**. A threshold right for Hindi is wrong for Odia

- [ ] **W4.G** — **GATE:** `scorecard.csv` exists with WER at four SNRs for five languages

---

## Week 5 — Modes and alerts

**Gate W5: an alert wakes a locked, silenced handset and announces at full volume.**

### Push-to-talk and phone mode

- [ ] **W5.1** — Floor state machine: free / held by me / held by peer / contended
- [ ] **W5.2** — `PTT_CTL` seize and release frames; channel-busy indicator
- [ ] **W5.3** — Randomised backoff on contention · *Risk S-05*
- [ ] **W5.4** — **Volume-down hardware key binding, working with the screen off**
  · *Rule 4. Operators wear gloves and rarely look at the screen*
- [ ] **W5.5** — Half duplex: speaker muted while transmitting; 150 ms endpoint on release
- [ ] **W5.6** — Full duplex: continuous VAD-gated streaming both directions
- [ ] **W5.7** — Barge-in: duck to −18 dB within 100 ms, stop at chunk end
- [ ] **W5.8** — A press while the floor is held gives a **haptic refusal, never a dialog**

### Alert delivery — all six steps

- [ ] **W5.9** — `AudioAttributes.USAGE_ALARM` + `CONTENT_TYPE_SONIFICATION`
- [ ] **W5.10** — `setStreamVolume(STREAM_ALARM, max, 0)` before playback
- [ ] **W5.11** — **Restore the prior volume afterwards**
  · *Easy to forget, and forgetting leaves the handset permanently at max alarm volume —
  a defect certain to be found during a demonstration*
- [ ] **W5.12** — `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, **loss callbacks deliberately
  ignored** — this is precisely the non-interruptible requirement
- [ ] **W5.13** — `PARTIAL_WAKE_LOCK` + full-screen-intent notification
- [ ] **W5.14** — Vibration pattern, full-screen visual, message repeated twice
- [ ] **W5.15** — `ALERT` pre-empts the transmit queue; ack + retry 3 × 300 ms
- [ ] **W5.16** — Delivered on the **first** ack; UI shows `3 of 6 units`

### Alert screens

- [ ] **W5.17** — Alert compose: six template buttons + hold-to-speak
  · *[WIREFRAMES.md §10](WIREFRAMES.md#10-alert-compose). Icon **plus** word, never a word alone*
- [ ] **W5.18** — Confirm-before-send: text spoken aloud on open, **RETAKE and SEND exactly
  equal in size**
  · *A confirmation that makes the safe option smaller is not a confirmation*
- [ ] **W5.19** — Incoming alert full screen, **no swipe-to-dismiss** — a swipe is something
  a pocket can do
- [ ] **W5.20** — "Test alert on this device" in settings
  · *Vendor audio policy varies; also lets demo step 5 be rehearsed solo*

### Instrumented tests — on the target handset, not an emulator

- [ ] **W5.21** — Alert on locked handset · **Done when** audio at max, screen wakes, vibration fires
- [ ] **W5.22** — Alert with ringer silenced
- [ ] **W5.23** — Alert in Do Not Disturb
- [ ] **W5.24** — Alert during music playback
- [ ] **W5.25** — Alert during a phone call — documented behaviour, queued and repeated after
- [ ] **W5.26** — Volume restoration verified
- [ ] **W5.27** — Focus loss ignored during alert playback
- [ ] **W5.28** — Mic pre-empted by a call → `DEGRADED` with reason, auto-recovery · *T-12*

- [ ] **W5.G** — **GATE:** video of a locked, silenced, DND handset announcing at full volume

---

## Week 6 — Latency, reach, cryptography

**Gate W6: encrypted frames, replay rejected, three transports pass one suite, first soak.**

- [ ] **W6.1** — Chunked synthesis tuned; time-to-first-audio measured and recorded
- [ ] **W6.2** — Stabilised partials; `PARTIAL` flag; receiver may begin early synthesis
  · *Depends on W3.13 — partials come from completed decode windows, not from the model*
- [ ] **W6.3** — Adaptive endpointing
- [ ] **W6.4** — `BleLink`: GATT, MTU negotiated to 247, ~244 usable
- [ ] **W6.5** — Fragmentation for BLE and serial: chunks of `mtu - 12`, 2 s reassembly
  timeout, **AEAD verified after reassembly**
- [ ] **W6.6** — `WifiLink`: hosted network, TCP 38173, UDP discovery 38174
  · *Hosted network is primary. `WifiP2pManager` is optional — risk T-09*
- [ ] **W6.7** — `SerialLink`: Bluetooth SPP to the radio module
- [ ] **W6.8** — **Same integration suite runs green against all four transports**
- [ ] **W6.9** — Enable AES-GCM on every transport; tag length by transport class
- [ ] **W6.10** — `EPOCH` survives force-stop and reboot
  · **Done when** a test kills the app, reboots, and shows `EPOCH` incremented and no
  replay rejection of new frames
- [ ] **W6.11** — Pairing screen: QR generate and scan, `FLAG_SECURE`, key straight to
  Android Keystore, decoded string never written to disk
  · *[WIREFRAMES.md §3](WIREFRAMES.md#3-pairing)*
- [ ] **W6.12** — `KEYID` derived as `SHA-256(key)[0]`, never configured, never shown
- [ ] **W6.13** — Relay: `TTL` decrement, 512-entry LRU seen-set on `(SRC, EPOCH, SEQ)`,
  0–50 ms random delay · *Risk T-10*
- [ ] **W6.14** — UNSECURED banner: red, permanent, undismissable, no silent path
- [ ] **W6.15** — `TEMPLATE MISMATCH` warning; template sending disabled on digest mismatch
  · *Risk S-06 — a safety defect, not a compatibility inconvenience*
- [ ] **W6.16** — **First 30-minute thermal soak**; sustained RTF recorded
  · *Risk T-03. **Done when** RTF after soak < 2× RTF cold. Finding throttling in week 8
  is finding it too late*
- [ ] **W6.17** — Reduce thread count under thermal pressure
- [ ] **W6.18** — `resource.csv` writer: CPU, RSS, battery, thermal state

- [ ] **W6.G** — **GATE:** replay rejected, mutation test green, four transports pass, soak
  trace recorded

---

## Week 7 — Coverage and hardening

**Gate W7: ten languages, normalisation 100 %, relay soak clean, field test recorded.**

- [ ] **W7.1** — Remaining five languages enabled — **vocabulary and voice per language;
  the acoustic model is already present** — verified and in the manifest
- [ ] **W7.2** — `normalise.json` for all ten languages
- [ ] **W7.3** — Normalisation fixtures ≥ 60 cases × 10 languages, **100 % in CI**
  · *Adding a rule without its fixtures is a rejected review*
- [ ] **W7.4** — `templates.json` in all ten languages, one deployment profile
- [ ] **W7.5** — Deployment gazetteer loading (~200 place names, sectors, callsigns)
- [ ] **W7.6** — RNNoise integration, recognition path only, **never** audio the user hears
- [ ] **W7.7** — Per-language decision on noise suppression **from measurement, not
  preference** — report WER with it on and off at each SNR
- [ ] **W7.8** — Full WER run: 10 languages × 4 SNRs
- [ ] **W7.9** — CTER with and without biasing, in the same table
  · *The delta is directly attributable to an engineering decision the team made*
- [ ] **W7.10** — MOS listening panel executed, 15 speakers per language
  · *Recruited in P0.10. **Report the actual panel size** — a MOS without one is not a
  measurement*
- [ ] **W7.11** — Intelligibility test: native listeners transcribe synthesised output
- [ ] **W7.12** — LoRa firmware, ~200 lines: SPP in → LoRa out, LoRa in → SPP out.
  **The firmware must not understand the protocol — it is a wire**
- [ ] **W7.13** — LoRa bench test across a room, then at 2 km
- [ ] **W7.14** — Field test at range: Bluetooth 30 m, Wi-Fi 150 m
- [ ] **W7.15** — Four-device relay soak, 1 h · **Done when** every message arrives exactly
  once and the seen-set stays bounded
- [ ] **W7.16** — Eight-hour endurance soak: BLE, screen off, listening · *> 8 h drain*
- [ ] **W7.17** — Memory soak 1 h: resident memory flat, no leak in ring or outbox
- [ ] **W7.18** — Message log screen with frame sizes and delivery state
- [ ] **W7.19** — Mode and transport screen
- [ ] **W7.20** — Language screen — own script first, English gloss second, Odia CC-BY-NC
  warning surfaced **in the product**
- [ ] **W7.21** — Settings, storage (per-pack licence on the row), and about/licences
- [ ] **W7.22** — All six degraded banners with reason strings
- [ ] **W7.23** — Full TalkBack pass; text at 200 % with no truncation

- [ ] **W7.G** — **GATE:** `scorecard.csv` complete for ten languages; soaks clean

---

## Week 8 — Evidence and rehearsal

**Gate W8: full scorecard; demo run end to end three times without intervention.**

- [ ] **W8.1** — Metrics screen: histogram, per-stage medians, CSV export
- [ ] **W8.2** — **Final measurement run on the target handset after a 30-minute soak**,
  release build, battery > 30 % and not charging
  · *[EVALUATION.md §1](EVALUATION.md#1-measurement-conditions). Figures taken outside
  these conditions are not reportable*
- [ ] **W8.3** — ≥ 100 utterances per latency figure; **median and p95**, never a single run
- [ ] **W8.4** — Perfetto idle-CPU trace, 10 minutes of silence with VAD active · *< 2 %*
- [ ] **W8.5** — Perfetto active-CPU trace during continuous speech · *< 35 %*
- [ ] **W8.6** — `batterystats` 8 h endurance figure
- [ ] **W8.7** — APK: `arm64-v8a` split, App Bundle · **Done when** installer < 30 MB with
  two languages resident
- [ ] **W8.8** — All three CSVs generated, each naming its device and soak duration
- [ ] **W8.9** — Compression ratios computed four ways: 2 182× unauthenticated, 1 600×
  authenticated, 43× vs Opus, 7 385× template
  · *Have all four ready. Quoting only the largest and being asked for the Opus comparison
  is a bad thirty seconds in front of a technical panel*
- [ ] **W8.10** — **Security pre-submission audit — all ten items**
  · *[SECURITY.md §8](SECURITY.md#8-pre-submission-audit-checklist)*
- [ ] **W8.11** — Documentation consistency pass: every number in `docs/` matches a CSV
  · *The `RESCUE-A` and `22 B` drift proves this is needed*
- [ ] **W8.12** — Identical APK checksum on both handsets · *R9 symmetry*
- [ ] **W8.13** — Spare handset paired, charged, in the bag · *P-03*
- [ ] **W8.14** — Fallback videos recorded: locked-handset alert, LoRa hop
- [ ] **W8.15** — Scorecard printed, two copies, in case the projector fails
- [ ] **W8.16** — Demo pre-flight checklist dry run
  · *[DEMO.md §4](DEMO.md#4-pre-flight-checklist)*
- [ ] **W8.17** — **Rehearse the nine-step demo three times without intervention**, logged
  with date, failures and median latency
- [ ] **W8.18** — Rehearse the failure recoveries — a recovery performed calmly reads as
  competence, improvised reads as a broken system
- [ ] **W8.19** — Answers prepared for the eight likely questions
  · *[DEMO.md §6](DEMO.md#6-questions-to-have-answers-ready-for)*

- [ ] **W8.G** — **GATE:** three clean rehearsals; three CSVs; APK under 30 MB

---

## Continuous — every week, not a phase

- [ ] **C.1** — `LICENSES.md` updated in the same commit as any new dependency · *CI-enforced*
- [ ] **C.2** — Behaviour change and its specification document land in the same commit
- [ ] **C.3** — Weekly gate check; every risk owner reports unchanged / mitigating /
  escalating / closed
- [ ] **C.4** — Weekly `scorecard.csv` committed from week 4, so the trend is visible
- [ ] **C.5** — Weekly 30-minute thermal soak from week 6
- [ ] **C.6** — Demo rehearsal weekly from week 6
- [ ] **C.7** — Any input that ever caused a failure joins the fuzz corpus permanently

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
