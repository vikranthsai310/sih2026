# Requirements — the product requirements document

Problem Statement 26173 written as testable requirements. Every requirement has an
identifier, an owning module, an acceptance test, and a verification method. Nothing in
this document is aspirational — if a row cannot be demonstrated, the requirement is not
met.

**This is the PRD.** It is deliberately one document rather than a separate product brief
restating it: two documents describing the same requirements drift apart, and a
requirement that disagrees with itself is worse than one written only once. What a product
brief would normally hold lives here — the problem (§0), who it is for (§6), what it must
do (§1), what it must not (§5), the bounds it works within (§2, §2a), how success is judged
(§3), and what is still unresolved (§7).

## 0. The problem statement, verbatim

Everything below in §1 is our *restatement*. This section is ISRO's own text, reproduced
without edit, so that any claim in this repository can be audited against the source.

| | |
| --- | --- |
| PS number | `SIH26173` |
| Title | iTantra -Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links |
| Organisation | Indian Space Research Organisation (ISRO) |
| Department | Department of Space / Indian Space Research Organisation |
| Category | Software |
| Theme | Smart Automation |
| Idea submission deadline | **20 September 2026** |

> **Background** As vocal audio information is very data intensive making it difficult to
> transmit through low data rate links. In alert and distress based scenarios Transmitting
> Audio information is critical instead of written message as it will be more inclusive and
> will cater to everyone even if they are literate or not.
>
> **Description** Build an Android App with lightweight, highly accurate STT and TTS models
> for 10 Indian Languages (Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu,
> Odia, Bengali, English) that runs locally on a low-power device. The system's STT module
> when activated after detecting pauses and stoppages should form the sentences detected and
> must instantly and efficiently stream the data through wifi/Bluetooth connected embedded
> device or another phone with same application with minimal latency. The systems TTS module
> when activated after receiving the Text data should convert it into intelligible speech
> which will be played as a voice note and alert type messages will be announced at highest
> volume non-interruptible. To verify the complete loop two phones with same app one in TTS
> mode and another in STT mode can be connected via wifi or Bluetooth and it should work
> like a walkie talkie using push to talk feature, if turned off it should work like a phone.
>
> **Key Metrics for Evaluation**
> - Efficiency: Model size, App size (RAM/Flash footprint) and CPU usage during idle
>   listening. (20%)
> - Accuracy: Low Word Error Rate for STT and High human legibility and flow for TTS. (40%)
> - Latency: The Time delay between the Words said and STT completion, Time delay between
>   the text received and audio processed and played for TTS along with RTF (Real Time
>   Factor). The time delta between the sentence said and the same sentence started as audio
>   in another phone. (20%)
>
> **Software & Framework Restrictions**
> - Open-Source Only: The use of proprietary, closed-source, or commercial voice-activation
>   SDKs is strictly prohibited.
> - Allowed Frameworks: Teams must build their pipelines using open-source machine learning
>   and TinyML frameworks. Recommended tools include TensorFlow Lite for Microcontrollers,
>   PyTorch Mobile or similar.
> - Fully Offline Working: Model or pipeline should work fully offline only and no internet
>   hosted API based solutions are expected and encouraged for the STT or TTS.
>
> **Expected Solution** Teams are expected to deliver a robust, deployable system
> architecture. A successful submission must strictly satisfy the following technical
> boundaries:
> - Hardware & Runtime Environment: The Android application must run smoothly on Low and Mid
>   rage mobile phones.

### What the statement does not say

These words appear **nowhere** in the text above: *security, encryption, authentication,
privacy, tamper, integrity, group, channel, addressing, relay, multi-hop*.

