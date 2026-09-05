"""Task W1.25: download a language pack, verify it, install it atomically.

Reads models/manifest.json -- the same file the application parses -- so there is exactly
one statement of what a pack contains, how big it is and what it hashes to. A script with
its own copy of that table is a second source of truth that drifts, and the drift shows up
as a checksum failure nobody can explain.

Three properties, and each one exists because of a specific way this goes wrong.

**Verified before installed.** These artefacts are ~120 MB of model weights that will be
memory-mapped and executed against. A build that accepts whatever the network returned is
the same supply-chain hole tools/fetch_sherpa.sh exists to close, and the manifest already
carries the hashes.

**Atomic.** Downloads go to `<name>.partial` and are renamed into place only after the hash
matches. A handset that loses power mid-download must find either the previous pack or no
pack, never half of one -- a truncated .onnx loads, produces silence, and looks like a
recogniser bug rather than a storage one. Matches PackInstaller on the device side.

**Resumable.** A 120 MB download over a relief-camp connection does not complete first try.
A `.partial` is continued with a Range request rather than restarted, and a server that
ignores the range is detected rather than trusted.

Usage:
    python tools/fetch_models.py --lang hi
    python tools/fetch_models.py --all --base-url https://example.org/itantra/models
    python tools/fetch_models.py --lang hi --verify-only
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "models" / "manifest.json"

# A hash of all zeroes is the placeholder the manifest ships with. It is not a hash, and a
# script that treated it as one would verify nothing while appearing to verify everything.
PLACEHOLDER = "0" * 64

CHUNK = 1 << 16


def sha256_of(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(CHUNK), b""):
            digest.update(block)
    return digest.hexdigest()


def human(n: int) -> str:
    for unit in ("B", "KiB", "MiB", "GiB"):
        if n < 1024 or unit == "GiB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n / 1:.1f} {unit}"
        n /= 1024
    return f"{n} B"


def download(url: str, target: pathlib.Path, expected_bytes: int) -> None:
    """Fetches `url` into `target`, resuming a `.partial` if one is there."""
    partial = target.with_suffix(target.suffix + ".partial")
    have = partial.stat().st_size if partial.exists() else 0

    if have > expected_bytes:
        # Longer than the manifest says it should be: the file on disk is not this
        # artefact. Resuming would append to something unrelated.
        print(f"   discarding a {human(have)} partial, longer than the expected {human(expected_bytes)}")
        partial.unlink()
        have = 0

    if have == expected_bytes:
        print(f"   already downloaded: {human(have)}")
        partial.replace(target)
        return

    request = urllib.request.Request(url)
    if have:
        request.add_header("Range", f"bytes={have}-")
        print(f"   resuming at {human(have)} of {human(expected_bytes)}")

    try:
        with urllib.request.urlopen(request) as response:
            resumed = response.status == 206
            if have and not resumed:
                # The server ignored the range and is sending the whole file. Detected
                # rather than trusted: appending it to what we have would corrupt it.
                print("   server ignored the range request; restarting")
                have = 0
            mode = "ab" if (have and resumed) else "wb"
            with partial.open(mode) as handle:
                shutil.copyfileobj(response, handle, CHUNK)
    except urllib.error.URLError as error:
        # The partial is deliberately left behind so the next run resumes it.
        sys.exit(f"::error::{url}: {error}")

    partial.replace(target)


def install(artefact: dict, base_url: str, into: pathlib.Path, verify_only: bool) -> list[str]:
    """@return the problems found, empty if the artefact is present and correct."""
    problems = []
    expected = artefact["sha256"]

    # Where a file is published is not always what it is called once installed. Odia's
    # vocabulary ships as vocab.txt and is installed as tokens.txt, so that one loader
    # config serves all ten languages instead of nine plus a special case.
    remote_names = artefact.get("remote") or artefact["files"]

    for name, remote in zip(artefact["files"], remote_names):
        target = into / name
        if expected == PLACEHOLDER:
            # Said out loud rather than passed over. A run that prints "installed" for a
            # pack it could not verify is the worst outcome this script has.
            problems.append(f"{name}: the manifest carries a placeholder hash, so nothing can be verified")
            continue

        if target.exists():
            actual = sha256_of(target)
            if actual == expected:
                print(f"   {name}: present and verified")
                continue
            problems.append(f"{name}: on disk but hashes to {actual[:16]}…, expected {expected[:16]}…")
            if verify_only:
                continue
            target.unlink()

        if verify_only:
            problems.append(f"{name}: absent")
            continue

        staged = into / f"{name}.staged"
        # Before the download, not before the rename: a pack whose files live in a
        # per-language subdirectory has nowhere to stage them otherwise.
        staged.parent.mkdir(parents=True, exist_ok=True)
        print(f"   {name}: fetching")
        download(f"{base_url.rstrip('/')}/{remote}", staged, artefact["bytes"])

        actual = sha256_of(staged)
        if actual != expected:
            staged.unlink()
            problems.append(f"{name}: downloaded but hashes to {actual[:16]}…, expected {expected[:16]}…")
            continue

        # The rename is the install. Everything before it is reversible.
        staged.replace(target)
        print(f"   {name}: verified and installed")

    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lang", action="append", default=[], help="language code, repeatable")
    parser.add_argument("--all", action="store_true", help="every pack in the manifest")
    parser.add_argument("--base-url", default="", help="where the artefacts are served from")
    parser.add_argument("--into", default=str(ROOT / "models"), help="install directory")
    parser.add_argument("--verify-only", action="store_true", help="check what is present, download nothing")
    args = parser.parse_args()

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    packs = {pack["lang"]: pack for pack in manifest["packs"]}

    wanted = sorted(packs) if args.all else args.lang
    if not wanted:
        return parser.error("name a language with --lang, or --all")

    unknown = [lang for lang in wanted if lang not in packs]
    if unknown:
        sys.exit(f"::error::not in the manifest: {', '.join(unknown)}. Known: {', '.join(sorted(packs))}")

    # The recogniser's own models now have a real published home, so --base-url is only
    # needed for the artefacts that do not: the voices, and the lexicons. Each pack's "asr"
    # block carries its own baseUrl, because that is where those files actually are.
    if not args.base_url and not args.verify_only:
        print("no --base-url: fetching the ASR models only. Voices and lexicons are not "
              "published yet and will be reported as absent.")

    into = pathlib.Path(args.into)
    into.mkdir(parents=True, exist_ok=True)
    problems = []

    # The acoustic model is shared by every language, so it is fetched once however many
    # packs are asked for. Ten copies of a 120 MB model is 1.2 GB of downloads for
    # something the device needs once -- the reason the manifest has this shape at all.
    print(f"shared: {manifest['shared']['family']}, {human(manifest['shared']['bytes'])}")
    problems += install(manifest["shared"], args.base_url, into / "asr", args.verify_only)

    for lang in wanted:
        pack = packs[lang]
        print(f"\n{lang}: {pack['displayName']} ({pack['script']})")

        # The recogniser. One self-contained int8 model per language, Apache-2.0, at the URL
        # the block names -- see MODELS.md on why this is per-language rather than the
        # shared encoder the "shared" block above still describes.
        asr = pack.get("asr")
        if asr:
            print(f"   asr: {asr['family']}")
            for artefact in asr["artefacts"]:
                problems += install(artefact, asr["baseUrl"], into / "asr", args.verify_only)
        else:
            # Not a failure, and not silent. Odia has no IndicConformer export published in
            # sherpa-onnx form, so this language displays and transmits but cannot be
            # spoken *into* on this build. LICENSES.md and MODELS.md both say so.
            print("   no recogniser: no model published for this language yet")

        problems += install(pack["vocabulary"], args.base_url, into / "lexicon", args.verify_only)
        if pack["tts"]:
            problems += install(pack["tts"], args.base_url, into / "voices", args.verify_only)
        else:
            # Not a failure. Four of the ten languages have no permissively licensed voice
            # and the pack recognises and displays without speaking -- LICENSES.md.
            print("   no voice: this pack recognises and displays but does not speak")

    if problems:
        print(f"\n::error::{len(problems)} problems:")
        for problem in problems:
            print("   -", problem)
        return 1

    print(f"\n{len(wanted)} pack(s) present and verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
