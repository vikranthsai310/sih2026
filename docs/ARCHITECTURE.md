# Architecture

Every device runs the complete system. Nothing is a dedicated transmitter or a dedicated
receiver; role is a runtime mode, not a build variant. One APK, installed on both
handsets.

## 1. End-to-end signal path

```
        DEVICE A — TRANSMIT                          DEVICE B — RECEIVE
 ┌────────────────────────────┐              ┌────────────────────────────┐
 │  Microphone   16 kHz mono  │              │  Link receive · reassemble │
 │            ▼               │              │            ▼               │
 │  Ring buffer · 20 ms hops  │              │  CRC · decrypt · de-dupe   │
 │            ▼               │              │            ▼               │
 │  Tier 0   energy gate      │              │  Address filter            │
 │  Tier 1   Silero VAD       │  idle path   │  group / dest / TTL        │
 │            ▼  speech       │              │            ▼               │
 │  Feature extraction        │              │  Unpack script  ·  or      │
 │  80-dim log-mel            │              │  expand template code      │
 │            ▼               │              │            ▼               │
 │  Conformer encoder         │              │  Text normalisation        │
 │            ▼               │              │            ▼               │
 │  Transducer decode         │              │  Phonemisation  espeak-ng  │
 │  + hotword biasing         │              │            ▼               │
 │            ▼               │              │  VITS synthesis, chunked   │
 │  Endpoint detected         │              │            ▼               │
 │  400 ms / 150 ms silence   │              │  AudioTrack streaming      │
 │            ▼               │              │            ▼               │
 │  Template match ─ or ─     │              │  ALERT? → alarm stream,    │
 │  script pack               │              │  max volume, wake lock,    │
 │            ▼               │              │  exclusive focus, repeat   │
 │  Frame · encrypt · CRC     │              │            ▼               │
 └────────────┼───────────────┘              └─────────[ SPEAKER ]────────┘
              │                                            ▲
              │           13 – 61 bytes                    │
              └────────────────────────────────────────────┘
                 Bluetooth  ·  BLE  ·  Wi-Fi  ·  LoRa / HF
```

Both halves are present on every device and both are always resident. The transmit path is
gated by mode and by the push-to-talk key; the receive path is always armed.

## 2. Module map

| Module | Responsibility | Principal contents |
| --- | --- | --- |
| `app` | User interface and orchestration | Compose screens, PTT control, channel and roster UI, settings, latency HUD, QR provisioning, the foreground service |
| `core-audio` | Capture and playback | `AudioRecord`/`AudioTrack` wrappers, ring buffers, gain control, noise suppression, alert audio policy, focus handling |
| `core-asr` | Speech to text | sherpa-onnx recogniser binding, three-tier VAD, endpointing state machine, hotword and gazetteer biasing, confidence scoring |
| `core-tts` | Text to speech | Per-language normalisation rules, phonemiser, chunked VITS synthesis, streaming playout scheduler |
| `core-link` | Transport | `Link` interface plus RFCOMM, BLE GATT, Wi-Fi socket and serial implementations; discovery, reconnection, queueing |
| `core-proto` | Wire format | Frame encode/decode, CRC-16, AES-GCM, script packing tables, template tables, sequence and replay windows, relay logic |
| `core-models` | Asset management | Language pack manifest, checksum verification, resumable download, storage accounting, model lifecycle |
| `bench` | Evaluation | WER harness, RTF measurement, latency logger, noise mixing, CSV and scorecard export |

### Dependency rules

These are enforced, not merely encouraged. A violation is a build failure, not a review
comment.

```
app  ──►  core-audio, core-asr, core-tts, core-link, core-proto, core-models
bench ─►  core-asr, core-tts, core-proto
core-asr ──► core-audio
core-tts ──► core-audio
core-link ─► core-proto        (framing only)
core-proto ─► (nothing)
core-audio ─► (nothing)
core-models ► (nothing)
```

- `core-proto` MUST have no Android dependency beyond `java.*` and `kotlin.*`. It is pure
  JVM so the frame codec can be unit-tested and fuzzed on a desktop without an emulator.
  This is what makes week 2 possible.
- No `core-*` module may depend on `app`.
- No module may reference a concrete `Link` implementation; only the interface.
- `core-asr` and `core-tts` MUST NOT know which transport is in use, and `core-link` MUST
  NOT know what the payload means.

### Why the boundaries fall here

The seam between `core-proto` and everything else is the important one. It lets the entire
transport and protocol layer be built and tested in week 2 with a text field standing in
for the recogniser, which is what de-risks the schedule. The seam between `core-link` and
its implementations is what allows a phone-to-phone demonstration to become a
phone-to-radio deployment without touching a line above the interface.

## 3. Threading and lifecycle

Audio work must never touch the UI thread, and the recogniser must never block capture.
Four threads with bounded queues between them.