Everything in this repository concerning cryptography, pairing, addressing and relay is
therefore **self-imposed scope**. It is defensible under "robust, deployable system
architecture", and the reasoning is set out in
[SECURITY.md §0](SECURITY.md#0-this-is-not-a-stated-requirement) — but it is not a
requirement, it is worth no marks directly, and it is on the cut list in
[ROADMAP.md §4](ROADMAP.md#4-critical-path).

**"iTantra" is ISRO's title for the problem statement, not a product name we invented.**
Use it, but do not present it as our branding.

## 1. Functional requirements

### R1 — Lightweight, accurate STT and TTS for ten Indian languages

Quantised on-device acoustic and synthesis models per language, with a measured word error
rate and a reported footprint per language.

- **Owner:** `core-asr`, `core-tts`, `core-models`
- **Acceptance:** all ten languages present in the pack manifest; the shared acoustic model ≤ 130 MB int8 and each voice ≤ 70 MB; WER
  reported per language at four SNRs; TTS mean opinion score reported per language.
- **Verification:** `bench` produces `scorecard.csv`. See [EVALUATION.md](EVALUATION.md).
- **Languages:** English, Hindi, Bengali, Marathi, Telugu, Tamil, Gujarati, Kannada,
  Malayalam, Odia. Language indices are normative and fixed in
  [PROTOCOL.md §3](PROTOCOL.md#3-language-indices).

### R2 — Runs locally on a low-power device

All inference on-device. No server, no network call at runtime.

- **Owner:** `app`, `core-models`
- **Acceptance:** the full loop completes with the handset in aeroplane mode and all
  radios except the chosen transport disabled; resident memory < 350 MB with one language
  loaded; idle CPU < 2 %.
- **Verification:** aeroplane-mode run recorded in the demo; Perfetto trace over ten
  minutes; Android Studio memory profiler under sustained load.
- **Negative test:** the application declares no `INTERNET` permission in its manifest.
  This is the strongest possible form of the claim — it is enforced by the platform rather
  than asserted by us, and it should be shown to the jury.

### R3 — STT activated after detecting pauses and stoppages

Voice activity detection plus an endpointing state machine that decides when an utterance
has ended.

- **Owner:** `core-asr`
- **Acceptance:** recognition is not running during silence (verifiable in a trace as
  absence of tier-2 work); a final hypothesis is emitted 400 ± 50 ms after speech stops in
  phone mode, and 150 ± 30 ms after key release in PTT mode.
- **Verification:** `latency.csv` stage boundaries; trace inspection.
- **Specification:** [ASR.md §2](ASR.md#2-endpointing).

### R4 — Forms the sentences detected

Segment-level finalisation with punctuation and normalisation — not a stream of loose
words.

- **Owner:** `core-asr`, `core-tts`
- **Acceptance:** output of a finalised segment is a single sentence-shaped string;
  numbers, units, times and callsigns are normalised before synthesis with 100 %
  correctness against the regression suite.
- **Verification:** the normalisation regression suite, per language. See
  [TTS.md §1](TTS.md#1-text-normalisation).

### R5 — Instantly and efficiently streams the data

A compact binary wire protocol with minimal framing overhead.

- **Owner:** `core-proto`
- **Acceptance:** header ≤ 12 B including CRC; a typical Hindi sentence transmits in
  ≤ 60 B authenticated on Bluetooth and ≤ 52 B authenticated on a low-rate link; framing
  and transmit stage ≤ 60 ms.
- **Verification:** the on-screen byte counter, and `latency.csv`.
- **Specification:** [PROTOCOL.md](PROTOCOL.md).

### R6 — Through a Wi-Fi/Bluetooth connected embedded device or another phone

A transport abstraction with three implementations, all phone to phone. The problem
statement offers "embedded device **or** another phone"; we deliver the second.

- **Owner:** `core-link`
- **Acceptance:** three implementations satisfy the `Link` interface — Bluetooth Classic,
  Bluetooth LE and Wi-Fi — and switching transport is a settings toggle with no change
  above `core-link`.
- **Verification:** the same integration suite runs green against all four transports.
- **Specification:** [TRANSPORT.md](TRANSPORT.md).

### R7 — TTS converts received text into intelligible speech, played as a voice note

Streaming synthesis with playback beginning before synthesis completes.

- **Owner:** `core-tts`, `core-audio`
- **Acceptance:** time from frame receipt to first audible sample ≤ 250 ms; intelligibility
  > 95 % by native-listener transcription; the whole utterance plays without a gap.
- **Verification:** `latency.csv`; the listening panel described in
  [EVALUATION.md §3](EVALUATION.md#3-synthesis-quality).

### R8 — Alert messages announced at highest volume, non-interruptible

- **Owner:** `core-audio`, `app`
- **Acceptance:** an `ALERT` frame received by a handset that is locked, silenced, and in
  Do Not Disturb produces full-volume audio, vibration, and a full-screen visual; audio
  focus loss does not stop playback; the prior volume is restored afterwards.
- **Verification:** the six-step sequence in [UX.md §3](UX.md#3-alert-delivery) executed
  on the target handset, on video.

### R9 — Two phones, one TTS one STT, verify the complete loop

- **Owner:** `app`
- **Acceptance:** the application is symmetric — every device runs both halves and role is
  a runtime mode, not a build variant. A single APK is installed on both handsets.
- **Verification:** identical APK checksum on both devices during the demonstration.

### R10 — Works like a walkie-talkie using push-to-talk

- **Owner:** `app`, `core-audio`
- **Acceptance:** half-duplex operation with floor control; capture live only while the
  key is held; the speaker muted while transmitting; a channel-busy indicator; the
  hardware volume-down key works with the screen off.
- **Verification:** two-device manual script in [TESTING.md](TESTING.md).

### R11 — With push-to-talk off it works like a phone

- **Owner:** `app`, `core-asr`, `core-audio`
- **Acceptance:** full-duplex operation, continuous VAD-gated streaming in both
  directions, with barge-in ducking of local playback when the local user begins speaking.
- **Verification:** two-device manual script; barge-in latency measured.

## 2. Constraints

These are pass/fail. Violating any one of them invalidates the submission regardless of
how the system performs.

| ID | Constraint | Consequence for the design | How we prove it |
| --- | --- | --- | --- |
| C1 | **Open source only** — ISRO prohibits "proprietary, closed-source, or commercial **voice-activation** SDKs" | Rules out Google Speech Services, Azure Speech, Picovoice. **We apply this more broadly than required**, also excluding Google Nearby Connections for transport in favour of raw platform sockets — a self-imposed tightening, not ISRO's words, adopted because a single blanket rule is easier to audit than a boundary argument about what counts as voice-activation. | [LICENSES.md](../LICENSES.md) enumerates every dependency with its licence and role. |
| C2 | **Fully offline at runtime** | No network call, ever. Language packs may be fetched once during setup; the running system never touches a network. | No `INTERNET` permission in the shipped manifest; demonstration conducted in aeroplane mode. |
| C3 | **Approved frameworks** | TensorFlow Lite, PyTorch Mobile, ONNX Runtime or equivalent. Our runtime is ONNX Runtime via sherpa-onnx — permissively licensed, ARM-optimised, explicitly in scope. | Dependency list; LiteRT and ExecuTorch evaluated as alternates and recorded. |
| C4 | **Low and mid-range phones** | Target is a 4 GB entry-tier Snapdragon or Helio handset. Every number in every document is measured on that class of hardware. | The target device is named in [SETUP.md](SETUP.md) and every scorecard records the device it was produced on. |

## 2a. Non-functional requirements

The functional requirements say what the system does. These say how well, and each is
measured rather than asserted — the method is in
[EVALUATION.md](EVALUATION.md#1-measurement-conditions).

| ID | Requirement | Target | Verified by |
| --- | --- | --- | --- |
| **N1 Latency** | End-to-end delay from the end of a spoken sentence to audio beginning on the receiving handset | 800–1200 ms push-to-talk; 1050–1500 ms telephone mode | `latency.csv`, median and p95 over ≥ 100 utterances |
| **N2 Efficiency** | Installer size, resident memory, idle processor use while listening | < 30 MB installer; < 350 MB resident; < 2 % idle CPU | Perfetto trace, memory profiler, APK inspection |
| **N3 Endurance** | Standby listening on one charge, BLE, screen off | > 8 h | `batterystats` over a full run |
| **N4 Reliability** | A corrupt frame is discarded and never spoken | 100 % — a garbled instruction is more dangerous than a missing one | CRC and AEAD tests in `core-proto` |
| **N5 Robustness** | The frame decoder survives malformed input | No uncaught exception, no unbounded growth, recovery on the next valid frame | Fuzz harness, 20 000 inputs |
| **N6 Security** | An unauthorised transmitter cannot inject an alert | Every single-byte mutation fails verification | Mutation tests; see [SECURITY.md](SECURITY.md) |
| **N7 Usability** | Operable without reading, with gloves, screen off | Icons and colour carry primary meaning; text is secondary | Inclusive design rules, [UX.md §4](UX.md#4-inclusive-design-rules) |
| **N8 Portability** | Runs on a low or mid-range handset | Every reported figure measured on the entry-tier target device | Device named in every scorecard row |
| **N9 Sustained performance** | Figures hold after prolonged use, not only cold | RTF after a 30-minute soak < 2× cold RTF | Thermal soak from week 6 |

N9 exists because entry-tier handsets throttle after roughly ten minutes of continuous
inference and the real-time factor can double. A latency figure taken from a cold device is
not the figure a jury will see.

## 3. Assessment criteria

Eighty per cent of the marks are allocated to measurable properties. This is unusual and
it is a gift: the jury has said exactly what to instrument.

**ISRO states three criteria totalling 80 %.** The remaining 20 % is not described. The
last row below is **our inference**, not their words, and must never be presented as their
rubric — if asked, say the statement allocates 80 % and we assume the balance rewards a
complete, robust, well-presented system.

| Weight | Criterion | What it forces |
| --- | --- | --- |
| 40 % | Accuracy | Low WER for STT, high legibility and natural flow for TTS. Requires a real evaluation harness over held-out benchmark data, per language, reported honestly, including under noise. |
| 20 % | Latency | STT delay, TTS delay, real-time factor, and the end-to-end delta between a sentence spoken on one handset and heard on the other. Requires per-stage instrumentation in the shipped application. |
| 20 % | Efficiency | Model size, application size, RAM and flash footprint, idle CPU. Requires quantisation, on-demand language packs, and a tiered wake-up path. |
| 20 % | *Unstated — our inference* | ISRO does not describe this share. We assume completeness, robustness and presentation of the delivered system, which is also where self-imposed work such as security earns its keep. Present it as an assumption, never as their criterion. |

**Design principle drawn from the rubric:** instrument from day one. The application shows
a permanent latency and resource strip and can print its own scorecard. Most competing
teams will demonstrate that their system works; few will show measurements. The rubric is
asking for measurements.

## 4. Traceability matrix

| Req | Primary module | Specification | Verified by |
| --- | --- | --- | --- |
| R1 | `core-asr`, `core-tts`, `core-models` | [MODELS.md](MODELS.md) | `scorecard.csv` |
| R2 | `app`, `core-models` | [ARCHITECTURE.md](ARCHITECTURE.md) | `resource.csv`, manifest inspection |
| R3 | `core-asr` | [ASR.md §1–2](ASR.md) | `latency.csv`, Perfetto trace |
| R4 | `core-asr`, `core-tts` | [TTS.md §1](TTS.md#1-text-normalisation) | Normalisation regression suite |
| R5 | `core-proto` | [PROTOCOL.md](PROTOCOL.md) | Codec unit tests, byte counter |
| R6 | `core-link` | [TRANSPORT.md](TRANSPORT.md) | Transport integration suite ×4 |
| R7 | `core-tts`, `core-audio` | [TTS.md §3–4](TTS.md) | `latency.csv`, listening panel |
| R8 | `core-audio`, `app` | [UX.md §3](UX.md#3-alert-delivery) | Locked-handset alert test |
| R9 | `app` | [ARCHITECTURE.md](ARCHITECTURE.md) | Identical APK on both devices |
| R10 | `app`, `core-audio` | [UX.md §2](UX.md#2-the-two-modes) | Two-device manual script |
| R11 | `app`, `core-asr` | [UX.md §2](UX.md#2-the-two-modes) | Two-device manual script |
| C1 | all | [LICENSES.md](../LICENSES.md) | Licence audit |
| C2 | `app` | [ARCHITECTURE.md](ARCHITECTURE.md) | No `INTERNET` permission |
| C3 | `core-asr`, `core-tts` | [MODELS.md](MODELS.md) | Dependency list |
| C4 | all | [SETUP.md](SETUP.md) | Device recorded in every scorecard |
| N1 | `core-asr`, `core-tts`, `core-link` | [EVALUATION.md §4](EVALUATION.md#4-latency--20--of-the-mark) | `latency.csv`, median and p95 |
| N2 | `core-models`, `app` | [EVALUATION.md §5](EVALUATION.md#5-efficiency--20--of-the-mark) | Perfetto, memory profiler, APK inspection |
| N3 | `core-audio`, `core-link` | [TESTING.md §6](TESTING.md#6-soak-tests) | `batterystats`, 8 h run |
| N4 | `core-proto` | [PROTOCOL.md §12](PROTOCOL.md#12-reliability) | `Crc16Test`, `AeadTest` |
| N5 | `core-proto` | [PROTOCOL.md §13](PROTOCOL.md#13-stream-framing) | `StreamFramerTest` fuzz harness |
| N6 | `core-proto` | [SECURITY.md §3](SECURITY.md#3-controls) | Mutation tests, 384 + 10 mutations |
| N7 | `app` | [UX.md §4](UX.md#4-inclusive-design-rules) | Two-device manual script, TalkBack pass |
| N8 | all | [SETUP.md §2](SETUP.md#2-target-hardware) | Every scorecard names its device |
| N9 | `core-asr`, `core-tts` | [TESTING.md §6](TESTING.md#6-soak-tests) | 30-minute thermal soak |

## 5. Explicit non-goals

Stating these prevents scope creep and, in front of a jury, reads as judgement rather than
omission.

- **Speaker identity is not preserved.** Compensated by a sender identifier in the header,
  a display name in the interface, and a distinct synthesis voice per node.
- **Prosody and emotion are not preserved.** Compensated by an explicit priority flag and
  a harder, faster delivery profile for alert traffic.
- **Background audio is discarded.** Accepted, and arguably desirable given the bandwidth
  budget.
- **No machine translation.** Cross-language delivery works only for template-coded
  messages, where it is a property of the shared table rather than a model. Free-form
  cross-language messaging is out of scope and should be described that way.
- **Code-mixed Hindi–English speech is a known weakness** (risk T-06). English is retained
  in the biasing lexicon; the limitation is disclosed rather than concealed.
- **No message history sync, no channel management, no file transfer.** There is no notion of a named group to create or join — pairing distributes a key, and the units that hold it are the radio set.

## 6. Who this is for

Four operators, drawn from the audiences in the problem statement's own framing: alert and
distress scenarios, where transmitting audio is "more inclusive and will cater to everyone
even if they are literate or not".

### 6.1 Asha — relief worker, cannot read

Deployed to a flooded district. Speaks Odia. Left school at nine.

- **As Asha, I want to report casualties by speaking**, so that my information reaches base
  even though I cannot compose a message in any script. → R1, R3, R4, R7
- **As Asha, I want to hear replies in Odia**, so that I do not need someone literate beside
  me to interpret. → R1, R7
- **As Asha, I want to know the message was sent**, without reading a status line — a haptic
  pulse and a spoken cue. → [UX.md §4](UX.md#4-inclusive-design-rules) rule 2

> Asha is the reason the project exists. Every existing system that carries text over
> narrowband radio requires her to type and to read, which excludes her at exactly the
> moment her information is most valuable.

### 6.2 Ravi — NDRF team leader

Coordinating four units across a district. Towers are down. Speaks Hindi; two of his units
speak Tamil.

- **As Ravi, I want to raise an alert that reaches every unit at full volume**, even a
  handset that is locked and silenced in a pocket. → R8
- **As Ravi, I want my Tamil-speaking units to hear alerts in Tamil**, without a translator
  and without me choosing a language. → Template cross-language delivery, [PROTOCOL.md §5.1](PROTOCOL.md#51-cross-language-delivery)
- **As Ravi, I want to see that a message was delivered**, and to how many units, so I know
  whether to repeat it. → Acknowledgement, [PROTOCOL.md §12](PROTOCOL.md#12-reliability)
- **As Ravi, I want to confirm an alert before it goes**, because an alert that says the
  opposite of what I meant is worse than no alert. → Risk S-03

### 6.3 Meena — soldier on patrol

At altitude, wearing gloves, in darkness. Cannot look at a screen.

- **As Meena, I want to transmit by pressing one physical key**, with the screen off. → R10,
  [UX.md §2](UX.md#2-the-two-modes)
- **As Meena, I want the channel to tell me when someone else is speaking**, so we do not
  transmit over each other. → Floor control, risk S-05
- **As Meena, I want to know the link is alive** rather than merely quiet. → Heartbeat
- **As Meena, I want a stranger in radio range to be unable to send my unit a false order.**
  → Risk S-01, [SECURITY.md](SECURITY.md)

### 6.4 Base station operator

At a fixed post with mains power, coordinating whoever is in range.

- **As the base operator, I want to switch between push-to-talk and open conversation**,
  because a briefing and an emergency need different modes. → R10, R11
- **As the base operator, I want to add a new unit in seconds**, without typing a key or a
  channel number. → Pairing, [UX.md §5](UX.md#5-pairing)
- **As the base operator, I want to replay what was said**, because a name or a grid
  reference is easy to mishear once. → Message log

### 6.5 What every story has in common

None of them involves reading or typing during operation. That is the design constraint the
whole system is arranged around, and any change that introduces a reading step for a routine
task should be rejected on that basis alone.

## 7. Open questions

Recorded rather than left implicit. Each blocks something specific.

| # | Question | Blocks | Owner |
| --- | --- | --- | --- |
| Q1 | Who owns each of the six roles? | Every weekly gate review; every risk needs exactly one owner | Team |
| Q2 | Which entry-tier handset is the target device? | **Every reportable number in the project** — risk P-02, task P0.2 | Team |
| ~~Q3~~ | ~~How is sherpa-onnx actually distributed?~~ **Answered 2026-09-04.** Not on Maven Central under *any* coordinates — the Central search API returns no k2-fsa artifact at all. It ships as a GitHub release asset, `sherpa-onnx-1.13.7.aar`, fetched and checksum-verified by `tools/fetch_sherpa.sh` | — | Closed |
| ~~Q4~~ | ~~Do the Piper Odia voices exist?~~ **Answered 2026-09-04: no**, and the gap is wider — Piper has no Tamil, Gujarati or Kannada voice either, covering six of our ten languages. Meta MMS **cannot** be dropped, and would apply to four languages. Risk T-05 re-escalated to High | Now a work item, not a question | Synthesis |
| Q5 | Can the language-pack downloader live outside the shipped manifest, so no `INTERNET` permission ever ships? | Constraint C2 in its strongest form; the fallback is sideloading | Application |
| Q6 | Can 150 listening-panel speakers be recruited — 15 per language? | Mean opinion score, and therefore part of the accuracy criterion — risk P-05 | Evaluation |
| Q7 | Does the sliding-window decoder actually recover the 400 ms it is budgeted to? | The revised 800–1200 ms latency target — risk T-16, task W3.13 | ASR |

**Q2 — which handset — is now the one that blocks the most**, since it gates every
reportable number in the project. Q3 and Q4 are closed; Q4's answer created work rather
than removing it, which is the more useful kind of answer to get early.
