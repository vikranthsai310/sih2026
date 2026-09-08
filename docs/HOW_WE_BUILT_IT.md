# iTantra — how we built it

*The what, why, who, when, where and how of Smart India Hackathon 2026 problem statement
26173, written for a reader who has not seen the code. Every number here comes from the
repository's own documents, tests or build output; where a figure has not been measured
yet, this document says so rather than guessing. Written 2026-09-08 by Team Taraketu.*

---

## 1. WHAT — what iTantra is

**One sentence.** Two ordinary Android phones, no SIM, no tower, no cloud: one person
speaks in any of ten Indian languages, the other hears it spoken aloud, and what crossed
the radio in between was a few dozen bytes.

**The problem, in ISRO's words** (problem statement SIH26173, Department of Space):

> "Vocal audio information is very data intensive making it difficult to transmit through
> low data rate links … Transmitting audio information is critical instead of written
> message as it will be more inclusive and will cater to everyone even if they are literate
> or not."

ISRO asked for an Android application with "lightweight, highly accurate STT and TTS
models for 10 Indian languages" that "runs locally on a low-power device", forms
sentences "after detecting pauses and stoppages", streams them "through wifi/Bluetooth
connected embedded device or another phone", plays them back as a voice note, announces
alerts "at highest volume non-interruptible", and works "like a walkie talkie using push
to talk feature, if turned off it should work like a phone."

**What we delivered.**

| Capability | State on 2026-09-08 |
| --- | --- |
| Ten languages recognised | English, Hindi, Bengali, Marathi, Telugu, Tamil, Gujarati, Kannada, Malayalam, Odia — all ten recognise |
| Languages spoken aloud | Seven of ten. Tamil, Kannada and Odia have no permissively licensed open voice anywhere; they are recognise-only and arrive as text |
| Fully offline | Yes. No HTTP client exists in the application; verified by inspection, see §7 |
| Transports | Bluetooth LE broadcast, Wi-Fi broadcast (hotspot, any shared Wi-Fi, or a Wi-Fi Direct group the app forms by itself), Bluetooth Classic to bonded handsets, and serial framing for a LoRa or HF radio |
| Multi-hop | Every unit rebroadcasts what it hears, within a hop count of 0–7 (default 3). A phone two hops away reads the message and knows who sent it |
| Encrypted | AES-256-GCM, pre-shared key, replay-protected, with the alert type bound so a text cannot be promoted into an evacuation order |
| Modes | Push-to-talk, and phone mode with an open line and barge-in |
| Alerts | Wake a locked, silenced handset at maximum alarm volume, restore the volume after |
| Cross-language alerts | A template alert spoken in Odia is announced in Hindi on a Hindi handset, with no translation model |
| Find a unit | Distance by radio signal, a siren on the searcher's phone or a chirp on the target's, position shared only while asked |
| Installer | 27.1 MiB release APK; language packs downloaded separately and verified by SHA-256 |

**The number that explains everything else.** Three seconds of speech:

| Representation | Bytes | Time on a 300 bps link |
| --- | --- | --- |
| Raw PCM, 16 kHz 16-bit mono | 96 000 | 43 minutes |
| Opus at 6 kbps, the practical codec floor | 2 250 | 60 s |
| iTantra packed sentence, encrypted, 16-byte tag | 60 | 1.6 s |
| iTantra template alert, encrypted, 8-byte tag | 21 | 0.6 s |
| iTantra template alert, unauthenticated | 13 | 0.35 s |

That is 1 600× smaller than raw audio with full authentication and 43× smaller than the
Opus floor. The honest headline is 1 600×; the 2 182× figure elsewhere in the docs is the
unauthenticated 44-byte frame, and `EVALUATION.md` says which is which.

---

## 2. WHY — why it is shaped this way

### Why not compress the audio?

Audio codecs compress the *waveform*, and a waveform detailed enough to understand has an
irreducible size: Opus cannot go usefully below about 6 kbps. A 300 bps link, which is
what an HF or LoRa channel offers, is twenty times too slow for that. iTantra does not
compress the waveform at all. It recognises the speech on the sending phone, sends the
*meaning* as text, and re-synthesises speech on the receiving phone. Meaning is small.
Both people only ever speak and listen, which is exactly what ISRO's "inclusive … even if
they are literate or not" asks for.

### Why the four hard constraints drive every decision

`REQUIREMENTS.md` treats four constraints as pass/fail; violating one invalidates the
submission.

