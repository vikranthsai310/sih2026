# Risk register

Thirty-one identified failure modes, each with a designed response. **Severity reflects impact
on the assessed criteria**, not merely on the software — a defect that costs marks is more
severe here than one that merely annoys a user.

Every risk has exactly one owner. A risk with no owner is not being managed.

Status values: `OPEN` · `MITIGATING` · `CLOSED` · `ACCEPTED`.

## 1. Technical risks

| ID | Risk | Sev | Owner | Mitigation | Trigger to escalate | Status |
| --- | --- | --- | --- | --- | --- | --- |
| **T-01** | Word error rate collapses in real acoustic noise, destroying the largest single mark | High | Audio | RNNoise or DTLN pre-processing (~200 KB); adaptive gain; 80 Hz high-pass; platform `NoiseSuppressor`. Evaluate and report at four SNRs rather than clean only. Template fallback engages when confidence drops | WER at +10 dB SNR above 30 % for any language in week 4 | OPEN |
| **T-02** | Digits, callsigns and place names misrecognised — precisely the tokens that carry operational meaning | High | ASR | Contextual biasing with a domain lexicon and deployment gazetteer; digits read and confirmed on screen before transmission; separate critical-term error metric tracked throughout | Critical-term error above 10 % with biasing on | OPEN |
| **T-03** | Thermal throttling doubles real-time factor during a long demonstration | High | Evaluation | 30-minute soak testing from week 6; report sustained figures; reduce thread count under thermal pressure; BLE standby to lower baseline temperature | RTF after soak > 2× cold RTF | OPEN |
| **T-04** | Total asset size defeats the efficiency criterion | Med | Models | int8 quantisation throughout; on-demand language packs; ABI splitting; a 25 MB installer with two languages resident | Installer above 30 MB, or any pack above 70 MB | OPEN |
| **T-05** | **No open synthesis voice exists for four of the ten languages** | **High** | Synthesis | **Re-escalated 2026-09-04, and the gap is wider than Odia.** The `rhasspy/piper-voices` repository tree carries **no Tamil, Gujarati, Kannada or Odia** directory, and `piper/VOICES.md` corroborates it. An earlier note recorded Odia voices named Debjani and Manas as present; that came from a search summary rather than the repository and **was wrong**. Piper covers six of our ten: en, hi, bn, mr, te, ml. `models/manifest.json` now declares `"tts": null` for the other four and the interface still offers them — they recognise and display, they simply cannot speak (W4.6). Resolution options in order of preference: a Coqui VITS voice trained on AI4Bharat IndicTTS data (MPL-2.0, ~2 GPU-days per language); Meta MMS as a disclosed CC-BY-NC stopgap; or ship them recognise-only and say so | Four languages still silent at the week-6 gate | **OPEN** |
| **T-06** | Code-mixed Hindi and English speech is mis-decoded | Med | ASR | English retained in the biasing lexicon; acknowledged openly as a limitation with a stated roadmap rather than concealed. Honest scoping is defensible; a broken demonstration is not | — | ACCEPTED |
| **T-07** | NeMo → ONNX export of IndicConformer fails or degrades accuracy | Med | ASR | Vosk models integrated in week 1 keep the schedule intact regardless. Export attempted in week 4 with three weeks of slack behind it | Export incomplete by end of week 5 | OPEN |
| **T-08** | Stream framing defect — partial reads corrupt frames under load | Med | Transport | Mandatory `readFully` loop; CRC on every frame; magic-byte resynchronisation; fuzz testing of the decoder with truncated and interleaved input | Any frame corruption observed in a soak | OPEN |
| **T-09** | Wi-Fi Direct discovery unreliable across manufacturers | Med | Transport | Hosted-network path adopted as the primary Wi-Fi mechanism; `WifiP2pManager` treated as optional. Bluetooth remains the default transport regardless | — | MITIGATING |
| **T-10** | Broadcast storm when three or more devices relay | Low | Transport | TTL decrement plus a bounded seen-set of `(SRC, EPOCH, SEQ)`; verified with a four-device soak | Duplicate delivery observed with 3+ devices | OPEN |
| **T-11** | Cold-start model load stalls the first transmission | Low | Application | Models loaded at foreground-service start and held resident; the transmit control disabled until ready, with a visible indicator | — | MITIGATING |
| **T-12** | Microphone unavailable — an incoming call or another application holds it | Low | Audio | Audio focus and recording-configuration callbacks; explicit `DEGRADED` state surfaced in the UI; automatic recovery on release | — | OPEN |

