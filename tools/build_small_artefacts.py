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
            archive.writestr(item["install"], blob)
            print(f"   bundled {item['install']} ({len(blob)} B)")

    # ?download=true is what makes a browser save the big ones rather than try to render
    # them. It is a no-op on the small ones, which is the whole reason those are bundled.
    for item in downloadable:
        if "?" not in item["url"]:
            item["url"] += "?download=true"
        item["bundled"] = False
    for item in bundled:
        item["bundled"] = True

    io.open(INDEX, "w", encoding="utf-8", newline="\n").write(
        json.dumps(index, indent=2, ensure_ascii=False) + "\n"
    )

    print(f"\nwrote {OUT.name}: {len(bundled)} artefacts, {OUT.stat().st_size / 1024:.0f} kB")
    print(f"left {len(downloadable)} to download, each marked ?download=true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