- **C1, open source only.** ISRO bars "proprietary, closed-source, or commercial
  voice-activation SDKs". We tightened this further: Android's own `SpeechRecognizer` is
  Google Speech Services and is not used; Google Nearby Connections is not used either,
  raw platform sockets are. One blanket rule is easier to audit than a boundary argument.
  Every dependency and its licence is enumerated in `LICENSES.md`.
- **C2, fully offline at runtime.** Language packs are fetched once, by the operator's
  browser, at setup. After that no network call is ever made.
- **C3, approved frameworks.** ISRO recommends "TensorFlow Lite for Microcontrollers,
  PyTorch Mobile or similar". We use ONNX Runtime via sherpa-onnx, defended in
  `ARCHITECTURE.md` §7.1: TFLite Micro targets bare-metal microcontrollers with kilobytes
  of RAM and cannot run a Conformer; LiteRT and ExecuTorch are viable but no maintained
  build of IndicConformer or Piper exists for them, so adopting either meant weeks of
  model conversion in a schedule whose one rule was "close the loop in week 3".
- **C4, entry-tier hardware.** A 4 GB handset is the target, not a flagship.

### Why security exists at all

ISRO never asked for encryption. `SECURITY.md` §0 says so plainly and ranks it fourth of
seven on the cut list. It exists because this device wakes a locked, silenced handset at
maximum volume on command: a forged evacuation order is a real harm, and a replayed one is
too. The design cost was kept small: the nonce is derived rather than transmitted, so
authentication adds 8 or 16 bytes and nothing else.

### Why one APK, no roles

Every phone runs the complete system. There is no transmitter build and no receiver
build; role is a runtime mode. Two handsets with checksum-identical APKs is the whole
deployment, which is also what makes the seven-minute demonstration possible.

### Why a broadcast channel, not connections

The protocol has had no destination address since week 2: a walkie-talkie has no address
book, and on a radio everybody hears everything anyway. Encryption is the real address.
This is why the first transport, one RFCOMM socket per bonded pair, was replaced by BLE
advertising and UDP broadcast: N units over point-to-point links is N×(N−1)/2 connections
to pair, discover and re-establish, and `TRANSPORT.md` §8 records in bold that "pairing is
the most common cause of demonstration failure".

---

## 3. WHO — who it is for, and who built it

### The users

`REQUIREMENTS.md` §6 writes the system for four people who share one constraint: nobody
reads or types while operating.

- **Asha**, an Odia-speaking relief worker who cannot read. She is the reason the project
  exists.
- **Ravi**, an NDRF team leader who needs full-volume alerts and needs his Tamil units to
  hear them in Tamil.
- **Meena**, a gloved soldier working a physical push-to-talk key with the screen off.
- **The base station operator**, who switches modes, pairs handsets in seconds and reads
  the replay log.

### The team — Taraketu

Six people, six roles, each owning one of the technical problems that makes an offline,
low-bitrate mesh work.

| # | Member | Role | What they built |
| --- | --- | --- | --- |
| 1 | **Akshay** | AI & Speech Recognition Engineer — STT integration and user interface | Integrated AI4Bharat's IndicConformer models through ONNX Runtime for offline speech-to-text. Designed the push-to-talk interface so the microphone listens only while the key is held, for battery and privacy. Built the live confidence display that shows what the phone heard before the operator commits to sending. Configured the recogniser's emergency vocabulary (injured, trapped, evacuate). Built the first-run setup flow with SHA-256-verified download of the 190 MB language models, keeping the base APK at 27 MB |
| 2 | **Vikranth** | Audio Synthesis & Localisation Lead — TTS engine and far-end delivery | Implemented the receiving-end text-to-speech pipeline on Piper neural voices through ONNX Runtime, so a template code or free text is spoken in the listener's language, entirely offline. Engineered the alert playback path that overrides silent and locked states for critical alerts. Built the localisation logic by which an Odia speaker's alert triggers the correct Hindi sentence on a Hindi handset without a translation model |
| 3 | **Nirmal** | Cryptography & Protocol Architect — data packing and security | Designed the byte-level protocol and the encryption envelope that fit voice intent into 13 to 60 bytes. Implemented the template-table encoding that maps an emergency sentence to a single byte (13 bytes on the wire). Built the script-packing fallback for free text. Secured the pipeline with AES-256-GCM so a fraudulent evacuation order cannot be injected, with CRC checksums for integrity |
| 4 | **Anirudh** | Mesh Networking & BLE Specialist — Bluetooth LE broadcast and relay logic | Built the core radio link: phones communicate with no pairing, internet or towers using Bluetooth LE advertisements that reach every listening phone in range at once. Authored the mesh relaying system, in which phones rebroadcast what they hear, up to three hops by default, to reach units out of direct range. Implemented the deduplication so a message already received is never echoed or read aloud twice |
| 5 | **Divya** | Multi-Transport Networking Engineer — Wi-Fi and Bluetooth Classic fallbacks | Built the secondary and tertiary radio paths so a message travels down three roads at once. Engineered the Wi-Fi broadcast road, where one phone hosts a hotspot and the rest join it, at far higher bandwidth. Integrated Bluetooth Classic for bonded handsets. Owns the transport interface that merges packets arriving at different speeds over three radios without duplicating an alert |
| 6 | **Brahmani** | Data Pipeline & Normalisation Engineer — text processing and cross-language templates | Built the text normalisation engine that turns messy real speech into text the packer can compress and the synthesiser can pronounce. Created the cross-language template tables, aligning emergency sentences one-to-one across ten languages with no translation AI. Wrote the number, distance ("5 km") and time normalisation, and the edge-case handling (whitespace, digit-wise reading of coordinates and identifiers) that keeps free text inside the 60-byte limit |

