# Evaluation

Eighty per cent of the assessment is measurable. This document defines what is measured,
how, and what result counts as a pass.

**The governing principle:** most teams will demonstrate that their system works; few will
show measurements. The rubric is asking for measurements. Every figure below is produced by
the `bench` module and exported as a file, not read off a slide.

## 1. Measurement conditions

All figures MUST be produced under these conditions or they are not reportable.

| Condition | Requirement |
| --- | --- |
| Device | The entry-tier target handset named in [SETUP.md](SETUP.md). Never a flagship, never an emulator |
| Thermal state | After a **30-minute soak** of continuous inference. Cold-device figures are not reported |
| Battery | Above 30 %, not charging — many handsets throttle differently on charge |
| Build | Release build, `minifyEnabled`, the same APK used in the demonstration |
| Repetitions | 100 utterances minimum per reported latency figure; report median and p95, never a single run |

> **Sustained, not peak.** Entry-tier handsets throttle after roughly ten minutes of
> continuous inference and real-time factor can double. Reporting sustained rather than
> peak numbers is both more honest and more defensible under questioning. It also happens
> to be the only figure that predicts behaviour during a seven-minute demonstration
> following a rehearsal.

## 2. Recognition accuracy — 40 % of the mark

### Datasets

Word error rate is measured on **held-out benchmark data, not self-recorded samples**.
Self-recorded evaluation is the single most common way a hackathon accuracy claim falls
apart under questioning.

| Dataset | Source | Use |
| --- | --- | --- |
| IndicSUPERB / Kathbath | AI4Bharat | Primary WER benchmark across all ten languages |
| IndicVoices | AI4Bharat | Spontaneous rather than read speech — the realistic condition |
| Vaani | Google & IISc | District-level accent coverage; supports the inclusivity claim |
| FLEURS | Google | Figures comparable with published literature |
| Common Voice | Mozilla | Supplementary validation |

### Noise conditions

Clean-room figures are not representative of a flood embankment, and a jury may test in a
noisy hall. Noise is mixed at four signal-to-noise ratios from a standard set — crowd,
wind, engine, siren — at a fixed random seed so results are reproducible.

| Condition | Target WER | Comment |
| --- | --- | --- |
| Clean, read speech | < 12 % | Reference figure |
| +20 dB SNR | < 15 % | Quiet room, realistic |
| +10 dB SNR | < 22 % | Vehicle, crowd |
| +5 dB SNR | < 32 % | Adverse; template fallback engages |
| **Critical vocabulary, biased** | **< 6 %** | **The figure that actually matters operationally** |

### Critical-term error rate

The last row is the important one. Overall word error rate is a poor proxy for usefulness;
what matters is whether मदद, आग, sector numbers and callsigns survive.

`CTER` is defined as: over a fixed list of critical terms per language, the proportion of
reference occurrences not present in the hypothesis, counted at the term level after NFC
normalisation.

It is reported **with and without contextual biasing**, in the same table. That pair of
columns demonstrates a level of evaluation rigour that general WER alone does not, and the
delta between them is directly attributable to an engineering decision the team made.

### WER definition

Insertions plus deletions plus substitutions, divided by reference word count. Computed
after: NFC normalisation, punctuation stripping, case folding, and whitespace collapsing.
Numerals are compared in their spoken-word form so that `17` and `सत्रह` do not count as
an error against each other. The scoring script is in `bench/` and is the single source of
truth — no figure is ever computed by hand.

## 3. Synthesis quality

| Measure | Target | Method |
| --- | --- | --- |
| Mean opinion score | > 3.8 / 5 | Blind listening panel, minimum **15 native speakers per language**, 5-point scale, samples randomised and interleaved with a reference |
| Intelligibility | > 95 % | Native listeners transcribe synthesised output; scored as word accuracy against the source text |
| Normalisation correctness | **100 %** | Fixed regression suite of numbers, units, times and callsigns per language, asserted in CI |

MOS is the softest number in the project and should be reported with its panel size and
methodology attached. A MOS quoted without a panel size is not a measurement.

## 4. Latency — 20 % of the mark

