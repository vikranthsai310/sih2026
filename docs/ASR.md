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
times. This is 8 000 samples, 16 kB, permanently allocated at service start.

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
| Roster | Display names of nodes in the current group, injected at runtime | ≤ 254 terms |

Hotword score 1.5 by default; the value is a per-language tunable recorded in the pack
manifest.

Negation terms are in the list because of risk S-03: a recognition error that turns "do
not evacuate" into "now evacuate" inverts meaning and is the most dangerous single failure
this system can produce.

> **Demonstration note.** Decoding the same audio with biasing off and then on is one of
> the strongest live comparisons available to us. It takes fifteen seconds and it shows a
> measurable, explainable accuracy improvement that the team engineered deliberately.

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
| AI4Bharat IndicConformer | Permissive | ~35 MB | **Production choice.** Trained specifically on Indian languages; covers all ten targets. Requires NeMo → ONNX export |
| Vosk small models | Apache-2.0 | ~40 MB | **Week-one prototype.** Streaming, Android-ready today, lower accuracy. This is schedule insurance, not a compromise |
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
| float32, as trained | ~120 MB | 1.0× | baseline | Unshippable |
| float16 | ~60 MB | 1.3× | ≈ 0 | Intermediate |
| **int8 dynamic** | **~32 MB** | **2.5×** | **+ ~1 % rel.** | **Shipped** |

Four times smaller, two and a half times faster, for approximately one per cent relative
degradation. This single transformation is what makes on-device speech viable on an
entry-tier handset, and the before-and-after table is direct evidence for the efficiency
criterion — put it in the deck.

## 8. Reference binding

```kotlin
// constructed once, at foreground-service start
val recognizer = OnlineRecognizer(
    assetManager = assets,
    config = OnlineRecognizerConfig(
        featConfig  = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "models/hi/encoder.int8.onnx",
                decoder = "models/hi/decoder.onnx",
                joiner  = "models/hi/joiner.onnx"
            ),
            tokens     = "models/hi/tokens.txt",
            numThreads = 2,
            provider   = "cpu"
        ),
        enableEndpoint = true,
        endpointConfig = EndpointConfig(),
        decodingMethod = "modified_beam_search",
        hotwordsFile   = "models/hi/alert-lexicon.txt",
        hotwordsScore  = 1.5f
    )
)

// per 100 ms of captured audio
stream.acceptWaveform(samples, 16000)
while (recognizer.isReady(stream)) recognizer.decode(stream)
val text = recognizer.getResult(stream).text
if (recognizer.isEndpoint(stream)) {
    link.send(Frame.encode(text, lang = HI, dst = BROADCAST))
    recognizer.reset(stream)
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
