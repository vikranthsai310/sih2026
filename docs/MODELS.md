# Models and language packs

Implemented by `core-models`. Shipping ten languages in the installer would produce a
package of roughly seven hundred megabytes and forfeit the efficiency criterion. Assets are
therefore modular.

## 1. Delivery

| Component | Size | Delivery |
| --- | --- | --- |
| Base application, ONNX runtime, Silero VAD, espeak-ng data | ~25 MB | Installer |
| Hindi and English packs | ~120 MB | Installer — usable immediately, offline, out of the box |
| Each additional language | ~60 MB | On demand, **once**, at setup; verified by checksum |
| Template and gazetteer tables, all ten languages | < 1 MB | Installer |

**Runtime operation is unconditionally offline.** Downloads occur only during setup, only
on explicit user action, and the running system makes no network call ever. The shipped
manifest declares no `INTERNET` permission; the pack downloader lives in a separate,
optional setup module that is the only component permitted to.

> If keeping `INTERNET` out of the shipped manifest proves impossible for the download
> path, the fallback is sideloading packs from local storage or a USB drive, which keeps
> the strong claim intact. The strong claim is worth more than the convenience.

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

```json
{
  "manifestVersion": 1,
  "packs": [
    {
      "lang": "hi",
      "index": 1,
      "displayName": "हिन्दी",
      "script": "Devanagari",
      "blockBase": "U+0900",
      "asr": {
        "family": "IndicConformer",
        "licence": "Permissive",
        "files": ["encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"],
        "bytes": 34603008,
        "sha256": "…",
        "beamSize": 4,
        "numThreads": 2,
        "hotwordsScore": 1.5,
        "confidenceLow": -1.8,
        "confidenceHigh": -0.6
      },
      "tts": {
        "family": "Piper",
        "licence": "MIT",
        "files": ["voice.onnx"],
        "bytes": 26214400,
        "sha256": "…",
        "sampleRate": 22050,
        "speakerCount": 4
      },
      "rules": { "files": ["normalise.json"], "version": 3 },
      "totalBytes": 62914560
    }
  ]
}
```

`confidenceLow` and `confidenceHigh` are calibrated per language in week 7 and live in the
manifest rather than in code, because a threshold that is right for Hindi is not right for
Odia.

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
