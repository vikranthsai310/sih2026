#!/usr/bin/env python3
"""Build ``models/install-index.json``: what a handset may be given, and how to know it.

The application never fetches a language pack — constraint **C2**. It contains no HTTP
client, resolves no hostname, and opens no outbound network connection. So a phone that has never
been plugged into a developer's machine cannot fetch its own pack, and until now that meant
it could not have one at all.

It can fetch one with its **browser**, which is a different program with its own
permissions. The operator downloads the artefacts to `Download/`, and the application
imports them. The running system is offline exactly as before.

(The application does declare ``android.permission.INTERNET``: Android requires it to open
the Wi-Fi transport's broadcast socket. The offline claim therefore rests on what the code
does, not on the permission list — see ``docs/SECURITY.md`` audit item 3.)

The problem that creates is identity. A file in `Download/` called ``model.int8.onnx``
could be any of ten languages, and ``tokens.txt`` could be either alphabet. Filenames do
not say. **SHA-256 does**, and this index is the mapping — hash to destination — so the
importer identifies each file by its content, installs it where it belongs, and rejects a
truncated download instead of installing silence.

The hashes come from Hugging Face's own Git-LFS object ids where the file is stored in
LFS, and from the bytes themselves where it is small enough to be stored inline.

**This is stage 1 of two, and the index is not shippable after it.** Stage 2 bundles every
artefact at or under 512 kB into the installer -- Hugging Face serves those inline and a
browser displays them rather than saving them, so an operator cannot download one -- and
appends ``?download=true`` to the large binaries, which is what makes a browser save those.
Running this tool alone silently drops both, and ``check_install_index.py`` exists to catch
exactly that:

    python tools/build_install_index.py       # 1. hashes and addresses  <- you are here
    python tools/build_small_artefacts.py     # 2. bundling and ?download=true
    python tools/check_install_index.py       # 3. refuses a half-built index
"""
from __future__ import annotations

import hashlib
import io
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "models" / "manifest.json"
OUT = ROOT / "models" / "install-index.json"

PIPER = "https://huggingface.co/rhasspy/piper-voices/resolve/main"
PIPER_API = "https://huggingface.co/api/models/rhasspy/piper-voices/tree/main"

# The Piper voices. Piper has no Gujarati at all -- `rhasspy/piper-voices` carries no `gu`
# directory -- so Gujarati comes from a different family entirely, below. Tamil, Kannada and
# Odia have no permissively licensed voice in any family and ship recognise-only:
# LICENSES.md section 6 records what was looked at and refused.
VOICES = {
    "hi": "hi/hi_IN/pratham/medium/hi_IN-pratham-medium",
    "bn": "bn/bn_BD/google/medium/bn_BD-google-medium",
    "mr": "mr/mr_IN/google/medium/mr_IN-google-medium",
    "te": "te/te_IN/maya/medium/te_IN-maya-medium",
    "ml": "ml/ml_IN/arjun/medium/ml_IN-arjun-medium",
    "en": "en/en_US/hfc_male/medium/en_US-hfc_male-medium",
}


# Voices that are not Piper, and therefore not shaped like one.
#
# A Piper voice is a `.onnx` plus a `.onnx.json` carrying a `phoneme_id_map`, which the
# handset turns into sherpa's `tokens.txt` on import. This one is a Mimic 3 VITS voice: its
# `.onnx.json` is a *training* config with no `phoneme_id_map` in it, and the conversion
# would fail. sherpa-onnx publishes a ready-made `tokens.txt` beside the model, so that is
# installed as it comes and no conversion is attempted -- `install` ends in `tokens.txt`
# rather than `config.json`, which is exactly what the importer keys on.
OTHER_VOICES = {
    "gu": {
        "base": (
            "https://huggingface.co/csukuangfj/"
            "vits-mimic3-gu_IN-cmu-indic_low/resolve/main"
        ),
        "model": "gu_IN-cmu-indic_low.onnx",
        "tokens": "tokens.txt",
    },
}


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Stops at the CDN redirect, because the headers we want are on the first response."""

    def redirect_request(self, *_args, **_kwargs):  # noqa: D102
        return None


def lfs_pointer(url: str) -> dict | None:
    """@return {sha256, bytes} for one Git-LFS file, from the headers Hugging Face sets.

    ``X-Linked-ETag`` is the object's sha256 and ``X-Linked-Size`` its true length; the
    response body at this URL is a redirect to a CDN, so nothing is downloaded to learn
    them. This works for any repository, which ``lfs_index`` -- pinned to the Piper tree
    API -- does not.
    """
    request = urllib.request.Request(url, headers={"User-Agent": "itantra"}, method="HEAD")
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        headers = dict(opener.open(request, timeout=120).headers)
    except urllib.error.HTTPError as error:
        headers = dict(error.headers)
    except Exception:  # noqa: BLE001 - a missing voice is reported by the caller, not fatal
        return None
    etag = (headers.get("X-Linked-ETag") or "").strip('"')
    size = headers.get("X-Linked-Size")
    if not etag or not size:
        return None
    return {"sha256": etag, "bytes": int(size)}


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

    # ── voices that are not Piper ────────────────────────────────────────────
    for language, voice in OTHER_VOICES.items():
        model_url = f"{voice['base']}/{voice['model']}"
        blob = lfs_pointer(model_url)
        if blob is None:
            print(f"   {language}: could not read an LFS id for {voice['model']}")
        else:
            items.append(
                {
                    "sha256": blob["sha256"],
                    "bytes": blob["bytes"],
                    "install": f"tts/{language}/model.onnx",
                    "url": model_url,
                    "language": language,
                    "kind": "voice",
                }
            )
        # Small, stored inline, and installed verbatim -- see OTHER_VOICES.
        table = fetch(f"{voice['base']}/{voice['tokens']}")
        items.append(
            {
                "sha256": hashlib.sha256(table).hexdigest(),
                "bytes": len(table),
                "install": f"tts/{language}/tokens.txt",
                "url": f"{voice['base']}/{voice['tokens']}",
                "language": language,
                "kind": "voice tokens",
            }
        )

    index = {
        "indexVersion": 1,
        "note": (
            "Downloaded by the operator's browser, imported and verified by the "
            "application. Nothing here is fetched by the application itself: it "
            "contains no HTTP client and opens no outbound network connection, per "
            "constraint C2."
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
    print(
        "\nthis index is HALF-BUILT: no artefact is bundled and no URL carries "
        "?download=true.\nrun next:  python tools/build_small_artefacts.py"
    )
    missing = [i["install"] for i in items if not i["sha256"]]
    if missing:
        print("::error::no hash for:", ", ".join(missing))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
