#!/usr/bin/env python3
"""Prove that int8 quantisation did not cost accuracy. Task W4.3.

**Mandatory, and per language.** The efficiency argument of this project rests on the
claim that quantisation costs about one per cent relative WER. That claim must be
re-established for every language, not assumed from one -- a shared multilingual model
does not degrade uniformly across scripts, and Odia is not Hindi.

Exit status is 1 if the regression exceeds the threshold, so this can gate a release
rather than merely print a number.

Usage:
    python tools/verify_quantisation.py --ref build/shared/encoder.onnx \\
        --test build/shared/encoder.int8.onnx --corpus data/kathbath/hi --lang hi
"""
from __future__ import annotations

import argparse
import json
import sys
import unicodedata
from pathlib import Path

# docs/EVALUATION.md: the quantised model must stay within this of the float32 model,
# measured as a relative change in WER.
MAX_RELATIVE_REGRESSION = 0.015

PUNCTUATION = set(",.;:!?\"'()[]{}-–—…«»‘’“”।॥")


def tokenise(text: str) -> list[str]:
    """The same rules as the Kotlin WerScorer -- NFC, strip punctuation, fold, collapse.

    These two implementations must agree. Where they disagree, the Kotlin one is
    authoritative: it is the one that produces the figures in the report.
    """
    text = unicodedata.normalize("NFC", text)
    text = "".join(c for c in text if c not in PUNCTUATION)
    return [w.lower() for w in text.split() if w]


def wer(reference: str, hypothesis: str) -> tuple[int, int]:
    """Levenshtein distance over words. Returns (errors, reference_length)."""
    ref, hyp = tokenise(reference), tokenise(hypothesis)
    if not ref:
        return len(hyp), 0

    previous = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, start=1):
        current = [i]
        for j, h in enumerate(hyp, start=1):
            current.append(
                min(
                    previous[j - 1] + (r != h),  # substitute
                    previous[j] + 1,             # delete
                    current[j - 1] + 1,          # insert
                )
            )
        previous = current
    return previous[-1], len(ref)


def transcribe(model_path: Path, corpus: Path) -> dict[str, str]:
    """Runs the model over the corpus. Requires sherpa-onnx."""
    try:
        import sherpa_onnx
    except ImportError:
        print(
            "sherpa-onnx is not installed: pip install sherpa-onnx\n"
            "(the Python package IS on PyPI; only the Android AAR is not on Maven Central)",
            file=sys.stderr,
        )
        raise SystemExit(2)

    recogniser = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=str(model_path),
        decoder=str(model_path.parent / "decoder.onnx"),
        joiner=str(model_path.parent / "joiner.onnx"),
        tokens=str(model_path.parent / "tokens.txt"),
        num_threads=4,
    )

    out: dict[str, str] = {}
    for wav in sorted(corpus.glob("*.wav")):
        stream = recogniser.create_stream()
        import wave

        with wave.open(str(wav), "rb") as handle:
            frames = handle.readframes(handle.getnframes())
            rate = handle.getframerate()
        import array

        samples = array.array("h", frames)
        stream.accept_waveform(rate, [s / 32768.0 for s in samples])
        recogniser.decode_stream(stream)
        out[wav.stem] = stream.result.text
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True, type=Path, help="float32 encoder")
    parser.add_argument("--test", required=True, type=Path, help="int8 encoder")
    parser.add_argument("--corpus", required=True, type=Path, help="wavs + transcript.json")
    parser.add_argument("--lang", required=True)
    parser.add_argument("--threshold", type=float, default=MAX_RELATIVE_REGRESSION)
    args = parser.parse_args()

    truth_file = args.corpus / "transcript.json"
    if not truth_file.is_file():
        print(f"no transcript.json in {args.corpus}", file=sys.stderr)
        return 1
    truth = json.loads(truth_file.read_text(encoding="utf-8"))

    results = {}
    for label, model in (("float32", args.ref), ("int8", args.test)):
        print(f"transcribing with {label}: {model}")
        hypotheses = transcribe(model, args.corpus)
        errors = length = 0
        for key, reference in truth.items():
            e, n = wer(reference, hypotheses.get(key, ""))
            errors += e
            length += n
        results[label] = errors / length if length else 0.0
        print(f"  WER {results[label] * 100:.2f} %  over {length} words")

    base = results["float32"]
    quantised = results["int8"]
    relative = (quantised - base) / base if base else 0.0

    print(f"\n{args.lang}:")
    print(f"  float32 WER      {base * 100:6.2f} %")
    print(f"  int8 WER         {quantised * 100:6.2f} %")
    print(f"  relative change  {relative * 100:+6.2f} %  (limit {args.threshold * 100:.1f} %)")

    if relative > args.threshold:
        print(f"\nFAILED: quantisation cost too much accuracy for {args.lang}.", file=sys.stderr)
        print("This model must not ship. Options: quantise fewer layers, or keep", file=sys.stderr)
        print("float32 for this language and accept the size.", file=sys.stderr)
        return 1

    print("\nPASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