| Thread | Priority | Duty | Constraint |
| --- | --- | --- | --- |
| Capture | `THREAD_PRIORITY_URGENT_AUDIO` | Reads 20 ms blocks from `AudioRecord`, runs the energy gate and Silero VAD, pushes speech blocks into the ASR queue | MUST NOT allocate. Buffers are pre-allocated and reused; no logging, no string formatting |
| Inference | `THREAD_PRIORITY_AUDIO` | Drains the ASR queue, runs encoder and decoder, emits partial and final hypotheses | May take longer than real time transiently; the queue absorbs it |
| Link | `THREAD_PRIORITY_DEFAULT` | Blocking socket reads and writes, framing, retransmission timers, heartbeat | Blocking I/O is expected here and nowhere else |
| Synthesis | `THREAD_PRIORITY_AUDIO` | Chunked TTS generation feeding an `AudioTrack` in streaming mode | Must stay ahead of the playback clock; see [TTS.md §4](TTS.md#4-chunked-synthesis-and-streaming-playout) |

### Queue policy

| Queue | Depth | On overflow |
| --- | --- | --- |
| Capture → Inference | 100 blocks (2 s) | Drop oldest, increment a dropped-block counter, surface a `DEGRADED` state in the UI. Never block the capture thread. |
| Inference → Link | 32 frames | Drop oldest `TEXT`; never drop `ALERT` — alerts pre-empt the queue instead |
| Link → Synthesis | 16 utterances | Drop oldest `TEXT`; never drop `ALERT` |

Dropping stale speech is correct behaviour. Retransmitting or queueing old conversation is
worse than losing it — the exception is `ALERT`, which is acknowledged and retried.

### Service lifecycle

The whole engine lives in a **foreground service** with a persistent notification, so
Android does not reclaim it while the screen is off — which is the normal operating state
for a radio.

```
Service.onCreate
  ├─ acquire PARTIAL_WAKE_LOCK
  ├─ start foreground notification
  ├─ load VAD  (1.8 MB, ~40 ms)
  ├─ load acoustic model for the active language  (~800 ms)
  ├─ load synthesis voice for the active language  (~600 ms)
  ├─ open the configured Link
  └─ state ← READY            transmit control enabled only now
```

**Cold start.** Loading the acoustic and synthesis models costs one to two seconds. That
delay MUST never be paid when the user presses transmit. Models are loaded when the
service starts and held resident; the idle path keeps them warm but does no inference.
The transmit control is disabled with a visible indicator until state is `READY`
(risk T-11).

**Language switching** unloads the outgoing pack and loads the incoming one, and is the
only runtime operation permitted to take more than a second. It is blocked while the floor
is held.

## 4. State model

One state machine owns the device; everything else observes it.

```
        ┌─────────────────────────────────────────────┐
        ▼                                             │
   INITIALISING ──► READY ──► LISTENING ──► RECOGNISING ──► TRANSMITTING
        │             ▲  ▲                                        │
        │             │  └────────────────────────────────────────┘
        │             │
        │             └──── RECEIVING ──► SPEAKING ────┘
        │
        └──► DEGRADED  (mic lost, link down, thermal, storage)
             └──► recovers to READY automatically when the cause clears
```

`DEGRADED` is a first-class state, always visible in the interface, and always carries a
reason string. A system that silently stops working is worse than one that says it has
stopped (risk T-12).

## 5. Data flow contracts

| Boundary | Type | Notes |
| --- | --- | --- |
| `AudioRecord` → capture | `ShortArray(320)` | 20 ms at 16 kHz mono, pre-allocated ring |
| Capture → inference | `SpeechBlock(pcm, tMic)` | `tMic` is the capture timestamp, carried end to end for latency accounting |
| Inference → app | `Hypothesis(text, isFinal, confidence, tEndpoint)` | Partials are advisory and never transmitted unless `PARTIAL` is set |
| App → `core-proto` | `Utterance(text, lang, priority, dst)` | |
| `core-proto` → `core-link` | `ByteArray` | Complete framed, CRC'd, optionally sealed frame |
| `core-link` → `core-proto` | `ByteArray` | One frame, already de-framed from the stream |
| `core-proto` → `core-tts` | `Utterance(text, lang, priority, src)` | |

Every one of these carries the originating `tMic` or `tRx` timestamp. That is what makes
`latency.csv` possible without a separate tracing mechanism.

## 6. Configuration and persistence

| Store | Contents | Mechanism |
| --- | --- | --- |
| Group configuration | Group ID, node ID, template profile digest, roster | DataStore, plaintext |
| Group key | AES-256 key material | **Android Keystore only.** Never DataStore, never a file, never a log |
| Language packs | Models, tokens, voices, rules | App-private external files, verified by SHA-256 |
| Outbox | Undelivered frames for store-and-forward | Room, capped at 500 frames or 24 h |
| Metrics | Rolling latency histogram, resource samples | In-memory ring, exported to CSV on demand |

## 7. Technology decisions

| Decision | Choice | Alternative considered | Rationale |
| --- | --- | --- | --- |
| Language | Kotlin | Java | Coroutines and `Flow` map cleanly onto the queue-and-stage model |
| UI | Jetpack Compose | Views | Fewer files for a small team; the interface is simple and mostly custom-drawn |
| Inference runtime | ONNX Runtime via sherpa-onnx | LiteRT, ExecuTorch | One library covers recognition, synthesis, VAD and endpointing, with an Android archive and Kotlin bindings. Both alternatives are named in the problem statement and are recorded as evaluated |
| Async | Coroutines + `Flow` | RxJava, callbacks | Structured cancellation matters when a service holds four long-lived threads |
| Storage | DataStore + Room | SharedPreferences + files | Outbox needs queries; configuration needs typed access |
| Audio output | `AudioTrack` streaming | Oboe | `AudioTrack` first; Oboe only if the output stage exceeds its 80 ms budget |
| Min SDK | 26 | 21 | `AudioAttributes.USAGE_ALARM` behaviour, Keystore, and `AudioRecord` timestamps are all reliable from 26 |

Toolchain versions are pinned in [SETUP.md](SETUP.md), not here, so that this document
does not go stale on a dependency bump.
