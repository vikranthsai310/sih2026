# The listening engine

Implemented by `core-asr`. Speech recognition carries the largest share of the accuracy
mark and the largest share of the compute budget. It is also where the idle-power score is
won or lost.

## 1. Three-tier voice activity detection

Running the acoustic model continuously would exhaust the battery in about two hours and
would fail the efficiency criterion outright. Detection is tiered so the expensive stage
runs only when the cheap stages agree that a human is speaking.

| Tier | Mechanism | Cost per 20 ms | Purpose |
| --- | --- | --- | --- |
| 0 | Energy gate — sum of squares against an adaptive noise floor | ~3 µs | Rejects true silence, which is the overwhelming majority of elapsed time. Effectively free |
| 1 | Silero VAD, 1.8 MB ONNX | ~300 µs | Distinguishes human speech from door slams, engines, wind and music. Prevents false wake-ups |
| 2 | Conformer acoustic model | ~4 ms | Actual recognition. Runs only while tier 1 reports speech |

With a duty cycle typical of radio use — speech perhaps five per cent of the time —
measured idle CPU should sit below **2 % of one core**. That figure is measured with
Perfetto on the target handset over ten minutes of silence and reported, not asserted.

### Tier 0 parameters

| Parameter | Value | Note |
| --- | --- | --- |
| Frame | 20 ms, 320 samples at 16 kHz mono | Matches the capture hop |
| Noise floor | Exponential moving average, τ = 3 s, updated only on non-speech frames | Adapts to a changing environment without being dragged up by the speaker |
| Trigger | Frame energy > noise floor + 9 dB | |
| Hysteresis | 3 consecutive frames to open, 10 to close | Prevents chattering at the threshold |

Updating the noise floor only on frames tier 1 rejects is what stops a long utterance from
raising the floor until the speaker is gated out.

### Tier 1 parameters

| Parameter | Value |
| --- | --- |
| Model | `silero_vad.onnx`, 1.8 MB, ships in the base installer |
| Window | 512 samples at 16 kHz |
| Speech threshold | 0.5 |
| Minimum speech | 250 ms |
| Minimum silence | 100 ms |

Tier 1 runs only when tier 0 has opened. Tier 2 runs only when tier 1 reports speech.

## 2. Endpointing

The problem statement asks for activation "after detecting pauses and stoppages" and for
the system to "form the sentences detected" (R3, R4). This is the endpointing state
machine.

```
 IDLE ──speech detected──► LISTENING ──────────────────► FINALISING ──► IDLE
  ▲                          │    │                          │
  │                          │    └─ 8 s elapsed ────────────┤   force cut,
  │                          │                               │   avoid hogging
  │                          └─ trailing silence ────────────┘   the channel
  │                                                          │
  └──────────────── frame transmitted ◄──────────────────────┘
```

| Parameter | Value | Rationale |
| --- | --- | --- |
| Trailing silence, phone mode | 400 ms | Shorter clips natural inter-clause pauses; longer is perceptible as lag |
| Trailing silence, PTT mode | 150 ms | Releasing the key is itself an explicit end-of-utterance signal |
| Leading pad | 250 ms | Retained from the ring buffer so the first phoneme is never clipped |
| Maximum utterance | 8 s | Bounds latency and prevents one speaker monopolising a half-duplex channel |
| Minimum utterance | 300 ms | Suppresses coughs, clicks and key noise |

> **The single largest latency term.** The 400 ms silence window is the biggest component
> of end-to-end delay, and it is a *design choice* rather than a computational cost. In
> push-to-talk mode the key release replaces it entirely, which is why PTT mode is
> measurably faster than phone mode — a point worth demonstrating live rather than
> explaining on a slide.

The leading pad requires that the ring buffer retain 250 ms of pre-trigger audio at all
times. At 16 kHz that is 4 000 samples, or 8 000 bytes, permanently allocated at
service start.

## 3. From pressure wave to text

### 3.1 Feature extraction

Raw samples are an inefficient representation. Audio is cut into 25 ms windows every
10 ms; each window is transformed to the frequency domain and projected onto 80 mel bands,
spaced the way human hearing resolves pitch. Three seconds of speech becomes a 300 × 80
matrix — a compact spectro-temporal fingerprint. Cost is roughly one millisecond, in
native code.

| Parameter | Value |
| --- | --- |
| Sample rate | 16 000 Hz, mono |
| Window | 25 ms (400 samples), Povey window |
| Hop | 10 ms (160 samples) |
| Filterbank | 80 mel bands |
| Dither | 1.0 |
| Pre-emphasis | 0.97 |