The git history carries two author names: 117 commits under Vikranth Sai and 25 under
Akshay Patel, the two who committed on behalf of the team.

### The customer

ISRO, Department of Space. Category Software, theme Smart Automation. The idea-submission
deadline is 20 September 2026.

---

## 4. WHEN — the timeline

### The plan

`ROADMAP.md` lays out eight weeks, each with a gate, in an order chosen so that the
riskiest thing is proven earliest.

| Week | Theme | Why it comes here |
| --- | --- | --- |
| W1 | Capture and recognise | The microphone and the model are the two things that can simply not work |
| W2 | Transport | Built against a text field, before any model existed, so the whole path is proven |
| W3 | **Close the loop** | Speech in, radio, speech out, on real handsets. Risk P-01 says a loop unproven late is the top project risk |
| W4 | Real models | Ten languages, quantised, verified by hash |
| W5 | Modes and alerts | Push-to-talk, phone mode, the alert that wakes a locked phone |
| W6 | Latency, reach, cryptography | Decode-during-speech, relay, AEAD |
| W7 | Coverage and hardening | Normalisation for all ten, fuzzing, soaks |
| W8 | Evidence and rehearsal | The scorecard and the seven-minute demonstration |

### What actually happened

The repository's commit history runs from **2026-09-02 to 2026-09-08**: 144 commits in
seven days, with the eight-week plan executed as a sequence rather than a calendar.
Dated amendments inside the docs mark the turning points:

- **2026-09-03** — model verification: no streaming acoustic model exists for the ten
  languages, so the latency budget was revised from 500–800 ms to 800–1200 ms and
  decode-during-speech was designed to win most of it back.
- **2026-09-04** — the sherpa-onnx and Piper distribution questions closed; the APK was
  measured.
- **2026-09-06** — the `INTERNET` permission accepted for the Wi-Fi socket and constraint
  C2 re-verified by inspection; the Gujarati voice found; the TTL moved out of the
  authenticated header so relaying works; the Wi-Fi road rewritten as UDP broadcast.
- **2026-09-07** — presence and locate frames; finding a unit.
- **2026-09-08** — Wi-Fi Direct groups formed automatically; the hello and the presence
  relayed so a unit two hops away can read and name the sender.

### Where the task list stands

`TODO.md`: **126 of 205 tasks complete, 35 in progress, none blocked**. Weeks 1, 2, 5, 6
and 7 are done or done-except-measurement; week 8 is the least complete because most of
it is measurement and rehearsal. **No weekly gate is ticked**, because every gate ends in
a measurement on the target handset, and the handset has not been bought (open question
Q2: "the one that blocks the most").

---

## 5. WHERE — where it runs and where things live

### The device

Android 8.0 and later (minimum SDK 26, chosen because alarm audio attributes, the
Keystore and `AudioRecord` timestamps are all reliable from there). The target is an
entry-tier 4 GB handset. The last verified device was a Galaxy SM-S947B on Android 16.

### The code — eight Gradle modules