## 2. Security and safety risks

| ID | Risk | Sev | Owner | Mitigation | Status |
| --- | --- | --- | --- | --- | --- |
| **S-01** | Fraudulent alert injected by an unauthorised transmitter, played at maximum volume | High | Transport | AES-256-GCM with a pre-shared key held by every paired unit; the sender identifier lies inside the authenticated region; frames failing authentication are discarded silently | OPEN |
| **S-02** | Recorded alert replayed later | High | Transport | 64-entry sliding replay window per sender keyed on `(EPOCH, SEQ)`; sequence numbers outside the window rejected | OPEN |
| **S-03** | Recognition error inverts meaning — "do not evacuate" becomes "now evacuate" | High | ASR | Confidence transmitted with every frame; recognised text displayed to the sender before transmission; explicit confirmation required for alert-class messages; negation terms added to the biasing lexicon | OPEN |
| **S-04** | Key material exposed on a captured device | Med | Transport | Android Keystore storage; re-keying supported; a unit is removed by rotating the key at the next provisioning | OPEN |
| **S-05** | Two operators transmit simultaneously on a half-duplex channel, garbling both | Med | Application | `PTT_CTL` floor announcement, channel-busy indicator, randomised backoff before retry | OPEN |

Detail and verification for all five is in [SECURITY.md](SECURITY.md).

## 3. Programme risks

| ID | Risk | Sev | Owner | Mitigation | Status |
| --- | --- | --- | --- | --- | --- |
| **P-01** | Effort consumed by model work, leaving the end-to-end loop unproven late in the schedule | High | All | The loop closes in week 3 using the weakest acceptable models. Model quality is an upgrade path, never a prerequisite. **This is the single most important scheduling decision in the plan** | OPEN |
| **P-02** | Development on flagship hardware conceals performance failures | High | Evaluation | An entry-tier target handset is acquired in week 1 and every benchmark is taken on it | OPEN |
| **P-03** | Live pairing fails on stage | Med | Application | QR provisioning; devices pre-paired before the session; a rehearsed recovery path; the demonstration script fixed by week 8; a third pre-configured handset kept ready | OPEN |
| **P-04** | Licence incompatibility discovered late | Low | Transport | `LICENSES.md` maintained from week 1; GPL and non-commercial components flagged on entry, not on discovery; a CI gate fails any dependency without an entry | MITIGATING |

## 4. Risks added during specification and problem-statement review

Identified while writing the normative specification, and while auditing our documents
against ISRO's verbatim text in [REQUIREMENTS.md §0](REQUIREMENTS.md#0-the-problem-statement-verbatim).
None appears in the original design document.

