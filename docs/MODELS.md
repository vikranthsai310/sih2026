# Models and language packs

Implemented by `core-models`. Shipping ten languages in the installer would produce a
package of roughly seven hundred megabytes and forfeit the efficiency criterion. Assets are
therefore modular.

## 0. Where the recogniser's models actually come from

Written after the fact, because what is publishable today is not what this document was
planned around.

**Source:** `parismitaglobalsolutions/indicconformer-sherpa-onnx` on Hugging Face,
**Apache-2.0** — AI4Bharat's IndicConformer exported to sherpa-onnx form and quantised to
int8. `models/manifest.json` now carries the real URL, byte count and SHA-256 for every
language, taken from the repository's own Git-LFS object ids and, for the files small enough
to be stored inline, computed from an actual download. `tools/fetch_models.py` no longer
refuses to run.

**One model per language, not a shared encoder.** Section 1 below assumes a single shared
acoustic model with a small vocabulary per language. That is the better design and it is
not what exists: the only multilingual export published
(`ai4bharat/indic-conformer-600m-multilingual`) stores its encoder weights as several
hundred external-data blobs, which neither the fetcher nor sherpa-onnx loads directly. What
does exist is a self-contained ~189 MB int8 model per language sharing one multilingual
5,633-token table.

The `shared` block used to be retained with a note saying it described the intention rather
than the artefact, and an all-zero hash. **Removed 2026-09-06.** A manifest entry that
describes an intention is not a manifest entry: `Manifest.shared` is nullable and the
shipped manifest declares `null`. The recogniser each pack really fetches is now modelled
properly — `Pack.asr`, which the schema had been silently discarding through
`ignoreUnknownKeys`, so `Pack.totalBytes` was missing the ~197 MB that dominates a language
download and only looked plausible because the fictional shared model was being added in
its place.

**Ten of ten, from two publishers.** The sherpa-onnx conversion above covers nine
languages and has no Odia export. `OpenVoiceOS/ai4bharat-indicconformer-or-onnx` (**MIT**)
does: the same AI4Bharat model, declaring `model_type: "nemo-conformer-ctc"` with 80
features and the same `token id` vocabulary format, so it loads through the identical
`OfflineNemoEncDecCtcModelConfig` rather than needing a special case. It publishes its
vocabulary as `vocab.txt` and it is installed as `tokens.txt`, which is why manifest
artefacts may carry a `remote` name distinct from the installed one.

Two publishers for one language set is worth stating rather than hiding: the Odia weights
were converted by someone else, and the `source` field in each pack's `asr` block records
which. Both are AI4Bharat's IndicConformer underneath, both permissively licensed.

**Voices, checked rather than assumed.** Piper publishes voices for Hindi, Marathi,
Malayalam, Telugu, Bengali and English, and none for **Tamil, Gujarati, Kannada or Odia** —
verified against the `rhasspy/piper-voices` repository tree, which has no `ta`, `gu`, `kn`
or `or` directory at all.

**Piper is not the only permissive family, and Gujarati proves it.** Re-checked 2026-09-06
across every family sherpa-onnx packages — 642 published artefacts spanning VITS, Piper,
Mimic 3, Coqui, Matcha and Kokoro. Exactly one covers a language Piper misses: a **Mimic 3
VITS voice for Gujarati**, trained on **CMU Indic**, whose licence grants permission "to
use, copy, modify, and license this software and its documentation for any purpose ...
without fee". That is permissive and commercially usable, so Gujarati speaks and the count
is **seven of ten**, not six.

It is not shaped like a Piper voice. A Piper voice is a `.onnx` plus a `.onnx.json` holding
a `phoneme_id_map`, which the handset converts into sherpa's `tokens.txt` on import; this
one's `.onnx.json` is a *training* config with no such map, and the conversion would fail.
sherpa-onnx publishes a ready-made `tokens.txt` beside it, so that is installed verbatim —
`tools/build_install_index.py` emits it under `tts/gu/tokens.txt` rather than
`config.json`, which is precisely what the importer keys the conversion on. Two properties
were checked before it was adopted: the ONNX metadata carries `sample_rate` (22 050), which
`ModelStore.isLoadableVoice` requires and without which sherpa calls `exit(-1)`; and
`gu_dict` is in the pruned `espeak-ng-data.zip`, which it needs because the model declares
`has_espeak`.

**Tamil, Kannada and Odia remain silent, and this was searched properly.** Nothing in any
family sherpa-onnx packages covers them. The two Apache-2.0 models that *do* — AI4Bharat's
`indic-parler-tts` (880M parameters) and `kenpath/svara-tts-v1` (3B, Llama-3.2-based) —
have no ONNX export and would not fit an entry-tier 4 GB handset if they did. espeak-ng can
speak all three and is already statically linked into `libsherpa-onnx-jni.so`, but it is
linked as a *phonemiser*: no `espeak_Synth` or any other espeak entry point is exported
from the shipped library, so it cannot be called without rebuilding sherpa-onnx from
source. Risk T-05 stays open for three languages instead of four.