| Module | Owns | Depends on Android? |
| --- | --- | --- |
| `core-proto` | The wire format: `Frame`, CRC-16, `Aead`, `EpochCounter`, `ReplayWindow`, `ScriptPacker`, `TemplateTable`, `Presence`, `Locate` | No. Pure JVM, so the codec is fuzzable on a laptop in milliseconds |
| `core-audio` | Capture and playback: `AudioCapture`, `AlertPlayback`, energy gate, pre-trigger ring, duplex policy | Yes |
| `core-asr` | Speech to text: `SherpaRecogniser`, `UtteranceDecoder`, `Endpointer`, biasing lexicon | Yes |
| `core-tts` | Text to speech: `SherpaSynthesiser`, `TextNormaliser`, `SpeechShaper`, `AudioPolish` | Yes |
| `core-link` | Transport: the `Link` interface, `Session`, `MeshLink`, `Relay`, `BleBroadcastLink`, `WifiBroadcastLink`, `WifiDirectGroup`, `RfcommLink`, `FloorControl`, `Outbox` | Yes |
| `core-models` | Language packs: manifest, hash verification, installer | Yes |
| `bench` | Evaluation: WER scorer, latency log, clock sync, scorecard, report bundle | Partly |
| `app` | `MessageEngine`, `EngineService`, the Compose screens, platform adapters | Yes |

The dependency rules between modules are enforced by the build, not by review. A
violation is a build failure.

### The models on the phone

Under the app's external files directory, `models/asr/<lang>/model.int8.onnx` with a
shared multilingual token table, and `models/tts/<voice>.onnx`. The layout on disk is
identical to the layout produced by `tools/fetch_models.py`, so `adb push` of the models
folder is a valid install. Every artefact has a SHA-256 in `models/manifest.json`, and the
manifest parser refuses an all-zero hash, a rule added on 2026-09-06 after seventeen
placeholders shipped past the check.

### The key

A 256-bit pre-shared key, generated by the first unit, carried to the second only as a QR
code shown on a secure window for 120 seconds, and stored in the Android Keystore. It is
never written to a file, a preference or a log, and never radiated.

### The documents

`docs/` is the normative specification, 26 files and about 11 000 lines. `ARCHITECTURE.md`
for the modules and the signal path, `PROTOCOL.md` for every byte on the wire,
`TRANSPORT.md` for the radios, `SECURITY.md` for the threat model, `ASR.md` and `TTS.md`
for the speech stack, `EVALUATION.md` for how every published number is measured,
`DEMO.md` for the seven minutes on stage, `RISKS.md` for the 31 named risks and their
owners, and `TODO.md` for every task with its done-condition.

---

## 6. HOW — how it works, stage by stage

### 6.1 The signal path

```
 SENDER                                        RECEIVER
 microphone, 16 kHz mono, 20 ms hops           frame arrives on any road
   ▼                                             ▼
 tier 0: energy gate (3 µs/frame)              CRC · KEYID filter · reassemble
 tier 1: Silero VAD (300 µs/frame)               ▼
   ▼ speech                                    AEAD open under the sender's epoch
 IndicConformer, int8, greedy CTC                ▼
 decoded clause by clause while speaking       replay window · relay decision
   ▼ endpoint: 150 ms silence (PTT)              ▼
 template match ≥ 0.85  — or —  script pack    expand template in MY language
   ▼                                           — or — unpack script
 10-byte header · encrypt · CRC                  ▼
   ▼                                           normalise · phonemise (espeak-ng)
 13 – 61 bytes                                 VITS synthesis, chunked
   ▼                                             ▼
 BLE advert · UDP broadcast · RFCOMM · serial  AudioTrack; ALERT → alarm stream, max volume
```

Both halves are resident on every phone. The transmit half is gated by the mode and the
push-to-talk key; the receive half is always armed.

### 6.2 Hearing: capture and voice activity

`AudioCapture` opens the microphone with the `VOICE_RECOGNITION` source, not `MIC`,
because vendor gain control and noise suppression distort the spectrum the model was
trained on. A 60 Hz single-pole high-pass filter ships; vendor suppression does not. A
250 ms pre-trigger ring keeps the start of a word that began before the gate opened.

Voice activity runs in three tiers so the phone is not running a neural network on
silence: an energy gate at about 3 µs per 20 ms frame, Silero VAD at about 300 µs, and
the Conformer itself at about 4 ms. Continuous acoustic inference would drain an
entry-tier battery in around two hours and fail the efficiency criterion outright.

### 6.3 Recognising: IndicConformer through sherpa-onnx

