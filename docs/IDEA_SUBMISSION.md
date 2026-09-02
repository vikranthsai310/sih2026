# SIH idea submission — slide content

Content for `Doc/SIH2026-IDEA-Presentation-Format (1).pptx`. Six slides maximum including
the title slide; the template's instruction slide is deleted before upload; the file is
uploaded as **PDF**, not PPT.

Portal rules, from the template itself:

- Maximum **six slides** including the title slide
- Use the provided template without changing the idea-detail pointers
- **Avoid paragraphs** — points, diagrams, infographics, pictures
- Save as **PDF**; no PPT, DOC or other format is accepted

The single most common way to lose marks here is prose. Everything below is written as
points and figures on purpose. If a line does not fit on one line of the slide, cut it.

---

## Slide 1 — Title

| Field | Value |
| --- | --- |
| Problem Statement ID | **26173** |
| Problem Statement Title | Development of lightweight multilingual STT and TTS for low-bandwidth voice communication |
| Theme | Space Technology / Disaster Management |
| PS Category | **Software** |
| Team ID | _____________ |
| Team Name | _____________ |

Put the product name and the one-line thesis on this slide if the template leaves room:

> **iTantra** — speech in, speech out, a few dozen bytes in between.

---

## Slide 2 — Idea / Proposed solution

**Headline:** *We are not transmitting audio. We are transmitting meaning.*

**The problem, in three numbers** — make this a bar, not a sentence:

```
 256 000 bps   raw speech
   6 000 bps   Opus floor — the best any codec can do
     300 bps   the radio link that actually works in a disaster
```

**The solution**

- Speech → text **on the sending phone**
- A few dozen bytes cross the radio link
- Text → speech **on the receiving phone**
- Both people only speak and listen — **neither reads nor types**

**How it addresses the problem**

- 3 s of Hindi speech: **96 000 B → 45 B**, a **2 133×** reduction
- Fits LoRa, HF and narrowband satellite links, on which voice is impossible
- 10 Indian languages · fully offline · no cloud, no SIM, no proprietary SDK
- Voice at both ends because the people who most need it **cannot type**: literacy, gloves,
  darkness, stress, ten scripts

**Innovation and uniqueness**

- Compresses **meaning**, not the waveform — a different problem from a better codec
- Three-level graceful degradation: semantic → script packing → 1-byte template codes
- **Cross-language delivery for free**: a Tamil speaker's alert is announced in Hindi, with
  no translation model
- The same code that talks to a phone talks to a radio — RFCOMM is a serial cable

**Visual for this slide:** the two-phone signal path with `45 B` on the arrow between them.

---

## Slide 3 — Technical approach

**Technologies**

| Layer | Choice |
| --- | --- |
| Platform | Android, Kotlin, Jetpack Compose |
| Inference | ONNX Runtime via sherpa-onnx (Apache-2.0) |
| Recognition | AI4Bharat IndicConformer, int8 quantised, ~35 MB/language |
| Synthesis | Piper VITS voices (MIT), streaming chunked playout |
| Detection | Silero VAD (MIT), 1.8 MB |
| Transport | Bluetooth RFCOMM · BLE GATT · Wi-Fi · serial → LoRa (SX1276, 865.5 MHz) |
| Security | AES-256-GCM per group, replay window, QR provisioning |

**Methodology** — one flow diagram, not text:

```
 Mic → VAD (3 tiers) → Conformer ASR → endpoint → template match / script pack
     → 11-byte frame + AES-GCM + CRC → [ BT · BLE · Wi-Fi · LoRa ]
     → verify → unpack → normalise → phonemise → VITS → streaming audio
```

**Efficiency, in one line each**

- Three-tier VAD: energy gate → Silero → Conformer. Idle CPU **< 2 %**
- int8 quantisation: **4× smaller, 2.5× faster, ~1 % relative WER cost**
- Chunked synthesis: first audio at **180 ms** instead of 700 ms
- On-demand language packs: **< 30 MB installer**, two languages resident

---

## Slide 4 — Feasibility and viability

**Feasibility**