| Stage | Budget | Note |
| --- | --- | --- |
| Capture and buffering | 20–40 ms | 20 ms hop size |
| Endpoint silence window | 150–400 ms | Largest term; a design choice, not compute. 150 ms in PTT mode |
| Final decode after endpoint | 250–450 ms | **Revised.** The recogniser is offline, so only the final window is decoded after the endpoint; the earlier windows were decoded during speech. See [ASR.md §3.5](ASR.md#35-decoding-an-offline-model-without-paying-for-it-at-the-end) |
| Framing, encryption, transmit | 20–60 ms | Bluetooth; sub-millisecond of that is the payload itself |
| Normalisation | < 10 ms | Rule-based |
| First synthesis chunk | 150–250 ms | Chunked, not full-sentence synthesis |
| Output pipeline | 30–80 ms | `AudioTrack`; Oboe if this proves material |
| **End to end, PTT mode** | **800–1200 ms** | **Revised upward.** The figure the jury will time. Naive offline decode would give ~1330 ms; sliding-window decoding recovers roughly 400 ms of it |
| **End to end, phone mode** | **1050–1500 ms** | Includes the 400 ms silence window |
| Real-time factor, ASR | < 0.30 | Sustained, after thermal soak |
| Real-time factor, TTS | < 0.25 | Sustained |

### How end-to-end is measured

Not with a stopwatch. Both handsets timestamp against a common reference established by a
clock-sync exchange over the link at session start (four `HEARTBEAT` round trips, median
offset). `tAudio` on the receiver minus `tMic` on the sender, corrected by the offset, is
the end-to-end figure, and it is written to `latency.csv` for every utterance.

Report the **median and p95 over at least 100 utterances**. A single best-case number is
not a measurement and a jury that has seen a hundred demos knows it.

### Implementation

`ClockSync` in `core-proto` (task W3.10) and `LatencyLog` in `bench` (task W3.11), the
latter wired into the application itself so every utterance is logged during ordinary use —
the only realistic way to reach a hundred of them.

Three decisions in there are worth stating, because each is a way the numbers could
otherwise be wrong without anyone noticing:

- **The offset is a median, not a mean.** Bluetooth round trips are occasionally stalled by
  tens of milliseconds while the radio is busy, and one such outlier drags a mean far more
  than it moves a median. A test holds a single 200 ms stall to no more than 1 ms of error
  in the offset.
- **An unsynchronised clock refuses to produce a figure** rather than returning a zero
  offset. A zero would look plausible and would make every latency figure derived from it
  wrong.
- **A stage that did not happen is written as an empty field, never a zero.** An offline
  recogniser produces no partial hypothesis, and a zero in `t_first_partial` would be read
  as a partial arriving at the microphone and averaged in as a measurement.

The estimator assumes the outbound and return paths take equal time. They do not exactly,
and the residual error is half the path asymmetry — a few milliseconds over Bluetooth,
against a budget of 800 ms. That bound is asserted as a test rather than left as a claim.

## 5. Efficiency — 20 % of the mark

| Quantity | Target | Method |
| --- | --- | --- |
| Installer size | < 30 MB | `arm64-v8a` split, App Bundle, no bundled weights beyond the base pack |
| Per-language footprint | < 70 MB | int8 quantised acoustic model and voice |
| Resident memory, one language | < 350 MB | Android Studio memory profiler under sustained load |
| Idle CPU, listening | < 2 % | Perfetto trace over ten minutes of silence with VAD active |
| Active CPU, recognising | < 35 % | Perfetto, single core, during continuous speech |
| Standby endurance | > 8 h | `batterystats`, BLE transport, screen off, listening |
| Cold start to ready | < 2 s | Instrumented service start; models preloaded thereafter |

### Compression ratio

Reported three ways, because one of them will be challenged:

| Comparison | Ratio | When to quote it |
| --- | --- | --- |
| Packed text vs raw PCM, unauthenticated | 2 182× | The headline |
| Packed text vs raw PCM, authenticated | 1 600× | The honest deployment figure |
| Packed text vs Opus at 6 kbps | 43× | **When asked "why not just use a codec?"** |
| Template code vs raw PCM | 7 385× | The best case, clearly labelled as best case |

Have all four ready. Quoting only the largest and being asked for the Opus comparison
without having it is a bad thirty seconds in front of a technical panel.

## 6. Instrumentation

Every stage boundary emits a timestamp; the application maintains a rolling histogram and
can export a complete run as CSV. The operating screen displays current figures permanently
— **an instrumentation strip, not a debug view**.

### Output artefacts

| File | Contents |
| --- | --- |
| `scorecard.csv` | Per language: WER at four SNRs, critical-term error with and without biasing, TTS MOS, model sizes, RTF |
| `latency.csv` | Per utterance: every stage boundary, transport, payload size, end-to-end delta |
| `resource.csv` | Sampled CPU, resident memory, battery drain, thermal state |

Producing these three files automatically converts the presentation from a set of claims
into a set of results.

### `scorecard.csv` schema

```
lang,model,model_bytes,voice_bytes,wer_clean,wer_snr20,wer_snr10,wer_snr5,
cter_biased,cter_unbiased,mos,mos_panel_n,rtf_asr,rtf_tts,device,build,soak_minutes,date
```

### `latency.csv` schema

```
utterance_id,lang,mode,transport,t_mic,t_vad,t_first_partial,t_endpoint,t_final,
t_tx,t_rx,t_norm,t_chunk1,t_audio,t_done,payload_bytes,frame_bytes,
compression_ratio,confidence,template_id,end_to_end_ms
```

### `resource.csv` schema

```
timestamp,cpu_pct,rss_bytes,battery_pct,thermal_status,state,lang,transport
```

Every row of every file records the device and build it came from. A scorecard that does
not name its hardware is not evidence.

## 7. Reporting rules

These are non-negotiable and they are what makes the evaluation credible.

1. **Never report a number the harness did not produce.** If it is not in a CSV, it does
   not go in the deck.
2. **Never report a clean-only WER.** All four SNRs or none.
3. **Never report peak latency as though it were typical.** Median and p95, over ≥ 100
   utterances.
4. **Always name the device and the soak duration** alongside any performance figure.
5. **Report failures.** If Odia MOS is 3.1, the deck says 3.1 and says why, with the
   mitigation. A jury trusts a team that discloses its weakest language far more than one
   whose ten languages are suspiciously uniform.