The recogniser is AI4Bharat's IndicConformer, a Conformer-CTC model, exported to ONNX and
quantised to int8. Quantisation makes it 4× smaller and about 2.5× faster for roughly 1 %
relative WER, and the published sherpa-onnx files are int8 only anyway. Each language's
model is about 189 MB; ten languages share one 5 633-token table. Decoding is greedy
because sherpa-onnx's offline CTC path refuses beam search at construction, not because
greedy was preferred.

**No streaming model exists for these languages**, verified on 2026-09-03. A naive
"decode after release" cost 4 116 ms on a 3-second utterance. `UtteranceDecoder` recovers
most of it: each clause is decoded in the 280 ms pause that ends it, while the operator is
still speaking, so only the last clause waits on the endpoint. The predecessor design
(fixed 1.5 s windows with overlap) cut words in half and could not tell a revision from a
new word; it was replaced.

Threads: four while decoding, two at idle. Four is faster cold and slower after thermal
soak, and the thermal budget matters more than the cores.

Endpointing: 150 ms of trailing silence in push-to-talk, 400 ms in phone mode, 8 s maximum
utterance, 300 ms minimum. Confidence is quantised to two wire bits; low confidence blocks
alert-class messages from leaving.

Contextual biasing (a 300-term domain lexicon, negation terms, a gazetteer, roster names)
is built but not active: sherpa-onnx's CTC path ignores hotword files, which was
discovered and recorded on 2026-09-07. It is held for a transducer export.

### 6.4 Shrinking: templates and script packing

Two levels, tried in order.

**Level 3, a template.** Emergency sentences live in a table in all ten languages, keyed by
one byte. The recognised sentence is matched against the table in the *sender's* language
by normalised-token Levenshtein similarity; it must score at least 0.85 with high
recogniser confidence. That threshold means a single wrong word only passes on a sentence
of seven or more tokens, so the system falls back to Level 2 often, on the safe side. The
frame is 13 bytes.

**Cross-language delivery falls out of this for free.** The receiver renders template id
`0x01` from *its own* table in *its own* language. There is no translation model, no
download and no latency. It applies to template traffic only; free text arrives in the
language it was spoken.

**Level 2, script packing.** The header declares the language, which implies a 128-code-
point Unicode block (Devanagari, Bengali, Tamil, Telugu and so on). Bytes 0x80–0xFF map to
that block, 0x00–0x7F are literal ASCII, and 0x1B escapes a full 24-bit scalar for
anything else, including the zero-width joiners Indic scripts depend on. Text is NFC-
normalised first. A 54-byte UTF-8 Hindi sentence becomes 20 bytes. If packing would not
shrink the text, it is sent as UTF-8 with the flag clear.

Template tables are bound to a profile id and a 4-byte digest that travel with the key
and in every heartbeat. Two handsets with different tables would render different
sentences from the same byte, which is a safety defect, so a mismatch stops template
traffic in both directions and shows a persistent warning.

### 6.5 Framing: the ten-byte header

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 1 | Magic nibble `0xA` and version `1` |
| 1 | 1 | Type nibble, language nibble |
| 2–3 | 2 | Sequence number, per sender |
| 4 | 1 | Flags: FINAL, PARTIAL, ENCRYPTED, FRAGMENT, PACKED, TEMPLATE, two confidence bits |
| 5–6 | 2 | Payload length, at most 1 024 |
| 7 | 1 | Source node id. **There is no destination field** |
| 8 | 1 | Key id: first byte of SHA-256 of the key, a cheap reject filter |
| 9 | 1 | TTL, 0–7 |
| … | | Payload, then a CRC-16/CCITT-FALSE over everything before it |

Types: TEXT, ALERT, ACK, PTT_CTL, HEARTBEAT, TEMPLATE, POSITION, AUDIO_FB. Reserved
types are discarded silently. Frames larger than a link's MTU are fragmented and
reassembled on the sealed bytes, so a fragment can be relayed before it is readable.

### 6.6 Securing: AES-256-GCM with a derived nonce

Plaintext is the payload. Associated data is the ten-byte header **with the TTL byte
zeroed**, so the source, type, sequence, flags and language are all bound. A text frame
cannot be promoted to an alert, and a frame cannot be attributed to another unit.

The nonce is never transmitted, which saves 12 bytes a frame. It is `EPOCH ‖ SRC ‖ SEQ`.
The epoch is a 32-bit counter, persisted, bumped on every service start and on every
sequence wrap, and seeded from the clock as minutes since 2026-01-01 so a reinstall cannot
restart at zero and reuse a nonce. The sender's epoch is announced in an unauthenticated
**hello** every five seconds, used only as the first candidate; the receiver then
*discovers* the epoch by trying candidates and accepting only one whose tag verifies. A
forged hello costs the receiver one wasted tag check.

