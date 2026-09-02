# Glossary

| Term | Definition |
| --- | --- |
| **AEAD** | Authenticated encryption with associated data — encryption that also detects any modification to both the ciphertext and an attached plaintext header. AES-GCM is the AEAD used here |
| **ASR / STT** | Automatic speech recognition — converting audio into text |
| **Barge-in** | Detecting that the local user has begun speaking while output is playing, and ducking or halting that output |
| **Beam search** | A decoding strategy that keeps several candidate token sequences alive and selects the most probable, rather than committing greedily at each step |
| **Contextual biasing** | Boosting decoding scores for a supplied phrase list, improving recognition of known domain vocabulary without retraining |
| **Conformer** | An acoustic model architecture combining convolution for local acoustic detail with self-attention for long-range context |
| **CRC-16** | A 16-bit cyclic redundancy check; detects transmission corruption. An integrity check, never a security control |
| **CTER** | Critical-term error rate — the proportion of operationally critical words (मदद, आग, callsigns, sector numbers) that fail to survive recognition. A better proxy for usefulness than overall WER |
| **Duty cycle** | The proportion of elapsed time a transmitter is active. Regulated in the 865–867 MHz LoRa band |
| **Endpointing** | Deciding that an utterance has finished, so that a final result can be emitted |
| **EPOCH** | A per-sender counter, incremented on every `SEQ` wrap and every service start, that guarantees the AEAD nonce is never reused |
| **Frame** | One complete protocol message: 11-byte header, payload, 2-byte CRC |
| **GATT** | The Bluetooth Low Energy attribute protocol; data is exchanged through named characteristics rather than a stream |
| **Half / full duplex** | Half duplex permits one direction at a time — the walkie-talkie mode. Full duplex permits both simultaneously — the telephone mode |
| **Hotword** | A phrase supplied to the decoder for score boosting. Synonymous here with a biasing term |
| **LoRa** | A long-range, low-bitrate radio modulation operating licence-free in India at 865–867 MHz |
| **Mel spectrogram** | A time-by-frequency representation of audio on a perceptually spaced frequency scale; the standard input to acoustic models |
| **MOS** | Mean opinion score — subjective synthesis quality on a 5-point scale, averaged over a listening panel. Meaningless without its panel size |
| **MTU** | Maximum transmission unit — the largest payload a single packet can carry |
| **NFC (Unicode)** | Normalization Form C — the canonical composed form of a Unicode string. Applied before packing and before template matching so that visually identical strings compare equal. Not to be confused with near-field communication |
| **Nonce** | A number used once. Under AES-GCM, reusing one with the same key destroys the security of every message under that key |
| **ONNX** | An open interchange format for neural networks, executable by ONNX Runtime on ARM |
| **Opus** | The reference open-source speech and audio codec. Its ~6 kbps intelligibility floor is the bound this project is designed to circumvent |
| **PCM** | Pulse-code modulation — uncompressed audio samples. 16 kHz 16-bit mono is 256 kbps |
| **Phonemisation** | Converting orthography into a sequence of phonemes — मदद to /m ə d ə d/ — before synthesis |
| **PTT** | Push-to-talk. A physical or on-screen control that seizes the channel while held |
| **Quantisation** | Re-encoding model weights at lower precision — typically 8-bit integer — for smaller size and faster inference |
| **Replay window** | A record of recently accepted sequence numbers, preventing a captured frame from being retransmitted by an attacker |
| **RFCOMM / SPP** | The Bluetooth serial port profile — a virtual serial cable, and the same interface a radio module presents |
| **RTF** | Real-time factor — processing time divided by audio duration. Below 1.0 is faster than real time |
| **Script packing** | Level 2 compression: remapping a declared Indic Unicode block to a single-byte alphabet, three bytes to one |
| **Seen-set** | A bounded record of recently relayed `(SRC, EPOCH, SEQ)` triples, used to suppress duplicate forwarding and prevent broadcast storms |
| **SNR** | Signal-to-noise ratio in decibels; the standard way of specifying how adverse an acoustic test condition is |
| **Soak** | Sustained continuous operation, typically 30 minutes, after which performance figures are taken. Cold-device figures are not reportable |
| **Template code** | Level 3 compression: a one-byte identifier for a canned operational sentence, rendered in the receiver's own language |
| **Transducer / CTC** | Decoding schemes that map a sequence of frame predictions onto a shorter sequence of output tokens |
| **TTL** | Time to live — a hop counter that bounds how far a relayed frame may travel |
| **TTS** | Text to speech — converting text into an audio waveform |
| **VAD** | Voice activity detection — deciding whether a short window of audio contains human speech |
| **VITS** | A synthesis architecture generating waveforms directly from text in a single pass, including duration modelling |
| **WER** | Word error rate — insertions plus deletions plus substitutions, divided by reference word count. Lower is better |
| **XNNPACK** | The ARM-optimised CPU execution provider used beneath ONNX Runtime |
| **ZWJ / ZWNJ** | Zero-width joiner and non-joiner, `U+200D` and `U+200C`. Invisible characters that control conjunct formation in Indic scripts. They sit outside every Indic Unicode block, which is why script packing needs an escape |
