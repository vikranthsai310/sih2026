# iTantra documentation

`Doc/iTantra.html` is the design document — the argument for the system, written to be
read start to finish. This directory is the **specification** — normative, indexed, and
written to be consulted while writing code.

Where the two disagree, this directory wins, and the disagreement is a bug in one of them.
Resolved divergences are listed at the bottom of this page.

## The set

### Scope and shape

| Document | What it settles |
| --- | --- |
| [REQUIREMENTS.md](REQUIREMENTS.md) | R1–R11 restated as testable requirements, the four constraints, and a traceability matrix from requirement to module to test |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Module boundaries, dependency rules, threading model, service lifecycle, end-to-end signal path |

### Component specifications

| Document | What it settles |
| --- | --- |
| [ASR.md](ASR.md) | The listening engine: three VAD tiers, the endpointing state machine, feature and decode pipeline, contextual biasing, confidence |
| [TTS.md](TTS.md) | The speaking engine: text normalisation rules, phonemisation, VITS, clause chunking and streaming playout |
| [TRANSPORT.md](TRANSPORT.md) | The `Link` interface and its four implementations, discovery, reconnection, queueing, stream framing |
| [PROTOCOL.md](PROTOCOL.md) | **Normative wire format.** Frame layout, script packing including escapes, template tables, AEAD construction, relay and replay rules |
| [MODELS.md](MODELS.md) | Language pack format, manifest schema, export and quantisation pipeline, storage lifecycle |
| [UX.md](UX.md) | Screens, the two modes, alert delivery sequence, provisioning, inclusive design rules |

### Verification

| Document | What it settles |
| --- | --- |
| [EVALUATION.md](EVALUATION.md) | Metric definitions, benchmark datasets, noise conditions, measurement method, scorecard file formats |
| [TESTING.md](TESTING.md) | Test strategy by layer, fuzzing the frame decoder, multi-device soak procedure, CI |
| [SECURITY.md](SECURITY.md) | Threat model, the five security risks, controls and their verification |

### Execution

| Document | What it settles |
| --- | --- |
| [SETUP.md](SETUP.md) | Development environment, toolchain versions, model acquisition, first build |
| [ROADMAP.md](ROADMAP.md) | Eight weeks with an acceptance gate per week, and the ordering rule that governs all of it |
| [RISKS.md](RISKS.md) | The risk register — twenty numbered risks, owners, triggers, mitigations, status |
| [DEMO.md](DEMO.md) | The nine-step demonstration runbook, equipment list, failure recovery |
| [IDEA_SUBMISSION.md](IDEA_SUBMISSION.md) | Slide-by-slide content for the official SIH idea-submission template |
| [GLOSSARY.md](GLOSSARY.md) | Every term of art used anywhere in the project |

## Conventions used throughout

- **MUST / SHOULD / MAY** carry their RFC 2119 meanings. A MUST in `PROTOCOL.md` is a
  wire-compatibility obligation; violating it produces frames other devices reject.
- Byte order on the wire is **big-endian** everywhere, without exception.
- Sizes are bytes (`B`), rates are bits per second (`bps`), times are milliseconds.
- Every performance number in these documents is a figure **measured on the target
  handset after a thirty-minute soak**, never a peak or a flagship figure. Where a number
  is still a target rather than a measurement it is marked `(target)`.
- Requirement identifiers (`R1`–`R11`), risk identifiers (`T-01`, `S-01`, `P-01`) and
  message type names (`TYPE_ALERT`) are stable across all documents and may be cited
  directly in code comments and commit messages.

## Divergences from `Doc/iTantra.html`

The design document is Revision 1.0 and remains accurate as an argument. Three details
were under-specified for implementation and have been settled here. Each is a deliberate
change, not a transcription error.

| Topic | Design document | Specification | Why |
| --- | --- | --- | --- |
| Script packing | "roughly seventy code points cover ordinary running text" — no handling for anything else | [PROTOCOL.md §4](PROTOCOL.md#4-level-2--script-packing) defines a complete single-byte alphabet with a 4-byte escape for any codepoint outside it | Real messages contain ASCII digits, punctuation, and ZWJ/ZWNJ, none of which sit in an Indic block. Without an escape the packer is lossy on ordinary input. |
| Frame size with encryption | The 45 B figure is quoted alongside AES-256-GCM as though both hold at once | [PROTOCOL.md §6](PROTOCOL.md#6-cryptography) specifies a deterministic nonce (transmitted implicitly) and a transport-dependent tag length: 45 B unauthenticated, 53 B on low-rate links, 61 B on Bluetooth and Wi-Fi | A GCM tag and nonce are not free. Quoting the unauthenticated figure while claiming authenticated framing is the kind of gap a technical jury finds. State both. |
| Template profile | "up to 255 entries per deployment profile", but no frame field identifies the profile | [PROTOCOL.md §5](PROTOCOL.md#5-level-3--template-codes) binds the profile to the group at provisioning and advertises its digest in `HEARTBEAT` | Two devices holding different template tables would silently speak different sentences from the same byte. That is a safety defect, not a compatibility inconvenience. |

## Maintaining these documents

A change to behaviour lands in the same commit as the change to the document that
specifies it. `PROTOCOL.md` additionally carries a version number that MUST be incremented
whenever the wire format changes in a way that is not backward compatible, and the low
nibble of the frame's first byte MUST be incremented with it.