Replay protection is a 64-entry sliding window per sender keyed on epoch and sequence.
The tag is 16 bytes on Bluetooth and Wi-Fi and 8 bytes on serial links, where those 8
bytes are 27 seconds of airtime at 300 bps; truncation is only defensible because an
authentication failure limiter degrades the link after 16 failures in a minute.

Security tests mutate every single byte of the header and of the ciphertext and assert
rejection; a fuzz test feeds the decoder a million malformed inputs and asserts it never
throws, never over-allocates and recovers after a megabyte of rubbish.

### 6.7 Carrying: four roads behind one interface

`Link` is one interface: send bytes, receive complete frames, report a state. Everything
above it never learns which radio carried the bytes. `MeshLink` fans one send out to every
road that is on and does **not** deduplicate, because the replay window and the relay
seen-set above it already do, per sender.

- **Bluetooth LE broadcast** (`BleBroadcastLink`). No pairing, no connection, no roster.
  Each phone advertises *everything it has said recently* in one extended advertisement,
  ten times a second, and every phone scanning hears the whole buffer. A message stays on
  the air for at least five seconds and up to thirty, because a scanner takes only about
  one chance in three; the first version aired each frame for 2.5 s once and messages
  silently vanished. The hello and the presence are pinned at the head of the buffer;
  everything else queues, alerts first. Every advertisement heard is also a signal
  reading of the sender's distance.
- **Wi-Fi broadcast** (`WifiBroadcastLink`). One UDP datagram to every subnet broadcast
  address on port 38173. A hotspot, any shared access point, or a Wi-Fi Direct group all
  count; the road is up only while some interface has a subnet broadcast address, checked
  every three seconds. A multicast lock is held because Wi-Fi power save otherwise drops
  broadcast frames before they reach the socket.
- **Wi-Fi Direct, formed by the application** (`WifiDirectGroup`, added 2026-09-08). With
  Wi-Fi on and nothing arranged, the phones discover each other; a unit joins any group
  owner it can see, becomes an owner after a random five to twenty seconds if it sees
  none, and an empty owner that sees a rival steps down. There is no leader because
  Android hides a phone's own P2P address from applications. Android asks one tap on the
  owner's screen the first time each pair meets; the group is persistent, so never again.
- **Bluetooth Classic** (`RfcommLink`, `BluetoothNet`). One socket per bonded handset, at
  about 200 kbps. Used where a bond exists, no longer required for anything.
- **Serial** for a LoRa or HF radio: identical frames over Bluetooth SPP to the module,
  8-byte tags, a sentinel byte and a stream framer for resynchronisation.

Roads can be switched off for routine traffic in the control room, but never for
receiving, and an alert goes down every road that is up regardless.

### 6.8 Reaching further: relay

Every unit rebroadcasts what it hears, sealed and unmodified except for the TTL, after a
random 0–50 ms. Three mechanisms hold the storm down and `Relay.kt` says why all three are
necessary: the TTL bounds hop count, a 512-entry seen-set on (source, epoch, sequence,
fragment) stops loops, and the jitter stops every unit rebroadcasting in the same
instant. A four-unit chain soak runs an hour of traffic and asserts every message arrives
exactly once.

The first relay never worked, twice, and both times are on the record: it relayed the
*opened* frame, which every next hop refused as unauthenticated; and the TTL was inside
the authenticated header, so a decremented frame failed the tag. Both were fixed on
2026-09-06. On 2026-09-08 a third gap closed: a unit two hops away never heard the
sender's hello, so it could open the sender's frames only if the two phones had been set
up within about three days of each other. The hello and the presence are now relayed when
they are news, so the far unit learns the epoch in one check and shows the sender by name.

### 6.9 Speaking: Piper voices through the same runtime

Synthesis is VITS through sherpa-onnx, phonemised by espeak-ng, which is linked as a
phonemiser only and cannot itself synthesise. Voices are Piper models of 20–60 MB for six
languages and a Mimic 3 CMU Indic voice for Gujarati, found on 2026-09-06 after surveying
642 published sherpa-onnx voice artefacts. Meta MMS would have covered the remaining three
languages and was refused as CC-BY-NC, which would kill any deployability claim.

