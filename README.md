# iTantra

**Indian Multilingual TTS & STT Aided Neural Transceiver — Radio Access for Low Bitrate Links**

Smart India Hackathon 2026 · Problem Statement **26173** · ISRO, Department of Space

---

Speech goes in one end. Speech comes out the other. In between it becomes a few dozen
bytes — small enough to cross a radio link that could never carry a voice.

## The problem in one table

| Representation | Size for a 3-second sentence | Fits a 300 bps link? |
| --- | --- | --- |
| Raw PCM, 16 kHz 16-bit mono | 96 000 B | No — 43 minutes |
| Opus at 6 kbps (the practical floor) | 2 250 B | No — 60 s |
| **iTantra packed text** | **44 B** | **Yes — 1.2 s** |
| **iTantra template code** | **13 B** | **Yes — 0.3 s** |

Audio codecs compress the *waveform*, and a waveform detailed enough to be understood has
an irreducible size. iTantra does not compress the waveform. It recognises the speech on
the sending device, transmits the meaning, and re-synthesises the speech on the receiving
device. The two humans only ever speak and listen — neither reads nor types.

## What this is

An Android application that turns two ordinary phones — or a phone and a radio module —
into a walkie-talkie:

- **Ten Indian languages**: English, Hindi, Bengali, Marathi, Telugu, Tamil, Gujarati,
  Kannada, Malayalam, Odia.
- **Entirely offline.** No cloud, no SIM, no network call at runtime. The demonstration
  runs in aeroplane mode.
- **Open source only.** No proprietary voice SDK anywhere in the system. See
  [LICENSES.md](LICENSES.md).
- **Runs on entry-tier hardware.** A 4 GB Snapdragon/Helio handset is the target, not a
  flagship. Every published number is measured on that class of device.
- **Two modes.** Push-to-talk (half duplex, walkie-talkie) and released (full duplex,
  telephone).
- **Four transports** behind one interface: Bluetooth Classic, BLE, Wi-Fi, and serial to a
  LoRa or HF radio module.

## Documentation

The complete design argument lives in [`Doc/iTantra.html`](Doc/iTantra.html) — read that
first if you want to know *why* the system is shaped this way. The `docs/` tree is the
normative engineering specification: what to build, to what tolerance, and how it is
verified.

| Start here | |
| --- | --- |
| [docs/README.md](docs/README.md) | Index of the whole documentation set |
| [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) | R1–R11, the four constraints, and the traceability matrix |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, threads, lifecycle, signal path |
| [docs/SETUP.md](docs/SETUP.md) | Get a development environment running |
| [docs/iTantra Screens.html](docs/iTantra%20Screens.html) | The rendered UI/UX — all seventeen screens, openable in a browser |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Eight weeks, with an acceptance gate per week |
| [docs/TODO.md](docs/TODO.md) | **Start here to build.** Every task, in order, with a testable done-condition |

## Repository layout

```
Doc/                     Design document (HTML + PDF) and the SIH submission template
docs/                    Normative engineering specification — see docs/README.md
app/                     Android application: UI, orchestration, foreground service
core-audio/              Capture, playback, ring buffers, alert audio policy
core-asr/                Recognition: VAD tiers, endpointing, biasing, confidence
core-tts/                Synthesis: normalisation, phonemisation, chunked playout
core-link/               Transport: RFCOMM, BLE, Wi-Fi, serial
core-proto/              Wire format: framing, CRC, AEAD, script packing, templates
core-models/             Language pack manifest, verification, lifecycle
bench/                   Evaluation harness: WER, RTF, latency, resource, scorecard
tools/                   Model export and quantisation scripts, dataset preparation
```

Modules under `app/` and `core-*` do not exist yet. Week 1 of
[the roadmap](docs/ROADMAP.md) creates them.

## Status

**Pre-implementation.** The design is complete and the specification is written; no code
has been committed. The governing scheduling rule is recorded in
[docs/ROADMAP.md](docs/ROADMAP.md) and is worth repeating here:

> The end-to-end loop closes in **week 3** using the weakest acceptable models. Model
> quality is an upgrade path, never a prerequisite. A working loop with mediocre models
> can be improved under any amount of time pressure; excellent models with no loop cannot
> be demonstrated at all.

## Targets

These are the numbers the system is built to hit, all measured on the entry-tier target
handset after a thirty-minute thermal soak. Method and full breakdown in
[docs/EVALUATION.md](docs/EVALUATION.md).

| | Target |
| --- | --- |
| End-to-end latency, PTT mode | 800–1200 ms |
| End-to-end latency, phone mode | 1050–1500 ms |
| Word error rate, clean read speech | < 12 % |
| Word error rate, critical vocabulary with biasing | < 6 % |
| Installer size | < 30 MB (models fetched once at setup) |
| Idle CPU while listening | < 2 % |
| Standby endurance, BLE, screen off | > 8 h |

## Licence

The application is released under Apache-2.0. Two dependencies carry restrictive licences
— espeak-ng (GPL-3.0) and Meta MMS (CC-BY-NC) — and both are disclosed, with their
consequences, in [LICENSES.md](LICENSES.md).
