# Licence inventory

Constraint C1 prohibits proprietary voice SDKs. **This file is the evidence of
compliance**, and it is maintained from week 1 rather than assembled in week 8 (risk P-04).

> **CI gate.** A dependency that does not appear in this file fails the build. Adding a
> dependency and adding its row here are the same commit. This is what makes late licence
> discovery impossible rather than merely unlikely.

## Project licence

iTantra is released under **Apache-2.0**, subject to the copyleft note in §6.

## 1. Runtime and inference

| Component | Licence | Role |
| --- | --- | --- |
| k2-fsa / sherpa-onnx | Apache-2.0 | Principal dependency. Recognition, synthesis, VAD and endpointing in one library, with an Android archive and Kotlin bindings |
| microsoft / onnxruntime | MIT | Inference engine beneath sherpa-onnx; XNNPACK acceleration on ARM |
| google-ai-edge / LiteRT | Apache-2.0 | Named in the problem statement; evaluated as an alternative backend |
| pytorch / executorch | BSD-3-Clause | Named in the problem statement; evaluated |
| ggml-org / whisper.cpp | MIT | Accuracy baseline for comparison only |

## 2. Recognition models

| Component | Licence | Role |
| --- | --- | --- |
| AI4Bharat IndicConformer | Permissive | Production acoustic models, all ten languages |
| alphacep / vosk-api | Apache-2.0 | Week-one prototype; schedule insurance |
| NVIDIA / NeMo | Apache-2.0 | ONNX export tooling and fine-tuning recipes |
| k2-fsa / icefall | Apache-2.0 | Streaming Zipformer training recipes, if fine-tuning is undertaken |
| **Meta MMS** | **CC-BY-NC** ⚠ | Coverage gap-filler. **Non-commercial** — see §6 |

## 3. Synthesis

| Component | Licence | Role |
| --- | --- | --- |
| rhasspy / piper and piper-voices | MIT | Primary synthesis voices; already ONNX |
| **espeak-ng** | **GPL-3.0** ⚠ | Phonemisation. **Copyleft** — see §6 |
| idiap / coqui-ai-TTS | MPL-2.0 | Training path for languages without an adequate voice |
| AI4Bharat Indic-TTS | Permissive | Higher-naturalness Indic voices |
| shivammehta25 / Matcha-TTS | MIT | Alternative architecture; benchmarked against Piper |

## 4. Detection, transport and utilities

| Component | Licence | Role |
| --- | --- | --- |
| snakers4 / silero-vad | MIT | Tier-1 voice activity detection |
| WebRTC VAD | BSD-3-Clause | Reference for the tier-0 energy gate |
| xiph / rnnoise | BSD-3-Clause | Noise suppression ahead of recognition |
| google / oboe | Apache-2.0 | Low-latency audio path, if `AudioTrack` proves insufficient |
| facebook / zstd | BSD-3-Clause | Dictionary compression above script packing |

**Transport uses only the Android platform Bluetooth and socket APIs.** No third-party
connectivity SDK is used, and **Google Nearby Connections is deliberately excluded** —
raw sockets instead — because it is not open source and would breach C1.

## 4a. Application framework and platform libraries

Added when the build was scaffolded. All are Apache-2.0, the standard licence for
AndroidX, and none is a voice component, so none touches constraint C1.

| Component | Licence | Role |
| --- | --- | --- |
| kotlinx-coroutines-core / -android | Apache-2.0 | Structured concurrency for the four-thread pipeline |
| androidx.compose (BOM, ui, material3) | Apache-2.0 | User interface |
| androidx.activity:activity-compose | Apache-2.0 | Activity host for Compose |
| androidx.lifecycle:lifecycle-service | Apache-2.0 | Foreground service lifecycle |
| androidx.datastore:datastore-preferences | Apache-2.0 | Typed configuration storage |
| androidx.room (runtime, ktx, compiler) | Apache-2.0 | Outbox for store-and-forward |
| com.google.zxing:core | Apache-2.0 | QR generation and scanning for optical key transfer |