Text is normalised first by ordered per-language rule files: numbers as words or digit by
digit depending on whether they are quantities or identifiers, `HH:MM` as a spoken time,
units expanded, a decimal point kept from ending a sentence. Speech is shaped into clauses
at punctuation or before a conjunction after 24 words, levelled to −20 dBFS, and
synthesised in chunks so the first audio plays in about 180 ms instead of 700. Each
sending node gets a consistent voice by speaker id.

### 6.10 Alerting

`AlertPlayback.announce()` runs a fixed order: take a wake lock, save the alarm volume,
raise it to maximum, request exclusive transient audio focus and proceed even if refused,
wake the screen and vibrate, play twice, and restore the volume in a `finally`. A full-
screen intent puts the alert over a locked screen. The volume-restore test exists because
forgetting it leaves a handset permanently at maximum alarm volume, a defect certain to be
found on stage.

### 6.11 Two modes, and who may speak

Push-to-talk holds the microphone open while the key is down and cuts the endpoint to
150 ms. Phone mode runs an open line with the endpoint at 400 ms; the key becomes a
hold/resume toggle, the far side is ducked to −18 dB within 100 ms when the near side
speaks, and an echo filter drops the handset's own sentence coming back. Floor control is
deterministic: the lower node id wins, the loser backs off randomly, and a refusal is a
haptic, never a dialog.

### 6.12 Finding a unit

There is no arrow, because GPS fixes wander further than the search distance. Distance
comes from the radio: a log-distance path-loss model whose two parameters are fitted for
*this pair* of phones while GPS distance is still trustworthy, then smoothed. The searcher
chooses who sounds: a Geiger-counter siren on their own phone quickening as they close, or
a chirp on the target's phone quickening from the searcher's own signal. Position is
shared only while asked, sealed, on the channel.

### 6.13 Lifetime and screens

`EngineService` has two lifetimes. Bound only, the engine lives and dies with the screen.
In **relay mode**, off by default and priced on its card in battery, the service goes
foreground with a partial wake lock, the radios stay up with the screen off, and a kill
under memory pressure brings the relay back working rather than back empty. The
interface is Jetpack Compose: an operating screen whose dock states are idle, seized,
live, busy and phone; a control room where every row carries a fact; message log, metrics,
language, storage, unit name, locate and licence screens. Every control is spoken for
TalkBack and no text has a fixed height, so 200 % text works.

---

## 7. HOW WE KNOW — verification

### What runs on every change

| Gate | Protects |
| --- | --- |
| `:core-proto:test` and 90 % line coverage enforced (94.2 % achieved) | The frame codec, the highest-risk code |
| `testDebugUnitTest` across eight modules | 771 tests before today; core-link alone is at 226 after today's relay and Wi-Fi Direct work |
| `ktlintCheck` | Style, warnings as errors in core-proto |
| `tools/check_licences.py` | A dependency absent from `LICENSES.md` fails the build |
| `tools/check_doc_numbers.py` | A compression figure in the docs that the codec does not produce |
| `tools/fetch_models.py --verify-only` | A model whose hash differs from the manifest |
| Decoder fuzz, 10⁶ inputs | Never throws, never over-allocates, recovers |
| Nonce uniqueness, 10⁷ frames across wraps and restarts | No (key, nonce) repeat |

### How the constraints are proven

C1 by the licence audit. C2 by inspection, not by the permission list: a grep of
`src/main` for any HTTP client or WebSocket returns nothing, a grep for hostname
resolution returns nothing, and `WifiBroadcastLinkTest` asserts every address the Wi-Fi
road can send to is a broadcast address. The one class that ever dialled outbound TCP was
dead code and was deleted on 2026-09-06 so that the claim holds without a caveat. The
demonstration is run in aeroplane mode, which shows the property rather than a proxy for
it.

### How the scored numbers will be measured

ISRO weights accuracy 40 %, latency 20 %, efficiency 20 %. `EVALUATION.md` fixes the
conditions before any figure is taken: an entry-tier handset, after a 30-minute thermal
soak, battery above 30 % and not charging, a release build, at least 100 utterances,
median and p95. End-to-end latency is measured by clock synchronisation across four
heartbeat round trips, never by stopwatch, and an unsynchronised clock refuses to emit a
figure. The targets: WER below 12 % clean and below 22 % at 10 dB SNR, critical-term error
below 6 %, ASR real-time factor below 0.30, TTS below 0.25, 800–1200 ms push-to-talk end
to end, under 30 MB installer, under 350 MB resident, over eight hours standby.

### What has been measured, and what has not

Measured: the release APK at 27.1 MiB, native libraries at 31 MB of it; the test counts and
coverage above; the 4 116 ms naive decode that motivated decode-during-speech; the 700 ms
to 180 ms time-to-first-audio from chunked synthesis; the high-pass filter's response.

