# The speaking engine

Implemented by `core-tts`. Synthesis is scored on intelligibility and natural flow. Two of
the four stages below are ordinary software engineering rather than machine learning, and
they account for most of the perceived quality.

Pipeline: **normalise → phonemise → synthesise → stream**.

## 1. Text normalisation

No neural synthesiser handles raw digits, units or abbreviations correctly, and these are
exactly what operational messages contain. A per-language rule file runs before synthesis.

| Input | Naïve output | Correct output |
| --- | --- | --- |
| `112` | one hundred and twelve | एक · एक · दो — digit-wise, because it is a number to dial |
| `5 km` | five k m | पाँच किलोमीटर |
| `Sector 17` | sector seventeen | सेक्टर सत्रह |
| `14:30` | fourteen colon thirty | दोपहर दो बजकर तीस मिनट |

### The rule that does the work

Distinguishing a **quantity** from an **identifier** is a rule, not a model:

- A bare digit run in a dialling or callsign context — preceded by a callsign marker, or
  3 digits standing alone, or any run of 5 or more digits — is read **digit-wise**.
- A digit run followed by a unit token is read as a **number**, and the unit is expanded.
- A digit run in `HH:MM` shape is read as a **time**.
- Everything else is read as a number.

Cheap to implement, and it is the difference between a synthesiser that sounds
professional and one that sounds broken.

### Rule file format

One file per language, shipped in the pack as `normalise.json`. Ordered; first match wins.

```json
{
  "language": "hi",
  "version": 3,
  "rules": [
    { "id": "time-24h",  "pattern": "\\b(\\d{1,2}):(\\d{2})\\b", "kind": "time" },
    { "id": "unit-km",   "pattern": "\\b(\\d+)\\s*km\\b",        "kind": "quantity", "unit": "किलोमीटर" },
    { "id": "callsign",  "pattern": "\\b(\\d{3})\\b",            "kind": "digits" },
    { "id": "long-num",  "pattern": "\\b(\\d{5,})\\b",           "kind": "digits" },
    { "id": "cardinal",  "pattern": "\\b(\\d+)\\b",              "kind": "number" }
  ],
  "units":  { "km": "किलोमीटर", "m": "मीटर", "kg": "किलोग्राम" },
  "digits": ["शून्य", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ"]
}
```

Adding a language requires no code change — only a rule file. This is the same principle
as the language packs themselves.

#### Three extensions, added in week 7 (W7.2)

The sketch above is enough to read `112` digit-wise and nothing else. Ten real languages
needed three additions, each because a language demanded it. The implementation is
`core-tts` `NormaliseSpec`; the shipped files are `models/rules/normalise.<lang>.json`.

**`numerals`** — the number table moves into the file, in one of two shapes. Indo-Aryan
languages (`hi`, `bn`, `mr`, `gu`, `or`) use `"kind": "table"` and list all hundred values,
because Hindi's सत्रह and उनहत्तर are not composed from anything. English and the Dravidian
languages use `"kind": "composed"` and list zero to nineteen plus eight tens words, since
Telugu's ఇరవై ఒకటి is transparently *twenty one*. Tamil and Malayalam add `tensCombining`,
the form a tens word takes before a ones digit — இருபது becomes இருபத்தி.

Forcing one shape on both would cost either eight hundred hand-written words that can be
generated, or a generator emitting plausible non-words in five languages.

**`clock`** — a time is assembled from two template strings rather than a fixed order,
because *"दोपहर दो बजकर तीस मिनट"* and *"two thirty in the afternoon"* arrange the same four
parts differently. `{period}`, `{hour}` and `{minute}` are substituted; anything else is
literal.

**`{units}` in a pattern** — expanded from the `units` table below it, so a file cannot
list a unit its own regex will never reach. Symbols are tried longest-first, or every
kilometre is read as a metre.

