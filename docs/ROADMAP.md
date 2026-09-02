# Roadmap

> ## The nearest deadline is not the build
>
> **Idea submission closes 20 September 2026.** What is due is six slides as a PDF, on the
> official template. No code is submitted and no demonstration is given.
>
> The eight-week plan below is the *build*, and it only matters if the submission gets
> through. Content for the slides is written and ready in
> [IDEA_SUBMISSION.md](IDEA_SUBMISSION.md); the remaining work is filling the template,
> exporting to PDF, and uploading. **Do that first, in week 1, in parallel with
> engineering** — not in the week it is due.

Eight weeks. The governing rule is that the end-to-end loop closes before any effort is
spent on model quality.

> ## The scheduling rule that matters
>
> **Week three is the hinge.** A working loop with mediocre models can be improved
> incrementally under any amount of time pressure. Excellent models with no loop cannot be
> demonstrated at all. Every competing team that fails will fail by inverting this order.
>
> This is risk P-01, and it outranks every other consideration in this document. If a
> week-4 task threatens the week-3 gate, the week-4 task is cut.

## 1. Schedule

| Week | Objective | Deliverable |
| --- | --- | --- |
| 1 | **Capture and recognise** | Skeleton application; `AudioRecord` → Silero VAD → Vosk Hindi; recognised text on screen. Entry-tier target handset acquired. Licence register opened |
| 2 | **Transport** | `Link` interface, RFCOMM implementation, frame codec with CRC, unit-tested and fuzzed decoder. Typed text traverses two phones. Independent of any model |
| 3 | **Close the loop** | Piper synthesis on the receiving device. Speech in, speech out, end to end. Baseline latency measured and recorded. **The project is now de-risked** |
| 4 | **Real models** | IndicConformer exported and quantised; five languages integrated; WER harness operational; Odia gap assessed and assigned |
| 5 | **Modes and alerts** | Push-to-talk with hardware key, half and full duplex, alert path with alarm-stream routing, wake lock and lock-screen delivery |
| 6 | **Latency and reach** | Chunked synthesis, stabilised partials, adaptive endpointing. BLE and Wi-Fi transports. Encryption, replay window and relay. Thermal soak begins |
| 7 | **Coverage and hardening** | Remaining languages; template tables; normalisation suites; noise pre-processing; field testing at range and under noise; four-device relay soak |
| 8 | **Evidence and rehearsal** | Scorecard generation, latency HUD, documentation, LoRa bench demonstration if hardware permits, demonstration rehearsed to a fixed script |

## 2. Acceptance gates

A week is not complete until its gate passes. A gate that does not pass is escalated
immediately, not absorbed into the following week — absorbing slippage silently is how a
week-3 hinge becomes a week-5 hinge.

| Gate | Criterion | Evidence |
| --- | --- | --- |
| **W1** | Hindi speech produces correct text on screen on the target handset, offline | Video, 10 utterances |
| **W2** | Typed text on device A appears on device B over RFCOMM; the decoder survives 10⁵ fuzz inputs | CI green, two-device video |
| **W3** | **Speech on device A is heard on device B.** End-to-end latency measured and recorded | `latency.csv`, video |
| **W4** | Five languages recognised; WER harness produces a `scorecard.csv` for all five | `scorecard.csv` |
| **W5** | PTT with the hardware key; an alert wakes a locked, silenced handset and announces at full volume | Video of the locked-handset test |
| **W6** | Encrypted frames; replay rejected; BLE and Wi-Fi transports pass the same integration suite; first 30-minute soak recorded | Test report, soak trace |
| **W7** | Ten languages present; normalisation suites 100 %; four-device relay soak clean; field test at range recorded | `scorecard.csv` (all ten), soak report |
| **W8** | Full scorecard for ten languages; demonstration run end to end three times without intervention | The three artefact CSVs, rehearsal log |

## 3. Allocation

Six roles. Names are filled in by the team; the risks each role carries are not negotiable
and are what the role is accountable for.

| Role | Owns | Principal risks carried | Owner |
| --- | --- | --- | --- |
| Models — recognition | `core-asr`, export, quantisation, biasing | T-02, T-05, T-07 | _____________ |
| Models — synthesis | `core-tts`, normalisation, voice selection | T-05 | _____________ |
| Audio engine | `core-audio`, VAD tiers, endpointing, noise | T-01, T-12 | _____________ |
| Transport | `core-link`, `core-proto`, security, relay | T-08, T-09, T-10, S-01, S-02 | _____________ |
| Application | UI, PTT, alerts, provisioning, packs | S-05, P-03 | _____________ |
| Evaluation | `bench`, datasets, scorecard, documentation | T-03, P-02 | _____________ |

Every risk in [RISKS.md](RISKS.md) has exactly one owner. A risk with no owner is not
being managed.

## 4. Critical path

```
 W1 capture ──► W2 transport ──► W3 LOOP CLOSES ──► W5 modes ──► W8 rehearsal
                                       │
                                       ├──► W4 models ──► W7 ten languages ──► W8 scorecard
                                       │
                                       └──► W6 crypto + BLE/Wi-Fi ──► W7 relay soak
```

Only the top line is critical. Model work, transport breadth and cryptography all branch
**after** the loop closes and can each be cut back independently without losing a
demonstrable system.

### What gets cut, in order, if time is lost

1. Matcha-TTS benchmarking — Piper is sufficient
2. `AUDIO_FB` Opus fallback — a nice answer to a question nobody may ask
3. Multi-hop relay, `POSITION`, store-and-forward — **none of these appear in the problem
   statement**; they are ours
4. **Encryption, replay window and pairing** — ISRO asks for none of it (see
   [SECURITY.md §0](SECURITY.md#0-this-is-not-a-stated-requirement)). Degrade to a fixed
   pre-shared key compiled into the build, keep the UNSECURED banner honest, and say so
5. Wi-Fi transport — BT Classic and BLE cover the requirement
6. Languages beyond five — report five well rather than ten badly
7. LoRa hop — the strongest differentiator, but a differentiator, not a requirement

**Never cut:** the loop, the alert path, the ten-language coverage, the scorecard. The
first two are ISRO's words; the rest is 80 % of the mark.

> Security sits at position 4 deliberately. It is the right engineering call and it is
> genuinely defensible under "robust, deployable system architecture" — but it is worth
> **zero marks directly**, and a week spent on AEAD that should have gone to word error
> rate is a week traded from a 40 % criterion into a 0 % one.

## 5. Parallel tracks

These run continuously from week 1, not as a phase:

| Track | Cadence | Owner |
| --- | --- | --- |
| Licence register maintained | Every new dependency, enforced in CI | Transport |
| Benchmark harness improved | Weekly from week 4 | Evaluation |
| Documentation kept in step with behaviour | Same commit as the change | Everyone |
| Demonstration script rehearsed | Weekly from week 6 | Application |
| Thermal soak | Weekly from week 6 | Evaluation |

## 6. Milestones outside the build

| Milestone | Depends on | Note |
| --- | --- | --- |
| SIH idea submission PPT | Sections 01–03 of the design document | Content is drafted in [IDEA_SUBMISSION.md](IDEA_SUBMISSION.md); must be uploaded as **PDF**, six slides maximum |
| Target handset acquired | — | Week 1. Blocks every reportable number (P-02) |
| LoRa hardware acquired | — | Order by week 4; a two-week lead time will otherwise consume the differentiator |
| Listening panel recruited | Week 7 voices | 15 native speakers per language is the largest logistical task in the evaluation — start recruiting in week 5 |

The listening panel is the item most likely to be left until it is too late. It needs
people, and people need notice.