### 3.2 Acoustic model

A Conformer encoder: convolutional blocks that detect local acoustic events such as
plosive bursts and formant transitions, interleaved with self-attention blocks that supply
sentence-level context. Output is a sequence of 512-dimensional frame embeddings. This
stage is approximately **85 % of the inference cost**, and it is where the thermal budget
goes.

### 3.3 Decoding

A transducer or CTC head projects each frame onto a vocabulary of roughly one thousand
sub-word tokens defined in the language's token table. Modified beam search selects the
most probable token sequence; repeated and blank emissions collapse, and the surviving
tokens are joined into text.

| Parameter | Value | Note |
| --- | --- | --- |
| Decoding method | `modified_beam_search` | Greedy is faster but measurably worse on digits |
| Beam size | 4 | 8 costs ~35 % more time for < 0.5 % WER; not worth it on the target device |
| Threads | 2 | 4 threads is faster cold and slower after thermal soak — see risk T-03 |
| Provider | `cpu` (XNNPACK) | NNAPI is inconsistent across entry-tier vendors; evaluated and rejected |

### 3.4 Contextual biasing — the highest-yield accuracy work

Decoding scores are boosted for a supplied phrase list. In a distress context the critical
vocabulary is small and known in advance: मदद, घायल, आग, निकासी, unit callsigns, sector
numbers, local place names.

Loading a domain lexicon and a deployment gazetteer measurably reduces error on precisely
the words whose misrecognition would be most costly, and requires **no retraining**.

| Source | Contents | Size |
| --- | --- | --- |
| Domain lexicon | Distress and operational vocabulary, per language, shipped in the pack | ~300 terms |
| Negation terms | "not", "do not", "नहीं", and equivalents, weighted high | ~20 terms |
| Deployment gazetteer | Place names, sector labels, unit callsigns, loaded per deployment | ~200 terms |
| Roster | Display names of paired units, injected at runtime | ≤ 254 terms |

Hotword score 1.5 by default; the value is a per-language tunable recorded in the pack
manifest.

Negation terms are in the list because of risk S-03: a recognition error that turns "do
not evacuate" into "now evacuate" inverts meaning and is the most dangerous single failure
this system can produce.

> **Demonstration note.** Decoding the same audio with biasing off and then on is one of
> the strongest live comparisons available to us. It takes fifteen seconds and it shows a
> measurable, explainable accuracy improvement that the team engineered deliberately.

### 3.5 Decoding an offline model without paying for it at the end

> **Verified 2026-09-03, and it changes the architecture.** There is **no streaming
> (online) acoustic model published for the ten Indian languages.** Everything available
> through sherpa-onnx for Indic ASR — IndicConformer NeMo-CTC and the Dolphin CTC family —
> is **offline**. The only genuinely streaming option is Vosk, whose accuracy is materially
> lower. The design document assumed `OnlineRecognizer`, partial hypotheses and
> `isEndpoint()`; none of that is available with the production model.

The naive consequence is severe. With an offline recogniser nothing is decoded until the
utterance has ended, so the whole decode lands *after* the endpoint:

```
 speak 3 s ──────────────────────►│ endpoint │◄─── decode 3 s of audio ───►│ transmit
                                   150 ms          900 ms at RTF 0.30
```

That alone is 1 050 ms before a byte is sent, and pushes end-to-end past 1 300 ms.

**The fix is to decode during speech anyway.** The energy floor already says when the
speaker is talking and when they have paused, so each clause is decoded **in the pause
that ends it**, while the speaker is drawing breath for the next one, and only the last
clause is decoded after the endpoint:

```
 speech  |--- clause 1 ---|  pause  |--- clause 2 ---|  pause  |- clause 3 -|
                          decode 1                    decode 2   <- while the speaker talks
                                                                  endpoint
                                                                  |- decode 3 -|
```

| Parameter | Value | Rationale |
| --- | --- | --- |
| Pause that closes a clause | 280 ms of quiet after ≥ 500 ms of speech | Longer than a stop closure or an inter-word gap; shorter than a breath |
| Forced cut | At 6 s without a pause, at the quietest frame of the last 1.5 s, keeping 0.6 s of context both sides | A speaker who never pauses still leaves a bounded tail |
| Quiet kept around a clause | 300 ms either side | The model normalises per utterance; a clause with its own quiet is what it was trained on |
| Tail decode | The last clause only, and nothing if it was already read | The term that remains in the latency budget |
| Threads | 4 while decoding, 2 at idle | Cores are free during speech; thermal budget is not |

