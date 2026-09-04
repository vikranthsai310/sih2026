#!/usr/bin/env python3
"""Dynamic int8 quantisation of the acoustic model. Task W4.2.

**Encoder only.** Quantising the decoder and joiner costs accuracy for no meaningful
size gain -- they are a few per cent of the parameters, and they are the parts whose
precision the output is most sensitive to. docs/MODELS.md section 5.

The quantised model is not usable until verify_quantisation.py has passed. A quantised
model that has not been re-benchmarked is an unmeasured regression, and the whole
efficiency argument of this project rests on the claim that quantisation costs about
one per cent relative WER.

Usage:
    python tools/quantise.py --in build/shared/encoder.onnx --out build/shared/
"""
from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def megabytes(path: Path) -> float:
    return path.stat().st_size / (1024 * 1024)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--in", dest="source", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic
        from onnxruntime.quantization.preprocess import quant_pre_process
    except ImportError:
        print("onnxruntime is not installed: pip install -r tools/requirements.txt", file=sys.stderr)
        return 2

    if not args.source.is_file():
        print(f"no such file: {args.source}", file=sys.stderr)
        return 1

    args.out.mkdir(parents=True, exist_ok=True)
    prepared = args.out / "encoder.prep.onnx"
    quantised = args.out / "encoder.int8.onnx"

    # Pre-processing folds constants and runs shape inference. Skipping it produces a
    # model that quantises but runs measurably slower.
    print(f"pre-processing {args.source}")
    quant_pre_process(str(args.source), str(prepared))

    print(f"quantising to int8 -> {quantised}")
    quantize_dynamic(str(prepared), str(quantised), weight_type=QuantType.QInt8)

    prepared.unlink(missing_ok=True)

    before = megabytes(args.source)
    after = megabytes(quantised)
    print(f"\n  float32  {before:8.1f} MB")
    print(f"  int8     {after:8.1f} MB   ({before / after:.1f}x smaller)")
    print(f"  sha256   {sha256(quantised)}")

    print("\nThis model is NOT usable yet. Verification is mandatory:")
    print(f"  python tools/verify_quantisation.py --ref {args.source} --test {quantised} \\")
    print("      --corpus data/kathbath/hi --lang hi")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