**What is not used.** Android's `SpeechRecognizer`. It works, it is offline, and
`REQUIREMENTS.md` constraint **C1** rules it out by name: on this handset it is served by
Google Speech Services, and ISRO prohibits "proprietary, closed-source, or commercial
voice-activation SDKs". It was briefly wired into push-to-talk and has been removed. The
40 % Accuracy criterion is measured as word error rate, so a build recognising through
Google would have had a jury measuring Google's model rather than this one's.

## 1. Delivery

| Component | Size | Delivery |
| --- | --- | --- |
| Base application, ONNX runtime, Silero VAD, espeak-ng data | ~25 MB | Installer |
| **Acoustic model — one multilingual model, all ten languages** | **~120 MB int8** | Fetched once at setup. **Not per-language**: IndicConformer is a single ~120 M-parameter model with a per-language vocabulary file |
| Synthesis voice, per language | 20–60 MB | On demand, once, at setup; verified by checksum |
| Template and gazetteer tables, all ten languages | < 1 MB | Installer |

> **Corrected after verification.** The design document assumed ten independent
> 35 MB acoustic models, 350 MB in total. The published IndicConformer ONNX is a single
> multilingual model of ~493 MB in float32, which quantises to roughly 120 MB — larger per
> download than assumed, but a third of the assumed total, and it covers every language at
> once. The consequence is that **the acoustic model cannot be delivered per language**; the
> on-demand story applies to synthesis voices only.

**Runtime operation is unconditionally offline.** Downloads occur only during setup, only
on explicit user action, and the running system makes no network call ever. The application
itself never fetches a pack: it hands an address to `ACTION_VIEW` and the operator's browser
fetches under its own permissions, after which the file is imported from local storage and
verified here by SHA-256.

> The shipped manifest **does** declare `android.permission.INTERNET`, added 2026-09-06 for
> the Wi-Fi broadcast transport — Android requires it to open any socket, including one that
> only ever addresses a broadcast address. This paragraph used to rest the offline claim on
> that permission's absence. It now rests on inspection: no HTTP client anywhere in
> `src/main`, no hostname resolved, no outbound network connection opened. Sideloading
> packs from local storage or a USB drive remains supported, and is the recommended path
> for a venue with no connectivity at all.

## 2. Pack contents

A pack is a manifest entry plus four files. **Adding a language requires no code change.**

```
models/
  manifest.json
  hi/
    encoder.int8.onnx      acoustic model, quantised
    decoder.onnx
    joiner.onnx
    tokens.txt             sub-word token table, ~1000 entries
    voice.onnx             VITS synthesis voice
    normalise.json         text normalisation rules  (see TTS.md §1)
    alert-lexicon.txt      biasing phrase list       (see ASR.md §3.4)
    templates.json         template table for this language
    checksums.txt
```

## 3. Manifest schema

`models/manifest.json` is tracked in version control; the binaries it points at are not.

**The acoustic model is one shared entry, not one per language.** This is the schema
consequence of the correction in section 1, and getting it the other way round would mean
1.2 GB of downloads for something a device needs once — risk T-17.

```json
{
  "manifestVersion": 1,
  "shared": {
    "family": "IndicConformer",
    "licence": "Permissive",
    "files": ["encoder.int8.onnx", "decoder.onnx", "joiner.onnx"],
    "bytes": 125829120,
    "sha256": "…",
    "numThreads": 2
  },
  "packs": [
    {
      "lang": "hi",
      "index": 1,
      "displayName": "हिन्दी",
      "script": "Devanagari",
      "blockBase": "U+0900",
      "vocabulary": {
        "files": ["tokens.hi.txt", "alert-lexicon.hi.txt"],
        "bytes": 262144,
        "sha256": "…"
      },
      "tts": {
        "family": "Piper",
        "licence": "MIT",
        "files": ["hi_IN-pratham-medium.onnx", "hi_IN-pratham-medium.onnx.json"],
        "bytes": 63963136,
        "sha256": "…",
        "sampleRate": 22050
      },
      "rules": { "files": ["normalise.hi.json"], "version": 3 },
      "confidenceLow": -1.8,
      "confidenceHigh": -0.6
    }
  ]
}
```

`confidenceLow` and `confidenceHigh` are calibrated per language in week 7 and live in the
manifest rather than in code, because a threshold that is right for Hindi is not right for
Odia (task W4.16).

**`"tts": null` is a valid and expected value.** Three languages — Tamil, Kannada and Odia
— have no permissively licensed voice in any family, re-verified 2026-09-06. Such a pack is
still offered: it recognises and it displays, it simply cannot speak. Risk T-05.

