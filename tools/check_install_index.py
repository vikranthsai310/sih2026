#!/usr/bin/env python3
"""Fail if ``models/install-index.json`` is half-built.

The index is produced by **two** tools, in order, and it is not shippable after the first:

    python tools/build_install_index.py      # 1. hashes and addresses
    python tools/build_small_artefacts.py    # 2. bundling and ?download=true

Stage 2 does two things stage 1 cannot. It bundles every artefact at or under 512 kB into
``app/src/main/assets/small-artefacts.zip``, because Hugging Face serves those as
``Content-Disposition: inline`` and a browser will display them rather than save them --
so an operator asked to download a token table simply cannot. And it appends
``?download=true`` to the large binaries, which is what flips *those* to ``attachment``.

Running stage 1 on its own rewrites the index without either, and nothing complains. The
application then lists ~16 files the operator is unable to download, and offers URLs for
the big ones that a browser will try to render. Both bugs were fixed once already, in
"A browser will not save a text file, so stop asking it to" and "The download list looked
like buttons and was not"; re-running one tool brings both straight back.

This check is the guard. It runs offline, in a second, and it is why the ordering above is
worth trusting rather than remembering.

    python tools/check_install_index.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
INDEX = ROOT / "models" / "install-index.json"
ASSET_INDEX = ROOT / "app" / "src" / "main" / "assets" / "install-index.json"
ZIP = ROOT / "app" / "src" / "main" / "assets" / "small-artefacts.zip"

# Must match BUNDLE_UNDER_BYTES in tools/build_small_artefacts.py.
BUNDLE_UNDER_BYTES = 512 * 1024


def main() -> int:
    index = json.loads(INDEX.read_text(encoding="utf-8"))
    items = index.get("items") or []
    faults: list[str] = []

    if not items:
        faults.append("the index has no items at all")

    for item in items:
        install = item.get("install", "?")
        size = item.get("bytes", 0)
        url = item.get("url", "")
        small = size <= BUNDLE_UNDER_BYTES

        if "bundled" not in item:
            faults.append(
                f"{install}: no 'bundled' flag — stage 2 has not run since stage 1 "
                f"last rewrote the index"
            )
        elif item["bundled"] is not small:
            faults.append(
                f"{install}: bundled={item['bundled']} but it is "
                f"{size} B, which is {'under' if small else 'over'} the "
                f"{BUNDLE_UNDER_BYTES} B threshold"
            )

        if not small and "download=true" not in url:
            faults.append(
                f"{install}: {size} B and no ?download=true — a browser will try to "
                f"render this rather than save it"
            )
        if small and "download=true" in url:
            faults.append(
                f"{install}: {size} B is bundled, so ?download=true on its URL is "
                f"misleading"
            )

    if not ZIP.is_file():
        faults.append(f"{ZIP.relative_to(ROOT)} is missing — stage 2 has never run")

    # The application reads the assets copy; `models/` is only where the tools write. A
    # build where they differ ships an index that does not describe the APK it is in.
    if not ASSET_INDEX.is_file():
        faults.append(f"{ASSET_INDEX.relative_to(ROOT)} is missing — the app reads that one")
    elif ASSET_INDEX.read_text(encoding="utf-8") != INDEX.read_text(encoding="utf-8"):
        faults.append(
            f"{ASSET_INDEX.relative_to(ROOT)} differs from {INDEX.relative_to(ROOT)} — "
            f"the application reads the assets copy, so it is the one that ships"
        )

    # Every bundled artefact must actually be in the archive, or the application will look
    # for a file the installer never carried.
    if ZIP.is_file():
        import zipfile

        with zipfile.ZipFile(ZIP) as archive:
            carried = set(archive.namelist())
        for item in items:
            if item.get("bundled") and item.get("install") not in carried:
                faults.append(
                    f"{item['install']}: marked bundled but absent from "
                    f"{ZIP.name}"
                )

    if faults:
        print("::error::install index is half-built:")
        for fault in faults:
            print(f"   {fault}")
        print("\nrun: python tools/build_small_artefacts.py")
        return 1

    bundled = sum(1 for i in items if i["bundled"])
    print(
        f"install index clean: {len(items)} artefacts, {bundled} bundled in the "
        f"installer, {len(items) - bundled} marked ?download=true"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
