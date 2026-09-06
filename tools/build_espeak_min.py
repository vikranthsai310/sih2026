#!/usr/bin/env python3
"""Rebuild `app/src/main/assets/espeak-ng-data.zip` from a full espeak-ng-data directory.

espeak-ng ships dictionaries for about a hundred languages and this project speaks ten.
The full set is 17 MB, of which `ru_dict` alone is 8; the ten this application needs come
to about one. Pruning is what lets the data be **bundled** rather than downloaded, and
bundling is what lets a handset synthesise speech without ever fetching anything: the
voices are one file each, and this was the only part that arrives as an archive.

The shared machinery — `phondata`, `phonindex`, `phontab`, `intonations`, `lang/`,
`voices/` — is kept whole, because espeak needs all of it whatever language is asked for.

    python tools/build_espeak_min.py models/tts/espeak-ng-data
"""
from __future__ import annotations

import os
import sys
import zipfile
from pathlib import Path

LANGUAGES = ("hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en")
OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "espeak-ng-data.zip"


def main() -> int:
    if len(sys.argv) != 2:
        return int(bool(sys.stderr.write(__doc__)))
    source = Path(sys.argv[1])
    if not (source / "phondata").is_file():
        sys.exit(f"::error::{source} does not look like espeak-ng-data (no phondata)")

    wanted_dicts = {f"{code}_dict" for code in LANGUAGES}
    kept = 0
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for root, _, files in os.walk(source):
            for name in files:
                path = Path(root) / name
                relative = path.relative_to(source)
                # A language dictionary this project does not speak is dead weight; a file
                # that is not a dictionary is machinery espeak needs whatever it speaks.
                if name.endswith("_dict") and name not in wanted_dicts:
                    continue
                archive.write(path, relative.as_posix())
                kept += 1

    print(f"wrote {OUT.name}: {kept} files, {OUT.stat().st_size / 1048576:.2f} MB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