- Every component exists today, is open source, and runs on ARM — nothing is being invented
- Reference systems prove the transport: Meshtastic, Briar, APRSdroid
- 8-week plan; **the end-to-end loop closes in week 3** on prototype models
- Target hardware is a **4 GB entry-tier handset**, not a flagship

**Challenges and strategies** — the table is the slide:

| Challenge | Strategy |
| --- | --- |
| Accuracy collapses in real noise | RNNoise pre-processing; evaluated and reported at **four SNRs**, not clean only |
| Digits and callsigns misrecognised | Contextual biasing with a domain lexicon and gazetteer; separate critical-term metric |
| Thermal throttling on entry-tier silicon | 30-minute soak testing; **sustained figures reported**, not peak |
| No adequate open Odia voice | Coqui VITS trained on IIT Madras IndicTTS data; assigned week 4, not week 7 |
| Model work eating the schedule | Loop first with weak models; quality is an upgrade path, never a prerequisite |
| Fraudulent alert injection | AES-256-GCM per group; sender identity inside the authenticated region |

**Viability**

- Zero running cost — no servers, no data plan, no per-message fee
- Deploys on existing handsets plus ~₹1 500 of radio hardware per pair
- Every dependency is permissively licensed; the two restrictive ones are disclosed

---

## Slide 5 — Impact and benefits

**Target users**

- Disaster relief and NDRF teams where towers are down
- Armed forces and border patrols on narrowband links
- Fishermen and coastal communities beyond cellular range
- Remote medical and forest patrols
- ISRO ground teams on low-bitrate satellite links

**Social**

- **Works for people who cannot read or write** — the interface is voice at both ends
- Ten Indian languages, ten scripts, one interaction model
- Cross-language operation: a Tamil speaker's alert reaches a Hindi speaker in Hindi
- Usable with gloves, in darkness, with the screen off

**Economic**

- No infrastructure, no spectrum licence, no subscription
- Runs on handsets people already own
- ~₹1 500 per radio pair converts phone-to-phone into 2–15 km coverage

**Operational**

- Sub-second delivery: **500–800 ms** end to end in push-to-talk mode
- Alerts wake a locked, silenced handset and announce at full volume
- 2 133× compression puts voice communication on links that could never carry it

**One-sentence close:** *On the links that work when infrastructure does not, audio is
impossible and iTantra is comfortable. That gap is the product.*

---

## Slide 6 — Research and references

Keep these as links, one line each.

**Models and runtime**

- sherpa-onnx — `github.com/k2-fsa/sherpa-onnx` (Apache-2.0)
- AI4Bharat IndicConformer and Indic-TTS — `ai4bharat.iitm.ac.in`
- Piper TTS voices — `github.com/rhasspy/piper` (MIT)
- Silero VAD — `github.com/snakers4/silero-vad` (MIT)
- ONNX Runtime — `github.com/microsoft/onnxruntime` (MIT)

**Benchmarks**

- IndicSUPERB / Kathbath, IndicVoices — AI4Bharat
- FLEURS — Google Research
- Vaani — Google & IISc
- IndicTTS database — IIT Madras

**Reference systems studied**

- Meshtastic — text over LoRa with an Android client (GPL-3.0)
- Briar — robust Bluetooth and Wi-Fi transport on Android (GPL-3.0)
- APRSdroid — Android to amateur radio over Bluetooth serial (GPL-3.0)

**Standards**

- Bluetooth SPP/RFCOMM; Bluetooth LE GATT
- LoRaWAN IN865 band plan, 865–867 MHz
- NIST SP 800-38D — AES-GCM

---

## Preparation notes

1. **Delete the instructions slide** (slide 7 of the template) before exporting.
2. **Export to PDF.** The portal accepts nothing else.
3. Slide 2 carries the argument. If a reviewer reads only one slide, that is the one — put
   the three-number bar chart on it and make it the largest thing on the page.
4. Replace every claim with a **measured** figure once the scorecard exists. Until then,
   the numbers above are design targets and should be described as targets if asked.
5. Do not add a seventh slide. The limit is enforced.