## 5. Reference designs studied

Studied for architecture. **No code is copied from any of these**, which matters because
all three are GPL-3.0 and copying would relicense our application.

| Project | Licence | What it informs |
| --- | --- | --- |
| meshtastic / firmware and Android | GPL-3.0 | The closest existing system: text messaging over LoRa with an Android client across Bluetooth. Informs framing, addressing, relay and duplicate suppression |
| Briar | GPL-3.0 | Robust Bluetooth and Wi-Fi transport on Android: discovery, reconnection, degraded states |
| ge0rg / aprsdroid | GPL-3.0 | Android to amateur radio over Bluetooth serial — the deployment path, in production use |
| android / connectivity-samples | Apache-2.0 | Platform boilerplate for RFCOMM and Wi-Fi Direct |

## 6. Restrictive licences — disclosed, not discovered

Two components carry licences that constrain what may be done with the result. Both are
declared here deliberately. A jury that finds an undisclosed GPL dependency draws a
conclusion about the whole submission; a team that discloses one draws a different one.

### espeak-ng — GPL-3.0

- **Used for:** phonemisation, invoked through sherpa-onnx with its data directory shipped
  in the base installer (~10 MB, shared across all languages).
- **Consequence:** GPL-3.0 is copyleft. Distributing the application with espeak-ng
  bundled obliges us to offer the complete corresponding source of the combined work under
  GPL-3.0. **For an open-source hackathon submission this is entirely acceptable** — the
  source is public anyway.
- **Constraint on the future:** any closed-source or commercially licensed derivative
  would have to replace espeak-ng. A rule-based Indic G2P module is the substitution path,
  and Indic scripts being largely phonetic makes that far more tractable than it would be
  for English.
- **Obligation discharged by:** shipping the licence text in the app's about screen and
  publishing the source.

### Meta MMS — CC-BY-NC

- **Used for:** gap-filling only, where no permissively licensed model exists for a
  language. Expected to apply to **Odia** if the Coqui training path does not complete.
- **Consequence:** **non-commercial**. Any language pack containing an MMS model is marked
  non-commercial in `models/manifest.json`, the application displays that marking, and the
  pack is **excluded from any deployability claim**.
- **Preferred resolution:** train a Coqui VITS Odia voice (MPL-2.0) on the IIT Madras
  IndicTTS data and drop MMS entirely. This is the week-4 assignment under risk T-05.

## 7. Evaluation data

Data licences bind reporting, not distribution — none of these datasets ship with the
application.

| Dataset | Source | Use |
| --- | --- | --- |
| IndicSUPERB / Kathbath | AI4Bharat | Primary WER benchmark across all ten languages |
| IndicVoices | AI4Bharat | Spontaneous rather than read speech — the realistic condition |
| Vaani | Google & IISc | District-level accent coverage; supports the inclusivity claim |
| FLEURS | Google | Figures comparable with published results |
| Common Voice | Mozilla (CC0) | Supplementary validation |
| IndicTTS database | IIT Madras | Voice training data where a gap exists |
| LIMMITS / SYSPIN | IISc | Multi-speaker Indic synthesis corpora |

## 8. Compliance statement

> No proprietary, closed-source or commercial voice-activation SDK is used anywhere in the
> system. Every model executes on the device. No network request is made at runtime, and
> the shipped manifest declares no `INTERNET` permission, so the property is enforced by
> the platform rather than asserted by us.
>
> Two components carry restrictive licences — espeak-ng under GPL-3.0 and Meta MMS under
> CC-BY-NC — and both are disclosed above, with their consequences, rather than left to be
> discovered.

## 9. Adding a dependency

1. Confirm the licence permits use in an Apache-2.0 open-source Android application.
2. Add a row to the correct section above, with the licence and the role.
3. If the licence is copyleft or non-commercial, add a subsection to §6 explaining the
   consequence and the substitution path. Do not add it silently.
4. Add the licence text to the app's about screen.
5. The CI licence audit will otherwise fail the build.
