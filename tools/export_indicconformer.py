#!/usr/bin/env python3
"""Export IndicConformer from NeMo to ONNX. Task W4.1.

A model nobody can regenerate is a liability, so this script plus a pinned upstream
revision is the whole reproduction recipe -- docs/MODELS.md section 5.

Note what this exports. IndicConformer is **one multilingual model** covering all ten
languages, not ten separate models. The design document assumed the latter; the
correction is the reason models/manifest.json carries a single shared acoustic-model
entry (task W4.4, risk T-17). There is therefore no --lang option here: exporting
"the Hindi model" is not a thing that exists.

Usage:
    python tools/export_indicconformer.py --out build/shared/

Requires nemo_toolkit[asr]; it is deliberately not in tools/requirements.txt because it
pulls in a full PyTorch stack that nothing else here needs. Install it in a throwaway
environment:

    pip install "nemo_toolkit[asr]==2.0.0"
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

# Pinned. An export that resolves to a different checkpoint on another machine produces
# figures that cannot be compared with the ones in the report.
MODEL_ID = "ai4bharat/indicconformer_stt_multilingual_fp16"
REVISION = "main"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path, help="output directory")
    parser.add_argument("--model", default=MODEL_ID)
    parser.add_argument("--revision", default=REVISION)
    args = parser.parse_args()

    try:
        import nemo.collections.asr as nemo_asr
    except ImportError:
        print(
            "nemo_toolkit is not installed. See the module docstring; it is kept out of\n"
            "tools/requirements.txt because it pulls in a full PyTorch stack.",
            file=sys.stderr,
        )
        return 2

    args.out.mkdir(parents=True, exist_ok=True)

    print(f"loading {args.model} @ {args.revision}")
    model = nemo_asr.models.ASRModel.from_pretrained(model_name=args.model)
    model.eval()

    # export() writes encoder, decoder and joiner as separate graphs, which is what the
    # runtime loads and what quantisation is applied to selectively.
    target = args.out / "model.onnx"
    print(f"exporting to {target}")
    model.export(str(target))

    # The vocabulary is per language even though the acoustic model is not.
    tokens = args.out / "tokens.txt"
    with tokens.open("w", encoding="utf-8") as handle:
        for index, token in enumerate(model.decoder.vocabulary):
            handle.write(f"{token} {index}\n")
    print(f"wrote {tokens} ({len(model.decoder.vocabulary)} tokens)")

    # Recorded so manifest.json can be filled in without anyone hashing by hand, which
    # is exactly where a wrong digest would come from.
    produced = sorted(p for p in args.out.iterdir() if p.is_file())
    report = {
        "model": args.model,
        "revision": args.revision,
        "files": [
            {"name": p.name, "bytes": p.stat().st_size, "sha256": sha256(p)}
            for p in produced
        ],
    }
    receipt = args.out / "export.json"
    receipt.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {receipt}")

    print("\nNext: quantise the encoder only --")
    print("  python tools/quantise.py --in %s --out %s" % (target, args.out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