| ID | Risk | Sev | Owner | Mitigation | Status |
| --- | --- | --- | --- | --- | --- |
| **T-13** | Script packing is lossy on ordinary input — ASCII digits, punctuation and ZWJ/ZWNJ sit outside every Indic block | Med | Transport | Complete single-byte alphabet with a 4-byte escape, plus a `unpack(pack(s)) == s` round-trip assertion over a full Unicode corpus. See [PROTOCOL.md §4](PROTOCOL.md#4-level-2--script-packing) | MITIGATING |
| **S-06** | Template profile mismatch — the same byte renders as a different sentence on two devices | **High** | Transport | `profileDigest` advertised in every `HEARTBEAT`; a mismatch disables template sending and raises a persistent warning. Degrading to script packing costs bytes; speaking the wrong sentence costs more. See [PROTOCOL.md §5.2](PROTOCOL.md#52-profile-binding) | MITIGATING |
| **S-07** | AEAD nonce reuse under a fixed key, which destroys GCM entirely | **High** | Transport | Nonce derived as `EPOCH ‖ SRC ‖ SEQ`; `EPOCH` persisted and incremented on every `SEQ` wrap and every service start; uniqueness asserted over 10⁷ simulated frames including restarts | MITIGATING |
| **T-14** | Quoted frame sizes omit the authentication tag, so the headline compression figure does not survive a technical question | Low | Evaluation | Report all four compression ratios — unauthenticated, authenticated, versus Opus, and template best-case. See [EVALUATION.md §5](EVALUATION.md#5-efficiency--20--of-the-mark) | MITIGATING |
| **T-16** | **No streaming acoustic model exists for the ten Indian languages.** Every available Indic model is offline, so nothing is decoded until the utterance ends | **High** | ASR | Confirmed by verification on 2026-09-03. Endpointing already comes from our own VAD tiers rather than the model, so the architecture survives. Sliding-window decoding runs during speech so only a short tail is decoded after the endpoint. **Latency targets revised upward and republished** rather than quietly missed. See [ASR.md §3.5](ASR.md#35-decoding-an-offline-model-without-paying-for-it-at-the-end) | MITIGATING |
| **T-17** | **The acoustic model is one shared 120 MB file, not ten 35 MB packs.** IndicConformer is ~120 M parameters, 493 MB as float32 | **Med** | Models | Total footprint is a third of the assumed 350 MB, but it cannot be delivered per language, so the on-demand story now applies to synthesis voices only. Efficiency figures republished on that basis. See [MODELS.md §1](MODELS.md#1-delivery) | MITIGATING |
| **P-05** | Listening panel of 15 native speakers per language cannot be assembled in time, leaving MOS unreported | Med | Evaluation | Begin recruiting in week 5, not week 7. If a language cannot reach 15 panellists, report the actual panel size rather than dropping the figure | OPEN |
| **P-06** | **Idea submission deadline missed or met with a rushed deck.** Closes **20 September 2026** | Med | *Owned outside the engineering track — a teammate is handling the submission* | Slide content is already written in [IDEA_SUBMISSION.md](IDEA_SUBMISSION.md). Slide content is ready for them; the engineering track neither blocks on this nor is blocked by it. Verify PS ID `26173`, title and theme **Smart Automation** against the portal before upload | OPEN |
| **P-07** | Effort spent on work the problem statement never asked for — cryptography, relay, addressing, position — while a scored criterion goes unmeasured | **High** | All | [REQUIREMENTS.md §0](REQUIREMENTS.md#0-the-problem-statement-verbatim) records ISRO's text verbatim and names what is self-imposed. Unrequired work sits on the cut list in [ROADMAP.md §4](ROADMAP.md#4-critical-path) above languages and the LoRa hop. Weekly gate check asks one question: *has anything in the 80 % gone unmeasured this week while unrequired work advanced?* | OPEN |
| **T-15** | ONNX Runtime is not among the frameworks ISRO names, and the choice is challenged at evaluation | Low | ASR | "or similar" permits it, and the written defence is in [ARCHITECTURE.md §7.1](ARCHITECTURE.md#71-why-onnx-runtime-and-not-the-frameworks-isro-names) — including why TFLite **Micro** is the wrong family member for a 4 GB handset. LiteRT and ExecuTorch remain drop-in behind the module boundary | MITIGATING |

## 5. Review cadence

The register is reviewed weekly at the gate check in [ROADMAP.md §2](ROADMAP.md#2-acceptance-gates).
For each risk the owner reports: unchanged, mitigating, escalating, or closed. A risk that
has been `OPEN` and unchanged for three consecutive weeks is either not real or not being
worked, and either answer requires a decision.

Closing a risk requires evidence — a passing test, a recorded measurement, a merged
mitigation — not an opinion.
