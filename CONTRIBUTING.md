# Contributing

Six people, eight weeks, one demonstration. These conventions exist to keep the team out of
each other's way, not to impose process for its own sake.

## 1. Before you start

Read, in this order:

1. `Doc/iTantra.html` sections 01–03 — the argument. Nothing else makes sense without it
2. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — module boundaries and the dependency rules
3. The specification for the module you own — see [docs/README.md](docs/README.md)
4. [docs/SETUP.md](docs/SETUP.md) — get a build running

## 2. Branches and commits

| | Convention |
| --- | --- |
| Default branch | `main`, always buildable, always demonstrable |
| Feature branches | `<module>/<short-description>` — e.g. `core-proto/script-packing` |
| Commit subject | Imperative, ≤ 72 characters, no trailing period |
| Commit body | Why, not what. The diff already says what |
| References | Cite requirement, risk or protocol identifiers directly: `R3`, `T-08`, `PROTOCOL §4` |

```
core-proto: add escape sequence to script packer

UTF-8 digits, punctuation and ZWJ/ZWNJ fall outside every Indic block,
so the packer was silently lossy on ordinary input. Adds a 0x1B escape
carrying a 24-bit codepoint, per PROTOCOL §4.1, and a round-trip
assertion over the full block plus ASCII.

Closes T-13.
```

Never commit: model binaries, keystores, shared keys, `local.properties`, benchmark output.
`.gitignore` covers all of these; if you are fighting it, stop and ask.

## 3. Pull requests

Small and frequent beats large and late — especially in weeks 1–3, where the whole schedule
depends on the loop closing.

A pull request must state:

- What it changes and which requirement or risk it serves
- How it was verified — which test, on which device
- Any specification document it updates

**A change to behaviour lands in the same commit as the change to the document that
specifies it.** A pull request that changes the wire format without updating
[docs/PROTOCOL.md](docs/PROTOCOL.md) is rejected on sight, and so is the reverse.

### Review

One reviewer, except for these which need the module owner:

- Anything in `core-proto` — the wire format is a compatibility contract
- Anything touching cryptography, key handling, or the replay window
- Anything on the capture thread — the no-allocation rule is a correctness constraint
- Anything changing a documented metric target

## 4. What CI enforces

| Gate | Note |
| --- | --- |
| Build, all modules | Dependency rules from [ARCHITECTURE.md §2](docs/ARCHITECTURE.md#2-module-map) |
| ktlint, Android Lint | Warnings are errors in `core-proto` |
| JVM unit tests | Must pass |
| `core-proto` coverage ≥ 90 % | The highest-risk code in the project |
| Normalisation regression, 100 % | All languages, no exceptions |
| Latency regression | Loopback stage budgets |
| **Licence audit** | A dependency missing from [LICENSES.md](LICENSES.md) fails the build |
| Secret scan | No key material in the diff |

## 5. Code conventions

- Kotlin, ktlint defaults.
- `core-proto` is **pure JVM**. No Android imports, ever — that is what makes it testable
  and fuzzable in milliseconds.
- **No allocation, no logging, no string formatting on the capture thread.** Not a style
  preference; a dropped audio block is a lost utterance.
- Public API in `core-*` carries KDoc. Internals do not need it.
- Prefer a table-driven rule file over a code branch wherever a language differs from
  another — adding a language must not require a code change.
- No `TODO` without an identifier: `// TODO(T-05): Odia voice pending`.

## 6. Documentation

Everything in `docs/` is specification. If you change what the system does, change the
document in the same commit.

- New behaviour with no specification: write the section first, then the code. It is faster.
- A resolved ambiguity in `Doc/iTantra.html` goes in the divergence table in
  [docs/README.md](docs/README.md), so the design document and the specification never
  silently disagree.
- A new risk goes in [docs/RISKS.md](docs/RISKS.md) with an owner. A risk with no owner is
  not being managed.

## 7. Weekly rhythm

| When | What |
| --- | --- |
| Weekly | Gate check against [ROADMAP.md §2](docs/ROADMAP.md#2-acceptance-gates); risk register review; every owner reports unchanged / mitigating / escalating / closed |
| Weekly from week 4 | Benchmark run; `scorecard.csv` committed so the trend is visible |
| Weekly from week 6 | Thermal soak; demonstration rehearsal |

A gate that does not pass is escalated the same day. Absorbing slippage quietly is how a
week-3 hinge becomes a week-5 hinge, and there is no week 9.

## 8. The rule that outranks the others

> The end-to-end loop closes in week 3 using the weakest acceptable models. If your work
> threatens that, your work is what gets cut.

Risk P-01. It is the single most important scheduling decision in the plan, and every
competing team that fails will fail by inverting it.
