#!/usr/bin/env python3
"""Replace the placeholder hashes in ``models/manifest.json`` with real ones.

Seventeen entries carried ``sha256`` of sixty-four zeros. That is not a hash, and
``Manifest.requireSha256`` — whose stated job is that "a manifest carrying a placeholder
must fail here rather than at install time" — accepted every one of them, because sixty-four
zeros *are* sixty-four lowercase hex characters. The guard has been narrowed to reject them;
this tool is what makes the manifest pass it again.

The seventeen were three different problems:

**The six Piper voices** describe real files that are really downloaded. Their true hashes
were already known — ``models/install-index.json`` carries them, taken from Hugging Face's
Git-LFS object ids — so they are copied across rather than recomputed, which also makes the
two files agree by construction instead of by hand.

**The ten vocabularies** describe files bundled into the APK from ``models/lexicon/``. Each
listed three files, and one of the three — ``tokens.<lang>.txt`` — has never existed in any
language: the recogniser's token tables live in the ``asr`` block, at ``asr/tokens.txt`` and
``asr/en/tokens.txt``. So the file list is corrected to the two that exist and the hash is
computed over them. ``bytes`` was 262 144 for all ten, a round number that was nobody's
measurement; Hindi's two files come to 4 397.

The digest over a multi-file artefact is **sha256 of each file's bytes concatenated in the
order the manifest lists them**. Order is part of the definition, so reordering ``files``
changes the hash and the verify below fails — which is the intended behaviour, not a flaw:
the hash names an ordered set, and a reader must be able to reproduce it from the manifest
alone.

**The shared model** is not fixed here, because it cannot be. It describes a multilingual
shared encoder that is not what ships and has no artefact to hash; ``shared`` is now
nullable and the shipped manifest declares ``null``. See ``docs/MODELS.md``.

    python tools/build_manifest_hashes.py             # rewrite
    python tools/build_manifest_hashes.py --verify    # fail if stale, for CI
"""
from __future__ import annotations

import hashlib
import io
import json
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "models" / "manifest.json"
INDEX = ROOT / "models" / "install-index.json"

# Where a vocabulary file may live. Checked in order; the first hit wins.
SEARCH = ("models/lexicon", "models/rules", "models/templates")


def digest(paths: list[Path]) -> tuple[str, int]:
    """@return (sha256 over the files' bytes in order, total bytes)."""
    out = hashlib.sha256()
    total = 0
    for path in paths:
        blob = path.read_bytes()
        out.update(blob)
        total += len(blob)
    return out.hexdigest(), total


def locate(name: str) -> Path | None:
    for directory in SEARCH:
        candidate = ROOT / directory / name
        if candidate.is_file():
            return candidate
    return None


def main() -> int:
    verify = "--verify" in sys.argv
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    index = json.loads(INDEX.read_text(encoding="utf-8"))

    # voice hashes, by language, from the index that already resolved them
    voices = {
        item["language"]: item
        for item in index["items"]
        if item["kind"] == "voice"
    }

    changes: list[str] = []

    for pack in manifest["packs"]:
        lang = pack["lang"]

        # ── vocabulary ───────────────────────────────────────────────────────
        vocabulary = pack["vocabulary"]
        present, absent = [], []
        for name in vocabulary["files"]:
            found = locate(name)
            (present if found else absent).append(name)
        if absent:
            changes.append(f"{lang}: vocabulary drops {', '.join(absent)} (no such file)")
            vocabulary["files"] = present
        paths = [locate(name) for name in vocabulary["files"]]
        if not paths or any(p is None for p in paths):
            print(f"::error::{lang}: no vocabulary file found at all")
            return 1
        sha, size = digest([p for p in paths if p])
        if vocabulary["sha256"] != sha or vocabulary["bytes"] != size:
            changes.append(
                f"{lang}: vocabulary {vocabulary['bytes']} B -> {size} B, hash "
                f"{vocabulary['sha256'][:8]}… -> {sha[:8]}…"
            )
            vocabulary["sha256"], vocabulary["bytes"] = sha, size

        # ── voice ────────────────────────────────────────────────────────────
        voice = pack.get("tts")
        if voice is None:
            continue
        known = voices.get(lang)
        if known is None:
            print(f"::error::{lang}: declares a voice the install index does not carry")
            return 1
        if voice["sha256"] != known["sha256"] or voice["bytes"] != known["bytes"]:
            changes.append(
                f"{lang}: voice {voice['bytes']} B -> {known['bytes']} B, hash "
                f"{voice['sha256'][:8]}… -> {known['sha256'][:8]}…"
            )
            voice["sha256"], voice["bytes"] = known["sha256"], known["bytes"]

    if verify:
        if changes:
            print("::error::manifest hashes are stale:")
            for line in changes:
                print(f"   {line}")
            print("\nrun: python tools/build_manifest_hashes.py")
            return 1
        print("manifest hashes clean: every vocabulary and voice hash matches its bytes")
        return 0

    io.open(MANIFEST, "w", encoding="utf-8", newline="\n").write(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
    )
    if changes:
        print(f"wrote {MANIFEST.relative_to(ROOT)}: {len(changes)} entries corrected")
        for line in changes:
            print(f"   {line}")
    else:
        print("nothing to change")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
