#!/usr/bin/env python3
"""Build ``models/install-index.json``: what a handset may be given, and how to know it.

The application has no ``INTERNET`` permission and will not get one — constraint **C2**.
So a phone that has never been plugged into a developer's machine cannot fetch its own
language pack, and until now that meant it could not have one at all.

It can fetch one with its **browser**, which is a different program with its own
permissions. The operator downloads the artefacts to `Download/`, and the application
imports them. That keeps every claim intact: nothing in this application ever opens a
socket, and the running system is offline exactly as before.

The problem that creates is identity. A file in `Download/` called ``model.int8.onnx``
could be any of ten languages, and ``tokens.txt`` could be either alphabet. Filenames do
not say. **SHA-256 does**, and this index is the mapping — hash to destination — so the
importer identifies each file by its content, installs it where it belongs, and rejects a
truncated download instead of installing silence.

The hashes come from Hugging Face's own Git-LFS object ids where the file is stored in
LFS, and from the bytes themselves where it is small enough to be stored inline.

    python tools/build_install_index.py
"""
from __future__ import annotations

import hashlib
import io
import json
import sys
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "models" / "manifest.json"
OUT = ROOT / "models" / "install-index.json"

PIPER = "https://huggingface.co/rhasspy/piper-voices/resolve/main"
PIPER_API = "https://huggingface.co/api/models/rhasspy/piper-voices/tree/main"

# The six languages with a permissively licensed voice. Tamil, Gujarati, Kannada and Odia
# have none -- LICENSES.md section 6 -- and ship recognise-only.
VOICES = {
    "hi": "hi/hi_IN/pratham/medium/hi_IN-pratham-medium",
    "bn": "bn/bn_BD/google/medium/bn_BD-google-medium",
    "mr": "mr/mr_IN/google/medium/mr_IN-google-medium",
    "te": "te/te_IN/maya/medium/te_IN-maya-medium",
    "ml": "ml/ml_IN/arjun/medium/ml_IN-arjun-medium",
    "en": "en/en_US/hfc_male/medium/en_US-hfc_male-medium",
}


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def lfs_index(path: str) -> dict[str, dict]:
    """@return name -> {sha256, bytes} for one directory of the Piper repository."""
    entries = json.loads(fetch(f"{PIPER_API}/{path}").decode("utf-8"))
    out = {}
    for entry in entries:
        lfs = entry.get("lfs") or {}
        out[entry["path"].split("/")[-1]] = {
            "sha256": lfs.get("oid"),
            "bytes": lfs.get("size") or entry.get("size") or 0,
        }
    return out


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    items = []

    # ── the recognisers, already hashed in the manifest ──────────────────────
    for pack in manifest["packs"]:
        asr = pack.get("asr")
        if not asr:
            continue
        for artefact in asr["artefacts"]:
            local = artefact["files"][0]
            remote = (artefact.get("remote") or artefact["files"])[0]
            items.append(
                {
                    "sha256": artefact["sha256"],
                    "bytes": artefact["bytes"],
                    "install": f"asr/{local}",
                    "url": f"{asr['baseUrl'].rstrip('/')}/{remote}",
                    "language": pack["lang"],
                    "kind": "recogniser",
                }
            )

    # ── the voices, hashed from Piper's own repository ───────────────────────
    for language, path in VOICES.items():
        directory, name = path.rsplit("/", 1)
        try:
            listing = lfs_index(directory)
        except Exception as error:  # noqa: BLE001 - a missing voice is reported, not fatal
            print(f"   {language}: could not index ({error})")
            continue

        model = listing.get(f"{name}.onnx")
        if model and model["sha256"]:
            items.append(
                {
                    "sha256": model["sha256"],
                    "bytes": model["bytes"],
                    "install": f"tts/{language}/model.onnx",
                    "url": f"{PIPER}/{path}.onnx",
                    "language": language,
                    "kind": "voice",
                }
            )

        # The config is small enough to be stored inline, so it carries no LFS id and is
        # hashed from its bytes. It is also what tokens.txt is generated from on the
        # handset -- see tools/piper_tokens.py, whose output this reproduces.
        config = fetch(f"{PIPER}/{path}.onnx.json")
        items.append(
            {
                "sha256": hashlib.sha256(config).hexdigest(),
                "bytes": len(config),
                "install": f"tts/{language}/config.json",
                "url": f"{PIPER}/{path}.onnx.json",
                "language": language,
                "kind": "voice tokens",
            }
        )

    index = {
        "indexVersion": 1,
        "note": (
            "Downloaded by the operator's browser, imported and verified by the "
            "application. Nothing here is fetched by the application itself: it holds no "
            "INTERNET permission, per constraint C2."
        ),
        "items": items,
    }
    io.open(OUT, "w", encoding="utf-8", newline="\n").write(
        json.dumps(index, indent=2, ensure_ascii=False) + "\n"
    )

    kinds: dict[str, int] = {}
    for item in items:
        kinds[item["kind"]] = kinds.get(item["kind"], 0) + 1
    print(f"wrote {OUT.relative_to(ROOT)}: {len(items)} artefacts")
    for kind, count in sorted(kinds.items()):
        print(f"   {count:2d} {kind}")
    missing = [i["install"] for i in items if not i["sha256"]]
    if missing:
        print("::error::no hash for:", ", ".join(missing))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