**Not measured:** WER, MOS, real-time factor, end-to-end latency, battery and thermal
figures. The reporting rule is that no number is published that the harness did not
produce, and the harness needs the target handset, the corpus and a listening panel of
fifteen native speakers per language. The latency strip in the demo script (210 / 40 /
180 / 780 ms) is the script's target, not a recorded result, and `DEMO.md` says so.

Today's two additions, automatic Wi-Fi Direct grouping and two-hop hello relay, pass their
unit tests and have not yet been run on handsets. `TESTING.md` M13 and M14 are the bench
procedures.

---

## 8. WHICH — the decisions, and the alternatives they beat

| Decision | Chosen | Instead of | Because |
| --- | --- | --- | --- |
| Send meaning, not sound | Recognise, pack, re-synthesise | Opus or any codec | A waveform has an irreducible size; a 300 bps link is 20× too slow for the codec floor |
| Inference runtime | ONNX Runtime via sherpa-onnx | LiteRT, ExecuTorch, TFLite Micro | Nothing needs converting; the loop closes in week 3 not week 6. TFLite Micro is for microcontrollers |
| Recogniser | IndicConformer int8 | Vosk, Whisper, IndicWhisper, MMS, Android SpeechRecognizer | Accuracy, size, licence, and C1 in that order |
| Streaming | Decode during speech, clause by clause | Streaming model | None exists for these languages; this recovers most of the 4 s |
| Voices | Piper, plus Mimic 3 for Gujarati | Meta MMS | MMS is CC-BY-NC |
| Addressing | None; broadcast | Destination field | A radio does not route; encryption is the real address |
| BLE shape | Advertising, whole buffer, always on | GATT connections | Pairing is the top demonstration failure; a buffer survives a scanner's missed chances |
| Wi-Fi shape | UDP broadcast | TCP plus discovery | Point-to-point is the pairing problem in another costume |
| Wi-Fi Direct | Leaderless election in the app | Manual Settings, or a fixed owner | A phone cannot see its own P2P address; a manual step is a demo failure |
| Nonce | Derived from epoch, source, sequence | Transmitted | 12 bytes a frame |
| TTL | Outside the authenticated data | Inside | A relay holds no key and must decrement it |
| Threads | 2 idle, 4 decoding | 4 always | Faster cold, slower after thermal soak |
| Language | Kotlin, coroutines, Compose | Java, RxJava, Views | Structured cancellation across four long-lived threads; a small team |

---

## 9. WHAT IS LEFT — limits and risks, stated

- Three languages do not speak: Tamil, Kannada and Odia arrive as text. Risk T-05, high,
  open. The worst-case language for a jury's question is Odia, and `DEMO.md` prepares the
  answer.
- Nothing is measured on the target handset yet. Risks P-01 and P-02, both high. The
  handset is unbought.
- Contextual biasing is built but inactive until a transducer export exists.
- Wi-Fi Direct still needs one tap per pair of phones, once; Android offers no way round
  it. Two Wi-Fi Direct groups that both already have members are not merged.
- Relaying with the middle phone's screen off needs relay mode on that phone.
- The pairing screen is deliberately unreachable: there is no in-app key exchange yet, so
  two development handsets share a development key. Audit items 5 and 6 in `SECURITY.md`
  stay open until that flow exists.
- Older lines in a few documents still say the app declares no location permission. It
  has since 2026-09-07, for finding a unit, and for Wi-Fi Direct discovery on Android 12
  and below; the manifest comment and the testing gate say so, and the rest is a
  documentation sweep still to do.

---

## 10. Where to look

| Question | File |
| --- | --- |
| How does a message travel through the code? | `app/.../engine/MessageEngine.kt`, `core-link/.../Session.kt` |
| What is on the wire? | `docs/PROTOCOL.md`, `core-proto/.../Frame.kt` |
| Why is the radio shaped like this? | `docs/TRANSPORT.md`, `core-link/.../BleBroadcastLink.kt` |
| How is the group formed? | `core-link/.../WifiDirectElection.kt` and its test |
| How does relay work and why three mechanisms? | `core-link/.../Relay.kt` |
| What is the threat model? | `docs/SECURITY.md` |
| How is every number measured? | `docs/EVALUATION.md` |
| What happens on stage? | `docs/DEMO.md` |
| What is done and what is not? | `docs/TODO.md`, `docs/RISKS.md` |