This costs roughly 1.5–2× the compute of a single pass, which the efficiency budget can
absorb because it happens only while someone is speaking — perhaps five per cent of elapsed
time. It buys back most of the delay that matters, and it does so without cutting a word.

**Why not fixed windows.** The first implementation sliced audio into 1.5 s windows with a
0.4 s overlap and glued the texts back together by matching repeated words. It kept the
latency claim and it cost accuracy three ways, and each of them showed on a handset as
"the text is wrong":

1. A window boundary lands wherever 1.5 s happens to fall — mid word — and a CTC model asked
   about half a word answers with a different word, or two.
2. Where the two windows read the shared 0.4 s differently, the join could not tell a
   revision from a new word and kept both, so a word appeared twice in two spellings.
3. IndicConformer normalises its features over the whole buffer it is given
   (`normalize_type = per_feature` in the model's own metadata). Statistics over 1.5 s that
   is half silence are not the statistics it was trained against; over a whole clause with
   a little quiet either side, they are.

Cutting in the pauses removes all three. Nothing straddles a cut made in 280 ms of quiet, so
the clause texts simply follow one another.

**Honest consequence:** end-to-end in push-to-talk mode is now budgeted at **800–1200 ms**,
not the 500–800 ms the design document claimed. That figure was derived from a streaming
recogniser that does not exist for these languages. See
[EVALUATION.md §4](EVALUATION.md#4-latency--20--of-the-mark).

#### Implementation

`UtteranceDecoder` in `core-asr`, task W3.13. The model is injected as a function, so the
segmentation is tested without one — against a scripted model that knows where every word
in a synthetic utterance really is, and reports a word it was asked about only half of. The
claims under test are properties of the cutting, not of the recogniser:

- **Every word once, whole.** A clause closed at a pause is read with no window beginning
  or ending inside a word, for any sequence of clauses and pauses.
- **A forced cut loses nothing.** A speaker who never pauses is cut at the quietest recent
  frame with 0.6 s of context both sides. sherpa-onnx reports the frame at which its CTC
  search emitted each token, and a word is owned by whichever side its emission time falls
  on — so the word on the cut is read once, by the side that heard all of it, rather than
  guessed from two conflicting texts.
- **A bounded tail.** A sweep of unbroken speech from 200 ms to 12 s checks that what is
  left to decode at the endpoint never exceeds one segment.
- **A finished clause costs the endpoint nothing.** When the speaker falls quiet for
  200 ms the open clause is read then and there; the pause cut and the endpoint both take
  that reading as final rather than decoding the clause again. An operator who lets go a
  moment after their last word waits for nothing.
- **Silence is never sent to the model.** A held control with nothing said into it, a
  click, and seven seconds of thought before a sentence all leave the model idle, and the
  sentence is decoded with 300 ms of its own quiet rather than the seven seconds.

**The two ends of the utterance** are handled in `SherpaSpeech`, because both are where
words go missing on a real handset and neither is the model's fault. The screen says
"listening" only once `AudioRecord` reports that it is recording — not when it was built,
tens of milliseconds earlier, which had the operator speaking the first syllable into
nothing. And the microphone stays open for 200 ms after the release, because an operator
lets go *on* the last word and the audio path has its own buffering besides; that is
latency spent on purpose, visible in band F, and the cheapest accuracy available.

## 4. Confidence

Every finalised hypothesis carries a confidence value, quantised to the two `CONFIDENCE`
bits in the frame header.

| Level | Wire value | Source | Consequence |
| --- | --- | --- | --- |
| Low | 0 | Mean token log-probability below the low threshold | Sender is warned; alert-class messages are blocked pending confirmation; `AUDIO_FB` offered on Wi-Fi |
| Medium | 1 | Between thresholds | Transmitted; displayed with a caution marker at the receiver |
| High | 2 | Above the high threshold | Normal path |
| Template-matched | 3 | Fuzzy match ≥ 0.85 **and** high confidence | Transmitted as a template code |

Thresholds are calibrated per language during week 7 against the benchmark set, and stored
in the pack manifest rather than hard-coded.

## 5. Noise pre-processing

Word error rate collapsing in real acoustic noise is the highest-severity technical risk
(T-01) because it destroys the largest single mark.

| Stage | Mechanism | Cost |
| --- | --- | --- |
| High-pass | 80 Hz single-pole | Negligible |
| Platform suppression | `NoiseSuppressor` / `AcousticEchoCanceler` when the device reports availability | Free, vendor-dependent |
| Adaptive gain | Target −18 dBFS, 3 s attack | Negligible |
| Neural suppression | RNNoise, ~200 KB | ~0.5 ms per 20 ms frame |

Neural suppression is applied **only to the recognition path**, never to any audio the
user hears, and is a per-language toggle because it is not uniformly beneficial. The
benchmark harness reports WER with it on and off at each SNR; if it does not help at a
given SNR for a given language, it is disabled for that pack. That decision is data, not
preference.

## 6. Model selection

| Candidate | Licence | Size, int8 | Assessment |
| --- | --- | --- | --- |
| AI4Bharat IndicConformer, NeMo-CTC | Permissive | **~120 MB int8, shared** | **Production choice.** ~120 M parameters, 493 MB as fp32 ONNX. **One multilingual model covers all ten languages** with a per-language vocabulary file — it is not 35 MB per language. **Offline, not streaming** |
| Vosk small models | Apache-2.0 | ~40 MB | **Week-one prototype, and the only genuinely streaming option available.** Android-ready today, lower accuracy. Schedule insurance, not a compromise |
| Whisper tiny / base | MIT | ~40–75 MB | Baseline for comparison only. Not streaming; weak on Odia and Kannada; slow on entry-tier silicon |
| IndicWhisper | Permissive | ~240 MB | Strong accuracy, too heavy for the target device. Useful as an accuracy ceiling reference |
| Meta MMS | CC-BY-NC ⚠ | ~300 MB | Broadest coverage; the non-commercial licence makes it unsuitable for any deployability claim. Gap-filler only |

Export and quantisation procedure is in [MODELS.md](MODELS.md).

## 7. Quantisation

Models are trained in 32-bit floating point, which is unnecessary for inference. Dynamic
quantisation rewrites the weights as 8-bit integers, exploiting the fact that ARM cores
execute integer arithmetic considerably faster than float.

| Precision | Size | Relative speed | WER impact | Verdict |
| --- | --- | --- | --- | --- |
| float32, as trained | ~493 MB | 1.0× | baseline | Unshippable |
| float16 | ~240 MB | 1.3× | ≈ 0 | Intermediate |
| **int8 dynamic** | **~120 MB** | **2.5×** | **+ ~1 % rel.** | **Shipped** |

Four times smaller, two and a half times faster, for approximately one per cent relative
degradation. This single transformation is what makes on-device speech viable on an
entry-tier handset, and the before-and-after table is direct evidence for the efficiency
criterion — put it in the deck.

## 8. Reference binding

```kotlin
// One multilingual model, constructed once at foreground-service start.
// OFFLINE, not OnlineRecognizer -- no streaming Indic model exists (see 3.5).
val recognizer = OfflineRecognizer(
    assetManager = assets,
    config = OfflineRecognizerConfig(
        featConfig  = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OfflineModelConfig(
            nemoCtc    = OfflineNemoEncDecCtcModelConfig("models/asr/indicconformer.int8.onnx"),
            tokens     = "models/asr/tokens.hi.txt",   // vocabulary is per language
            numThreads = 4,                            // 4 while decoding, 2 at idle
            provider   = "cpu"
        ),
        decodingMethod = "greedy_search",
        hotwordsFile   = "models/hi/alert-lexicon.txt",
        hotwordsScore  = 1.5f
    )
)

// Endpointing is ours, from the VAD tiers -- the model cannot do it.
// Windows are decoded while the speaker is still talking; only the tail waits.
vad.speechWindows().collect { window ->                 // 1.5 s, 0.4 s overlap
    partials += recognizer.createStream()
        .apply { acceptWaveform(window, 16000) }
        .let { recognizer.decode(it); it.result.text }
}
onEndpoint { tail ->                                     // 250-450 ms, not 900 ms
    val text = stitch(partials, decodeTail(tail))
    link.send(Frame.encode(text, lang = HI))             // always broadcast
    partials.clear()
}
```

## 9. Instrumentation

`core-asr` emits a timestamp at each of these boundaries. They become columns in
`latency.csv`.

| Symbol | Boundary |
| --- | --- |
| `tMic` | Sample captured by `AudioRecord` |
| `tVad` | Tier 1 declared speech |
| `tFirstPartial` | First partial hypothesis available |
| `tEndpoint` | Endpointer declared the utterance finished |
| `tFinal` | Final hypothesis available |

`tFinal − tEndpoint` is the "final decode after endpoint" budget of 100–250 ms.
`tEndpoint − tMic` contains the silence window and is the term that PTT mode shortens.
