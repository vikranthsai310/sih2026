# Fuzz corpus

Task **C.7**: *any input that ever caused a failure joins the fuzz corpus permanently.*

Each file is one byte sequence that must not break the decoder. They are checked in as
files rather than as byte arrays in a Kotlin literal, because that is how one actually gets
added — at the moment it is found, by whoever found it, without editing a test.

## Adding one

1. Write the exact bytes that caused the failure to a file here.
2. Name it `NNN-what-it-is[.reject|.accept]`, continuing the numbering.
3. Fix the defect.
4. Commit the file **in the same change as the fix**.

Step 4 is the one that matters. A corpus entry added later is an entry added after somebody
remembered, and the whole point of C.7 is that nobody has to.

## What the suffix means

| Suffix | Meaning |
| --- | --- |
| `.reject` | This input **must** be rejected. Accepting it is the defect. |
| `.accept` | This input **must** be accepted. Rejecting it is the defect. |
| neither | The verdict is not the point; the input must simply not break anything. |

Most entries need no suffix. An entry that crashed the decoder is interesting because it
crashed it, not because of what it should have returned — and pinning a verdict that was
never specified turns a regression test into a specification nobody agreed.

## What every entry is held to, suffix or not

`CorpusTest` asserts all four for every file:

- the decoder **terminates** and returns a verdict;
- it **does not throw** — turning hostile bytes into a rejection is its whole job;
- anything accepted has a payload within `MAX_PAYLOAD`, so nothing unbounded was allocated;
- the framer can still read **the next valid frame** afterwards. A decoder that survives bad
  input by wedging itself has not survived anything useful.

## The seeds

The eight numbered entries here were not found by a failure. They are the shapes that
historically break framing implementations in this class of project — risk **T-08** — and
they are here because an empty corpus with a passing test looks exactly like a working one.

`CorpusTest.the seed corpus is present` asserts them by name, so a clean-up that deletes
them fails a test rather than quietly weakening the suite.

## Relationship to the random fuzzer

`FrameFuzzTest` generates a million inputs from a fixed seed and finds what a million random
inputs find. It does not **remember**: change the generator, the seed or the shape
distribution, and the specific sequence that once crashed the decoder is no longer being
produced, with the suite still green. This directory is the memory.
