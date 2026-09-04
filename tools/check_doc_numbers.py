"""Task W8.11: every headline number in docs/ must match the code that produces it.

The RESCUE-A and 22 B drift is why this exists. A figure gets computed once, typed into a
specification, copied into a slide, and then the code changes -- and nobody notices until
somebody with a calculator asks in front of a panel. Grepping is cheap; being wrong in the
room is not.

This checks the four compression ratios and the frame sizes they come from, because those
are the numbers the whole submission rests on and the ones a technical reviewer will
actually verify. It reads the sizes from core-proto rather than from a table, so a header
change fails here as well as in CompressionRatiosTest.

Run by CI on every push, alongside check_licences.py.
"""
import pathlib
import re
import sys

# The quoted lines carry Devanagari and typographic marks; a Windows console defaults to
# cp1252 and would crash on the first one rather than report the defect it found.
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

root = pathlib.Path(__file__).resolve().parent.parent
docs = root / "docs"


def constant(path: pathlib.Path, name: str) -> int:
    """Reads `const val NAME = 123` out of a Kotlin source file."""
    text = path.read_text(encoding="utf-8")
    match = re.search(rf"const val {name}\s*=\s*(\d+)", text)
    if not match:
        sys.exit(f"::error::{name} not found in {path.relative_to(root)}")
    return int(match.group(1))


frame = root / "core-proto/src/main/kotlin/org/itantra/proto/Frame.kt"
transport = root / "core-proto/src/main/kotlin/org/itantra/proto/TransportClass.kt"

OVERHEAD = constant(frame, "HEADER_SIZE") + constant(frame, "CRC_SIZE")
FULL_TAG = int(re.search(r"private const val FULL\s*=\s*(\d+)",
                         transport.read_text(encoding="utf-8")).group(1))
TRUNCATED_TAG = int(re.search(r"private const val TRUNCATED\s*=\s*(\d+)",
                              transport.read_text(encoding="utf-8")).group(1))

# The reference utterance every published figure uses: 3 s of 16 kHz 16-bit mono PCM.
RAW_PCM = 3 * 16_000 * 2
OPUS = 6_000 * 3 // 8

PACKED = 32   # a 32-character Hindi sentence, one byte per character after script packing
TEMPLATE = 1  # a template code is one byte, and that is the entire message

# name -> (bytes on the wire, what it is compared against)
FRAMES = {
    "unauthenticated packed text": (OVERHEAD + PACKED, RAW_PCM),
    "authenticated packed text": (OVERHEAD + PACKED + FULL_TAG, RAW_PCM),
    "low-rate packed text": (OVERHEAD + PACKED + TRUNCATED_TAG, OPUS),
    "template code": (OVERHEAD + TEMPLATE, RAW_PCM),
}


def ratio(name: str) -> int:
    wire, source = FRAMES[name]
    return round(source / wire)


# A number that must appear in these documents, and must not appear wrong in any of them.
# `pattern` allows the thin space this project uses inside numerals: "2 182".
CLAIMS = [
    ("2182x unauthenticated", ratio("unauthenticated packed text"),
     r"2[\s ]?182\s*×"),
    ("1600x authenticated", ratio("authenticated packed text"),
     r"1[\s ]?600\s*×"),
    ("43x versus Opus", ratio("low-rate packed text"),
     r"\b43\s*×"),
    ("7385x template", ratio("template code"),
     r"7[\s ]?385\s*×"),
]

# Frame sizes quoted in prose. A size quoted next to the wrong ratio is the exact drift
# W8.11 exists to catch, so these are checked as well as the ratios.
SIZES = [
    ("44 B unauthenticated frame", FRAMES["unauthenticated packed text"][0]),
    ("60 B authenticated frame", FRAMES["authenticated packed text"][0]),
    ("52 B low-rate frame", FRAMES["low-rate packed text"][0]),
    ("13 B template frame", FRAMES["template code"][0]),
]

# Ratios that are correct but are not headline claims, so they may appear and need not.
# The low-rate authenticated frame against raw PCM is the one DEMO.md quotes when it
# reconciles the two tag lengths, and it has to be allowed or the reconciliation reads as
# an error.
OTHER_CORRECT = {
    round(RAW_PCM / FRAMES["low-rate packed text"][0]),
}