Two smaller additions: `decimalPoint` gives the word for a grid reference's separator —
a full stop reaching the phonemiser is a sentence break, and 20.29 then arrives as two
unrelated numbers — and `reviewed` records whether a native speaker has checked the
numerals. It is `false` for eight of the ten languages, and the intelligibility panel
(W7.11) is what clears one.

### Regression suite

Normalisation correctness is a **100 % target**, not a best effort, because every failure
is audible and every failure is trivially reproducible in front of a jury. Each language
ships a fixture file of at least 60 cases covering numbers, times, dates, units,
callsigns, sector labels and coordinates, asserted in CI. Adding a rule without adding its
fixtures is a rejected review.

## 2. Phonemisation

Letters are not sounds. espeak-ng converts orthography to phoneme sequences — मदद to
`/m ə d ə d/` — which are then mapped to integer identifiers. Indic scripts are largely
phonetic, which makes this stage far more reliable for our ten languages than it would be
for English.

> **Licence note.** espeak-ng is **GPL-3.0**. This is acceptable for an open-source
> submission and is recorded in [LICENSES.md](../LICENSES.md), but it constrains any future
> closed-source derivative and MUST be disclosed rather than discovered. It is invoked as a
> data-driven phonemiser through sherpa-onnx, with its data directory shipped in the base
> installer (~10 MB, shared across all languages).

## 3. VITS synthesis

Earlier architectures required two models — text to spectrogram, then spectrogram to
waveform — and were too slow for phones. VITS performs both in a single pass:

1. A **text encoder** transforms phoneme identifiers into latent representations.
2. A **duration predictor** assigns a length to each phoneme. This is the stage that
   produces natural rhythm, and it is why VITS output does not sound metronomic.
3. A **flow-based decoder and vocoder** generates the waveform directly at 22 050 samples
   per second.

Measured real-time factor on entry-tier ARM is approximately **0.15** — one second of
speech synthesised in about 150 ms.

Output is resampled to the `AudioTrack` rate once, at pack load, rather than per
utterance.

## 4. Chunked synthesis and streaming playout

Synthesising a complete sentence and then playing it wastes the entire synthesis duration
as latency. Instead the sentence is split at clause boundaries; the first chunk is
synthesised and playback begins immediately while subsequent chunks are generated against
the playing clock.

```
 NAÏVE     │◄──────── synthesise whole sentence ────────►│◄─ play ─►│
           0                                          700 ms
           time to first audio: 700 ms

 CHUNKED   │◄ chunk 1 ►│◄ chunk 2 ►│◄ chunk 3 ►│
           0        180 ms
                       │◄──────────── play ─────────────►│
           time to first audio: 180 ms
```

Total synthesis work is unchanged; perceived latency falls by roughly **75 %**. Since the
rubric measures "time delay between the text received and audio processed and played",
this optimisation is directly worth marks.

### Chunking rules

> **Amended 2026-09-06.** The rules below replaced a 12-word cut at every conjunction.
> Each cut was synthesised as a sentence of its own — pitch falling to a full stop, a click
> at each end, a fresh `AudioTrack` per piece released before its tail had played — and a
> six-word message sounded like three announcements. That was most of what "sounds like a
> robot" meant, and none of it was the voice. `SpeechShaper`, `AudioPolish` and
> `VoiceProfile` in `core-tts` are the implementation.

