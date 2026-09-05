<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/sih-2026-dark.png">
  <img src="docs/assets/sih-2026.png" alt="Smart India Hackathon 2026" width="430">
</picture>

<br><br>

# iTantra

**Indian Multilingual TTS &amp; STT Aided Neural Transceiver**<br>
*Radio Access for Low Bitrate Links*

Smart India Hackathon 2026 · Problem Statement **26173** · ISRO, Department of Space

<br>

![Problem Statement](https://img.shields.io/badge/PS-26173-F48C22?style=flat-square)
![Organisation](https://img.shields.io/badge/ISRO-Dept._of_Space-149447?style=flat-square)
![Platform](https://img.shields.io/badge/Android-8.0%2B-415861?style=flat-square)
![Kotlin](https://img.shields.io/badge/built_with-Kotlin-415861?style=flat-square)
![Offline](https://img.shields.io/badge/runtime-100%25_offline-149447?style=flat-square)
![Languages](https://img.shields.io/badge/languages-10-F48C22?style=flat-square)
![Licence](https://img.shields.io/badge/licence-Apache--2.0-415861?style=flat-square)

<br>

<img src="docs/assets/mesh.svg" alt="One handset speaks and many hear: speech is recognised on the sending device, sent as a 44-byte packet to three handsets over the radio link and to a fourth by a relay hop, then re-synthesised locally on each." width="100%">

</div>

<br>

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

That is also why one transmission can serve a whole net. A waveform link is a pipe between
two endpoints; a 44-byte packet is small enough to broadcast, and small enough for a
handset that received it to relay onward to one that never heard the original.

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
- **Three transports** behind one interface: Bluetooth Classic, Bluetooth LE and Wi-Fi —
  phone to phone, no additional hardware.
- **Many units on one net.** Broadcast to every handset in range, with relaying for those
  out of it.

## Status

The end-to-end loop is closed and running on real handsets — speech in, radio link,
speech out — and the application has been exercised on a physical device rather than only
in tests.

| | |
| --- | --- |
| Tasks complete | **126 of 170** ([docs/TODO.md](docs/TODO.md)) |
| Source files | 156 across 8 modules |
| Test files | 76 |
| Last verified on | Galaxy **SM-S947B**, Android 16 |

The governing scheduling rule, recorded in [docs/ROADMAP.md](docs/ROADMAP.md), is worth
repeating:

> The end-to-end loop closes in **week 3** using the weakest acceptable models. Model
> quality is an upgrade path, never a prerequisite. A working loop with mediocre models
> can be improved under any amount of time pressure; excellent models with no loop cannot
> be demonstrated at all.

## Documentation

The complete design argument lives in [`docs/source/iTantra.html`](docs/source/iTantra.html) — read that
first if you want to know *why* the system is shaped this way. The `docs/` tree is the
normative engineering specification: what to build, to what tolerance, and how it is
verified.

| Start here | |
| --- | --- |
| [docs/README.md](docs/README.md) | Index of the whole documentation set |
| [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md) | R1–R11, the four constraints, and the traceability matrix |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, threads, lifecycle, signal path |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Frame format, packing, AEAD, relaying, fragmentation |
| [docs/SETUP.md](docs/SETUP.md) | Get a development environment running |
| [docs/iTantra Screens.html](docs/iTantra%20Screens.html) | The rendered UI/UX — all seventeen screens, openable in a browser |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Eight weeks, with an acceptance gate per week |
| [docs/TODO.md](docs/TODO.md) | **Start here to build.** Every task, in order, with a testable done-condition |

## Repository layout

```
settings.gradle.kts       8 modules; core-proto deliberately has no Android plugin
build.gradle.kts          dependency rules enforced as build failures
gradle/libs.versions.toml every version pinned, no dynamic ranges

app/                      UI, push-to-talk, alerts, pairing, foreground service
core-audio/               capture, playback, ring buffers, alert audio policy
core-asr/                 VAD tiers, endpointing, recognition, biasing
core-tts/                 normalisation, phonemisation, chunked playout
core-link/                Link interface: RFCOMM, BLE, Wi-Fi, serial
core-proto/               PURE JVM -- frame codec, CRC, AEAD, script packing
core-models/              language pack manifest, verification, lifecycle
bench/                    WER, RTF, latency, resource, scorecard export

models/                   manifest.json tracked; binaries are not
tools/                    model export, licence audit, report build
docs/                     the specification -- see docs/README.md
docs/source/              the original design document and the SIH template
```

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

<div align="center">
<br>
<sub>Team <b>Taraketu</b> · Smart India Hackathon 2026 · Problem Statement 26173</sub>
</div>