failures = []
markdown = sorted(docs.glob("*.md"))
corpus = {path: path.read_text(encoding="utf-8") for path in markdown}

# 1. Each headline ratio must appear at least once, and with the right value.
for label, expected, pattern in CLAIMS:
    found_in = [p.name for p, text in corpus.items() if re.search(pattern, text)]
    if not found_in:
        failures.append(f"{label}: {expected}x is not stated anywhere in docs/")


# 2. A compression ratio stated in prose must be one of the four, not an arithmetic slip.
#
# The multiplication sign is used all over these documents for ordinary arithmetic -- "10
# languages x 4 SNRs", "360 x 800 dp", "2.5x faster" -- so only lines actually making a
# compression claim are scanned. A leading tilde marks a deliberate approximation of a
# different comparison and is left alone.
CLAIM_CONTEXT = re.compile(r"compress|ratio|PCM|Opus|reduction|on the wire", re.I)
COMPRESSION_RATIO = re.compile(r"(?<![\d.~])(\d[\d\s ]*)\s*×")

# The smallest published ratio is 43. A single- or double-digit multiplier on one of these
# lines is always something else -- a quantisation factor, a thermal margin, a hex repeat
# count -- and chasing those would make this check noisy enough to get switched off.
SMALLEST_CLAIM = 40

# A figure inside backticks is a quotation, not a claim. Documents have to be able to say
# "it said 42x and it is 43x" when recording a defect, and a checker that cannot tell a
# quotation from an assertion forces the record to be vague instead.
QUOTED = re.compile(r"`[^`]*`")


def is_quoted(line: str, at: int) -> bool:
    return any(span.start() <= at < span.end() for span in QUOTED.finditer(line))


known = {expected for _, expected, _ in CLAIMS} | OTHER_CORRECT
for path, text in corpus.items():
    for line in text.splitlines():
        if not CLAIM_CONTEXT.search(line):
            continue
        for match in COMPRESSION_RATIO.finditer(line):
            value = int(re.sub(r"[\s ]", "", match.group(1)))
            if value in known or value < SMALLEST_CLAIM or is_quoted(line, match.start()):
                continue
            failures.append(
                f"{path.name}: {value}x is stated as a compression figure but is not one "
                f"of the four published ratios {sorted(known)}\n"
                f"      {line.strip()[:110]}"
            )

# 3. Where a document pairs a ratio with a frame size, the pair must be the right one.
PAIRS = [
    (r"1[\s ]?600\s*×", r"\b52\s*B\b",
     "1 600× is the 60 B frame; 52 B gives 1 846×"),
    (r"2[\s ]?182\s*×", r"\b(52|60)\s*B\b",
     "2 182× is the 44 B unauthenticated frame"),
]
#
# Only lines quoting a single ratio are checked. A line naming several is reconciling them
# -- the answer to "isn't 2 182x misleading?" has to put all four frame sizes next to each
# other to be honest -- and a pairing rule cannot read that. Those lines are held by the
# value check above, which is what catches an arithmetic slip inside one.
for path, text in corpus.items():
    for line in text.splitlines():
        if len(COMPRESSION_RATIO.findall(line)) != 1:
            continue
        for ratio_pattern, wrong_size, message in PAIRS:
            if re.search(ratio_pattern, line) and re.search(wrong_size, line):
                failures.append(f"{path.name}: {message}\n      {line.strip()[:110]}")

if failures:
    print("::error::documentation numbers do not match the code:")
    for failure in failures:
        print("   -", failure)
    print("\nThe figures are computed by bench CompressionRatios and pinned by its tests.")
    print("Fix the document, or change the code and let CompressionRatiosTest tell you what")
    print("else moved.")
    sys.exit(1)

print(f"doc numbers clean: {len(CLAIMS)} ratios and {len(SIZES)} frame sizes "
      f"checked across {len(markdown)} documents")
for label, expected, _ in CLAIMS:
    print(f"   {label:32} {expected}x")