| Rule | Value |
| --- | --- |
| Split points | Sentence marks (`.` `?` `!` `।` `॥`) and clause marks (`,` `;` `:`) only. A phrase keeps its mark, so a clause ending in a comma is synthesised with a continuation contour rather than a full stop |
| Unpunctuated runs | Left whole up to 24 words; beyond that, cut before a conjunction (in all ten languages) or by count, with a comma added so the voice does not end the phrase |
| Minimum phrase | 2 words — a lone word joins its neighbour across a clause mark, never across a sentence end |
| Terminal punctuation | Added when the message has none: the danda for Devanagari, Bengali and Odia, a full stop elsewhere. Recognised speech carries none, and text without any is read as an unfinished sentence |
| Speaker's pauses | The recogniser writes a comma where the speaker paused ≥ 280 ms, so the receiving voice pauses where the speaker did |
| Pauses | 170 ms of written silence after a clause, 380 ms after a sentence |
| Level and edges | Each phrase levelled to −20 dBFS RMS with a −1 dB peak ceiling and at most +14 dB of gain; 6 ms fades at both ends |
| Voice tuning | `noise_scale`, `noise_w` and `length_scale` from the voice's own `.onnx.json`, at 1.05× the tempo. Left at the library defaults, the English voice ran at the wrong tempo with the wrong variation |
| Playout | One `AudioTrack` per message, released only after the last sample has played |
| Underrun policy | Writes block; if synthesis falls behind, delivery stretches rather than tears. Never emit a gap mid-sentence |

An audible gap inside a sentence is worse than 100 ms of extra initial latency. The
scheduler prioritises continuity over time-to-first-audio once playback has started.

### Barge-in

In phone mode (R11), when tier 1 VAD declares local speech while playback is active, local
playback ducks to −18 dB within 100 ms and stops at the end of the current chunk. In PTT
mode the speaker is muted outright while the floor is held.

## 5. Voice selection

| Source | Licence | Per voice | Assessment |
| --- | --- | --- | --- |
| Piper voices | MIT | 20–60 MB | **Primary.** Already ONNX, fast, and covers nine of the ten targets |
| AI4Bharat Indic-TTS | Permissive | ~90 MB | Best Indic naturalness. Reserve for languages where Piper quality is inadequate; quantise aggressively |
| Coqui VITS, self-trained | MPL-2.0 | ~50 MB | Training path for gaps. **Odia is the expected gap**; IIT Madras IndicTTS data supports this |
| Meta MMS-TTS | CC-BY-NC ⚠ | ~40 MB | Stopgap only, owing to the non-commercial licence |
| Matcha-TTS | MIT | ~35 MB | Flow-matching alternative; benchmark against Piper for RTF before committing |

### Per-node voices

Speaker identity is discarded by the compression scheme, so it is reconstructed at the
receiver: each node identifier maps to a distinct speaker embedding of the multi-speaker
voice, so messages from different senders sound different. The mapping is
`sid = src % speakerCount`, stable for the life of a pairing, and shown alongside the display
name in the interface.

### Alert delivery profile

Alert traffic is synthesised with a harder, faster profile — `speed = 1.15`, and where the
voice supports it, a more forceful speaker embedding. Urgency is discarded on the wire and
reconstructed here, from the `ALERT` type rather than from any acoustic cue.

## 6. Reference binding

```kotlin
val tts = OfflineTts(assets, OfflineTtsConfig(
    model = OfflineTtsModelConfig(
        vits = OfflineTtsVitsModelConfig(
            model   = "models/hi/hi_IN-voice.onnx",
            tokens  = "models/hi/tokens.txt",
            dataDir = "espeak-ng-data"
        ),
        numThreads = 2
    )
))

// clause-level chunking: playback starts on the first chunk
normalise(received, lang).splitClauses().forEach { chunk ->
    val audio = tts.generate(chunk, sid = speakerFor(frame.src), speed = 1.0f)
    track.write(audio.samples, 0, audio.samples.size, WRITE_BLOCKING)
}
```

## 7. Instrumentation

| Symbol | Boundary |
| --- | --- |
| `tRx` | Frame accepted by `core-proto` |
| `tNorm` | Normalisation complete |
| `tChunk1` | First chunk synthesised |
| `tAudio` | First sample written to `AudioTrack` |
| `tDone` | Last sample written |

`tAudio − tRx` is the receive-side budget and MUST stay under 250 ms (R7).
`tDone − tAudio` divided by audio duration gives the synthesis RTF.
