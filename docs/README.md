# iTantra documentation

`docs/source/iTantra.html` is the design document — the argument for the system, written to be
read start to finish. This directory is the **specification** — normative, indexed, and
written to be consulted while writing code.

Where the two disagree, this directory wins, and the disagreement is a bug in one of them.
Resolved divergences are listed at the bottom of this page.

## The set

### Scope and shape

| Document | What it settles |
| --- | --- |
| [REQUIREMENTS.md](REQUIREMENTS.md) | **ISRO's problem statement verbatim**, then R1–R11 restated as testable requirements, the four constraints, and a traceability matrix from requirement to module to test |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Module boundaries, dependency rules, threading model, service lifecycle, end-to-end signal path |

### Component specifications

| Document | What it settles |
| --- | --- |
| [ASR.md](ASR.md) | The listening engine: three VAD tiers, the endpointing state machine, feature and decode pipeline, contextual biasing, confidence |
| [TTS.md](TTS.md) | The speaking engine: text normalisation rules, phonemisation, VITS, clause chunking and streaming playout |
| [TRANSPORT.md](TRANSPORT.md) | The `Link` interface and its four implementations, discovery, reconnection, queueing, stream framing |
| [PROTOCOL.md](PROTOCOL.md) | **Normative wire format.** Frame layout, script packing including escapes, template tables, AEAD construction, relay and replay rules |
| [MODELS.md](MODELS.md) | Language pack format, manifest schema, export and quantisation pipeline, storage lifecycle |
| [UX.md](UX.md) | Screens, the two modes, alert delivery sequence, pairing, inclusive design rules |
| [WIREFRAMES.md](WIREFRAMES.md) | All nine screens at low fidelity, with the layout system, state variants, banners and navigation map |
| [iTantra Screens.html](iTantra%20Screens.html) | **The rendered UI/UX canvas** — the same seventeen screens drawn properly, self-contained, openable in a browser. Look at the interface here; build it from `WIREFRAMES.md` |

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
| [TODO.md](TODO.md) | **Every task, in order, with owners, dependencies and a testable done-condition.** The list that turns the specification into a system |
| [RISKS.md](RISKS.md) | The risk register — twenty-nine numbered risks, owners, triggers, mitigations, status |
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

## Divergences from `docs/source/iTantra.html`

The design document is Revision 1.0 and remains accurate as an argument. Six details
were under-specified or over-specified for implementation and have been settled here.
Each is a deliberate change, not a transcription error.

| Topic | Design document | Specification | Why |
| --- | --- | --- | --- |
| Script packing | "roughly seventy code points cover ordinary running text" — no handling for anything else | [PROTOCOL.md §4](PROTOCOL.md#4-level-2--script-packing) defines a complete single-byte alphabet with a 4-byte escape for any codepoint outside it | Real messages contain ASCII digits, punctuation, and ZWJ/ZWNJ, none of which sit in an Indic block. Without an escape the packer is lossy on ordinary input. |
| Frame size with encryption | The 45 B figure is quoted alongside AES-256-GCM as though both hold at once | [PROTOCOL.md §6](PROTOCOL.md#6-cryptography) specifies a deterministic nonce (transmitted implicitly) and a transport-dependent tag length: 44 B unauthenticated, 52 B on low-rate links, 60 B on Bluetooth and Wi-Fi | A GCM tag and nonce are not free. Quoting the unauthenticated figure while claiming authenticated framing is the kind of gap a technical jury finds. State both. |
| Template profile | "up to 255 entries per deployment profile", but no frame field identifies the profile | [PROTOCOL.md §5](PROTOCOL.md#5-level-3--template-codes) binds the profile to the shared key at pairing and advertises its digest in `HEARTBEAT` | Two devices holding different template tables would silently speak different sentences from the same byte. That is a safety defect, not a compatibility inconvenience. |
| Addressing | A `DST` byte gives "private one-to-one messages", with a roster and an address selector in the interface | [PROTOCOL.md §8](PROTOCOL.md#8-addressing) removes `DST`. Every frame is broadcast to every unit holding the key. Header 11 → **10 bytes**; the roster and address screens are gone | ISRO asks for something that "should work like a walkie talkie", and a walkie-talkie has no address book. The byte, two branches in the receive path and two screens all bought a capability nobody asked for. Every frame is now a byte smaller, which is 27 ms of airtime on a 300 bps link |
| Groups and channels | A `GRP` byte carries "a channel identifier", and the interface has the user create or join a named group | [UX.md §5](UX.md#5-pairing) removes the concept. The byte becomes `KEYID`, derived from the shared key as a cheap pre-AEAD reject filter and never shown; the interface has only pairing | The key already separates traffic cryptographically — an unpaired device fails the tag whatever channel byte it saw — so `GRP` duplicated it. Worse, create/join forced a first-time user to answer a question they cannot answer, in an app premised on the user not reading. |
| Template-coded alerts | `TYPE` has separate `ALERT` and `TEMPLATE` codes, so a template-coded alert has no representation — the payload budget quotes one but the type nibble cannot express it | [PROTOCOL.md §2.1](PROTOCOL.md#21-type-carries-handling-flags-carry-encoding) makes `TYPE` carry handling and `FLAGS` carry encoding: the former reserved flag bit 2 becomes `TEMPLATE` | Priority and payload encoding are orthogonal. Conflating them in one nibble makes the most operationally important frame — a one-byte alert — unrepresentable. |

## Maintaining these documents

A change to behaviour lands in the same commit as the change to the document that
specifies it. `PROTOCOL.md` additionally carries a version number that MUST be incremented
whenever the wire format changes in a way that is not backward compatible, and the low
nibble of the frame's first byte MUST be incremented with it.
