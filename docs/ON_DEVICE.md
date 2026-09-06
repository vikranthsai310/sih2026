# Running it on handsets

What works on real phones today, how to get it there, and — as precisely as possible —
what does **not** work yet and why.

## 1. What this build actually does

Install on two or more Android phones, open the app. Pressing the transmit control sends a
message that travels the **entire real path**:

```
  microphone ─► IndicConformer (ONNX Runtime, on the handset)
             ─► template match ─► seal (AES-256-GCM, tag length chosen by the transport)
             ─► frame (12 B header + CRC) ─► BLE broadcast and/or Wi-Fi broadcast
             ─► CRC ─► KEYID ─► AEAD verify ─► replay window
             ─► render in the RECEIVER'S language ─► Piper voice ─► band E
```

Every unit receives every frame — there is no destination field — and a unit out of direct
range is reached by another rebroadcasting, TTL 3.

**Speech is in this build, at both ends.** `SherpaSpeech` (IndicConformer, int8, via
ONNX Runtime) and `Speaker` (Piper) are both constructed in `MainActivity`. This section
used to say the opposite — *"what is not in this build: speech, at either end"* — and that
stopped being true when the recogniser and the voice were wired. Corrected 2026-09-06.

**What still limits it,** stated precisely, because these are the things a demonstration
runs into:

- **A handset with no language pack installed does not speak and does not recognise.** The
  packs are ~198 MB per language and are not in the installer — constraint N2 caps it at
  30 MB. See §2 and the in-app pack importer. A judge's phone straight from the Play-less
  APK will show the UI and hear nothing until a pack is imported.
- **Three of the ten languages have no voice.** Tamil, Kannada and Odia carry
  `"tts": null` in `models/manifest.json`: no permissively licensed voice exists for them in
  any family sherpa-onnx packages, and Meta MMS was refused because it is CC-BY-NC. Those
  three **recognise and display** but do not speak free-form text. A *template-coded*
  message still reaches them in their own language, because the table is held in all ten.
  This is the largest genuine requirement gap in the project and it is named in
  [MODELS.md](MODELS.md) and [DEMO.md](DEMO.md) rather than left to be discovered.
  Gujarati was in this set until 2026-09-06, when a Mimic 3 CMU Indic voice was found for
  it under a permissive licence — seven of ten now speak.
- ~~The hashes in `models/manifest.json` are all-zero placeholders.~~ **Fixed 2026-09-06.**
  All seventeen are real now: the six Piper voices take theirs from
  `models/install-index.json`, the ten vocabularies are hashed from the files they name, and
  the shared-encoder block is gone because the artefact it described was never published.
  `tools/build_manifest_hashes.py --verify` re-checks it, and `Manifest.requireSha256` now
  actually rejects an all-zero hash — it never did, which is how seventeen of them survived
  a guard written to stop exactly that.

That is the transport, the cryptography, the recognition, the synthesis and the
cross-language delivery, on real radios and real handsets.

## 2. Build and install

```
tools/fetch_sherpa.sh                 # once, ~47 MB
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install the **same APK** on every handset. Different builds mean different template tables,
and a table mismatch is a safety defect rather than an inconvenience — see
[PROTOCOL.md §5.2](PROTOCOL.md#52-profile-binding).

## 3. Before opening the app

1. **Bond the handsets in Android's Bluetooth settings**, every pair, before launching.
   RFCOMM connects to bonded devices only; the app does not pair them for you (that is
   W6.11).
2. **Give the handsets different Bluetooth names** if they share one — two phones of the
   same model out of the box often do. The name is what the two units use to agree which
   of them dials and which listens, so with identical names both dial, both connect, and
   one of the two sockets is thrown away. It still works; it is simply wasteful. See
   `PeerPreference`.
3. Grant **Nearby devices** and **Microphone** when asked. Microphone is not used yet and is
   requested once so the permission flow is exercised. If you refuse, Android will not ask
   again — the banner then points you at Settings, which is the only route back.
4. Bluetooth on, aeroplane mode **on** if you want to prove there is no network involved.

## 4. What you should see

| On screen | Meaning |
| --- | --- |
| `BASE · node 07` | This unit's id, derived from the installation id. **Every handset must show a different number**. `node 00` is a value the id derivation cannot produce, so it means the engine never started |
| `● LINK OK` | At least one peer is connected |
| `2 units` | Peers currently reachable, re-read once a second |
| A coloured banner | Something is wrong *and* what to do about it. The four you will meet first are Bluetooth off, nobody paired, permission refused, and reconnecting — they look identical without the banner, and only one of them fixes itself |
| Band F `29 B 3310×` | The last frame's real size, and its ratio against three seconds of audio |

Hold **PUSH TO TALK** on one handset and let go. It sends on release, the way a real
walkie-talkie does. Within about a second the sentence appears in band E on the others, and
band F shows the frame size on both.

### The one worth showing a jury

Tap the language in band B on one handset until it reads **தமிழ்**. Leave the other on
**हिन्दी**. Now press transmit.

The same byte arrives and each handset renders it in **its own** language. Nothing
translated anything — both hold the same 24-sentence table in ten languages, and the sender
transmitted one byte of payload. That is the cross-language claim, demonstrated rather than
asserted.

### Three or more handsets

Every unit listens on one service socket and dials the units it is paired with, so a net of
four is six connections and no configuration. Where two units cannot reach each other
directly, **relay** carries the message: a frame from C that cannot reach A arrives via B,
and the seen-set stops it looping. Watch band A — a unit can show `LINK OK` with fewer peers
than there are handsets in the room and still receive everything.

## 5. What will not work, and why

| Symptom | Cause |
| --- | --- |
| `Bluetooth is off` | Exactly that. Turn it on and return to the app — it retries on resume |
| `No other unit paired` | Nothing bonded that could be a handset. Pair the other phone in Bluetooth settings |
| `Link down — reconnecting` | Bonded, but not answering: the other handset is out of range or does not have iTantra open. This one really does retry by itself |
| `Nearby devices permission refused` | Android will not ask twice. Settings → Apps → iTantra → Permissions |
| Two handsets show the **same** node number | A 1-in-254 id collision. Both drop each other's frames as their own transmission, so the net reads `LINK OK` and stays silent. Nothing detects this yet — clear app data on one handset to re-derive its id |
| Nothing is spoken aloud | Expected. No voice models — see §1 |
| Band F latency shows `—` | Expected. Nothing measures a stage yet |
| A message appears twice | Should not happen; the replay window and relay seen-set both prevent it. If it does, capture the frame and add it to the fuzz corpus (C.7) |

## 6. What is unsafe about this build

**The key is not a secret.** Until pairing exists (W6.11) every unit built from this
repository holds the same fixed development key, and it is written in
`NodeIdentity.developmentKey()` in plain sight. Frames are authenticated against corruption
and against nothing else. Do not use this build anywhere real, and do not describe it as
secure — `SECURITY.md` audit item 5 stays open for exactly this reason.

## 7. Where the numbers come from

Nothing on screen is typed in. The frame size in band F is `wire.size` from the frame that
was actually transmitted, and the ratio beside it is that against 96 000 B — three seconds
of 16 kHz 16-bit mono. The same four figures are computed from the frame codec in
`bench` `CompressionRatios` and checked against `docs/` by `tools/check_doc_numbers.py`.
