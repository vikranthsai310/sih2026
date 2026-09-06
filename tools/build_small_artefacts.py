#!/usr/bin/env python3
"""Bundle every small text artefact into the installer, and force-download the rest.

A browser will not save a file the server marks ``Content-Disposition: inline``. Hugging
Face marks the big binaries that way too, but ``?download=true`` flips those to
``attachment``; for a ``text/plain`` file it changes nothing, and the browser shows the
contents instead of saving them. Checked, not assumed:

    hi_IN-pratham-medium.onnx        inline  ->  attachment with ?download=true
    hi_IN-pratham-medium.onnx.json   inline  ->  inline        with ?download=true

So the operator can never download the token tables and voice configs, and telling them to
long-press and hope is not an instruction. They are 2 to 66 kB each and come to about a
hundred kilobytes for all ten languages, so they go **in the installer** and the download
list is left holding only the large binaries — which do download, and which are the only
things too big to bundle.

    python tools/build_small_artefacts.py
"""
from __future__ import annotations

import io
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
INDEX = ROOT / "models" / "install-index.json"
OUT = ROOT / "app" / "src" / "main" / "assets" / "small-artefacts.zip"

# The application reads its index from `assets/`, not from `models/`. Keeping the two in
# step by hand is a trap: `models/install-index.json` is what the tools regenerate, and a
# build that forgets to copy it ships an index describing artefacts the APK does not have.
# So stage 2 writes both, and `check_install_index.py` refuses a build where they differ.
ASSET_INDEX = ROOT / "app" / "src" / "main" / "assets" / "install-index.json"

# A zip stores each entry's modification time, so rebuilding an unchanged archive produces
# different bytes and a spurious diff. Every entry is stamped with the same fixed date, and
# the archive becomes reproducible: same inputs, same bytes.
FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)

# Anything at or below this is bundled rather than downloaded. The largest is the shared
# Indic token table at 66 kB; the smallest is Odia's at under three.
BUNDLE_UNDER_BYTES = 512 * 1024


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def main() -> int:
    index = json.loads(INDEX.read_text(encoding="utf-8"))
    bundled, downloadable = [], []

    for item in index["items"]:
        (bundled if item["bytes"] <= BUNDLE_UNDER_BYTES else downloadable).append(item)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    # Eight languages share one Indic token table, so the same install path appears eight
    # times in the index. Writing it eight times produced a zip with duplicate entries and
    # eight identical downloads to build it.
    seen: set[str] = set()
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for item in bundled:
            if item["install"] in seen:
                continue
            seen.add(item["install"])
            blob = fetch(item["url"])
            entry = zipfile.ZipInfo(item["install"], date_time=FIXED_TIMESTAMP)
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o644 << 16
            archive.writestr(entry, blob)
            print(f"   bundled {item['install']} ({len(blob)} B)")

    # ?download=true is what makes a browser save the big ones rather than try to render
    # them. It is a no-op on the small ones, which is the whole reason those are bundled.
    for item in downloadable:
        if "?" not in item["url"]:
            item["url"] += "?download=true"
        item["bundled"] = False
    for item in bundled:
        item["bundled"] = True

    rendered = json.dumps(index, indent=2, ensure_ascii=False) + "\n"
    io.open(INDEX, "w", encoding="utf-8", newline="\n").write(rendered)
    ASSET_INDEX.parent.mkdir(parents=True, exist_ok=True)
    io.open(ASSET_INDEX, "w", encoding="utf-8", newline="\n").write(rendered)

    print(f"\nwrote {OUT.name}: {len(bundled)} artefacts, {OUT.stat().st_size / 1024:.0f} kB")
    print(f"left {len(downloadable)} to download, each marked ?download=true")
    print(f"wrote {INDEX.relative_to(ROOT)} and {ASSET_INDEX.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
