#!/usr/bin/env python3
"""Generate a sherpa-onnx ``tokens.txt`` from a Piper voice's ``.onnx.json``.

sherpa-onnx publishes ready-made archives for a handful of Piper voices and not for the
rest. The rest are perfectly usable: a Piper voice is a ``.onnx`` plus a ``.onnx.json``
holding, among other things, a ``phoneme_id_map``. sherpa's ``tokens.txt`` **is** that map,
one ``<phoneme> <id>`` per line, in the order the JSON lists them.

Sorting the lines by id looks tidier and is wrong -- it is what this script did first, and
the file it produced differed from sherpa's on the very first line. The check below is the
reason to trust the output: run against a voice sherpa *has* packaged, it reproduces
sherpa's own file byte for byte.

    python tools/piper_tokens.py voice.onnx.json --out tokens.txt
    python tools/piper_tokens.py voice.onnx.json --verify-against models/tts/hi/tokens.txt

Multi-id phonemes are refused rather than silently truncated: every entry in every Piper
voice seen so far maps to exactly one id, and an entry that does not is a format this
script has not been shown to understand.
"""
from __future__ import annotations

import argparse
import io
import json
import sys
from pathlib import Path


def tokens_for(config: dict) -> str:
    mapping = config.get("phoneme_id_map")
    if not mapping:
        sys.exit("::error::no phoneme_id_map in this config; not a Piper voice?")

    lines = []
    for phoneme, ids in mapping.items():
        if len(ids) != 1:
            sys.exit(f"::error::{phoneme!r} maps to {ids}, which this script does not handle")
        lines.append(f"{phoneme} {ids[0]}")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", help="the voice's .onnx.json")
    parser.add_argument("--out", help="where to write tokens.txt")
    parser.add_argument("--verify-against", help="a tokens.txt sherpa-onnx published")
    args = parser.parse_args()

    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    tokens = tokens_for(config)

    if args.verify_against:
        known = Path(args.verify_against).read_text(encoding="utf-8")
        if tokens != known:
            print("::error::generated tokens differ from the published file")
            for n, (a, b) in enumerate(zip(tokens.splitlines(), known.splitlines())):
                if a != b:
                    print(f"   first difference at line {n}: {a!r} vs {b!r}")
                    break
            return 1
        print(f"identical to {args.verify_against} ({tokens.count(chr(10))} tokens)")

    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        io.open(out, "w", encoding="utf-8", newline="\n").write(tokens)
        print(f"wrote {out} ({tokens.count(chr(10))} tokens)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