Gujarati carried `"tts": null` until 2026-09-06 and no longer does. Its block names a
family other than Piper, which is why nothing may assume `tts.family == "Piper"` —
`ManifestTest` asserts exactly that.

Every hash is validated on parse: 64 lowercase hexadecimal characters **and not all
zeros**, or the manifest is refused. A placeholder that fails at install time instead looks
like a corrupt download rather than a bad manifest, and by then the bytes are already on
disk.

The all-zeros clause was missing until 2026-09-06, and the omission was invisible because
the guard's own comment claimed it: sixty-four zeros are sixty-four lowercase hex
characters, so every placeholder passed the check written to stop them, and seventeen of
them shipped. `tools/build_manifest_hashes.py` fills them from the bytes they name and
`--verify` fails a build where the manifest and the files have drifted apart.

## 4. Lifecycle

| Operation | Behaviour |
| --- | --- |
| Install | Download to a temp file, verify SHA-256 against the manifest, then atomically rename into place. A partially written pack MUST never be loadable |
| Resume | Range requests; a partial download resumes rather than restarting. A 60 MB pack over a weak connection will be interrupted |
| Verify | On every service start, cheaply: file size and mtime. Full SHA-256 on demand and after any crash during install |
| Load | At service start for the active language only. One acoustic model and one voice resident at a time |
| Switch | Unload outgoing, load incoming. Blocked while the floor is held. The only runtime operation permitted to exceed one second |
| Delete | User-initiated, with a storage accounting screen showing per-pack size |
| Corrupt | Checksum failure marks the pack invalid, offers re-download, and falls back to the base packs. It never crashes and never loads a partial model |

## 5. Export and quantisation pipeline

Scripts live in `tools/`. Every exported model is reproducible from a checked-in script
plus a pinned upstream revision — a model nobody can regenerate is a liability.

### ASR: NeMo → ONNX → int8

```bash
# 1. Export from NeMo to ONNX
python tools/export_indicconformer.py --lang hi --out build/hi/

# 2. Pre-process for quantisation
python -m onnxruntime.quantization.preprocess \
    --input build/hi/encoder.onnx --output build/hi/encoder.prep.onnx

# 3. Dynamic int8 quantisation
python - <<'PY'
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic("build/hi/encoder.prep.onnx",
                 "build/hi/encoder.int8.onnx",
                 weight_type=QuantType.QInt8)
PY

# 4. Verify: WER delta against the float32 model must be < 1.5 % relative
python tools/verify_quantisation.py --lang hi --ref build/hi/encoder.onnx \
                                    --test build/hi/encoder.int8.onnx
```

Step 4 is mandatory. A quantised model that has not been re-benchmarked is an unmeasured
regression, and the whole efficiency argument rests on the claim that quantisation costs
~1 % relative WER. That claim must be re-established per language, not assumed.

Only the encoder is quantised. The decoder and joiner are small and quantising them
measurably hurts accuracy for no meaningful size saving.

### Risk T-07 — export failure

NeMo → ONNX export of IndicConformer may fail or degrade accuracy. **Vosk models integrated
in week 1 keep the schedule intact regardless.** Export is attempted in week 4 with three
weeks of slack behind it. If it fails outright, the system ships on Vosk with lower
accuracy and the report says so.

### TTS: Piper and the Odia gap

Piper voices are already ONNX and need no export. The expected gap is **Odia** (risk T-05),
assigned in week 4, not week 7. Fallbacks in order:

1. Piper, if a community voice appears
2. Meta MMS-TTS — works, but CC-BY-NC, so it is disclosed and excluded from any
   deployability claim
3. Coqui VITS trained on the IIT Madras IndicTTS Odia data — the real answer, ~2 GPU-days

## 6. Licence obligations by pack

Every pack carries its licence in the manifest, and the application's about screen renders
it. The obligations that actually bind:

| Component | Licence | Obligation |
| --- | --- | --- |
| IndicConformer, Indic-TTS | Permissive | Attribution |
| Piper voices | MIT | Attribution |
| Vosk | Apache-2.0 | Attribution, NOTICE |
| Coqui-trained voice | MPL-2.0 | Source of modified files |
| espeak-ng data | GPL-3.0 ⚠ | Copyleft — see [LICENSES.md](../LICENSES.md) |
| Meta MMS | CC-BY-NC ⚠ | **Non-commercial.** Any pack using MMS is marked non-commercial in the manifest, and the UI shows it |

A pack whose licence field is missing MUST fail verification and MUST NOT load. Licence
metadata is not documentation here; it is a load-time precondition.

## 7. Storage accounting

The settings screen shows, per pack: language, size, licence, install date, and a delete
control; plus total used and device free space. A user who cannot see where 400 MB went
will uninstall the application, and a jury that asks "how big is it really" gets an answer
on screen rather than an estimate.
